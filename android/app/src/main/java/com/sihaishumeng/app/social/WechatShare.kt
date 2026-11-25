package com.sihaishumeng.app.social

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.sihaishumeng.app.core.SiteConfig
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage
import com.tencent.mm.opensdk.modelmsg.WXWebpageObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * M387 微信分享网页卡片（open-sdk Webpage 消息）：App 内点分享微信渠道 → SDK 把
 * 标题/描述/缩略图/链接打包成卡片直接发给微信——微信照单显示（**不抓 og、不受域名
 * 信誉限制**；web 端粘贴链接出卡需等新域名观察期，App 卡片即时可用，息壤同款机制）。
 * 缩略图：thumbUrl 下载（host 白名单在 ShellBridge 已过）→ 保持比例压缩 100 宽
 * JPEG ≤32KB（微信硬限）；3s 超时/失败回落无图（微信显示默认图标，卡片仍可发）。
 * 回执：WXEntryActivity.onResp 收 SendMessageToWX.Resp 静默（已发送/取消由微信
 * 客户端自行反馈）。未装微信（isWXAppInstalled=false）→ share 返 false（web toast）。
 */
object WechatShare {

    private const val TAG = "WechatShare"

    private val thumbClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * @return true=已受理（微信客户端接管）；false=未装微信。调用前提：ShellBridge
     * 已做 host 白名单校验。缩略图下载在后台线程（主线程网络=ANR 风险），下载完
     * 切回主线程 sendReq（SDK 惯例主线程调用）。
     */
    fun share(activity: Activity, title: String, desc: String, url: String, thumbUrl: String): Boolean {
        val iwx = WechatAuth.ensure(activity) // 复用登录同款 API 实例（registerApp 已在）
        if (!iwx.isWXAppInstalled) return false
        Thread {
            val thumb = loadThumb(thumbUrl)
            activity.runOnUiThread { send(iwx, title, desc, url, thumb) }
        }.start()
        return true
    }

    private fun send(iwx: com.tencent.mm.opensdk.openapi.IWXAPI, title: String, desc: String, url: String, thumb: ByteArray?) {
        try {
            val webpage = WXWebpageObject().apply { webpageUrl = url }
            val msg = WXMediaMessage(webpage).apply {
                this.title = title.take(60)
                this.description = desc.take(120)
                thumb?.let { thumbData = it }
            }
            val req = SendMessageToWX.Req().apply {
                transaction = "share-web-${System.currentTimeMillis()}"
                message = msg
                scene = SendMessageToWX.Req.WXSceneSession // 会话（朋友圈不做）
            }
            if (!iwx.sendReq(req)) Log.w(TAG, "sendReq false")
        } catch (e: Exception) {
            Log.e(TAG, "send", e)
        }
    }

    /** 缩略图下载+压缩：保持比例 100 宽 JPEG ≤32KB；任何失败/超限回落 null（无图卡） */
    private fun loadThumb(url: String): ByteArray? {
        if (url.isBlank()) return null
        return try {
            val req = Request.Builder().url(url).header("User-Agent", SiteConfig.DESKTOP_UA).build()
            thumbClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val scale = 100f / bmp.width
                val scaled = Bitmap.createScaledBitmap(
                    bmp, 100, (bmp.height * scale).toInt().coerceAtLeast(1), true
                )
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                val jpeg = out.toByteArray()
                if (jpeg.size <= 32 * 1024) jpeg else null // 超微信硬限回落无图（防拒收）
            }
        } catch (e: IOException) {
            Log.w(TAG, "loadThumb failed: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "loadThumb error", e)
            null
        }
    }
}
