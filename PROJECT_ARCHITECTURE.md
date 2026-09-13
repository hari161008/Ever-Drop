# Ever Drop (MeDrop) — Project Architecture & Developer Guide

This document provides a fast, comprehensive overview of the Ever Drop codebase to accelerate development, debugging, and AI prompt analysis.

---

## 1. High-Level Summary

**Ever Drop** is an Android application designed for rapid, seamless peer-to-peer data sharing using **NFC Host Card Emulation (HCE)** and **NFC Reader Mode**, augmented with dynamic Android Share Sheet integration.

### Core Capabilities
1. **Contact Profile Sharing**: Share digital contact cards (vCards) across standard, professional, and custom profiles.
2. **Text Beaming**: Transmit arbitrary snippets of text over NFC that automatically copy to the receiver's clipboard.
3. **File Beaming**: Beam files (documents, images, audio, etc.) directly over NFC or stage them for high-speed transfer; received files are automatically stored under `Downloads/Ever Share`.
4. **Android Share Sheet Receiver**: Any text or file shared from third-party apps (Browser, Gallery, Files, WhatsApp, etc.) is directly accepted and staged for instant NFC transmission.
5. **Adaptive IME Layout**: The main interface dynamically adjusts padding and visibility when the software keyboard opens, keeping the input area visible and dismissing the floating toolbar.

---

## 2. Priority Hierarchy for Beaming

Ever Drop enforces a strict **content priority rule** to ensure predictable transmission:

```
[ FILE STAGED ]   ───► Beams ONLY File (MIME: application/vnd.everdrop.file)
      │ (if empty)
      ▼
[ TEXT STAGED ]   ───► Beams ONLY Text (MIME: text/plain)
      │ (if empty)
      ▼
[ CONTACT CARD ]  ───► Beams Contact vCard (MIME: text/vcard) [Default Fallback]
```

> **Rule**: When a file is selected or text is typed in the box, the NFC transmitter will **never** broadcast the contact card by default. Only when both file and text are explicitly cleared does the transmitter revert to the default contact card.

---

## 3. Core Component Architecture

### A. Presentation Layer (Jetpack Compose)
- **`MainActivity`** (`com.sameerasw.medrop.MainActivity`)
  - Root Activity and Compose entry point (`Theme.MeDrop.Splash`, edge-to-edge).
  - Listens for incoming NFC tags (`ACTION_NDEF_DISCOVERED`) and Android Share Sheet intents (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
  - Implements keyboard adjustment using `WindowInsets.ime.asPaddingValues()` and `Modifier.imePadding()`.
  - Automatically conceals the bottom floating toolbar (`MeDropFloatingToolbar`) when the keyboard is active.
- **`EverDropShareHubUI`** (`com.sameerasw.medrop.ui.features.EverDropShareHubUI`)
  - UI cards for file and text sharing.
  - Houses the file picker launcher and OutlinedTextField.
  - Uses `BringIntoViewRequester` to smoothly bring text field into focus above the keyboard.
  - Displays real-time chip status (`NFC Active • Beaming File`, `NFC Active • Beaming Text`).
- **`MeDropHeaderUI`** (`com.sameerasw.medrop.ui.features.MeDropHeaderUI`)
  - Collapsing morphing avatar header and contact identity display.
- **`ReceivedContactActivity`** (`com.sameerasw.medrop.ui.activities.ReceivedContactActivity`)
  - Translucent lock-screen capable Activity that triggers when an NDEF contact tag is tapped while device is locked or outside the app.

### B. State Management
- **`MeDropViewModel`** (`com.sameerasw.medrop.viewmodels.MeDropViewModel`)
  - Survives activity lifecycles and configuration changes.
  - Holds `shareText`, `selectedFileUri`, `selectedFileName`, `selectedFileSize`, `selectedFileRawBytes`, `selectedFileMimeType`.
  - Dispatches updates to `EverDropNfcShareManager`.
  - Manages profile selections, permissions, dark theme, and Quick Settings tile status.

### C. NFC Communication Engine
- **`EverDropNfcShareManager`** (`com.sameerasw.medrop.utils.EverDropNfcShareManager`)
  - Decides active payload target (`ShareTargetType`: `NONE`, `TEXT`, `FILE`, `CONTACT`).
  - Holds cached payload buffers (`stagedFilePayloadJson`, `stagedTextPayload`).
  - Guards `shareContact(...)` from overwriting staged files or text.
  - Provides `rearmActiveShare(...)` to safely refresh HCE buffers on resume.
- **`MeDropNfcManager`** (`com.sameerasw.medrop.utils.MeDropNfcManager`)
  - `startBroadcast(...)`: Sets `MeDropHceService` as preferred card emulation service and re-arms the active share.
  - `stopBroadcast(...)`: Releases preferred emulation service without clearing staged user content.
  - `enableReaderMode(...)`: Enables foreground NFC tag reader with ISO-DEP fallback.
  - `parseNdefMessage(...)`: Parses raw NDEF records into typed `EverDropItem` (Contact, FileItem, Text).
- **`MeDropHceService`** (`com.sameerasw.medrop.services.MeDropHceService`)
  - Android `HostApduService` emulating an NFC Type 4 Tag.
  - Responds to standard ISO 7816-4 APDUs:
    - `SELECT AID`: `D2 76 00 00 85 01 01` (NDEF Application)
    - `SELECT CC File`: `E1 03`
    - `SELECT NDEF File`: `E1 04`
    - `READ BINARY`: Streams binary NDEF message chunks to the reader.

### D. File Management
- **`EverDropFileManager`** (`com.sameerasw.medrop.utils.EverDropFileManager`)
  - `saveFileToEverShare(...)`: Writes bytes into public storage under `Downloads/Ever Share/`. Uses `MediaStore.Downloads` on Android 10+ (API 29+) and direct File I/O with `MediaScannerConnection` on older versions.
  - `queryFileInfoWithRawSize(...)`: Queries display name, formatted size string, and raw byte length from any Content Uri.

---

## 4. Android Share Sheet Flow

```
[ External App (Gallery, Files, Chrome) ]
                    │
                    ▼ (User selects Share -> Ever Drop)
[ Intent.ACTION_SEND / ACTION_SEND_MULTIPLE ]
                    │
                    ▼
[ MainActivity.onCreate() / onNewIntent() ]
                    │
                    ▼
[ MainActivity.handleIncomingIntent() ]
     ├─► If Uri stream present:
     │     1. EverDropFileManager.queryFileInfoWithRawSize(uri)
     │     2. viewModel.setSelectedFile(uri, name, size, rawBytes, mime)
     │     3. EverDropNfcShareManager.shareFile(...)
     │     4. activeShareType = FILE
     │
     └─► If EXTRA_TEXT present:
           1. viewModel.setShareText(text)
           2. EverDropNfcShareManager.shareText(...)
           3. activeShareType = TEXT
```

---

## 5. Keyboard / IME Layout Adjustment Flow

```
User taps OutlinedTextField
          │
          ▼
Software Keyboard opens (IME)
          │
          ├─► WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
          │     - isKeyboardVisible = true
          │
          ├─► Column applies Modifier.imePadding()
          │     - Shrinks scrollable container height to sit above keyboard
          │
          ├─► Bottom Spacer collapses from (navigationBars + 150.dp) to 32.dp
          │     - Eliminates dead space
          │
          ├─► MeDropFloatingToolbar is conditionally hidden (!isKeyboardVisible)
          │     - Prevents toolbar obstruction over the text area
          │
          └─► BringIntoViewRequester on OutlinedTextField fires
                - Automatically scrolls the input box into clear view
```

---

## 6. Directory Map

```
app/src/main/
├── AndroidManifest.xml                  # App manifest, permissions, HCE service, Share Sheet filters
├── java/com/sameerasw/medrop/
│   ├── MainActivity.kt                  # Main entry, IME adaptation, Share Sheet intent handler
│   ├── MeDropApp.kt                     # Application class
│   ├── data/repository/
│   │   └── MeDropRepository.kt         # SharedPreferences storage for settings and profiles
│   ├── domain/model/
│   │   ├── EverDropItem.kt              # Sealed class: Text, FileItem, Contact
│   │   ├── MeDropContact.kt             # Contact entity & vCard generator
│   │   ├── MeDropProfile.kt             # Profile definitions (Contact, Work, Custom)
│   │   └── MeDropSettings.kt            # App settings state
│   ├── services/
│   │   ├── MeDropHceService.kt          # Host Card Emulation ISO-DEP / APDU handler
│   │   └── tiles/MeDropTileService.kt   # Quick Settings tile service
│   ├── ui/
│   │   ├── activities/
│   │   │   ├── MeDropActivity.kt        # Quick share overlay
│   │   │   ├── ReceivedContactActivity.kt # Lock-screen NDEF receiver
│   │   │   └── SettingsActivity.kt      # Settings UI
│   │   ├── features/
│   │   │   ├── EverDropShareHubUI.kt    # File & text share cards, NFC beam chips
│   │   │   ├── MeDropHeaderUI.kt        # Collapsing profile header
│   │   │   └── MeDropProfileFieldsUI.kt # Contact info fields
│   │   └── components/
│   │       └── MeDropFloatingToolbar.kt # Bottom navigation bar
│   ├── utils/
│   │   ├── EverDropFileManager.kt       # Public download directory writer & URI query helper
│   │   ├── EverDropNfcShareManager.kt   # Beam state coordinator & priority guard
│   │   ├── MeDropNfcManager.kt          # NFC Reader mode & HCE preferred service binding
│   │   └── VCardParser.kt               # Parser for incoming vCard payloads
│   └── viewmodels/
│       └── MeDropViewModel.kt           # Lifecycle-aware ViewModel for sharing and settings
```
