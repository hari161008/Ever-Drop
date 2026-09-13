package com.sameerasw.medrop.utils

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import androidx.core.content.FileProvider
import com.sameerasw.medrop.domain.model.EverDropItem
import com.sameerasw.medrop.domain.model.MeDropSettings
import com.sameerasw.medrop.services.MeDropHceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object MeDropNfcManager {

    fun isNfcAvailable(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context) != null

    fun isNfcEnabled(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    /**
     * Starts NFC HCE broadcast for the foreground activity.
     * Re-arms current active share (FILE, TEXT, or CONTACT) and registers MeDropHceService
     * as the preferred payment/APDU handler for this activity.
     */
    suspend fun startBroadcast(activity: Activity, settings: MeDropSettings) {
        val context = activity.applicationContext

        MeDropHceService.isSharingAllowed = true

        // Re-arm active share payload (preserving FILE/TEXT priority over contact)
        EverDropNfcShareManager.rearmActiveShare(context, settings)

        withContext(Dispatchers.Main) {
            val component = ComponentName(context, MeDropHceService::class.java)
            val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
            if (nfcAdapter != null && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    val cardEmulation = CardEmulation.getInstance(nfcAdapter)
                    cardEmulation.setPreferredService(activity, component)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Pauses NFC HCE preferred service binding for the activity.
     * Note: We DO NOT call clearShare() here so that staged files or text are NOT lost
     * when the activity briefly pauses (e.g. file picker opened, share sheet active).
     */
    suspend fun stopBroadcast(activity: Activity) {
        val context = activity.applicationContext
        MeDropHceService.isSharingAllowed = false

        withContext(Dispatchers.Main) {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
            if (nfcAdapter != null) {
                try {
                    val cardEmulation = CardEmulation.getInstance(nfcAdapter)
                    cardEmulation.unsetPreferredService(activity)
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun startBroadcast(context: Context, settings: MeDropSettings) {
        if (context is Activity) {
            startBroadcast(context, settings)
        } else {
            EverDropNfcShareManager.rearmActiveShare(context, settings)
        }
    }

    suspend fun stopBroadcast(context: Context) {
        if (context is Activity) {
            stopBroadcast(context)
        }
    }

    fun parseNdefMessage(context: Context, ndefMessage: NdefMessage): EverDropItem? {
        for (record in ndefMessage.records) {
            val payload = record.payload ?: continue
            val mimeType = try { record.toMimeType() } catch (_: Exception) { null }

            // 1. Check for Contact (vCard)
            if (mimeType.equals("text/vcard", ignoreCase = true) ||
                mimeType.equals("text/x-vcard", ignoreCase = true)) {
                val text = String(payload, Charsets.UTF_8)
                val start = text.indexOf("BEGIN:VCARD", ignoreCase = true)
                val clean = if (start != -1) text.substring(start) else text
                val parsed = VCardParser.parse(clean)
                return EverDropItem.Contact(clean, parsed)
            }

            // 2. Check for EverDrop File
            if (mimeType.equals("application/vnd.everdrop.file", ignoreCase = true)) {
                try {
                    val jsonStr = String(payload, Charsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    val name = json.getString("name")
                    val fileMime = json.optString("mimeType", "*/*")
                    val size = json.optLong("size", 0L)
                    val base64Data = if (json.has("data")) json.getString("data") else null
                    var savedUri: String? = null
                    if (!base64Data.isNullOrBlank()) {
                        try {
                            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            val uri = EverDropFileManager.saveFileToEverShare(context, name, fileMime, bytes)
                            savedUri = uri?.toString()
                        } catch (_: Exception) {}
                    }
                    return EverDropItem.FileItem(name, fileMime, size, base64Data, savedUri)
                } catch (_: Exception) {}
            }

            // 3. Check for Plain Text
            if (mimeType.equals("text/plain", ignoreCase = true)) {
                val text = String(payload, Charsets.UTF_8)
                if (text.contains("BEGIN:VCARD", ignoreCase = true)) {
                    val start = text.indexOf("BEGIN:VCARD", ignoreCase = true)
                    val clean = text.substring(start)
                    val parsed = VCardParser.parse(clean)
                    return EverDropItem.Contact(clean, parsed)
                }
                return EverDropItem.Text(text)
            }

            // 4. Check for NDEF Text Record (TNF_WELL_KNOWN + RTD_TEXT)
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                val text = try {
                    val statusByte = payload[0].toInt()
                    val langLen = statusByte and 0x3F
                    val isUtf16 = (statusByte and 0x80) != 0
                    val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
                    String(payload, 1 + langLen, payload.size - 1 - langLen, charset)
                } catch (_: Exception) {
                    String(payload, Charsets.UTF_8)
                }
                if (text.contains("BEGIN:VCARD", ignoreCase = true)) {
                    val start = text.indexOf("BEGIN:VCARD", ignoreCase = true)
                    val clean = text.substring(start)
                    val parsed = VCardParser.parse(clean)
                    return EverDropItem.Contact(clean, parsed)
                }
                return EverDropItem.Text(text)
            }

            // Fallback: Inspect raw string content
            val raw = String(payload, Charsets.UTF_8)
            if (raw.contains("BEGIN:VCARD", ignoreCase = true)) {
                val start = raw.indexOf("BEGIN:VCARD", ignoreCase = true)
                val clean = raw.substring(start)
                val parsed = VCardParser.parse(clean)
                return EverDropItem.Contact(clean, parsed)
            }
            if (raw.isNotBlank()) {
                return EverDropItem.Text(raw)
            }
        }
        return null
    }

    fun enableReaderMode(activity: Activity, onItemReceived: (EverDropItem) -> Unit) {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        try {
            nfcAdapter.enableReaderMode(activity, { tag ->
                try {
                    // Try reading standard NDEF first
                    val ndef = android.nfc.tech.Ndef.get(tag)
                    if (ndef != null) {
                        ndef.connect()
                        val ndefMessage = ndef.ndefMessage
                        ndef.close()
                        if (ndefMessage != null) {
                            val item = parseNdefMessage(activity.applicationContext, ndefMessage)
                            if (item != null) {
                                activity.runOnUiThread {
                                    onItemReceived(item)
                                }
                                return@enableReaderMode
                            }
                        }
                    }

                    // Fallback to ISO-DEP APDU commands for Ever Drop HCE transmitters
                    val isoDep = android.nfc.tech.IsoDep.get(tag)
                    if (isoDep != null) {
                        isoDep.connect()
                        // Select NDEF Application AID: D2 76 00 00 85 01 01
                        val selectAid = byteArrayOf(
                            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(),
                            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x01.toByte()
                        )
                        val respAid = isoDep.transceive(selectAid)
                        if (respAid.size >= 2 && respAid[respAid.size - 2] == 0x90.toByte()) {
                            // Select NDEF file (0xE1, 0x04)
                            val selectFile = byteArrayOf(
                                0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x02.toByte(),
                                0xE1.toByte(), 0x04.toByte()
                            )
                            val respFile = isoDep.transceive(selectFile)
                            if (respFile.size >= 2 && respFile[respFile.size - 2] == 0x90.toByte()) {
                                // Read NLEN (first 2 bytes)
                                val readNlen = byteArrayOf(0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte())
                                val nlenResp = isoDep.transceive(readNlen)
                                if (nlenResp.size >= 4 && nlenResp[nlenResp.size - 2] == 0x90.toByte()) {
                                    val nlen = ((nlenResp[0].toInt() and 0xFF) shl 8) or (nlenResp[1].toInt() and 0xFF)
                                    if (nlen in 1..65535) {
                                        var offset = 2
                                        var remaining = nlen
                                        val fullData = java.io.ByteArrayOutputStream()
                                        while (remaining > 0) {
                                            val chunkSize = minOf(remaining, 240)
                                            val readChunk = byteArrayOf(
                                                0x00.toByte(), 0xB0.toByte(),
                                                ((offset shr 8) and 0xFF).toByte(),
                                                (offset and 0xFF).toByte(),
                                                (chunkSize and 0xFF).toByte()
                                            )
                                            val chunkResp = isoDep.transceive(readChunk)
                                            if (chunkResp.size >= 2 && chunkResp[chunkResp.size - 2] == 0x90.toByte()) {
                                                fullData.write(chunkResp, 0, chunkResp.size - 2)
                                                offset += chunkSize
                                                remaining -= chunkSize
                                            } else {
                                                break
                                            }
                                        }
                                        val fullNdefBytes = fullData.toByteArray()
                                        if (fullNdefBytes.isNotEmpty()) {
                                            try {
                                                val msg = android.nfc.NdefMessage(fullNdefBytes)
                                                val item = parseNdefMessage(activity.applicationContext, msg)
                                                if (item != null) {
                                                    activity.runOnUiThread {
                                                        onItemReceived(item)
                                                    }
                                                    isoDep.close()
                                                    return@enableReaderMode
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                            }
                        }
                        isoDep.close()
                    }
                } catch (_: Exception) {}
            }, flags, null)
        } catch (_: Exception) {}
    }

    // Overload for backward compatibility
    fun enableReaderModeVCard(activity: Activity, onVCardReceived: (String) -> Unit) {
        enableReaderMode(activity) { item: EverDropItem ->
            if (item is EverDropItem.Contact) {
                onVCardReceived(item.vcard)
            }
        }
    }

    fun disableReaderMode(activity: Activity) {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        try {
            nfcAdapter.disableReaderMode(activity)
        } catch (_: Exception) {}
    }
}
