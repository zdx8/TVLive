package com.tvlive.tv.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Decoded form of the [com.tvlive.tv.data.model.ChannelEntity.optionsJson]
 * column. We keep the list of `key=value` strings the M3U parser
 * produced and surface a typed API to [PlayerController] / ExoPlayer.
 *
 * Why a separate class from [com.tvlive.tv.data.model.Channel]?
 * Because the parser's `options` field is what we persist; what the
 * player actually needs is a typed map. Decoupling lets us evolve
 * either side without breaking the other.
 */
class ChannelOptions private constructor(
    val pairs: List<Pair<String, String>>
) {
    /**
     * Default request properties to inject into
     * [androidx.media3.datasource.DefaultHttpDataSource]. Mirrors
     * what libVLC would have applied with `:http-user-agent=…`
     * `:http-referrer=…` `:http-proxy=…`.
     */
    fun asDefaultRequestProperties(): Map<String, String> = buildMap {
        for ((k, v) in pairs) {
            when (k) {
                "http-user-agent" -> put("User-Agent", v)
                "http-referrer", "http-referer" -> put("Referer", v)
                // http-proxy is handled separately in asProxy()
            }
        }
    }

    fun asProxy(): ProxySetting? = pairs
        .firstOrNull { it.first == "http-proxy" }
        ?.let { (_, v) -> ProxySetting.parse(v) }

    fun isEmpty(): Boolean = pairs.isEmpty()

    data class ProxySetting(val host: String, val port: Int) {
        companion object {
            /**
             * Accept `host:port`, `http://host:port`, or just `host`
             * (defaults to 80).
             */
            fun parse(raw: String): ProxySetting? {
                val cleaned = raw.trim()
                if (cleaned.isEmpty()) return null
                val withoutScheme = cleaned
                    .removePrefix("http://")
                    .removePrefix("https://")
                val (hostPart, portPart) = withoutScheme.split(':', limit = 2)
                    .let { if (it.size == 2) it[0] to it[1] else it[0] to "80" }
                val port = portPart.toIntOrNull() ?: return null
                return ProxySetting(hostPart, port)
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(optionsJson: String?): ChannelOptions {
            if (optionsJson.isNullOrBlank()) return EMPTY
            val list = runCatching {
                json.decodeFromString(
                    ListSerializer(String.serializer()),
                    optionsJson
                )
            }.getOrDefault(emptyList())
            val pairs = list.mapNotNull { it.toPair() }
            return ChannelOptions(pairs)
        }

        fun empty(): ChannelOptions = EMPTY

        /**
         * Parse `key=value` into a Pair. The M3U parser guarantees
         * only the whitelisted keys reach here, but we defend in depth.
         */
        private fun String.toPair(): Pair<String, String>? {
            val eq = indexOf('=')
            if (eq <= 0) return null
            val key = substring(0, eq).trim().lowercase()
            val value = substring(eq + 1).trim()
            if (key.isEmpty() || value.isEmpty()) return null
            return key to value
        }

        private val EMPTY = ChannelOptions(emptyList())
    }
}
