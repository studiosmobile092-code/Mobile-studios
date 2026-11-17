package com.example.mobilelabs.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.mobilelabs.model.PublishedRecording
import com.example.mobilelabs.persistence.SceneCollectionRepository
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.max

/**
 * Memory-bounded encoded GOP ring buffer for OBS-style Instant Replay Buffer.
 * Captures packets from the shared H.264/AAC MediaCodec output with zero extra encode cost,
 * automatically evicting oldest complete GOPs beyond [targetDurationSec] or [maxBytes].
 *
 * Uses video timestamps specifically for window span calculation so hardware AAC codecs
 * that emit 0-based sample-count PTS do not desync window pruning from wall-clocked video.
 */
class ReplayGopRingBuffer(
    private val maxBytes: Long = 32L * 1024L * 1024L
) {
    data class EncodedSample(
        val isVideo: Boolean,
        val isKey: Boolean,
        val ptsUs: Long,
        val flags: Int,
        val data: ByteArray
    )

    data class SnapshotStats(
        val bufferedSeconds: Float,
        val bufferedKb: Long
    )

    private val lock = Any()
    private val packets = ArrayDeque<EncodedSample>(512)
    private var totalBytes = 0L

    @Volatile var enabled: Boolean = false
    @Volatile var targetDurationSec: Int = 30

    fun configure(enabled: Boolean, durationSec: Int) {
        synchronized(lock) {
            this.enabled = enabled
            this.targetDurationSec = durationSec.coerceIn(10, 120)
            if (!enabled) {
                packets.clear()
                totalBytes = 0L
            } else {
                pruneLocked()
            }
        }
    }

    fun pushSample(
        isVideo: Boolean,
        isKey: Boolean,
        ptsUs: Long,
        flags: Int,
        payload: ByteArray
    ) {
        if (!enabled || payload.isEmpty()) return
        synchronized(lock) {
            packets.addLast(EncodedSample(isVideo, isKey, ptsUs, flags, payload))
            totalBytes += payload.size
            pruneLocked()
        }
    }

    fun stats(): SnapshotStats = synchronized(lock) {
        var firstVideoUs = -1L
        var lastVideoUs = -1L
        for (p in packets) {
            if (p.isVideo) {
                if (firstVideoUs < 0L) firstVideoUs = p.ptsUs
                lastVideoUs = p.ptsUs
            }
        }
        val sec = if (firstVideoUs >= 0L && lastVideoUs > firstVideoUs) {
            (lastVideoUs - firstVideoUs) / 1_000_000f
        } else {
            0f
        }
        SnapshotStats(bufferedSeconds = sec, bufferedKb = totalBytes / 1024L)
    }

    /**
     * Returns an IDR-aligned list of samples trimmed cleanly to at least the target window.
     */
    fun extractAlignedClipSamples(): List<EncodedSample> = synchronized(lock) {
        trimToKeyframeAlignedList(packets.toList())
    }

    fun clear() = synchronized(lock) {
        packets.clear()
        totalBytes = 0L
    }

    private fun latestVideoPtsLocked(): Long {
        val it = packets.descendingIterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.isVideo) return s.ptsUs
        }
        return -1L
    }

    private fun firstVideoPtsLocked(): Long {
        for (s in packets) {
            if (s.isVideo) return s.ptsUs
        }
        return -1L
    }

    private fun pruneLocked() {
        val maxWindowUs = (targetDurationSec.toLong() + 4L) * 1_000_000L
        while (packets.size > 2) {
            val firstV = firstVideoPtsLocked()
            val lastV = latestVideoPtsLocked()
            val spanUs = if (firstV >= 0L && lastV > firstV) lastV - firstV else 0L
            if (spanUs <= maxWindowUs && totalBytes <= maxBytes) break

            // Pop oldest packet, and keep popping until we land on the next video IDR keyframe
            val first = packets.removeFirst()
            totalBytes -= first.data.size
            while (packets.isNotEmpty()) {
                val head = packets.first()
                if (head.isVideo && head.isKey &&
                    (lastV < 0L || (lastV - head.ptsUs) <= maxWindowUs) &&
                    totalBytes <= maxBytes
                ) {
                    break
                }
                val removed = packets.removeFirst()
                totalBytes -= removed.data.size
            }
        }
        totalBytes = max(0L, totalBytes)
    }

    companion object {
        fun trimToKeyframeAlignedList(raw: List<EncodedSample>): List<EncodedSample> {
            val firstKeyIdx = raw.indexOfFirst { it.isVideo && it.isKey }
            if (firstKeyIdx < 0) return emptyList()
            // Keep all packets queued from the IDR frame onward (FIFO order already aligns audio & video
            // without assuming audio/video share the same epoch).
            return raw.subList(firstKeyIdx, raw.size).toList()
        }
    }
}

object ReplayClipMuxer {
    fun writeReplayMp4(
        repository: SceneCollectionRepository,
        samples: List<ReplayGopRingBuffer.EncodedSample>,
        videoFormat: MediaFormat?,
        audioFormat: MediaFormat?,
        publishFn: (File) -> String?
    ): PublishedRecording? {
        if (samples.isEmpty() || videoFormat == null) return null
        val outFile = MediaFileAllocator.allocateTimestampedFile(repository.recordingsDir, "OBSmobile_Replay", "mp4")

        val firstVideoUs = samples.firstOrNull { it.isVideo }?.ptsUs ?: return null
        val lastVideoUs = samples.lastOrNull { it.isVideo }?.ptsUs ?: firstVideoUs
        val firstAudioUs = samples.firstOrNull { !it.isVideo }?.ptsUs ?: -1L
        val durationSec = max(1L, (lastVideoUs - firstVideoUs) / 1_000_000L)

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return try {
            val vTrack = muxer.addTrack(videoFormat)
            val aTrack = if (audioFormat != null) muxer.addTrack(audioFormat) else -1
            muxer.start()

            val info = MediaCodec.BufferInfo()
            var lastVUs = -1L
            var lastAUs = -1L
            for (s in samples) {
                val track = if (s.isVideo) vTrack else aTrack
                if (track < 0) continue
                val baseUs = if (s.isVideo) firstVideoUs else firstAudioUs
                if (baseUs < 0L) continue
                var pts = (s.ptsUs - baseUs).coerceAtLeast(0L)
                if (s.isVideo) {
                    if (pts <= lastVUs) pts = lastVUs + 1L
                    lastVUs = pts
                } else {
                    if (pts <= lastAUs) pts = lastAUs + 1L
                    lastAUs = pts
                }
                info.set(0, s.data.size, pts, s.flags)
                muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
            muxer.release()

            val sizeBytes = outFile.length()
            if (sizeBytes <= 1024L) {
                outFile.delete()
                return null
            }
            val publishedUri = publishFn(outFile) ?: outFile.absolutePath
            PublishedRecording(
                fileName = outFile.name,
                mediaStoreUri = publishedUri,
                durationSec = durationSec,
                bytesSize = sizeBytes,
                createdAtMs = System.currentTimeMillis()
            )
        } catch (_: Throwable) {
            runCatching { muxer.release() }
            runCatching { outFile.delete() }
            null
        }
    }
}
