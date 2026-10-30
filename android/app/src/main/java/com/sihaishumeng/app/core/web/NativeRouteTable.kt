package com.sihaishumeng.app.core.web

import android.net.Uri

/**
 * 批④ 原生路由表：站内路径 → 原生页目标的有序匹配（先胜）。MainActivity 接管三口
 * （shouldOverrideUrlLoading 硬导航 / doUpdateVisitedHistory 软导航 / onPageStarted
 * 302 兜底）与 NativeNav（原生页→原生/WebView 出口）共用本表。
 *
 * 恒 WebView（匹配=null）的既定边界：阅读器（novels 域一切 chapter 三段路径）、
 * TipTap 发帖/编辑、studio/admin、rankings/reading-report/users 个人主页、论坛
 * ISR 变体（forum/v 前缀）与精华聚合（forum/essence）、注册/找回（auth 前缀内除
 * login 外的一切）。
 *
 * （⚠️ Kotlin 块注释可嵌套——注释里勿写「斜杠星」形态的路径通配。）
 */
sealed class NativeRoute {
    object Login : NativeRoute()
    object TabShelf : NativeRoute()

    /** 书库 tab：segment=novels|articles|classics（/novels 与 /novels?type=articles 与 /classics） */
    data class TabLibrary(val segment: String) : NativeRoute()
    object TabForum : NativeRoute()

    /** 书籍详情：identifier=路由段原样（数字或 slug）；kind=novels|classics（数字链域，透传 API） */
    data class NovelDetail(val identifier: String, val kind: String) : NativeRoute()
    data class Board(val slug: String) : NativeRoute()

    /** 帖详情：idOrNo=数字 no 或 cuid */
    data class PostDetail(val idOrNo: String) : NativeRoute()
    data class Search(val query: String?) : NativeRoute()
    object Notifications : NativeRoute()
    data class InviteLanding(val code: String) : NativeRoute()
    object ProfileEdit : NativeRoute()
}

object NativeRouteTable {

    /** forum 域保留段：v=ISR 变体页、essence=精华聚合——恒 WebView */
    private val FORUM_RESERVED = setOf("v", "essence")

    /**
     * 匹配站内路径（调用方已保证 host∈SITE_HOSTS）。query 用于 TabLibrary 的
     * type=articles 分段与 Search 的 q 预填。未命中返回 null=WebView。
     */
    fun match(uri: Uri): NativeRoute? {
        val path = uri.path ?: return null
        val segs = path.split('/').filter { it.isNotEmpty() }
        val first = segs.getOrNull(0) ?: return null // 站根=首页，WebView
        return when {
            // auth 前缀：仅登录页原生（注册/找回恒 WebView）
            first == "auth" && segs.getOrNull(1) == "login" && segs.size == 2 -> NativeRoute.Login
            first == "bookshelf" && segs.size == 1 -> NativeRoute.TabShelf
            first == "novels" && segs.size == 1 ->
                NativeRoute.TabLibrary(if (uri.getQueryParameter("type") == "articles") "articles" else "novels")
            first == "classics" && segs.size == 1 -> NativeRoute.TabLibrary("classics")
            // 书籍详情恰两段；三段及以上=章节页（chapter 前缀）或其他子资源，恒 WebView 阅读器
            first == "novels" && segs.size == 2 -> NativeRoute.NovelDetail(segs[1], "novels")
            first == "classics" && segs.size == 2 -> NativeRoute.NovelDetail(segs[1], "classics")
            first == "forum" && segs.size == 1 -> NativeRoute.TabForum
            first == "forum" && segs.size == 2 && segs[1] !in FORUM_RESERVED -> NativeRoute.Board(segs[1])
            first == "post" && segs.size == 2 -> NativeRoute.PostDetail(segs[1])
            first == "search" && segs.size == 1 ->
                NativeRoute.Search(uri.getQueryParameter("q"))
            first == "notifications" && segs.size == 1 -> NativeRoute.Notifications
            first == "invite" && segs.size == 2 -> NativeRoute.InviteLanding(segs[1])
            first == "settings" && segs.size == 2 && segs[1] == "profile" -> NativeRoute.ProfileEdit
            else -> null
        }
    }
}
