package com.sihaishumeng.app.social

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.sihaishumeng.app.core.SiteConfig

/**
 * 批③ B站授权屏（零 SDK 零新端点）：POST /api/auth/sign-in/social 拿到的授权 url
 * 在本透明 Activity 的 WebView 里走完整网页流——B站授权 → 302 回
 * /api/auth/callback/bilibili → 服务端建会话（审计/邀请归因全在现有 after-hook，
 * M340 链路原样）→ cookie 落共享 CookieManager → 嗅探「站内非 /api/ 页」即收屏。
 *
 * 分诊（比主 WebView 更收敛）：B站/GitHub/授权大域放行加载；本站 /api/auth/ 前缀
 * 放行（回调链）；本站其他路径=完成信号；其余一切拦死（授权屏内不外跳系统浏览器）。
 * （⚠️ Kotlin 块注释可嵌套——注释里勿写「斜杠星」通配形态，会多开一层注释。）
 */
class BiliAuthActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "biliAuthUrl"
        private var completion: ((Boolean) -> Unit)? = null

        fun launch(activity: Activity, url: String, onDone: (Boolean) -> Unit) {
            completion = onDone
            activity.startActivity(
                Intent(activity, BiliAuthActivity::class.java).putExtra(EXTRA_URL, url)
            )
        }
    }

    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 桌面 UA 同主 WebView——壳内 B站登录 2026-09-19 站主实测通过的同一形态
            userAgentString = SiteConfig.DESKTOP_UA
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host?.lowercase() ?: return true
                val path = request.url.path ?: ""
                val onSite = host in SiteConfig.SITE_HOSTS
                return when {
                    // 站内回调链放行（302 由引擎跟）
                    onSite && path.startsWith("/api/auth/") -> false
                    // 站内落地页（/ 或 /settings/…?welcome=1 或 error 回登录页）=完成
                    onSite -> {
                        finishWith(!path.startsWith("/auth/login"))
                        true
                    }
                    // B站/授权大域（授权页本体与其站内跳转）
                    host in SiteConfig.OAUTH_HOSTS ||
                        SiteConfig.OAUTH_SUFFIXES.any { host.endsWith(it) } -> false
                    // 其余拦死（授权屏不外跳）
                    else -> true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush() // M189e：立刻落盘
            }
        }
        setContentView(web)
        web.loadUrl(url)
    }

    private fun finishWith(ok: Boolean) {
        if (done) return
        done = true
        val cb = completion
        completion = null
        CookieManager.getInstance().flush()
        finish()
        cb?.invoke(ok)
    }

    override fun onDestroy() {
        // 用户按返回=放弃授权
        finishWith(false)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWith(false)
    }
}
