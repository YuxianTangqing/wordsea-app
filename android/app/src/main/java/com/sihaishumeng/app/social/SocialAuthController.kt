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
 *
 * 批⑥ 绑定态（optionsJson.mode='link'，缺省登录）：微信/QQ/支付宝 SDK 流程同款但
 * 上送 /app-social/link（已登录会话内关联当前用户；cookie 经 WebViewCookieJar 自动
 * 携带，响应增 already 幂等标记）；B站/GitHub（无 SDK 家族）走 better-auth 既有
 * /link-social → 通用授权屏（BiliAuthActivity）浮层完成整条回调链——「已绑他人」被
 * errorCallbackURL 安全拒绝，无会话劫持。github 仅绑定态放行（登录态网页页不放行）。
 */
class SocialAuthController(private val activity: Activity) {

    enum class Provider { WECHAT, QQ, BILIBILI, ALIPAY, GITHUB }

    private companion object {
        /** 僵尸熔断窗口（与 web 侧 180s 超时同口径） */
        private const val BUSY_ZOMBIE_MS = 180_000L
    }

    /** 同一时刻至多一条登录在途（网页社交钮 loading 态即互斥闸；busy 回 BUSY）。 */
    private var busy = false
    /** 批⑤R 僵尸熔断：busy 起算时刻（elapsedRealtime 防时钟回跳）。emit 永不回呼的
     *  断链（如批⑤ 支付宝回跳无 filter 接收）会让 busy 永卡——四家按钮全 BUSY 静默
     *  直到杀 APP。超 180s（与 web 侧超时同口径）视为僵尸，清态放行新请求。 */
    private var busyAt = 0L
    /** 批⑥ 绑定态标记（startForWeb 解析 optionsJson.mode；绑定态忽略 invite）。 */
    private var linkMode = false
    private var invite: String? = null
    private var emit: ((JSONObject) -> Unit)? = null

    fun isAvailable(provider: Provider): Boolean = when (provider) {
        Provider.WECHAT -> WechatAuth.isInstalled(activity)
        Provider.QQ -> true // open-sdk 内置 WAP 兜底（未装手机QQ也能登）
        Provider.BILIBILI -> true
        Provider.ALIPAY -> true // SDK 第 5 参 true=未装支付宝走内置 H5 中转页
        Provider.GITHUB -> true // 批⑥ 绑定态浮层网页流（无 SDK）
    }

    /** 桥面入口（ShellBridge.socialLogin 切主线程后转发）。provider=wechat/qq/
     *  alipay/bilibili/github（github 仅 link 态）；optionsJson={invite?, mode?}；
     *  emit=结果回呼（调用方包成 window[callbackName](json) 注入网页）。 */
    fun startForWeb(providerName: String, optionsJson: String, emit: (JSONObject) -> Unit) {
        val opts = runCatching { JSONObject(optionsJson) }.getOrNull() ?: JSONObject()
        val link = opts.optString("mode") == "link"
        val provider = when (providerName) {
            "wechat" -> Provider.WECHAT
            "qq" -> Provider.QQ
            "bilibili" -> Provider.BILIBILI
            "alipay" -> Provider.ALIPAY
            "github" -> if (link) Provider.GITHUB else null
            else -> null
        }
        if (provider == null) {
            emit(resultJson(ok = false, code = "AUTH", message = "未知登录方式：$providerName"))
            return
        }
        if (busy) {
            if (android.os.SystemClock.elapsedRealtime() - busyAt < BUSY_ZOMBIE_MS) {
                emit(resultJson(ok = false, code = "BUSY"))
                return
            }
            // 僵尸态：上一条在途的回呼永不到达（断链/进程级丢失）——清态放行本次请求
            // （emit=参数遮蔽名；置空成员须 this.）
            busy = false
            this.emit = null
        }
        if (provider == Provider.WECHAT && !isAvailable(provider)) {
            emit(resultJson(ok = false, code = "AUTH", message = "未安装微信客户端，无法使用微信登录"))
            return
        }
        busy = true
        busyAt = android.os.SystemClock.elapsedRealtime()
        this.linkMode = link
        this.invite = if (link) null else opts.optString("invite").trim().takeIf { it.isNotEmpty() }
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
            Provider.BILIBILI -> if (link) startWebLink("bilibili") else startBili()
            Provider.ALIPAY -> AlipayAuth.signIn(activity) { authCode, message ->
                // 回调线程无主线程承诺——runOnUiThread 收口（主线程调用=立即执行）
                activity.runOnUiThread {
                    if (authCode != null) signInApp("alipay", JSONObject().put("code", authCode))
                    else finish(resultJson(ok = false, code = "AUTH", message = message))
                }
            }
            Provider.GITHUB -> startWebLink("github")
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

    /** 上送端点（provider 单独参数拼入；wechat/qq/alipay 共用）：登录态
     *  /api/auth/app-social/sign-in（invite 拼入）；批⑥ 绑定态 /api/auth/app-social/link
     *  （会话 cookie 经 WebViewCookieJar 自动携带；响应无 isRegister/user，增 already）。 */
    private fun signInApp(provider: String, body: JSONObject) {
        body.put("provider", provider)
        if (!linkMode && invite != null && !body.has("invite")) body.put("invite", invite)
        ApiClient.postJson(
            if (linkMode) "/api/auth/app-social/link" else "/api/auth/app-social/sign-in",
            body,
        ) { code, json ->
            when {
                code == 200 && json?.optString("status") == "ok" -> finish(
                    if (linkMode) {
                        resultJson(ok = true).put("already", json.optBoolean("already", false))
                    } else {
                        resultJson(ok = true)
                            .put("isRegister", json.optBoolean("isRegister", false))
                            .put("user", json.optJSONObject("user") ?: JSONObject.NULL)
                    }
                )
                code == 404 -> finish(resultJson(ok = false, code = "SERVER", message = "该登录方式暂未启用"))
                code == 429 -> finish(resultJson(ok = false, code = "SERVER", message = "尝试过于频繁，请稍后再试"))
                // 批⑥：401（会话过期「请先登录后再绑定」）等服务端中文文案由此档直达
                code in 400..499 -> finish(resultJson(ok = false, code = "SERVER", message = json?.optString("message")?.takeIf { it.isNotEmpty() } ?: "授权失败（$code）"))
                else -> finish(resultJson(ok = false, code = "SERVER", message = "网络异常（${if (code < 0) "连接失败" else code}）"))
            }
        }
    }

    /** 批⑥ 绑定态浮层流（B站/GitHub）：POST /link-social 拿授权 url → 通用授权屏
     *  （BiliAuthActivity）承载整条回调链——会话 cookie 经共享 CookieManager 自动带，
     *  better-auth callback 在会话在场时执行关联；「已绑他人」被 errorCallbackURL 安全
     *  拒绝（account_already_linked_to_different_user），无会话劫持。finalUrl 含
     *  bindFailed=绑定失败（冲突/中途出错）；浮层被关闭=CANCEL 静默复位。 */
    private fun startWebLink(providerId: String) {
        val body = JSONObject()
            .put("provider", providerId)
            .put("callbackURL", "/settings/account-security?bound=1")
            .put("errorCallbackURL", "/settings/account-security?bindFailed=1")
        ApiClient.postJson("/api/auth/link-social", body) { code, json ->
            val url = json?.optString("url")
            if (code == 200 && !url.isNullOrEmpty()) {
                BiliAuthActivity.launch(activity, url) { ok, finalUrl ->
                    finish(
                        when {
                            !ok -> resultJson(ok = false, code = "CANCEL")
                            finalUrl != null && finalUrl.contains("bindFailed") ->
                                resultJson(ok = false, code = "AUTH", message = "绑定未完成，该账号可能已绑定其他用户")
                            else -> resultJson(ok = true)
                        }
                    )
                }
            } else {
                finish(resultJson(ok = false, code = "SERVER", message = "发起绑定失败（${if (code < 0) "网络异常" else code}）"))
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
