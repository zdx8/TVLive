package com.tvlive.tv.data.model

/**
 * Domain model — what the UI sees. Independent from Room so we can
 * refactor storage later without touching the presentation layer.
 *
 * Field semantics match iTelly-macOS's `Channel` struct 1:1:
 *   - [logo] is the tvg-logo URL (often a CDN PNG/SVG; we render
 *     with Coil in the UI layer)
 *   - [groupTitle] is the iTelly-macOS `group` field
 *   - [options] is a list of Media3-style options (the M2 parser
 *     filters to a safe whitelist, same as iTelly-macOS)
 */
data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val options: List<String> = emptyList()
) {
    /** Best-effort group for the browse UI. Never null. */
    fun groupKey(): String = groupTitle?.takeIf { it.isNotBlank() } ?: "未分组"
}

/**
 * Result of parsing one M3U/M3U8 file. Includes the [suggestedName]
 * (used when the user imports an unnamed playlist).
 */
data class ParsedPlaylist(
    val channels: List<Channel>,
    val suggestedName: String
) {
    val channelCount: Int get() = channels.size

    /** Distinct group titles, in M3U insertion order. */
    val groups: List<String> by lazy {
        channels.asSequence()
            .map { it.groupKey() }
            .distinct()
            .toList()
    }
}
