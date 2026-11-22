package com.tvlive.tv.update

/**
 * 从 Release 附件里挑正确的安装包。
 *
 * 这里的 historically bug：只按 `-release.apk` 后缀匹配，而私有包
 * `TVLive-v2.1.1-private-release.apk` 也满足该后缀。GitHub 上私有资产
 * 还排在公开资产前面，导致**公开包用户会下载到含预置订阅的私有包**。
 * 现在按构建类型严格区分，任何一端都不可能串包。
 */
internal object UpdateAssetPicker {

    data class Asset(val name: String, val url: String, val size: Long)

    private const val APK_SUFFIX = "-release.apk"
    private const val PRIVATE_MARKER = "-private-"

    /**
     * @param privateBuild 是否为私有包构建（BuildConfig.INCLUDE_PRIVATE_SUBS）
     * @return 匹配到的附件；没有安全可用的附件时返回 null（宁可不更新）
     */
    fun pick(assets: List<Asset>, privateBuild: Boolean): Asset? =
        assets.firstOrNull {
            it.name.endsWith(APK_SUFFIX) &&
                it.name.contains(PRIVATE_MARKER) == privateBuild &&
                it.url.isNotBlank()
        }
}
