package com.kuc.onks.room

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Room lifecycle: PIN, QR, performer registry, mute/volume, All-Call.
 */
class RoomManager(private val context: Context) {

    companion object {
        private const val TAG = "RoomManager"
        const val ROOM_NAME = "KUC D\u0026D Production"
        const val MAX_PERFORMERS = 19
    }

    data class Performer(
        val id: String,
        val name: String,
        var isMuted: Boolean = false,
        var volume: Float = 1.0f,
        var isTransmitting: Boolean = false,
        var isConnected: Boolean = true
    )

    val pin: String = "%06d".format(Random.nextInt(100000, 999999))

    private val performerMap = ConcurrentHashMap<String, Performer>()
    private val _performers = MutableStateFlow<List<Performer>>(emptyList())
    val performers: StateFlow<List<Performer>> = _performers

    private val _transmittingPerformer = MutableStateFlow<String?>(null)
    val transmittingPerformer: StateFlow<String?> = _transmittingPerformer

    private val _isAllCallActive = MutableStateFlow(false)
    val isAllCallActive: StateFlow<Boolean> = _isAllCallActive

    // ── QR ──────────────────────────────────────────────────────────────────

    fun generateQrCode(sizePx: Int = 480): Bitmap {
        val content = "ONKS:$pin:$ROOM_NAME:${getLocalIpAddress()}"
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to
                        com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )
            val matrix = MultiFormatWriter().encode(
                content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
            )
            BarcodeEncoder().createBitmap(matrix)
        } catch (e: Exception) {
            Log.e(TAG, "QR gen failed", e)
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        }
    }

    fun validatePin(input: String) = input.trim() == pin

    // ── Performers ──────────────────────────────────────────────────────────

    fun addPerformer(id: String, name: String): Boolean {
        if (performerMap.size >= MAX_PERFORMERS) return false
        performerMap[id] = Performer(id = id, name = name)
        notify()
        Log.i(TAG, "Joined: $name ($id)  total=${performerMap.size}")
        return true
    }

    fun removePerformer(id: String) {
        performerMap.remove(id)
        if (_transmittingPerformer.value == id) _transmittingPerformer.value = null
        notify()
    }

    fun markTransmitting(id: String, tx: Boolean) {
        performerMap[id]?.isTransmitting = tx
        _transmittingPerformer.value = if (tx) id else null
        notify()
    }

    fun mutePerformer(id: String, muted: Boolean) {
        performerMap[id]?.isMuted = muted; notify()
    }

    fun setPerformerVolume(id: String, volume: Float) {
        performerMap[id]?.volume = volume.coerceIn(0f, 1f); notify()
    }

    fun getPerformer(id: String): Performer? = performerMap[id]

    fun setAllCall(active: Boolean) { _isAllCallActive.value = active }

    private fun notify() {
        _performers.value = performerMap.values.toList().sortedBy { it.name }
    }

    // ── Network ─────────────────────────────────────────────────────────────

    fun getLocalIpAddress(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull {
                !it.isLoopbackAddress && it is java.net.Inet4Address &&
                        (it.hostAddress?.startsWith("192.168.") == true ||
                                it.hostAddress?.startsWith("10.") == true ||
                                it.hostAddress?.startsWith("172.") == true)
            }?.hostAddress ?: "127.0.0.1"
    } catch (e: Exception) { "127.0.0.1" }
}
