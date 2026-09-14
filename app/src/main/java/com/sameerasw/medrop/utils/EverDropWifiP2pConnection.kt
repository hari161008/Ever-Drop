package com.sameerasw.medrop.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * EverDropWifiP2pConnection
 *
 * Wi-Fi Direct Peer-to-Peer connection manager adapted from Mobile Talkie (bluetoothtalkie2):
 * - Initializes Wi-Fi P2P manager & channel
 * - Peer discovery & device enumeration
 * - Group formation & direct device connection
 * - Symmetric Group Owner / Client socket establishment
 */
@SuppressLint("MissingPermission")
class EverDropWifiP2pConnection(private val context: Context) {

    companion object {
        private const val TAG = "EverDropWifiP2p"
        const val DEFAULT_AUDIO_PORT = 8888
        const val DEFAULT_FILE_PORT = 8988
    }

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var activeServerSocket: ServerSocket? = null
    private var wifiManager: WifiManager? = null

    init {
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    fun isWifiEnabled(): Boolean {
        return wifiManager?.isWifiEnabled == true
    }

    fun isP2pSupported(): Boolean {
        return wifiManager?.isP2pSupported == true
    }

    fun initialize(onChannelDisconnected: (() -> Unit)? = null): Boolean {
        if (wifiP2pManager != null && channel != null) return true

        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return false
        wifiP2pManager = manager
        channel = manager.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "Wi-Fi P2P channel disconnected")
            onChannelDisconnected?.invoke()
        }
        return channel != null
    }

    fun getManager(): WifiP2pManager? = wifiP2pManager
    fun getChannel(): WifiP2pManager.Channel? = channel

    fun discoverPeers(
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Int) -> Unit)? = null
    ) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers onSuccess")
                onSuccess?.invoke()
            }

            override fun onFailure(reasonCode: Int) {
                Log.w(TAG, "discoverPeers onFailure: reasonCode=$reasonCode")
                onFailure?.invoke(reasonCode)
            }
        })
    }

    fun stopPeerDiscovery(
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Int) -> Unit)? = null
    ) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "stopPeerDiscovery onSuccess")
                onSuccess?.invoke()
            }

            override fun onFailure(reasonCode: Int) {
                Log.w(TAG, "stopPeerDiscovery onFailure: reasonCode=$reasonCode")
                onFailure?.invoke(reasonCode)
            }
        })
    }

    fun requestPeers(callback: (List<WifiP2pDevice>) -> Unit) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.requestPeers(ch) { deviceList: WifiP2pDeviceList? ->
            val list = deviceList?.deviceList?.toList() ?: emptyList()
            callback(list)
        }
    }

    fun connect(
        deviceAddress: String,
        groupOwnerIntent: Int = -1,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Int) -> Unit)? = null
    ) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            if (groupOwnerIntent in 0..15) {
                this.groupOwnerIntent = groupOwnerIntent
            }
        }

        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect onSuccess to $deviceAddress")
                onSuccess?.invoke()
            }

            override fun onFailure(reasonCode: Int) {
                Log.w(TAG, "connect onFailure to $deviceAddress: reasonCode=$reasonCode")
                onFailure?.invoke(reasonCode)
            }
        })
    }

    fun removeGroup(
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Int) -> Unit)? = null
    ) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "removeGroup onSuccess")
                onSuccess?.invoke()
            }

            override fun onFailure(reasonCode: Int) {
                Log.w(TAG, "removeGroup onFailure: reasonCode=$reasonCode")
                onFailure?.invoke(reasonCode)
            }
        })
    }

    /**
     * ServerSocket creation for Group Owner (from Mobile Talkie j2.f createServerSocket).
     */
    suspend fun createServerSocket(port: Int, timeoutMs: Int = 30000): Socket? = withContext(Dispatchers.IO) {
        try {
            closeServerSocket()
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = timeoutMs
            }
            activeServerSocket = server
            Log.d(TAG, "ServerSocket listening on port $port, awaiting client...")
            val clientSocket = server.accept().apply {
                keepAlive = true
                tcpNoDelay = true
            }
            Log.d(TAG, "Client socket accepted: ${clientSocket.remoteSocketAddress}")
            return@withContext clientSocket
        } catch (e: Exception) {
            Log.e(TAG, "createServerSocket error on port $port: ${e.message}")
            return@withContext null
        }
    }

    /**
     * ClientSocket creation connecting to Group Owner (from Mobile Talkie j2.f createClientSocket).
     */
    suspend fun createClientSocket(
        host: InetAddress,
        port: Int,
        timeoutMs: Int = 5000,
        maxRetries: Int = 10
    ): Socket? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var retries = maxRetries
        while (socket == null && retries > 0) {
            try {
                val s = Socket().apply {
                    reuseAddress = true
                    keepAlive = true
                    tcpNoDelay = true
                }
                s.connect(InetSocketAddress(host, port), timeoutMs)
                socket = s
                Log.d(TAG, "Connected to Group Owner $host on port $port")
            } catch (e: Exception) {
                retries--
                if (retries > 0) {
                    kotlinx.coroutines.delay(600)
                } else {
                    Log.e(TAG, "createClientSocket failed to connect to $host:$port: ${e.message}")
                }
            }
        }
        return@withContext socket
    }

    /**
     * Establishes a socket channel regardless of which peer became the Group Owner.
     * Implements the symmetric logic from Mobile Talkie (c1 / b3 / L3 in MainViewModel):
     * - If this device is Group Owner -> runs createServerSocket(port).
     * - If this device is Client -> runs createClientSocket(info.groupOwnerAddress, port).
     */
    suspend fun establishSocket(
        info: WifiP2pInfo,
        port: Int
    ): Socket? = withContext(Dispatchers.IO) {
        if (!info.groupFormed) return@withContext null

        return@withContext if (info.isGroupOwner) {
            createServerSocket(port)
        } else {
            val goAddress = info.groupOwnerAddress ?: InetAddress.getByName("192.168.49.1")
            createClientSocket(goAddress, port)
        }
    }

    fun closeServerSocket() {
        try {
            activeServerSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "closeServerSocket error: ${e.message}")
        } finally {
            activeServerSocket = null
        }
    }

    fun release() {
        closeServerSocket()
        channel = null
        wifiP2pManager = null
    }
}
