package com.sameerasw.medrop.ui.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sameerasw.medrop.domain.model.EverDropItem
import com.sameerasw.medrop.ui.sheets.ReceivedContactBottomSheet
import com.sameerasw.medrop.ui.theme.MeDropTheme
import com.sameerasw.medrop.utils.EverDropFileManager
import com.sameerasw.medrop.utils.ReceivedContact
import com.sameerasw.medrop.utils.VCardParser
import com.sameerasw.medrop.viewmodels.MeDropViewModel

class ReceivedContactActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        var parsedItem: com.sameerasw.medrop.domain.model.EverDropItem? = null
        intent?.let { int ->
            if (int.action == android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED) {
                val rawMsgs = int.getParcelableArrayExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES)
                if (rawMsgs != null) {
                    for (raw in rawMsgs) {
                        val msg = raw as? android.nfc.NdefMessage ?: continue
                        val item = com.sameerasw.medrop.utils.MeDropNfcManager.parseNdefMessage(applicationContext, msg)
                        if (item != null) {
                            parsedItem = item
                            break
                        }
                    }
                }
            }
        }

        if (parsedItem == null) {
            finish()
            return
        }

        // Direct handling for Text and File without opening the app window
        when (parsedItem) {
            is EverDropItem.Text -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Received Text", parsedItem.text))
                Toast.makeText(applicationContext, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            is EverDropItem.FileItem -> {
                if (parsedItem.localSavedUri == null && !parsedItem.base64Data.isNullOrBlank()) {
                    try {
                        val bytes = android.util.Base64.decode(parsedItem.base64Data, android.util.Base64.DEFAULT)
                        EverDropFileManager.saveFileToEverShare(
                            applicationContext,
                            parsedItem.name,
                            parsedItem.mimeType,
                            bytes
                        )
                    } catch (_: Exception) {}
                }
                Toast.makeText(
                    applicationContext,
                    "Saved to Downloads/Ever Share: ${parsedItem.name}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }
            is EverDropItem.Contact -> {
                // Proceed below to display ReceivedContactBottomSheet
            }
        }

        setContent {
            val mainViewModel: MeDropViewModel = viewModel()
            val isPitchBlackThemeEnabled by mainViewModel.isPitchBlackThemeEnabled
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                mainViewModel.check(context)
            }

            var itemToDisplay by remember { mutableStateOf<EverDropItem?>(parsedItem) }

            MeDropTheme(pitchBlackTheme = isPitchBlackThemeEnabled) {
                val item = itemToDisplay
                if (item is EverDropItem.Contact) {
                    val contact = item.parsed ?: VCardParser.parse(item.vcard)
                    if (contact != null) {
                        ReceivedContactBottomSheet(
                            contact = contact,
                            onDismissRequest = {
                                itemToDisplay = null
                                finish()
                            }
                        )
                    } else {
                        finish()
                    }
                } else {
                    finish()
                }
            }
        }
    }
}
