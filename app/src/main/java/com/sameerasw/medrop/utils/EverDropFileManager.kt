package com.sameerasw.medrop.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

object EverDropFileManager {

    const val FOLDER_NAME = "Ever Share"

    /**
     * Saves file bytes directly into the public Downloads/Ever Share folder.
     * On Android 10+ (API 29+), uses MediaStore.Downloads with RELATIVE_PATH.
     * On older Android versions, creates the folder under Environment.getExternalStoragePublicDirectory.
     */
    fun saveFileToEverShare(
        context: Context,
        fileName: String,
        mimeType: String?,
        bytes: ByteArray
    ): Uri? {
        val originalName = fileName.ifBlank { "received_file" }
        // Clean filename of filesystem-invalid characters
        val cleanName = originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val effectiveMime = if (mimeType.isNullOrBlank() || mimeType == "*/*") {
            val ext = MimeTypeMap.getFileExtensionFromUrl(cleanName)
            if (!ext.isNullOrBlank()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
            } else {
                "application/octet-stream"
            }
        } else {
            mimeType
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                    put(MediaStore.Downloads.MIME_TYPE, effectiveMime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { outStream ->
                        outStream.write(bytes)
                        outStream.flush()
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)
                    itemUri
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val everShareFolder = File(downloadsDir, FOLDER_NAME)
                if (!everShareFolder.exists()) {
                    everShareFolder.mkdirs()
                }

                var targetFile = File(everShareFolder, cleanName)
                if (targetFile.exists()) {
                    val dotIdx = cleanName.lastIndexOf('.')
                    val base = if (dotIdx != -1) cleanName.substring(0, dotIdx) else cleanName
                    val ext = if (dotIdx != -1) cleanName.substring(dotIdx) else ""
                    var count = 1
                    while (targetFile.exists()) {
                        targetFile = File(everShareFolder, "$base ($count)$ext")
                        count++
                    }
                }

                targetFile.writeBytes(bytes)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(effectiveMime),
                    null
                )
                Uri.fromFile(targetFile)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Resolves display name, human-readable size text, and raw byte length from a Content Uri.
     */
    fun queryFileInfoWithRawSize(context: Context, uri: Uri): Triple<String, String, Long> {
        var name = "Selected file"
        var sizeText = ""
        var rawSize = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                    if (sizeIndex != -1) {
                        rawSize = cursor.getLong(sizeIndex)
                        sizeText = formatFileSize(rawSize)
                    }
                }
            }
        } catch (_: Exception) {}
        return Triple(name, sizeText, rawSize)
    }

    /**
     * Formats bytes into human-readable B, KB, MB, GB strings.
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) digitGroups = units.size - 1
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[digitGroups])
    }
}
