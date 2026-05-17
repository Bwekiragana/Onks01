package com.kuc.onks.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.kuc.onks.MainActivity
import com.kuc.onks.R
import com.kuc.onks.audio.AudioEngine
import com.kuc.onks.network.UdpTransport
import com.kuc.onks.network.WifiP2PManager
import com.kuc.onks.room.RoomManager
import com.kuc.onks.server.WebDashboardServer

/**
 * Foreground service that keeps all Onks engines alive during a live session.
 * Holds a partial wake lock so audio continues even when the screen is off.
 */
class OnksService : Service() {

    companion object {
        private const val CHANNEL_ID = "onks_live"
        private const val NOTIF_ID = 1
        const val EXTRA_IS_HOST = "is_host"
        const val EXTRA_NAME = "name"
    }

    inner class LocalBinder : Binder() {
        fun getService(): OnksService = this@OnksService
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    // Public engine references — Activities bind and use these
    var audioEngine: AudioEngine? = null
    var udpTransport: UdpTransport? = null
    var wifiP2PManager: WifiP2PManager? = null
    var roomManager: RoomManager? = null
    var webDashboardServer: WebDashboardServer? = null
    var isHost = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isHost = intent?.getBooleanExtra(EXTRA_IS_HOST, false) ?: false
        val notifText = if (isHost) getString(R.string.notif_host) else getString(R.string.notif_performer)
        startForeground(NOTIF_ID, buildNotification(notifText))
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        releaseAll()
        wakeLock?.release()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Onks::LiveSession")
            .apply { acquire(4 * 60 * 60 * 1000L) } // 4 h max
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_desc)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radio_wave)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun releaseAll() {
        webDashboardServer?.stop()
        audioEngine?.release()
        udpTransport?.stop()
        wifiP2PManager?.release()
        webDashboardServer = null
        audioEngine = null
        udpTransport = null
        wifiP2PManager = null
        roomManager = null
    }
}
