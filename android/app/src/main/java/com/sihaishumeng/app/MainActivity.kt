package com.sihaishumeng.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.sihaishumeng.app.core.SiteConfig
import com.sihaishumeng.app.core.bridge.ShellBridge
import com.sihaishumeng.app.core.device.KeyboardInsetController
import com.sihaishumeng.app.core.device.TtsController
import com.sihaishumeng.app.core.update.SelfUpdateController
import com.sihaishumeng.app.core.web.WebViewRouter
import com.sihaishumeng.app.ui.LoginActivity
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * 四海书盟原生 APP（wordsea-app，2026-10-30 批②起线）。
 *
 * 批②=轻壳 1.1.9（android-wordsea-shell）的「结构化重写」：817 行单文件按 core
 * 分层拆为 bridge/web/update/device 四控制器，行为与壳 1.1.9 严格等价——单
 * Activity + 单 WebView 恒指 https://www.sihaishumeng.com 。同包名
 * com.sihaishumeng.app + 同 jks（versionCode 接 15/2.0.0）＝以「升级版」身份
 * 覆盖安装轻壳用户，cookie/localStorage 登录态无损迁移。
 *
 * 桥契约冻结：__wsShell 8 方法与回调（__wsKeyboard/__wsTtsEvent/__wsVolumeKey）
 * 逐字保留——web 层零改动同时适配轻壳与本工程；UA 尾标 SiHaiShuMengShell/1.1
 * 不动（代际标识，web 侧判 shell-latest 靠桥版本号而非尾标）。
 *
 * 铁律全文见本仓 CLAUDE.md 与 core/SiteConfig.kt 注释（恒线上站/零密钥/站外拦截/
 * scheme 黑洞/恒桌面 UA/SwipeRefreshLayout mTarget/键盘盖页第三态/缓存 360MB）。
 * 后续批次：批③ social/ 四家登录 SDK、批④ ui/ 原生页（core 层纯 Kotlin 解耦，
 * 为 iOS/KMP 留门——勿把业务逻辑写进本 Activity）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 批③：LoginActivity「网页版登录/注册/找回密码」回退——singleTask 复用本实例定向加载 */
        const val EXTRA_OPEN_URL = "openUrl"
    }

    // ── 状态五态（壳 1:1）+ 返回键时间戳 ──
    private var canGoBack = false
    private var progress = 0
    private var loadedOnce = false
    private var errorActive = false
    private var extHost: String? = null
    private var lastBackAt = 0L

    // ── 批③R /auth/login 全导航形态接管（真机报障根因②返修）──
    /** LoginActivity「网页版登录」显式回退态：置起期间接管让路（网页登录页内的
     *  302 失败回跳等不被劫回原生屏），离开 auth 前缀路径即自动恢复接管。 */
    private var webLoginFallback = false
    /** 原生登录屏在前（loginLauncher 已发未收）——防重复拉起。 */
    private var nativeLoginShowing = false

    // ── M247 音量键翻页：web 侧（阅读器挂载且听书未播）经 __wsShell.setVolumePaging(true)
    //    声明接管才拦键；默认 false＝旧网页/非阅读器页面音量键归系统。@Volatile：
    //    JS 桥 binder 线程写、主线程 onKeyDown 读 ──
    @Volatile
    private var volumePaging = false

    private lateinit var router: WebViewRouter
    private lateinit var ttsController: TtsController
    private lateinit var keyboardInset: KeyboardInsetController
    private lateinit var selfUpdate: SelfUpdateController

    private lateinit var rootLayout: ViewGroup
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingOverlay: View
    private lateinit var errorOverlay: View
    private lateinit var errorBackBtn: View
    private lateinit var banner: View
    private lateinit var bannerText: TextView

    // 文件上传（改头像等）：回调置空防 Activity 泄漏（onDestroy 兜底 null）
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = result.data?.let { data ->
                ArrayList<Uri>().apply {
                    data.data?.let { add(it) }
                    data.clipData?.let { cd -> for (i in 0 until cd.itemCount) add(cd.getItemAt(i).uri) }
                }
            }
            fileChooserCallback?.onReceiveValue(uris?.toTypedArray()?.takeIf { it.isNotEmpty() })
            fileChooserCallback = null
        }

    // 批③：登录屏 RESULT_OK=已登入——重载当前页让登录态立即可见（cookie 在共享
    // CookieManager，重载即带）
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            nativeLoginShowing = false
            if (result.resultCode == RESULT_OK && this::webView.isInitialized) webView.reload()
        }

    /** 主线程 JS 注入唯一出口（各控制器经此回呼；webView 未初始化=静默丢弃，
     *  与壳 1.1.9 的 isInitialized 守卫同语义） */
    private fun evalJs(js: String) {
        if (this::webView.isInitialized) webView.evaluateJavascript(js, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = WebViewRouter(this)
        ttsController = TtsController(this) { js -> evalJs(js) }
        keyboardInset = KeyboardInsetController(
            webProvider = { if (this::webView.isInitialized) webView else null },
            evalJs = { js -> evalJs(js) },
        )
        selfUpdate = SelfUpdateController(this, this)
        // targetSdk 36 强制 edge-to-edge：自管 insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        keyboardInset.install(rootLayout)
        setupSwipe()
        attachWebView()
        updateUi()
        trimCacheIfNeeded()
    }

    /** 启动时后台量 WebView 数据足迹（cacheDir+app_webview），超阈清 HTTP 缓存。
     *  clearCache 须在主线程且有 WebView 实例（正常流程 attachWebView 先于此跑，
     *  WebView(this) 仅极端时序兜底）；Activity 若已销毁则跳过。 */
    private fun trimCacheIfNeeded() {
        thread(name = "cache-trim") {
            val size = sequenceOf(cacheDir, File(applicationInfo.dataDir, "app_webview"))
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown() }
                .filter { it.isFile }
                .sumOf { it.length() }
            if (size > SiteConfig.CACHE_TRIM_THRESHOLD && !isDestroyed) {
                runOnUiThread {
                    if (!isDestroyed) {
                        (if (this::webView.isInitialized) webView else WebView(this)).clearCache(true)
                    }
                }
            }
        }
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.root)
        swipe = findViewById(R.id.swipe)
        progressBar = findViewById(R.id.progressBar)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        errorBackBtn = findViewById(R.id.btnBack)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.bannerText)
        findViewById<View>(R.id.btnRetry).setOnClickListener {
            errorActive = false; loadedOnce = false; progress = 0
            updateUi(); webView.reload()
        }
        errorBackBtn.setOnClickListener {
            errorActive = false
            updateUi(); webView.goBack()
        }
        findViewById<View>(R.id.btnHome).setOnClickListener {
            errorActive = false; loadedOnce = false; progress = 0
            updateUi()
            // 保持壳的 replace 语义（不压历史栈）
            evalJs("location.replace(${JSONObject.quote(SiteConfig.SITE_URL)});")
        }
        findViewById<View>(R.id.bannerClose).setOnClickListener {
            extHost = null; updateUi()
        }
    }

    /** 下拉刷新（M-APP14 由注入式 JS 换原生 SwipeRefreshLayout）：
     *  setOnChildScrollUpCallback 是官方为宿主定制滚动判定的 API（按需求值，无监听器
     *  状态机）；页面滚动装在 inner overflow div 时有盲区——与 JS 版查 document 滚动
     *  是同一个盲区，非回归。reload() 不会自动停转圈：onPageFinished/onReceivedError 停。 */
    private fun setupSwipe() {
        swipe.setColorSchemeResources(R.color.brand)
        swipe.setProgressBackgroundColorSchemeResource(R.color.paper)
        swipe.setOnRefreshListener { webView.reload() }
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
    }

    /** WebView 创建+装配（onRenderProcessGone 崩溃重建复用同函数）——统一装配口：
     *  桥注入是本函数内建动作（防漏挂桥让 web 误判「旧壳待更新」） */
    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView() {
        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#F5F0E6")) // 米白底防白闪（壳 1.1.9 同款）
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // 站点依赖 localStorage（主题/阅读设置）
            setSupportMultipleWindows(false) // 必须显式设（引擎默认 false，显式防漂移）
            userAgentString = SiteConfig.DESKTOP_UA
            // 引擎默认层照搬壳（防行为漂移）
            builtInZoomControls = true // 桌面 UA 下页面可捏合缩放
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true) // OAuth 回调 cookie 落壳
        }
        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createChromeClient()
        webView.setDownloadListener(selfUpdate.createDownloadListener())
        // __wsShell 版本桥：每个新文档开始前即注入页面 JS 语境（无时序问题）；
        // render 崩溃重建走同一装配口自动恢复
        webView.addJavascriptInterface(
            ShellBridge(this, ttsController, keyboardInset) { enabled -> volumePaging = enabled },
            "__wsShell",
        )
        // 精确移除旧实例（onRenderProcessGone 重建路径）——绝不用 removeAllViews：
        // 那会误删 SwipeRefreshLayout 自管理的 circleView 指示器，且库的 mTarget 是
        // 「首次发现即冻结」，整批换子后新 WebView 将无人测量（0×0 白屏根因）。
        if (this::webView.isInitialized && webView.parent === swipe) {
            swipe.removeView(webView)
        }
        swipe.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        webView.loadUrl(SiteConfig.SITE_URL)
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // 批③ 原生登录屏接管站内 /auth/login（批④ 正式路由表前哨）。只拦「导航
            // 进入」：当前已在登录页（网页版登录回退态）不再拦，站内失败重定向不会
            // 把用户再弹回原生屏。loadUrl 直载不经本函数——LoginActivity 的
            // 「网页版登录」回退不受影响。批③R：软导航入口另由
            // maybeTakeOverLogin 覆盖（见 doUpdateVisitedHistory）。
            val host = request.url.host?.lowercase()
            val path = request.url.path ?: ""
            if (host != null && host in SiteConfig.SITE_HOSTS && path == SiteConfig.NATIVE_LOGIN_PATH) {
                if (webLoginFallback || nativeLoginShowing) return false
                val currentPath = view.url?.let { runCatching { Uri.parse(it).path }.getOrNull() }
                if (currentPath != SiteConfig.NATIVE_LOGIN_PATH) {
                    nativeLoginShowing = true
                    loginLauncher.launch(Intent(this@MainActivity, LoginActivity::class.java))
                    return true
                }
                return false
            }
            return router.routeUrl(view, request.url) { h ->
                extHost = h
                updateUi()
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // 任何新导航开始即清错误态（返回上一页/重试/回首页后遮罩即时消失）
            errorActive = false
            // 批③R：兜 302 落地等不触发 shouldOverrideUrlLoading 的整页加载形态
            maybeTakeOverLogin(url, isReload = false, bounce = true)
            updateUi()
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress = 100
            loadedOnce = true
            canGoBack = view.canGoBack()
            swipe.isRefreshing = false
            CookieManager.getInstance().flush()
            updateUi()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // SPA 客户端路由 pushState 挂点——canGoBack 跟随路由历史
            canGoBack = view.canGoBack()
            // 批③R：站内「登录」入口全是 Next Link/router.push 软导航（不过
            // shouldOverrideUrlLoading）——接管必须挂在这里才全覆盖
            maybeTakeOverLogin(url, isReload, bounce = true)
            updateUi()
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            // 只对主框架弹错误页；不过滤则站内任一 iframe/图片 404 都弹错误页=明显劣化
            if (request.isForMainFrame) {
                errorActive = true
                swipe.isRefreshing = false
                updateUi()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // 旧引擎（<Chrome 111）渲染进程崩溃时原生默认只留白屏——移除旧实例、重建、重载
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            errorActive = false; loadedOnce = false; progress = 0
            attachWebView()
            updateUi()
            return true
        }
    }

    /**
     * 批③R /auth/login 全导航形态接管（真机报障返修：B站崩溃重开后点导航栏「登录」
     * 渲染出网页版登录页——根因=接管只挂 shouldOverrideUrlLoading，而站内登录入口
     * 全是软导航 pushState 不过该回调）。软硬导航（doUpdateVisitedHistory/
     * onPageStarted）统一走本口：命中即开原生屏；软导航页面已驶入无法取消，bounce
     * 把主 WebView 弹回首页——登录成功 reload 即见登录态首页，背后不残留网页登录页。
     * 三守卫：isReload（下拉刷新/登录成功 reload 不劫）/webLoginFallback（「网页版
     * 登录」显式回退态）/nativeLoginShowing（屏已在前防重复拉起）。
     */
    private fun maybeTakeOverLogin(url: String?, isReload: Boolean, bounce: Boolean) {
        val u = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        val path = u.path ?: return
        if (path == SiteConfig.NATIVE_LOGIN_PATH) {
            if (isReload || webLoginFallback || nativeLoginShowing) return
            if (u.host?.lowercase() !in SiteConfig.SITE_HOSTS) return
            nativeLoginShowing = true
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
            if (bounce) evalJs("location.replace(${JSONObject.quote(SiteConfig.SITE_URL)});")
        } else if (!path.startsWith("/auth/")) {
            // 离开 auth 前缀（登录/注册/找回）＝回退语境结束，接管自动恢复。
            // 停留在 auth 前缀内（如网页登录页跳注册再返回）不打断回退态。
            webLoginFallback = false
        }
    }

    private fun createChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
            updateUi()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                fileChooserCallback = null
                false
            }
        }
    }

    // ── M247 音量键翻页：接管态吞掉音量键转交网页（window.__wsVolumeKey(dir)；
    //    down=+1 下一页 / up=-1 上一页）。repeatCount>0 不连发（与 web 键盘 e.repeat
    //    同口径）；ACTION_UP 一并吞防个别机型残留音量 UI/提示音。非接管（含听书让
    //    位期）全放行系统调音量 ──

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumePaging && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event != null && event.repeatCount == 0) {
                val dir = if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
                evalJs("window.__wsVolumeKey && window.__wsVolumeKey($dir);")
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumePaging && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ── 返回键两段（M189e：错误态也计入历史判断） ──

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (canGoBack && webView.canGoBack()) {
            errorActive = false
            updateUi()
            webView.goBack()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < SiteConfig.EXIT_WINDOW_MS) {
            lastBackAt = 0
            // ≤Android11 移后台不杀（WebView 保活），失败才 finish；12+ 走默认
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                if (!moveTaskToBack(false)) @Suppress("DEPRECATION") super.onBackPressed()
            } else {
                @Suppress("DEPRECATION") super.onBackPressed()
            }
        } else {
            lastBackAt = now
            Toast.makeText(this, R.string.back_exit_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        // M189e 铁律：退后台立刻落盘 cookie——「登录完秒杀 APP」会话只在内存的坑
        CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 批③：LoginActivity 链接区回退（singleTask 复用本实例定向加载）
        intent.getStringExtra(EXTRA_OPEN_URL)?.let { url ->
            // 批③R：目标=登录页（「网页版登录」回退）先置回退标记再加载——
            // loadUrl 与其后续导航不被原生接管劫回；离开 auth 前缀自动恢复。
            webLoginFallback =
                runCatching { Uri.parse(url).path }.getOrNull() == SiteConfig.NATIVE_LOGIN_PATH
            if (this::webView.isInitialized) webView.loadUrl(url)
        }
    }

    override fun onResume() {
        super.onResume()
        // M-APP15：后台完成的更新包在此补弹安装器（后台 startActivity 被静默拦的兜底）
        selfUpdate.tryStartInstall()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        selfUpdate.destroy()
        ttsController.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    // ── 可见性推导（= 壳 render 的唯一出口） ──

    private fun updateUi() {
        progressBar.progress = progress
        progressBar.visibility = if (!errorActive && progress < 100) View.VISIBLE else View.GONE
        loadingOverlay.visibility =
            if (!errorActive && !loadedOnce && progress < 100) View.VISIBLE else View.GONE
        errorOverlay.visibility = if (errorActive) View.VISIBLE else View.GONE
        errorBackBtn.visibility = if (canGoBack) View.VISIBLE else View.GONE
        val host = extHost
        if (host != null) {
            bannerText.text = "${getString(R.string.banner_opened_prefix)}$host"
            banner.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
        }
    }
}
