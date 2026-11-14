package com.example.mobilelabs.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import com.example.mobilelabs.model.EncoderFallbackLevel
import com.example.mobilelabs.model.PublishedRecording
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.persistence.SceneCollectionRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocketFactory

/**
 * Shared Hardware MediaCodec H.264 (Surface Input) + AAC Encoder + Independent Outputs (§6.5, §8):
 * - Single shared encode feeds both local MP4 recording and RTMP/RTMPS streaming (D5).
 * - Stream and recording are completely independent: failure of one never halts the other.
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

    private val videoRunning = AtomicBoolean(false)
    private var videoEncoder: MediaCodec? = null
    private var videoInputSurface: Surface? = null
    private var videoDrainThread: Thread? = null

    private val audioRunning = AtomicBoolean(false)
    private var audioEncoder: MediaCodec? = null
    private var audioDrainThread: Thread? = null

    // Shared encoded formats and packets
    @Volatile private var currentVideoFormat: MediaFormat? = null
    @Volatile private var currentAudioFormat: MediaFormat? = null
    @Volatile private var videoConfigBytes: ByteArray? = null
    @Volatile private var audioConfigBytes: ByteArray? = null

    // Independent Recording state
    private val recordingActive = AtomicBoolean(false)
    private var muxer: MediaMuxer? = null
    private var muxerVideoTrack = -1
    private var muxerAudioTrack = -1
    private var muxerStarted = false
    private var currentRecordFile: File? = null
    private var recordStartMs = 0L
    private val recordBytesWritten = AtomicLong(0L)
    private val muxerLock = Any()

    // Independent RTMP/RTMPS state (Primary + Phase 3 Dual Destination sharing the single HW encode)
    private val streamActive = AtomicBoolean(false)
    private var rtmpSender: RtmpFlvSender? = null

    private val secondaryStreamActive = AtomicBoolean(false)
    private var secondaryRtmpSender: RtmpFlvSender? = null
    private val secondaryNetworkDroppedFrames = AtomicLong(0)

    // Telemetry counters
    @Volatile var encoderMode = EncoderFallbackLevel.OPTIMAL_HW_SURFACE
        private set
    private val encodedVideoFrames = AtomicLong(0)
    private val encoderDroppedFrames = AtomicLong(0)
    private val networkDroppedFrames = AtomicLong(0)
    private val currentBitrateKbps = AtomicInteger(0)
    private var lastBitrateSampleMs = 0L
    private var bytesInWindow = 0L

    fun startEncoders(videoConfig: VideoConfig) {
        if (videoRunning.get()) return
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

    fun startRecording(): File? {
        if (recordingActive.get()) return currentRecordFile
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        var target = File(repository.recordingsDir, "MobileLabs_$stamp.mp4")
        var suffix = 1
        while (target.exists()) {
            target = File(repository.recordingsDir, "MobileLabs_${stamp}_$suffix.mp4")
            suffix++
        }

        return try {
            synchronized(muxerLock) {
                muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxerVideoTrack = -1
                muxerAudioTrack = -1
                muxerStarted = false
                currentRecordFile = target
                recordStartMs = System.currentTimeMillis()
                recordBytesWritten.set(0L)
                maybeStartMuxerLocked()
            }
            repository.markActiveRecording(target, recordStartMs)
            recordingActive.set(true)
            target
        } catch (t: Throwable) {
            onRecordingError("Muxer start failed: ${t.message}")
            null
        }
    }

    fun stopRecording() {
        if (!recordingActive.getAndSet(false)) return
        val file: File?
        val durationSec: Long
        synchronized(muxerLock) {
            file = currentRecordFile
            durationSec = maxOf(1L, (System.currentTimeMillis() - recordStartMs) / 1000L)
            runCatching {
                if (muxerStarted) muxer?.stop()
            }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
            muxerVideoTrack = -1
            muxerAudioTrack = -1
        }
        repository.clearActiveRecordingMarker()
        if (file != null && file.exists() && file.length() > 1024L) {
            val publishedUri = publishToMediaStore(file)
            onRecordingFinalized(
                PublishedRecording(
                    fileName = file.name,
                    mediaStoreUri = publishedUri ?: file.absolutePath,
                    durationSec = durationSec,
                    bytesSize = file.length(),
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun startStreaming(rtmpBaseUrl: String, streamKey: String, videoConfig: VideoConfig) {
        if (streamActive.getAndSet(true)) return
        val sender = RtmpFlvSender(
            baseUrl = rtmpBaseUrl,
            streamKey = streamKey,
            width = videoConfig.outputWidth,
            height = videoConfig.outputHeight,
            fps = videoConfig.fps,
            videoBitrateKbps = videoConfig.videoBitrateKbps,
            audioBitrateKbps = videoConfig.audioBitrateKbps,
            onConnected = onStreamConnected,
            onFailure = { reason ->
                streamActive.set(false)
                onStreamError(reason)
            },
            onNetworkDrop = { networkDroppedFrames.incrementAndGet() }
        )
        rtmpSender = sender
        sender.start(videoConfigBytes, audioConfigBytes)
    }

    fun stopStreaming() {
        if (!streamActive.getAndSet(false)) return
        rtmpSender?.stop()
        rtmpSender = null
    }

    fun startSecondaryStreaming(
        rtmpBaseUrl: String,
        streamKey: String,
        videoConfig: VideoConfig,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (secondaryStreamActive.getAndSet(true)) return
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
                secondaryStreamActive.set(false)
                onError(reason)
            },
            onNetworkDrop = { secondaryNetworkDroppedFrames.incrementAndGet() }
        )
        secondaryRtmpSender = sender
        sender.start(videoConfigBytes, audioConfigBytes)
    }

    fun stopSecondaryStreaming() {
        if (!secondaryStreamActive.getAndSet(false)) return
        secondaryRtmpSender?.stop()
        secondaryRtmpSender = null
    }

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
        stopStreaming()
        stopSecondaryStreaming()
        stopRecording()

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

    fun getEncoderQueueDepth(): Int = rtmpSender?.queueDepth() ?: 0
    fun getRtmpBufferedBytes(): Long = rtmpSender?.bufferedBytes() ?: 0L
    fun getSecondaryBufferedBytes(): Long = secondaryRtmpSender?.bufferedBytes() ?: 0L
    fun getSecondaryDroppedFrames(): Long = secondaryNetworkDroppedFrames.get()
    fun getEncoderDroppedFrames(): Long = encoderDroppedFrames.get()
    fun getNetworkDroppedFrames(): Long = networkDroppedFrames.get()
    fun getActualBitrateKbps(): Int = currentBitrateKbps.get()
    fun getRecordingBytesWritten(): Long = recordBytesWritten.get()

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
                            videoConfigBytes = copy
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

                            val dup = buf.duplicate()
                            writeMuxerSample(isVideo = true, data = dup, info = info)

                            if (streamActive.get() || secondaryStreamActive.get()) {
                                val packetBytes = ByteArray(info.size)
                                buf.position(info.offset)
                                buf.get(packetBytes)
                                val ptsMs = info.presentationTimeUs / 1000L
                                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                if (streamActive.get()) {
                                    rtmpSender?.enqueueVideo(packetBytes, ptsMs, isKey)
                                }
                                if (secondaryStreamActive.get()) {
                                    secondaryRtmpSender?.enqueueVideo(packetBytes, ptsMs, isKey)
                                }
                            }
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(idx, false) }
                }
            }
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
                        } else {
                            writeMuxerSample(isVideo = false, data = buf.duplicate(), info = info)
                            if (streamActive.get() || secondaryStreamActive.get()) {
                                val aBytes = ByteArray(info.size)
                                buf.position(info.offset)
                                buf.get(aBytes)
                                val ptsMs = info.presentationTimeUs / 1000L
                                if (streamActive.get()) {
                                    rtmpSender?.enqueueAudio(aBytes, ptsMs)
                                }
                                if (secondaryStreamActive.get()) {
                                    secondaryRtmpSender?.enqueueAudio(aBytes, ptsMs)
                                }
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
        muxerVideoTrack = m.addTrack(vFmt)
        currentAudioFormat?.let { aFmt ->
            muxerAudioTrack = m.addTrack(aFmt)
        }
        m.start()
        muxerStarted = true
    }

    private fun writeMuxerSample(isVideo: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!recordingActive.get()) return
        synchronized(muxerLock) {
            val m = muxer ?: return
            if (!muxerStarted) {
                maybeStartMuxerLocked()
                if (!muxerStarted) return
            }
            val track = if (isVideo) muxerVideoTrack else muxerAudioTrack
            if (track < 0) return
            runCatching {
                m.writeSampleData(track, data, info)
                recordBytesWritten.addAndGet(info.size.toLong())
            }.onFailure { err ->
                onRecordingError("Recording write failed: ${err.message}")
                stopRecording()
            }
        }
    }

    private fun extractAvcConfig(fmt: MediaFormat) {
        val sps = fmt.getByteBuffer("csd-0") ?: return
        val pps = fmt.getByteBuffer("csd-1")
        val out = ByteArrayOutputStream()
        val spsBytes = ByteArray(sps.remaining())
        sps.duplicate().get(spsBytes)
        out.write(spsBytes)
        if (pps != null) {
            val ppsBytes = ByteArray(pps.remaining())
            pps.duplicate().get(ppsBytes)
            out.write(ppsBytes)
        }
        videoConfigBytes = out.toByteArray()
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
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            sourceFile.delete()
            uri.toString()
        }.getOrNull()
    }
}

/**
 * Bounded-queue RTMP / RTMPS sender implementing RTMP C0/C1/C2 handshake,
 * connect/createStream/publish, and FLV-wrapped H.264/AAC chunks.
 * Never buffers unboundedly (Iron Rule R12): drops oldest video frames on slow uplink.
 */
class RtmpFlvSender(
    private val baseUrl: String,
    private val streamKey: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val videoBitrateKbps: Int,
    private val audioBitrateKbps: Int,
    private val onConnected: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val onNetworkDrop: () -> Unit
) {
    private data class Packet(
        val isVideo: Boolean,
        val isConfig: Boolean,
        val isKey: Boolean,
        val timestampMs: Long,
        val payload: ByteArray
    )

    private val queue = ArrayBlockingQueue<Packet>(120)
    private val running = AtomicBoolean(false)
    private val queuedBytes = AtomicLong(0L)
    private var worker: Thread? = null
    private var socket: Socket? = null

    fun start(videoSeqHeader: ByteArray?, audioSeqHeader: ByteArray?) {
        if (running.getAndSet(true)) return
        worker = Thread({
            runSession(videoSeqHeader, audioSeqHeader)
        }, "MobileLabs-RTMP").apply { start() }
    }

    fun enqueueVideo(bytes: ByteArray, timestampMs: Long, isKey: Boolean) {
        if (!running.get()) return
        val pkt = Packet(isVideo = true, isConfig = false, isKey = isKey, timestampMs = timestampMs, payload = bytes)
        enqueueBounded(pkt)
    }

    fun enqueueAudio(bytes: ByteArray, timestampMs: Long) {
        if (!running.get()) return
        val pkt = Packet(isVideo = false, isConfig = false, isKey = false, timestampMs = timestampMs, payload = bytes)
        enqueueBounded(pkt)
    }

    fun queueDepth(): Int = queue.size
    fun bufferedBytes(): Long = queuedBytes.get()

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        queue.clear()
        queuedBytes.set(0L)
        runCatching { worker?.join(500) }
        worker = null
    }

    private fun enqueueBounded(pkt: Packet) {
        while (queue.remainingCapacity() == 0) {
            val dropped = queue.poll() ?: break
            queuedBytes.addAndGet(-dropped.payload.size.toLong())
            onNetworkDrop()
        }
        if (queue.offer(pkt)) {
            queuedBytes.addAndGet(pkt.payload.size.toLong())
        }
    }

    private fun runSession(videoSeqHeader: ByteArray?, audioSeqHeader: ByteArray?) {
        try {
            val parsed = parseRtmpUrl(baseUrl)
            val sock = if (parsed.isTls) {
                SSLSocketFactory.getDefault().createSocket()
            } else {
                Socket()
            }
            sock.connect(InetSocketAddress(parsed.host, parsed.port), 6000)
            sock.soTimeout = 8000
            sock.tcpNoDelay = true
            socket = sock

            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

            // RTMP Simple Handshake (C0 + C1 -> S0 + S1 + S2 -> C2)
            output.writeByte(0x03)
            val c1 = ByteArray(1536)
            output.write(c1)
            output.flush()

            val s0 = input.readByte().toInt()
            if (s0 != 0x03) error("Unsupported RTMP server version $s0")
            val s1 = ByteArray(1536)
            input.readFully(s1)
            val s2 = ByteArray(1536)
            input.readFully(s2)
            output.write(s1)
            output.flush()

            sendConnectChunk(output, parsed.app, baseUrl)
            sendCreateStreamChunk(output)
            sendPublishChunk(output, streamKey)
            output.flush()

            // Confirmed connected! Notify SessionController so LIVE badge activates honestly (R11)
            onConnected()

            sendMetadataChunk(output)
            if (videoSeqHeader != null) {
                writeFlvVideoTag(output, videoSeqHeader, 0L, isKey = true, isSeqHeader = true)
            }
            if (audioSeqHeader != null) {
                writeFlvAudioTag(output, audioSeqHeader, 0L, isSeqHeader = true)
            }
            output.flush()

            val baseTs = System.currentTimeMillis()
            while (running.get()) {
                val pkt = queue.poll()
                if (pkt == null) {
                    Thread.sleep(5)
                    continue
                }
                queuedBytes.addAndGet(-pkt.payload.size.toLong())
                val relTs = (pkt.timestampMs - baseTs).coerceAtLeast(0L)
                if (pkt.isVideo) {
                    writeFlvVideoTag(output, pkt.payload, relTs, isKey = pkt.isKey, isSeqHeader = false)
                } else {
                    writeFlvAudioTag(output, pkt.payload, relTs, isSeqHeader = false)
                }
                output.flush()
            }
        } catch (t: Throwable) {
            if (running.get()) {
                onFailure("RTMP disconnected: ${t.message ?: "connection error"}")
            }
        } finally {
            runCatching { socket?.close() }
            socket = null
        }
    }

    private data class ParsedRtmp(val isTls: Boolean, val host: String, val port: Int, val app: String)

    private fun parseRtmpUrl(url: String): ParsedRtmp {
        val trimmed = url.trim()
        val isTls = trimmed.startsWith("rtmps://", ignoreCase = true)
        val withoutScheme = trimmed
            .removePrefix("rtmps://")
            .removePrefix("rtmp://")
        val slashIdx = withoutScheme.indexOf('/')
        val hostPort = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
        val app = if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1).ifBlank { "live" } else "live"
        val colonIdx = hostPort.indexOf(':')
        val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
        val port = if (colonIdx >= 0) {
            hostPort.substring(colonIdx + 1).toIntOrNull() ?: if (isTls) 443 else 1935
        } else {
            if (isTls) 443 else 1935
        }
        return ParsedRtmp(isTls, host, port, app)
    }

    private fun sendConnectChunk(out: DataOutputStream, app: String, tcUrl: String) {
        val amf = ByteArrayOutputStream()
        writeAmfString(amf, "connect")
        writeAmfNumber(amf, 1.0)
        amf.write(0x03) // AMF0 Object
        writeAmfProp(amf, "app", app)
        writeAmfProp(amf, "type", "nonprivate")
        writeAmfProp(amf, "tcUrl", tcUrl)
        writeAmfObjectEnd(amf)
        writeRtmpChunk(out, csid = 3, msgTypeId = 20, streamId = 0, ts = 0, payload = amf.toByteArray())
    }

    private fun sendCreateStreamChunk(out: DataOutputStream) {
        val amf = ByteArrayOutputStream()
        writeAmfString(amf, "createStream")
        writeAmfNumber(amf, 2.0)
        amf.write(0x05) // AMF0 Null
        writeRtmpChunk(out, csid = 3, msgTypeId = 20, streamId = 0, ts = 0, payload = amf.toByteArray())
    }

    private fun sendPublishChunk(out: DataOutputStream, key: String) {
        val amf = ByteArrayOutputStream()
        writeAmfString(amf, "publish")
        writeAmfNumber(amf, 3.0)
        amf.write(0x05) // Null
        writeAmfString(amf, key)
        writeAmfString(amf, "live")
        writeRtmpChunk(out, csid = 8, msgTypeId = 20, streamId = 1, ts = 0, payload = amf.toByteArray())
    }

    private fun sendMetadataChunk(out: DataOutputStream) {
        val amf = ByteArrayOutputStream()
        writeAmfString(amf, "@setDataFrame")
        writeAmfString(amf, "onMetaData")
        amf.write(0x08) // ECMA Array
        val count = 6
        amf.write((count ushr 24) and 0xFF)
        amf.write((count ushr 16) and 0xFF)
        amf.write((count ushr 8) and 0xFF)
        amf.write(count and 0xFF)
        writeAmfNumProp(amf, "width", width.toDouble())
        writeAmfNumProp(amf, "height", height.toDouble())
        writeAmfNumProp(amf, "framerate", fps.toDouble())
        writeAmfNumProp(amf, "videodatarate", videoBitrateKbps.toDouble())
        writeAmfNumProp(amf, "audiodatarate", audioBitrateKbps.toDouble())
        writeAmfProp(amf, "encoder", "Mobile Labs (libobs/MediaCodec)")
        writeAmfObjectEnd(amf)
        writeRtmpChunk(out, csid = 4, msgTypeId = 18, streamId = 1, ts = 0, payload = amf.toByteArray())
    }

    private fun writeFlvVideoTag(
        out: DataOutputStream,
        payload: ByteArray,
        tsMs: Long,
        isKey: Boolean,
        isSeqHeader: Boolean
    ) {
        val body = ByteArray(5 + payload.size)
        body[0] = if (isKey) 0x17 else 0x27
        body[1] = if (isSeqHeader) 0x00 else 0x01
        body[2] = 0x00
        body[3] = 0x00
        body[4] = 0x00
        System.arraycopy(payload, 0, body, 5, payload.size)
        writeRtmpChunk(out, csid = 6, msgTypeId = 9, streamId = 1, ts = tsMs, payload = body)
    }

    private fun writeFlvAudioTag(out: DataOutputStream, payload: ByteArray, tsMs: Long, isSeqHeader: Boolean) {
        val body = ByteArray(2 + payload.size)
        body[0] = 0xAF.toByte() // AAC 44/48kHz 16-bit stereo
        body[1] = if (isSeqHeader) 0x00 else 0x01
        System.arraycopy(payload, 0, body, 2, payload.size)
        writeRtmpChunk(out, csid = 4, msgTypeId = 8, streamId = 1, ts = tsMs, payload = body)
    }

    private fun writeRtmpChunk(
        out: DataOutputStream,
        csid: Int,
        msgTypeId: Int,
        streamId: Int,
        ts: Long,
        payload: ByteArray
    ) {
        val chunkSize = 128
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val take = minOf(chunkSize, payload.size - offset)
            if (first) {
                out.writeByte(csid and 0x3F) // fmt 0
                val t = ts.coerceAtMost(0xFFFFFF)
                out.writeByte(((t ushr 16) and 0xFF).toInt())
                out.writeByte(((t ushr 8) and 0xFF).toInt())
                out.writeByte((t and 0xFF).toInt())
                out.writeByte((payload.size ushr 16) and 0xFF)
                out.writeByte((payload.size ushr 8) and 0xFF)
                out.writeByte(payload.size and 0xFF)
                out.writeByte(msgTypeId)
                // Message stream id little-endian
                out.writeByte(streamId and 0xFF)
                out.writeByte((streamId ushr 8) and 0xFF)
                out.writeByte((streamId ushr 16) and 0xFF)
                out.writeByte((streamId ushr 24) and 0xFF)
                first = false
            } else {
                out.writeByte(0xC0 or (csid and 0x3F)) // fmt 3 continuation
            }
            out.write(payload, offset, take)
            offset += take
        }
    }

    private fun writeAmfString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(0x02)
        out.write((b.size ushr 8) and 0xFF)
        out.write(b.size and 0xFF)
        out.write(b)
    }

    private fun writeAmfProp(out: ByteArrayOutputStream, key: String, value: String) {
        val kb = key.toByteArray(Charsets.UTF_8)
        out.write((kb.size ushr 8) and 0xFF)
        out.write(kb.size and 0xFF)
        out.write(kb)
        writeAmfString(out, value)
    }

    private fun writeAmfNumProp(out: ByteArrayOutputStream, key: String, value: Double) {
        val kb = key.toByteArray(Charsets.UTF_8)
        out.write((kb.size ushr 8) and 0xFF)
        out.write(kb.size and 0xFF)
        out.write(kb)
        writeAmfNumber(out, value)
    }

    private fun writeAmfNumber(out: ByteArrayOutputStream, value: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (shift in 56 downTo 0 step 8) {
            out.write(((bits ushr shift) and 0xFF).toInt())
        }
    }

    private fun writeAmfObjectEnd(out: ByteArrayOutputStream) {
        out.write(0x00)
        out.write(0x00)
        out.write(0x09)
    }
}
