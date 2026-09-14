package com.sameerasw.medrop.viewmodels

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import com.sameerasw.medrop.data.repository.MeDropRepository
import com.sameerasw.medrop.domain.model.MeDropContact
import com.sameerasw.medrop.domain.model.MeDropProfile
import com.sameerasw.medrop.domain.model.MeDropProfileType
import com.sameerasw.medrop.domain.model.MeDropSettings
import com.sameerasw.medrop.utils.PermissionUtils

class MeDropViewModel : ViewModel() {
    val meDropSettings = mutableStateOf<MeDropSettings?>(null)
    val isMeDropAllowWhenLocked = mutableStateOf(true)
    val isPitchBlackThemeEnabled = mutableStateOf(false)
    val isBlurEnabled = mutableStateOf(true)
    val isDeveloperModeEnabled = mutableStateOf(false)
    val hasContactsPermission = mutableStateOf(false)
    val isTileAdded = mutableStateOf(false)

    // Ever Drop Share Hub: Staged sharing content (survives UI lifecycle, sheet intake, and rotation)
    val shareText = mutableStateOf("")
    val selectedFileUri = mutableStateOf<android.net.Uri?>(null)
    val selectedFileName = mutableStateOf<String?>(null)
    val selectedFileSize = mutableStateOf<String?>(null)
    val selectedFileRawBytes = mutableStateOf(0L)
    val selectedFileMimeType = mutableStateOf<String?>("*/*")

    /**
     * Updates typed text to beam. Automatically manages NFC target priority.
     */
    fun setShareText(context: Context, text: String) {
        shareText.value = text
        if (text.isNotBlank()) {
            com.sameerasw.medrop.utils.EverDropNfcShareManager.shareText(context, text)
        } else {
            com.sameerasw.medrop.utils.EverDropNfcShareManager.clearStagedText(context)
        }
    }

    /**
     * Updates selected file to beam. Automatically manages NFC target priority.
     */
    fun setSelectedFile(
        context: Context,
        uri: android.net.Uri,
        name: String,
        sizeStr: String,
        rawBytes: Long,
        mimeType: String = "*/*"
    ) {
        selectedFileUri.value = uri
        selectedFileName.value = name
        selectedFileSize.value = sizeStr
        selectedFileRawBytes.value = rawBytes
        selectedFileMimeType.value = mimeType
        com.sameerasw.medrop.utils.EverDropNfcShareManager.shareFile(context, uri, name, mimeType, rawBytes)
    }

    /**
     * Clears staged file and falls back to text if typed, or default contact card if text is empty.
     */
    fun clearShareFile(context: Context) {
        selectedFileUri.value = null
        selectedFileName.value = null
        selectedFileSize.value = null
        selectedFileRawBytes.value = 0L
        selectedFileMimeType.value = null
        com.sameerasw.medrop.utils.EverDropNfcShareManager.clearStagedFile(context)
    }

    /**
     * Clears staged text.
     */
    fun clearShareText(context: Context) {
        shareText.value = ""
        com.sameerasw.medrop.utils.EverDropNfcShareManager.clearStagedText(context)
    }

    fun check(context: Context) {
        val repo = MeDropRepository(context)
        isPitchBlackThemeEnabled.value = repo.isPitchBlackThemeEnabled()
        isBlurEnabled.value = repo.isBlurEnabled()
        isDeveloperModeEnabled.value = repo.isDeveloperModeEnabled()
        hasContactsPermission.value = PermissionUtils.hasContactsPermission(context)
        updateTileState(context)
        loadMeDropSettings(context)
    }

    fun updateTileState(context: Context) {
        var added = false
        try {
            val tilesString = android.provider.Settings.Secure.getString(context.contentResolver, "sysui_qs_tiles") ?: ""
            if (tilesString.contains("${context.packageName}/.services.tiles.MeDropTileService") ||
                tilesString.contains(context.packageName) ||
                tilesString.contains("com.coolappstore.everdrop.by.svhp") ||
                tilesString.contains("com.sameerasw.medrop")
            ) {
                added = true
                MeDropRepository(context).setTileAdded(true)
            }
        } catch (_: Exception) {}

        if (!added) {
            added = MeDropRepository(context).isTileAdded()
        }
        isTileAdded.value = added
    }

    fun setTileAdded(context: Context, added: Boolean) {
        MeDropRepository(context).setTileAdded(added)
        isTileAdded.value = added
    }

    fun setPitchBlackTheme(context: Context, enabled: Boolean) {
        MeDropRepository(context).setPitchBlackThemeEnabled(enabled)
        isPitchBlackThemeEnabled.value = enabled
    }

    fun setBlurEnabled(context: Context, enabled: Boolean) {
        MeDropRepository(context).setBlurEnabled(enabled)
        isBlurEnabled.value = enabled
    }

    fun setDeveloperModeEnabled(context: Context, enabled: Boolean) {
        MeDropRepository(context).setDeveloperModeEnabled(enabled)
        isDeveloperModeEnabled.value = enabled
    }

    fun loadMeDropSettings(context: Context) {
        val repo = MeDropRepository(context)
        val json = repo.getMeDropSettingsJson()
        meDropSettings.value =
            if (json != null) {
                try {
                    Gson().fromJson(json, MeDropSettings::class.java)
                } catch (_: Exception) {
                    MeDropSettings()
                }
            } else {
                MeDropSettings()
            }
        isMeDropAllowWhenLocked.value = repo.isMeDropAllowWhenLocked()
        com.sameerasw.medrop.services.MeDropWearSyncManager.syncProfiles(context)
        com.sameerasw.medrop.utils.DynamicShortcutManager.updateShortcuts(context, meDropSettings.value)
    }

    fun saveMeDropSettings(
        context: Context,
        settings: MeDropSettings?,
    ) {
        meDropSettings.value = settings
        val json = if (settings != null) Gson().toJson(settings) else null
        MeDropRepository(context).setMeDropSettingsJson(json)
        com.sameerasw.medrop.services.MeDropWearSyncManager.syncProfiles(context)
        com.sameerasw.medrop.utils.DynamicShortcutManager.updateShortcuts(context, settings)
    }

    fun setMeDropContact(
        context: Context,
        contact: MeDropContact?,
    ) {
        val current = meDropSettings.value ?: MeDropSettings()
        val updated = current.copy(contact = contact)
        saveMeDropSettings(context, updated)
    }

    fun setMeDropAllowWhenLocked(context: Context, enabled: Boolean) {
        MeDropRepository(context).setMeDropAllowWhenLocked(enabled)
        isMeDropAllowWhenLocked.value = enabled
        val current = meDropSettings.value ?: MeDropSettings()
        saveMeDropSettings(context, current.copy(allowWhenLocked = enabled))
    }

    fun setMeDropProfileEnabled(
        context: Context,
        type: MeDropProfileType,
        enabled: Boolean,
    ) {
        val current = meDropSettings.value ?: MeDropSettings()
        val profile = current.getProfile(type).copy(enabled = enabled)
        val updated = current.updateProfile(profile)
        saveMeDropSettings(context, updated)
    }

    fun setMeDropActiveProfile(
        context: Context,
        type: MeDropProfileType,
    ) {
        val current = meDropSettings.value ?: MeDropSettings()
        val updated = current.copy(activeProfileType = type)
        saveMeDropSettings(context, updated)
    }

    fun setMeDropShowRipple(context: Context, enabled: Boolean) {
        val current = meDropSettings.value ?: MeDropSettings()
        val updated = current.copy(showRipple = enabled)
        saveMeDropSettings(context, updated)
    }

    fun setMeDropUsePhotoForAll(context: Context, enabled: Boolean) {
        val current = meDropSettings.value ?: MeDropSettings()
        val updated = current.copy(usePhotoForAll = enabled)
        saveMeDropSettings(context, updated)
    }

    fun setMeDropEnableReceiving(context: Context, enabled: Boolean) {
        val current = meDropSettings.value ?: MeDropSettings()
        val updated = current.copy(enableReceiving = enabled)
        saveMeDropSettings(context, updated)
    }

    fun setOrientationShare(context: Context, enabled: Boolean) {
        val current = meDropSettings.value ?: MeDropSettings()
        val updated = current.copy(orientationShare = enabled)
        saveMeDropSettings(context, updated)
    }

    fun toggleMeDropProfileEntry(
        context: Context,
        type: MeDropProfileType,
        entryId: String,
        enabled: Boolean,
    ) {
        val current = meDropSettings.value ?: MeDropSettings()
        val effective = current.getEffectiveEntryIds(type).toMutableSet()
        if (enabled) {
            effective.add(entryId)
        } else {
            effective.remove(entryId)
        }
        val profile = current.getProfile(type).copy(selectedEntryIds = effective)
        val updated = current.updateProfile(profile)
        saveMeDropSettings(context, updated)
    }

    fun updateMeDropProfilePhoto(
        context: Context,
        type: MeDropProfileType,
        photoUri: String?,
    ) {
        MeDropContact.clearPhotoCache()
        val current = meDropSettings.value ?: MeDropSettings()
        val profile = current.getProfile(type).copy(photoUri = photoUri)
        val updated = current.updateProfile(profile)
        saveMeDropSettings(context, updated)
    }

    fun updateMeDropProfileDisplayName(
        context: Context,
        type: MeDropProfileType,
        customName: String?,
    ) {
        val current = meDropSettings.value ?: MeDropSettings()
        val profile = current.getProfile(type).copy(customDisplayName = customName?.takeIf { it.isNotBlank() })
        val updated = current.updateProfile(profile)
        saveMeDropSettings(context, updated)
    }

    fun updateMeDropProfileFieldValue(
        context: Context,
        type: MeDropProfileType,
        fieldId: String,
        value: String?,
    ) {
        val current = meDropSettings.value ?: MeDropSettings()
        val profile = current.getProfile(type)
        val currentOverrides = profile.customFieldOverrides.toMutableMap()
        if (value.isNullOrBlank()) {
            currentOverrides.remove(fieldId)
        } else {
            currentOverrides[fieldId] = value
        }
        val updatedProfile = profile.copy(customFieldOverrides = currentOverrides)
        val updated = current.updateProfile(updatedProfile)
        saveMeDropSettings(context, updated)
    }
}
