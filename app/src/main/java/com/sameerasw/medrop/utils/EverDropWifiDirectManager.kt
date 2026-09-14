package com.sameerasw.medrop.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import com.sameerasw.medrop.domain.model.PeerStatus
import com.sameerasw.medrop.domain.model.TransferHeader
import com.sameerasw.medrop.domain.model.TransferProgress
import com.sameerasw.medrop.domain.model.TransferProgressStatus
import com.sameerasw.medrop.domain.model.TransferType
import com.sameerasw.medrop.domain.model.WifiDirectPeer
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * EverDropWifiDirectManager
 *
 * Core engine for Android Quick Share-style high-speed Wi-Fi Direct file and text transfers:
 * - Peer & DNS-SD Service Discovery
 * - Group Negotiation & Automatic Connection
 * - High-speed TCP Socket Streaming with live progress tracking
 * - Safe file persistence to Downloads/Ever Share and text to Clipboard
 */
@SuppressLint("MissingPermission")
object EverDropWifiDirectManager {

    private const val PORT = 8988
    private const val PROTOCOL_MAGIC = "EVERDROP_P2P_V2"
    private const val PROTOCOL_MAGIC_V1 = "EVERDROP_P2P_V1"
    private const val SERVICE_TYPE = "_everdrop._tcp"
    private const val SERVICE_NAME = "EverDropShare"
    private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks for fast transfer
    const val NOTIFICATION_ID_TRANSFER = 9001

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false
    private var receiverJob: Job? = null
    private var receiverClientJob: Job? = null
    private var senderJob: Job? = null
    private var activeServerSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var applicationContext: Context? = null

    @Volatile
    var isAppInForeground: Boolean = false

    private var confirmationDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    val onSenderTransferCompleted = kotlinx.coroutines.flow.MutableSharedFlow<TransferType>(
        replay = 0,
        extraBufferCapacity = 1
    )

    private val _incomingRequest = MutableStateFlow<com.sameerasw.medrop.domain.model.IncomingTransferRequest?>(null)
    val incomingRequest: StateFlow<com.sameerasw.medrop.domain.model.IncomingTransferRequest?> = _incomingRequest.asStateFlow()

    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<WifiDirectPeer>> = _discoveredPeers.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _isReceiverActive = MutableStateFlow(false)
    val isReceiverActive: StateFlow<Boolean> = _isReceiverActive.asStateFlow()

    private val _transferProgress = MutableStateFlow(TransferProgress())
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    private val _thisDeviceName = MutableStateFlow(Build.MODEL ?: "Ever Drop Device")
    val thisDeviceName: StateFlow<String> = _thisDeviceName.asStateFlow()

    private val _thisDeviceAddress = MutableStateFlow("")
    val thisDeviceAddress: StateFlow<String> = _thisDeviceAddress.asStateFlow()

    private val _receiverGroupInfo = MutableStateFlow<com.sameerasw.medrop.domain.model.WifiDirectGroupInfo?>(null)
    val receiverGroupInfo: StateFlow<com.sameerasw.medrop.domain.model.WifiDirectGroupInfo?> = _receiverGroupInfo.asStateFlow()

    private var discoveryLoopJob: Job? = null
    private var receiverLoopJob: Job? = null

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || context == null) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _isWifiP2pEnabled.value = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestAvailablePeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    requestConnectionInfo()
                    if (_isReceiverActive.value) {
                        refreshReceiverGroupInfo()
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    device?.let { dev ->
                        if (dev.deviceName.isNotBlank()) {
                            _thisDeviceName.value = dev.deviceName
                        }
                        if (dev.deviceAddress.isNotBlank()) {
                            _thisDeviceAddress.value = dev.deviceAddress
                        }
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        if (wifiP2pManager != null) return
        wifiP2pManager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(appContext, Looper.getMainLooper(), null)

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        try {
            appContext.registerReceiver(p2pReceiver, filter)
            isReceiverRegistered = true
        } catch (_: Exception) {}
    }

    /**
     * Start discovery for nearby devices when opening the Quick Share sheet.
     * Continuously refreshes peer discovery so nearby devices are detected without delay.
     */
    fun startPeerDiscovery(context: Context) {
        init(context)
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        _isDiscovering.value = true
        _discoveredPeers.value = emptyList()

        // Clear any old service requests and register DNS-SD service listeners
        try {
            mgr.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    setupDnsSdServiceDiscovery(mgr, ch)
                }
                override fun onFailure(reason: Int) {
                    setupDnsSdServiceDiscovery(mgr, ch)
                }
            })
        } catch (_: Exception) {
            setupDnsSdServiceDiscovery(mgr, ch)
        }

        // Periodic discovery loop: keeps the Wi-Fi Direct radio actively probing
        discoveryLoopJob?.cancel()
        discoveryLoopJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && _isDiscovering.value) {
                try {
                    mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            _isDiscovering.value = true
                        }
                        override fun onFailure(reason: Int) {}
                    })
                } catch (_: Exception) {}
                delay(10000L) // Re-trigger discovery every 10 seconds to prevent radio idle timeout
            }
        }
    }

    private fun setupDnsSdServiceDiscovery(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        try {
            mgr.setDnsSdResponseListeners(ch,
                { instanceName, registrationType, srcDevice ->
                    if (registrationType.contains("everdrop", ignoreCase = true) || instanceName.contains("EverDrop", ignoreCase = true)) {
                        addOrUpdatePeer(
                            WifiDirectPeer(
                                deviceAddress = srcDevice.deviceAddress,
                                deviceName = srcDevice.deviceName.ifBlank { instanceName },
                                isEverDropPeer = true,
                                status = PeerStatus.AVAILABLE
                            )
                        )
                    }
                },
                { fullDomainName, txtRecordMap, srcDevice ->
                    val customName = txtRecordMap["name"] ?: srcDevice.deviceName
                    addOrUpdatePeer(
                        WifiDirectPeer(
                            deviceAddress = srcDevice.deviceAddress,
                            deviceName = customName.ifBlank { "Ever Drop Peer" },
                            isEverDropPeer = true,
                            status = PeerStatus.AVAILABLE
                        )
                    )
                }
            )

            val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
            mgr.addServiceRequest(ch, serviceRequest, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    mgr.discoverServices(ch, null)
                }
                override fun onFailure(reason: Int) {}
            })
        } catch (_: Exception) {}
    }

    fun stopPeerDiscovery() {
        discoveryLoopJob?.cancel()
        discoveryLoopJob = null
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        try {
            mgr.stopPeerDiscovery(ch, null)
            mgr.clearServiceRequests(ch, null)
        } catch (_: Exception) {}
        _isDiscovering.value = false
    }

    /**
     * Start discoverable receiver mode when entering the Receive tab.
     * Creates an autonomous Wi-Fi Direct Group (Group Owner AP).
     * This broadcasts Wi-Fi Direct 802.11 beacons so senders discover this device IMMEDIATELY
     * without requiring the user to open Android's system Wi-Fi Direct settings!
     */
    fun isTransferBusy(): Boolean {
        val status = _transferProgress.value.status
        return confirmationDeferred != null ||
                _incomingRequest.value != null ||
                status == TransferProgressStatus.CONNECTING ||
                status == TransferProgressStatus.NEGOTIATING ||
                status == TransferProgressStatus.WAITING_CONFIRMATION ||
                status == TransferProgressStatus.SENDING ||
                status == TransferProgressStatus.RECEIVING
    }

    /**
     * Start discoverable receiver mode when entering the Receive tab or running in background.
     * Creates an autonomous Wi-Fi Direct Group (Group Owner AP).
     * This broadcasts Wi-Fi Direct 802.11 beacons so senders discover this device IMMEDIATELY
     * without requiring the user to open Android's system Wi-Fi Direct settings!
     */
    /**
     * Start discoverable receiver mode when entering the Receive tab or running in background.
     * Uses Wi-Fi Direct Peer Discovery (P2P Listen state) and DNS-SD service advertising.
     * This makes this device discoverable to all senders WITHOUT requiring the user to open
     * Android's system Wi-Fi Direct settings!
     */
    fun startDiscoverableReceiver(context: Context) {
        init(context)
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        _isReceiverActive.value = true

        // 1. Start listening on ServerSocket (PORT 8988)
        startServerListening(context.applicationContext)

        // 2. Clear any lingering stale groups if not actively transferring
        if (!isTransferBusy()) {
            try {
                mgr.requestGroupInfo(ch) { currentGroup ->
                    if (currentGroup != null && !isTransferBusy()) {
                        mgr.removeGroup(ch, null)
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Register local DNS-SD service
        registerReceiverDnsSdService(mgr, ch)

        // 4. Start peer discovery to enter P2P Listen state immediately!
        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (_: Exception) {}

        // 5. Continuous keep-alive discovery loop (every 12 seconds).
        // Android Wi-Fi Direct peer discovery automatically times out after 120s.
        // This loop keeps the P2P Listen state active 24/7 in foreground and background.
        receiverLoopJob?.cancel()
        receiverLoopJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && _isReceiverActive.value) {
                delay(12000L)
                if (!isTransferBusy()) {
                    try {
                        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {}
                            override fun onFailure(reason: Int) {}
                        })
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Start direct Autonomous Group Owner specifically for Direct Connect QR Code.
     */
    fun startQrDirectGroup() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                refreshReceiverGroupInfo()
            }
            override fun onFailure(reason: Int) {}
        })
    }

    fun stopQrDirectGroup(context: Context) {
        disconnectCurrentGroup()
        _receiverGroupInfo.value = null
        startDiscoverableReceiver(context)
    }

    private fun registerReceiverDnsSdService(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val record = mapOf(
            "port" to PORT.toString(),
            "name" to _thisDeviceName.value,
            "type" to "EverDropReceiver"
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_NAME, SERVICE_TYPE, record)
        try {
            mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    mgr.addLocalService(ch, serviceInfo, null)
                }
                override fun onFailure(reason: Int) {
                    mgr.addLocalService(ch, serviceInfo, null)
                }
            })
        } catch (_: Exception) {
            try { mgr.addLocalService(ch, serviceInfo, null) } catch (_: Exception) {}
        }
    }

    private fun refreshReceiverGroupInfo() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        try {
            mgr.requestGroupInfo(ch) { group ->
                if (group != null) {
                    _receiverGroupInfo.value = com.sameerasw.medrop.domain.model.WifiDirectGroupInfo(
                        networkName = group.networkName ?: "",
                        passphrase = group.passphrase ?: "",
                        isGroupOwner = group.isGroupOwner
                    )
                }
            }
        } catch (_: Exception) {}
    }

    fun stopDiscoverableReceiver() {
        if (isTransferBusy()) {
            // Active transfer or confirmation in progress: do not terminate group or server socket prematurely
            return
        }
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        receiverLoopJob?.cancel()
        receiverLoopJob = null
        _isReceiverActive.value = false
        _receiverGroupInfo.value = null
        try {
            mgr.clearLocalServices(ch, null)
            mgr.stopPeerDiscovery(ch, null)
        } catch (_: Exception) {}

        stopServerListening()
        disconnectCurrentGroup()
    }

    private fun requestAvailablePeers() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        try {
            mgr.requestPeers(ch) { peers ->
                val currentList = _discoveredPeers.value.toMutableList()
                peers.deviceList.forEach { dev ->
                    val index = currentList.indexOfFirst { it.deviceAddress == dev.deviceAddress }
                    val existingPeer = if (index >= 0) currentList[index] else null
                    val pStatus = when (dev.status) {
                        WifiP2pDevice.AVAILABLE -> PeerStatus.AVAILABLE
                        WifiP2pDevice.INVITED -> PeerStatus.INVITED
                        WifiP2pDevice.CONNECTED -> PeerStatus.CONNECTED
                        WifiP2pDevice.FAILED -> PeerStatus.FAILED
                        else -> PeerStatus.UNAVAILABLE
                    }
                    val effectiveName = when {
                        !dev.deviceName.isNullOrBlank() && !dev.deviceName.startsWith("Android_") -> dev.deviceName
                        existingPeer != null && existingPeer.deviceName.isNotBlank() && existingPeer.deviceName != "Nearby Phone" -> existingPeer.deviceName
                        !dev.deviceName.isNullOrBlank() -> dev.deviceName
                        else -> existingPeer?.deviceName ?: "Nearby Phone"
                    }
                    val isEverDrop = dev.deviceName.contains("Ever", ignoreCase = true) ||
                            (existingPeer?.isEverDropPeer == true)
                    val peer = WifiDirectPeer(
                        deviceAddress = dev.deviceAddress,
                        deviceName = effectiveName,
                        isEverDropPeer = isEverDrop,
                        status = pStatus
                    )
                    if (index >= 0) {
                        currentList[index] = peer
                    } else {
                        currentList.add(peer)
                    }
                }
                _discoveredPeers.value = currentList
            }
        } catch (_: Exception) {}
    }

    private fun addOrUpdatePeer(peer: WifiDirectPeer) {
        val current = _discoveredPeers.value.toMutableList()
        val index = current.indexOfFirst { it.deviceAddress == peer.deviceAddress }
        if (index >= 0) {
            current[index] = peer
        } else {
            current.add(0, peer) // Prioritize DNS-SD EverDrop peers at the top
        }
        _discoveredPeers.value = current
    }

    private fun requestConnectionInfo() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        try {
            mgr.requestConnectionInfo(ch) { info ->
                if (info.groupFormed) {
                    onGroupFormed(info)
                }
            }
        } catch (_: Exception) {}
    }

    private var connectionInfoCallback: ((WifiP2pInfo) -> Unit)? = null

    private fun onGroupFormed(info: WifiP2pInfo) {
        connectionInfoCallback?.invoke(info)

        // If this device is in discoverable receiver mode and became the client (sender is GO),
        // proactively connect to the sender GO to establish the socket channel
        if (_isReceiverActive.value && !info.isGroupOwner && info.groupOwnerAddress != null) {
            applicationContext?.let { ctx ->
                val host = info.groupOwnerAddress.hostAddress ?: "192.168.49.1"
                connectToGroupOwnerAsReceiver(ctx, host)
            }
        }
    }

    /**
     * SENDER FLOW: Connect to chosen peer and transmit payload (FILE or TEXT).
     */
    fun sendContent(
        context: Context,
        peer: WifiDirectPeer,
        type: TransferType,
        textPayload: String? = null,
        fileUri: Uri? = null,
        fileName: String? = null,
        fileSize: Long = 0L,
        mimeType: String = "*/*"
    ) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        senderJob?.cancel()
        _transferProgress.value = TransferProgress(
            status = TransferProgressStatus.CONNECTING,
            payloadType = type,
            payloadName = fileName ?: if (type == TransferType.TEXT) "Text Note" else "File",
            totalBytes = if (type == TransferType.TEXT) (textPayload?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L) else fileSize,
            message = "Connecting to ${peer.deviceName}…"
        )

        val config = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer the receiver as group owner
        }

        var isConnected = false
        connectionInfoCallback = { info ->
            isConnected = true
            connectionInfoCallback = null
            senderJob?.cancel()
            senderJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    _transferProgress.value = _transferProgress.value.copy(
                        status = TransferProgressStatus.NEGOTIATING,
                        message = "Establishing high-speed channel…"
                    )

                    val socket: Socket = if (!info.isGroupOwner && info.groupOwnerAddress != null) {
                        val targetIp = info.groupOwnerAddress.hostAddress ?: "192.168.49.1"
                        connectWithRetries(targetIp, PORT)
                    } else {
                        waitForReceiverConnectionOrScan(PORT)
                    }

                    transmitPayloadOverConnectedSocket(
                        context = context.applicationContext,
                        socket = socket,
                        type = type,
                        textPayload = textPayload,
                        fileUri = fileUri,
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType
                    )
                } catch (e: Exception) {
                    _transferProgress.value = TransferProgress(
                        status = TransferProgressStatus.FAILED,
                        message = "Transfer failed: ${e.localizedMessage ?: "Connection error"}"
                    )
                } finally {
                    delay(500L)
                    disconnectCurrentGroup()
                }
            }
        }

        fun executeConnect(isRetry: Boolean = false) {
            try {
                mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        _transferProgress.value = _transferProgress.value.copy(
                            status = TransferProgressStatus.CONNECTING,
                            message = "Invitation sent to ${peer.deviceName}…"
                        )
                        // Active poll connection info in case broadcast is delayed
                        CoroutineScope(Dispatchers.IO).launch {
                            for (i in 1..25) {
                                if (isConnected || connectionInfoCallback == null) break
                                delay(1200)
                                requestConnectionInfo()
                            }
                        }
                    }
                    override fun onFailure(reason: Int) {
                        if (reason == WifiP2pManager.BUSY && !isRetry) {
                            // Clear busy channel state and retry once
                            try { mgr.cancelConnect(ch, null) } catch (_: Exception) {}
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1000)
                                executeConnect(isRetry = true)
                            }
                        } else {
                            _transferProgress.value = TransferProgress(
                                status = TransferProgressStatus.FAILED,
                                message = "Could not connect to ${peer.deviceName} (reason $reason)"
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                _transferProgress.value = TransferProgress(
                    status = TransferProgressStatus.FAILED,
                    message = e.localizedMessage
                )
            }
        }

        // Cancel any pending stale connections first to prevent busy/stuck state
        try {
            mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { executeConnect() }
                override fun onFailure(reason: Int) { executeConnect() }
            })
        } catch (_: Exception) {
            executeConnect()
        }
    }

    /**
     * SENDER FLOW (Direct Wi-Fi / QR Code): Connect to receiver using Wi-Fi Direct network name and passphrase.
     * Works across all devices without needing peer discovery!
     */
    fun sendContentDirectByNetwork(
        context: Context,
        networkName: String,
        passphrase: String,
        type: TransferType,
        textPayload: String? = null,
        fileUri: Uri? = null,
        fileName: String? = null,
        fileSize: Long = 0L,
        mimeType: String = "*/*"
    ) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        senderJob?.cancel()
        _transferProgress.value = TransferProgress(
            status = TransferProgressStatus.CONNECTING,
            payloadType = type,
            payloadName = fileName ?: if (type == TransferType.TEXT) "Text Note" else "File",
            totalBytes = if (type == TransferType.TEXT) (textPayload?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L) else fileSize,
            message = "Connecting to $networkName…"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .build()

            connectionInfoCallback = { info ->
                connectionInfoCallback = null
                senderJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        _transferProgress.value = _transferProgress.value.copy(
                            status = TransferProgressStatus.NEGOTIATING,
                            message = "Establishing high-speed channel…"
                        )
                        val socket: Socket = if (!info.isGroupOwner && info.groupOwnerAddress != null) {
                            val targetIp = info.groupOwnerAddress.hostAddress ?: "192.168.49.1"
                            connectWithRetries(targetIp, PORT)
                        } else {
                            waitForReceiverConnectionOrScan(PORT)
                        }
                        transmitPayloadOverConnectedSocket(
                            context = context.applicationContext,
                            socket = socket,
                            type = type,
                            textPayload = textPayload,
                            fileUri = fileUri,
                            fileName = fileName,
                            fileSize = fileSize,
                            mimeType = mimeType
                        )
                    } catch (e: Exception) {
                        _transferProgress.value = TransferProgress(
                            status = TransferProgressStatus.FAILED,
                            message = "Transfer failed: ${e.localizedMessage ?: "Connection error"}"
                        )
                    } finally {
                        delay(500L)
                        disconnectCurrentGroup()
                    }
                }
            }

            try {
                mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {
                        _transferProgress.value = TransferProgress(
                            status = TransferProgressStatus.FAILED,
                            message = "Could not connect to network ($reason)"
                        )
                    }
                })
            } catch (e: Exception) {
                _transferProgress.value = TransferProgress(
                    status = TransferProgressStatus.FAILED,
                    message = e.localizedMessage
                )
            }
        } else {
            // Direct socket transfer to default GO IP if already joined
            senderJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val socket = connectWithRetries("192.168.49.1", PORT)
                    transmitPayloadOverConnectedSocket(
                        context = context.applicationContext,
                        socket = socket,
                        type = type,
                        textPayload = textPayload,
                        fileUri = fileUri,
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType
                    )
                } catch (e: Exception) {
                    _transferProgress.value = TransferProgress(
                        status = TransferProgressStatus.FAILED,
                        message = e.localizedMessage
                    )
                }
            }
        }
    }

    private fun connectToGroupOwnerAsReceiver(context: Context, goHost: String) {
        if (isTransferBusy()) return
        receiverClientJob?.cancel()
        receiverClientJob = CoroutineScope(Dispatchers.IO).launch {
            var clientSocket: Socket? = null
            var retries = 15
            while (clientSocket == null && retries > 0 && isActive && _isReceiverActive.value) {
                try {
                    val s = Socket()
                    s.reuseAddress = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress(goHost, PORT), 3000)
                    clientSocket = s
                } catch (_: Exception) {
                    clientSocket?.close()
                    clientSocket = null
                    retries--
                    if (retries > 0) delay(1000L)
                }
            }
            val s = clientSocket ?: return@launch
            handleIncomingTransfer(context, s)
        }
    }

    private suspend fun connectWithRetries(
        targetHost: String,
        port: Int,
        maxRetries: Int = 12
    ): Socket = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var retries = maxRetries
        while (socket == null && retries > 0 && isActive) {
            try {
                val s = Socket()
                s.reuseAddress = true
                s.keepAlive = true
                s.connect(InetSocketAddress(targetHost, port), 3500)
                socket = s
            } catch (e: Exception) {
                socket?.close()
                socket = null
                retries--
                if (retries > 0) delay(1000)
            }
        }
        socket ?: throw Exception("Could not reach receiver at $targetHost:$port")
    }

    private suspend fun waitForReceiverConnectionOrScan(port: Int): Socket = withContext(Dispatchers.IO) {
        var ss: ServerSocket? = null
        try {
            try { activeServerSocket?.close() } catch (_: Exception) {}
            ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = 25000
            }
            val server = ss
            activeServerSocket = server

            val socketDeferred = kotlinx.coroutines.CompletableDeferred<Socket>()

            // 1. Accept incoming reverse connection from receiver client (Mobile Talkie pattern)
            val acceptJob = launch {
                try {
                    val s = server.accept().apply {
                        keepAlive = true
                        tcpNoDelay = true
                    }
                    if (!socketDeferred.isCompleted) {
                        socketDeferred.complete(s)
                    } else {
                        try { s.close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            // 2. Concurrently attempt connecting to typical Wi-Fi Direct client DHCP addresses (192.168.49.2 .. 192.168.49.10)
            val scanJob = launch {
                delay(1200L) // Give receiver a moment to initiate reverse connect
                val baseSubnet = "192.168.49."
                for (lastOctet in 2..10) {
                    if (socketDeferred.isCompleted || !isActive) break
                    val target = "$baseSubnet$lastOctet"
                    launch {
                        try {
                            val client = Socket().apply {
                                reuseAddress = true
                                keepAlive = true
                                tcpNoDelay = true
                            }
                            client.connect(InetSocketAddress(target, port), 1200)
                            if (!socketDeferred.isCompleted) {
                                socketDeferred.complete(client)
                            } else {
                                try { client.close() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            val connectedSocket = withTimeoutOrNull(25000L) {
                socketDeferred.await()
            } ?: throw Exception("Could not reach or establish connection with receiver")

            acceptJob.cancel()
            scanJob.cancel()
            try { server.close() } catch (_: Exception) {}
            if (activeServerSocket == server) activeServerSocket = null

            return@withContext connectedSocket
        } catch (e: Exception) {
            try { ss?.close() } catch (_: Exception) {}
            if (activeServerSocket == ss) activeServerSocket = null
            throw e
        }
    }

    private suspend fun transmitPayloadOverConnectedSocket(
        context: Context,
        socket: Socket,
        type: TransferType,
        textPayload: String?,
        fileUri: Uri?,
        fileName: String?,
        fileSize: Long,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        socket.keepAlive = true
        socket.soTimeout = 120_000 // 120s timeout so sender patiently waits for receiver confirmation in background

        activeSocket = socket
        val dos = DataOutputStream(socket.getOutputStream())
        val dis = DataInputStream(socket.getInputStream())

        try {
            // 1. Send Handshake Magic
            dos.writeUTF(PROTOCOL_MAGIC)

            val effectiveName = fileName ?: (if (type == TransferType.TEXT) "Text" else "File")
            val rawBytes = if (type == TransferType.TEXT) {
                textPayload?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            } else null
            val effectiveSize = if (type == TransferType.TEXT) (rawBytes?.size?.toLong() ?: 0L) else fileSize

            // 2. Send Header with Sender Device Name
            dos.writeUTF(type.name)
            dos.writeUTF(effectiveName)
            dos.writeLong(effectiveSize)
            dos.writeUTF(mimeType)
            dos.writeUTF(_thisDeviceName.value)
            dos.flush()

            _transferProgress.value = TransferProgress(
                status = TransferProgressStatus.WAITING_CONFIRMATION,
                progress = 0f,
                bytesTransferred = 0L,
                totalBytes = effectiveSize,
                payloadType = type,
                payloadName = effectiveName,
                message = "Waiting for receiver to accept…"
            )

            // 3. Wait for receiver confirmation decision (ACCEPTED or REJECTED)
            val decision = dis.readUTF()
            if (decision != "ACCEPTED") {
                _transferProgress.value = TransferProgress(
                    status = TransferProgressStatus.CANCELLED,
                    payloadType = type,
                    payloadName = effectiveName,
                    message = "Transfer declined by receiver"
                )
                return@withContext
            }

            // 4. Stream data
            _transferProgress.value = TransferProgress(
                status = TransferProgressStatus.SENDING,
                progress = 0f,
                bytesTransferred = 0L,
                totalBytes = effectiveSize,
                payloadType = type,
                payloadName = effectiveName,
                message = "Sending $effectiveName…"
            )

            var transferred = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            if (type == TransferType.TEXT) {
                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    dos.write(rawBytes)
                    dos.flush()
                }
                transferred = effectiveSize
            } else if (fileUri != null) {
                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1 && isActive) {
                        dos.write(buffer, 0, bytesRead)
                        transferred += bytesRead
                        val progress = if (effectiveSize > 0) (transferred.toFloat() / effectiveSize.toFloat()).coerceIn(0f, 1f) else 1f
                        _transferProgress.value = TransferProgress(
                            status = TransferProgressStatus.SENDING,
                            progress = progress,
                            bytesTransferred = transferred,
                            totalBytes = effectiveSize,
                            payloadType = type,
                            payloadName = effectiveName,
                            message = "Sending $effectiveName (${(progress * 100).toInt()}%)"
                        )
                    }
                    dos.flush()
                }
            }

            // 5. Wait for receiver ACK
            val ack = dis.readUTF()
            if (ack == "OK") {
                onSenderTransferCompleted.tryEmit(type)
                _transferProgress.value = TransferProgress(
                    status = TransferProgressStatus.COMPLETED,
                    progress = 1f,
                    bytesTransferred = effectiveSize,
                    totalBytes = effectiveSize,
                    payloadType = type,
                    payloadName = effectiveName,
                    message = "Transfer complete!"
                )
            } else {
                throw Exception("Receiver rejected transfer: $ack")
            }
        } finally {
            try { dos.close() } catch (_: Exception) {}
            try { dis.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            activeSocket = null
        }
    }

    /**
     * RECEIVER FLOW: Listen for incoming connections and save payload after explicit user approval.
     */
    private fun startServerListening(appContext: Context) {
        receiverJob?.cancel()
        receiverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                try { activeServerSocket?.close() } catch (_: Exception) {}
                var serverSocket: ServerSocket? = null
                for (attempt in 1..3) {
                    try {
                        serverSocket = ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress(PORT))
                        }
                        break
                    } catch (e: Exception) {
                        if (attempt < 3) delay(500L) else throw e
                    }
                }
                val socket = serverSocket ?: return@launch
                activeServerSocket = socket

                while (isActive && _isReceiverActive.value && !socket.isClosed) {
                    try {
                        val clientSocket = socket.accept()
                        handleIncomingTransfer(appContext, clientSocket)
                    } catch (_: Exception) {
                        if (socket.isClosed) break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { activeServerSocket?.close() } catch (_: Exception) {}
                activeServerSocket = null
            }
        }
    }

    private suspend fun handleIncomingTransfer(context: Context, socket: Socket) = withContext(Dispatchers.IO) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "EverDrop:TransferWakeLock")?.apply {
            setReferenceCounted(false)
            try { acquire(180_000L) } catch (_: Exception) {}
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiLock = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "EverDrop:TransferWifiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EverDrop:TransferWifiLock")
            }?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { null }

        socket.keepAlive = true
        socket.soTimeout = 120_000 // 2 minutes timeout for user background interaction and streaming

        val dis = DataInputStream(socket.getInputStream())
        val dos = DataOutputStream(socket.getOutputStream())

        try {
            val magic = dis.readUTF()
            if (magic != PROTOCOL_MAGIC && magic != PROTOCOL_MAGIC_V1) {
                dos.writeUTF("ERROR_BAD_MAGIC")
                dos.flush()
                return@withContext
            }

            val typeStr = dis.readUTF()
            val type = TransferType.valueOf(typeStr)
            val name = dis.readUTF()
            val totalSize = dis.readLong()
            val mimeType = dis.readUTF()
            val senderName = if (magic == PROTOCOL_MAGIC) {
                try { dis.readUTF() } catch (_: Exception) { "Nearby Phone" }
            } else {
                "Nearby Phone"
            }

            val request = com.sameerasw.medrop.domain.model.IncomingTransferRequest(
                type = type,
                name = name,
                size = totalSize,
                mimeType = mimeType,
                senderName = senderName
            )

            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            confirmationDeferred = deferred
            _incomingRequest.value = request

            _transferProgress.value = TransferProgress(
                status = TransferProgressStatus.WAITING_CONFIRMATION,
                progress = 0f,
                bytesTransferred = 0L,
                totalBytes = totalSize,
                payloadType = type,
                payloadName = name,
                senderName = senderName,
                message = "$senderName wants to send $name"
            )

            // ALWAYS show incoming transfer notification prompt (both in foreground and background)
            // so the user receives a prompt in notification to accept or dismiss
            showIncomingTransferNotification(context, request)

            // If the app is currently in the background, also attempt launching full-screen activity
            if (!isAppInForeground) {
                launchBackgroundConfirmation(context, request)
            }

            // Wait for user's explicit decision
            val accepted = try {
                deferred.await()
            } catch (_: Exception) {
                false
            }

            confirmationDeferred = null
            _incomingRequest.value = null
            dismissIncomingTransferNotification(context)

            if (!accepted) {
                dos.writeUTF("REJECTED")
                dos.flush()
                _transferProgress.value = TransferProgress(
                    status = TransferProgressStatus.CANCELLED,
                    payloadType = type,
                    payloadName = name,
                    senderName = senderName,
                    message = "Transfer declined by receiver"
                )
                return@withContext
            }

            // User accepted transfer!
            dos.writeUTF("ACCEPTED")
            dos.flush()

            _transferProgress.value = TransferProgress(
                status = TransferProgressStatus.RECEIVING,
                progress = 0f,
                bytesTransferred = 0L,
                totalBytes = totalSize,
                payloadType = type,
                payloadName = name,
                senderName = senderName,
                message = "Receiving $name…"
            )

            var bytesReceived = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            if (type == TransferType.TEXT) {
                val baos = ByteArrayOutputStream()
                while (bytesReceived < totalSize && isActive) {
                    val toRead = Math.min(buffer.size.toLong(), totalSize - bytesReceived).toInt()
                    val read = dis.read(buffer, 0, toRead)
                    if (read == -1) break
                    baos.write(buffer, 0, read)
                    bytesReceived += read
                }
                val receivedString = baos.toString(Charsets.UTF_8.name())

                // Copy received text to clipboard
                withContext(Dispatchers.Main) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Ever Drop Text", receivedString))
                }

                dos.writeUTF("OK")
                dos.flush()

                _transferProgress.value = TransferProgress(
                    status = TransferProgressStatus.COMPLETED,
                    progress = 1f,
                    bytesTransferred = totalSize,
                    totalBytes = totalSize,
                    payloadType = type,
                    payloadName = name,
                    senderName = senderName,
                    receivedText = receivedString,
                    message = "Text received and copied to clipboard!"
                )
                EverDropNfcShareManager.setForceShareContact(context, null)
            } else {
                // FILE TRANSFER: Stream directly to temp file on disk
                val tempFile = File(context.cacheDir, "everdrop_${System.currentTimeMillis()}_$name")
                FileOutputStream(tempFile).use { fos ->
                    while (bytesReceived < totalSize && isActive) {
                        val toRead = Math.min(buffer.size.toLong(), totalSize - bytesReceived).toInt()
                        val read = dis.read(buffer, 0, toRead)
                        if (read == -1) break
                        fos.write(buffer, 0, read)
                        bytesReceived += read
                        val progress = if (totalSize > 0) (bytesReceived.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f) else 1f
                        _transferProgress.value = TransferProgress(
                            status = TransferProgressStatus.RECEIVING,
                            progress = progress,
                            bytesTransferred = bytesReceived,
                            totalBytes = totalSize,
                            payloadType = type,
                            payloadName = name,
                            senderName = senderName,
                            message = "Receiving $name (${(progress * 100).toInt()}%)"
                        )
                    }
                    fos.flush()
                }

                // Save to public Downloads/Ever Share directory without loading all into memory
                val savedUri = EverDropFileManager.saveFileToEverShare(
                    context = context,
                    fileName = name,
                    mimeType = mimeType,
                    sourceFile = tempFile
                )
                tempFile.delete()

                dos.writeUTF("OK")
                dos.flush()

                _transferProgress.value = TransferProgress(
                    status = TransferProgressStatus.COMPLETED,
                    progress = 1f,
                    bytesTransferred = totalSize,
                    totalBytes = totalSize,
                    payloadType = type,
                    payloadName = name,
                    senderName = senderName,
                    receivedFileUri = savedUri,
                    message = "Saved to Downloads/Ever Share"
                )
                EverDropNfcShareManager.setForceShareContact(context, null)
            }
        } catch (e: Exception) {
            try { dos.writeUTF("ERROR: ${e.message}") } catch (_: Exception) {}
            _transferProgress.value = TransferProgress(
                status = TransferProgressStatus.FAILED,
                message = "Receive error: ${e.localizedMessage}"
            )
        } finally {
            try { dis.close() } catch (_: Exception) {}
            try { dos.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            try { wakeLock?.release() } catch (_: Exception) {}
            try { wifiLock?.release() } catch (_: Exception) {}
        }
    }

    fun acceptIncomingTransfer(context: Context? = null) {
        if (context != null) dismissIncomingTransferNotification(context)
        confirmationDeferred?.complete(true)
    }

    fun rejectIncomingTransfer(context: Context? = null) {
        if (context != null) dismissIncomingTransferNotification(context)
        confirmationDeferred?.complete(false)
        cancelActiveTransfer()
    }

    private fun launchBackgroundConfirmation(context: Context, request: com.sameerasw.medrop.domain.model.IncomingTransferRequest) {
        try {
            val intent = Intent(context, com.sameerasw.medrop.ui.activities.IncomingTransferActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("request_id", request.id)
                putExtra("request_name", request.name)
                putExtra("request_size", request.size)
                putExtra("request_type", request.type.name)
                putExtra("request_mime", request.mimeType)
                putExtra("request_sender", request.senderName)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private const val CHANNEL_ID_INCOMING = "everdrop_incoming_transfers"

    fun showIncomingTransferNotification(context: Context, request: com.sameerasw.medrop.domain.model.IncomingTransferRequest) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID_INCOMING,
                "Incoming Transfers",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for incoming Wi-Fi Direct file and text transfers"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val fullScreenIntent = Intent(context, com.sameerasw.medrop.ui.activities.IncomingTransferActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("request_id", request.id)
            putExtra("request_name", request.name)
            putExtra("request_size", request.size)
            putExtra("request_type", request.type.name)
            putExtra("request_mime", request.mimeType)
            putExtra("request_sender", request.senderName)
        }
        val fullScreenPendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Action Accept via EverDropActionReceiver
        val acceptIntent = Intent(context, com.sameerasw.medrop.receivers.EverDropActionReceiver::class.java).apply {
            action = com.sameerasw.medrop.receivers.EverDropActionReceiver.ACTION_ACCEPT
        }
        val acceptPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            1,
            acceptIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Action Reject via EverDropActionReceiver
        val rejectIntent = Intent(context, com.sameerasw.medrop.receivers.EverDropActionReceiver::class.java).apply {
            action = com.sameerasw.medrop.receivers.EverDropActionReceiver.ACTION_REJECT
        }
        val rejectPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            2,
            rejectIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val sizeFormatted = EverDropFileManager.formatFileSize(request.size)
        val subtitle = if (request.type == com.sameerasw.medrop.domain.model.TransferType.TEXT) "Text snippet ($sizeFormatted)" else "${request.name} ($sizeFormatted)"

        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID_INCOMING)
            .setSmallIcon(com.sameerasw.medrop.R.drawable.rounded_share_24)
            .setContentTitle("Incoming transfer from ${request.senderName}")
            .setContentText(subtitle)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(com.sameerasw.medrop.R.drawable.rounded_check_24, "Receive", acceptPendingIntent)
            .addAction(com.sameerasw.medrop.R.drawable.rounded_remove_24, "Cancel", rejectPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_TRANSFER, notification)
    }

    fun dismissIncomingTransferNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID_TRANSFER)
    }

    private fun stopServerListening() {
        receiverJob?.cancel()
        receiverJob = null
        try {
            activeServerSocket?.close()
        } catch (_: Exception) {}
        activeServerSocket = null
    }

    fun resetTransferState() {
        _transferProgress.value = TransferProgress()
    }

    fun cancelActiveTransfer() {
        senderJob?.cancel()
        senderJob = null
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null
        if (!_isReceiverActive.value) {
            disconnectCurrentGroup()
        }
        _transferProgress.value = TransferProgress(
            status = TransferProgressStatus.CANCELLED,
            message = "Transfer cancelled"
        )
    }

    fun disconnectCurrentGroup() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (_isReceiverActive.value) {
                        try { mgr.discoverPeers(ch, null) } catch (_: Exception) {}
                    }
                }
                override fun onFailure(reason: Int) {}
            })
        } catch (_: Exception) {}
    }

    fun destroy(context: Context) {
        stopPeerDiscovery()
        stopDiscoverableReceiver()
        if (isReceiverRegistered) {
            try {
                context.applicationContext.unregisterReceiver(p2pReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }
}
