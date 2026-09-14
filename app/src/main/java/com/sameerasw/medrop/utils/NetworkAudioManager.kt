package com.sameerasw.medrop.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.sameerasw.medrop.domain.model.AudioConnectionState
import com.sameerasw.medrop.domain.model.AudioDiscoveredDevice
import com.sameerasw.medrop.domain.model.AudioShareMode
import com.sameerasw.medrop.domain.model.AudioTransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * NetworkAudioManager
 *
 * Modular manager handling peer-to-peer audio streaming:
 * - Transport: Wi-Fi Direct P2P (Mobile Talkie engine), Wi-Fi LAN (NSD mDNS + TCP sockets), with Classic Bluetooth RFCOMM fallback
 * - Audio Pipeline: 16-bit PCM @ 16kHz mono, framed with 4-byte length prefixes
 * - Jitter Buffering on Receiver to ensure smooth playback
 * - Sender: AudioRecord (mic) capture + Mute control
 * - Receiver: AudioTrack playback + Volume & Mute control
 */
@SuppressLint("MissingPermission")
object NetworkAudioManager {

    private const val TAG = "NetworkAudioManager"
    private const val SERVICE_TYPE = "_everdrop-audio._tcp."
    private const val DEFAULT_SERVICE_NAME = "EverDropAudio"
    private val RFCOMM_UUID: UUID = UUID.fromString("e8c3b7a0-6f2b-4e12-8822-0d172e27d81a")

    // Audio format constants
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val CHUNK_SIZE = 640 // 20ms of 16kHz 16-bit mono = 320 samples * 2 bytes = 640 bytes

    // Packet Multiplexing Types (Mobile Talkie protocol model)
    private const val PACKET_TYPE_AUDIO: Byte = 0x01
    private const val PACKET_TYPE_CHAT: Byte = 0x02
    private const val PACKET_TYPE_CONTROL: Byte = 0x03

    // State flows
    private val _currentMode = MutableStateFlow(AudioShareMode.TALKIE)
    val currentMode: StateFlow<AudioShareMode> = _currentMode.asStateFlow()

    private val _connectionState = MutableStateFlow<AudioConnectionState>(AudioConnectionState.Idle)
    val connectionState: StateFlow<AudioConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<AudioDiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<AudioDiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isMuted = MutableStateFlow(false) // Microphone mute
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerMuted = MutableStateFlow(false) // Speaker mute
    val isSpeakerMuted: StateFlow<Boolean> = _isSpeakerMuted.asStateFlow()

    private val _volume = MutableStateFlow(1.0f) // 0.0 to 1.0
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _isBluetoothFallbackAvailable = MutableStateFlow(false)
    val isBluetoothFallbackAvailable: StateFlow<Boolean> = _isBluetoothFallbackAvailable.asStateFlow()

    // Live In-App P2P Chat Messages
    private val _chatMessages = MutableStateFlow<List<com.sameerasw.medrop.domain.model.P2pChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<com.sameerasw.medrop.domain.model.P2pChatMessage>> = _chatMessages.asStateFlow()

    // Internal resources
    private val scope = CoroutineScope(Dispatchers.IO)
    private var appContext: Context? = null
    private var nsdManager: NsdManager? = null

    // Wi-Fi Direct (P2P) resources
    private var wifiP2pConnection: EverDropWifiP2pConnection? = null
    private var wifiP2pReceiver: BroadcastReceiver? = null
    private var pendingP2pConnectDevice: AudioDiscoveredDevice? = null

    // Sockets & streams
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var bluetoothServerSocket: BluetoothServerSocket? = null
    private var activeBtSocket: BluetoothSocket? = null
    private var activeInputStream: InputStream? = null
    private var activeOutputStream: OutputStream? = null

    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    // Coroutine Jobs
    private var streamingJob: Job? = null
    private var senderAudioJob: Job? = null
    private var receiverAudioJob: Job? = null
    private var serverAcceptJob: Job? = null
    private var discoveryTimeoutJob: Job? = null

    // NSD Listeners
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isNsdRegistered = false
    private var isNsdDiscovering = false

    // Bluetooth discovery receiver
    private var bluetoothReceiver: BroadcastReceiver? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        nsdManager = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (wifiP2pConnection == null) {
            wifiP2pConnection = EverDropWifiP2pConnection(app)
        }
    }

    /**
     * Switch operating mode: TALKIE (Walkie-Talkie), BROADCAST (Sender), LISTEN (Receiver)
     */
    fun setMode(mode: AudioShareMode) {
        if (_currentMode.value == mode && _connectionState.value !is AudioConnectionState.Error) {
            return
        }
        stopAll()
        _currentMode.value = mode
        when (mode) {
            AudioShareMode.TALKIE -> startTalkieMode()
            AudioShareMode.LISTEN -> startListenMode()
            AudioShareMode.BROADCAST -> startBroadcastMode()
        }
    }

    /**
     * Start Walkie-Talkie Mode (Duplex Talk & Listen + Live Chat):
     * - Advertises NSD service and Wi-Fi Direct listen socket
     * - Simultaneously discovers nearby peers on Wi-Fi Direct and LAN
     * - Whoever connects first establishes bidirectional symmetric audio & chat!
     */
    fun startTalkieMode() {
        stopAll()
        _currentMode.value = AudioShareMode.TALKIE
        _connectionState.value = AudioConnectionState.Advertising
        _discoveredDevices.value = emptyList()
        _isBluetoothFallbackAvailable.value = false

        val context = appContext ?: return
        val deviceName = Build.MODEL ?: "Android Device"

        // 1. Start TCP ServerSocket
        try {
            val tcpServer = ServerSocket(0)
            serverSocket = tcpServer
            val localPort = tcpServer.localPort

            // 2. Register NSD Service
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "$DEFAULT_SERVICE_NAME-$deviceName"
                serviceType = SERVICE_TYPE
                port = localPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(service: NsdServiceInfo?) {
                    isNsdRegistered = true
                    Log.d(TAG, "Talkie NSD registered on port $localPort")
                }
                override fun onRegistrationFailed(service: NsdServiceInfo?, errorCode: Int) {
                    isNsdRegistered = false
                }
                override fun onServiceUnregistered(service: NsdServiceInfo?) {
                    isNsdRegistered = false
                }
                override fun onUnregistrationFailed(service: NsdServiceInfo?, errorCode: Int) {
                    isNsdRegistered = false
                }
            }

            try {
                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering Talkie NSD: ${e.message}")
            }

            // 3. Fallback Bluetooth Server
            startBluetoothServer()

            // 4. Wi-Fi Direct P2P Listen & Discover
            startWifiP2pListenMode()
            startNsdDiscovery()
            startWifiP2pBroadcastDiscovery()

            // 5. Accept incoming TCP connection for Duplex Talkie
            serverAcceptJob = scope.launch {
                try {
                    val socket = tcpServer.accept()
                    activeSocket = socket
                    activeInputStream = socket.getInputStream()
                    activeOutputStream = socket.getOutputStream()
                    val remoteName = socket.inetAddress.hostAddress ?: "Walkie-Talkie Peer"
                    _connectionState.value = AudioConnectionState.Connected(remoteName, AudioTransportType.WIFI_LAN)
                    startDuplexTalkiePipeline(remoteName, AudioTransportType.WIFI_LAN)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Talkie server accept error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start talkie mode: ${e.message}")
            _connectionState.value = AudioConnectionState.Error("Failed to start walkie-talkie: ${e.message}")
        }
    }

    /**
     * Start Listen (Receiver) Mode:
     * - Opens TCP ServerSocket and BluetoothServerSocket
     * - Registers NSD service on local LAN
     * - Awaits incoming sender connection
     */
    fun startListenMode() {
        stopAll()
        _currentMode.value = AudioShareMode.LISTEN
        _connectionState.value = AudioConnectionState.Advertising

        val context = appContext ?: return
        val deviceName = Build.MODEL ?: "Android Device"

        // 1. Start TCP ServerSocket
        try {
            val tcpServer = ServerSocket(0)
            serverSocket = tcpServer
            val localPort = tcpServer.localPort

            // 2. Register NSD Service
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "$DEFAULT_SERVICE_NAME-$deviceName"
                serviceType = SERVICE_TYPE
                port = localPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(service: NsdServiceInfo?) {
                    isNsdRegistered = true
                    Log.d(TAG, "NSD service registered on port $localPort")
                }

                override fun onRegistrationFailed(service: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "NSD registration failed: $errorCode")
                    isNsdRegistered = false
                }

                override fun onServiceUnregistered(service: NsdServiceInfo?) {
                    isNsdRegistered = false
                }

                override fun onUnregistrationFailed(service: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "NSD unregistration failed: $errorCode")
                }
            }

            try {
                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering NSD: ${e.message}")
            }

            // 3. Start Bluetooth ServerSocket for fallback
            startBluetoothServer()

            // 4. Start Wi-Fi Direct P2P Listen Mode (Mobile Talkie P2P engine)
            startWifiP2pListenMode()

            // 5. Accept incoming TCP connection in background
            serverAcceptJob = scope.launch {
                try {
                    val socket = tcpServer.accept()
                    activeSocket = socket
                    activeInputStream = socket.getInputStream()
                    activeOutputStream = socket.getOutputStream()
                    val remoteName = socket.inetAddress.hostAddress ?: "Sender"
                    _connectionState.value = AudioConnectionState.Connected(remoteName, AudioTransportType.WIFI_LAN)
                    startDuplexTalkiePipeline(remoteName, AudioTransportType.WIFI_LAN)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Server accept error: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listener: ${e.message}")
            _connectionState.value = AudioConnectionState.Error("Failed to start listener: ${e.message}")
        }
    }

    /**
     * Starts Bluetooth RFCOMM server socket for listener fallback
     */
    private fun startBluetoothServer() {
        val btManager = appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) return

        scope.launch {
            try {
                val btServer = btAdapter.listenUsingRfcommWithServiceRecord(DEFAULT_SERVICE_NAME, RFCOMM_UUID)
                bluetoothServerSocket = btServer
                val btSocket = btServer.accept()
                if (activeSocket == null) {
                    activeBtSocket = btSocket
                    activeInputStream = btSocket.inputStream
                    activeOutputStream = btSocket.outputStream
                    val remoteName = btSocket.remoteDevice?.name ?: "Bluetooth Sender"
                    _connectionState.value = AudioConnectionState.Connected(remoteName, AudioTransportType.BLUETOOTH_RFCOMM)
                    startDuplexTalkiePipeline(remoteName, AudioTransportType.BLUETOOTH_RFCOMM)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Bluetooth server ended: ${e.message}")
            }
        }
    }

    /**
     * Start Broadcast (Sender) Mode:
     * - Begins NSD discovery on local LAN
     * - Begins Wi-Fi Direct P2P peer discovery (Mobile Talkie engine)
     * - Times out after 7 seconds: enables Bluetooth fallback scan if no devices found
     */
    fun startBroadcastMode() {
        stopAll()
        _currentMode.value = AudioShareMode.BROADCAST
        _connectionState.value = AudioConnectionState.Discovering
        _discoveredDevices.value = emptyList()
        _isBluetoothFallbackAvailable.value = false

        startNsdDiscovery()
        startWifiP2pBroadcastDiscovery()

        // Fallback timer: if after 7 seconds no NSD device is found, search Bluetooth devices
        discoveryTimeoutJob = scope.launch {
            delay(7000)
            if (_discoveredDevices.value.isEmpty()) {
                _isBluetoothFallbackAvailable.value = true
                queryBluetoothDevices()
            }
        }
    }

    private fun startNsdDiscovery() {
        val manager = nsdManager ?: return
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {
                isNsdDiscovering = true
                Log.d(TAG, "NSD discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo?) {
                if (service == null) return
                if (service.serviceType.contains("everdrop-audio") || service.serviceType.contains("_tcp")) {
                    resolveNsdService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo?) {
                if (service == null) return
                val current = _discoveredDevices.value.toMutableList()
                current.removeAll { it.name == service.serviceName }
                _discoveredDevices.value = current
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                isNsdDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                isNsdDiscovering = false
                Log.e(TAG, "Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                isNsdDiscovering = false
            }
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting NSD discovery: ${e.message}")
        }
    }

    private fun resolveNsdService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo?.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                if (resolved == null) return
                val host = resolved.host?.hostAddress ?: return
                val port = resolved.port
                val name = resolved.serviceName.replace("$DEFAULT_SERVICE_NAME-", "")

                val device = AudioDiscoveredDevice(
                    id = "$host:$port",
                    name = name,
                    host = host,
                    port = port,
                    transportType = AudioTransportType.WIFI_LAN
                )

                val current = _discoveredDevices.value.toMutableList()
                current.removeAll { it.id == device.id }
                current.add(0, device)
                _discoveredDevices.value = current
            }
        }

        try {
            manager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving service: ${e.message}")
        }
    }

    /**
     * Query paired and nearby Bluetooth devices for RFCOMM fallback
     */
    fun queryBluetoothDevices() {
        val btManager = appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return
        if (!btAdapter.isEnabled) return

        val devices = mutableListOf<AudioDiscoveredDevice>()
        // 1. Bonded devices
        try {
            val bonded = btAdapter.bondedDevices
            bonded?.forEach { dev ->
                devices.add(
                    AudioDiscoveredDevice(
                        id = dev.address,
                        name = dev.name ?: dev.address,
                        transportType = AudioTransportType.BLUETOOTH_RFCOMM,
                        bluetoothDevice = dev
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying bonded BT devices: ${e.message}")
        }

        val existing = _discoveredDevices.value.filter { it.transportType == AudioTransportType.WIFI_LAN }
        _discoveredDevices.value = existing + devices

        // 2. Discover nearby Bluetooth devices if permission allows
        try {
            if (btAdapter.isDiscovering) {
                btAdapter.cancelDiscovery()
            }
            bluetoothReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null) {
                            val btDevice = AudioDiscoveredDevice(
                                id = device.address,
                                name = device.name ?: device.address,
                                transportType = AudioTransportType.BLUETOOTH_RFCOMM,
                                bluetoothDevice = device
                            )
                            val list = _discoveredDevices.value.toMutableList()
                            if (list.none { it.id == btDevice.id }) {
                                list.add(btDevice)
                                _discoveredDevices.value = list
                            }
                        }
                    }
                }
            }
            appContext?.registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            btAdapter.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BT scan: ${e.message}")
        }
    }

    /**
     * Connect to a discovered device and start streaming audio
     */
    fun connectToDevice(device: AudioDiscoveredDevice) {
        _connectionState.value = AudioConnectionState.Connecting(device.name)

        scope.launch {
            try {
                when (device.transportType) {
                    AudioTransportType.WIFI_P2P -> {
                        val p2p = wifiP2pConnection ?: throw IllegalArgumentException("Wi-Fi P2P not initialized")
                        pendingP2pConnectDevice = device
                        p2p.connect(
                            deviceAddress = device.id,
                            onSuccess = {
                                Log.d(TAG, "Wi-Fi Direct connect invitation sent to ${device.name}")
                            },
                            onFailure = { reason ->
                                Log.w(TAG, "Wi-Fi Direct connect failed: code $reason")
                                _connectionState.value = AudioConnectionState.Error("Wi-Fi Direct connection failed (code $reason)")
                                pendingP2pConnectDevice = null
                            }
                        )
                    }
                    AudioTransportType.WIFI_LAN -> {
                        val host = device.host ?: throw IllegalArgumentException("Host address is null")
                        val socket = Socket()
                        socket.connect(InetSocketAddress(host, device.port), 5000)
                        activeSocket = socket
                        activeInputStream = socket.getInputStream()
                        activeOutputStream = socket.getOutputStream()
                        _connectionState.value = AudioConnectionState.Connected(device.name, AudioTransportType.WIFI_LAN)
                        startDuplexTalkiePipeline(device.name, AudioTransportType.WIFI_LAN)
                    }
                    AudioTransportType.BLUETOOTH_RFCOMM -> {
                        val btDev = device.bluetoothDevice ?: throw IllegalArgumentException("Bluetooth device is null")
                        val socket = btDev.createRfcommSocketToServiceRecord(RFCOMM_UUID)
                        socket.connect()
                        activeBtSocket = socket
                        activeInputStream = socket.inputStream
                        activeOutputStream = socket.outputStream
                        _connectionState.value = AudioConnectionState.Connected(device.name, AudioTransportType.BLUETOOTH_RFCOMM)
                        startDuplexTalkiePipeline(device.name, AudioTransportType.BLUETOOTH_RFCOMM)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                _connectionState.value = AudioConnectionState.Error("Connection failed: ${e.message}")
            }
        }
    }

    private fun registerWifiP2pReceiver(isListenMode: Boolean) {
        unregisterWifiP2pReceiver()
        val context = appContext ?: return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        wifiP2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (!isListenMode || _currentMode.value == AudioShareMode.TALKIE) {
                            wifiP2pConnection?.requestPeers { peers ->
                                val p2pDevices = peers.map { dev ->
                                    AudioDiscoveredDevice(
                                        id = dev.deviceAddress,
                                        name = dev.deviceName.ifBlank { "Nearby Wi-Fi Peer" },
                                        transportType = AudioTransportType.WIFI_P2P,
                                        wifiP2pDevice = dev
                                    )
                                }
                                val currentOther = _discoveredDevices.value.filter { it.transportType != AudioTransportType.WIFI_P2P }
                                _discoveredDevices.value = p2pDevices + currentOther
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val p2pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        }

                        if (p2pInfo != null && p2pInfo.groupFormed && activeSocket == null) {
                            handleWifiP2pGroupConnected(p2pInfo, isListenMode)
                        }
                    }
                }
            }
        }

        try {
            context.registerReceiver(wifiP2pReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering Wi-Fi P2P audio receiver: ${e.message}")
        }
    }

    private fun handleWifiP2pGroupConnected(info: WifiP2pInfo, isListenMode: Boolean) {
        val p2p = wifiP2pConnection ?: return
        scope.launch {
            try {
                val socket = p2p.establishSocket(info, EverDropWifiP2pConnection.DEFAULT_AUDIO_PORT)
                if (socket != null && activeSocket == null) {
                    activeSocket = socket
                    activeInputStream = socket.getInputStream()
                    activeOutputStream = socket.getOutputStream()
                    val remoteName = if (pendingP2pConnectDevice != null) {
                        val n = pendingP2pConnectDevice?.name ?: "Wi-Fi Direct Peer"
                        pendingP2pConnectDevice = null
                        n
                    } else if (info.isGroupOwner) {
                        "Wi-Fi Direct Peer"
                    } else {
                        "Wi-Fi Direct Host"
                    }
                    _connectionState.value = AudioConnectionState.Connected(remoteName, AudioTransportType.WIFI_P2P)
                    startDuplexTalkiePipeline(remoteName, AudioTransportType.WIFI_P2P)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wi-Fi P2P audio socket error: ${e.message}")
                if (!isListenMode) {
                    _connectionState.value = AudioConnectionState.Error("Wi-Fi Direct audio stream error: ${e.message}")
                    pendingP2pConnectDevice = null
                }
            }
        }
    }

    private fun startWifiP2pListenMode() {
        val p2p = wifiP2pConnection ?: return
        p2p.initialize()
        registerWifiP2pReceiver(isListenMode = true)
        p2p.discoverPeers(
            onSuccess = { Log.d(TAG, "Wi-Fi Direct listen mode (peer discovery) started") },
            onFailure = { Log.w(TAG, "Wi-Fi Direct listen mode discoverPeers failed: $it") }
        )
    }

    private fun startWifiP2pBroadcastDiscovery() {
        val p2p = wifiP2pConnection ?: return
        p2p.initialize()
        registerWifiP2pReceiver(isListenMode = false)
        p2p.discoverPeers(
            onSuccess = { Log.d(TAG, "Wi-Fi Direct broadcast discovery started") },
            onFailure = { Log.w(TAG, "Wi-Fi Direct broadcast discoverPeers failed: $it") }
        )
    }

    private fun unregisterWifiP2pReceiver() {
        if (wifiP2pReceiver != null) {
            try {
                appContext?.unregisterReceiver(wifiP2pReceiver)
            } catch (_: Exception) {}
            wifiP2pReceiver = null
        }
    }

    /**
     * Unified Full-Duplex Walkie-Talkie & Chat Pipeline:
     * - Captures microphone audio and transmits PACKET_TYPE_AUDIO (0x01)
     * - Receives PACKET_TYPE_AUDIO and plays out through speaker via AudioTrack & jitter buffer
     * - Receives PACKET_TYPE_CHAT (0x02) and decodes live incoming chat messages into _chatMessages
     * - Both phones can talk and hear each other simultaneously without switching modes!
     */
    private fun startDuplexTalkiePipeline(deviceName: String, transport: AudioTransportType) {
        stopAudioPipelines()
        _connectionState.value = AudioConnectionState.Streaming(
            deviceName = deviceName,
            transport = transport,
            isMuted = _isMuted.value,
            isDuplex = true
        )

        // Start playback pipeline
        startDuplexAudioPlayback()

        // Start incoming packet reader (Audio + Chat multiplexer)
        startDuplexPacketReceiver()

        // Start microphone capture pipeline
        startDuplexAudioCapture()
    }

    private fun startDuplexAudioCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE * 4)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord = record

            // Acoustic enhancements (from Mobile Talkie walkie talkie pipeline)
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply {
                        enabled = true
                    }
                } catch (_: Exception) {}
            }
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                        enabled = true
                    }
                } catch (_: Exception) {}
            }

            record.startRecording()

            val outStream = activeOutputStream ?: return
            val dataOut = DataOutputStream(outStream)

            senderAudioJob = scope.launch(Dispatchers.IO) {
                val pcmBuffer = ByteArray(CHUNK_SIZE)
                val silentBuffer = ByteArray(CHUNK_SIZE)

                try {
                    while (isActive) {
                        val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
                        if (bytesRead > 0) {
                            val dataToSend = if (_isMuted.value) silentBuffer else pcmBuffer
                            synchronized(outStream) {
                                // Packet Header: [Type (Byte)][Length (Int)][Payload]
                                dataOut.writeByte(PACKET_TYPE_AUDIO.toInt())
                                dataOut.writeInt(bytesRead)
                                dataOut.write(dataToSend, 0, bytesRead)
                                dataOut.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Duplex audio send error: ${e.message}")
                        _connectionState.value = AudioConnectionState.Error("Audio send error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting mic capture: ${e.message}")
        }
    }

    private val jitterBuffer = LinkedBlockingQueue<ByteArray>(25)

    private fun startDuplexAudioPlayback() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE * 6)

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .setEncoding(ENCODING)
                .build()

            val track = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed")
                return
            }

            audioTrack = track
            updateVolumeInternal()
            track.play()

            // Playback thread consuming jitter buffer
            streamingJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val chunk = jitterBuffer.poll()
                        if (chunk != null) {
                            if (!_isSpeakerMuted.value) {
                                track.write(chunk, 0, chunk.size)
                            }
                        } else {
                            delay(5)
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting audio playback: ${e.message}")
        }
    }

    private fun startDuplexPacketReceiver() {
        val inStream = activeInputStream ?: return
        val dataIn = DataInputStream(inStream)

        receiverAudioJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val packetType = dataIn.readByte()
                    val length = dataIn.readInt()
                    if (length in 1..(256 * 1024)) {
                        val payload = ByteArray(length)
                        dataIn.readFully(payload)

                        when (packetType) {
                            PACKET_TYPE_AUDIO -> {
                                // Offer PCM chunk to jitter buffer
                                if (!jitterBuffer.offer(payload)) {
                                    jitterBuffer.poll()
                                    jitterBuffer.offer(payload)
                                }
                            }
                            PACKET_TYPE_CHAT -> {
                                val chatText = String(payload, Charsets.UTF_8)
                                val current = _connectionState.value
                                val sender = if (current is AudioConnectionState.Streaming) current.deviceName else "Peer"
                                val newMsg = com.sameerasw.medrop.domain.model.P2pChatMessage(
                                    senderName = sender,
                                    message = chatText,
                                    isFromMe = false
                                )
                                val updated = _chatMessages.value.toMutableList().apply { add(newMsg) }
                                _chatMessages.value = updated
                            }
                        }
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Duplex stream read error: ${e.message}")
                    _connectionState.value = AudioConnectionState.Error("Connection ended: ${e.message}")
                }
            }
        }
    }

    /**
     * Send a Live In-App Chat message across the active duplex connection
     */
    fun sendChatMessage(message: String): Boolean {
        if (message.isBlank()) return false
        val outStream = activeOutputStream ?: return false
        val myDeviceName = Build.MODEL ?: "Me"

        return try {
            val payload = message.toByteArray(Charsets.UTF_8)
            val dataOut = DataOutputStream(outStream)
            synchronized(outStream) {
                dataOut.writeByte(PACKET_TYPE_CHAT.toInt())
                dataOut.writeInt(payload.size)
                dataOut.write(payload)
                dataOut.flush()
            }

            val msg = com.sameerasw.medrop.domain.model.P2pChatMessage(
                senderName = myDeviceName,
                message = message,
                isFromMe = true
            )
            val updated = _chatMessages.value.toMutableList().apply { add(msg) }
            _chatMessages.value = updated
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending chat message: ${e.message}")
            false
        }
    }

    /**
     * Toggle Microphone Mute
     */
    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        val current = _connectionState.value
        if (current is AudioConnectionState.Streaming) {
            _connectionState.value = current.copy(isMuted = _isMuted.value)
        }
    }

    /**
     * Toggle Speaker Mute
     */
    fun toggleSpeakerMute() {
        _isSpeakerMuted.value = !_isSpeakerMuted.value
        updateVolumeInternal()
    }

    /**
     * Set playback volume for receiver (0.0 to 1.0)
     */
    fun setVolume(vol: Float) {
        _volume.value = vol.coerceIn(0f, 1f)
        updateVolumeInternal()
    }

    private fun updateVolumeInternal() {
        val effectiveVolume = if (_isSpeakerMuted.value) 0.0f else _volume.value
        try {
            audioTrack?.setVolume(effectiveVolume)
        } catch (_: Exception) {}
    }

    /**
     * Disconnect active audio session
     */
    fun disconnect() {
        stopAudioPipelines()
        closeSockets()
        _chatMessages.value = emptyList()
        _connectionState.value = AudioConnectionState.Idle
        // Re-arm mode state
        when (_currentMode.value) {
            AudioShareMode.TALKIE -> startTalkieMode()
            AudioShareMode.BROADCAST -> startBroadcastMode()
            AudioShareMode.LISTEN -> startListenMode()
        }
    }

    private fun stopAudioPipelines() {
        streamingJob?.cancel()
        streamingJob = null
        senderAudioJob?.cancel()
        senderAudioJob = null
        receiverAudioJob?.cancel()
        receiverAudioJob = null
        jitterBuffer.clear()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null

        try {
            echoCanceler?.release()
        } catch (_: Exception) {}
        echoCanceler = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    private fun closeSockets() {
        try {
            activeInputStream?.close()
        } catch (_: Exception) {}
        activeInputStream = null

        try {
            activeOutputStream?.close()
        } catch (_: Exception) {}
        activeOutputStream = null

        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null

        try {
            activeBtSocket?.close()
        } catch (_: Exception) {}
        activeBtSocket = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            bluetoothServerSocket?.close()
        } catch (_: Exception) {}
        bluetoothServerSocket = null

        serverAcceptJob?.cancel()
        serverAcceptJob = null

        pendingP2pConnectDevice = null
        wifiP2pConnection?.closeServerSocket()
    }

    /**
     * Clean up all network discovery, sockets, and audio resources
     */
    fun stopAll() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null

        stopAudioPipelines()
        closeSockets()

        // Unregister Wi-Fi P2P Receiver & reset Wi-Fi P2P group
        unregisterWifiP2pReceiver()
        try {
            wifiP2pConnection?.stopPeerDiscovery()
            wifiP2pConnection?.removeGroup()
        } catch (_: Exception) {}

        // Unregister NSD Service
        if (isNsdRegistered && registrationListener != null) {
            try {
                nsdManager?.unregisterService(registrationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering NSD: ${e.message}")
            }
            isNsdRegistered = false
            registrationListener = null
        }

        // Stop NSD Discovery
        if (isNsdDiscovering && discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping NSD discovery: ${e.message}")
            }
            isNsdDiscovering = false
            discoveryListener = null
        }

        // Unregister Bluetooth receiver
        if (bluetoothReceiver != null) {
            try {
                appContext?.unregisterReceiver(bluetoothReceiver)
            } catch (_: Exception) {}
            bluetoothReceiver = null
        }

        _discoveredDevices.value = emptyList()
        _isBluetoothFallbackAvailable.value = false
        _connectionState.value = AudioConnectionState.Idle
    }

    /**
     * Clean up resources on Activity onDestroy / onStop
     */
    fun cleanUp() {
        stopAll()
        appContext = null
    }
}
