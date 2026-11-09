package com.tvlive.tv.pairing

import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 扫码配对的迷你 HTTP 服务（方案 B，见 docs/扫码填源-设计方案.md）。
 *
 * 手写而非用 nanohttpd / com.sun.net.httpserver：Android 没有
 * com.sun.net.httpserver；项目惯例不引第三方。只支持本场景两条路由：
 *
 *   GET  /?t=<token>   → 返回手机填写页（assets HTML）
 *   POST /submit?t=…   → form 字段 url=…，校验后回调 [onSubmit]
 *
 * 生命周期：start() 绑定随机端口；stop() 关闭一切。token 一次性、
 * [ttlMs] 过期、成功或 stop 后所有请求一律拒绝。
 */
class PairingServer(
    private val token: String,
    private val pageHtml: String,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val onSubmit: (url: String, name: String?) -> Unit,
) {
    private val startedAtMs = SystemClock.elapsedRealtime()
    private val used = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val serverRef = AtomicReference<ServerSocket?>()
    private var pool: ExecutorService? = null

    /** 绑定的随机端口；start() 之前为 -1。 */
    val port: Int get() = serverRef.get()?.localPort ?: -1

    /** 是否已成功受理过一次提交（一次性语义）。 */
    val isUsed: Boolean get() = used.get()

    @Synchronized
    fun start() {
        check(serverRef.get() == null) { "PairingServer already started" }
        val server = ServerSocket(0, BACKLOG, InetAddress.getByName("0.0.0.0"))
        serverRef.set(server)
        pool = Executors.newFixedThreadPool(THREADS)
        Thread({
            while (!closed.get()) {
                val sock = try {
                    server.accept()
                } catch (e: IOException) {
                    if (closed.get()) break else {
                        Log.w(TAG, "accept failed", e)
                        continue
                    }
                }
                pool?.execute { handle(sock) }
            }
        }, "pairing-accept").apply { isDaemon = true; start() }
        Log.i(TAG, "Pairing server on port $port")
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverRef.get()?.close() }
        pool?.shutdownNow()
        Log.i(TAG, "Pairing server stopped")
    }

    // ---------- 连接处理 ----------

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(s.getInputStream(), 8192)
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val rawPath = parts[1]

                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength !in 0..MAX_BODY_BYTES) {
                    respond(s, 400, "text/plain; charset=utf-8", "Bad Request")
                    return
                }
                val body = if (contentLength > 0) {
                    ByteArray(contentLength).also { buf ->
                        var off = 0
                        while (off < buf.size) {
                            val n = input.read(buf, off, buf.size - off)
                            if (n < 0) break
                            off += n
                        }
                    }
                } else ByteArray(0)

                when {
                    method == "GET" && (rawPath == "/" || rawPath.startsWith("/?")) ->
                        handleGet(rawPath, s)
                    method == "HEAD" && (rawPath == "/" || rawPath.startsWith("/?")) ->
                        respond(s, 200, "text/html; charset=utf-8", "", headOnly = true)
                    method == "POST" && rawPath.startsWith("/submit") ->
                        handleSubmit(rawPath, body, s)
                    else ->
                        respond(s, 404, "text/plain; charset=utf-8", "Not Found")
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.i(TAG, "Connection timed out")
        } catch (e: Exception) {
            Log.i(TAG, "Connection dropped: ${e.message}")
        }
    }

    private fun handleGet(path: String, sock: Socket) {
        val t = queryParams(path)["t"]
        if (!tokenValid(t)) {
            respond(sock, 403, "text/plain; charset=utf-8", reason())
            return
        }
        respond(sock, 200, "text/html; charset=utf-8", pageHtml)
    }

    private fun handleSubmit(path: String, body: ByteArray, sock: Socket) {
        val t = queryParams(path)["t"]
        if (!tokenValid(t)) {
            respond(sock, 403, "text/plain; charset=utf-8", reason())
            return
        }
        val form = String(body, Charsets.UTF_8)
        val fields = form.split('&')
            .filter { it.contains('=') }
            .associate {
                val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(it.substringAfter('='), "UTF-8")
                k to v
            }
        val url = sanitizeUrl(fields["url"].orEmpty())
        if (url == null) {
            respond(sock, 400, "text/plain; charset=utf-8", "链接格式不对：要以 http:// 或 https:// 开头")
            return
        }
        if (!used.compareAndSet(false, true)) {
            respond(sock, 403, "text/plain; charset=utf-8", "该二维码已被使用")
            return
        }
        // 名字留空则由 PlaylistNamer 从订阅源内容自动取名。
        val name = fields["name"]?.trim()?.takeIf { it.isNotEmpty() }?.take(24)
        onSubmit(url, name)
        respond(sock, 200, "text/plain; charset=utf-8", "OK")
    }

    // ---------- 校验与工具 ----------

    private fun expired(): Boolean = SystemClock.elapsedRealtime() - startedAtMs > ttlMs

    private fun tokenValid(t: String?): Boolean = !used.get() && !expired() && t == token

    private fun reason(): String = when {
        expired() -> "二维码已过期，请在电视上重新打开"
        used.get() -> "该二维码已被使用"
        else -> "二维码无效"
    }

    companion object {
        private const val TAG = "PairingServer"
        const val DEFAULT_TTL_MS = 5 * 60_000L
        private const val BACKLOG = 16
        private const val THREADS = 2
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_BODY_BYTES = 8 * 1024

        /** 提交 URL 的合法性：trim、全角转半角、http(s) 前缀、长度上限。 */
        fun sanitizeUrl(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            // 全角 ASCII（FF01–FF5E）转半角 —— 手机输入法常见事故。
            s = buildString(s.length) {
                s.forEach { c ->
                    val code = c.code
                    append(if (code in 0xFF01..0xFF5E) (code - 0xFEE0).toChar() else c)
                }
            }.trim()
            if (s.length !in 1..2048) return null
            val lower = s.lowercase(Locale.US)
            return if (lower.startsWith("http://") || lower.startsWith("https://")) s else null
        }

        /** 解析 query 参数（仅本场景用，不追求完整 RFC）。 */
        fun queryParams(path: String): Map<String, String> {
            val q = path.substringAfter('?', "")
            if (q.isEmpty()) return emptyMap()
            return q.split('&')
                .filter { it.contains('=') }
                .associate {
                    val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
                    val v = URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    k to v
                }
        }

        /** 按字节读一行（到 \n），去掉行尾 \r；流结束返回 null。 */
        private fun readLine(input: BufferedInputStream): String? {
            val sb = StringBuilder(96)
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) break
                sb.append(c.toChar())
            }
            return sb.toString().trimEnd('\r')
        }

        private fun respond(
            sock: Socket,
            status: Int,
            contentType: String,
            body: String,
            headOnly: Boolean = false
        ) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = when (status) {
                200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
                else -> "Error"
            }
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().let { out ->
                BufferedOutputStream(out).use { bos ->
                    bos.write(head.toByteArray(Charsets.UTF_8))
                    if (!headOnly && bytes.isNotEmpty()) bos.write(bytes)
                    bos.flush()
                }
            }
        }
    }
}
