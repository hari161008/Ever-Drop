package com.sameerasw.medrop.domain.model

import android.net.Uri

enum class PeerStatus {
    AVAILABLE,
    INVITED,
    CONNECTED,
    FAILED,
    UNAVAILABLE
}

data class WifiDirectPeer(
    val deviceAddress: String,
    val deviceName: String,
    val isEverDropPeer: Boolean = true,
    val status: PeerStatus = PeerStatus.AVAILABLE
)

enum class TransferType {
    FILE,
    TEXT
}

data class TransferHeader(
    val type: TransferType,
    val name: String,
    val size: Long,
    val mimeType: String
)

enum class TransferProgressStatus {
    IDLE,
    CONNECTING,
    NEGOTIATING,
    SENDING,
    RECEIVING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferProgress(
    val status: TransferProgressStatus = TransferProgressStatus.IDLE,
    val progress: Float = 0f,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
    val payloadType: TransferType? = null,
    val payloadName: String? = null,
    val receivedText: String? = null,
    val receivedFileUri: Uri? = null
)

data class WifiDirectGroupInfo(
    val networkName: String = "",
    val passphrase: String = "",
    val isGroupOwner: Boolean = false
)
