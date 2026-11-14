package com.example.mobilelabs.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated local video Media Source decoder (M2.1).
 * Uses MediaExtractor + MediaCodec decoding directly into the GL compositor OES Surface
 * (zero CPU pixel copies). Releases codec immediately when hidden or stopped.
 */
class MediaSourceDecoder(
    private val filePath: String,
    private val outputSurface: Surface,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile var loopPlayback: Boolean = true
    @Volatile var isPlaying: Boolean = true
    private var workerThread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        val file = File(filePath)
        if (!file.exists() || !file.canRead() || file.length() <= 0L) {
            running.set(false)
            onError("Missing media file: ${file.name}")
            return
        }

        workerThread = Thread({
            decodeLoop(filePath)
        }, "MobileLabs-MediaSrc").apply { start() }
    }

    fun stop() {
        running.set(false)
        runCatching { workerThread?.join(450) }
        workerThread = null
    }

    private fun decodeLoop(path: String) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(path) }
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrack < 0 || videoFormat == null) {
                onError("No video track found in media source")
                return
            }

            extractor.selectTrack(videoTrack)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(videoFormat, outputSurface, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var playbackStartMs = SystemClock.elapsedRealtime()
            var firstPtsUs = -1L
            var inputEos = false

            while (running.get()) {
                if (!isPlaying) {
                    SystemClock.sleep(25)
                    playbackStartMs += 25
                    continue
                }

                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (info.size > 0) {
                        if (firstPtsUs < 0L) firstPtsUs = info.presentationTimeUs
                        val targetMs = (info.presentationTimeUs - firstPtsUs) / 1000L
                        val elapsedMs = SystemClock.elapsedRealtime() - playbackStartMs
                        val waitMs = targetMs - elapsedMs
                        if (waitMs in 1..100) {
                            SystemClock.sleep(waitMs)
                        }
                        codec.releaseOutputBuffer(outIdx, true)
                    } else {
                        codec.releaseOutputBuffer(outIdx, false)
                    }

                    if (isEos) {
                        if (loopPlayback && running.get()) {
                            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                            inputEos = false
                            firstPtsUs = -1L
                            playbackStartMs = SystemClock.elapsedRealtime()
                        } else {
                            isPlaying = false
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                onError("Media decode failed: ${t.message ?: "unsupported codec"}")
            }
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor?.release() }
            running.set(false)
        }
    }
}
