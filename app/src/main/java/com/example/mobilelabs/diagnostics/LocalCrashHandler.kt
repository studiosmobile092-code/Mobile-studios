package com.example.mobilelabs.diagnostics

import android.content.Context
import com.example.mobilelabs.security.KeystoreSecretStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M3.8 Local-only privacy-respecting crash logger (zero Crashlytics / third-party telemetry SDKs).
 * Strips stream keys and RTMP URLs before persisting to app-private storage (Iron Rule R13).
 */
class LocalCrashHandler(
    private val context: Context,
    private val delegate: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
) : Thread.UncaughtExceptionHandler {

    private val crashFile: File
        get() = File(File(context.filesDir, "mobilelabs_state").apply { mkdirs() }, "last_crash.txt")

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val redactedTrace = KeystoreSecretStore.redactUrlAndKeys(sw.toString())
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val report = "Crash @ $stamp on thread ${t.name}: ${e.javaClass.simpleName}: ${KeystoreSecretStore.redactUrlAndKeys(e.message.orEmpty())}\n$redactedTrace"
            crashFile.writeText(report.take(8192), Charsets.UTF_8)
        }
        delegate?.uncaughtException(t, e)
    }

    fun consumeLastCrashSummary(): String? {
        val f = crashFile
        if (!f.exists()) return null
        val firstLine = runCatching { f.readLines().firstOrNull() }.getOrNull()
        runCatching { f.delete() }
        return firstLine
    }
}
