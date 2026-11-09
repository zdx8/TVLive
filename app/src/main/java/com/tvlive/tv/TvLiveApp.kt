package com.tvlive.tv

import android.app.Application
import android.util.Log
import com.tvlive.tv.player.Diagnostics
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

/**
 * TvLiveApp — application entry point.
 *
 * On debug builds we run the offline regression checks (mirrors the
 * iTelly-macOS `--diagnise` self-test). The result is logged; a CI
 * script can grep the log for "[FAIL]" to fail the build.
 *
 * Also installs a global uncaught-exception handler so the next
 * black-screen-on-launch report we get from a user actually
 * contains the stack trace in logcat. Without this the box just
 * logs the crash to system_server, the user sees "已停止运行",
 * and we have nothing to work with.
 */
class TvLiveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        logPriorCrashIfAny()
        stageSamplePlaylist()
        if (BuildConfig.DEBUG) {
            Log.i("TVLive", Diagnostics.dumpConfig())
            try {
                val summary = Diagnostics.runOfflineRegressionChecks()
                Log.i("TVLive", summary.toText())
            } catch (t: Throwable) {
                // Don't crash the app over a failed diag — the macOS
                // --diagnose script also runs separately and would
                // catch a regression. Here we just want to log.
                Log.e("TVLive", "Diagnostics failed at startup", t)
            }
        }
    }

    /**
     * If a previous run left a [CrashLog] file, dump it to logcat at
     * ERROR level so a `adb logcat` capture from the next start will
     * contain the trace even if the device never gets `adb pull`'d
     * to retrieve the file directly.
     */
    private fun logPriorCrashIfAny() {
        try {
            val file = java.io.File(getExternalFilesDir(null), CrashLog.FILE_NAME)
            if (file.exists() && file.length() > 0) {
                Log.e("TVLive.Crash", "Last run crashed. Trace:")
                file.useLines { lines ->
                    lines.forEach { Log.e("TVLive.Crash", "  $it") }
                }
            }
        } catch (t: Throwable) {
            Log.w("TVLive", "Failed to read prior crash log: ${t.message}")
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                Log.e(
                    "TVLive.Crash",
                    "Uncaught exception on thread ${thread.name}: " +
                        System.lineSeparator() + sw.toString()
                )
                CrashLog.write(this, "uncaught", throwable)
            } catch (_: Throwable) {
                // Never let our crash handler itself crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun stageSamplePlaylist() {
        val target = File(filesDir, "samples/sample.m3u")
        if (target.exists()) return
        target.parentFile?.mkdirs()
        try {
            assets.open("samples/sample.m3u").use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i("TVLive", "Staged sample playlist: ${target.absolutePath}")
        } catch (e: Exception) {
            Log.e("TVLive", "Failed to stage sample playlist", e)
        }
    }
}
