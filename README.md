# 电视直播（TVLive）

Android TV 上的 IPTV / 直播播放器。M3U 订阅、遥控器全键盘操作、手机扫码添加订阅、
应用内自更新，APK 约 4.4 MB。

- 包名：`com.tvlive.tv`
- 系统：Android TV 6.0+（兼容 TV 13 / 14 / 15 / 16）
- 播放内核：[Media3 / ExoPlayer](https://github.com/androidx/media)（Apache 2.0）
- 许可证：[Apache 2.0](LICENSE)

## 下载

到 [Releases](https://github.com/zdx8/TVLive/releases/latest) 下载
`TVLive-v2.0.0-release.apk`，侧载（U 盘 / adb / 文件管理器）安装即可。

已安装的用户不联网也能用：更新**只在 MENU →「检查更新」手动触发**（不后台自动检查），
有新版本会弹出更新卡片，确认后自动下载并交给系统安装器。

> 国内网络访问 GitHub 可能较慢或失败——检查失败会提示已是最新，不影响使用。

## 功能

- ✅ M3U / M3U8 订阅（远程地址 / 本地文件 / 单条流直连）
- ✅ **手机扫码添加订阅**：电视亮二维码，手机扫码在网页里粘贴地址和名称，一键提交
- ✅ 订阅管理：多订阅切换、行内删除、订阅名自动识别（也可在扫码页手动命名）
- ✅ 频道列表抽屉（左键召唤，3 秒无操作自动隐藏）、长按 OK 收藏
- ✅ 数字键跳台（1-2-3 → 第 123 个频道）、D-pad 上下键静默切台
- ✅ 自动重连退避（1s/2s/4s，最多 3 次）+ 15s 无进度看门狗
- ✅ 错误连续失败自动跳下一频道；全部失败给出明确提示
- ✅ 应用内自更新（GitHub Releases → 下载 → 系统安装器）
- ✅ 返回键确认退出、边到边 UI（适配 Android 15+）

## 快速上手

1. 安装并打开 → 首次使用会自动弹出「添加订阅」；
2. 按遥控器 **MENU** 键 → 「＋ 添加订阅」→ 手机扫码；
3. 在手机网页里填**订阅名称**和 **M3U 地址**，提交；
4. 电视自动刷新频道并开始播放，之后打开 app 即直接看电视。

### D-pad 键位

| 键 | 频道列表关 | 频道列表开 |
|---|---|---|
| OK / 中心 | 播放/暂停 | 选台（高亮 → 切到该频道） |
| ← (LEFT) | 召唤频道列表 | 收起 |
| ↑ (UP) | 上一个频道 | 光标上移（画面不变） |
| ↓ (DOWN) / → (RIGHT) | 下一个频道 | 光标下移（画面不变） |
| Back | 退出确认 | 收起 |
| 数字 0-9 | 跳到第 N 个频道 | 同左 |

长按 OK：收藏 / 取消收藏当前频道（列表里出现 ★）。

## 自行构建

```bash
# 环境要求：JDK 17 + Android SDK（API 23-35，build-tools 34+）
cp .env.sh.example .env.sh && $EDITOR .env.sh   # 填 SDK/JDK 路径
source .env.sh

./gradlew :app:assembleRelease                  # 公开版 → app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest                # 单元测试
```

Release 构建需要签名文件：复制 `keystore.properties.example` 为 `keystore.properties`
并填写 `app/signing/` 下 keystore 的路径与密码（文件已 gitignore）。没有签名配置时
构建仍会产出 unsigned APK。

## 项目结构

```
TVLive/
├── app/src/main/java/com/tvlive/tv/
│   ├── AppConfig.kt               应用常量（订阅超时 / GitHub 更新端点等）
│   ├── pairing/                   扫码填源：PairingActivity / PairingServer / QrBitmap
│   ├── data/
│   │   ├── store/                 SubscriptionStore（订阅仓库）/ PlaylistNamer（自动取名）
│   │   ├── repository/            ChannelRepository / SubscriptionRefresher
│   │   ├── m3u/                   M3U 解析（编码兜底 / EXTVLCOPT）
│   │   ├── model/ source/         Room 实体与 DAO、自然排序、订阅合并
│   ├── player/                    PlayerController / PlaybackActivity / ChannelDrawer
│   ├── ui/                        磁贴主页 / 频道列表 / 数字键缓冲
│   ├── update/                    UpdateManager（NAS 清单 + GitHub Releases 双模式）
│   └── widget/                    MaxHeightScrollView 等通用控件
├── app/src/main/assets/pairing.html   扫码页手机端网页
├── docs/                          设计文档
├── scripts/                       打包 / 诊断 / 资源生成脚本
└── build.gradle.kts               AGP 8.6.1 · Kotlin 2.0.21 · Media3 1.7
```

## 设计说明

- **扫码配对**：电视在局域网内起一个临时 HTTP 服务（一次性 token、5 分钟过期），
  手机扫码直连电视内置网页提交——数据不出局域网，详见
  [docs/扫码填源-设计方案.md](docs/扫码填源-设计方案.md)。
- **可靠性**：重连退避 + 播放看门狗 + 连续失败保护，直播流卡死自动恢复或跳台；
  订阅刷新采用原地合并，不丢收藏与播放历史。
- **自更新**：GitHub Releases 即更新清单——发一个 Release 并上传
  `TVLive-vX.Y.Z-release.apk` 即完成推送，app 手动检查时按 semver 比较版本。

## 许可证

**Apache 2.0** — 见 [LICENSE](LICENSE)。内嵌的 Media3 / ExoPlayer 由 Google 以
Apache 2.0 授权，可独立分发。

## 致谢

- 播放引擎 [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media)
- 二维码 [ZXing](https://github.com/zxing/zxing)（Apache 2.0）
