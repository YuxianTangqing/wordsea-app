package com.sihaishumeng.app.core.session

import com.sihaishumeng.app.core.api.ApiClient
import org.json.JSONObject

/**
 * 批④ 原生侧会话存储：登录态单点事实源（此前原生零 session 概念，登录态只在
 * WebView cookie 里——原生页需要「已登录否+我是谁」必须有本件）。
 *
 * 探测口=GET /api/v1/me（requireUser：200=登录并回全量 user；401=游客）——比
 * better-auth get-session 多回 uid/username/nickname 等展示字段，且 401 语义干净。
 * 缓存进程内（cookie 本就是持久事实源，冷启动按需 refresh 即可）。
 *
 * 失效源两处（防「会话过期后原生页还假装登录态」）：
 * - Api 层 401 → markLoggedOut()（见 Api.settle）
 * - LoginActivity 登录成功 → markLoginChanged()
 */
object SessionStore {

    data class SUser(
        val id: String,
        val uid: Int?,
        val username: String?,
        val nickname: String?,
        val avatarUrl: String?,
    )

    @Volatile
    private var cached: SUser? = null

    /** 是否已发起过探测（false=冷启动未查；peek 与「查过=游客」区分，强登录页据此先 refresh） */
    @Volatile
    private var queried = false

    @Volatile
    private var refreshing = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val listeners = mutableListOf<(SUser?) -> Unit>()
    private val waiting = mutableListOf<(SUser?) -> Unit>()

    /** 缓存读（未查过返回 null=「未知」，与「查过=游客 null」语义有别） */
    fun peek(): SUser? = cached

    val hasQueried: Boolean get() = queried

    fun addListener(l: (SUser?) -> Unit) {
        listeners.add(l)
        // 注册即回放当前值（页面晚注册也能拿到态；未知态不回放，等探测结果）
        if (queried) mainHandler.post { l(cached) }
    }

    /** 探测（并发去重，只在途一发；cb 主线程回放结果，null=游客）。 */
    fun refresh(cb: ((SUser?) -> Unit)? = null) {
        if (cb != null) synchronized(waiting) { waiting.add(cb) }
        if (refreshing) return
        refreshing = true
        ApiClient.getJson("/api/v1/me") { code, json ->
            val user = if (code == 200 && json != null && json.optInt("code", -1) == 0) {
                parseUser(json.optJSONObject("data"))
            } else {
                null // 401/网络失败：统一游客降级（下次再探；cookie 仍在，成功登录不受影响）
            }
            cached = user
            queried = true
            refreshing = false
            val toCall = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
            mainHandler.post {
                (toCall.toSet() + listeners.toList()).forEach { it(user) }
            }
        }
    }

    /** Api 层 401：清缓存广播（强登录页据此降游客墙） */
    fun markLoggedOut() {
        if (cached != null || !queried) {
            cached = null
            queried = true
            mainHandler.post { listeners.toList().forEach { it(null) } }
        }
    }

    /** 登录成功（LoginActivity/social 链收尾）：清态立即重探（cookie 已在共享 CookieManager） */
    fun markLoginChanged() {
        cached = null
        queried = false
        refresh()
    }

    private fun parseUser(data: JSONObject?): SUser? {
        if (data == null || data.optString("id").isEmpty()) return null
        return SUser(
            id = data.optString("id"),
            uid = if (data.isNull("uid")) null else data.optInt("uid"),
            username = data.optString("username").ifEmpty { null },
            nickname = data.optString("nickname").ifEmpty { null },
            avatarUrl = data.optString("avatarUrl").ifEmpty { null },
        )
    }
}
