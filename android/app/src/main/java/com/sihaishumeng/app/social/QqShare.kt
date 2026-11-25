package com.sihaishumeng.app.social

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.tencent.connect.share.QQShare
import com.tencent.tauth.IUiListener
import com.tencent.tauth.UiError

/**
 * M387 QQ 分享网页卡片（QQ 互联 SDK shareToQQ/shareToQzone）：App 内点分享 QQ/
 * QQ 空间渠道 → 卡片消息直接发到 QQ——标题/摘要/链接/缩略图由 App 提供（imageUrl
 * 网络图由 QQ 客户端自抓，**无需本地下载**），不受域名信誉限制。
 * 回执静默（QQ 客户端自行反馈）。未装 QQ → share 返 false（web toast）。
 * 铁坑（QqAuth 同款，官方 wiki）：IUiListener 必须成员持有——SDK 对 listener 是
 * 弱引用，局部变量=回调被 GC 丢失。
 */
object QqShare {

    private const val TAG = "QqShare"

    private var listener: IUiListener? = null

    /**
     * @param target 'qq'=QQ 会话 / 'qzone'=QQ 空间。
     * @return true=已受理；false=未装 QQ。调用前提：ShellBridge 已做 host 白名单校验。
     */
    fun share(
        activity: Activity,
        title: String,
        summary: String,
        targetUrl: String,
        imageUrl: String,
        target: String,
    ): Boolean {
        val t = QqAuth.ensure(activity)
        if (!t.isQQInstalled(activity)) return false
        listener = object : IUiListener {
            override fun onComplete(o: Any?) {
                // 已发送（QQ 客户端自行反馈）——App 侧静默
            }

            override fun onCancel() {
            }

            override fun onError(e: UiError?) {
                if (e != null) Log.w(TAG, "share onError: ${e.errorCode} ${e.errorMessage}")
            }

            /** 3.53.0 接口新成员（无 UI 的告警透传）——空实现即可。 */
            override fun onWarning(p0: Int) {
            }
        }
        val params = Bundle().apply {
            putInt(QQShare.SHARE_TO_QQ_KEY_TYPE, QQShare.SHARE_TO_QQ_TYPE_DEFAULT)
            putString(QQShare.SHARE_TO_QQ_TITLE, title.take(30))
            putString(QQShare.SHARE_TO_QQ_SUMMARY, summary.take(40))
            putString(QQShare.SHARE_TO_QQ_TARGET_URL, targetUrl)
            if (imageUrl.isNotBlank()) putString(QQShare.SHARE_TO_QQ_IMAGE_URL, imageUrl)
            putString(QQShare.SHARE_TO_QQ_APP_NAME, "四海书盟")
            putInt(QQShare.SHARE_TO_QQ_EXT_INT, QQShare.SHARE_TO_QQ_FLAG_QZONE_ITEM_HIDE)
        }
        try {
            if (target == "qzone") t.shareToQzone(activity, params, listener)
            else t.shareToQQ(activity, params, listener)
        } catch (e: Exception) {
            Log.e(TAG, "share", e)
            return false
        }
        return true
    }
}
