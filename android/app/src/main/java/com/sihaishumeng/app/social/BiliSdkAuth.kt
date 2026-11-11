package com.sihaishumeng.app.social

import android.app.Activity
import android.content.Intent
import com.sihaishumeng.app.core.SiteConfig
import com.bilibili.lib.accountoauth.OAuthManager

/**
 * 批⑧ B站原生登录（accountoauth-2.0.0.0 SDK，手动 AAR）：OAuthManager 拉起B站
 * 客户端授权（隐式 VIEW bilibili.oauth://main——manifest queries 须 scheme 形态），
 * 回跳 code 经宿主 Activity.onActivityResult 透传到本单例再分发；未装B站客户端
 * SDK 自兜内置 OauthWebActivity（H5 授权页，AAR manifest 自带声明）——无我方分支。
 *
 * onGetCode(code, msg)：code 非空=授权码（上送服务端换 openid+union_id）；
 * 空=失败/取消（msg 带B站侧错误信息，含后台签名/包名配置错——真机拉起段的
 * 诊断定位器）。会话收口必须 finish()（SDK 静态引用持有宿主 Activity，不清=泄漏）。
 *
 * client_id=网页应用 bd4c6315d7f14d99（批⑧R 安卓平台并入后同应用双平台；SiteConfig
 * 公开标识，Secret 只在服务器 .env）。同应用=同 openid：服务端 resolveBilibiliIdentity
 * 按 openid 键直连存量账号（union_id 缺席回落链），零桥接零裂号——旧移动应用
 * client_id 7a3a27dff0d04936 待验收后从平台删除。
 */
object BiliSdkAuth {

    private var manager: OAuthManager? = null
    private var onResult: ((code: String?, msg: String?) -> Unit)? = null

    /** 拉起授权。onResult 在 SDK 回调线程到达（无主线程承诺——调用方自行 runOnUiThread）。 */
    fun start(activity: Activity, onResult: (code: String?, msg: String?) -> Unit) {
        finish()
        this.onResult = onResult
        manager = OAuthManager(activity, SiteConfig.BILI_CLIENT_ID, activity.packageName).apply {
            // ⭐SDK 是 Kotlin 编的：IOauthCallback 非 SAM 接口，lambda 隐转不成立须显式 object
            setOAuthCallback(object : OAuthManager.IOauthCallback {
                override fun onGetCode(code: String?, msg: String?) {
                    val cb = this@BiliSdkAuth.onResult ?: return
                    this@BiliSdkAuth.onResult = null
                    cb(code?.takeIf { it.isNotEmpty() }, msg?.takeIf { it.isNotEmpty() })
                }
            })
            startOauth()
        }
    }

    /** 宿主 onActivityResult 透传（SocialAuthController.onActivityResult→本口）。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        manager?.onActivityResult(requestCode, resultCode, data)
    }

    /** 会话收口：清 SDK 静态引用（防 Activity 泄漏）+挂起回调。 */
    fun finish() {
        manager?.finish()
        manager = null
        onResult = null
    }
}
