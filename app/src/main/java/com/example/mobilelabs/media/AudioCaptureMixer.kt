package com.example.mobilelabs.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.example.mobilelabs.model.AudioChannelState
import com.example.mobilelabs.model.AudioFilterConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Pre-allocated circular stereo PCM delay line for OBS-grade per-channel audio sync (-500..+500 ms).
 */
class AudioDelayRingBuffer(maxSamples: Int = 48000 * 2) {
    private val ring = FloatArray(maxSamples.coerceAtLeast(2048))
    private var writePos = 0

    fun reset() {
        ring.fill(0f)
        writePos = 0
    }

    fun applyDelayInPlace(buf: FloatArray, sampleCount: Int, delayMs: Int, sampleRateHz: Int = 48000) {
        val delaySamples = ((delayMs.coerceIn(0, 500) * sampleRateHz) / 1000) * 2
        val cap = ring.size
        val clampedDelay = delaySamples.coerceIn(0, cap - 2)
        for (i in 0 until sampleCount) {
            val incoming = buf[i]
            ring[writePos] = incoming
            val readPos = (writePos - clampedDelay + cap) % cap
            if (clampedDelay > 0) {
                buf[i] = ring[readPos]
            }
            writePos = (writePos + 1) % cap
        }
    }
}

/**
 * Zero-allocation inline PCM DSP filter processor (M2.3).
 * Executes on pre-allocated float buffers inside the audio mixer thread.
 * All filters default to OFF and bypass immediately with zero math cost when disabled.
 */
class AudioDspProcessor(private val sampleRateHz: Int = 48000) {
    private var gateEnvelope = 1f
    private var compEnvelope = 0f
    private var hpfPrevInL = 0f
    private var hpfPrevOutL = 0f
    private var hpfPrevInR = 0f
    private var hpfPrevOutR = 0f

    // 110 Hz high-pass coefficient for lightweight speech rumble removal when NS is enabled
    private val hpfAlpha = 0.985f

    fun processStereoInPlace(buf: FloatArray, sampleCount: Int, cfg: AudioFilterConfig) {
        if (!cfg.anyActive() || sampleCount <= 0) return

        val gainLin = if (cfg.gainEnabled) dbToLinear(cfg.gainDb) else 1f
        val gateCloseLin = dbToLinear(cfg.gateCloseDb)
        val gateOpenLin = dbToLinear(cfg.gateOpenDb)
        val compThreshLin = dbToLinear(cfg.compressorThresholdDb)
        val compMakeupLin = dbToLinear(cfg.compressorMakeupDb)
        val compRatio = cfg.compressorRatio.coerceIn(1f, 20f)
        val limiterLin = dbToLinear(cfg.limiterCeilingDb)

        val attackCoeff = 0.008f
        val releaseCoeff = 0.0005f

        var i = 0
        while (i < sampleCount) {
            var s = buf[i]

            if (cfg.gainEnabled) {
                s *= gainLin
            }

            if (cfg.noiseSuppressionEnabled) {
                if ((i and 1) == 0) {
                    val out = hpfAlpha * (hpfPrevOutL + s - hpfPrevInL)
                    hpfPrevInL = s
                    hpfPrevOutL = out
                    s = out
                } else {
                    val out = hpfAlpha * (hpfPrevOutR + s - hpfPrevInR)
                    hpfPrevInR = s
                    hpfPrevOutR = out
                    s = out
                }
            }

            val mag = abs(s)

            if (cfg.noiseGateEnabled) {
                val targetGate = when {
                    mag >= gateOpenLin -> 1f
                    mag <= gateCloseLin -> 0f
                    else -> gateEnvelope
                }
                val coeff = if (targetGate > gateEnvelope) attackCoeff else releaseCoeff
                gateEnvelope += (targetGate - gateEnvelope) * coeff
                s *= gateEnvelope
            }

            if (cfg.compressorEnabled) {
                val envCoeff = if (mag > compEnvelope) 0.01f else 0.0008f
                compEnvelope += (mag - compEnvelope) * envCoeff
                if (compEnvelope > compThreshLin && compEnvelope > 1e-6f) {
                    val overDb = 20f * log10(compEnvelope / compThreshLin)
                    val reducedDb = overDb * (1f - 1f / compRatio)
                    val gainReduction = dbToLinear(-reducedDb)
                    s *= gainReduction * compMakeupLin
                } else {
                    s *= compMakeupLin
                }
            }

            if (cfg.limiterEnabled && abs(s) > limiterLin && limiterLin > 0.001f) {
                val sign = if (s >= 0f) 1f else -1f
                val excess = abs(s) - limiterLin
                s = sign * (limiterLin + tanh(excess.toDouble()).toFloat() * 0.05f)
            }

            buf[i] = s.coerceIn(-1f, 1f)
            i++
        }
    }

    companion object {
        fun dbToLinear(db: Float): Float = 10.0.pow((db / 20.0)).toFloat()
    }
}

/**
 * 48 kHz stereo non-blocking audio capture, sync-offset delay line, DSP filter chain, & mixing engine (§7, M2.3):
 * - Non-blocking AudioRecord.READ_NON_BLOCKING reads so silent system audio never stalls microphone capture
 * - Per-channel sync offset (-500..+500 ms) computed cleanly without blocking
 * - Decaying peak-hold dB meter markers alongside live RMS dB
 */
class AudioCaptureMixer(
    private val context: Context,
    private val onEncodedPcmChunk: (pcm16Stereo: ShortArray, sampleCount: Int, ptsUs: Long) -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)

    private var micRecord: AudioRecord? = null
    private var playbackRecord: AudioRecord? = null
    private var platformNoiseSuppressor: NoiseSuppressor? = null
    private var mixerThread: Thread? = null

    private val gameDsp = AudioDspProcessor(48000)
    private val micDsp = AudioDspProcessor(48000)
    private val gameDelayBuffer = AudioDelayRingBuffer()
    private val micDelayBuffer = AudioDelayRingBuffer()

    @Volatile var gameVolume = 1.0f
    @Volatile var gameMuted = false
    @Volatile var gameSyncOffsetMs = 0
    @Volatile var micVolume = 1.0f
    @Volatile var micMuted = false
    @Volatile var micSyncOffsetMs = 0
    @Volatile var masterMuted = false

    @Volatile var gameFilters = AudioFilterConfig()
    @Volatile var micFilters = AudioFilterConfig()
        set(value) {
            field = value
            runCatching { platformNoiseSuppressor?.enabled = value.noiseSuppressionEnabled }
        }

    @Volatile var gamePeakDb = -60f
        private set
    @Volatile var micPeakDb = -60f
        private set
    @Volatile var gamePeakHoldDb = -60f
        private set
    @Volatile var micPeakHoldDb = -60f
        private set
    @Volatile var gameClipping = false
        private set
    @Volatile var micClipping = false
        private set
    @Volatile var gameUnavailableReason: String? = null
        private set
    @Volatile var micUnavailableReason: String? = null
        private set
    @Volatile var activePlaybackRoute = "Tablet Speaker"
        private set
    @Volatile var activeMicRoute = "Built-in Mic"
        private set
    @Volatile var isSpeakerOutputActive = true
        private set
    @Volatile var lastAudioDspMicros: Long = 0L
        private set

    private var gamePeakHoldAtMs = 0L
    private var micPeakHoldAtMs = 0L

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoutes()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(
        mediaProjection: MediaProjection?,
        hasMicPermission: Boolean
    ) {
        if (running.getAndSet(true)) return
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshRoutes()
        gameDelayBuffer.reset()
        micDelayBuffer.reset()

        val sampleRate = 48000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        if (mediaProjection != null) {
            try {
                val playbackCfg = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val record = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(playbackCfg)
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    playbackRecord = record
                    gameUnavailableReason = null
                } else {
                    record.release()
                    gameUnavailableReason = "AudioPlaybackCapture failed to initialize"
                }
            } catch (t: Throwable) {
                gameUnavailableReason = "System audio unavailable: ${t.message ?: "blocked"}"
            }
        } else {
            gameUnavailableReason = "Arm screen capture to enable system audio"
        }

        if (hasMicPermission) {
            try {
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val mic = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf * 2)
                    .build()
                if (mic.state == AudioRecord.STATE_INITIALIZED) {
                    if (NoiseSuppressor.isAvailable()) {
                        runCatching {
                            platformNoiseSuppressor = NoiseSuppressor.create(mic.audioSessionId)?.apply {
                                enabled = micFilters.noiseSuppressionEnabled
                            }
                        }
                    }
                    mic.startRecording()
                    micRecord = mic
                    micUnavailableReason = null
                } else {
                    mic.release()
                    micUnavailableReason = "Mic busy or taken by another app"
                }
            } catch (t: Throwable) {
                micUnavailableReason = "Mic unavailable: ${t.message ?: "error"}"
            }
        } else {
            micUnavailableReason = "Microphone permission not granted"
        }

        mixerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            runMixerLoop()
        }, "MobileLabs-AudioMixer").apply { start() }
    }

    private fun runMixerLoop() {
        val frameSamples = 1024 * 2 // 1024 stereo frames = ~21.33 ms at 48 kHz
        val gameBuf = ShortArray(frameSamples)
        val micBuf = ShortArray(frameSamples)
        val gameFloatBuf = FloatArray(frameSamples)
        val micFloatBuf = FloatArray(frameSamples)
        val mixedBuf = ShortArray(frameSamples)
        var consecutiveGameSilenceFrames = 0

        while (running.get()) {
            val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
            var gameRead = 0
            var micRead = 0

            val pRec = playbackRecord
            if (pRec != null) {
                val n = pRec.read(gameBuf, 0, frameSamples, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) {
                    gameUnavailableReason = "System audio stream closed"
                } else {
                    gameRead = n
                }
            }

            val mRec = micRecord
            if (mRec != null) {
                val n = mRec.read(micBuf, 0, frameSamples, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) {
                    micUnavailableReason = "Microphone taken by another app"
                } else {
                    micRead = n
                }
            }

            if (gameRead == 0 && micRead == 0) {
                SystemClock.sleep(10)
                continue
            }

            val count = max(gameRead, micRead)
            val dspStartNs = System.nanoTime()

            var gameMaxAbs = 0
            var micMaxAbs = 0
            val gMuted = gameMuted || masterMuted
            val mMuted = micMuted || masterMuted
            val gVol = gameVolume
            val mVol = micVolume

            for (i in 0 until count) {
                val gRaw = if (i < gameRead) gameBuf[i].toInt() else 0
                val mRaw = if (i < micRead) micBuf[i].toInt() else 0
                val gAbs = abs(gRaw)
                val mAbs = abs(mRaw)
                if (gAbs > gameMaxAbs) gameMaxAbs = gAbs
                if (mAbs > micMaxAbs) micMaxAbs = mAbs
                gameFloatBuf[i] = if (gMuted) 0f else (gRaw / 32768f) * gVol
                micFloatBuf[i] = if (mMuted) 0f else (mRaw / 32768f) * mVol
            }

            // Negative mic sync offset delays game audio; positive sync offset delays that channel cleanly.
            val gOff = gameSyncOffsetMs
            val mOff = micSyncOffsetMs
            val effGameDelayMs = (gOff - min(0, mOff)).coerceIn(0, 500)
            val effMicDelayMs = (mOff - min(0, gOff)).coerceIn(0, 500)
            gameDelayBuffer.applyDelayInPlace(gameFloatBuf, count, effGameDelayMs, 48000)
            micDelayBuffer.applyDelayInPlace(micFloatBuf, count, effMicDelayMs, 48000)

            val gFilters = gameFilters
            if (!gMuted && gFilters.anyActive()) {
                gameDsp.processStereoInPlace(gameFloatBuf, count, gFilters)
            }
            val mFilters = micFilters
            if (!mMuted && mFilters.anyActive()) {
                micDsp.processStereoInPlace(micFloatBuf, count, mFilters)
            }

            var gameRmsSum = 0.0
            var micRmsSum = 0.0
            for (i in 0 until count) {
                val gFloat = gameFloatBuf[i]
                val mFloat = micFloatBuf[i]
                gameRmsSum += (gFloat * gFloat)
                micRmsSum += (mFloat * mFloat)

                val sum = gFloat + mFloat
                val limited = if (abs(sum) > 0.92f) {
                    tanh(sum.toDouble()).toFloat()
                } else {
                    sum
                }
                mixedBuf[i] = (limited.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            lastAudioDspMicros = (System.nanoTime() - dspStartNs) / 1000L

            val nowMs = SystemClock.elapsedRealtime()
            val gDb = toDb(sqrt(gameRmsSum / max(1, count)).toFloat())
            val mDb = toDb(sqrt(micRmsSum / max(1, count)).toFloat())
            gamePeakDb = gDb
            micPeakDb = mDb
            if (gDb >= gamePeakHoldDb || nowMs - gamePeakHoldAtMs > 1500L) {
                gamePeakHoldDb = if (gDb >= gamePeakHoldDb) gDb else (gamePeakHoldDb - 1.5f).coerceAtLeast(gDb)
                if (gDb >= gamePeakHoldDb) gamePeakHoldAtMs = nowMs
            }
            if (mDb >= micPeakHoldDb || nowMs - micPeakHoldAtMs > 1500L) {
                micPeakHoldDb = if (mDb >= micPeakHoldDb) mDb else (micPeakHoldDb - 1.5f).coerceAtLeast(mDb)
                if (mDb >= micPeakHoldDb) micPeakHoldAtMs = nowMs
            }
            gameClipping = gameMaxAbs >= 32600 && !gameMuted
            micClipping = micMaxAbs >= 32600 && !micMuted

            if (pRec != null && gameMaxAbs == 0) {
                consecutiveGameSilenceFrames++
                if (consecutiveGameSilenceFrames > 140) {
                    gameUnavailableReason = "System audio silent or blocked by foreground app"
                }
            } else if (gameMaxAbs > 4) {
                consecutiveGameSilenceFrames = 0
                gameUnavailableReason = null
            }

            onEncodedPcmChunk(mixedBuf, count, ptsUs)
        }
    }

    fun snapshotChannelStates(): List<AudioChannelState> {
        return listOf(
            AudioChannelState(
                id = "audio_game",
                label = "Game Audio",
                volume = gameVolume,
                muted = gameMuted,
                peakDb = if (gameMuted || masterMuted) -60f else gamePeakDb,
                peakHoldDb = if (gameMuted || masterMuted) -60f else gamePeakHoldDb,
                syncOffsetMs = gameSyncOffsetMs,
                isClipping = gameClipping,
                activeRouteLabel = activePlaybackRoute,
                unavailableReason = gameUnavailableReason,
                filters = gameFilters,
                measuredDspMicros = lastAudioDspMicros
            ),
            AudioChannelState(
                id = "audio_mic",
                label = "Mic/Aux",
                volume = micVolume,
                muted = micMuted,
                peakDb = if (micMuted || masterMuted) -60f else micPeakDb,
                peakHoldDb = if (micMuted || masterMuted) -60f else micPeakHoldDb,
                syncOffsetMs = micSyncOffsetMs,
                isClipping = micClipping,
                activeRouteLabel = activeMicRoute,
                unavailableReason = micUnavailableReason,
                filters = micFilters,
                measuredDspMicros = lastAudioDspMicros
            )
        )
    }

    fun isSpeakerMicFeedbackRisk(): Boolean {
        return isSpeakerOutputActive && !micMuted && micRecord != null && !gameMuted && playbackRecord != null
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { mixerThread?.join(500) }
        mixerThread = null
        runCatching { platformNoiseSuppressor?.release() }
        platformNoiseSuppressor = null
        runCatching { playbackRecord?.stop(); playbackRecord?.release() }
        runCatching { micRecord?.stop(); micRecord?.release() }
        playbackRecord = null
        micRecord = null
        gameDelayBuffer.reset()
        micDelayBuffer.reset()
        gamePeakDb = -60f
        micPeakDb = -60f
        gamePeakHoldDb = -60f
        micPeakHoldDb = -60f
        lastAudioDspMicros = 0L
    }

    private fun refreshRoutes() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btOut = outputs.firstOrNull { info ->
            info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        val usbOut = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        val wiredOut = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        when {
            wiredOut != null -> {
                activePlaybackRoute = "Wired Headphones"
                isSpeakerOutputActive = false
            }
            usbOut != null -> {
                activePlaybackRoute = "USB Audio"
                isSpeakerOutputActive = false
            }
            btOut != null -> {
                activePlaybackRoute = "Bluetooth (${btOut.productName})"
                isSpeakerOutputActive = false
            }
            else -> {
                activePlaybackRoute = "Tablet Speaker"
                isSpeakerOutputActive = true
            }
        }

        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val extMic = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        activeMicRoute = extMic?.let { "${it.productName}" } ?: "Built-in Mic"
    }

    private fun toDb(rms: Float): Float {
        if (rms <= 0.0001f) return -60f
        return min(0f, max(-60f, (20.0 * log10(rms.toDouble())).toFloat()))
    }
}
