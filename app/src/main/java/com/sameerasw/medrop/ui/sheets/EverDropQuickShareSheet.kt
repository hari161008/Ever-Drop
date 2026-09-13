package com.sameerasw.medrop.ui.sheets

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sameerasw.medrop.R
import com.sameerasw.medrop.domain.model.TransferProgressStatus
import com.sameerasw.medrop.domain.model.TransferType
import com.sameerasw.medrop.domain.model.WifiDirectPeer
import com.sameerasw.medrop.utils.ColorUtil
import com.sameerasw.medrop.utils.EverDropWifiDirectManager
import com.sameerasw.medrop.utils.HapticUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * EverDropQuickShareSheet
 *
 * Android Quick Share style bottom sheet:
 * - Scans and displays nearby phones using Wi-Fi Direct
 * - One-tap beam of staged file or text snippet
 * - Real-time progress bar and success completion
 * - Fallback to system share sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EverDropQuickShareSheet(
    sheetState: SheetState,
    shareType: TransferType,
    fileName: String? = null,
    fileSize: String? = null,
    fileUri: Uri? = null,
    rawFileSize: Long = 0L,
    fileMimeType: String = "*/*",
    shareText: String? = null,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val peers by EverDropWifiDirectManager.discoveredPeers.collectAsState()
    val isDiscovering by EverDropWifiDirectManager.isDiscovering.collectAsState()
    val transferProgress by EverDropWifiDirectManager.transferProgress.collectAsState()

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            EverDropWifiDirectManager.startPeerDiscovery(context)
        }
    }

    // Start peer discovery when sheet opens, stop on dismiss
    DisposableEffect(Unit) {
        if (hasPermission) {
            EverDropWifiDirectManager.startPeerDiscovery(context)
        }
        onDispose {
            EverDropWifiDirectManager.stopPeerDiscovery()
            EverDropWifiDirectManager.resetTransferState()
        }
    }

    // Auto-dismiss on transfer complete after brief celebration
    LaunchedEffect(transferProgress.status) {
        if (transferProgress.status == TransferProgressStatus.COMPLETED) {
            HapticUtil.performSuccessHaptic(view)
            delay(2200)
            onDismissRequest()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header Row: Title and Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.rounded_share_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.quick_share_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.quick_share_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDismissRequest) {
                    Icon(
                        painter = painterResource(id = R.drawable.rounded_remove_24),
                        contentDescription = stringResource(R.string.feat_medrop_action_cancel)
                    )
                }
            }

            // Staged Payload Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val iconRes = if (shareType == TransferType.FILE) {
                        R.drawable.rounded_app_registration_24
                    } else {
                        R.drawable.rounded_edit_24
                    }
                    val pastel = ColorUtil.getPastelColorFor(if (shareType == TransferType.FILE) "Files" else "Text")
                    val vibrant = ColorUtil.getVibrantColorFor(if (shareType == TransferType.FILE) "Files" else "Text")

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(color = pastel, shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = vibrant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (shareType == TransferType.FILE) {
                                fileName ?: "Selected File"
                            } else {
                                shareText?.take(40)?.let { if (shareText.length > 40) "$it…" else it } ?: "Shared Text"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (shareType == TransferType.FILE) {
                                fileSize.orEmpty()
                            } else {
                                "${shareText?.length ?: 0} characters"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Permission Request Banner (if required)
            if (!hasPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.quick_share_perm_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        FilledTonalButton(
                            onClick = { permissionLauncher.launch(requiredPermission) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.quick_share_perm_grant))
                        }
                    }
                }
            }

            // Active Transfer State Card (Progress Bar & Cancel)
            AnimatedVisibility(
                visible = transferProgress.status == TransferProgressStatus.CONNECTING ||
                        transferProgress.status == TransferProgressStatus.NEGOTIATING ||
                        transferProgress.status == TransferProgressStatus.SENDING ||
                        transferProgress.status == TransferProgressStatus.COMPLETED ||
                        transferProgress.status == TransferProgressStatus.FAILED
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (transferProgress.status == TransferProgressStatus.COMPLETED) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else if (transferProgress.status == TransferProgressStatus.FAILED) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val statusIcon = when (transferProgress.status) {
                                TransferProgressStatus.COMPLETED -> R.drawable.rounded_check_24
                                TransferProgressStatus.FAILED -> R.drawable.rounded_remove_24
                                else -> R.drawable.rounded_share_24
                            }
                            Icon(
                                painter = painterResource(id = statusIcon),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = transferProgress.message ?: stringResource(R.string.quick_share_transferring),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (transferProgress.status == TransferProgressStatus.SENDING ||
                            transferProgress.status == TransferProgressStatus.CONNECTING ||
                            transferProgress.status == TransferProgressStatus.NEGOTIATING) {
                            LinearProgressIndicator(
                                progress = { transferProgress.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { EverDropWifiDirectManager.cancelActiveTransfer() },
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Text(stringResource(R.string.feat_medrop_action_cancel))
                                }
                            }
                        }
                    }
                }
            }

            // Nearby Devices Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.quick_share_searching),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    RadarPulseIndicator()
                }

                if (peers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.quick_share_no_devices),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.quick_share_no_devices_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(peers, key = { it.deviceAddress }) { peer ->
                            QuickShareDeviceItem(
                                peer = peer,
                                onClick = {
                                    HapticUtil.performHeavyHaptic(view)
                                    EverDropWifiDirectManager.sendContent(
                                        context = context,
                                        peer = peer,
                                        type = shareType,
                                        textPayload = shareText,
                                        fileUri = fileUri,
                                        fileName = fileName,
                                        fileSize = rawFileSize,
                                        mimeType = fileMimeType
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Fallback: Share via other apps (Android system chooser)
            FilledTonalButton(
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    onDismissRequest()
                    if (shareType == TransferType.FILE && fileUri != null) {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = fileMimeType
                            putExtra(Intent.EXTRA_STREAM, fileUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(sendIntent, context.getString(R.string.share_file_send))
                        )
                    } else if (shareType == TransferType.TEXT && !shareText.isNullOrBlank()) {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(
                            Intent.createChooser(sendIntent, context.getString(R.string.share_text_action))
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_globe_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.quick_share_other_apps))
            }
        }
    }
}

@Composable
private fun QuickShareDeviceItem(
    peer: WifiDirectPeer,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.rounded_devices_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        }

        Text(
            text = peer.deviceName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = stringResource(R.string.quick_share_tap_to_share),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RadarPulseIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulseAlpha }
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Text(
            text = "Scanning",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
