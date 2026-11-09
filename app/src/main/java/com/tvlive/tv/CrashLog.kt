package com.tvlive.tv

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash log persistence.
 *
 * Writes the latest uncaught exception to a file in the app's
 * external files directory (so we can `adb pull` it without root
 * even after the app process has died).
 *
 *   adb pull /sdcard/Android/data/com.tvlive.tv/files/last_crash.txt
 *
 * The file is overwritten on every crash — only the most recent
 * trace is kept, which is what we need to debug the next report.
 */
object CrashLog {

    const val FILE_NAME = "last_crash.txt"

    fun filePath(context: Context): String =
        File(context.getExternalFilesDir(null), FILE_NAME).absolutePath

    fun write(context: Context, stage: String, t: Throwable) {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        file.parentFile?.mkdirs()
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            appendLine("TVLive last_crash")
            appendLine("timestamp: $now")
            appendLine("stage:     $stage")
            appendLine("error:     ${t.javaClass.name}")
            appendLine("message:   ${t.message ?: "(none)"}")
            appendLine()
            appendLine("stack trace:")
            append(sw.toString())
        }
        file.writeText(body)
    }
}
