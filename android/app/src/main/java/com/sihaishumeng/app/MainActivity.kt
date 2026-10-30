package com.sihaishumeng.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
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
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.sihaishumeng.app.core.session.SessionStore
import com.sihaishumeng.app.core.update.SelfUpdateController
import com.sihaishumeng.app.core.web.NativeRoute
import com.sihaishumeng.app.core.web.NativeRouteTable
import com.sihaishumeng.app.core.web.WebViewRouter
import com.sihaishumeng.app.ui.LoginActivity
import com.sihaishumeng.app.ui.common.NativeNav
import com.sihaishumeng.app.ui.tab.BookshelfScreen
import com.sihaishumeng.app.ui.tab.ForumHomeScreen
import com.sihaishumeng.app.ui.tab.LibraryScreen
import com.sihaishumeng.app.ui.tab.TabScreen
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * 四海书盟原生 APP（wordsea-app，批②起线；批④ 五 tab 骨架+原生路由表泛化）。
 *
 * 批②=轻壳 1.1.9 的结构化重写（bridge/web/update/device 四控制器，行为等价）；
 * 批③=四家社交登录+/auth/login 原生接管；批③R=接管挂全导航形态+bounce 弹回+
 * 过渡遮罩；批④=「单 WebView+原生 tab 页共栖」：内容区〔swipe{WebView}｜原生 tab
 * 屏〕+ 底部五 tab 栏（书架/写作/小说库/社区/我的——写作/我的仍是 WebView tab），
 * maybeTakeOver 泛化为 NativeRouteTable 全站接管（bounce 三档：登录弹首页+待归位
 * 路径、tab 目标原位切、深页起 Activity，共用 takeoverCover 遮罩）。
 *
 * 铁律全文见本仓 CLAUDE.md 与 core/SiteConfig.kt 注释（恒线上站/零密钥/站外拦截/
 * scheme 黑洞/恒桌面 UA/SwipeRefreshLayout mTarget/键盘盖页第三态/缓存 360MB/
 * AppCompatActivity 必挂 AppCompat 族主题）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 批③：LoginActivity「网页版登录/注册/找回密码」回退——singleTask 复用本实例定向加载 */
        const val EXTRA_OPEN_URL = "openUrl"

        /** 批④：定向切 tab（NativeNav tab 目标回壳不绕 WebView） */
        const val EXTRA_TAB = "openTab"
        const val EXTRA_TAB_SEGMENT = "openTabSegment"
        const val TAB_SHELF = "shelf"
        const val TAB_STUDIO = "studio"
        const val TAB_LIBRARY = "library"
        const val TAB_FORUM = "forum"
        const val TAB_MINE = "mine"

        /** 深页/原生页 → WebView 长尾出口（阅读器/发帖/studio/文章/用户页全走此口） */
        fun openInWebView(context: Context, pathOrUrl: String) {
            val url = if (pathOrUrl.startsWith("http")) pathOrUrl else SiteConfig.SITE_URL + pathOrUrl
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        /** 原生页 → 站内任意地址（路由表命中起原生页，否则 WebView） */
        fun openNativeOrWeb(context: Context, pathOrUrl: String) {
            NativeNav.open(context, pathOrUrl)
        }
    }

    enum class Tab(val key: String) {
        SHELF(TAB_SHELF), STUDIO(TAB_STUDIO), LIBRARY(TAB_LIBRARY), FORUM(TAB_FORUM), MINE(TAB_MINE)
    }

    // ── 状态五态（壳 1:1）+ 返回键时间戳 ──
    private var canGoBack = false
    private var progress = 0
    private var loadedOnce = false
    private var errorActive = false
    private var extHost: String? = null
    private var lastBackAt = 0L

    // ── 批④ tab 状态机 ──
    /** 当前激活 tab（null=WebView 态无 tab 高亮：首页/长尾页） */
    private var activeTab: Tab? = null
    /** 原生 tab 屏（懒建常驻，保滚动与数据） */
    private val screens = mutableMapOf<Tab, TabScreen>()
    private lateinit var nativeHost: FrameLayout
    private lateinit var tabBarHost: FrameLayout
    private var tabButtons: List<Pair<Tab, LinearLayout>> = emptyList()

    // ── 批③R/批④ 接管体系 ──
    /** LoginActivity「网页版登录」显式回退态（auth 前缀内让路） */
    private var webLoginFallback = false
    /** 原生屏在前（防重复拉起；tab 目标不置此旗） */
    private var nativeScreenShowing = false
    /** 批③R-b 接管过渡遮罩（软导航驶入页渲染帧与原生屏落座之间的闪现） */
    private var takeoverCover = false
    /** WebView 侧最近一个非接管路径（bounce 弹回目标；冷启动=首页） */
    private var lastStableWebPath = "/"
    /** EXTRA_OPEN_URL 显式 WebView 请求的精确路径豁免（见异路径即清） */
    private var webFallbackPath: String? = null
    /** 登录档 bounce 弹首页后，成功归位的前进式目标（/me 类登录墙页防 302 死循环） */
    private var pendingPostLoginPath: String? = null

    // ── M247 音量键翻页（web 侧声明接管才拦键）──
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

    // 批③：登录屏 RESULT_OK=已登入——SessionStore 失效+WebView 重载（cookie 已共享）
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            nativeScreenShowing = false
            takeoverCover = false
            SessionStore.markLoginChanged()
            if (result.resultCode == RESULT_OK && this::webView.isInitialized) {
                val pending = pendingPostLoginPath
                pendingPostLoginPath = null
                if (!pending.isNullOrEmpty() && pending != "/" && pending != lastStableWebPath) {
                    // 前进式归位（登录墙页如 /me：弹首页期间已登录，此时加载即达目标）
                    webView.loadUrl(SiteConfig.SITE_URL + pending)
                } else {
                    webView.reload()
                }
            }
            updateUi() // 取消路径无后续页面事件兜底，遮罩必须在此显式撤
        }

    /** 主线程 JS 注入唯一出口（各控制器经此回呼） */
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        keyboardInset.install(rootLayout)
        setupSwipe()
        attachWebView()
        buildTabBar()
        handleIntent(intent)
        updateUi()
        trimCacheIfNeeded()
    }

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
        nativeHost = findViewById(R.id.nativeHost)
        tabBarHost = findViewById(R.id.tabBarHost)
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
            evalJs("location.replace(${JSONObject.quote(SiteConfig.SITE_URL)});")
        }
        findViewById<View>(R.id.bannerClose).setOnClickListener {
            extHost = null; updateUi()
        }
    }

    // ── 批④ 五 tab 栏（代码构建：icon+label，选中 brand 高亮）──

    private fun buildTabBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.paper))
            background = getDrawable(R.drawable.bg_banner)
        }
        val defs = listOf(
            Triple(Tab.SHELF, R.drawable.ic_tab_shelf, R.string.tab_shelf),
            Triple(Tab.STUDIO, R.drawable.ic_tab_pen, R.string.tab_studio),
            Triple(Tab.LIBRARY, R.drawable.ic_tab_books, R.string.tab_library),
            Triple(Tab.FORUM, R.drawable.ic_tab_forum, R.string.tab_forum),
            Triple(Tab.MINE, R.drawable.ic_tab_user, R.string.tab_mine),
        )
        tabButtons = defs.map { (tab, icon, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(7))
                isClickable = true
                isFocusable = true
                background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                setOnClickListener { selectTab(tab, null) }
            }
            val iconView = ImageView(this).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inkSoft))
            }
            item.addView(iconView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val labelView = TextView(this).apply {
                text = getString(label)
                textSize = 11f
                setTextColor(getColor(R.color.inkSoft))
            }
            item.addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            bar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tab to item
        }
        tabBarHost.removeAllViews()
        tabBarHost.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        renderTabBar()
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v + 0.5f).toInt()

    private fun renderTabBar() {
        tabButtons.forEach { (tab, item) ->
            val on = activeTab == tab && isNativeContentActiveFor(tab)
            val color = getColor(if (on) R.color.brand else R.color.inkSoft)
            (item.getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(color)
            (item.getChildAt(1) as TextView).setTextColor(color)
        }
    }

    /** 原生 tab 在前（非 WebView tab 高亮态） */
    private val nativeTabActive: Boolean get() = activeTab != null && activeTab != Tab.STUDIO && activeTab != Tab.MINE

    private fun isNativeContentActiveFor(tab: Tab): Boolean =
        tab != Tab.STUDIO && tab != Tab.MINE

    private fun screenFor(tab: Tab): TabScreen = screens.getOrPut(tab) {
        when (tab) {
            Tab.SHELF -> BookshelfScreen(this)
            Tab.LIBRARY -> LibraryScreen(this)
            Tab.FORUM -> ForumHomeScreen(this)
            else -> throw IllegalArgumentException("WebView tab 无原生屏: $tab")
        }
    }

    /** 切 tab：原生三 tab 原位换屏；写作/我的切 WebView 加载对应入口（前缀已符则复显） */
    fun selectTab(tab: Tab, segment: String?) {
        val previous = activeTab
        activeTab = tab
        if (tab == Tab.STUDIO || tab == Tab.MINE) {
            screens[previous]?.onHide()
            nativeHost.removeAllViews()
            swipe.visibility = View.VISIBLE
            val prefix = if (tab == Tab.STUDIO) "/studio" else "/me"
            val cur = runCatching { webView.url?.let { Uri.parse(it).path } }.getOrNull()
            if (cur == null || !cur.equals(prefix) && !cur.startsWith("$prefix/") && !(tab == Tab.MINE && (cur == "/users" || cur.startsWith("/users/")))) {
                webView.loadUrl(SiteConfig.SITE_URL + prefix)
            }
        } else {
            swipe.visibility = View.GONE
            val screen = screenFor(tab)
            if (screen.view().parent == null) {
                nativeHost.removeAllViews()
                nativeHost.addView(screen.view(), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
            screens.filterKeys { it != tab }.values.forEach { runCatching { it.onHide() } }
            if (previous == tab) {
                // 重按当前 tab：v1 不动作（防写作编辑态被打断的同款考量）
            } else {
                screen.onShow()
            }
        }
        renderTabBar()
        updateUi()
    }

    // ── 原生页/屏回调宿主 API ──

    fun openInWebView(pathOrUrl: String) = openInWebView(this, pathOrUrl)
    fun openNativeOrWeb(pathOrUrl: String) = openNativeOrWeb(this, pathOrUrl)
    fun openLogin() {
        if (nativeScreenShowing) return
        nativeScreenShowing = true
        loginLauncher.launch(Intent(this, LoginActivity::class.java))
    }

    private fun setupSwipe() {
        swipe.setColorSchemeResources(R.color.brand)
        swipe.setProgressBackgroundColorSchemeResource(R.color.paper)
        swipe.setOnRefreshListener { webView.reload() }
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
    }

    /** WebView 创建+装配（onRenderProcessGone 崩溃重建复用同函数） */
    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView() {
        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#F5F0E6"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false)
            userAgentString = SiteConfig.DESKTOP_UA
            builtInZoomControls = true
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
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createChromeClient()
        webView.setDownloadListener(selfUpdate.createDownloadListener())
        webView.addJavascriptInterface(
            ShellBridge(this, ttsController, keyboardInset) { enabled -> volumePaging = enabled },
            "__wsShell",
        )
        // 精确移除旧实例（mTarget 冻结铁律：绝不用 removeAllViews 换 WebView）
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
            val host = request.url.host?.lowercase()
            val path = request.url.path ?: ""
            // 批④ 泛化接管：站内路径查路由表（硬导航可取消加载，无需 bounce/cover）
            if (host != null && host in SiteConfig.SITE_HOSTS) {
                val takeover = takeoverDecision(request.url, isReload = false)
                if (takeover) return true
            }
            return router.routeUrl(view, request.url) { h ->
                extHost = h
                updateUi()
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            errorActive = false
            // 批③R：兜 302 落地等不触发 shouldOverrideUrlLoading 的整页加载形态
            maybeTakeOver(url, isReload = false, bounce = true)
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
            canGoBack = view.canGoBack()
            maybeTakeOver(url, isReload, bounce = true)
            updateUi()
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                errorActive = true
                swipe.isRefreshing = false
                updateUi()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            errorActive = false; loadedOnce = false; progress = 0
            attachWebView()
            updateUi()
            return true
        }
    }

    /** 深页双发节流（800ms 内同发忽略——接管三口可能同 URL 连触） */
    private var lastDeepLaunchAt = 0L

    /**
     * 批④ 接管决策（硬导航口）：true=取消加载并起原生目标。守卫同软导航口。
     * 深页无结果回调链：不置常驻旗（onResume 统一清态），双发靠时间戳节流。
     */
    private fun takeoverDecision(uri: Uri, isReload: Boolean): Boolean {
        val route = NativeRouteTable.match(uri)
        if (route == null) return false
        val path = uri.path ?: return false
        if (isReload || path == webFallbackPath) return false
        if (webLoginFallback && path.startsWith("/auth/")) return false
        return when (route) {
            NativeRoute.Login -> {
                val currentPath = runCatching { webView.url?.let { Uri.parse(it).path } }.getOrNull()
                if (currentPath == SiteConfig.NATIVE_LOGIN_PATH) return false
                if (nativeScreenShowing) return false
                openLogin()
                true
            }
            is NativeRoute.TabShelf -> { selectTab(Tab.SHELF, null); true }
            is NativeRoute.TabLibrary -> { selectTab(Tab.LIBRARY, route.segment); true }
            NativeRoute.TabForum -> { selectTab(Tab.FORUM, null); true }
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastDeepLaunchAt < 800) return true
                lastDeepLaunchAt = now
                NativeNav.launchDeep(this, uri)
                true
            }
        }
    }

    /**
     * 批③R/批④ 全导航形态接管（doUpdateVisitedHistory 软导航+onPageStarted 兜底）。
     * bounce 三档：登录=弹首页+记 pendingPostLoginPath；tab 目标=原位切+弹回稳定路径；
     * 深页=起 Activity+弹回稳定路径。软导航页面已驶入（Link 预取秒渲染）——takeoverCover
     * 同帧遮罩挡闪现帧（批③R-b）。
     */
    private fun maybeTakeOver(url: String?, isReload: Boolean, bounce: Boolean) {
        val u = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        val path = u.path ?: return
        // 稳定路径维护 + 豁免清理
        if (path == webFallbackPath) return // 显式 WebView 请求：本轮让路
        if (webFallbackPath != null && path != webFallbackPath) webFallbackPath = null
        val route = if (u.host?.lowercase() in SiteConfig.SITE_HOSTS) NativeRouteTable.match(u) else null
        if (route == null) {
            lastStableWebPath = path
            if (!path.startsWith("/auth/")) webLoginFallback = false
            return
        }
        if (isReload) return
        if (webLoginFallback && path.startsWith("/auth/")) return
        when (route) {
            NativeRoute.Login -> {
                if (nativeScreenShowing) return
                nativeScreenShowing = true
                pendingPostLoginPath = lastStableWebPath.takeIf { it != "/" && it != SiteConfig.NATIVE_LOGIN_PATH }
                if (bounce) {
                    takeoverCover = true
                    loginLauncher.launch(Intent(this, LoginActivity::class.java))
                    evalJs("location.replace(${JSONObject.quote(SiteConfig.SITE_URL)});")
                } else {
                    loginLauncher.launch(Intent(this, LoginActivity::class.java))
                }
            }
            is NativeRoute.TabShelf -> {
                selectTab(Tab.SHELF, null)
                if (bounce) bounceBack()
            }
            is NativeRoute.TabLibrary -> {
                selectTab(Tab.LIBRARY, route.segment)
                if (bounce) bounceBack()
            }
            NativeRoute.TabForum -> {
                selectTab(Tab.FORUM, null)
                if (bounce) bounceBack()
            }
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastDeepLaunchAt < 800) return
                lastDeepLaunchAt = now
                if (bounce) takeoverCover = true
                NativeNav.launchDeep(this, u)
                if (bounce) bounceBack()
            }
        }
    }

    /** 软导航已驶入：弹回稳定路径（不压历史，与批③R 登录档同语义泛化） */
    private fun bounceBack() {
        val target = if (lastStableWebPath == "/") SiteConfig.SITE_URL else SiteConfig.SITE_URL + lastStableWebPath
        evalJs("location.replace(${JSONObject.quote(target)});")
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

    // ── M247 音量键翻页 ──

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

    // ── 返回键：原生 tab 态=tab 根语义（双击退出）；WebView 态=壳两段 ──

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (nativeTabActive) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt < SiteConfig.EXIT_WINDOW_MS) {
                lastBackAt = 0
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                    if (!moveTaskToBack(false)) @Suppress("DEPRECATION") super.onBackPressed()
                } else {
                    @Suppress("DEPRECATION") super.onBackPressed()
                }
            } else {
                lastBackAt = now
                Toast.makeText(this, R.string.back_exit_toast, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (canGoBack && webView.canGoBack()) {
            errorActive = false
            updateUi()
            webView.goBack()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < SiteConfig.EXIT_WINDOW_MS) {
            lastBackAt = 0
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
        CookieManager.getInstance().flush()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val tab = intent.getStringExtra(EXTRA_TAB)
        if (tab != null) {
            val target = Tab.values().firstOrNull { it.key == tab }
            if (target != null) {
                selectTab(target, intent.getStringExtra(EXTRA_TAB_SEGMENT))
                return
            }
        }
        intent.getStringExtra(EXTRA_OPEN_URL)?.let { url ->
            val path = runCatching { Uri.parse(url).path }.getOrNull()
            // 显式 WebView 请求：登录页沿用 auth 前缀回退态；其余置精确路径豁免
            // （loadUrl 直载会过 onPageStarted/doUpdateVisitedHistory，不豁免即被劫回）
            if (path == SiteConfig.NATIVE_LOGIN_PATH) {
                webLoginFallback = true
            } else if (path != null) {
                webFallbackPath = path
                webLoginFallback = false
            }
            activeTab = null
            swipe.visibility = View.VISIBLE
            renderTabBar()
            if (this::webView.isInitialized) webView.loadUrl(url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        selfUpdate.tryStartInstall()
        // 深页返回清接管态（深页无结果回调链，残留=下页接管被拦+遮罩卡屏；
        // loginLauncher 回调先于 onResume 自清，此处双清无害）
        if (takeoverCover) {
            takeoverCover = false
            updateUi()
        }
        nativeScreenShowing = false
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
        progressBar.visibility = if (!errorActive && progress < 100 && !nativeTabActive) View.VISIBLE else View.GONE
        loadingOverlay.visibility =
            if (!errorActive && !nativeTabActive && (takeoverCover || (!loadedOnce && progress < 100)))
                View.VISIBLE
            else View.GONE
        errorOverlay.visibility = if (errorActive && !nativeTabActive) View.VISIBLE else View.GONE
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
