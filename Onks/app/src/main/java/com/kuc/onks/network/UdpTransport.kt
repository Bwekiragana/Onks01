package com.kuc.onks.network

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP transport for Onks audio packets.
 *
 * Packet format:
 *   [4 bytes: sender ID] [4 bytes: sequence] [2 bytes: payload length] [N bytes: Opus data]
 *
 * Uses unicast for WiFi Direct, multicast fallback for plain LAN.
 */
class UdpTransport(private val listenPort: Int = DEFAULT_PORT) {

    companion object {
        private const val TAG = "UdpTransport"
        const val DEFAULT_PORT = 47000
        const val MULTICAST_GROUP = "239.77.0.1"
        private const val HEADER_SIZE = 10
        private const val MAX_PAYLOAD = 1400
    }

    private var socket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var receiveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sequenceNumber = 0

    var onPacketReceived: ((senderId: String, opusData: ByteArray) -> Unit)? = null

    fun start(useMulticast: Boolean = false) {
        isRunning.set(true)
        try {
            if (useMulticast) {
                multicastSocket = MulticastSocket(listenPort).apply {
                    joinGroup(InetAddress.getByName(MULTICAST_GROUP))
                    timeToLive = 4
                }
                socket = multicastSocket
            } else {
                socket = DatagramSocket(listenPort)
            }
            socket?.soTimeout = 100
            startReceiving()
            Log.i(TAG, "UDP listening on :$listenPort (multicast=$useMulticast)")
        } catch (e: Exception) {
            Log.e(TAG, "UDP start failed: ${e.message}")
        }
    }

    private fun startReceiving() {
        receiveJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(MAX_PAYLOAD + HEADER_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            while (isRunning.get()) {
                try {
                    socket?.receive(packet)
                    if (packet.length < HEADER_SIZE) continue
                    val bb = ByteBuffer.wrap(buf, 0, packet.length)
                    val senderIdHash = bb.int
                    @Suppress("UNUSED_VARIABLE") val seq = bb.int
                    val payloadLen = bb.short.toInt() and 0xFFFF
                    if (payloadLen <= 0 || payloadLen > packet.length - HEADER_SIZE) continue
                    val opus = ByteArray(payloadLen).also { bb.get(it) }
                    val senderId = "${packet.address.hostAddress}:$senderIdHash"
                    withContext(Dispatchers.Main) {
                        onPacketReceived?.invoke(senderId, opus)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (isRunning.get()) Log.w(TAG, "Receive: ${e.message}")
                }
            }
        }
    }

    fun sendPacket(targetAddress: String, targetPort: Int, senderId: Int, opusData: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                val len = opusData.size.coerceAtMost(MAX_PAYLOAD)
                val bb = ByteBuffer.allocate(HEADER_SIZE + len)
                bb.putInt(senderId)
                bb.putInt(sequenceNumber++)
                bb.putShort(len.toShort())
                bb.put(opusData, 0, len)
                val addr = InetAddress.getByName(targetAddress)
                socket?.send(DatagramPacket(bb.array(), bb.array().size, addr, targetPort))
            } catch (e: Exception) {
                Log.w(TAG, "Send to $targetAddress: ${e.message}")
            }
        }
    }

    fun sendMulticast(senderId: Int, opusData: ByteArray) =
        sendPacket(MULTICAST_GROUP, listenPort, senderId, opusData)

    fun stop() {
        isRunning.set(false)
        receiveJob?.cancel()
        try { multicastSocket?.leaveGroup(InetAddress.getByName(MULTICAST_GROUP)) } catch (_: Exception) {}
        socket?.close()
        socket = null
        multicastSocket = null
        scope.cancel()
    }
}
