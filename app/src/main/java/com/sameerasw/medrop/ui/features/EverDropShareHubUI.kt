package com.sameerasw.medrop.ui.features

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.medrop.R
import com.sameerasw.medrop.utils.ColorUtil
import com.sameerasw.medrop.utils.EverDropNfcShareManager
import com.sameerasw.medrop.utils.HapticUtil
import com.sameerasw.medrop.utils.ShareTargetType
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sameerasw.medrop.utils.EverDropFileManager
import com.sameerasw.medrop.viewmodels.MeDropViewModel
import com.sameerasw.medrop.domain.model.TransferType
import com.sameerasw.medrop.ui.sheets.EverDropQuickShareSheet
import androidx.compose.material3.rememberModalBottomSheetState
import java.util.Locale

/**
 * EverDropShareHubUI
 *
 * Provides the interactive UI for sharing files and text over NFC and Wi-Fi Direct:
 * - Direct file picker and share sheet receiver
 * - Dynamic text input with automatic keyboard layout adjustment
 * - Live NFC beam status indicators ("NFC Active • Beaming File", "NFC Active • Beaming Text")
 * - Wi-Fi Direct Quick Share bottom sheet integration
 * - Enforces strict beam priority: contact card is never beamed when file or text is present.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EverDropShareHubUI(
    modifier: Modifier = Modifier,
    viewModel: MeDropViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    val activeShareType by EverDropNfcShareManager.activeShareType.collectAsState()

    // Quick Share sheet state
    val quickShareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showQuickShareSheet by remember { mutableStateOf(false) }
    var quickShareType by remember { mutableStateOf(TransferType.FILE) }

    // Persistent share state backed by MeDropViewModel
    val shareText by viewModel.shareText
    val selectedFileUri by viewModel.selectedFileUri
    val selectedFileName by viewModel.selectedFileName
    val selectedFileSize by viewModel.selectedFileSize
    val selectedFileRawBytes by viewModel.selectedFileRawBytes
    val selectedFileMimeType by viewModel.selectedFileMimeType

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size, rawBytes) = EverDropFileManager.queryFileInfoWithRawSize(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            if (EverDropFileManager.isTextFile(name, mime)) {
                val fileText = EverDropFileManager.readTextFromUri(context, uri)
                if (fileText != null) {
                    viewModel.clearShareFile(context)
                    viewModel.setShareText(context, fileText)
                    HapticUtil.performVirtualKeyHaptic(view)
                    Toast.makeText(context, "Text loaded into text box from $name", Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }
            }
            viewModel.setSelectedFile(context, uri, name, size, rawBytes, mime)
            HapticUtil.performVirtualKeyHaptic(view)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- Share Files Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceBright,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = ColorUtil.getPastelColorFor("Files"),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.rounded_share_24),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = ColorUtil.getVibrantColorFor("Files"),
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.share_files_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.share_files_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // NFC status indicator for Files
                if (selectedFileUri != null) {
                    if (activeShareType == ShareTargetType.FILE) {
                        AssistChip(
                            onClick = {},
                            label = { Text("NFC Active • Beaming File", fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_share_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    } else {
                        AssistChip(
                            onClick = {
                                HapticUtil.performVirtualKeyHaptic(view)
                                val uri = selectedFileUri ?: return@AssistChip
                                val mime = context.contentResolver.getType(uri) ?: "*/*"
                                EverDropNfcShareManager.shareFile(context, uri, selectedFileName ?: "file", mime, selectedFileRawBytes)
                            },
                            label = { Text("Set File as NFC Beam") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_share_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_app_registration_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedFileName ?: "Selected file",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee()
                                )
                                if (!selectedFileSize.isNullOrBlank()) {
                                    Text(
                                        text = selectedFileSize.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    HapticUtil.performVirtualKeyHaptic(view)
                                    viewModel.clearShareFile(context)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_delete_24),
                                    contentDescription = stringResource(R.string.share_file_remove),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (selectedFileUri != null) {
                                    HapticUtil.performVirtualKeyHaptic(view)
                                    quickShareType = TransferType.FILE
                                    showQuickShareSheet = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.rounded_share_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.share_file_send))
                        }

                        OutlinedButton(
                            onClick = {
                                HapticUtil.performVirtualKeyHaptic(view)
                                filePickerLauncher.launch("*/*")
                            },
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(stringResource(R.string.share_file_change))
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = {
                            HapticUtil.performVirtualKeyHaptic(view)
                            filePickerLauncher.launch("*/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_add_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.share_files_action))
                    }
                }
            }
        }

        // --- Share Text Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceBright,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = ColorUtil.getPastelColorFor("Text"),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.rounded_edit_24),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = ColorUtil.getVibrantColorFor("Text"),
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.share_text_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.share_text_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // NFC status indicator for Text
                if (shareText.isNotBlank()) {
                    if (activeShareType == ShareTargetType.TEXT) {
                        AssistChip(
                            onClick = {},
                            label = { Text("NFC Active • Beaming Text", fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_share_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    } else {
                        AssistChip(
                            onClick = {
                                HapticUtil.performVirtualKeyHaptic(view)
                                EverDropNfcShareManager.shareText(context, shareText)
                            },
                            label = { Text("Set Text as NFC Beam") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_share_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = shareText,
                    onValueChange = {
                        viewModel.setShareText(context, it)
                        coroutineScope.launch {
                            bringIntoViewRequester.bringIntoView()
                        }
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.share_text_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringIntoViewRequester)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                coroutineScope.launch {
                                    delay(100)
                                    bringIntoViewRequester.bringIntoView()
                                    delay(250)
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    minLines = 3,
                    maxLines = 6,
                    trailingIcon = {
                        if (shareText.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.clearShareText(context)
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_remove_24),
                                    contentDescription = stringResource(R.string.share_text_clear),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (shareText.isNotBlank()) {
                                HapticUtil.performVirtualKeyHaptic(view)
                                quickShareType = TransferType.TEXT
                                showQuickShareSheet = true
                            }
                        },
                        enabled = shareText.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_share_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.share_text_action))
                    }

                    FilledTonalButton(
                        onClick = {
                            if (shareText.isNotBlank()) {
                                HapticUtil.performVirtualKeyHaptic(view)
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Ever Drop Text", shareText))
                                Toast.makeText(context, context.getString(R.string.share_text_copied), Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = shareText.isNotBlank(),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(stringResource(R.string.share_text_copy))
                    }
                }
            }
        }
    }

    if (showQuickShareSheet) {
        EverDropQuickShareSheet(
            sheetState = quickShareSheetState,
            shareType = quickShareType,
            fileName = selectedFileName,
            fileSize = selectedFileSize,
            fileUri = selectedFileUri,
            rawFileSize = selectedFileRawBytes,
            fileMimeType = selectedFileMimeType ?: "*/*",
            shareText = shareText,
            onDismissRequest = { showQuickShareSheet = false }
        )
    }
}

private fun shareFile(context: Context, uri: Uri) {
    try {
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.share_file_send)))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share selected file", Toast.LENGTH_SHORT).show()
    }
}

private fun queryFileInfoWithRawSize(context: Context, uri: Uri): Triple<String, String, Long> {
    var name = "Selected file"
    var sizeText = ""
    var rawSize = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
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

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) digitGroups = units.size - 1
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.getDefault(), "%.1f %s", value, units[digitGroups])
}
