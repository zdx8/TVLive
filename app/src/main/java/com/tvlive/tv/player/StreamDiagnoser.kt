package com.tvlive.tv.player

import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * "What did the server actually return for that stream URL?"
 *
 * The single most useful piece of information when a stream fails
 * with `PARSING_CONTAINER_UNSUPPORTED` is the response body of the
 * stream URL — because it is almost always not a media container at
 * all, but an HTML login page, a JSON auth error, or a 302 to a CDN
 * whose host the TV cannot reach. ExoPlayer never logs it; it just
 * says "could not read the stream". So we have to fetch it ourselves.
 *
 * [diagnose] does two cheap round-trips:
 *   1. **HEAD** — for the status line + response headers (no body).
 *   2. **GET with `Range: bytes=0-1023`** — for the first 1 KB of body
 *      when the server supports it. The Range header keeps large
 *      files (e.g. some IPTV servers return a multi-MB TS) from
 *      being pulled in full. If the server ignores Range, we cap at
 *      1 KB anyway.
 *
 * The [Diagnosis] is plain text + a hex dump, so it can be shown
 * verbatim on a TV overlay (debugView / errorView) without losing
 * control characters. Long lines are wrapped at [LINE_WIDTH] for the
 * UTF-8 preview; the hex dump is never wrapped (each byte = 2 chars).
 */
internal object StreamDiagnoser {

    private const val TAG = "TVLive.Diag"
    private const val RANGE_BYTES = 1024
    private const val LINE_WIDTH = 96
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * Result of probing a single stream URL. Fields are populated
     * best-effort: a failed probe produces an empty body snippet rather
     * than throwing, so a flaky network can never break error reporting.
     */
    data class Diagnosis(
        val url: String,
        val statusLine: String,
        val headers: List<Pair<String, String>>,
        val bodyUtf8: String,
        val bodyHex: String,
        val notes: List<String>
    ) {
        /**
         * Render [Diagnosis] as multi-line text suitable for pasting
         * into a TV-side TextView or a logcat dump. Layout:
         *
         *   GET <url>
         *   <status line>
         *   <header>: <value>
         *   ── body (utf-8, first 256 chars) ──
         *   <…>
         *   ── body (hex, first 64 bytes) ──
         *   <…>
         *   ── notes ──
         *   - <note>
         */
        fun toDisplayString(): String = buildString {
            appendLine("GET ").appendLine(url)
            appendLine(statusLine)
            headers.forEach { (k, v) ->
                append(k).append(": ").appendLine(v)
            }
            appendLine("── body (utf-8, first ${bodyUtf8.length} chars) ──")
            appendLine(if (bodyUtf8.isEmpty()) "<empty>" else bodyUtf8)
            appendLine("── body (hex, first ${bodyHex.length / 2} bytes) ──")
            appendLine(if (bodyHex.isEmpty()) "<empty>" else bodyHex)
            if (notes.isNotEmpty()) {
                appendLine("── notes ──")
                notes.forEach { append("- ").appendLine(it) }
            }
        }
    }

    /**
     * Diagnose [url]. Never throws — failure modes are captured into
     * [Diagnosis.notes] so the caller always gets something to show.
     *
     * @param connectTimeoutMs TCP connect timeout in milliseconds.
     * @param readTimeoutMs Read timeout for each round-trip.
     */
    fun diagnose(
        url: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Diagnosis {
        val notes = mutableListOf<String>()
        val statusLine: String
        val headers: List<Pair<String, String>>
        val body: ByteArray

        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                // Use the same UA as PlayerConfig — some IPTV servers
                // gate responses on a known client UA. Without this,
                // the diagnostic body could mislead the user (e.g.
                // show a generic "use a real client" HTML).
                setRequestProperty(
                    "User-Agent",
                    com.tvlive.tv.player.PlayerConfig.userAgent
                )
            }

            // 1) Headers-only probe.
            conn.requestMethod = "HEAD"
            statusLine = try {
                val code = conn.responseCode
                val msg = conn.responseMessage ?: ""
                "$code $msg"
            } catch (t: Throwable) {
                // Release the connection before bailing out — without
                // disconnect() the socket lingers in the JVM pool until
                // its keep-alive expires.
                conn.disconnect()
                notes += "HEAD failed: ${t.javaClass.simpleName}: ${t.message ?: ""}"
                return Diagnosis(
                    url = url,
                    statusLine = "(HEAD failed: ${t.javaClass.simpleName})",
                    headers = emptyList(),
                    bodyUtf8 = "",
                    bodyHex = "",
                    notes = notes
                )
            }

            headers = conn.headerFields.entries
                .filter { it.key != null }
                .flatMap { entry -> entry.value.map { entry.key to it } }
                .sortedWith(compareBy({ it.first }, { it.second }))

            // Some IPTV servers (and some HTTP middleboxes) refuse
            // HEAD with 405/501. If we got a 2xx from HEAD, sample
            // the body too — otherwise skip the body probe so we don't
            // confuse the diagnostic.
            val headOk = statusLine.startsWith("2")
            val acceptsRange = headers.any { (k, _) -> k.equals("Accept-Ranges", true) }
            body = if (headOk) {
                try {
                    // Use a separate GET with Range so we never pull
                    // the whole stream. Capped at RANGE_BYTES.
                    fetchRangeBody(url, RANGE_BYTES, connectTimeoutMs, readTimeoutMs)
                        .also { bytes ->
                            notes += "body probe: ${bytes.size} bytes (≤$RANGE_BYTES)"
                        }
                } catch (t: Throwable) {
                    notes += "body probe failed: ${t.javaClass.simpleName}: ${t.message ?: ""}"
                    ByteArray(0)
                }
            } else {
                notes += "skipped body probe (HEAD was $statusLine)"
                ByteArray(0)
            }

            if (!acceptsRange && body.size == RANGE_BYTES) {
                notes += "server does not advertise Accept-Ranges; the body may be the full file"
            }

            conn.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "diagnose($url) threw", t)
            notes += "openConnection failed: ${t.javaClass.simpleName}: ${t.message ?: ""}"
            return Diagnosis(url, "(connection failed)", emptyList(), "", "", notes)
        }

        return Diagnosis(
            url = url,
            statusLine = statusLine,
            headers = headers,
            bodyUtf8 = toSafeUtf8(body, LINE_WIDTH),
            bodyHex = toHexPreview(body, 64),
            notes = notes
        )
    }

    /**
     * GET a small slice of the URL using `Range: bytes=0-{n-1}`.
     *
     * Some servers ignore Range; some return 200 with the whole body.
     * Either way we read at most [maxBytes] bytes from the response
     * stream.
     */
    private fun fetchRangeBody(
        url: String,
        maxBytes: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", com.tvlive.tv.player.PlayerConfig.userAgent)
            setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            setRequestProperty("Accept", "*/*")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 206) {
                throw IllegalStateException("body GET returned $code")
            }
            conn.inputStream.use { stream -> stream.readBoundedBytes(maxBytes) }
        } finally {
            conn.disconnect()
        }
    }

    private fun InputStream.readBoundedBytes(max: Int): ByteArray {
        val buf = ByteArray(max)
        var total = 0
        while (total < max) {
            val read = read(buf, total, max - total)
            if (read < 0) break
            total += read
        }
        return if (total == buf.size) buf else buf.copyOf(total)
    }

    /**
     * Decode [bytes] as UTF-8 with replacement, control characters
     * escaped, and the result truncated to at most [maxChars] code
     * units. Control characters are the silent killer of TV-side
     * TextView rendering: a stray U+0000 can truncate display.
     *
     * Everything that is NOT a control character passes through
     * verbatim — including CJK and emoji — so a Chinese-language
     * auth-error page stays readable on the overlay instead of
     * degrading into a wall of U+XXXX escapes.
     *
     * Exposed `internal` so the pure parts of the diagnoser can be
     * unit-tested without a real network.
     */
    fun toSafeUtf8(bytes: ByteArray, maxChars: Int): String {
        if (bytes.isEmpty()) return ""
        val raw = String(bytes, Charsets.UTF_8)
        val safe = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\n' -> safe.append('⏎')
                ch == '\r' -> {} // collapse \r\n
                ch.code == '\t'.code -> safe.append("→")
                // Remaining control chars (C0) + DEL (0x7F) + C1 (0x80-0x9F):
                // all of them can garble or truncate a TextView.
                ch.code < 0x20 || ch.code == 0x7F || ch.code in 0x80..0x9F ->
                    safe.append(String.format("\\u%04X", ch.code))
                else -> safe.append(ch)
            }
        }
        return if (safe.length <= maxChars) safe.toString()
        else safe.substring(0, maxChars) + "…"
    }

    /**
     * Render the first [maxBytes] of this buffer as a hex string,
     * formatted in 16-byte rows. Compact and copy-pasteable.
     *
     * Exposed `internal` for unit-testing.
     */
    fun toHexPreview(bytes: ByteArray, maxBytes: Int): String {
        if (bytes.isEmpty()) return ""
        val take = minOf(bytes.size, maxBytes)
        val sb = StringBuilder(take * 3)
        var i = 0
        while (i < take) {
            if (i > 0 && i % 16 == 0) sb.append('\n')
            sb.append(String.format("%02X", bytes[i]))
            sb.append(if (i % 16 == 15) ' ' else ' ')
            i++
        }
        if (bytes.size > take) sb.append("\n…(truncated, total ").append(bytes.size).append(" bytes)")
        return sb.toString()
    }
}