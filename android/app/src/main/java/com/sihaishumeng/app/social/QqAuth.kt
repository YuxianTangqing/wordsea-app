package com.sihaishumeng.app.social

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.sihaishumeng.app.core.SiteConfig
import com.tencent.tauth.IUiListener
import com.tencent.tauth.Tencent
import com.tencent.tauth.UiError
import org.json.JSONObject

/**
 * 批③ QQ 原生登录（open-sdk tauth）：Tencent.login 拉起手机QQ（未装走 SDK 内置
 * WAP 授权页）→ 回调 JSONObject 自带 openid+access_token（SDK 内部已换完，无 code）
 * → 上送服务端 /me 复核 client_id（防他应用 token 重放）+ 取 unionid。
 *
 * 两个 SDK 铁坑（官方 wiki）：
 * - IUiListener 必须成员持有——SDK 对 listener 是弱引用，局部变量=回调被 GC 丢失；
 * - 授权结果的 onActivityResult 必须回灌 Tencent.onActivityResultData，
 *   由宿主 Activity（批④W 起=MainActivity）透传给本单例。
 */
object QqAuth {

    private var tencent: Tencent? = null
    private var listener: IUiListener? = null

    fun ensure(ctx: Context): Tencent =
        tencent ?: Tencent.createInstance(SiteConfig.QQ_APP_ID, ctx.applicationContext).also {
            tencent = it
        }

    fun login(activity: Activity, onResult: (json: JSONObject?) -> Unit) {
        val t = ensure(activity)
        listener = object : IUiListener {
            override fun onComplete(o: Any?) {
                onResult(o as? JSONObject)
            }

            override fun onCancel() {
                onResult(null)
            }

            override fun onError(e: UiError?) {
                if (e != null) android.util.Log.w("QqAuth", "login onError: ${e.errorCode} ${e.errorMessage}")
                onResult(null)
            }

            /** 3.53.0 接口新成员（无 UI 的告警透传）——空实现即可。 */
            override fun onWarning(p0: Int) {
            }
        }
        t.login(activity, "get_user_info", listener)
    }

    /** 宿主 Activity.onActivityResult 透传口（SDK 结果回灌）。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        listener?.let { Tencent.onActivityResultData(requestCode, resultCode, data, it) }
    }
}
