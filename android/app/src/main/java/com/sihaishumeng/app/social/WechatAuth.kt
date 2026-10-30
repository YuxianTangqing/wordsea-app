package com.sihaishumeng.app.social

import android.content.Context
import com.sihaishumeng.app.core.SiteConfig
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import java.security.SecureRandom

/**
 * 批③ 微信原生登录（open-sdk SendAuth）：拉起微信授权中间页 → 用户确认 →
 * WXEntryActivity.onResp 收 code（errCode 0/-2取消/-4拒绝/-6签名不符）→ 上送
 * 服务端换 openid+unionid（服务端用移动应用 appid/secret 换，unionid 硬必需）。
 *
 * state 随机数回校（防他应用伪造回跳）；微信必须安装（SDK 无扫码兜底——按钮
 * isAvailable 置灰引导安装）。
 */
object WechatAuth {

    private var api: IWXAPI? = null
    private var pendingState: String? = null

    /** WXEntryActivity.onResp 的接续（每次 send 前由调用方重置）。 */
    var onResp: ((code: String?, errCode: Int) -> Unit)? = null

    fun ensure(ctx: Context): IWXAPI =
        api ?: WXAPIFactory.createWXAPI(ctx, SiteConfig.WECHAT_APP_ID, true).also {
            it.registerApp(SiteConfig.WECHAT_APP_ID)
            api = it
        }

    fun isInstalled(ctx: Context): Boolean = ensure(ctx).isWXAppInstalled

    /** @return false=无法发起（未装微信）。 */
    fun send(ctx: Context, onResp: (code: String?, errCode: Int) -> Unit): Boolean {
        val iwx = ensure(ctx)
        if (!iwx.isWXAppInstalled) return false
        this.onResp = onResp
        pendingState = randomState()
        val req = SendAuth.Req().apply {
            scope = "snsapi_userinfo" // 登录唯一合法 scope
            state = pendingState
        }
        return iwx.sendReq(req)
    }

    /** WXEntryActivity 专用入口：state 校验后分发（过期/伪造回跳静默丢弃）。 */
    fun handleResp(resp: SendAuth.Resp) {
        val expected = pendingState
        val cb = onResp
        if (expected == null || cb == null || resp.state != expected) return
        pendingState = null
        onResp = null
        // errCode：0=OK（code 有效）/ -2=取消 / -4=拒绝（BaseResp.ErrCode 官方值）
        cb(if (resp.errCode == 0) resp.code else null, resp.errCode)
    }

    private fun randomState(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
