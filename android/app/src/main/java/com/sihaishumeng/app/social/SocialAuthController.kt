package com.sihaishumeng.app.social

import android.app.Activity
import android.content.Intent
import com.sihaishumeng.app.core.api.ApiClient
import org.json.JSONObject

/**
 * 批③ 原生社交登录编排器：SDK 侧拉起/回调归一 → POST /api/auth/app-social/sign-in
 * → 会话 cookie 经 WebViewCookieJar 自动落 CookieManager（长尾 WebView 共享）。
 *
 * 三家形态：
 * - 微信：SendAuth code 上送（WXEntryActivity 回跳，见 WechatAuth）
 * - QQ：SDK 回调直接给 accessToken+openid 上送（unionid 由服务端 /me 复核+获取）
 * - B站：零 SDK——先 POST /sign-in/social 拿授权 url，BiliAuthActivity（WebView）
 *   走完整网页回调链（审计/邀请归因全在服务端 after-hook，M340 链路原样）
 * - 支付宝：官方 SDK（Maven 15.8.42）OpenAuthTask 拉起，auth_code 上送换 user_id
 *
 * QQ 裂号防线：服务端按 Account.unionid 反查（一次性迁移脚本+网页登录惰性回填）；
 * 未命中直接开新号（批③ 站主裁定：与微信/支付宝同构，裂号残余可接受）。
 */
class SocialAuthController(private val activity: Activity) {

    enum class Provider { WECHAT, QQ, BILIBILI, ALIPAY }

    interface Callback {
        /** [ok]=登录成功（会话 cookie 已落 CookieManager）；[message] 失败时给用户看。 */
        fun onSocialResult(ok: Boolean, message: String?)
    }

    private var callback: Callback? = null
    private var invite: String? = null
    private var busy = false

    fun isAvailable(provider: Provider): Boolean = when (provider) {
        Provider.WECHAT -> WechatAuth.isInstalled(activity)
        Provider.QQ -> true // open-sdk 内置 WAP 兜底（未装手机QQ也能登）
        Provider.BILIBILI -> true
        Provider.ALIPAY -> true // SDK 第 5 参 true=未装支付宝走内置 H5 中转页
    }

    fun start(provider: Provider, invite: String?, cb: Callback) {
        if (busy) return
        busy = true
        callback = cb
        this.invite = invite?.trim()?.takeIf { it.isNotEmpty() }
        when (provider) {
            Provider.WECHAT -> {
                if (!WechatAuth.send(activity) { code, errCode ->
                        // 微信回跳线程（WXEntryActivity 主线程，但保持统一走 ApiClient 主线程回调）
                        if (code != null) signInApp("wechat", JSONObject().put("code", code))
                        else finish(false, wechatErrText(errCode))
                    }) {
                    finish(false, "无法拉起微信（未安装或版本过低）")
                }
            }
            Provider.QQ -> {
                QqAuth.login(activity) { json ->
                    val token = json?.optString("access_token")
                    val openid = json?.optString("openid")
                    if (json != null && !token.isNullOrEmpty()) {
                        val body = JSONObject().put("accessToken", token)
                        if (!openid.isNullOrEmpty()) body.put("openid", openid)
                        signInApp("qq", body)
                    } else {
                        finish(false, "QQ 授权未完成")
                    }
                }
            }
            Provider.BILIBILI -> startBili()
            Provider.ALIPAY -> AlipayAuth.signIn(activity) { authCode, message ->
                // 回调线程无主线程承诺——runOnUiThread 收口（主线程调用=立即执行）
                activity.runOnUiThread {
                    if (authCode != null) signInApp("alipay", JSONObject().put("code", authCode))
                    else finish(false, message)
                }
            }
        }
    }

    /** QQ SDK 的 onActivityResult 透传（LoginActivity 收到后调这里）。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        QqAuth.onActivityResult(requestCode, resultCode, data)
    }

    // ── 内部 ──

    /** 上送 /api/auth/app-social/sign-in（provider 单独参数拼入；wechat/qq 共用）。 */
    private fun signInApp(provider: String, body: JSONObject) {
        body.put("provider", provider)
        if (invite != null && !body.has("invite")) body.put("invite", invite)
        ApiClient.postJson("/api/auth/app-social/sign-in", body) { code, json ->
            when {
                code == 200 && json?.optString("status") == "ok" -> finish(true, null)
                code == 404 -> finish(false, "该登录方式暂未启用")
                code == 429 -> finish(false, "尝试过于频繁，请稍后再试")
                code in 400..499 -> finish(false, json?.optString("message")?.takeIf { it.isNotEmpty() } ?: "授权失败（$code）")
                else -> finish(false, "网络异常（${if (code < 0) "连接失败" else code}）")
            }
        }
    }

    /** B站网页流：/sign-in/social 拿授权 url → WebView 承载整条回调链。 */
    private fun startBili() {
        val newUserCb = if (invite != null) {
            "/settings/account-security?welcome=1&invite=${java.net.URLEncoder.encode(invite, "UTF-8")}"
        } else {
            "/settings/account-security?welcome=1"
        }
        val body = JSONObject()
            .put("provider", "bilibili")
            .put("callbackURL", "/")
            .put("errorCallbackURL", "/auth/login?social=error")
            .put("newUserCallbackURL", newUserCb)
        ApiClient.postJson("/api/auth/sign-in/social", body) { code, json ->
            val url = json?.optString("url")
            if (code == 200 && !url.isNullOrEmpty()) {
                BiliAuthActivity.launch(activity, url) { ok ->
                    finish(ok, if (ok) null else "B站授权未完成")
                }
            } else {
                finish(false, "B站登录暂不可用（${if (code < 0) "网络异常" else code}）")
            }
        }
    }

    private fun wechatErrText(errCode: Int): String = when (errCode) {
        -2 -> "已取消微信授权"
        -4 -> "已拒绝微信授权"
        -6 -> "应用签名校验失败（请重装本应用）"
        else -> "微信授权失败（$errCode）"
    }

    private fun finish(ok: Boolean, message: String?) {
        busy = false
        val cb = callback
        callback = null
        cb?.onSocialResult(ok, message)
    }
}
