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
    private const val PROTOCOL_MAGIC = "EVERDROP_P2P_V1"
    private const val SERVICE_TYPE = "_everdrop._tcp"
    private const val SERVICE_NAME = "EverDropShare"
    private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks for fast transfer

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false
    private var receiverJob: Job? = null
    private var senderJob: Job? = null
    private var activeServerSocket: ServerSocket? = null
    private var activeSocket: Socket? = null

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

    private val _receiverGroupInfo = MutableStateFlow<com.sameerasw.medrop.domain.model.WifiDirectGroupInfo?>(null)
    val receiverGroupInfo: StateFlow<com.sameerasw.medrop.domain.model.WifiDirectGroupInfo?> = _receiverGroupInfo.asStateFlow()

    private var discoveryLoopJob: Job? = null

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
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    }
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
                    device?.deviceName?.let { name ->
                        if (name.isNotBlank()) {
                            _thisDeviceName.value = name
                        }
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        if (wifiP2pManager != null) return
        val appContext = context.applicationContext
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
    fun startDiscoverableReceiver(context: Context) {
        init(context)
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        _isReceiverActive.value = true

        // Start listening ServerSocket for incoming connections
        startServerListening(context.applicationContext)

        // Remove any stale group first, then create autonomous group
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    createAutonomousGroup(mgr, ch)
                }
                override fun onFailure(reason: Int) {
                    createAutonomousGroup(mgr, ch)
                }
            })
        } catch (_: Exception) {
            createAutonomousGroup(mgr, ch)
        }
    }

    private fun createAutonomousGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val record = mapOf(
            "port" to PORT.toString(),
            "name" to _thisDeviceName.value,
            "type" to "EverDropReceiver"
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_NAME, SERVICE_TYPE, record)

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                refreshReceiverGroupInfo()
                try {
                    mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            mgr.addLocalService(ch, serviceInfo, null)
                        }
                        override fun onFailure(reason: Int) {
                            mgr.addLocalService(ch, serviceInfo, null)
                        }
                    })
                } catch (_: Exception) {}
            }

            override fun onFailure(reason: Int) {
                // If autonomous group creation fails (e.g. Wi-Fi Direct busy), fall back to peer listen mode
                try {
                    mgr.addLocalService(ch, serviceInfo, null)
                    mgr.discoverPeers(ch, null)
                } catch (_: Exception) {}
            }
        })
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
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

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
                    val pStatus = when (dev.status) {
                        WifiP2pDevice.AVAILABLE -> PeerStatus.AVAILABLE
                        WifiP2pDevice.INVITED -> PeerStatus.INVITED
                        WifiP2pDevice.CONNECTED -> PeerStatus.CONNECTED
                        WifiP2pDevice.FAILED -> PeerStatus.FAILED
                        else -> PeerStatus.UNAVAILABLE
                    }
                    val peer = WifiDirectPeer(
                        deviceAddress = dev.deviceAddress,
                        deviceName = dev.deviceName.ifBlank { "Nearby Phone" },
                        isEverDropPeer = dev.deviceName.contains("Ever", ignoreCase = true) || (index != -1 && currentList[index].isEverDropPeer),
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

        connectionInfoCallback = { info ->
            connectionInfoCallback = null
            senderJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    _transferProgress.value = _transferProgress.value.copy(
                        status = TransferProgressStatus.NEGOTIATING,
                        message = "Establishing high-speed channel…"
                    )

                    // Determine target IP: if we are client, target is groupOwnerAddress
                    val targetIp = if (!info.isGroupOwner && info.groupOwnerAddress != null) {
                        info.groupOwnerAddress.hostAddress
                    } else {
                        // If we are group owner, wait briefly for receiver or connect on default p2p gateway
                        delay(1000)
                        "192.168.49.1"
                    }

                    transmitPayloadOverSocket(
                        context = context.applicationContext,
                        targetHost = targetIp ?: "192.168.49.1",
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
                    disconnectCurrentGroup()
                }
            }
        }

        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _transferProgress.value = _transferProgress.value.copy(
                        status = TransferProgressStatus.CONNECTING,
                        message = "Invitation sent to ${peer.deviceName}…"
                    )
                }
                override fun onFailure(reason: Int) {
                    _transferProgress.value = TransferProgress(
                        status = TransferProgressStatus.FAILED,
                        message = "Could not connect to ${peer.deviceName}"
                    )
                }
            })
        } catch (e: Exception) {
            _transferProgress.value = TransferProgress(
                status = TransferProgressStatus.FAILED,
                message = e.localizedMessage
            )
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
                        val targetIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                        transmitPayloadOverSocket(
                            context = context.applicationContext,
                            targetHost = targetIp,
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
                    transmitPayloadOverSocket(
                        context = context.applicationContext,
                        targetHost = "192.168.49.1",
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

    private suspend fun transmitPayloadOverSocket(
        context: Context,
        targetHost: String,
        type: TransferType,
        textPayload: String?,
        fileUri: Uri?,
        fileName: String?,
        fileSize: Long,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var retries = 5
        while (socket == null && retries > 0 && isActive) {
            try {
                socket = Socket()
                socket.bind(null)
                socket.connect(InetSocketAddress(targetHost, PORT), 4000)
            } catch (e: Exception) {
                socket?.close()
                socket = null
                retries--
                delay(800)
            }
        }

        if (socket == null) {
            throw Exception("Could not reach receiver at $targetHost:$PORT")
        }

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

            // 2. Send Header
            dos.writeUTF(type.name)
            dos.writeUTF(effectiveName)
            dos.writeLong(effectiveSize)
            dos.writeUTF(mimeType)
            dos.flush()

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

            // 3. Wait for receiver ACK
            val ack = dis.readUTF()
            if (ack == "OK") {
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
     * RECEIVER FLOW: Listen for incoming connections and save payload.
     */
    private fun startServerListening(appContext: Context) {
        receiverJob?.cancel()
        receiverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                activeServerSocket?.close()
                val serverSocket = ServerSocket(PORT)
                activeServerSocket = serverSocket

                while (isActive && _isReceiverActive.value) {
                    try {
                        val clientSocket = serverSocket.accept()
                        handleIncomingTransfer(appContext, clientSocket)
                    } catch (_: Exception) {
                        // Socket closed or timeout
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
        val dis = DataInputStream(socket.getInputStream())
        val dos = DataOutputStream(socket.getOutputStream())

        try {
            val magic = dis.readUTF()
            if (magic != PROTOCOL_MAGIC) {
                dos.writeUTF("ERROR_BAD_MAGIC")
                dos.flush()
                return@withContext
            }

            val typeStr = dis.readUTF()
            val type = TransferType.valueOf(typeStr)
            val name = dis.readUTF()
            val totalSize = dis.readLong()
            val mimeType = dis.readUTF()

            _transferProgress.value = TransferProgress(
                status = TransferProgressStatus.RECEIVING,
                progress = 0f,
                bytesTransferred = 0L,
                totalBytes = totalSize,
                payloadType = type,
                payloadName = name,
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
                    receivedText = receivedString,
                    message = "Text received and copied to clipboard!"
                )
            } else {
                // FILE TRANSFER
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
                            message = "Receiving $name (${(progress * 100).toInt()}%)"
                        )
                    }
                    fos.flush()
                }

                // Save to public Downloads/Ever Share directory
                val savedUri = EverDropFileManager.saveFileToEverShare(
                    context = context,
                    fileName = name,
                    mimeType = mimeType,
                    bytes = tempFile.readBytes()
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
                    receivedFileUri = savedUri,
                    message = "Saved to Downloads/Ever Share"
                )
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
        }
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
        disconnectCurrentGroup()
        _transferProgress.value = TransferProgress(
            status = TransferProgressStatus.CANCELLED,
            message = "Transfer cancelled"
        )
    }

    fun disconnectCurrentGroup() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        try {
            mgr.removeGroup(ch, null)
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
