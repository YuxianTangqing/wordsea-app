package com.sihaishumeng.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.core.SiteConfig
import com.sihaishumeng.app.core.web.NativeRoute
import com.sihaishumeng.app.core.web.NativeRouteTable
import com.sihaishumeng.app.ui.ForumBoardActivity
import com.sihaishumeng.app.ui.InviteLandingActivity
import com.sihaishumeng.app.ui.LoginActivity
import com.sihaishumeng.app.ui.NovelDetailActivity
import com.sihaishumeng.app.ui.NotificationsActivity
import com.sihaishumeng.app.ui.PostDetailActivity
import com.sihaishumeng.app.ui.ProfileEditActivity
import com.sihaishumeng.app.ui.SearchActivity

/**
 * 批④ 原生页统一出口：站内 URL 查 NativeRouteTable——命中起对应原生页；未命中回
 * MainActivity WebView（EXTRA_OPEN_URL，阅读器/发帖/studio/文章/用户页全走此口）。
 * 站外 http(s) 直送系统浏览器（与 WebViewRouter 同语义，原生页语境无横幅）。
 */
object NativeNav {

    /** path（"/post/1"）或完整 URL 均可 */
    fun open(context: Context, pathOrUrl: String) {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else SiteConfig.SITE_URL + pathOrUrl
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val host = uri.host?.lowercase()
        if (host != null && host in SiteConfig.SITE_HOSTS) {
            deepIntent(context, uri)?.let { context.startActivity(it); return }
            when (val r = NativeRouteTable.match(uri)) {
                NativeRoute.Login -> { context.startActivity(Intent(context, LoginActivity::class.java)); return }
                is NativeRoute.TabShelf -> { openTab(context, MainActivity.TAB_SHELF, null); return }
                is NativeRoute.TabLibrary -> { openTab(context, MainActivity.TAB_LIBRARY, r.segment); return }
                NativeRoute.TabForum -> { openTab(context, MainActivity.TAB_FORUM, null); return }
                else -> {}
            }
        }
        if (host == null || (!pathOrUrl.startsWith("http"))) return
        if (host !in SiteConfig.SITE_HOSTS) {
            // 站外 http(s)：送系统浏览器（授权大域在原生页语境不应出现——登录链除外）
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
            }
            return
        }
        // WebView 长尾：MainActivity singleTask 复用，onNewIntent 定向加载（不接管）
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** MainActivity 接管口专用：只起深页 Activity（无 WebView 回退语义） */
    fun launchDeep(context: Context, uri: Uri) {
        deepIntent(context, uri)?.let { context.startActivity(it) }
    }

    /** 深页路由 → 启动 Intent（tab/login 目标返回 null=非深页） */
    private fun deepIntent(context: Context, uri: Uri): Intent? = when (val route = NativeRouteTable.match(uri)) {
        is NativeRoute.NovelDetail -> Intent(context, NovelDetailActivity::class.java)
            .putExtra(NovelDetailActivity.EXTRA_IDENTIFIER, route.identifier)
            .putExtra(NovelDetailActivity.EXTRA_KIND, route.kind)
        is NativeRoute.Board -> Intent(context, ForumBoardActivity::class.java)
            .putExtra(ForumBoardActivity.EXTRA_SLUG, route.slug)
        is NativeRoute.PostDetail -> Intent(context, PostDetailActivity::class.java)
            .putExtra(PostDetailActivity.EXTRA_ID, route.idOrNo)
        is NativeRoute.Search -> Intent(context, SearchActivity::class.java)
            .putExtra(SearchActivity.EXTRA_QUERY, route.query)
        NativeRoute.Notifications -> Intent(context, NotificationsActivity::class.java)
        is NativeRoute.InviteLanding -> Intent(context, InviteLandingActivity::class.java)
            .putExtra(InviteLandingActivity.EXTRA_CODE, route.code)
        NativeRoute.ProfileEdit -> Intent(context, ProfileEditActivity::class.java)
        else -> null
    }

    private fun openTab(context: Context, tab: String, segment: String?) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (segment != null) intent.putExtra(MainActivity.EXTRA_TAB_SEGMENT, segment)
        context.startActivity(intent)
    }
}
