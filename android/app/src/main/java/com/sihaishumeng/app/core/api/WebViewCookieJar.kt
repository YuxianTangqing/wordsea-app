package com.sihaishumeng.app.core.api

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 批③ Cookie 桥：OkHttp ↔ android.webkit.CookieManager（登录态三处共享——
 * OkHttp 收的 Set-Cookie 落 CookieManager → 长尾 WebView 自动带；原生请求
 * 从 CookieManager 读回拼 Cookie 头）。
 *
 * 主 WebView 已 setAcceptThirdPartyCookies(true)（OAuth 回调 cookie 落壳同源），
 * CookieManager 是进程级单例，本桥与主 WebView 天然同仓。
 */
class WebViewCookieJar : CookieJar {

    private val cm = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            // Cookie.toString() 还原「name=value; Path=/; ...」形态，setCookie 接受
            cm.setCookie(url.toString(), c.toString())
        }
        // 退后台丢会话防线（M189e 同款：立刻落盘）
        cm.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = cm.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .mapNotNull { Cookie.parse(url, it) }
    }
}
