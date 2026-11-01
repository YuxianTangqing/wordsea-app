package com.sihaishumeng.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
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
import android.webkit.PermissionRequest
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.sihaishumeng.app.core.SiteConfig
import com.sihaishumeng.app.core.bridge.ShellBridge
import com.sihaishumeng.app.core.device.KeyboardInsetController
import com.sihaishumeng.app.core.flip.CurlPageView
import com.sihaishumeng.app.core.flip.FlipController
import com.sihaishumeng.app.core.flip.FlipInterceptorLayout
import com.sihaishumeng.app.core.tts.TtsEngineHolder
import com.sihaishumeng.app.core.tts.WebChannel
import com.sihaishumeng.app.core.update.SelfUpdateController
import com.sihaishumeng.app.core.web.WebViewRouter
import com.sihaishumeng.app.social.SocialAuthController
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
 * 批③ 四家登录 SDK + 批③R /auth/login 接管曾以原生登录屏为主路径；批④W（站主
 * 2026-10-30 裁定「用网页的登录UI」）登录屏与接管全家退役——登录页=网页本尊，
 * 四家按钮经 __wsShell.socialLogin 桥拉 SDK（本 Activity 零原生 UI；SDK 结果经
 * evalJs 回呼网页）。批⑩ 仿真翻页原生动画层（起点/番茄式整页卷曲）：core/flip/
 * 四件（手势容器包 WebView+卷页盖层+会话控制器+动力学），排版/长尾仍网页——
 * web 是决策大脑、native 是效果器（批⑨ 听书同构）。
 * core 层纯 Kotlin 解耦为 iOS/KMP 留门——勿把业务逻辑写进本 Activity。
 */
class MainActivity : AppCompatActivity() {

    // ── 状态五态（壳 1:1）+ 返回键时间戳 ──
    private var canGoBack = false
    private var progress = 0
    private var loadedOnce = false
    private var errorActive = false
    private var extHost: String? = null
    private var lastBackAt = 0L

    // ── M247 音量键翻页：web 侧（阅读器挂载且听书未播）经 __wsShell.setVolumePaging(true)
    //    声明接管才拦键；默认 false＝旧网页/非阅读器页面音量键归系统。@Volatile：
    //    JS 桥 binder 线程写、主线程 onKeyDown 读 ──
    @Volatile
    private var volumePaging = false

    private lateinit var router: WebViewRouter
    private lateinit var keyboardInset: KeyboardInsetController
    private lateinit var selfUpdate: SelfUpdateController
    /** 批④W 桥面社交登录编排（网页登录页四家按钮 → SDK → app-social 端点 → 回呼网页） */
    private lateinit var socialAuth: SocialAuthController

    private lateinit var rootLayout: ViewGroup
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webView: WebView
    /** 批⑩ 仿真翻页：手势容器（持久存活，包 WebView 于 swipe 内）+卷页盖层+会话控制器 */
    private var flipLayout: FlipInterceptorLayout? = null
    private var curlView: CurlPageView? = null
    private lateinit var flipController: FlipController
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

    // M362 语音评论麦克风：WebView getUserMedia 的 PermissionRequest 挂起件——系统
    // 运行时权限弹窗期间保持挂起（不先 deny：先 deny=用户稍后同意被误判永久拒绝，
    // 该 WebView 请求终结不可复活，页面须刷新重来）；新请求顶旧挂起（页面侧录音本
    // 就单实例串行）；grant/deny 全 try（页面已导航的 stale 请求可能抛/no-op，吞掉）
    private var pendingPermissionRequest: PermissionRequest? = null
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingPermissionRequest
            pendingPermissionRequest = null
            if (req == null) return@registerForActivityResult
            try {
                if (granted) req.grant(req.resources) else req.deny()
            } catch (_: Exception) {
                /* stale 请求：页面侧已终结 */
            }
        }

    // 批④W 桥面社交登录：网页登录页四家按钮经 __wsShell.socialLogin 进来，结果 JSON
    // 经 window[callbackName]("<json 字符串>") 回呼网页。批⑤R 归一 string 契约——
    // 批④W 起把 JSONObject 裸内插进实参位（求值成 JS 对象），web 侧旧版按 string
    // JSON.parse 必炸=首发「登录回调异常，请重试」根因（cookie 已种、刷新即登录）。
    // 现 quote(json) 产出 JS 字符串字面量注入；U+2028/2029 手工封死——JSONObject.quote
    // 不转义它们（JSON 合法），却是 JS 字符串字面量的非法裸字符（唯一残余缝）。
    private fun handleSocialLogin(provider: String, optionsJson: String, callbackName: String) {
        socialAuth.startForWeb(provider, optionsJson) { json ->
            val payload = JSONObject.quote(json.toString())
                .replace(0x2028.toChar().toString(), "\\u2028")
                .replace(0x2029.toChar().toString(), "\\u2029")
            evalJs(
                "window[${JSONObject.quote(callbackName)}] && " +
                    "window[${JSONObject.quote(callbackName)}]($payload);"
            )
        }
    }

    /** 主线程 JS 注入唯一出口（各控制器经此回呼；webView 未初始化=静默丢弃，
     *  与壳 1.1.9 的 isInitialized 守卫同语义） */
    private fun evalJs(js: String) {
        if (this::webView.isInitialized) webView.evaluateJavascript(js, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = WebViewRouter(this)
        // 批⑨ 逐块旧协议适配（进程级一次）：web 侧自铸 id（c${n}/one${n} 前缀）的
        // done/error 回投 __wsTtsEvent——与旧 TtsController 注入串逐字一致（契约冻结）；
        // stop 事件吞掉（旧协议无此 kind）；会话内部 id（s 前缀）不走此口（事件走
        // __wsTtsSession）。render 崩溃重建后经 WebChannel 弱引用仍可达。
        TtsEngineHolder.addUtteranceListener { kind, id ->
            if ((kind == "done" || kind == "error") &&
                (id.startsWith("c") || id.startsWith("one"))
            ) {
                WebChannel.callGuarded("__wsTtsEvent", "'$kind','$id'")
            }
        }
        keyboardInset = KeyboardInsetController(
            webProvider = { if (this::webView.isInitialized) webView else null },
            evalJs = { js -> evalJs(js) },
        )
        selfUpdate = SelfUpdateController(this, this)
        socialAuth = SocialAuthController(this)
        // targetSdk 36 强制 edge-to-edge：自管 insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        // 批⑩ 仿真翻页：会话控制器+卷页盖层（z 序插 progressBar 前=盖 swipe、
        // 不盖错误页/首载遮罩/横幅；初始 GONE，声明不来零行为）
        flipController = FlipController(
            this,
            viewProvider = { curlView },
            webViewProvider = { if (this::webView.isInitialized) webView else null },
        )
        curlView = CurlPageView(this).apply { visibility = View.GONE }
        rootLayout.addView(curlView, rootLayout.indexOfChild(progressBar))
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
            ShellBridge(
                this,
                keyboardInset,
                { enabled -> volumePaging = enabled },
                ::handleSocialLogin,
                { json -> flipController.onDeclaration(json) },
                { json -> flipController.onProgramFlip(json) },
            ),
            "__wsShell",
        )
        // 批⑨ 进程级 WebView 通道换绑（TTS 会话事件/逐块回投的唯一注 JS 出口；
        // render 崩溃重建走同一装配口自动恢复）
        WebChannel.bind(webView)
        // 批⑩ 仿真翻页：flipLayout 持久存活（SwipeRefreshLayout mTarget=flipLayout
        // 单子恒冻结——铁律不破），WebView 建在 flipLayout 内；render 崩溃重建只换
        // WebView 实例（精确移除旧实例，绝不用 removeAllViews）。
        if (flipLayout == null) {
            flipLayout = FlipInterceptorLayout(this, flipController)
            swipe.addView(flipLayout, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        flipLayout?.let { lay ->
            // 移除旧 WebView 实例（onRenderProcessGone 重建路径；flipLayout 自身不动）
            for (i in (lay.childCount - 1) downTo 0) {
                val child = lay.getChildAt(i)
                if (child is WebView) lay.removeView(child)
            }
            lay.addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        webView.loadUrl(SiteConfig.SITE_URL)
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // 批④W：/auth/login 不再接管——登录页=网页本尊（站内路径 router 恒返
            // false=壳内加载，四家按钮走桥拉 SDK）。scheme 黑洞/外链送系统浏览器
            // 全在 router.routeUrl。
            return router.routeUrl(view, request.url) { h ->
                extHost = h
                updateUi()
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // 任何新导航开始即清错误态（返回上一页/重试/回首页后遮罩即时消失）
            errorActive = false
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
            // 批⑩：render 崩溃=位图/声明全作废（收层+Idle，等新页面重新声明）
            flipController.onRenderGone()
            errorActive = false; loadedOnce = false; progress = 0
            attachWebView()
            updateUi()
            return true
        }
    }

    /**
     * QQ SDK 授权结果回灌（批④W：SDK 铁律——结果必须透传 Tencent.onActivityResultData，
     * 宿主从 LoginActivity 换成 Main，透传链随登录屏退役迁到此）。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        socialAuth.onActivityResult(requestCode, resultCode, data)
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

        // M362 麦克风：网页 getUserMedia（语音评论录音）经此回调——WebView 默认实现
        // 不响应=请求搁置到超时（批3 前壳内 web 侧判「不支持录音」的根因）。仅放行
        // 音频采集，其余资源一律 deny；已授权直 grant（WebView 按源记忆授权）
        override fun onPermissionRequest(request: PermissionRequest) {
            val audio = request.resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            if (!audio) {
                try { request.deny() } catch (_: Exception) { }
                return
            }
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                try { request.grant(request.resources) } catch (_: Exception) { }
                return
            }
            pendingPermissionRequest?.let { old ->
                try { old.deny() } catch (_: Exception) { }
            }
            pendingPermissionRequest = request
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // 页面侧取消（导航/超时自灭）：只清引用，绝不 grant/deny 已取消请求
        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermissionRequest === request) pendingPermissionRequest = null
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

    override fun onResume() {
        super.onResume()
        // M-APP15：后台完成的更新包在此补弹安装器（后台 startActivity 被静默拦的兜底）
        selfUpdate.tryStartInstall()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        selfUpdate.destroy()
        flipController.destroy()
        // 批⑨：TTS 引擎不再随 Activity 关停（TtsEngineHolder=进程级）——听书会话在
        // 前台服务里读，用户退出 Activity 进程存活时朗读继续（引擎经 applicationContext
        // 创建本就与 Activity 无绑定）
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
