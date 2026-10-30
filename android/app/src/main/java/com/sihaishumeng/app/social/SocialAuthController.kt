package com.sihaishumeng.app.social

import android.app.Activity
import android.content.Intent
import com.sihaishumeng.app.core.api.ApiClient
import org.json.JSONObject

/**
 * 批③ 原生社交登录编排器（批④W 改造为桥面）：SDK 侧拉起/回调归一 → POST
 * /api/auth/app-social/sign-in → 会话 cookie 经 WebViewCookieJar 自动落
 * CookieManager（主 WebView 长尾共享）。批④W 原生登录屏退役后唯一调用方=ShellBridge
 * .socialLogin（网页登录页四家按钮）——结果统一 JSON 化经 emit 回呼注入网页。
 *
 * 四家形态：
 * - 微信：SendAuth code 上送（WXEntryActivity 回跳，见 WechatAuth）
 * - QQ：SDK 回调直接给 accessToken+openid 上送（unionid 由服务端 /me 复核+获取）
 * - B站：零 SDK——POST /sign-in/social 拿授权 url，BiliAuthActivity（WebView）走完整
 *   网页回调链；新号落地页 URL 回传网页打开（M340 邀请码经落地页兑换——批③ 收屏
 *   吞页 bug 的根治面）
 * - 支付宝：官方 SDK OpenAuthTask 拉起，auth_code 上送换 user_id
 *
 * 结果形状（emit 的 JSON）：{ok, code?, message?, isRegister?, user?, redirect?}
 * - code：CANCEL（用户取消——网页静默复位）/ BUSY（已有登录在途）/ AUTH（拉起或
 *   授权失败）/ SERVER（端点 4xx/网络——message 已是给用户看的文案）
 * - isRegister/user：三家 SDK 直连的端点响应透传（B站网页流无此字段）
 * - redirect：B站 新号落地页（/settings/account-security?welcome=1[&invite=]）
 *
 * QQ 裂号防线：服务端按 Account.unionid 反查（一次性迁移脚本+网页登录惰性回填）；
 * 未命中直接开新号（批③ 站主裁定与微信/支付宝同构；批④W 站主复裁取消确认环节）。
 */
class SocialAuthController(private val activity: Activity) {

    enum class Provider { WECHAT, QQ, BILIBILI, ALIPAY }

    /** 同一时刻至多一条登录在途（网页社交钮 loading 态即互斥闸；busy 回 BUSY）。 */
    private var busy = false
    private var invite: String? = null
    private var emit: ((JSONObject) -> Unit)? = null

    fun isAvailable(provider: Provider): Boolean = when (provider) {
        Provider.WECHAT -> WechatAuth.isInstalled(activity)
        Provider.QQ -> true // open-sdk 内置 WAP 兜底（未装手机QQ也能登）
        Provider.BILIBILI -> true
        Provider.ALIPAY -> true // SDK 第 5 参 true=未装支付宝走内置 H5 中转页
    }

    /** 桥面入口（ShellBridge.socialLogin 切主线程后转发）。provider=wechat/qq/
     *  alipay/bilibili；optionsJson={invite?}；emit=结果回呼（调用方包成
     *  window[callbackName](json) 注入网页）。 */
    fun startForWeb(providerName: String, optionsJson: String, emit: (JSONObject) -> Unit) {
        val provider = when (providerName) {
            "wechat" -> Provider.WECHAT
            "qq" -> Provider.QQ
            "bilibili" -> Provider.BILIBILI
            "alipay" -> Provider.ALIPAY
            else -> {
                emit(resultJson(ok = false, code = "AUTH", message = "未知登录方式：$providerName"))
                return
            }
        }
        if (busy) {
            emit(resultJson(ok = false, code = "BUSY"))
            return
        }
        if (provider == Provider.WECHAT && !isAvailable(provider)) {
            emit(resultJson(ok = false, code = "AUTH", message = "未安装微信客户端，无法使用微信登录"))
            return
        }
        busy = true
        this.invite = runCatching { JSONObject(optionsJson).optString("invite") }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() }
        this.emit = emit
        when (provider) {
            Provider.WECHAT -> {
                if (!WechatAuth.send(activity) { code, errCode ->
                        if (code != null) signInApp("wechat", JSONObject().put("code", code))
                        else finishWechatErr(errCode)
                    }) {
                    finish(resultJson(ok = false, code = "AUTH", message = "无法拉起微信（未安装或版本过低）"))
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
                        finish(resultJson(ok = false, code = "AUTH", message = "QQ 授权未完成"))
                    }
                }
            }
            Provider.BILIBILI -> startBili()
            Provider.ALIPAY -> AlipayAuth.signIn(activity) { authCode, message ->
                // 回调线程无主线程承诺——runOnUiThread 收口（主线程调用=立即执行）
                activity.runOnUiThread {
                    if (authCode != null) signInApp("alipay", JSONObject().put("code", authCode))
                    else finish(resultJson(ok = false, code = "AUTH", message = message))
                }
            }
        }
    }

    /** QQ SDK 的 onActivityResult 透传（宿主 MainActivity 收到后调这里——批④W 宿主
     *  从 LoginActivity 换成 Main，透传铁律不变）。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        QqAuth.onActivityResult(requestCode, resultCode, data)
    }

    // ── 内部 ──

    private fun resultJson(ok: Boolean, code: String? = null, message: String? = null): JSONObject =
        JSONObject().put("ok", ok).apply {
            if (code != null) put("code", code)
            if (message != null) put("message", message)
        }

    /** 上送 /api/auth/app-social/sign-in（provider 单独参数拼入；wechat/qq/alipay 共用）。 */
    private fun signInApp(provider: String, body: JSONObject) {
        body.put("provider", provider)
        if (invite != null && !body.has("invite")) body.put("invite", invite)
        ApiClient.postJson("/api/auth/app-social/sign-in", body) { code, json ->
            when {
                code == 200 && json?.optString("status") == "ok" -> finish(
                    resultJson(ok = true)
                        .put("isRegister", json.optBoolean("isRegister", false))
                        .put("user", json.optJSONObject("user") ?: JSONObject.NULL)
                )
                code == 404 -> finish(resultJson(ok = false, code = "SERVER", message = "该登录方式暂未启用"))
                code == 429 -> finish(resultJson(ok = false, code = "SERVER", message = "尝试过于频繁，请稍后再试"))
                code in 400..499 -> finish(resultJson(ok = false, code = "SERVER", message = json?.optString("message")?.takeIf { it.isNotEmpty() } ?: "授权失败（$code）"))
                else -> finish(resultJson(ok = false, code = "SERVER", message = "网络异常（${if (code < 0) "连接失败" else code}）"))
            }
        }
    }

    /** B站网页流：/sign-in/social 拿授权 url → WebView 承载整条回调链。新号落地页
     *  （/settings/account-security?welcome=1[&invite=]）由 BiliAuthActivity 回传，
     *  经 redirect 给网页打开（M340 邀请码兑换闭环——批③ 收屏吞掉落地页，邀请从未
     *  真正兑换）。 */
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
                BiliAuthActivity.launch(activity, url) { ok, finalUrl ->
                    finish(
                        if (ok) {
                            resultJson(ok = true).apply {
                                finalUrl?.takeIf { it.contains("/settings/account-security") }
                                    ?.let { put("redirect", it) }
                            }
                        } else {
                            resultJson(ok = false, code = "AUTH", message = "B站授权未完成")
                        }
                    )
                }
            } else {
                finish(resultJson(ok = false, code = "SERVER", message = "B站登录暂不可用（${if (code < 0) "网络异常" else code}）"))
            }
        }
    }

    /** 微信 errCode：-2 取消/-4 拒绝=CANCEL（网页静默复位）；-6 签名失败等=AUTH 报错。 */
    private fun finishWechatErr(errCode: Int) {
        finish(
            when (errCode) {
                -2, -4 -> resultJson(ok = false, code = "CANCEL")
                -6 -> resultJson(ok = false, code = "AUTH", message = "应用签名校验失败（请重装本应用）")
                else -> resultJson(ok = false, code = "AUTH", message = "微信授权失败（$errCode）")
            }
        )
    }

    /** 收口：清 busy、取走回呼；emit 兜底切主线程（evaluateJavascript 须 UI 线程；
     *  已在主线程时 runOnUiThread=立即执行）。 */
    private fun finish(result: JSONObject) {
        busy = false
        val cb = emit
        emit = null
        if (cb != null) activity.runOnUiThread { cb(result) }
    }
}
