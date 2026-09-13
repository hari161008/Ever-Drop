package com.sameerasw.medrop.ui.features

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.sameerasw.medrop.utils.QrCodeGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.sameerasw.medrop.utils.EverDropWifiDirectManager
import com.sameerasw.medrop.utils.HapticUtil

/**
 * EverDropReceiveUI
 *
 * Rendered on the Receive tab:
 * - Automatically activates Wi-Fi Direct discoverability & DNS-SD advertising (like Android Quick Share)
 * - Animated pulsing radar rings indicating device is actively discoverable
 * - Displays local device name so senders can easily identify this phone
 * - Real-time incoming file and text transfer card with progress bar
 * - Automatic persistence to Downloads/Ever Share and clipboard
 */
@Composable
fun EverDropReceiveUI(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val thisDeviceName by EverDropWifiDirectManager.thisDeviceName.collectAsState()
    val isReceiverActive by EverDropWifiDirectManager.isReceiverActive.collectAsState()
    val transferProgress by EverDropWifiDirectManager.transferProgress.collectAsState()
    val receiverGroupInfo by EverDropWifiDirectManager.receiverGroupInfo.collectAsState()
    var showQrDialog by remember { mutableStateOf(false) }

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
            EverDropWifiDirectManager.startDiscoverableReceiver(context)
        }
    }

    // Automatically make device discoverable via Wi-Fi Direct while on the Receive page
    DisposableEffect(hasPermission) {
        if (hasPermission) {
            EverDropWifiDirectManager.startDiscoverableReceiver(context)
        }
        onDispose {
            EverDropWifiDirectManager.stopDiscoverableReceiver()
        }
    }

    // Haptic notification on transfer completion
    LaunchedEffect(transferProgress.status) {
        if (transferProgress.status == TransferProgressStatus.COMPLETED) {
            HapticUtil.performSuccessHaptic(view)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "receive_radar")

    val wave1Scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1_scale"
    )
    val wave1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1_alpha"
    )

    val wave2Scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave2_scale"
    )
    val wave2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave2_alpha"
    )

    val dotPulse by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_pulse"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Permission Request Banner (if nearby devices permission is missing)
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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

        // Live Incoming / Active Transfer Card
        AnimatedVisibility(
            visible = transferProgress.status == TransferProgressStatus.RECEIVING ||
                    transferProgress.status == TransferProgressStatus.COMPLETED ||
                    transferProgress.status == TransferProgressStatus.FAILED
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
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
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val iconRes = when (transferProgress.status) {
                            TransferProgressStatus.COMPLETED -> R.drawable.rounded_check_24
                            TransferProgressStatus.FAILED -> R.drawable.rounded_remove_24
                            else -> if (transferProgress.payloadType == TransferType.TEXT) R.drawable.rounded_edit_24 else R.drawable.rounded_app_registration_24
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (transferProgress.status == TransferProgressStatus.RECEIVING) {
                                    stringResource(R.string.receive_incoming_transfer)
                                } else {
                                    transferProgress.payloadName ?: "Ever Drop Transfer"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = transferProgress.message ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (transferProgress.status == TransferProgressStatus.RECEIVING) {
                        LinearProgressIndicator(
                            progress = { transferProgress.progress },
                            modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                        )
                    }

                    if (transferProgress.status == TransferProgressStatus.COMPLETED) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (transferProgress.receivedFileUri != null) {
                                Button(
                                    onClick = {
                                        val uri = transferProgress.receivedFileUri ?: return@Button
                                        try {
                                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(viewIntent)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Text(stringResource(R.string.action_open_file))
                                }
                            }

                            OutlinedButton(
                                onClick = { EverDropWifiDirectManager.resetTransferState() },
                                modifier = if (transferProgress.receivedFileUri == null) Modifier.fillMaxWidth() else Modifier,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text("Dismiss")
                            }
                        }
                    } else if (transferProgress.status == TransferProgressStatus.FAILED) {
                        OutlinedButton(
                            onClick = { EverDropWifiDirectManager.resetTransferState() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        // Radar Pulse Hero Area
        Box(
            modifier = Modifier
                .size(200.dp)
                .padding(top = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer Wave 2
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        scaleX = wave2Scale
                        scaleY = wave2Scale
                        alpha = wave2Alpha
                    }
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            )

            // Inner Wave 1
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        scaleX = wave1Scale
                        scaleY = wave1Scale
                        alpha = wave1Alpha
                    }
                    .border(
                        width = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            )

            // Center Glowing Circle with NFC & Quick Share Icon
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_devices_24),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Discoverable Device Name Badge (Quick Share Style)
        AssistChip(
            onClick = {},
            label = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .graphicsLayer { alpha = dotPulse }
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = "Discoverable as $thisDeviceName",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        // Informational Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceBright
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.receive_ready_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.receive_ready_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Detail row 1: Wi-Fi Direct discoverable status
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rounded_share_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = stringResource(R.string.receive_wifi_direct_active),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Detail row 2: saving destination
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rounded_check_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = stringResource(R.string.receive_destination_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Button to display Direct Wi-Fi QR Code for zero-latency connection
                FilledTonalButton(
                    onClick = { showQrDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.rounded_qr_code_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Direct Connect QR")
                }
            }
        }

        // Direct Connect QR Dialog
        if (showQrDialog) {
            val group = receiverGroupInfo
            val qrContent = if (group != null && group.networkName.isNotEmpty()) {
                "WIFI:S:${group.networkName};T:WPA;P:${group.passphrase};;"
            } else {
                "EVERDROP:${thisDeviceName}"
            }
            val qrBitmap = remember(qrContent) {
                QrCodeGenerator.generateQrBitmap(
                    content = qrContent,
                    size = 512,
                    foregroundColor = android.graphics.Color.BLACK,
                    backgroundColor = android.graphics.Color.WHITE
                )
            }

            AlertDialog(
                onDismissRequest = { showQrDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.rounded_qr_code_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Direct Connect",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Scan this QR code with any phone camera or Wi-Fi settings to connect directly without a router.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Wi-Fi Direct QR Code",
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                    .padding(8.dp)
                            )
                        }

                        if (group != null && group.networkName.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Wi-Fi: ${group.networkName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (group.passphrase.isNotEmpty()) {
                                    Text(
                                        text = "Password: ${group.passphrase}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQrDialog = false }) {
                        Text("Done")
                    }
                }
            )
        }
    }
}
