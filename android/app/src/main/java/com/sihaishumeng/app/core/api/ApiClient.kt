package com.sihaishumeng.app.core.api

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sihaishumeng.app.core.SiteConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 原生 API 客户端（单例）：批③ 登录链起线，批④ 扩 GET/PATCH/DELETE 与 query 拼装。
 *
 * 登录态三处共享的关键件：cookieJar=WebViewCookieJar——OkHttp 收到的 Set-Cookie
 * 自动写进 android.webkit.CookieManager（https://www.sihaishumeng.com），长尾
 * WebView 天然带上；发请求时也从 CookieManager 拼 Cookie 头（原生会话续用网页侧）。
 *
 * 固定头（对齐站点防线）：Origin=站址（better-auth trustedOrigins 放行；无 cookie
 * 请求本可省略，带上为续用会话的请求铺路）+ Referer=站址（M260 文章接口同站闸同款）。
 * 回调统一主线程；网络/解析失败 code=-1、body=null。
 */
object ApiClient {

    private const val TAG = "ApiClient"

    private val main = Handler(Looper.getMainLooper())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** POST {SITE_URL}{path}，JSON body；cb(code, body) 永远主线程，网络/解析失败 body=null。 */
    fun postJson(path: String, body: JSONObject, cb: (Int, JSONObject?) -> Unit) {
        send("POST", path, null, body, cb)
    }

    /** GET {SITE_URL}{path}?{query}；query 值 null 的键跳过（筛选态直传 Map 的惯用形态）。 */
    fun getJson(path: String, query: Map<String, String?>? = null, cb: (Int, JSONObject?) -> Unit) {
        send("GET", path, query, null, cb)
    }

    /** PATCH/DELETE/PUT（可选 JSON body）——批④ 写操作（已读/删除/资料保存）共用。 */
    fun sendJson(
        method: String,
        path: String,
        body: JSONObject? = null,
        cb: (Int, JSONObject?) -> Unit,
    ) {
        send(method, path, null, body, cb)
    }

    private fun send(
        method: String,
        path: String,
        query: Map<String, String?>?,
        body: JSONObject?,
        cb: (Int, JSONObject?) -> Unit,
    ) {
        val url = buildString {
            append(SiteConfig.SITE_URL).append(path)
            if (!query.isNullOrEmpty()) {
                var first = true
                for ((k, v) in query) {
                    if (v == null) continue
                    append(if (first) '?' else '&').also { first = false }
                    append(encode(k)).append('=').append(encode(v))
                }
            }
        }
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Origin", SiteConfig.SITE_URL)
            .header("Referer", SiteConfig.SITE_URL + "/")
        when {
            body != null -> builder.method(
                method,
                body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            method == "GET" -> builder.get()
            else -> builder.method(method, okhttp3.RequestBody.create(null, ByteArray(0)))
        }
        client.newCall(builder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.w(TAG, "$method $path 失败: ${e.message}")
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

    private fun encode(s: String): String =
        try {
            URLEncoder.encode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
}
