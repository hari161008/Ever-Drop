package com.sameerasw.medrop.ui.features

import android.Manifest
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sameerasw.medrop.R
import com.sameerasw.medrop.domain.model.AudioConnectionState
import com.sameerasw.medrop.domain.model.AudioDiscoveredDevice
import com.sameerasw.medrop.domain.model.AudioShareMode
import com.sameerasw.medrop.domain.model.AudioTransportType
import com.sameerasw.medrop.utils.HapticUtil
import com.sameerasw.medrop.utils.NetworkAudioManager

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults

@Composable
fun EverDropAudioShareUI(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current

    val currentMode by NetworkAudioManager.currentMode.collectAsState()
    val connectionState by NetworkAudioManager.connectionState.collectAsState()
    val discoveredDevices by NetworkAudioManager.discoveredDevices.collectAsState()
    val isMuted by NetworkAudioManager.isMuted.collectAsState()
    val isSpeakerMuted by NetworkAudioManager.isSpeakerMuted.collectAsState()
    val volume by NetworkAudioManager.volume.collectAsState()
    val chatMessages by NetworkAudioManager.chatMessages.collectAsState()
    val isBtFallbackAvailable by NetworkAudioManager.isBluetoothFallbackAvailable.collectAsState()

    // Required permissions
    val requiredPermissions = remember {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        perms
    }

    var hasAllPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAllPermissions = results.values.all { it }
        if (hasAllPermissions) {
            NetworkAudioManager.initialize(context)
            NetworkAudioManager.setMode(currentMode)
        }
    }

    DisposableEffect(Unit) {
        NetworkAudioManager.initialize(context)
        if (hasAllPermissions) {
            NetworkAudioManager.setMode(currentMode)
        }
        onDispose {
            NetworkAudioManager.stopAll()
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Selector: Walkie-Talkie vs Broadcast vs Listen
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.audio_share_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Walkie-Talkie Tab (Primary Duplex Mode)
                    val isTalkie = currentMode == AudioShareMode.TALKIE
                    Button(
                        onClick = {
                            if (!isTalkie) {
                                HapticUtil.performVirtualKeyHaptic(view)
                                NetworkAudioManager.setMode(AudioShareMode.TALKIE)
                            }
                        },
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTalkie) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (isTalkie) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_devices_24),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.audio_mode_talkie),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // Broadcast Tab
                    val isBroadcast = currentMode == AudioShareMode.BROADCAST
                    Button(
                        onClick = {
                            if (!isBroadcast) {
                                HapticUtil.performVirtualKeyHaptic(view)
                                NetworkAudioManager.setMode(AudioShareMode.BROADCAST)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBroadcast) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (isBroadcast) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_mic_24),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.audio_mode_broadcast),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // Listen Tab
                    val isListen = currentMode == AudioShareMode.LISTEN
                    Button(
                        onClick = {
                            if (!isListen) {
                                HapticUtil.performVirtualKeyHaptic(view)
                                NetworkAudioManager.setMode(AudioShareMode.LISTEN)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isListen) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (isListen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_volume_up_24),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.audio_mode_listen),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Text(
                    text = when (currentMode) {
                        AudioShareMode.TALKIE -> stringResource(R.string.audio_talkie_desc)
                        AudioShareMode.BROADCAST -> stringResource(R.string.audio_broadcast_desc)
                        AudioShareMode.LISTEN -> stringResource(R.string.audio_listen_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Permissions Guard Card
        if (!hasAllPermissions) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.audio_permissions_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = {
                            permissionsLauncher.launch(requiredPermissions.toTypedArray())
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.audio_grant_permissions))
                    }
                }
            }
        }

        // Active Session Card (Connected / Streaming / Error)
        when (val state = connectionState) {
            is AudioConnectionState.Streaming -> {
                StreamingActiveCard(
                    deviceName = state.deviceName,
                    transport = state.transport,
                    isSender = currentMode == AudioShareMode.BROADCAST,
                    isDuplex = currentMode == AudioShareMode.TALKIE || state.isDuplex,
                    isMuted = isMuted,
                    isSpeakerMuted = isSpeakerMuted,
                    volume = volume,
                    chatMessages = chatMessages,
                    onToggleMute = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        NetworkAudioManager.toggleMute()
                    },
                    onToggleSpeakerMute = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        NetworkAudioManager.toggleSpeakerMute()
                    },
                    onVolumeChange = { NetworkAudioManager.setVolume(it) },
                    onSendMessage = { text ->
                        NetworkAudioManager.sendChatMessage(text)
                    },
                    onDisconnect = {
                        HapticUtil.performHeavyHaptic(view)
                        NetworkAudioManager.disconnect()
                    }
                )
            }
            is AudioConnectionState.Connecting -> {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Connecting to ${state.deviceName}…",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedButton(
                            onClick = { NetworkAudioManager.disconnect() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.audio_disconnect))
                        }
                    }
                }
            }
            is AudioConnectionState.Error -> {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        FilledTonalButton(
                            onClick = { NetworkAudioManager.setMode(currentMode) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
            else -> {
                // Mode Specific Content when IDLE / DISCOVERING / ADVERTISING
                if (currentMode == AudioShareMode.LISTEN) {
                    ListenWaitingCard()
                } else {
                    BroadcastDiscoveryCard(
                        devices = discoveredDevices,
                        isBtFallbackAvailable = isBtFallbackAvailable,
                        onConnect = { device ->
                            HapticUtil.performVirtualKeyHaptic(view)
                            NetworkAudioManager.connectToDevice(device)
                        },
                        onSearchBluetooth = {
                            HapticUtil.performVirtualKeyHaptic(view)
                            NetworkAudioManager.queryBluetoothDevices()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenWaitingCard() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = pulseAlpha)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_volume_up_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = stringResource(R.string.audio_waiting_listener),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            AssistChip(
                onClick = {},
                label = { Text("Advertising on Wi-Fi Direct, LAN & Bluetooth") },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    }
}

@Composable
private fun BroadcastDiscoveryCard(
    devices: List<AudioDiscoveredDevice>,
    isBtFallbackAvailable: Boolean,
    onConnect: (AudioDiscoveredDevice) -> Unit,
    onSearchBluetooth: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.audio_discovered_devices),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (isBtFallbackAvailable) {
                    FilledTonalButton(
                        onClick = onSearchBluetooth,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.audio_bt_fallback_btn),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.audio_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                devices.forEach { device ->
                    DiscoveredDeviceItem(
                        device = device,
                        onConnect = { onConnect(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceItem(
    device: AudioDiscoveredDevice,
    onConnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_devices_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when (device.transportType) {
                        AudioTransportType.WIFI_P2P -> "Wi-Fi Direct P2P"
                        AudioTransportType.WIFI_LAN -> stringResource(R.string.audio_wifi_lan)
                        AudioTransportType.BLUETOOTH_RFCOMM -> stringResource(R.string.audio_bt_rfcomm)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = onConnect,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.audio_connect))
        }
    }
}

@Composable
private fun StreamingActiveCard(
    deviceName: String,
    transport: AudioTransportType,
    isSender: Boolean,
    isDuplex: Boolean,
    isMuted: Boolean,
    isSpeakerMuted: Boolean,
    volume: Float,
    chatMessages: List<com.sameerasw.medrop.domain.model.P2pChatMessage>,
    onToggleMute: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSendMessage: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    var chatInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Animated Live Indicator
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .graphicsLayer {
                        scaleX = if (isMuted && isSpeakerMuted) 1f else scale
                        scaleY = if (isMuted && isSpeakerMuted) 1f else scale
                    }
                    .clip(CircleShape)
                    .background(
                        if (isMuted && isSpeakerMuted) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = if (isDuplex) {
                        painterResource(if (isMuted) R.drawable.rounded_volume_off_24 else R.drawable.rounded_mic_24)
                    } else if (isSender) {
                        painterResource(if (isMuted) R.drawable.rounded_volume_off_24 else R.drawable.rounded_mic_24)
                    } else {
                        painterResource(if (isSpeakerMuted) R.drawable.rounded_volume_off_24 else R.drawable.rounded_volume_up_24)
                    },
                    contentDescription = null,
                    tint = if (isMuted && isSpeakerMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = if (isDuplex) "Connected with $deviceName" else if (isSender) "Streaming to $deviceName" else "Listening from $deviceName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (transport) {
                        AudioTransportType.WIFI_P2P -> "Connected via Wi-Fi Direct P2P • Full-Duplex"
                        AudioTransportType.WIFI_LAN -> "Connected via Wi-Fi LAN • Full-Duplex"
                        AudioTransportType.BLUETOOTH_RFCOMM -> "Connected via Bluetooth RFCOMM • Full-Duplex"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Audio Controls (Mic Mute, Speaker Mute, Disconnect)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Mute Button
                FilledTonalButton(
                    onClick = onToggleMute,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        painter = painterResource(if (isMuted) R.drawable.rounded_volume_off_24 else R.drawable.rounded_mic_24),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isMuted) "Mic Off" else "Mic On",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Speaker Mute Button
                FilledTonalButton(
                    onClick = onToggleSpeakerMute,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        painter = painterResource(if (isSpeakerMuted) R.drawable.rounded_volume_off_24 else R.drawable.rounded_volume_up_24),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isSpeakerMuted) "Muted" else "Speaker",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Disconnect Button
                Button(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = stringResource(R.string.audio_disconnect),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Volume Slider
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.audio_volume),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "${(volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Live In-App P2P Chat Section (Mobile Talkie Chat UI ported to Ever Drop)
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.chat_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("P2P Direct", style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    // Message history list
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(8.dp)
                    ) {
                        if (chatMessages.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_no_messages),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(chatMessages, key = { it.id }) { msg ->
                                    val isMe = msg.isFromMe
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(
                                                    RoundedCornerShape(
                                                        topStart = 12.dp,
                                                        topEnd = 12.dp,
                                                        bottomStart = if (isMe) 12.dp else 2.dp,
                                                        bottomEnd = if (isMe) 2.dp else 12.dp
                                                    )
                                                )
                                                .background(
                                                    if (isMe) MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                                )
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Column {
                                                if (!isMe) {
                                                    Text(
                                                        text = msg.senderName,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Text(
                                                    text = msg.message,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Chat Input Field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.chat_hint),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                            )
                        )

                        IconButton(
                            onClick = {
                                if (chatInput.isNotBlank()) {
                                    onSendMessage(chatInput.trim())
                                    chatInput = ""
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.rounded_send_24),
                                contentDescription = stringResource(R.string.chat_send),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
