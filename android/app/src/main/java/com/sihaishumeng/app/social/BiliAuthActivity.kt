package com.sihaishumeng.app.social

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
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
 *
 * 批③R 两修（真机报障「B站卡住没反应」返修）：①主题=AppCompat 族透明款（原框架
 * Translucent+AppCompatActivity=setContentView 必崩进程，见 styles.xml）；②空白期
 * 观感加固——米白底+居中转圈（首帧 onPageFinished 隐），主框架加载失败立即收屏，
 * 慢网下不再呈现「透明悬停在登录屏上像死机」的形态。
 *
 * 批④W：站内落地 URL 随 completion 回传（finalUrl）——新号落地页
 * （/settings/account-security?welcome=1[&invite=]）由主 WebView 打开承接 M340
 * 邀请码兑换（批③ 收屏吞页=邀请从未真正兑换的根治面）。
 */
class BiliAuthActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "biliAuthUrl"
        private var completion: ((Boolean, String?) -> Unit)? = null

        fun launch(activity: Activity, url: String, onDone: (Boolean, String?) -> Unit) {
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
        // 米白底（主 WebView 同款）——授权页首帧前就有实体底色，不再透明空窗
        web.setBackgroundColor(Color.parseColor("#F5F0E6"))
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
        // 转圈盖层：首帧 onPageFinished 隐（B站 PC 授权页重 JS，慢网首帧可达数秒）
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            val dp = (resources.displayMetrics.density * 4).toInt()
            setPadding(dp, dp, dp, dp)
        }
        val root = FrameLayout(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            spinner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host?.lowercase() ?: return true
                val path = request.url.path ?: ""
                val onSite = host in SiteConfig.SITE_HOSTS
                return when {
                    // 站内回调链放行（302 由引擎跟）
                    onSite && path.startsWith("/api/auth/") -> false
                    // 站内落地页（/ 或 /settings/…?welcome=1 或 error 回登录页）=完成；
                    // 落地 URL 回传（批④W：新号落地页由主 WebView 打开）
                    onSite -> {
                        finishWith(!path.startsWith("/auth/login"), request.url.toString())
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
                spinner.visibility = View.GONE
                CookieManager.getInstance().flush() // M189e：立刻落盘
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                // 主框架加载失败（断网/被墙）立即收屏报错——不悬停在转圈上
                if (request.isForMainFrame) finishWith(false)
            }
        }
        setContentView(root)
        web.loadUrl(url)
    }

    private fun finishWith(ok: Boolean, finalUrl: String? = null) {
        if (done) return
        done = true
        val cb = completion
        completion = null
        CookieManager.getInstance().flush()
        finish()
        cb?.invoke(ok, finalUrl)
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
