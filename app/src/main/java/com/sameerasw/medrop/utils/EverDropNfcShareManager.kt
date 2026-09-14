package com.sameerasw.medrop.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.sameerasw.medrop.data.repository.MeDropRepository
import com.sameerasw.medrop.domain.model.MeDropSettings
import com.sameerasw.medrop.services.MeDropHceService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class ShareTargetType {
    NONE,
    TEXT,
    FILE,
    CONTACT
}

/**
 * EverDropNfcShareManager
 *
 * Core coordinator for deciding WHAT payload is currently being beamed over NFC HCE:
 * - Priority Hierarchy:
 *     1. FILE   (if a file is selected / shared from share sheet)
 *     2. TEXT   (if text is typed in the box / shared from share sheet)
 *     3. CONTACT (default fallback ONLY when neither file nor text is present)
 *
 * Rule: If a file or text is being shared, NFC HCE MUST ONLY beam that item and NEVER
 * default or fall back to the contact card until both file and text are explicitly cleared.
 */
object EverDropNfcShareManager {

    private val _activeShareType = MutableStateFlow(ShareTargetType.NONE)
    val activeShareType: StateFlow<ShareTargetType> = _activeShareType.asStateFlow()

    private val _activeShareDetail = MutableStateFlow<String?>(null)
    val activeShareDetail: StateFlow<String?> = _activeShareDetail.asStateFlow()

    // Cached file and text payload info for instant re-arming without re-reading disks
    private var stagedFilePayloadJson: String? = null
    private var stagedTextPayload: String? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Staging text to be beamed over NFC.
     * Sets active target to TEXT and dispatches NDEF text record to HCE service.
     */
    fun shareText(context: Context, text: String) {
        if (text.isBlank()) {
            stagedTextPayload = null
            revertToDefault(context)
            return
        }
        stagedTextPayload = text
        _activeShareType.value = ShareTargetType.TEXT
        val preview = if (text.length > 30) text.take(30) + "…" else text
        _activeShareDetail.value = preview
        MeDropHceService.prepareText(text)
    }

    /**
     * Staging a file to be beamed over NFC.
     * Sets active target to FILE and dispatches NDEF file record (with embedded base64 if <= 50KB) to HCE.
     */
    fun shareFile(context: Context, uri: Uri, name: String, mimeType: String, size: Long) {
        _activeShareType.value = ShareTargetType.FILE
        _activeShareDetail.value = name

        scope.launch {
            var base64Data: String? = null
            try {
                // If small enough for NFC payload (under 50KB), embed data directly into NDEF
                if (size in 1..(50 * 1024)) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
            } catch (_: Exception) {}

            val myAddress = EverDropWifiDirectManager.thisDeviceAddress.value
            val myName = EverDropWifiDirectManager.thisDeviceName.value

            val json = JSONObject().apply {
                put("name", name)
                put("mimeType", mimeType)
                put("size", size)
                if (base64Data != null) {
                    put("data", base64Data)
                }
                if (myAddress.isNotBlank()) {
                    put("address", myAddress)
                    put("deviceName", myName)
                    put("type", "FILE")
                }
            }.toString()

            stagedFilePayloadJson = json
            if (base64Data != null) {
                MeDropHceService.prepareFile(json)
            } else {
                MeDropHceService.prepareP2pHandover(json)
            }
        }
    }

    /**
     * Share user's contact card.
     * STRICT GUARD: If a file or text is currently active, DO NOT share or overwrite with contact card!
     */
    fun shareContact(context: Context, settings: MeDropSettings?) {
        // Enforce strict priority: Never beam contact card if file or text is staged/active!
        if (_activeShareType.value == ShareTargetType.FILE || _activeShareType.value == ShareTargetType.TEXT) {
            return
        }

        val safeSettings = settings ?: return
        val contact = safeSettings.contact
        if (contact == null) {
            clearShare()
            return
        }

        _activeShareType.value = ShareTargetType.CONTACT
        _activeShareDetail.value = safeSettings.getEffectiveDisplayName(safeSettings.activeProfileType)

        scope.launch {
            val activeType = safeSettings.activeProfileType
            val activeEntries = safeSettings.getEffectiveEntryIds(activeType)
            val photoUri = safeSettings.getEffectivePhotoUri(activeType)
            val profile = safeSettings.getProfile(activeType)
            val vCard = contact.toVCard(
                context = context,
                activeEntryIds = activeEntries,
                customPhotoUri = photoUri,
                customDisplayName = if (activeType != com.sameerasw.medrop.domain.model.MeDropProfileType.CONTACT) profile.customDisplayName else null,
                fieldOverrides = profile.customFieldOverrides
            )
            MeDropHceService.prepareVCard(vCard)
        }
    }

    /**
     * Re-arms current active share payload without reverting to contact.
     * Called when activity resumes or broadcast starts.
     */
    fun rearmActiveShare(context: Context, settings: MeDropSettings?) {
        when (_activeShareType.value) {
            ShareTargetType.FILE -> {
                val json = stagedFilePayloadJson
                if (!json.isNullOrBlank()) {
                    MeDropHceService.prepareFile(json)
                }
            }
            ShareTargetType.TEXT -> {
                val text = stagedTextPayload
                if (!text.isNullOrBlank()) {
                    MeDropHceService.prepareText(text)
                }
            }
            ShareTargetType.CONTACT, ShareTargetType.NONE -> {
                // If neither file nor text is active, default to contact
                shareContact(context, settings)
            }
        }
    }

    fun setForceShareContact(context: Context, settings: MeDropSettings?) {
        stagedFilePayloadJson = null
        stagedTextPayload = null
        _activeShareType.value = ShareTargetType.NONE
        val repo = MeDropRepository(context)
        val loadedSettings = settings ?: run {
            val json = repo.getMeDropSettingsJson()
            if (json != null) {
                try {
                    Gson().fromJson(json, MeDropSettings::class.java)
                } catch (_: Exception) { null }
            } else null
        }

        if (loadedSettings != null) {
            val updatedSettings = loadedSettings.copy(
                activeProfileType = com.sameerasw.medrop.domain.model.MeDropProfileType.CONTACT,
                contactProfile = loadedSettings.contactProfile.copy(enabled = true)
            )
            try {
                repo.setMeDropSettingsJson(Gson().toJson(updatedSettings))
            } catch (_: Exception) {}
            shareContact(context, updatedSettings)
        } else {
            shareContact(context, null)
        }
    }

    /**
     * Reverts to default contact card ONLY if both file and text are absent.
     */
    fun revertToDefault(context: Context) {
        if (_activeShareType.value == ShareTargetType.FILE || _activeShareType.value == ShareTargetType.TEXT) {
            return
        }
        val repo = MeDropRepository(context)
        val json = repo.getMeDropSettingsJson()
        if (json != null) {
            try {
                val settings = Gson().fromJson(json, MeDropSettings::class.java)
                if (settings.contact != null) {
                    _activeShareType.value = ShareTargetType.NONE
                    shareContact(context, settings)
                    return
                }
            } catch (_: Exception) {}
        }
        clearShare()
    }

    fun clearShare() {
        stagedFilePayloadJson = null
        stagedTextPayload = null
        _activeShareType.value = ShareTargetType.NONE
        _activeShareDetail.value = null
        MeDropHceService.clearNdef()
    }
}
