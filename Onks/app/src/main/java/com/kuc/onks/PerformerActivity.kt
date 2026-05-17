package com.kuc.onks

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kuc.onks.audio.AudioEngine
import com.kuc.onks.databinding.ActivityPerformerBinding
import com.kuc.onks.network.UdpTransport
import com.kuc.onks.network.WifiP2PManager
import com.kuc.onks.service.OnksService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PerformerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPerformerBinding
    private lateinit var audioEngine: AudioEngine
    private lateinit var udpTransport: UdpTransport
    private lateinit var wifiP2PManager: WifiP2PManager

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private var hostIp = "192.168.49.1"
    private var myName = "Performer"
    private var myIdHash = 0
    private var isPttActive = false
    private var isConnected = false

    private var transmitTimeoutJob: Job? = null

    private var service: OnksService? = null
    private var isBound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            service = (b as OnksService.LocalBinder).getService()
            isBound = true
            initEngines()
        }
        override fun onServiceDisconnected(n: ComponentName) { isBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPerformerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        myName = intent.getStringExtra(MainActivity.EXTRA_NAME) ?: "Performer"
        myIdHash = myName.hashCode()
        binding.tvPerformerName.text = myName

        bindOnksService()
        setupPttButton()
    }

    private fun bindOnksService() {
        Intent(this, OnksService::class.java).also {
            it.putExtra(OnksService.EXTRA_IS_HOST, false)
            it.putExtra(OnksService.EXTRA_NAME, myName)
            startForegroundService(it)
            bindService(it, conn, BIND_AUTO_CREATE)
        }
    }

    private fun initEngines() {
        val svc = service ?: return

        audioEngine = AudioEngine(audioManager, isHost = false).apply {
            init()
            onEncodedPacket = { opus ->
                if (isPttActive && isConnected) {
                    if (hostIp == UdpTransport.MULTICAST_GROUP) {
                        udpTransport.sendMulticast(myIdHash, opus)
                    } else {
                        udpTransport.sendPacket(hostIp, UdpTransport.DEFAULT_PORT, myIdHash, opus)
                    }
                    // Auto-stop after 60 s to prevent stuck PTT
                    transmitTimeoutJob?.cancel()
                    transmitTimeoutJob = lifecycleScope.launch {
                        delay(60_000)
                        if (isPttActive) stopPtt()
                    }
                }
            }
            svc.audioEngine = this
        }

        udpTransport = UdpTransport(UdpTransport.DEFAULT_PORT + 1).apply {
            start()
            svc.udpTransport = this
        }

        wifiP2PManager = WifiP2PManager(this).apply {
            init()
            onGroupOwnerAddress = { ip ->
                hostIp = ip
                isConnected = true
                runOnUiThread { setConnected(true) }
            }
            onDisconnected = {
                isConnected = false
                runOnUiThread { setConnected(false) }
            }
            svc.wifiP2PManager = this
        }

        connectToHost()
    }

    private fun connectToHost() {
        setConnected(false)
        wifiP2PManager.discoverAndConnect(
            passphrase = "",
            onConnected = { ip ->
                hostIp = ip
                isConnected = true
                runOnUiThread { setConnected(true) }
            },
            onError = {
                runOnUiThread {
                    binding.tvConnectionStatus.text = "● WiFi Direct unavailable — using LAN"
                    useLanFallback()
                }
            }
        )
    }

    private fun useLanFallback() {
        udpTransport.stop()
        udpTransport = UdpTransport(UdpTransport.DEFAULT_PORT).apply { start(useMulticast = true) }
        service?.udpTransport = udpTransport
        hostIp = UdpTransport.MULTICAST_GROUP
        isConnected = true
        setConnected(true)
    }

    private fun setConnected(connected: Boolean) {
        if (connected) {
            binding.tvConnectionStatus.text = "● Connected to stage"
            binding.tvConnectionStatus.setTextColor(getColor(R.color.status_ready))
        } else {
            binding.tvConnectionStatus.text = "● Connecting..."
            binding.tvConnectionStatus.setTextColor(getColor(R.color.status_connecting))
        }
    }

    // ── PTT ─────────────────────────────────────────────────────────────────

    private fun setupPttButton() {
        binding.btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN  -> { startPtt(); true }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> { stopPtt(); true }
                else -> false
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0 && !isPttActive) startPtt()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { stopPtt(); return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun startPtt() {
        if (isPttActive || !isConnected) return
        isPttActive = true
        audioEngine.startRecording()

        binding.btnPtt.text = "Release to Stop"
        binding.btnPtt.background = getDrawable(R.drawable.gradient_ptt_active)
        binding.tvLiveIndicator.text = "🎙 LIVE – $myName"
        binding.tvLiveIndicator.visibility = android.view.View.VISIBLE
        binding.tvLiveIndicator.setTextColor(getColor(R.color.status_transmitting))
    }

    private fun stopPtt() {
        if (!isPttActive) return
        isPttActive = false
        transmitTimeoutJob?.cancel()
        audioEngine.stopRecording()

        binding.btnPtt.text = getString(R.string.ptt_hold)
        binding.btnPtt.background = getDrawable(R.drawable.ripple_ptt)
        binding.tvLiveIndicator.visibility = android.view.View.INVISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPtt()
        if (isBound) { unbindService(conn); isBound = false }
    }
}
