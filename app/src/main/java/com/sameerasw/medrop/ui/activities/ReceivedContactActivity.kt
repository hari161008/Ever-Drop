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
class ReceivedContactActivity : ComponentActivity() {

    private val currentItem = mutableStateOf<EverDropItem?>(null)
    private var uiInitialized = false

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

        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(int: android.content.Intent?) {
        if (int == null) return

        var parsedItem: EverDropItem? = null
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

        if (parsedItem == null) {
            if (currentItem.value == null) {
                finish()
            }
            return
        }

        // Direct handling for Text and File without keeping window open
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
                currentItem.value = parsedItem
                setupUI()
            }
        }
    }

    private fun setupUI() {
        if (uiInitialized) return
        uiInitialized = true

        setContent {
            val mainViewModel: com.sameerasw.medrop.viewmodels.MeDropViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val isPitchBlackThemeEnabled by mainViewModel.isPitchBlackThemeEnabled
            val context = androidx.compose.ui.platform.LocalContext.current

            androidx.compose.runtime.LaunchedEffect(Unit) {
                mainViewModel.check(context)
            }

            val item by currentItem
            com.sameerasw.medrop.ui.theme.MeDropTheme(pitchBlackTheme = isPitchBlackThemeEnabled) {
                if (item is EverDropItem.Contact) {
                    val contact = (item as EverDropItem.Contact).parsed ?: com.sameerasw.medrop.utils.VCardParser.parse((item as EverDropItem.Contact).vcard)
                    if (contact != null) {
                        ReceivedContactBottomSheet(
                            contact = contact,
                            onDismissRequest = {
                                currentItem.value = null
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
