package com.example.mobilelabs.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Hardware-accelerated local video + audio Media Source decoder.
 * Fixes:
 * - Supports both local file paths and SAF content:// URIs
 * - Decodes embedded audio track to 48 kHz stereo PCM when unmuted and routes to AudioCaptureMixer
 * - Never blocks main thread on stop()
 */
class MediaSourceDecoder(
    private val context: Context?,
    private val filePathOrUri: String,
    private val outputSurface: Surface,
    private val onAudioPcm: ((ShortArray, Int) -> Unit)? = null,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile var loopPlayback: Boolean = true
    @Volatile var isPlaying: Boolean = true
    @Volatile var mediaMuted: Boolean = false

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        val isContentUri = filePathOrUri.startsWith("content://")
        if (!isContentUri) {
            val file = File(filePathOrUri)
            if (!file.exists() || !file.canRead() || file.length() <= 0L) {
                running.set(false)
                onError("Missing media file: ${file.name}")
                return
            }
        } else if (context == null) {
            running.set(false)
            onError("Cannot resolve content URI without context")
            return
        }

        videoThread = Thread({
            decodeVideoLoop(filePathOrUri, isContentUri)
        }, "MobileLabs-MediaVid").apply { start() }

        if (onAudioPcm != null) {
            audioThread = Thread({
                decodeAudioLoop(filePathOrUri, isContentUri)
            }, "MobileLabs-MediaAud").apply { start() }
        }
    }

    fun stop() {
        running.set(false)
        videoThread?.interrupt()
        audioThread?.interrupt()
        videoThread = null
        audioThread = null
    }

    private fun openExtractor(pathOrUri: String, isContentUri: Boolean): MediaExtractor {
        return MediaExtractor().apply {
            if (isContentUri && context != null) {
                setDataSource(context, Uri.parse(pathOrUri), null)
            } else {
                setDataSource(pathOrUri)
            }
        }
    }

    private fun decodeVideoLoop(pathOrUri: String, isContentUri: Boolean) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = openExtractor(pathOrUri, isContentUri)
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
                    if (info.size > 0 && running.get()) {
                        if (firstPtsUs < 0L) firstPtsUs = info.presentationTimeUs
                        val targetMs = (info.presentationTimeUs - firstPtsUs) / 1000L
                        val elapsedMs = SystemClock.elapsedRealtime() - playbackStartMs
                        val waitMs = targetMs - elapsedMs
                        if (waitMs in 1..100) {
                            SystemClock.sleep(waitMs)
                        }
                        if (running.get()) {
                            codec.releaseOutputBuffer(outIdx, true)
                        } else {
                            codec.releaseOutputBuffer(outIdx, false)
                        }
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
            if (running.get() && t !is InterruptedException) {
                onError("Media decode failed: ${t.message ?: "unsupported codec"}")
            }
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor?.release() }
            running.set(false)
        }
    }

    private fun decodeAudioLoop(pathOrUri: String, isContentUri: Boolean) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = openExtractor(pathOrUri, isContentUri)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrack < 0 || audioFormat == null) return

            extractor.selectTrack(audioTrack)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var srcRate = audioFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48000)
            var srcChannels = audioFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
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
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    srcRate = of.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                    srcChannels = of.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, srcChannels)
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (outBuf != null && info.size > 0 && !mediaMuted) {
                        if (firstPtsUs < 0L) firstPtsUs = info.presentationTimeUs
                        val targetMs = (info.presentationTimeUs - firstPtsUs) / 1000L
                        val elapsedMs = SystemClock.elapsedRealtime() - playbackStartMs
                        val waitMs = targetMs - elapsedMs
                        if (waitMs in 1..100) {
                            SystemClock.sleep(waitMs)
                        }
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.order(ByteOrder.nativeOrder())
                        val shortView = outBuf.asShortBuffer()
                        val rawCount = shortView.remaining()
                        val rawShorts = ShortArray(rawCount)
                        shortView.get(rawShorts)
                        val resampled = resampleTo48kStereo(rawShorts, rawCount, srcRate, srcChannels)
                        onAudioPcm?.invoke(resampled, resampled.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)

                    if (isEos) {
                        if (loopPlayback && running.get()) {
                            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                            inputEos = false
                            firstPtsUs = -1L
                            playbackStartMs = SystemClock.elapsedRealtime()
                        } else {
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Video loop reports primary errors; audio decode fails gracefully if track has unsupported codec
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int {
        return if (containsKey(key)) getInteger(key) else fallback
    }

    companion object {
        fun resampleTo48kStereo(
            input: ShortArray,
            count: Int,
            srcRate: Int,
            srcChannels: Int
        ): ShortArray {
            if (count <= 0) return ShortArray(0)
            val ch = srcChannels.coerceIn(1, 2)
            val inFrames = count / ch
            if (inFrames <= 0) return ShortArray(0)
            if (srcRate == 48000 && ch == 2) {
                return if (count == input.size) input else input.copyOf(count)
            }
            val ratio = 48000.0 / srcRate.coerceIn(8000, 192000).toDouble()
            val outFrames = (inFrames * ratio).toInt().coerceAtLeast(1)
            val out = ShortArray(outFrames * 2)
            for (i in 0 until outFrames) {
                val srcPos = min(inFrames - 1, (i / ratio).toInt())
                val baseIdx = srcPos * ch
                val l = input[baseIdx]
                val r = if (ch == 2) input[baseIdx + 1] else l
                out[i * 2] = l
                out[i * 2 + 1] = r
            }
            return out
        }
    }
}
