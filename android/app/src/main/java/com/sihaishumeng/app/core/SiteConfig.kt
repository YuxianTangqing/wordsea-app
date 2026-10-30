package com.sihaishumeng.app.core

/**
 * 站点与壳行为常量（批②自轻壳 MainActivity.kt companion 原样迁出，2026-10-30）。
 *
 * 铁律（出处见壳仓 CLAUDE.md，逐条沿用）：
 * - 恒指线上正式站，不读环境变量、不带本地回退——App 里跑的就是网站本身。
 * - 零密钥零凭据。
 * - 站外拦截：白名单（本站+授权大域）外 http(s) 送系统浏览器；授权大域=M188c
 *   （*.qq.com/.alipay.com/.bilibili.com + github.com——授权链中途会跳同公司其他
 *   子域，枚举追不完才改整域放行）。改清单须站主知情。
 * - 恒桌面 UA（M188f）。UA 尾标 1.1 是「代际标识」非版本号（改它=QQ 授权链重测
 *   面）。具体版本经 __wsShell JS 桥暴露，web 侧先查桥、无桥再回退尾标判旧壳。
 */
object SiteConfig {
    const val SITE_URL = "https://www.sihaishumeng.com"

    // M188：本站域 + 授权大域（改清单须站主知情）
    val SITE_HOSTS = setOf("www.sihaishumeng.com", "sihaishumeng.com")
    val OAUTH_HOSTS = setOf("github.com")
    val OAUTH_SUFFIXES = listOf(".qq.com", ".alipay.com", ".bilibili.com")

    // 批③ 四家登录 appid（公开标识可硬编码——AppKey/Secret 只进 web 仓服务器 .env，
    // 本仓零密钥铁律）。B站零 appid：原生走网页流（client_id 在服务端）。
    const val WECHAT_APP_ID = "wxd3b4d2acfec510e2"
    const val QQ_APP_ID = "1905824730"
    const val ALIPAY_APP_ID = "2021007110603730" // AAR 落地后启用（build.gradle 注释位）

    // 批③ 原生登录屏接管路由（批④ 正式路由表前哨；LoginActivity 内置「网页版登录」
    // 回退原址，行为可逆）
    const val NATIVE_LOGIN_PATH = "/auth/login"

    // M188f 恒桌面 UA。尾标 SiHaiShuMengShell/1.1 是「代际标识」非版本号（改它=QQ
    // 授权链重测面），Kotlin 壳恒 1.1。M-APP15 起具体版本经 __wsShell JS 桥暴露
    // （见 attachWebView），web 侧先查桥、无桥再回退尾标判旧壳——不要再动这个数字。
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36 SiHaiShuMengShell/1.1"

    const val EXIT_WINDOW_MS = 2000L

    // WebView 缓存自清阈值（M-APP14e 定 48MB → M-APP14f 站主拍板 360MB：速度
    // 优先大预算，自清退化为防失控安全网——Chromium 默认配额数百 MB 且无公开
    // API 限配额，此阈只为兜住极端膨胀）。启动时后台扫描 cacheDir+app_webview
    // （雷电 Android9 实测 HTTP cache 落 cacheDir/WebView/…，SW/localStorage 落
    // app_webview——两处都计入判断），超阈即 clearCache(true)：雷电实证清掉
    // cacheDir 93% 且 SW 缓存〔断网回访，站点侧 FIFO 80 已自限〕与 localStorage
    // 〔登录态/主题〕分毫未伤。代价=下次冷载重新下载。
    const val CACHE_TRIM_THRESHOLD = 360L * 1024 * 1024
}
