package com.kuc.onks

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kuc.onks.audio.AudioEngine
import com.kuc.onks.databinding.ActivityHostBinding
import com.kuc.onks.network.UdpTransport
import com.kuc.onks.network.WifiP2PManager
import com.kuc.onks.room.RoomManager
import com.kuc.onks.server.WebDashboardServer
import com.kuc.onks.service.OnksService
import com.kuc.onks.util.AudioRouteHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding
    private lateinit var roomManager: RoomManager
    private lateinit var audioEngine: AudioEngine
    private lateinit var udpTransport: UdpTransport
    private lateinit var wifiP2PManager: WifiP2PManager
    private lateinit var webServer: WebDashboardServer
    private lateinit var performerAdapter: PerformerAdapter

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
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
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupRecyclerView()
        setupButtons()
        bindOnksService()
    }

    private fun bindOnksService() {
        Intent(this, OnksService::class.java).also {
            it.putExtra(OnksService.EXTRA_IS_HOST, true)
            startForegroundService(it)
            bindService(it, conn, BIND_AUTO_CREATE)
        }
    }

    private fun setupRecyclerView() {
        performerAdapter = PerformerAdapter(
            onMuteToggle = { id, muted ->
                if (::roomManager.isInitialized) roomManager.mutePerformer(id, muted)
            },
            onVolumeChange = { id, vol ->
                if (::roomManager.isInitialized) {
                    roomManager.setPerformerVolume(id, vol)
                    if (::audioEngine.isInitialized) audioEngine.setPerformerVolume(id, vol)
                }
            }
        )
        binding.rvPerformers.apply {
            layoutManager = LinearLayoutManager(this@HostActivity)
            adapter = performerAdapter
        }
    }

    private fun initEngines() {
        val svc = service ?: return

        // Room
        roomManager = RoomManager(this).also { svc.roomManager = it }

        // Audio engine (Host = playback only)
        audioEngine = AudioEngine(audioManager, isHost = true).apply {
            init(); svc.audioEngine = this
        }

        // UDP listener — receives Opus from performers
        udpTransport = UdpTransport(UdpTransport.DEFAULT_PORT).apply {
            onPacketReceived = { senderId, opus ->
                val p = roomManager.getPerformer(senderId)
                if (p == null) {
                    // Auto-register unknown sender (LAN fallback)
                    roomManager.addPerformer(senderId, "Performer-${senderId.takeLast(4)}")
                }
                if (roomManager.getPerformer(senderId)?.isMuted != true) {
                    audioEngine.receivePacket(senderId, opus)
                    roomManager.markTransmitting(senderId, true)
                }
            }
            start()
            svc.udpTransport = this
        }

        // Web dashboard
        webServer = WebDashboardServer(roomManager).apply {
            onCueReceived = { cue ->
                runOnUiThread {
                    binding.tvCueDisplay.visibility = android.view.View.VISIBLE
                    binding.tvCueDisplay.text = "📢 $cue"
                }
            }
            onMuteToggle = { id, muted -> roomManager.mutePerformer(id, muted) }
            onVolumeChange = { id, vol ->
                roomManager.setPerformerVolume(id, vol)
                audioEngine.setPerformerVolume(id, vol)
            }
            try { start() } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@HostActivity,
                    "Dashboard unavailable (port 8080 in use)", Toast.LENGTH_SHORT).show() }
            }
            svc.webDashboardServer = this
        }

        // WiFi P2P — create group so performers can join
        wifiP2PManager = WifiP2PManager(this).apply {
            init()
            createGroup(
                onSuccess = { ip ->
                    runOnUiThread {
                        binding.tvDashboardUrl.text = "Dashboard → http://$ip:8080"
                    }
                },
                onFailure = {
                    runOnUiThread { showLocalIpDashboard() }
                }
            )
            svc.wifiP2PManager = this
        }

        AudioRouteHelper.forceWiredOutput(audioManager)
        audioEngine.startPlayback()

        // Display PIN + QR
        binding.tvPin.text = "PIN: ${roomManager.pin}"
        binding.ivQrCode.setImageBitmap(roomManager.generateQrCode(480))
        showLocalIpDashboard()
        observeRoom()
    }

    private fun showLocalIpDashboard() {
        val ip = getLocalIp()
        binding.tvDashboardUrl.text = "Dashboard → http://$ip:8080"
    }

    private fun getLocalIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull {
                !it.isLoopbackAddress && it is java.net.Inet4Address &&
                        (it.hostAddress?.startsWith("192.") == true ||
                                it.hostAddress?.startsWith("10.") == true)
            }?.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) { "127.0.0.1" }

    private fun observeRoom() {
        lifecycleScope.launch {
            roomManager.performers.collectLatest { performers ->
                binding.tvConnectedCount.text = "${performers.size} performer(s) connected"
                performerAdapter.submitList(performers.toList())
            }
        }
        lifecycleScope.launch {
            roomManager.transmittingPerformer.collectLatest { id ->
                if (id != null) {
                    val name = roomManager.getPerformer(id)?.name ?: id.takeLast(6)
                    binding.tvLiveStatus.text = "🔴 LIVE – $name is speaking"
                    binding.tvLiveStatus.setTextColor(getColor(R.color.status_transmitting))
                } else {
                    binding.tvLiveStatus.text = "● Listening..."
                    binding.tvLiveStatus.setTextColor(getColor(R.color.status_ready))
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnAllCall.setOnClickListener {
            if (::roomManager.isInitialized) {
                roomManager.setAllCall(true)
                Toast.makeText(this, "ALL CALL broadcast sent to all performers", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSoundCheck.setOnClickListener {
            Toast.makeText(this,
                "Sound Check: each performer's mic now routes live to the PA",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) { unbindService(conn); isBound = false }
        AudioRouteHelper.resetToNormal(audioManager)
    }
}
