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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.example.mobilelabs.model.AudioChannelState
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.SoundboardPad
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
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
 * Fixed-capacity lock-safe PCM short circular FIFO for Media Source and Soundboard mixing.
 */
class PcmStereoRingFifo(capacityShorts: Int = 48000 * 2 * 4) {
    private val buf = ShortArray(capacityShorts)
    private var readPos = 0
    private var writePos = 0
    private var available = 0

    @Synchronized
    fun clear() {
        readPos = 0
        writePos = 0
        available = 0
    }

    @Synchronized
    fun push(samples: ShortArray, count: Int) {
        val n = min(count, samples.size)
        for (i in 0 until n) {
            if (available == buf.size) {
                readPos = (readPos + 1) % buf.size
                available--
            }
            buf[writePos] = samples[i]
            writePos = (writePos + 1) % buf.size
            available++
        }
    }

    @Synchronized
    fun popInto(dst: ShortArray, requested: Int): Int {
        val take = min(requested, available)
        for (i in 0 until take) {
            dst[i] = buf[readPos]
            readPos = (readPos + 1) % buf.size
        }
        available -= take
        return take
    }
}

/**
 * Zero-allocation inline PCM DSP filter processor:
 * Gain, 110Hz Speech High-Pass + NS, Noise Gate, Compressor, Soft-Knee Limiter.
 */
class AudioDspProcessor(private val sampleRateHz: Int = 48000) {
    private var gateEnvelope = 1f
    private var compEnvelope = 0f
    private var hpfPrevInL = 0f
    private var hpfPrevOutL = 0f
    private var hpfPrevInR = 0f
    private var hpfPrevOutR = 0f
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

        /**
         * Computes auto-ducking attenuation multiplier based on mic RMS level and target duck dB.
         */
        fun computeDuckingGainStep(
            currentDuckGain: Float,
            micRmsLinear: Float,
            duckAmountDb: Float,
            enabled: Boolean
        ): Float {
            if (!enabled) return 1f
            val voiceThreshold = dbToLinear(-34f)
            val target = if (micRmsLinear >= voiceThreshold) {
                dbToLinear(duckAmountDb.coerceIn(-24f, -2f))
            } else {
                1f
            }
            val coeff = if (target < currentDuckGain) 0.25f else 0.04f
            return (currentDuckGain + (target - currentDuckGain) * coeff).coerceIn(0.05f, 1f)
        }
    }
}

/**
 * 48 kHz stereo pro audio capture, sync-offset delay line, DSP filter chain, auto-ducking,
 * Push-to-Talk, Media Source audio mixer, & Soundboard SFX player.
 *
 * Fixes:
 * - Fixed 1024-frame (21.33 ms) cadence emitter prevents MediaMuxer stall when sources are silent/muted
 * - Per-channel read accumulators prevent phase drift and clicks on mismatched AudioRecord read lengths
 */
class AudioCaptureMixer(
    private val context: Context,
    private val onEncodedPcmChunk: (pcm16Stereo: ShortArray, sampleCount: Int, ptsUs: Long) -> Unit
) {

    private class ActiveSfxPlayback(
        val samples: ShortArray,
        val volume: Float,
        var cursor: Int = 0
    )

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
    private val mediaAudioFifo = PcmStereoRingFifo()
    private val activeSfxClips = CopyOnWriteArrayList<ActiveSfxPlayback>()
    private val decodedSfxCache = mutableMapOf<String, ShortArray>()

    @Volatile var gameVolume = 1.0f
    @Volatile var gameMuted = false
    @Volatile var gameSyncOffsetMs = 0
    @Volatile var micVolume = 1.0f
    @Volatile var micMuted = false
    @Volatile var micSyncOffsetMs = 0
    @Volatile var masterMuted = false
    @Volatile var pushToTalkHeld = false

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

    fun pushMediaAudioPcm(samples: ShortArray, count: Int) {
        if (!running.get() || count <= 0) return
        mediaAudioFifo.push(samples, count)
    }

    fun triggerSoundboardPad(pad: SoundboardPad) {
        val vol = pad.volume.coerceIn(0f, 1.5f)
        val path = pad.filePath
        if (!path.isNullOrBlank()) {
            Thread({
                val pcm = loadOrDecodeSfxFile(path)
                if (pcm != null && pcm.isNotEmpty()) {
                    activeSfxClips.add(ActiveSfxPlayback(pcm, vol))
                }
            }, "MobileLabs-SfxDecode").start()
            return
        }
        val tone = synthesizeBuiltInSfx(pad.builtInTone ?: "CHIME")
        activeSfxClips.add(ActiveSfxPlayback(tone, vol))
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
        mediaAudioFifo.clear()
        activeSfxClips.clear()

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
        val gameFifo = PcmStereoRingFifo(frameSamples * 8)
        val micFifo = PcmStereoRingFifo(frameSamples * 8)
        val scratchRead = ShortArray(frameSamples)
        val gameFrame = ShortArray(frameSamples)
        val micFrame = ShortArray(frameSamples)
        val mediaFrame = ShortArray(frameSamples)
        val gameFloatBuf = FloatArray(frameSamples)
        val micFloatBuf = FloatArray(frameSamples)
        val mixedBuf = ShortArray(frameSamples)

        var consecutiveGameSilenceFrames = 0
        var duckGain = 1f
        var nextEmitNs = SystemClock.elapsedRealtimeNanos()
        val frameStepNs = 21_333_333L

        while (running.get()) {
            val pRec = playbackRecord
            if (pRec != null) {
                val n = pRec.read(scratchRead, 0, frameSamples, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) {
                    gameUnavailableReason = "System audio stream closed"
                } else if (n > 0) {
                    gameFifo.push(scratchRead, n)
                }
            }

            val mRec = micRecord
            if (mRec != null) {
                val n = mRec.read(scratchRead, 0, frameSamples, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) {
                    micUnavailableReason = "Microphone taken by another app"
                } else if (n > 0) {
                    micFifo.push(scratchRead, n)
                }
            }

            val nowNs = SystemClock.elapsedRealtimeNanos()
            val gameRead = gameFifo.popInto(gameFrame, frameSamples)
            val micRead = micFifo.popInto(micFrame, frameSamples)

            // Wait cleanly until next 21.33ms cadence tick so MediaMuxer always receives continuous clock
            if (gameRead < frameSamples && micRead < frameSamples && nowNs < nextEmitNs) {
                val sleepMs = ((nextEmitNs - nowNs) / 1_000_000L).coerceIn(1L, 12L)
                SystemClock.sleep(sleepMs)
                continue
            }
            if (gameRead < frameSamples) gameFrame.fill(0, gameRead, frameSamples)
            if (micRead < frameSamples) micFrame.fill(0, micRead, frameSamples)

            val mediaRead = mediaAudioFifo.popInto(mediaFrame, frameSamples)
            if (mediaRead < frameSamples) mediaFrame.fill(0, mediaRead, frameSamples)

            val ptsUs = nextEmitNs / 1000L
            nextEmitNs = max(nowNs, nextEmitNs + frameStepNs)

            val dspStartNs = System.nanoTime()
            var gameMaxAbs = 0
            var micMaxAbs = 0
            val gMuted = gameMuted || masterMuted
            val mFilters = micFilters
            val pttBlocked = mFilters.pushToTalkEnabled && !pushToTalkHeld
            val mMuted = micMuted || masterMuted || pttBlocked
            val gVol = gameVolume
            val mVol = micVolume

            for (i in 0 until frameSamples) {
                val gRaw = gameFrame[i].toInt() + mediaFrame[i].toInt()
                val mRaw = micFrame[i].toInt()
                val gAbs = abs(gRaw)
                val mAbs = abs(mRaw)
                if (gAbs > gameMaxAbs) gameMaxAbs = gAbs
                if (mAbs > micMaxAbs) micMaxAbs = mAbs
                gameFloatBuf[i] = if (gMuted) 0f else (gRaw / 32768f) * gVol
                micFloatBuf[i] = if (mMuted) 0f else (mRaw / 32768f) * mVol
            }

            val gOff = gameSyncOffsetMs
            val mOff = micSyncOffsetMs
            val effGameDelayMs = (gOff - min(0, mOff)).coerceIn(0, 500)
            val effMicDelayMs = (mOff - min(0, gOff)).coerceIn(0, 500)
            gameDelayBuffer.applyDelayInPlace(gameFloatBuf, frameSamples, effGameDelayMs, 48000)
            micDelayBuffer.applyDelayInPlace(micFloatBuf, frameSamples, effMicDelayMs, 48000)

            val gFilters = gameFilters
            if (!gMuted && gFilters.anyActive()) {
                gameDsp.processStereoInPlace(gameFloatBuf, frameSamples, gFilters)
            }
            if (!mMuted && mFilters.anyActive()) {
                micDsp.processStereoInPlace(micFloatBuf, frameSamples, mFilters)
            }

            // Compute mic RMS first so Auto-Ducking smoothly lowers Game Audio while speaker talks
            var micRmsPreSum = 0.0
            for (i in 0 until frameSamples) {
                val mf = micFloatBuf[i]
                micRmsPreSum += (mf * mf)
            }
            val micRmsLin = sqrt(micRmsPreSum / frameSamples).toFloat()
            val duckEnabled = !masterMuted && (gFilters.autoDuckingEnabled || mFilters.autoDuckingEnabled)
            val duckDb = if (gFilters.autoDuckingEnabled) gFilters.duckAmountDb else mFilters.duckAmountDb
            duckGain = AudioDspProcessor.computeDuckingGainStep(duckGain, micRmsLin, duckDb, duckEnabled)

            var gameRmsSum = 0.0
            for (i in 0 until frameSamples) {
                val gFloat = gameFloatBuf[i] * duckGain
                gameRmsSum += (gFloat * gFloat)
                val mFloat = micFloatBuf[i]
                var sfxFloat = 0f
                if (activeSfxClips.isNotEmpty() && !masterMuted) {
                    for (clip in activeSfxClips) {
                        if (clip.cursor < clip.samples.size) {
                            sfxFloat += (clip.samples[clip.cursor++] / 32768f) * clip.volume
                        }
                    }
                }
                val sum = gFloat + mFloat + sfxFloat
                val limited = if (abs(sum) > 0.92f) {
                    tanh(sum.toDouble()).toFloat()
                } else {
                    sum
                }
                mixedBuf[i] = (limited.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            if (activeSfxClips.isNotEmpty()) {
                activeSfxClips.removeAll { it.cursor >= it.samples.size }
            }
            lastAudioDspMicros = (System.nanoTime() - dspStartNs) / 1000L

            val nowMs = SystemClock.elapsedRealtime()
            val gDb = toDb(sqrt(gameRmsSum / frameSamples).toFloat())
            val mDb = toDb(micRmsLin)
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

            onEncodedPcmChunk(mixedBuf, frameSamples, ptsUs)
        }
    }

    private fun loadOrDecodeSfxFile(pathOrUri: String): ShortArray? {
        synchronized(decodedSfxCache) {
            decodedSfxCache[pathOrUri]?.let { return it }
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return runCatching {
            if (pathOrUri.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(pathOrUri), null)
            } else {
                val f = File(pathOrUri)
                if (!f.exists()) return null
                extractor.setDataSource(f.absolutePath)
            }
            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                if (tf.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIdx = i
                    fmt = tf
                    break
                }
            }
            if (trackIdx < 0 || fmt == null) return null
            extractor.selectTrack(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(fmt, null, null, 0)
                start()
            }
            var srcRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
            var srcCh = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val maxSamples = 48000 * 2 * 10 // cap SFX clip at 10 seconds
            val collected = ArrayList<Short>(48000 * 2)
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false

            while (!outputEos && collected.size < maxSamples) {
                if (!inputEos) {
                    val inIdx = codec!!.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = codec!!.getInputBuffer(inIdx)
                        val sz = if (ib != null) extractor.readSampleData(ib, 0) else -1
                        if (sz < 0) {
                            codec!!.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec!!.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec!!.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec!!.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIdx >= 0) {
                    val ob = codec!!.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sv = ob.asShortBuffer()
                        val raw = ShortArray(sv.remaining())
                        sv.get(raw)
                        val converted = MediaSourceDecoder.resampleTo48kStereo(raw, raw.size, srcRate, srcCh)
                        for (s in converted) {
                            if (collected.size >= maxSamples) break
                            collected.add(s)
                        }
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                    codec!!.releaseOutputBuffer(outIdx, false)
                }
            }
            val result = collected.toShortArray()
            synchronized(decodedSfxCache) {
                if (decodedSfxCache.size >= 12) {
                    decodedSfxCache.keys.firstOrNull()?.let { decodedSfxCache.remove(it) }
                }
                decodedSfxCache[pathOrUri] = result
            }
            result
        }.getOrNull().also {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun synthesizeBuiltInSfx(kind: String): ShortArray {
        val sr = 48000
        return when (kind.uppercase()) {
            "HORN" -> {
                val len = (sr * 0.45).toInt()
                ShortArray(len * 2).apply {
                    for (i in 0 until len) {
                        val t = i.toDouble() / sr
                        val env = min(1.0, t / 0.02) * exp(-t * 3.0)
                        val s = (sin(2.0 * PI * 370.0 * t) + 0.7 * sin(2.0 * PI * 554.0 * t) + 0.5 * sin(2.0 * PI * 740.0 * t)) * 0.3
                        val v = (s * env * 28000.0).toInt().coerceIn(-32767, 32767).toShort()
                        this[i * 2] = v
                        this[i * 2 + 1] = v
                    }
                }
            }
            "RIMSHOT" -> {
                val len = (sr * 0.35).toInt()
                ShortArray(len * 2).apply {
                    var seed = 1337
                    for (i in 0 until len) {
                        val t = i.toDouble() / sr
                        seed = (seed * 1664525 + 1013904223)
                        val noise = ((seed ushr 16) and 0xFFFF) / 32768.0 - 1.0
                        val hit1 = if (t < 0.14) exp(-t * 28.0) * (sin(2 * PI * 190 * t) + 0.5 * noise) else 0.0
                        val hit2 = if (t >= 0.14) exp(-(t - 0.14) * 20.0) * (sin(2 * PI * 820 * (t - 0.14)) + 0.6 * noise) else 0.0
                        val v = ((hit1 + hit2) * 24000.0).toInt().coerceIn(-32767, 32767).toShort()
                        this[i * 2] = v
                        this[i * 2 + 1] = v
                    }
                }
            }
            "BOOM" -> {
                val len = (sr * 0.55).toInt()
                ShortArray(len * 2).apply {
                    for (i in 0 until len) {
                        val t = i.toDouble() / sr
                        val freq = 140.0 * exp(-t * 5.0) + 42.0
                        val env = min(1.0, t / 0.01) * exp(-t * 4.5)
                        val v = (sin(2.0 * PI * freq * t) * env * 30000.0).toInt().coerceIn(-32767, 32767).toShort()
                        this[i * 2] = v
                        this[i * 2 + 1] = v
                    }
                }
            }
            else -> { // CHIME
                val len = (sr * 0.45).toInt()
                ShortArray(len * 2).apply {
                    for (i in 0 until len) {
                        val t = i.toDouble() / sr
                        val f = if (t < 0.16) 880.0 else 1318.5
                        val env = min(1.0, t / 0.015) * exp(-t * 4.5)
                        val v = (sin(2.0 * PI * f * t) * env * 22000.0).toInt().coerceIn(-32767, 32767).toShort()
                        this[i * 2] = v
                        this[i * 2 + 1] = v
                    }
                }
            }
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
        mediaAudioFifo.clear()
        activeSfxClips.clear()
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
