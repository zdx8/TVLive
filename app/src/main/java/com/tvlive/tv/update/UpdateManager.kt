package com.tvlive.tv.update

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tvlive.tv.AppConfig
import com.tvlive.tv.BuildConfig
import com.tvlive.tv.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自更新：查清单 → 下载 APK → 交给系统安装器。
 *
 * 为什么最后一步一定要弹系统安装界面：普通应用（没有系统签名 / root）
 * 在 Android 上无法静默安装。能自动化的只是「检查 + 下载 + 唤起安装器」，
 * 最后一按必须由用户完成 —— 遥控器上就是一次「安装」。
 *
 * 清单格式（放在 NAS 静态站上，见 scripts/make_update_manifest.py）：
 * {
 *   "versionCode": 5,
 *   "versionName": "1.2.2",
 *   "url": "https://.../TVLive-v1.2.13-release.apk",
 *   "size": 4404019,
 *   "notes": "修复切台延迟"
 * }
 */
class UpdateManager(private val activity: Activity) {

    companion object {
        private const val TAG = "TVLive.Update"
        private const val PREFS = "hftv_update"
        private const val KEY_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 小时
        private const val APK_NAME = "hftv-update.apk"

        /** GitHub release 认领的安装包后缀（兼容历史 -public-release.apk 命名）。 */
        private const val GITHUB_APK_SUFFIX = "-release.apk"
    }

    data class Info(
        val versionCode: Int,   // 未使用（保留字段便于日志），版本判定走 semver
        val versionName: String,
        val url: String,
        val size: Long,
        val notes: String
    )

    private val main = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null

    /**
     * 检查一次更新。只在用户 MENU 手动触发（force=true）——策略上没有
     * 后台自动检查，自动弹更新卡片对看电视的人是打扰。
     *
     * @param force 忽略节流间隔（手动路径始终 force）
     */
    fun check(
        force: Boolean = false,
        onAvailable: (Info) -> Unit,
        onNone: (() -> Unit)? = null
    ) {
        // Public flavor ships with a blank manifest URL = self-update
        // disabled by design; updates come from the GitHub Releases page.
        if (AppConfig.UPDATE_MANIFEST_URL.isBlank()) {
            Log.i(TAG, "Self-update disabled (no manifest URL in this build)")
            onNone?.let { none -> main.post { none() } }
            return
        }
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        Thread {
            val info = try {
                fetchManifest()
            } catch (t: Throwable) {
                Log.i(TAG, "Update check skipped: ${t.message}")
                null
            }
            if (info == null || !semverGreaterThan(info.versionName, localVersionName())) {
                if (info != null) Log.i(TAG, "Up to date (${info.versionName})")
                onNone?.let { none -> main.post { none() } }
                return@Thread
            }
            Log.i(TAG, "Update available: ${info.versionName}")
            main.post { onAvailable(info) }
        }.apply { name = "hftv-update-check"; isDaemon = true }.start()
    }

    /**
     * 更新清单：GitHub releases/latest —— tag = 版本号（v1.2.19），
     * assets 里挑 *-release.apk 当安装包，release body 当更新说明。
     * 公开仓库无需认证。
     */
    private fun fetchManifest(): Info? {
        val conn = (URL(AppConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TVLive-update-check")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isBlank()) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var apkSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(GITHUB_APK_SUFFIX)) {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size", 0L)
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) {
                Log.i(TAG, "Latest release has no $GITHUB_APK_SUFFIX asset")
                return null
            }
            Info(
                versionCode = 0,
                versionName = tag,
                url = apkUrl,
                size = apkSize,
                notes = json.optString("body", "")
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 本地版本（去掉 flavor 后缀），供 semver 比较。 */
    private fun localVersionName(): String =
        BuildConfig.VERSION_NAME.removeSuffix("-public")

    /** 逐段数字比较 major.minor.patch（缺段按 0）。 */
    private fun semverGreaterThan(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** 弹卡片：有新版本 → 用户确认 → 下载（带进度）→ 安装器。 */
    fun promptInstall(info: Info) {
        if (activity.isFinishing) return
        if (dialog?.isShowing == true) return

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_update)
        d.setCanceledOnTouchOutside(false)
        d.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setDimAmount(0.35f)
        }

        val title = d.findViewById<TextView>(R.id.update_title)
        val message = d.findViewById<TextView>(R.id.update_message)
        val progress = d.findViewById<ProgressBar>(R.id.update_progress)
        val cancel = d.findViewById<View>(R.id.btn_update_later)
        val confirm = d.findViewById<View>(R.id.btn_update_now)

        title.text = "发现新版本 ${info.versionName}"
        message.text = buildString {
            if (info.size > 0) append("安装包 ${info.size / 1024 / 1024} MB")
            if (info.notes.isNotBlank()) {
                if (isNotEmpty()) appendLine().appendLine()
                append(info.notes)
            }
            appendLine().appendLine()
            append("下载完成后会弹出系统安装界面，按遥控器「安装」即可。")
        }

        d.setOnShowListener { cancel.requestFocus() }
        cancel.setOnClickListener { d.dismiss() }
        confirm.setOnClickListener {
            confirm.isEnabled = false
            confirm.alpha = 0.5f
            cancel.isEnabled = false
            progress.visibility = View.VISIBLE
            download(info, d, title, message, progress)
        }
        dialog = d
        d.show()
    }

    private fun download(
        info: Info,
        d: Dialog,
        title: TextView,
        message: TextView,
        progress: ProgressBar
    ) {
        Thread {
            var apkFile: File? = null
            try {
                val target = File(activity.cacheDir, APK_NAME)
                if (target.exists()) target.delete()

                val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                }
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    main.post {
                                        progress.progress = percent
                                        message.text = "下载中 $percent%"
                                    }
                                }
                            }
                        }
                    }
                }
                apkFile = target
            } catch (t: Throwable) {
                Log.w(TAG, "Download failed", t)
                main.post {
                    progress.visibility = View.GONE
                    title.text = "下载失败"
                    message.text = t.message ?: "网络错误"
                }
                return@Thread
            }

            val file = apkFile ?: return@Thread
            main.post {
                d.dismiss()
                install(file)
            }
        }.apply { name = "hftv-update-download"; isDaemon = true }.start()
    }

    /** 交给系统安装器。未授予「安装未知应用」时先跳设置页。 */
    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(activity, "请先允许「电视直播」安装应用", Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure {
            Log.e(TAG, "Failed to launch installer", it)
            Toast.makeText(activity, "无法打开安装器：${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
