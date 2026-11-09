package com.tvlive.tv.pairing

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.tvlive.tv.R
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale

/**
 * 扫码填入订阅（方案 B）：电视亮二维码，手机扫码在浏览器里粘贴订阅地址。
 *
 * 流程：start → PairingServer 绑定随机端口 → 二维码指向
 * http://<电视IP>:<端口>/?t=<一次性token> → 手机提交 → 写入
 * SubscriptionStore 并 setActive → setResult 回调用方（MENU 所在页）
 * → 调用方切到新订阅。
 *
 * 任何退出路径都 stop() 服务；5 分钟无提交自动结束。
 */
class PairingActivity : Activity() {

    companion object {
        private const val TAG = "PairingActivity"
        const val EXTRA_URL = "pairing_url"
        private const val TIMEOUT_MS = 5 * 60_000L
        private const val SUCCESS_LINGER_MS = 1_200L
    }

    private val main = Handler(Looper.getMainLooper())
    private val token = newToken()
    private var server: PairingServer? = null
    private var pageHtml: String = ""

    private lateinit var qrView: ImageView
    private lateinit var titleView: TextView
    private lateinit var hintView: TextView
    private lateinit var urlView: TextView
    private lateinit var manualBtn: TextView
    private lateinit var cancelBtn: TextView

    private val timeoutRunnable = Runnable {
        Toast.makeText(this, "二维码已过期", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ip = pickIpv4()
        if (ip == null) {
            Toast.makeText(this, "请先连接网络再使用扫码填入", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_pairing)
        qrView = findViewById(R.id.pairing_qr)
        titleView = findViewById(R.id.pairing_title)
        hintView = findViewById(R.id.pairing_hint)
        urlView = findViewById(R.id.pairing_url)
        manualBtn = findViewById(R.id.btn_manual)
        cancelBtn = findViewById(R.id.btn_cancel)

        pageHtml = loadPageHtml()

        server = PairingServer(token, pageHtml) { url, name ->
            main.post { onUrlReceived(url, name) }
        }
        server?.start()

        val link = "http://$ip:${server?.port}/?t=$token"
        Log.i(TAG, "Pairing link: $link")
        qrView.setImageBitmap(QrBitmap.make(link, 720))
        urlView.text = link

        manualBtn.setOnClickListener { promptManualInput() }
        cancelBtn.setOnClickListener { finish() }
        cancelBtn.nextFocusLeftId = manualBtn.id
        manualBtn.nextFocusRightId = cancelBtn.id

        main.postDelayed(timeoutRunnable, TIMEOUT_MS)
        cancelBtn.requestFocus()
    }

    override fun onDestroy() {
        main.removeCallbacks(timeoutRunnable)
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    // ---------- 成功落地 ----------

    private fun onUrlReceived(rawUrl: String, name: String? = null) {
        if (isFinishing || isDestroyed) return
        val url = PairingServer.sanitizeUrl(rawUrl) ?: return
        val entry = com.tvlive.tv.data.store.SubscriptionStore.add(this, url, name)
        com.tvlive.tv.data.store.SubscriptionStore.setActive(this, url)

        titleView.text = "✓ 已收到订阅「${entry.name}」"
        hintView.text = "正在切换到「${entry.name}」…"
        qrView.visibility = View.GONE
        urlView.visibility = View.GONE
        manualBtn.visibility = View.GONE
        cancelBtn.visibility = View.GONE

        main.postDelayed({
            setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, url))
            finish()
        }, SUCCESS_LINGER_MS)
    }

    // ---------- 手动输入（扫码不可用时的兜底） ----------

    private fun promptManualInput() {
        val nameInput = EditText(this).apply {
            hint = "订阅名称（可留空，自动取名）"
            setSingleLine(true)
        }
        val urlInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/playlist.m3u"
            setSingleLine(true)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
            addView(nameInput)
            addView(urlInput)
        }
        // framework 版 AlertDialog：项目主题是原生 Material，AppCompat 版会崩。
        android.app.AlertDialog.Builder(this)
            .setTitle("手动输入订阅")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val url = PairingServer.sanitizeUrl(urlInput.text.toString())
                val name = nameInput.text.toString().trim().takeIf { it.isNotEmpty() }
                if (url != null) {
                    onUrlReceived(url, name)
                } else {
                    Toast.makeText(this, "链接格式不对：要以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 工具 ----------

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private fun loadPageHtml(): String =
        runCatching { assets.open("pairing.html").bufferedReader().use { it.readText() } }
            .getOrDefault("")
            .replace("__TOKEN__", token)

    /**
     * 取电视的局域网 IPv4：优先有线（eth*），其次无线（wlan*），再次任意。
     * 多网卡并存时二维码只能编码一个地址，所以页面上同时明文展示 URL 供肉眼校对。
     */
    private fun pickIpv4(): String? {
        data class Cand(val name: String, val ip: String)

        val candidates = mutableListOf<Cand>()
        runCatching {
            val it = NetworkInterface.getNetworkInterfaces()
            while (it.hasMoreElements()) {
                val nif = it.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        candidates += Cand(nif.name, a.hostAddress)
                    }
                }
            }
        }
        return candidates.firstOrNull { it.name.startsWith("eth") }?.ip
            ?: candidates.firstOrNull { it.name.startsWith("wlan") }?.ip
            ?: candidates.firstOrNull()?.ip
    }
}
