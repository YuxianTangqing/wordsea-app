package com.sihaishumeng.app.social

import android.app.Activity
import android.os.Bundle
import com.alipay.sdk.app.OpenAuthTask
import com.sihaishumeng.app.core.SiteConfig
import java.security.SecureRandom

/**
 * 批③ 支付宝原生登录（官方 alipaysdk-android 15.8.42，Maven 分发——原「手动下载
 * 极简版 AAR」待办作废）：OpenAuthTask 拉起支付宝授权（未装支付宝时第 5 参 true =
 * SDK 内置 H5 中转页兜底）→ 回调 Bundle 直取 auth_code 上送服务端换 user_id。
 *
 * user_id 同商户跨应用同值＝与网页流天然同账号，无 unionid 工程（规划批③补充节
 * 定案：移动侧裂号风险=零）。
 *
 * SDK 契约（javap 实测 15.8.42 + 官方 Demo 摘录交叉验证）：
 * - execute(scheme, BizType.AccountAuth, bizParams{url}, callback, true)；
 *   url=authweb.alipay.com/auth?auth_type=PURE_OAUTH_SDK&app_id=…&scope=auth_user&state=…
 * - 回调码 OK=9000 / NOT_INSTALLED=4001 / Duplex=5000 / SYS_ERR=4000；
 * - 原生回跳由 AAR manifest 自带的 AlipayResultActivity（exported 显式组件）接收，
 *   经 SDK 静态回调表分发——商户侧零 manifest 配置。
 *
 * state 随机数回校是「回显才校」的弱校验：H5 中转链不透传 state，缺省放行——防
 * 重放的真实边界在服务端（auth_code 单次有效，且只有本应用私钥能换 token）。
 */
object AlipayAuth {

    private var pendingState: String? = null

    fun signIn(activity: Activity, onResult: (authCode: String?, userMessage: String?) -> Unit) {
        pendingState = randomState()
        val authUrl = "https://authweb.alipay.com/auth?auth_type=PURE_OAUTH_SDK" +
            "&app_id=${SiteConfig.ALIPAY_APP_ID}&scope=auth_user&state=$pendingState"
        val bizParams = HashMap<String, String>().apply { put("url", authUrl) }
        OpenAuthTask(activity).execute(
            SiteConfig.ALIPAY_SCHEME,
            OpenAuthTask.BizType.AccountAuth,
            bizParams,
            OpenAuthTask.Callback { code, memo, bundle -> handleResult(code, memo, bundle, onResult) },
            true,
        )
    }

    private fun handleResult(
        code: Int,
        memo: String?,
        bundle: Bundle?,
        onResult: (String?, String?) -> Unit,
    ) {
        // 回调线程不确定（SDK 内部 Handler 调度，未见主线程承诺文档）——终态统一由
        // 调用方（SocialAuthController）runOnUiThread 收口；本函数不碰 UI。
        when (code) {
            OpenAuthTask.OK -> {
                val authCode = bundle?.getString("auth_code")
                val echoed = bundle?.getString("state")
                when {
                    authCode.isNullOrEmpty() -> onResult(null, "支付宝授权未完成")
                    echoed != null && echoed != pendingState -> onResult(null, "支付宝回执校验失败")
                    else -> onResult(authCode, null)
                }
            }
            OpenAuthTask.NOT_INSTALLED -> onResult(null, "未安装支付宝客户端")
            else -> {
                val detail = if (memo.isNullOrEmpty()) "" else "：$memo"
                onResult(null, "支付宝授权失败（$code$detail）")
            }
        }
    }

    private fun randomState(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
