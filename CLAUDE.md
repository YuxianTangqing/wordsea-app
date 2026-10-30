# wordsea-app · 四海书盟原生 APP

「手机APP」路线的承接工程（2026-10-29 站主拍板「原生定稿」）：**纯 Kotlin + View 体系**，
同包名 `com.sihaishumeng.app` + 同 jks（versionCode 接轻壳 14/1.1.9 继续起 15/2.0.0）＝
以「升级版」身份覆盖安装轻壳用户，cookie/localStorage 登录态无损迁移。轻壳仓
`../android-wordsea-shell` 冻结保留（1.x 停更，勿再改动）。
路线总计划：`C:\Users\pujie\.claude\plans\b-client-id-7a3a27dff0d04936-qq-app-glittery-axolotl.md`
（五批路线/复刻度盘点/四家登录架构，2026-10-29 站主批准）。

## 分期（批②=本仓起线，2026-10-30）

- **批② 骨架+基建移植（已落）**：轻壳 817 行 MainActivity 按 core 分层拆为四控制器，
  行为与壳 1.1.9 严格等价（单 Activity+单 WebView 恒指线上站）。
  - 2026-10-30 真机验收修复：`setVolumePaging` 桥方法曾编译成**自递归**（构造参数
    属性与方法同名，Kotlin 解析「同名函数优先于 invoke 约定」）→ 阅读器挂载即
    StackOverflowError → 网页 error.tsx 死循环（壳正常因 inner class 直写字段）。
    已改名 `onVolumePagingChange` 根治（a9fdde8）；**桥方法凡经 lambda 间接层必查
    同名自调用**，诊断法=雷电装同签名调试包（临时 `setWebContentsDebuggingEnabled`）
    + `window.__wsShell` 换记录型 Proxy（桥对象本身只读不可包，window 引用可换）。
  - 2026-10-30 二验「翻页模式音量键失效」：**原生侧无责**（拦截/转发全链 CDP 实证
    正常，音量指数不变=确实吞键）。根因=web 仓手机设置面板僵尸态：面板内嵌手机
    浮层，翻页/滚动/中屏点按收浮层只 `setChromeOpen(false)` 不关 `settingsOpen`
    → `chapterNavAllowed`/`paused` 恒拦音量键/空格/←→（手机无 Esc 可解）。旧壳
    「无问题」是错觉（翻页设置早已在 localStorage，没经设置舞步）。修复在
    wordsea 仓 ReaderShell（浮层收起一并复位面板态），随下窗部署生效；APP 无需
    重装。归因法：真按键 vs 手动调 handler 对拍→Escape 解卡实验→逐步舞步复现。
- 批③ 四家登录：`social/` 四适配器（微信 SendAuth/QQ tauth/支付宝 OpenAuthTask/
  B站 WebView 授权页沿用旧网页 client_id）+ web 仓新端点 `/api/auth/app-social/sign-in`。
- 批④ T1 原生页：`ui/` 18-20 页（书架/书库/详情/论坛/搜索/通知/签到/我的）+
  `core/web` 路由表非白名单一律 WebView 兜底。
- 批⑤ v2.0 首发：服务器双名 APK + web 仓 site.ts 版本**与 APK 上传同批**；壳仓归档。

## 铁律

- **桥契约冻结**：`__wsShell` 8 方法签名与行为逐字保留、回调
  `__wsKeyboard/__wsTtsEvent/__wsVolumeKey` 不动——web 层零改动同时适配轻壳与本仓。
  **UA 尾标 `SiHaiShuMengShell/1.1` 不动**（代际标识；web 判 shell-latest 靠桥版本号）。
- **core 层纯 Kotlin 解耦**：`core/*` 与 UI 解耦、只经构造注入宿主（Activity/回调）——
  目标是 iOS/KMP 留门，未来整包抽 `:core`；业务逻辑禁止写进 MainActivity。
- **恒指线上正式站**：SITE_URL 不读环境变量；**零密钥零凭据**（SDK appid 等凭证
  只进 web 仓服务器 .env，本仓不落任何 secret；批③ 的 appid 属公开标识可写代码）。
- 站外拦截/scheme 黑洞/恒桌面 UA/SwipeRefreshLayout mTarget（XML swipe 空容器+
  WebView 代码创建+只 removeView）/键盘盖页第三态/缓存 360MB 自清/AGP 勿升 9.x
  ——六条铁律全文见轻壳仓 CLAUDE.md 与 `core/SiteConfig.kt` 注释，语义原样。
- **发布红线**：批⑤ 前不上传服务器 APK（site 版本仍 1.1.9，内测直装防壳用户被
  自更新链带进半成品）；出包必 `apksigner verify --print-certs` 对指纹
  （历史六代同纹 `34bfed46…0711`，不符=debug 签名包禁止流出）。
- 提交信息末行 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- GitHub 私有仓（建仓后在此记名）；推送挂代理一次性 `git -c http.proxy=… push`。

## 出包（底座同轻壳，2026-09-21 实证缓存）

```
export JAVA_HOME="C:\Users\pujie\Desktop\WordSea\build-tools\jdk17"
export ANDROID_HOME="C:/Users/pujie/Desktop/WordSea/build-tools/android-sdk"
cd android && ./gradlew.bat assembleRelease
```
产物 `app/build/outputs/apk/release/app-release.apk`（批⑤ 起按轻壳「出包四步」走：
版本递增→双名上传→site.ts ?v= 同步→真机回归）。签名件 gitignore（keystore.properties+
app/sihaishumeng-release.jks，从仓外 `APP签名钥匙\` ASCII 副本拷入）。

## 验收

真机（站主安卓）覆盖安装轻壳 1.1.9 → 登录态保留 + 站内 /app 页显「已是最新版本
2.0.0」（桥分支生效）；雷电模拟器烟测（adb connect 127.0.0.1:5555，-s 指定）。
批②验收清单：开屏/站点渲染/返回键两段/下拉刷新/错误页三钮/外跳横幅/音量键翻页/
听书 TTS/键盘盖页/下载链 guard（点 ?v=1.1.9 链 Toast「已是最新」不下载）。
