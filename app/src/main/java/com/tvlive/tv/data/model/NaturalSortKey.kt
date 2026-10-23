package com.tvlive.tv.data.model

/**
 * Bucket + natural sort key for channel display order.
 *
 * The TVLive home screen is an IPTV viewer; the user expects
 * channels to appear in a familiar, predictable order rather than
 * the raw lexicographic (and often surprising) ordering of the
 * m3u file. We bucket channels by their leading character set, then
 * apply a natural-sort (numeric-aware) key inside each bucket.
 *
 * Buckets (in display order):
 *
 *   1. **CCTV** — names that start with the literal Latin string
 *      "CCTV". Always first; the TVLive user lives in a Chinese
 *      network and the national channels are the natural first stop.
 *
 *   2. **Lake-first Chinese** — Chinese names that contain the
 *      character "湖" (Hunan/Hubei TV are the canonical "湖" names
 *      and the user wants them just after CCTV). Implemented as a
 *      special case inside the Chinese bucket: any channel whose
 *      display name contains "湖" sorts ahead of all other Chinese
 *      names.
 *
 *   3. **Other Chinese** — names that start with a CJK Unified
 *      Ideograph (U+4E00..U+9FFF) but are not "湖"-prefixed. Sorted
 *      by [of]'s natural sort key, which puts digits in numeric
 *      order ("第1集" < "第2集" < "第10集") and otherwise uses
 *      Unicode code-point order.
 *
 *   4. **Other** — everything else (Latin-only names, mixed-script
 *      names that don't start with a CJK character, etc.). Same
 *      natural-sort key.
 *
 * Example ordering (with the test data):
 *   1. CCTV1
 *   2. CCTV2
 *   3. CCTV13
 *   4. 湖南卫视      ← "湖" first
 *   5. 湖北经视
 *   6. 东方卫视      ← then other CJK
 *   7. 凤凰卫视
 *   8. 北京卫视
 *   9. ...
 *  10. ATV
 *  11. TVB
 *  12. Jade
 */
object NaturalSortKey {

    const val MAX_DIGITS = 10
    private const val PAD = "0000000000"   // 10 zeros
    private const val LAKE_CHAR = '湖'

    /**
     * Build a (bucketIndex, sortKey) pair that drives the comparator.
     * Lower bucketIndex = displayed earlier.
     */
    fun bucketKey(displayName: String?): Pair<Int, String> {
        val name = displayName.orEmpty()
        val key = of(name)
        val bucket = when {
            name.startsWith("CCTV") -> 0
            name.contains(LAKE_CHAR) -> 1     // 湖 系优先于其他汉字
            isCjkFirst(name) -> 2             // 汉字频道
            else -> 3
        }
        return bucket to key
    }

    /**
     * Pure: turn a free-form string into a comparator-friendly form
     * where the numeric chunks are zero-padded to [MAX_DIGITS] width.
     * "CCTV10" becomes "CCTV0000000010" so it sorts after
     * "CCTV2" (= "CCTV0000000002") — the standard natural-sort
     * trick.
     */
    fun of(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val sb = StringBuilder(input.length + MAX_DIGITS)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c.isDigit()) {
                // Find the digit run.
                var j = i
                while (j < input.length && input[j].isDigit()) j++
                val digits = input.substring(i, j)
                sb.append(PAD, 0, (MAX_DIGITS - digits.length).coerceAtLeast(0))
                sb.append(digits)
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Does the name start with a CJK Unified Ideograph? We treat the
     * [U+4E00, U+9FFF] range as "Chinese name" — that covers
     * everything from 一 (U+4E00) to 龿 (U+9FFF) inclusive.
     */
    private fun isCjkFirst(s: String): Boolean {
        if (s.isEmpty()) return false
        val cp = s[0].code
        return cp in 0x4E00..0x9FFF
    }
}
