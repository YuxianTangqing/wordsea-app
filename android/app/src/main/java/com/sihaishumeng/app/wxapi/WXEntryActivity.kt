package com.sihaishumeng.app.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.sihaishumeng.app.core.SiteConfig
import com.sihaishumeng.app.social.WechatAuth
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory

/**
 * 微信回跳页（开放平台硬契约，错一字回调静默不达）：
 * - 包名必须是 {applicationId}.wxapi（即 com.sihaishumeng.app.wxapi）
 * - 类名必须是 WXEntryActivity，exported，manifest 无 intent-filter
 * - onCreate/onNewIntent 都要 api.handleIntent
 * 只做一件事：把 SendAuth.Resp 递给 WechatAuth（state 校验/分发在彼处），随即 finish。
 */
class WXEntryActivity : Activity(), IWXAPIEventHandler {

    private var api: IWXAPI? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = WXAPIFactory.createWXAPI(this, SiteConfig.WECHAT_APP_ID, false).also {
            it.registerApp(SiteConfig.WECHAT_APP_ID)
            it.handleIntent(intent, this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        api?.handleIntent(intent, this)
    }

    override fun onResp(resp: BaseResp) {
        if (resp is SendAuth.Resp) WechatAuth.handleResp(resp)
        finish()
    }

    override fun onReq(req: BaseReq) {
        finish()
    }
}
