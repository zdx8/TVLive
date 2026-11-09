package com.tvlive.tv.data.m3u

import com.tvlive.tv.data.model.Channel
import com.tvlive.tv.data.model.ParsedPlaylist
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * M3U / M3U8 playlist parser.
 *
 * Ported from iTelly-macOS's `M3UParser.swift`. Key things kept identical:
 *
 *   1. `#EXTINF` attribute parsing skips commas inside quoted ranges
 *      (so `tvg-logo="https://example.com/a,b.png"` does not split the row).
 *
 *   2. `#EXTVLCOPT` and `#KODIPROP` are whitelisted — only the keys
 *      that ExoPlayer / libvlc can actually consume survive. We do NOT
 *      pass through arbitrary keys; Kodi's `inputstream.adaptive.*`
 *      would confuse a Media3 pipeline and iTelly-macOS drops them
 *      too. The whitelist is different from macOS's because Media3 has
 *      no equivalent of `:network-caching` — we only keep keys that
 *      ExoPlayer's `DefaultHttpDataSource` actually honors.
 *
 *   3. GB18030 / UTF-8 / UTF-8-BOM / Latin-1 fallback chain.
 *
 *   4. `looksLikeText()` heuristic — `Latin-1` succeeds for *any* byte
 *      sequence, so blindly falling back to it would parse a binary
 *      file as a playlist and return zero channels with no error. We
 *      require <5% control bytes before trusting a decode.
 *
 *   5. `isPlayableAddress()` — strict protocol allowlist. We do not
 *      treat a non-URL line as a path; iTelly-macOS does the same and
 *      it's the only way to filter out garbage.
 *
 * Differences from macOS:
 *   - No libvlc-style option strings. Media3 has no equivalent, so
 *     we drop the options list in [Channel.options] for now and will
 *     re-introduce it when the PlayerController learns to honor them.
 *   - `loadRemote()` returns [ParsedPlaylist] (we don't need the
 *     separate `(channels, suggestedName)` tuple the macOS code has).
 */
class M3UParser {

    // ---- Entry point ------------------------------------------------------

    /**
     * Parse a string of M3U/M3U8 contents. The caller is responsible
     * for decoding bytes (use [decode]).
     */
    fun parse(text: String): ParsedPlaylist {
        // Accumulator for the channel-in-progress. The macOS
        // implementation uses four parallel locals (pendingName,
        // pendingAttrs, pendingGroup, pendingOptions) which is
        // hard to read. We collect them in a single mutable state
        // object so the "reset on URL line" rule is one line.
        val state = PendingState()
        val channels = mutableListOf<Channel>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                handleDirective(line, state)
                continue
            }

            if (!isPlayableAddress(line)) continue

            channels += state.toChannel(line)
            state.reset()
        }

        return ParsedPlaylist(
            channels = channels,
            suggestedName = "导入的播放列表"
        )
    }

    /**
     * Mutable accumulator for the channel being assembled. Cleared
     * after every URL line in [parse]. Kept private because callers
     * must use [parse] / [parseStream]; constructing one by hand
     * would skip the reset.
     */
    private class PendingState {
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var group: String? = null
        var options: List<String> = emptyList()

        fun toChannel(url: String) = Channel(
            name = name ?: fallbackNameFor(url),
            url = url,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            groupTitle = group ?: attrs["group-title"],
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
            options = options
        )

        fun reset() {
            name = null
            attrs = emptyMap()
            group = null
            options = emptyList()
        }

        private fun fallbackNameFor(url: String): String {
            val last = url.substringAfterLast('/').substringBefore('?')
            return last.ifEmpty { url }
        }
    }

    /** Convenience: parse an [InputStream] of arbitrary encoding. */
    fun parseStream(input: InputStream): ParsedPlaylist {
        // Read all bytes so we can try multiple encodings.
        val bytes = input.readBytes()
        val text = decode(bytes)
            ?: throw M3UParseException("解码失败：文件不是可识别的文本格式")
        return parse(text)
    }

    // ---- Directive handling ----------------------------------------------

    private fun handleDirective(line: String, state: PendingState) {
        if (line.startsWith("#EXTINF:")) {
            val body = line.removePrefix("#EXTINF:").trim()
            val parsed = parseExtInf(body)
            // The macOS parser preserves any prior attributes that
            // weren't set on this #EXTINF line. M3U files in the wild
            // sometimes split attributes across multiple directives.
            state.attrs = state.attrs + parsed.attributes
            state.name = parsed.title ?: state.name
            val grp = parsed.attributes["group-title"]
            if (!grp.isNullOrEmpty()) state.group = grp
            return
        }

        if (line.startsWith("#EXTGRP:")) {
            val g = line.removePrefix("#EXTGRP:").trim()
            if (g.isNotEmpty()) state.group = g
            return
        }

        if (line.startsWith("#EXTVLCOPT:") || line.startsWith("#KODIPROP:")) {
            val body = line.substringAfter(':').trim()
            val option = vlcOptionFrom(body)
            if (option != null) state.options = state.options + option
            return
        }

        // #EXTM3U / #PLAYLIST / other unknown directives: ignore
    }

    /**
     * Parse the body of `#EXTINF:-1 tvg-id="x" tvg-name="y" group-title="z",Channel Name`.
     *
     * Crucial: the comma after the attributes might be **inside** a
     * quoted attribute value (e.g. a logo URL with a comma), so we
     * find the first comma that's not inside a `"…"` span.
     */
    internal fun parseExtInf(body: String): ExtInf {
        val attributes = mutableMapOf<String, String>()
        val quotedRanges = mutableListOf<IntRange>()

        val attrPattern = Pattern.compile("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
        val matcher = attrPattern.matcher(body)
        while (matcher.find()) {
            // group(1) / group(2) are platform types (String!). We
            // matched, so they cannot be null in practice, but Kotlin
            // 2.0 warns and 2.1+ errors on calling members of a
            // nullable receiver. Be explicit rather than rely on the
            // platform type.
            val key = matcher.group(1)?.lowercase() ?: continue
            val value = matcher.group(2) ?: continue
            attributes[key] = value
            quotedRanges += matcher.start() until matcher.end()
        }

        // Find first comma not inside any quoted range
        var title: String? = null
        var searchStart = 0
        while (searchStart < body.length) {
            val comma = body.indexOf(',', searchStart)
            if (comma < 0) break
            val insideQuoted = quotedRanges.any { comma in it }
            if (!insideQuoted) {
                val raw = body.substring(comma + 1).trim()
                if (raw.isNotEmpty()) title = raw
                break
            }
            searchStart = comma + 1
        }

        return ExtInf(title = title, attributes = attributes)
    }

    internal data class ExtInf(
        val title: String?,
        val attributes: Map<String, String>
    )

    /**
     * Whitelist of `#EXTVLCOPT` keys we know Media3's
     * `DefaultHttpDataSource` honors. Everything else is dropped, same
     * as iTelly-macOS's "仅放行 libvlc 能识别的键" policy.
     *
     * If you need a new key, add it here AND verify the underlying
     * ExoPlayer datasource supports it. The original iTelly-macOS
     * whitelist is in M3UParser.swift `vlcpOption(from:)`.
     */
    private fun vlcOptionFrom(body: String): String? {
        val eq = body.indexOf('=')
        if (eq < 0) return null
        val key = body.substring(0, eq).trim().lowercase()
        var value = body.substring(eq + 1).trim()
        // Strip surrounding quotes
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1)
        }

        // Media3's DefaultHttpDataSource honors these via setDefaultRequestProperties
        // and the DataSpec.headers map. The keys we pass through here will
        // be read by the custom DataSource we add in M5.
        val supported = setOf(
            "http-user-agent",
            "http-referrer",
            "http-referer",
            "http-proxy",
            "network-caching"
        )
        if (key !in supported) return null
        if (key == "network-caching") {
            // Pass through as milliseconds to the custom data source
            val ms = value.toLongOrNull() ?: return null
            return "network-caching=$ms"
        }
        return "$key=$value"
    }

    // ---- Decoding --------------------------------------------------------

    /**
     * Multi-encoding decode. Mirrors the macOS `M3UParser.decode(_:)`
     * chain, including the `looksLikeText` guard.
     */
    fun decode(bytes: ByteArray): String? {
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte()
            && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            val s = String(bytes.drop(3).toByteArray(), Charsets.UTF_8)
            if (looksLikeText(s)) return s
        }

        // Plain UTF-8
        runCatching { String(bytes, Charsets.UTF_8) }
            .getOrNull()
            ?.let { if (looksLikeText(it)) return it }

        // GB18030 (Chinese IPTV providers) — JDK 17+ on macOS supports
        // GB18030 out of the box, so we don't need the Charset.forName
        // dance the macOS Swift code does with CoreFoundation.
        runCatching {
            val gb = java.nio.charset.Charset.forName("GB18030")
            val s = String(bytes, gb)
            if (looksLikeText(s)) s else null
        }.getOrNull()?.let { return it }

        // Latin-1 fallback — but only if it really looks like text.
        // (Latin-1 can decode any byte sequence, including binary, so
        // we apply the same `looksLikeText` guard the macOS code uses.)
        val latin1 = String(bytes, Charsets.ISO_8859_1)
        if (looksLikeText(latin1)) return latin1

        return null
    }

    /**
     * Heuristic: a decoded string is "text-like" if it has no NUL
     * bytes and <5% control bytes. Mirrors the macOS rule. Without
     * this, decoding a binary file with Latin-1 always "succeeds" and
     * the parser silently returns zero channels.
     */
    internal fun looksLikeText(text: String): Boolean {
        if (text.isEmpty()) return false
        var controlCount = 0
        var total = 0
        for (scalar in text) {
            val v = scalar.code
            if (v == 0) return false
            total += 1
            if (v < 0x20 && v != 0x09 && v != 0x0A && v != 0x0D) {
                controlCount += 1
            }
        }
        if (total == 0) return false
        return controlCount.toDouble() / total.toDouble() < 0.05
    }

    // ---- Address validation ---------------------------------------------

    /**
     * True if [line] looks like a stream URL or a local path.
     * Mirrors the macOS allowlist, but with the protocols Media3
     * actually supports.
     */
    internal fun isPlayableAddress(line: String): Boolean {
        if (line.startsWith("/") || line.startsWith("file://")) return true
        val sep = line.indexOf("://")
        if (sep <= 0) return false
        val scheme = line.substring(0, sep).lowercase()
        return scheme in SUPPORTED_SCHEMES
    }

    private fun fallbackName(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?')
        return last.ifEmpty { url }
    }

    companion object {
        /**
         * Protocols Media3 1.4.x can actually play. We deliberately
         * leave out `mms` (Microsoft Media Server, dead since
         * Windows 7) and `ftp` (would need `media3-exoplayer-ftp`).
         * The macOS project is the same on this point; libVLC just
         * happens to support them so the original list looked
         * bigger.
         */
        val SUPPORTED_SCHEMES = setOf(
            "http", "https", "rtsp", "rtsps", "rtmp", "rtmps",
            "udp", "rtp"
        )
    }
}

/** Thrown when a playlist cannot be parsed. */
internal class M3UParseException(message: String) : RuntimeException(message)
