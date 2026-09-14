package com.sameerasw.medrop.domain.model

import android.bluetooth.BluetoothDevice
import android.net.wifi.p2p.WifiP2pDevice

enum class AudioShareMode {
    BROADCAST,
    LISTEN,
    TALKIE // Duplex Talk & Listen
}

enum class AudioTransportType {
    WIFI_P2P,
    WIFI_LAN,
    BLUETOOTH_RFCOMM
}

sealed class AudioConnectionState {
    data object Idle : AudioConnectionState()
    data object Advertising : AudioConnectionState()
    data object Discovering : AudioConnectionState()
    data class Connecting(val deviceName: String) : AudioConnectionState()
    data class Connected(val deviceName: String, val transport: AudioTransportType) : AudioConnectionState()
    data class Streaming(
        val deviceName: String,
        val transport: AudioTransportType,
        val isMuted: Boolean = false,
        val isDuplex: Boolean = true
    ) : AudioConnectionState()
    data class Error(val message: String) : AudioConnectionState()
}

data class AudioDiscoveredDevice(
    val id: String,
    val name: String,
    val host: String? = null,
    val port: Int = 0,
    val transportType: AudioTransportType,
    val bluetoothDevice: BluetoothDevice? = null,
    val wifiP2pDevice: WifiP2pDevice? = null,
    val lastSeen: Long = System.currentTimeMillis()
)

data class P2pChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderName: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean = false
)
