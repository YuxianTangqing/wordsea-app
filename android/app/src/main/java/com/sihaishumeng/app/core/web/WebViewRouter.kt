package com.sihaishumeng.app.core.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import com.sihaishumeng.app.core.SiteConfig
import org.json.JSONObject

/**
 * 链接分诊（M188/M188c/M188d/M188e 判定树逐字复刻，顺序固定；批②自壳迁出）。
 *
 * scheme 黑洞铁律（M188e）：一切非 http(s) 协议默认拦死（shouldOverrideUrlLoading
 * 返 true 不交引擎）——引擎对未知 scheme 的处理=转系统拉起 APP/浏览器，QQ「一键
 * 登录」wtloginmqq:// 曾从此口漏出。例外仅三个：mailto:/tel: 送系统、about: 放行、
 * intent:// 走 fallback_url 域名分诊。不要再回到「枚举拉起 scheme 名单」的老路。
 */
class WebViewRouter(private val activity: Activity) {

    fun isAllowedHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return h in SiteConfig.SITE_HOSTS || h in SiteConfig.OAUTH_HOSTS ||
            SiteConfig.OAUTH_SUFFIXES.any { h.endsWith(it) }
    }

    /** @return true=拦下（WebView 不加载），false=放行壳内加载。
     *  [onExternalHost]：⑤ 分支送系统浏览器时回呼越界 host（宿主更新诊断横幅）。 */
    fun routeUrl(view: WebView, uri: Uri, onExternalHost: (String?) -> Unit): Boolean {
        val scheme = uri.scheme?.lowercase()
        when {
            // ① mailto/tel：送系统，静默吞错（M188）
            scheme == "mailto" || scheme == "tel" -> {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }
            // ② intent://：解 fallback_url 分诊——旧版把 fallback 送浏览器=跳出元凶（M188d）
            scheme == "intent" -> {
                val fb = Regex("fallback_url=([^;&]+)").find(uri.toString())?.groupValues?.get(1)
                if (fb != null) {
                    try {
                        val target = Uri.decode(fb)
                        val tUri = Uri.parse(target)
                        val tScheme = tUri.scheme?.lowercase()
                        if ((tScheme == "http" || tScheme == "https") && isAllowedHost(tUri.host)) {
                            // 合格=壳内加载其网页版（replace 导航，不送系统）
                            view.evaluateJavascript(
                                "location.replace(${JSONObject.quote(target)});", null
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
                return true
            }
            // ③ about: 放行（window.open('') 弹窗兼容）
            scheme?.startsWith("about") == true -> return false
            // ④ scheme 黑洞：其余非 http(s) 一律拦死（M188e 铁律）
            scheme != "http" && scheme != "https" -> return true
            // ⑤ http(s)：白名单壳内；否则记 extHost 横幅+送系统浏览器（M188d 诊断横幅）
            else -> {
                val host = uri.host?.lowercase()
                return if (isAllowedHost(host)) {
                    false
                } else {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                    }
                    onExternalHost(host)
                    true
                }
            }
        }
    }
}
