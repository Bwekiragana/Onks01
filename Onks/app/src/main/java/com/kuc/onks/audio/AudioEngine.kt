package com.kuc.onks.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusApplication
import org.concentus.OpusSignal
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AudioEngine handles all audio I/O for Onks.
 *
 * Performer: mic → noise gate → compressor → AGC → Opus encode → callback
 * Host:      Opus decode → mix all streams → wired output
 *
 * 48 kHz · 16-bit mono · 20 ms frames (960 samples)
 */
class AudioEngine(
    private val audioManager: AudioManager,
    private val isHost: Boolean
) {
    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = 960           // 20 ms at 48 kHz
        const val OPUS_BITRATE = 32000
        const val MAX_PACKET_SIZE = 4000

        // DSP
        const val NOISE_GATE_THRESHOLD = 300
        const val GATE_HOLD_SAMPLES = 4800
        const val COMPRESSOR_THRESHOLD = 16000
        const val COMPRESSOR_RATIO = 4.0f
        const val AGC_TARGET_RMS = 8000
        const val AGC_MAX_GAIN = 8.0f
        const val AGC_MIN_GAIN = 0.5f
        const val AGC_ATTACK = 0.01f
        const val AGC_RELEASE = 0.001f
    }

    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private var recordThread: Thread? = null

    // DSP state
    private var agcGain = 1.0f
    private var gateHoldCounter = 0
    private var gateOpen = false

    // Callbacks
    var onEncodedPacket: ((ByteArray) -> Unit)? = null
    var onVuMeter: ((Float) -> Unit)? = null

    // Host mixer: peerId → queue of decoded PCM frames
    private val playbackBuffers = mutableMapOf<String, ArrayDeque<ShortArray>>()
    private val playbackLock = Any()

    // ── Initialise ──────────────────────────────────────────────────────────

    fun init(): Boolean {
        return try {
            if (!isHost) {
                encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
                    setBitrate(OPUS_BITRATE)
                    setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
                    setComplexity(5)
                    setInbandFEC(true)
                    setPacketLossPerc(5)
                }
            }
            decoder = OpusDecoder(SAMPLE_RATE, 1)
            Log.i(TAG, "Opus codec ready")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Codec init failed", e)
            false
        }
    }

    // ── Recording (Performer) ───────────────────────────────────────────────

    fun startRecording() {
        if (isRecording.getAndSet(true)) return

        val bufferSize = max(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ), FRAME_SIZE * 4
        )

        val source = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            MediaRecorder.AudioSource.UNPROCESSED
        else
            MediaRecorder.AudioSource.MIC

        audioRecord = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
                .also { if (it.state != AudioRecord.STATE_INITIALIZED) throw Exception("Init failed") }
        } catch (e: Exception) {
            Log.w(TAG, "UNPROCESSED source failed, falling back to MIC")
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
        }

        audioRecord?.startRecording()

        recordThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(FRAME_SIZE)
            val opus = ByteArray(MAX_PACKET_SIZE)
            while (isRecording.get()) {
                val read = audioRecord?.read(pcm, 0, FRAME_SIZE) ?: break
                if (read != FRAME_SIZE) continue
                val processed = processAudio(pcm)
                onVuMeter?.invoke(min(computeRms(processed) / 32767f, 1f))
                try {
                    val len = encoder?.encode(processed, 0, FRAME_SIZE, opus, 0, MAX_PACKET_SIZE) ?: continue
                    if (len > 0) onEncodedPacket?.invoke(opus.copyOf(len))
                } catch (e: Exception) {
                    Log.w(TAG, "Encode: ${e.message}")
                }
            }
        }.also { it.start() }
    }

    fun stopRecording() {
        isRecording.set(false)
        recordThread?.join(500)
        recordThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // ── DSP: Noise Gate → Compressor → AGC ─────────────────────────────────

    private fun processAudio(input: ShortArray): ShortArray {
        val out = ShortArray(input.size)
        val maxAmp = input.maxOf { abs(it.toInt()) }
        if (maxAmp > NOISE_GATE_THRESHOLD) {
            gateOpen = true; gateHoldCounter = GATE_HOLD_SAMPLES
        } else {
            if (gateHoldCounter > 0) gateHoldCounter -= input.size else gateOpen = false
        }
        val rms = computeRms(input)
        if (rms > 0) {
            val target = (AGC_TARGET_RMS.toFloat() / rms).coerceIn(AGC_MIN_GAIN, AGC_MAX_GAIN)
            agcGain = if (target < agcGain)
                agcGain * (1 - AGC_ATTACK) + target * AGC_ATTACK
            else
                agcGain * (1 - AGC_RELEASE) + target * AGC_RELEASE
        }
        for (i in input.indices) {
            var s = if (gateOpen) input[i].toFloat() else 0f
            val a = abs(s)
            if (a > COMPRESSOR_THRESHOLD) {
                val excess = a - COMPRESSOR_THRESHOLD
                s = (if (s > 0) 1f else -1f) * (COMPRESSOR_THRESHOLD + excess / COMPRESSOR_RATIO)
            }
            out[i] = (s * agcGain).coerceIn(-32767f, 32767f).toInt().toShort()
        }
        return out
    }

    private fun computeRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toLong() * s.toLong()
        return sqrt(sum / samples.size).toFloat()
    }

    // ── Playback (Host) ─────────────────────────────────────────────────────

    fun startPlayback() {
        if (isPlaying.getAndSet(true)) return

        val bufferSize = max(
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ), FRAME_SIZE * 4
        )

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }

        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val mix = ShortArray(FRAME_SIZE)
            while (isPlaying.get()) {
                mix.fill(0)
                synchronized(playbackLock) {
                    for ((_, q) in playbackBuffers) {
                        val frame = q.removeFirstOrNull() ?: continue
                        for (i in mix.indices) {
                            mix[i] = (mix[i].toInt() + frame[i].toInt())
                                .coerceIn(-32767, 32767).toShort()
                        }
                    }
                }
                audioTrack?.write(mix, 0, FRAME_SIZE)
            }
        }.start()
    }

    fun receivePacket(peerId: String, opusData: ByteArray) {
        val pcm = ShortArray(FRAME_SIZE)
        try {
            decoder?.decode(opusData, 0, opusData.size, pcm, 0, FRAME_SIZE, false)
        } catch (e: Exception) {
            Log.w(TAG, "Decode error $peerId: ${e.message}"); return
        }
        synchronized(playbackLock) {
            val q = playbackBuffers.getOrPut(peerId) { ArrayDeque() }
            if (q.size < 5) q.addLast(pcm)
        }
    }

    fun setPerformerVolume(peerId: String, volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun stopPlayback() {
        isPlaying.set(false)
        Thread.sleep(120)
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun release() {
        stopRecording()
        stopPlayback()
        encoder?.destroy()
        decoder?.destroy()
        encoder = null
        decoder = null
    }
}
