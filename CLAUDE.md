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
- **批③ 四家登录（2026-10-30 落地，验收待 Secret/真机）**：
  - `core/api/`：ApiClient（OkHttp 单例，Origin/Referer 固定头）+ WebViewCookieJar
    （OkHttp↔CookieManager 桥=登录态三处共享：原生收 Set-Cookie → CookieManager →
    长尾 WebView 自动带）。
  - `social/`：SocialAuthController 编排 + WechatAuth（SendAuth，state 回校）+
    QqAuth（tauth，IUiListener **成员持有**防弱引用丢回调+onActivityResult 必回灌）+
    BiliAuthActivity（零 SDK——/sign-in/social 拿 url 后 WebView 走完整网页回调链，
    嗅探「站内非 /api/auth/ 页」即收）+ AlipayAuth（官方 SDK **Maven 分发**
    `com.alipay.sdk:alipaysdk-android:15.8.42`——原「极简版 AAR 手动下载」待办
    作废：15.8.09 起官方弃手动 AAR，全量仅 460KB、R8 后 APK 仅 +127KB；契约=
    `execute(scheme, BizType.AccountAuth, bizParams{url=authweb…auth_type=PURE_OAUTH_SDK},
    callback, true)`，回调码 OK=9000/NOT_INSTALLED=4001/Duplex=5000，Bundle 直取
    `auth_code`；回跳接收器 AlipayResultActivity 在 AAR manifest 自带（exported
    显式组件回跳，不占 sihaishumeng scheme）——商户侧零 manifest 配置）。
  - `wxapi/WXEntryActivity`：微信回跳硬契约（包名 {applicationId}.wxapi、exported、
    无 intent-filter——错一字回调静默不达）。
  - ~~`ui/LoginActivity`：原生登录屏~~（**批④W 2026-10-30 退役**：站主裁定「用
    网页的登录UI」——登录页=网页本尊（/auth/login 不再接管，主 WebView 直接渲染），
    四家按钮经桥 `__wsShell.socialLogin(provider, optionsJson, callbackName)` 拉原生
    SDK，结果 JSON 经 `window[callbackName](json)` 回呼网页；QQ onActivityResult
    透传随宿主迁 MainActivity。恢复路径=git 历史 bf954df。）
  - QQ open-sdk=aliyun public 代理的 `com.tencent.tauth:qqopensdk:3.53.0`（JCenter
    遗物，Central 无；2021 lastUpdated）——真机若遇 2024-06 平台政策不兼容 → 换
    wiki.connect.qq.com 手动 open_sdk_3.5.17_lite.jar（API 面兼容）。
  - 服务端（wordsea 仓批③）：`/api/auth/app-social/sign-in`（微信 unionid 硬必需/
    QQ client_id 强制+unionid 反查/支付宝 user_id）+ QQ unionid 一次性迁移脚本
    `m-app-qq-unionid-backfill.mjs`（解存量 token 回填 Account.unionid，幂等）+
    网页流惰性回填（qq-provider，带回填冲突守卫防双号 split-brain）。
  - ⭐坑记：Kotlin 块注释**可嵌套**——注释里写「/api/auth/*」会多开一层注释致
    Unclosed comment（BiliAuthActivity 实录）。
- ~~批④ T1 原生页~~（2026-10-30 真机否决「UI 丑」→ 深夜探讨会站主定稿**网页为主+
  原生骨架**）：51 文件/5161 行封存于 git tag `batch4-native`（复活=cherry-pick 该
  tag；苹果 4.2 拒纯壳时的 Plan B）。主线回退 bf954df 后批④W 落地=**APP 零原生 UI**
  （原生只留能力层：四家登录 SDK+自更新+桥四件+Cookie 三通）。
- 批⑤ v2.0 首发：服务器双名 APK + web 仓 site.ts 版本**与 APK 上传同批**；壳仓归档。

## 铁律

- **桥契约冻结**：`__wsShell` 8 方法签名与行为逐字保留、回调
  `__wsKeyboard/__wsTtsEvent/__wsVolumeKey` 不动——web 层零改动同时适配轻壳与本仓。
  批④W 增第 9 方法 `socialLogin`（登录页网页化；增量不在冻结列，老壳无此方法=web
  特征探测回落网页 OAuth）。**UA 尾标 `SiHaiShuMengShell/1.1` 不动**（代际标识；
  web 判 shell-latest 靠桥版本号）。
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
app/sihaishumeng-release.jks，从仓外 `APP签名钥匙\` ASCII 副本拷入）。local.properties
（sdk.dir 指向上行 android-sdk）同 gitignore，2026-10-30 曾失踪致 SDK location 报错——
重建即可。

## 验收

真机（站主安卓）覆盖安装轻壳 1.1.9 → 登录态保留 + 站内 /app 页显「已是最新版本
2.0.0」（桥分支生效）；雷电模拟器烟测（adb connect 127.0.0.1:5555，-s 指定）。
批②验收清单：开屏/站点渲染/返回键两段/下拉刷新/错误页三钮/外跳横幅/音量键翻页/
听书 TTS/键盘盖页/下载链 guard（点 ?v=1.1.9 链 Toast「已是最新」不下载）。
