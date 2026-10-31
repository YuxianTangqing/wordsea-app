package com.sihaishumeng.app.core.api

import android.os.Handler
import android.os.Looper
import com.sihaishumeng.app.core.SiteConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 批③ 原生 API 客户端（单例）：登录屏/社交登录与站点 API 的 JSON 通道。
 *
 * 登录态三处共享的关键件：cookieJar=WebViewCookieJar——OkHttp 收到的 Set-Cookie
 * 自动写进 android.webkit.CookieManager（https://www.sihaishumeng.com），长尾
 * WebView 天然带上；发请求时也从 CookieManager 拼 Cookie 头（原生会话续用网页侧）。
 *
 * 固定头（对齐站点防线）：Origin=站址（better-auth trustedOrigins 放行；无 cookie
 * 请求本可省略，带上为续用会话的请求铺路）+ Referer=站址（M260 文章接口同站闸同款）。
 * 回调统一主线程。
 *
 * 批⑨ 增 getJson/putJson（听书会话拉章/进度心跳/rotate 续期）。
 */
object ApiClient {

    private val main = Handler(Looper.getMainLooper())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** GET {SITE_URL}{path}（path 已含 query）；cb(code, body) 永远主线程，失败 body=null。 */
    fun getJson(path: String, cb: (Int, JSONObject?) -> Unit) =
        exec("GET", path, null, cb)

    /** POST {SITE_URL}{path}，JSON body；cb(code, body) 永远主线程，网络/解析失败 body=null。 */
    fun postJson(path: String, body: JSONObject, cb: (Int, JSONObject?) -> Unit) =
        exec("POST", path, body, cb)

    /** PUT {SITE_URL}{path}，JSON body（批⑨ 进度心跳）。 */
    fun putJson(path: String, body: JSONObject, cb: (Int, JSONObject?) -> Unit) =
        exec("PUT", path, body, cb)

    private fun exec(method: String, path: String, body: JSONObject?, cb: (Int, JSONObject?) -> Unit) {
        val builder = okhttp3.Request.Builder()
            .url(SiteConfig.SITE_URL + path)
            .header("Origin", SiteConfig.SITE_URL)
            .header("Referer", SiteConfig.SITE_URL + "/")
        when {
            method == "GET" -> builder.get()
            body != null -> builder.method(
                method,
                body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            else -> builder.method(method, ByteArray(0).toRequestBody(null))
        }
        client.newCall(builder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                main.post { cb(-1, null) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val text = runCatching { response.body?.string() }.getOrNull()
                val json = try {
                    text?.let { JSONObject(it) }
                } catch (_: Exception) {
                    null
                }
                val code = response.code
                response.close()
                main.post { cb(code, json) }
            }
        })
    }
}
