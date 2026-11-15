package com.example.mobilelabs.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Surface
import androidx.core.net.toUri
import com.example.mobilelabs.model.EncoderFallbackLevel
import com.example.mobilelabs.model.PublishedRecording
import com.example.mobilelabs.model.RecordingFormat
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.persistence.SceneCollectionRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure monotonic PTS rebase calculator for Pause / Resume MP4 recording continuity.
 */
class RecordingPtsRebaser {
    private var basePtsUs: Long = -1L
    private var totalPausedUs: Long = 0L
    private var pausedAtUs: Long = -1L
    var isPaused: Boolean = false
        private set
    var waitingForResumeKeyframe: Boolean = false
        private set

    fun reset() {
        basePtsUs = -1L
        totalPausedUs = 0L
        pausedAtUs = -1L
        isPaused = false
        waitingForResumeKeyframe = false
    }

    fun pause(currentRawPtsUs: Long) {
        if (isPaused) return
        isPaused = true
        pausedAtUs = currentRawPtsUs
    }

    fun resume(currentRawPtsUs: Long) {
        if (!isPaused) return
        if (pausedAtUs >= 0L && currentRawPtsUs > pausedAtUs) {
            totalPausedUs += (currentRawPtsUs - pausedAtUs)
        }
        pausedAtUs = -1L
        isPaused = false
        waitingForResumeKeyframe = true
    }

    /**
     * Returns rebased microsecond PTS >= 0, or -1L if packet should be skipped (paused / pre-IDR after resume).
     */
    fun filterAndRebase(isVideo: Boolean, isKey: Boolean, rawPtsUs: Long): Long {
        if (isPaused) return -1L
        if (waitingForResumeKeyframe) {
            if (isVideo && isKey) {
                waitingForResumeKeyframe = false
            } else {
                return -1L
            }
        }
        if (basePtsUs < 0L) {
            if (isVideo && !isKey) return -1L
            basePtsUs = rawPtsUs
        }
        return (rawPtsUs - basePtsUs - totalPausedUs).coerceAtLeast(0L)
    }
}

/**
 * Shared Hardware MediaCodec H.264 (Surface Input) + AAC Encoder + Independent Outputs:
 * - Single shared encode feeds MP4 Recording (with Pause/Resume + PTS rebase),
 *   OBS Instant Replay Buffer ring, and up to 4 concurrent RTMP/RTMPS destinations
 *   (YouTube + Twitch + Dual + Custom RTMP).
 */
class HardwareMediaPipeline(
    private val context: Context,
    private val repository: SceneCollectionRepository,
    private val onEncoderSurfaceReady: (Surface?) -> Unit,
    private val onStreamConnected: () -> Unit,
    private val onStreamError: (String) -> Unit,
    private val onRecordingFinalized: (PublishedRecording) -> Unit,
    private val onRecordingError: (String) -> Unit
) {

    @Volatile var customRecordingFolderUri: String? = null
    @Volatile var recordingFormat: RecordingFormat = RecordingFormat.FMP4

    private val videoRunning = AtomicBoolean(false)
    private var videoEncoder: MediaCodec? = null
    private var videoInputSurface: Surface? = null
    private var videoDrainThread: Thread? = null

    private val audioRunning = AtomicBoolean(false)
    private var audioEncoder: MediaCodec? = null
    private var audioDrainThread: Thread? = null

    @Volatile private var currentVideoFormat: MediaFormat? = null
    @Volatile private var currentAudioFormat: MediaFormat? = null
    @Volatile private var videoConfigBytes: ByteArray? = null
    @Volatile private var audioConfigBytes: ByteArray? = null

    // Recording state + Pause/Resume PTS rebase
    private val recordingActive = AtomicBoolean(false)
    private var muxer: MediaMuxer? = null
    private var muxerVideoTrack = -1
    private var muxerAudioTrack = -1
    private var muxerStarted = false
    private var currentRecordFile: File? = null
    private var recordStartMs = 0L
    private var segmentStartMs = 0L
    private val recordBytesWritten = AtomicLong(0L)
    private val muxerLock = Any()
    private val ptsRebaser = RecordingPtsRebaser()
    @Volatile private var lastRawPtsUs = 0L
    private var lastMuxerVideoPtsUs = -1L
    private var lastMuxerAudioPtsUs = -1L

    // Instant Replay Buffer
    val replayRingBuffer = ReplayGopRingBuffer()

    // Named RTMP / RTMPS senders (Primary + Secondary + YouTube + Twitch)
    private val namedSenders = ConcurrentHashMap<String, RtmpFlvSender>()
    private val namedDropCounters = ConcurrentHashMap<String, AtomicLong>()

    // Telemetry counters
    @Volatile var encoderMode = EncoderFallbackLevel.OPTIMAL_HW_SURFACE
        private set
    private val encodedVideoFrames = AtomicLong(0)
    private val encoderDroppedFrames = AtomicLong(0)
    private val networkDroppedFrames = AtomicLong(0)
    private val secondaryNetworkDroppedFrames = AtomicLong(0)
    private val currentBitrateKbps = AtomicInteger(0)
    private var lastBitrateSampleMs = 0L
    private var bytesInWindow = 0L

    fun startEncoders(videoConfig: VideoConfig) {
        if (videoRunning.get()) return
        recordingFormat = videoConfig.recordingFormat
        startVideoEncoder(videoConfig)
        startAudioEncoder(videoConfig.audioBitrateKbps)
    }

    fun updateLiveBitrate(bitrateKbps: Int) {
        val codec = videoEncoder ?: return
        runCatching {
            val bundle = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateKbps * 1000)
            }
            codec.setParameters(bundle)
        }
    }

    fun requestKeyFrame() {
        val codec = videoEncoder ?: return
        runCatching {
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun startRecording(): File? {
        if (recordingActive.get()) return currentRecordFile
        val target = allocateRecordingFile()
        return try {
            synchronized(muxerLock) {
                muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxerVideoTrack = -1
                muxerAudioTrack = -1
                muxerStarted = false
                currentRecordFile = target
                val now = System.currentTimeMillis()
                recordStartMs = now
                segmentStartMs = now
                recordBytesWritten.set(0L)
                ptsRebaser.reset()
                lastMuxerVideoPtsUs = -1L
                lastMuxerAudioPtsUs = -1L
                maybeStartMuxerLocked()
            }
            requestKeyFrame()
            repository.markActiveRecording(target, recordStartMs)
            recordingActive.set(true)
            target
        } catch (t: Throwable) {
            onRecordingError("Muxer start failed: ${t.message}")
            null
        }
    }

    fun pauseRecording() {
        if (!recordingActive.get()) return
        synchronized(muxerLock) {
            ptsRebaser.pause(lastRawPtsUs)
        }
    }

    fun resumeRecording() {
        if (!recordingActive.get()) return
        synchronized(muxerLock) {
            ptsRebaser.resume(lastRawPtsUs)
        }
        requestKeyFrame()
    }

    fun isRecordingPaused(): Boolean = synchronized(muxerLock) { ptsRebaser.isPaused }

    fun stopRecording() {
        if (!recordingActive.getAndSet(false)) return
        val file: File?
        val durationSec: Long
        synchronized(muxerLock) {
            file = currentRecordFile
            durationSec = maxOf(1L, (System.currentTimeMillis() - segmentStartMs) / 1000L)
            runCatching {
                if (muxerStarted) muxer?.stop()
            }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
            muxerVideoTrack = -1
            muxerAudioTrack = -1
            currentRecordFile = null
            ptsRebaser.reset()
        }
        repository.clearActiveRecordingMarker()
        if (file != null && file.exists() && file.length() > 1024L) {
            finalizeAndPublishFile(file, durationSec)
        }
    }

    fun configureReplayBuffer(enabled: Boolean, durationSec: Int) {
        replayRingBuffer.configure(enabled, durationSec)
    }

    fun saveReplayBufferClip(): PublishedRecording? {
        val samples = replayRingBuffer.extractAlignedClipSamples()
        return ReplayClipMuxer.writeReplayMp4(
            repository = repository,
            samples = samples,
            videoFormat = currentVideoFormat,
            audioFormat = currentAudioFormat,
            publishFn = { f -> publishExistingPlayableFile(f) }
        )
    }

    fun publishBitmapScreenshot(bitmap: Bitmap): String? {
        return runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "MobileLabs_Shot_$stamp.png"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Mobile Labs")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        }.getOrNull()
    }

    fun publishExistingPlayableFile(file: File): String? {
        if (!file.exists() || !repository.verifyMediaPlayable(file)) return null
        val customUri = customRecordingFolderUri
        if (!customUri.isNullOrBlank()) {
            publishToSafTree(file, customUri)?.let { return it }
        }
        return publishToMediaStore(file)
    }

    fun startStreaming(rtmpBaseUrl: String, streamKey: String, videoConfig: VideoConfig) {
        startNamedStream(
            slotId = "primary",
            rtmpBaseUrl = rtmpBaseUrl,
            streamKey = streamKey,
            videoConfig = videoConfig,
            onConnected = onStreamConnected,
            onError = onStreamError
        )
    }

    fun stopStreaming() {
        stopNamedStream("primary")
    }

    fun startSecondaryStreaming(
        rtmpBaseUrl: String,
        streamKey: String,
        videoConfig: VideoConfig,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        startNamedStream(
            slotId = "secondary",
            rtmpBaseUrl = rtmpBaseUrl,
            streamKey = streamKey,
            videoConfig = videoConfig,
            onConnected = onConnected,
            onError = onError
        )
    }

    fun stopSecondaryStreaming() {
        stopNamedStream("secondary")
    }

    fun startNamedStream(
        slotId: String,
        rtmpBaseUrl: String,
        streamKey: String,
        videoConfig: VideoConfig,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        stopNamedStream(slotId)
        val dropCounter = namedDropCounters.getOrPut(slotId) { AtomicLong(0L) }
        dropCounter.set(0L)
        val sender = RtmpFlvSender(
            baseUrl = rtmpBaseUrl,
            streamKey = streamKey,
            width = videoConfig.outputWidth,
            height = videoConfig.outputHeight,
            fps = videoConfig.fps,
            videoBitrateKbps = videoConfig.videoBitrateKbps,
            audioBitrateKbps = videoConfig.audioBitrateKbps,
            onConnected = onConnected,
            onFailure = { reason ->
                namedSenders.remove(slotId)
                onError(reason)
            },
            onNetworkDrop = {
                dropCounter.incrementAndGet()
                if (slotId == "primary") networkDroppedFrames.incrementAndGet()
                if (slotId == "secondary") secondaryNetworkDroppedFrames.incrementAndGet()
            }
        )
        namedSenders[slotId] = sender
        sender.start(videoConfigBytes, audioConfigBytes)
    }

    fun stopNamedStream(slotId: String) {
        namedSenders.remove(slotId)?.stop()
    }

    fun stopAllStreams() {
        namedSenders.keys.toList().forEach { stopNamedStream(it) }
    }

    fun getNamedBufferedBytes(slotId: String): Long = namedSenders[slotId]?.bufferedBytes() ?: 0L
    fun getNamedDroppedFrames(slotId: String): Long = namedDropCounters[slotId]?.get() ?: 0L

    fun pushAudioPcm(pcm16Stereo: ShortArray, sampleCount: Int, ptsUs: Long) {
        val codec = audioEncoder ?: return
        if (!audioRunning.get()) return
        runCatching {
            val inIdx = codec.dequeueInputBuffer(0)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx) ?: return
                buf.clear()
                val bytesNeeded = sampleCount * 2
                val maxWritable = minOf(bytesNeeded, buf.remaining())
                val shortsToWrite = maxWritable / 2
                for (i in 0 until shortsToWrite) {
                    buf.putShort(pcm16Stereo[i])
                }
                codec.queueInputBuffer(inIdx, 0, shortsToWrite * 2, ptsUs, 0)
            }
        }
    }

    fun stopEncoders() {
        stopAllStreams()
        stopRecording()
        replayRingBuffer.clear()

        videoRunning.set(false)
        onEncoderSurfaceReady(null)
        runCatching { videoEncoder?.signalEndOfInputStream() }
        runCatching { videoDrainThread?.join(500) }
        videoDrainThread = null
        runCatching { videoEncoder?.stop(); videoEncoder?.release() }
        runCatching { videoInputSurface?.release() }
        videoEncoder = null
        videoInputSurface = null

        audioRunning.set(false)
        runCatching { audioDrainThread?.join(400) }
        audioDrainThread = null
        runCatching { audioEncoder?.stop(); audioEncoder?.release() }
        audioEncoder = null
    }

    fun getEncoderQueueDepth(): Int = namedSenders.values.maxOfOrNull { it.queueDepth() } ?: 0
    fun getRtmpBufferedBytes(): Long = namedSenders.values.sumOf { it.bufferedBytes() }
    fun getSecondaryBufferedBytes(): Long = getNamedBufferedBytes("secondary")
    fun getSecondaryDroppedFrames(): Long = secondaryNetworkDroppedFrames.get()
    fun getEncoderDroppedFrames(): Long = encoderDroppedFrames.get()
    fun getNetworkDroppedFrames(): Long = networkDroppedFrames.get()
    fun getActualBitrateKbps(): Int = currentBitrateKbps.get()
    fun getRecordingBytesWritten(): Long = recordBytesWritten.get()

    private fun allocateRecordingFile(): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        var target = File(repository.recordingsDir, "MobileLabs_$stamp.mp4")
        var suffix = 1
        while (target.exists()) {
            target = File(repository.recordingsDir, "MobileLabs_${stamp}_$suffix.mp4")
            suffix++
        }
        return target
    }

    private fun finalizeAndPublishFile(file: File, durationSec: Long) {
        val sizeBytes = file.length()
        val uri = publishExistingPlayableFile(file) ?: file.absolutePath
        onRecordingFinalized(
            PublishedRecording(
                fileName = file.name,
                mediaStoreUri = uri,
                durationSec = durationSec,
                bytesSize = sizeBytes,
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun startVideoEncoder(cfg: VideoConfig) {
        try {
            val fmt = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                cfg.outputWidth,
                cfg.outputHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, cfg.videoBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.keyframeIntervalSec)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val isHw = codec.codecInfo.isHardwareAccelerated
            encoderMode = if (isHw) {
                EncoderFallbackLevel.OPTIMAL_HW_SURFACE
            } else {
                EncoderFallbackLevel.DEGRADED_HW_BYTEBUFFER
            }

            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surf = codec.createInputSurface()
            codec.start()

            videoEncoder = codec
            videoInputSurface = surf
            videoRunning.set(true)
            onEncoderSurfaceReady(surf)

            videoDrainThread = Thread({ drainVideoLoop(codec) }, "MobileLabs-VideoEnc").apply {
                start()
            }
        } catch (t: Throwable) {
            encoderMode = EncoderFallbackLevel.DEGRADED_HW_BYTEBUFFER
            onStreamError("Hardware H.264 configure failed: ${t.message}")
        }
    }

    private fun drainVideoLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        lastBitrateSampleMs = System.currentTimeMillis()
        while (videoRunning.get()) {
            val idx = runCatching { codec.dequeueOutputBuffer(info, 10_000) }.getOrDefault(-1)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        currentVideoFormat = codec.outputFormat
                        maybeStartMuxerLocked()
                    }
                    extractAvcConfig(codec.outputFormat)
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)

                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            val copy = ByteArray(info.size)
                            buf.get(copy)
                            val avccRec = FlvH264Helper.buildAvcDecoderConfigFromAnnexB(copy)
                            if (avccRec != null) {
                                videoConfigBytes = avccRec
                                namedSenders.values.forEach { it.updateVideoConfig(avccRec) }
                            }
                        } else {
                            encodedVideoFrames.incrementAndGet()
                            bytesInWindow += info.size
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastBitrateSampleMs
                            if (elapsed >= 1000L) {
                                currentBitrateKbps.set(((bytesInWindow * 8L) / elapsed).toInt())
                                bytesInWindow = 0L
                                lastBitrateSampleMs = now
                            }

                            lastRawPtsUs = info.presentationTimeUs
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isKey && recordingActive.get() && !ptsRebaser.isPaused &&
                                recordingFormat == RecordingFormat.SEGMENTED_MP4 &&
                                now - segmentStartMs >= 600_000L
                            ) {
                                rotateRecordingSegmentLocked()
                            }

                            val packetBytes = ByteArray(info.size)
                            buf.get(packetBytes)

                            if (recordingActive.get()) {
                                writeMuxerSample(isVideo = true, isKey = isKey, rawBytes = packetBytes, info = info)
                            }

                            if (replayRingBuffer.enabled) {
                                replayRingBuffer.pushSample(
                                    isVideo = true,
                                    isKey = isKey,
                                    ptsUs = info.presentationTimeUs,
                                    flags = info.flags,
                                    payload = packetBytes
                                )
                            }

                            if (namedSenders.isNotEmpty()) {
                                val avccPayload = FlvH264Helper.annexBFrameToAvcc(packetBytes)
                                val ptsMs = info.presentationTimeUs / 1000L
                                namedSenders.values.forEach { it.enqueueVideo(avccPayload, ptsMs, isKey) }
                            }
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(idx, false) }
                }
            }
        }
    }

    private fun rotateRecordingSegmentLocked() {
        val oldFile: File?
        val oldDur: Long
        synchronized(muxerLock) {
            oldFile = currentRecordFile
            oldDur = maxOf(1L, (System.currentTimeMillis() - segmentStartMs) / 1000L)
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            val nextFile = allocateRecordingFile()
            muxer = MediaMuxer(nextFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerVideoTrack = -1
            muxerAudioTrack = -1
            muxerStarted = false
            currentRecordFile = nextFile
            segmentStartMs = System.currentTimeMillis()
            ptsRebaser.reset()
            lastMuxerVideoPtsUs = -1L
            lastMuxerAudioPtsUs = -1L
            maybeStartMuxerLocked()
            repository.markActiveRecording(nextFile, segmentStartMs)
        }
        if (oldFile != null && oldFile.exists() && oldFile.length() > 1024L) {
            Thread({ finalizeAndPublishFile(oldFile, oldDur) }, "MobileLabs-SegPublish").start()
        }
    }

    private fun startAudioEncoder(audioBitrateKbps: Int) {
        runCatching {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioEncoder = codec
            audioRunning.set(true)
            audioDrainThread = Thread({ drainAudioLoop(codec) }, "MobileLabs-AudioEnc").apply {
                start()
            }
        }
    }

    private fun drainAudioLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (audioRunning.get()) {
            val idx = runCatching { codec.dequeueOutputBuffer(info, 10_000) }.getOrDefault(-1)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        currentAudioFormat = codec.outputFormat
                        maybeStartMuxerLocked()
                    }
                    codec.outputFormat.getByteBuffer("csd-0")?.let { csd ->
                        val bytes = ByteArray(csd.remaining())
                        csd.duplicate().get(bytes)
                        audioConfigBytes = bytes
                        namedSenders.values.forEach { it.updateAudioConfig(bytes) }
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            val copy = ByteArray(info.size)
                            buf.get(copy)
                            audioConfigBytes = copy
                            namedSenders.values.forEach { it.updateAudioConfig(copy) }
                        } else {
                            val aBytes = ByteArray(info.size)
                            buf.get(aBytes)

                            if (recordingActive.get()) {
                                writeMuxerSample(isVideo = false, isKey = false, rawBytes = aBytes, info = info)
                            }
                            if (replayRingBuffer.enabled) {
                                replayRingBuffer.pushSample(
                                    isVideo = false,
                                    isKey = false,
                                    ptsUs = info.presentationTimeUs,
                                    flags = info.flags,
                                    payload = aBytes
                                )
                            }
                            if (namedSenders.isNotEmpty()) {
                                val ptsMs = info.presentationTimeUs / 1000L
                                namedSenders.values.forEach { it.enqueueAudio(aBytes, ptsMs) }
                            }
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(idx, false) }
                }
            }
        }
    }

    private fun maybeStartMuxerLocked() {
        val m = muxer ?: return
        if (muxerStarted) return
        val vFmt = currentVideoFormat ?: return
        val aFmt = currentAudioFormat
        if (aFmt == null && audioEncoder != null) return

        muxerVideoTrack = m.addTrack(vFmt)
        if (aFmt != null) {
            muxerAudioTrack = m.addTrack(aFmt)
        }
        m.start()
        muxerStarted = true
    }

    private fun writeMuxerSample(isVideo: Boolean, isKey: Boolean, rawBytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (!recordingActive.get()) return
        synchronized(muxerLock) {
            val m = muxer ?: return
            if (!muxerStarted) {
                maybeStartMuxerLocked()
                if (!muxerStarted) return
            }
            val track = if (isVideo) muxerVideoTrack else muxerAudioTrack
            if (track < 0) return

            var rebasedPts = ptsRebaser.filterAndRebase(isVideo, isKey, info.presentationTimeUs)
            if (rebasedPts < 0L) return

            if (isVideo) {
                if (rebasedPts <= lastMuxerVideoPtsUs) rebasedPts = lastMuxerVideoPtsUs + 1L
                lastMuxerVideoPtsUs = rebasedPts
            } else {
                if (rebasedPts <= lastMuxerAudioPtsUs) rebasedPts = lastMuxerAudioPtsUs + 1L
                lastMuxerAudioPtsUs = rebasedPts
            }

            val rebasedInfo = MediaCodec.BufferInfo().apply {
                set(0, rawBytes.size, rebasedPts, info.flags)
            }
            runCatching {
                m.writeSampleData(track, ByteBuffer.wrap(rawBytes), rebasedInfo)
                recordBytesWritten.addAndGet(rawBytes.size.toLong())
            }.onFailure { err ->
                onRecordingError("Recording write failed: ${err.message}")
                stopRecording()
            }
        }
    }

    private fun extractAvcConfig(fmt: MediaFormat) {
        val sps = fmt.getByteBuffer("csd-0") ?: return
        val pps = fmt.getByteBuffer("csd-1")
        val spsBytes = ByteArray(sps.remaining())
        sps.duplicate().get(spsBytes)
        val ppsBytes = if (pps != null) {
            ByteArray(pps.remaining()).also { pps.duplicate().get(it) }
        } else {
            ByteArray(0)
        }
        val avcc = if (ppsBytes.isEmpty()) {
            FlvH264Helper.buildAvcDecoderConfigFromAnnexB(spsBytes) ?: spsBytes
        } else {
            FlvH264Helper.buildAvcDecoderConfigRecord(spsBytes, ppsBytes)
        }
        if (avcc.isNotEmpty()) {
            videoConfigBytes = avcc
            namedSenders.values.forEach { it.updateVideoConfig(avcc) }
        }
    }

    private fun publishToSafTree(sourceFile: File, treeUriStr: String): String? {
        return runCatching {
            val resolver = context.contentResolver
            val treeUri = treeUriStr.toUri()
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val docUri = DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                "video/mp4",
                sourceFile.name
            ) ?: return null

            resolver.openOutputStream(docUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, bufferSize = 65536)
                    out.flush()
                }
            } ?: return null

            if (repository.verifyMediaPlayable(sourceFile)) {
                sourceFile.delete()
            }
            docUri.toString()
        }.getOrNull()
    }

    private fun publishToMediaStore(sourceFile: File): String? {
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Mobile Labs")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, bufferSize = 65536)
                    out.flush()
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            if (repository.verifyMediaPlayable(sourceFile)) {
                sourceFile.delete()
            }
            uri.toString()
        }.getOrNull()
    }
}

/**
 * Pure Kotlin H.264 Annex-B <-> FLV AVCC framing utilities.
 */
object FlvH264Helper {

    fun splitAnnexBNalus(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>(4)
        var i = 0
        val n = data.size
        while (i < n) {
            val scLen = startCodeLengthAt(data, i)
            if (scLen > 0) {
                val start = i + scLen
                var end = start
                while (end < n && startCodeLengthAt(data, end) == 0) {
                    end++
                }
                if (end > start) {
                    result += data.copyOfRange(start, end)
                }
                i = end
            } else {
                i++
            }
        }
        return result
    }

    fun buildAvcDecoderConfigRecord(sps: ByteArray, pps: ByteArray): ByteArray {
        val cleanSps = stripStartCode(sps)
        val cleanPps = stripStartCode(pps)
        if (cleanSps.size < 4) return ByteArray(0)
        val out = ByteArrayOutputStream(16 + cleanSps.size + cleanPps.size)
        out.write(0x01)
        out.write(cleanSps[1].toInt() and 0xFF)
        out.write(cleanSps[2].toInt() and 0xFF)
        out.write(cleanSps[3].toInt() and 0xFF)
        out.write(0xFF)
        out.write(0xE1)
        out.write((cleanSps.size ushr 8) and 0xFF)
        out.write(cleanSps.size and 0xFF)
        out.write(cleanSps)
        if (cleanPps.isNotEmpty()) {
            out.write(0x01)
            out.write((cleanPps.size ushr 8) and 0xFF)
            out.write(cleanPps.size and 0xFF)
            out.write(cleanPps)
        } else {
            out.write(0x00)
        }
        return out.toByteArray()
    }

    fun buildAvcDecoderConfigFromAnnexB(csd: ByteArray): ByteArray? {
        val nalus = splitAnnexBNalus(csd)
        val sps = nalus.firstOrNull { (it[0].toInt() and 0x1F) == 7 } ?: return null
        val pps = nalus.firstOrNull { (it[0].toInt() and 0x1F) == 8 } ?: ByteArray(0)
        val rec = buildAvcDecoderConfigRecord(sps, pps)
        return rec.takeIf { it.isNotEmpty() }
    }

    fun annexBFrameToAvcc(payload: ByteArray): ByteArray {
        val nalus = splitAnnexBNalus(payload)
        if (nalus.isEmpty()) return payload
        val out = ByteArrayOutputStream(payload.size + 16)
        for (nalu in nalus) {
            val nalType = nalu[0].toInt() and 0x1F
            if (nalType == 9 || nalType == 7 || nalType == 8) continue
            val len = nalu.size
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(nalu)
        }
        return if (out.size() > 0) out.toByteArray() else payload
    }

    private fun stripStartCode(bytes: ByteArray): ByteArray {
        val sc = startCodeLengthAt(bytes, 0)
        return if (sc > 0 && sc < bytes.size) bytes.copyOfRange(sc, bytes.size) else bytes
    }

    private fun startCodeLengthAt(data: ByteArray, index: Int): Int {
        if (index + 3 <= data.size &&
            data[index].toInt() == 0 &&
            data[index + 1].toInt() == 0
        ) {
            if (data[index + 2].toInt() == 1) return 3
            if (index + 4 <= data.size &&
                data[index + 2].toInt() == 0 &&
                data[index + 3].toInt() == 1
            ) return 4
        }
        return 0
    }
}
