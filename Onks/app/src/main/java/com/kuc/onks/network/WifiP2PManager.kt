package com.kuc.onks.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages WiFi Direct P2P discovery, group formation, and reconnection.
 */
class WifiP2PManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiP2PManager"
        const val SERVICE_TYPE = "_onksdnd._tcp"
    }

    private val manager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    sealed class P2PState {
        object Idle : P2PState()
        object Discovering : P2PState()
        data class Connected(val groupOwnerAddress: String) : P2PState()
        object Disconnected : P2PState()
        data class Error(val message: String) : P2PState()
    }

    private val _state = MutableStateFlow<P2PState>(P2PState.Idle)
    val state: StateFlow<P2PState> = _state

    var onGroupOwnerAddress: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun init() {
        channel = manager.initialize(context, context.mainLooper) {
            Log.w(TAG, "Channel disconnected — scheduling reconnect")
            _state.value = P2PState.Disconnected
            onDisconnected?.invoke()
            scope.launch { delay(3000); init() }
        }
        registerReceiver()
        Log.i(TAG, "WifiP2PManager ready")
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = P2PBroadcastReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun createGroup(onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        val ch = channel ?: return onFailure("Not initialised")
        manager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { requestGroupInfo(onSuccess) }
            override fun onFailure(reason: Int) {
                // Group may already exist — try requesting info directly
                requestGroupInfo(onSuccess)
            }
        })
    }

    private fun requestGroupInfo(onOwner: (String) -> Unit) {
        val ch = channel ?: return
        manager.requestGroupInfo(ch) { group ->
            val ip = if (group?.isGroupOwner == true) "192.168.49.1" else "192.168.49.1"
            Log.i(TAG, "Group owner IP: $ip  passphrase=${group?.passphrase}")
            onOwner(ip)
        }
    }

    fun discoverAndConnect(
        passphrase: String,
        onConnected: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val ch = channel ?: return onError("Not initialised")
        _state.value = P2PState.Discovering

        val txtListener = WifiP2pManager.DnsSdTxtRecordListener { _, _, device ->
            Log.i(TAG, "Found Onks service: ${device.deviceAddress}")
            connectToPeer(device, onConnected, onError)
        }
        manager.setDnsSdResponseListeners(ch, { _, _, _ -> }, txtListener)
        manager.addServiceRequest(
            ch,
            WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE),
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    manager.discoverServices(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = Unit
                        override fun onFailure(r: Int) {
                            Log.w(TAG, "discoverServices failed ($r) — peer fallback")
                            discoverPeersFallback(onConnected, onError)
                        }
                    })
                }
                override fun onFailure(r: Int) = discoverPeersFallback(onConnected, onError)
            }
        )
    }

    private fun discoverPeersFallback(onConnected: (String) -> Unit, onError: (String) -> Unit) {
        val ch = channel ?: return
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(r: Int) = onError("Discovery failed: $r")
        })
    }

    private fun connectToPeer(
        device: WifiP2pDevice,
        onConnected: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestConnectionInfo { addr ->
                    _state.value = P2PState.Connected(addr)
                    onConnected(addr)
                }
            }
            override fun onFailure(r: Int) = onError("Connect failed: $r")
        })
    }

    private fun requestConnectionInfo(onResult: (String) -> Unit) {
        val ch = channel ?: return
        manager.requestConnectionInfo(ch) { info ->
            if (info?.groupFormed == true) {
                val addr = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                onResult(addr)
            }
        }
    }

    fun disconnect() {
        val ch = channel ?: return
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(r: Int) = Unit
        })
    }

    fun release() {
        scope.cancel()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        channel?.close()
        channel = null
    }

    inner class P2PBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val s = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (s != WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        _state.value = P2PState.Error("WiFi Direct disabled")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ni = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            android.net.NetworkInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (ni?.isConnected == true) {
                        requestConnectionInfo { addr ->
                            _state.value = P2PState.Connected(addr)
                            onGroupOwnerAddress?.invoke(addr)
                        }
                    } else {
                        _state.value = P2PState.Disconnected
                        onDisconnected?.invoke()
                    }
                }
            }
        }
    }
}
