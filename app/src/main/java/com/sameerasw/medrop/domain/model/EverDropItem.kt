package com.sameerasw.medrop.domain.model

import com.sameerasw.medrop.utils.ReceivedContact

sealed class EverDropItem {
    data class Text(
        val text: String
    ) : EverDropItem()

    data class FileItem(
        val name: String,
        val mimeType: String,
        val size: Long,
        val base64Data: String? = null,
        val localSavedUri: String? = null,
    ) : EverDropItem()

    data class Contact(
        val vcard: String,
        val parsed: ReceivedContact? = null,
    ) : EverDropItem()

    data class P2pHandover(
        val deviceAddress: String,
        val deviceName: String,
        val transferType: TransferType,
        val payloadName: String,
        val payloadSize: Long,
        val mimeType: String = "*/*"
    ) : EverDropItem()
}
