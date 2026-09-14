package com.sameerasw.medrop.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.medrop.R
import com.sameerasw.medrop.domain.model.TransferProgressStatus
import com.sameerasw.medrop.domain.model.TransferType
import com.sameerasw.medrop.ui.theme.MeDropTheme
import com.sameerasw.medrop.utils.ColorUtil
import com.sameerasw.medrop.utils.EverDropWifiDirectManager
import java.util.Locale

class IncomingTransferActivity : ComponentActivity() {

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

        val reqName = intent.getStringExtra("request_name") ?: "Incoming Transfer"
        val reqSize = intent.getLongExtra("request_size", 0L)
        val reqTypeStr = intent.getStringExtra("request_type") ?: TransferType.FILE.name
        val reqType = try { TransferType.valueOf(reqTypeStr) } catch (_: Exception) { TransferType.FILE }
        val reqSender = intent.getStringExtra("request_sender") ?: "Nearby Phone"

        setContent {
            MeDropTheme {
                IncomingTransferDialog(
                    initialName = reqName,
                    initialSize = reqSize,
                    initialType = reqType,
                    initialSender = reqSender,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
internal fun IncomingTransferDialog(
    initialName: String,
    initialSize: Long,
    initialType: TransferType,
    initialSender: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val transferProgress by EverDropWifiDirectManager.transferProgress.collectAsState()
    val incomingRequest by EverDropWifiDirectManager.incomingRequest.collectAsState()

    val senderName = incomingRequest?.senderName ?: transferProgress.senderName ?: initialSender
    val payloadName = incomingRequest?.name ?: transferProgress.payloadName ?: initialName
    val payloadType = incomingRequest?.type ?: transferProgress.payloadType ?: initialType
    val totalBytes = incomingRequest?.size ?: transferProgress.totalBytes.takeIf { it > 0 } ?: initialSize

    LaunchedEffect(transferProgress.status) {
        if (transferProgress.status == TransferProgressStatus.CANCELLED ||
            transferProgress.status == TransferProgressStatus.FAILED) {
            kotlinx.coroutines.delay(1200)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (transferProgress.status == TransferProgressStatus.COMPLETED ||
                transferProgress.status == TransferProgressStatus.FAILED ||
                transferProgress.status == TransferProgressStatus.CANCELLED) {
                EverDropWifiDirectManager.resetTransferState()
                onDismiss()
            }
            // During WAITING_CONFIRMATION or RECEIVING, ignore accidental outside touch
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = {
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
                        text = stringResource(R.string.transfer_wants_to_share, senderName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "via Ever Drop",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
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
                        val iconRes = if (payloadType == TransferType.FILE) {
                            R.drawable.rounded_app_registration_24
                        } else {
                            R.drawable.rounded_edit_24
                        }
                        val categoryName = if (payloadType == TransferType.FILE) "Files" else "Text"
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    color = ColorUtil.getPastelColorFor(categoryName),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = null,
                                tint = ColorUtil.getVibrantColorFor(categoryName),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = payloadName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (payloadType == TransferType.FILE && totalBytes > 0) {
                                Text(
                                    text = formatBytes(totalBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (transferProgress.status == TransferProgressStatus.RECEIVING) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { transferProgress.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            text = transferProgress.message ?: "Receiving…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (transferProgress.status == TransferProgressStatus.COMPLETED) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.rounded_check_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (payloadType == TransferType.TEXT) {
                                stringResource(R.string.receive_text_copied)
                            } else {
                                stringResource(R.string.receive_file_saved)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (transferProgress.status == TransferProgressStatus.WAITING_CONFIRMATION ||
                incomingRequest != null) {
                Button(
                    onClick = {
                        EverDropWifiDirectManager.acceptIncomingTransfer(context)
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.transfer_action_receive))
                }
            } else if (transferProgress.status == TransferProgressStatus.COMPLETED) {
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
                            EverDropWifiDirectManager.resetTransferState()
                            onDismiss()
                        },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(stringResource(R.string.action_open_file))
                    }
                } else {
                    Button(
                        onClick = {
                            EverDropWifiDirectManager.resetTransferState()
                            onDismiss()
                        },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Done")
                    }
                }
            }
        },
        dismissButton = {
            if (transferProgress.status == TransferProgressStatus.WAITING_CONFIRMATION ||
                incomingRequest != null ||
                transferProgress.status == TransferProgressStatus.RECEIVING) {
                OutlinedButton(
                    onClick = {
                        EverDropWifiDirectManager.rejectIncomingTransfer(context)
                        onDismiss()
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.transfer_action_cancel))
                }
            } else if (transferProgress.status == TransferProgressStatus.COMPLETED) {
                OutlinedButton(
                    onClick = {
                        EverDropWifiDirectManager.resetTransferState()
                        onDismiss()
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Dismiss")
                }
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) digitGroups = units.size - 1
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.getDefault(), "%.1f %s", value, units[digitGroups])
}
