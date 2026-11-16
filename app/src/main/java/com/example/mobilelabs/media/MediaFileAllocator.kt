package com.example.mobilelabs.media

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared thread-safe timestamped file/name allocator used across MediaRecorder/MediaMuxer,
 * Instant Replay clip exports, and PNG canvas screenshots.
 */
object MediaFileAllocator {
    private val stampFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }

    fun currentTimestamp(): String {
        val formatter = stampFormatter.get() ?: SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return formatter.format(Date())
    }

    fun allocateTimestampedFile(directory: File, prefix: String, extension: String): File {
        directory.mkdirs()
        val cleanExt = extension.trimStart('.')
        val stamp = currentTimestamp()
        var candidate = File(directory, "${prefix}_$stamp.$cleanExt")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "${prefix}_${stamp}_$suffix.$cleanExt")
            suffix++
        }
        return candidate
    }
}
