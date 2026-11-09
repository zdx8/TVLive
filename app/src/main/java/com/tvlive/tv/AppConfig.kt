package com.tvlive.tv

/**
 * 电视直播 — application-level constants。
 *
 * 公开构建（仓库默认）：无预置订阅，更新检查走 GitHub Releases，
 * 仅在用户 MENU 手动「检查更新」时请求。
 *
 * 私有包（`-PprivateSubs=true` 构建，见 scripts/build-private-apk.sh）：
 * 启动 seed 会读取 assets/private-subscriptions.json（gitignore，
 * 仓库里不存在）预置作者自己的订阅；URL 永不进仓库。
 */
object AppConfig {

    /**
     * 无偏好时的兜底订阅地址。公开包恒为空字符串——首次使用由
     * 添加订阅流程（扫码 / 手动输入）引导；私有包的预置订阅在
     * SubscriptionStore seed 时写入偏好，同样不会落到这里。
     */
    const val DEFAULT_SUBSCRIPTION_URL = ""

    /**
     * HTTP timeouts for subscription refresh (separate from the
     * per-stream HTTP timeouts in PlayerConfig).
     */
    const val SUBSCRIPTION_CONNECT_TIMEOUT_S = 10L
    const val SUBSCRIPTION_READ_TIMEOUT_S = 20L

    /** 一个订阅源：菜单里只显示 [name]，URL 不上屏。 */
    data class Subscription(val name: String, val url: String)

    /** 本地偏好文件名（订阅仓库 / 当前订阅源等）。 */
    const val PREFS_NAME = "hftv_home"

    /** 当前选中的订阅源 URL，存在 SharedPreferences 里（key 见下）。 */
    const val PREF_ACTIVE_SUBSCRIPTION = "active_subscription"

    /**
     * 自更新清单：GitHub releases/latest —— UpdateManager 识别
     * api.github.com 地址后按 Release 解析（tag=版本、*-public-release.apk
     * 附件=安装包、release body=更新说明）。发布一个新 Release 即完成
     * 一次推送。只在用户 MENU 手动「检查更新」时请求；失败静默提示。
     */
    const val UPDATE_MANIFEST_URL = "https://api.github.com/repos/zdx8/TVLive/releases/latest"
}
