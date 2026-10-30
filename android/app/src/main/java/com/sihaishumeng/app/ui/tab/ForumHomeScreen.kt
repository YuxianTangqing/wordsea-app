package com.sihaishumeng.app.ui.tab

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.relativeTime
import com.sihaishumeng.app.ui.common.toast
import com.sihaishumeng.app.ui.forum.BoardLite
import com.sihaishumeng.app.ui.forum.HotPostItem
import com.sihaishumeng.app.ui.forum.LatestReplyItem
import com.sihaishumeng.app.ui.forum.parseForumHome
import org.json.JSONObject

/**
 * 批④ 社区 tab（公开，论坛门面）：版块列表 + 热帖三档 chips + 最新回复。
 * 数据=GET forum/home 一次成型（定长内容，无分页——ScrollView+行重建，不进
 * RecyclerView）。口径=ISR 游客版（无屏蔽过滤，对拍 /forum/v 变体页）。
 * 精华聚合/发帖入口跳 WebView（forum/essence 恒 WebView + TipTap 纪律）。
 */
@SuppressLint("SetTextI18n")
class ForumHomeScreen(host: MainActivity) : TabScreen(host) {

    private var boards: List<BoardLite> = emptyList()
    private var hot: Map<String, List<HotPostItem>> = emptyMap()
    private var latest: List<LatestReplyItem> = emptyList()
    private var hotRange = "today"
    private var loaded = false
    private var loading = false

    private lateinit var body: LinearLayout
    private lateinit var states: StateViews
    private lateinit var hotChips: Map<String, TextView>
    private lateinit var hotList: LinearLayout

    override fun build(): View {
        val root = android.widget.FrameLayout(host).apply { setBackgroundColor(host.getColor(R.color.paper)) }
        val swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(host).apply {
            setColorSchemeResources(R.color.brand)
            setProgressBackgroundColorSchemeResource(R.color.paper)
            setOnRefreshListener { load { isRefreshing = false } }
        }
        val scroll = android.widget.ScrollView(host).apply { isFillViewport = false }
        body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, host.dp(16)) }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        swipe.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(swipe, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        states = StateViews(host, root)
        load()
        return root
    }

    override fun onShow() {
        if (!loaded && !loading) load()
    }

    private fun load(onDone: () -> Unit = {}) {
        loading = true
        if (!loaded) states.loading()
        Api.get("/api/v1/forum/home") { r ->
            loading = false
            when (r) {
                is ApiResult.Ok -> {
                    loaded = true
                    val triple = r.data?.let { parseForumHome(it) }
                    if (triple == null) {
                        states.error(host.getString(R.string.net_error)) { load() }
                    } else {
                        boards = triple.first
                        hot = triple.second
                        latest = triple.third
                        if (hot[hotRange].isNullOrEmpty()) hotRange = listOf("today", "week", "month").firstOrNull { !hot[it].isNullOrEmpty() } ?: "today"
                        states.clear()
                        render()
                    }
                }
                is ApiResult.Err -> {
                    states.error(r.message) { load() }
                }
                ApiResult.Net -> states.error(host.getString(R.string.net_error)) { load() }
            }
            onDone()
        }
    }

    private fun sectionTitle(text: String): TextView =
        TextView(host).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(host.getColor(R.color.brand))
            setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(4))
        }

    private fun render() {
        body.removeAllViews()

        // 版块区
        body.addView(sectionTitle(host.getString(R.string.forum_boards)))
        boards.forEach { b ->
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(host.dp(16), host.dp(9), host.dp(16), host.dp(9))
                isClickable = true
                isFocusable = true
                background = host.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                setOnClickListener { host.openNativeOrWeb("/forum/${b.slug}") }
            }
            val line = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            line.addView(TextView(host).apply {
                text = b.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.getColor(R.color.ink))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (b.todayCount > 0) {
                line.addView(TextView(host).apply {
                    text = host.getString(R.string.forum_today_fmt, b.todayCount)
                    textSize = 11f
                    setTextColor(host.getColor(R.color.brand))
                    background = host.getDrawable(R.drawable.badge_tag)
                    setPadding(host.dp(8), host.dp(2), host.dp(8), host.dp(2))
                })
            }
            if (b.description.isNotEmpty()) {
                row.addView(TextView(host).apply {
                    text = b.description
                    textSize = 12f
                    setTextColor(host.getColor(R.color.inkSoft))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = host.dp(2) })
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // 热帖区（三档 chips 本地切换）
        body.addView(sectionTitle(host.getString(R.string.forum_hot_today)))
        val chipRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(host.dp(16), host.dp(4), host.dp(16), host.dp(2))
        }
        val labels = mapOf(
            "today" to host.getString(R.string.forum_hot_today),
            "week" to host.getString(R.string.forum_hot_week),
            "month" to host.getString(R.string.forum_hot_month),
        )
        hotChips = labels.mapValues { (key, label) ->
            val tv = TextView(host).apply {
                text = label
                textSize = 12f
                setPadding(host.dp(11), host.dp(4), host.dp(11), host.dp(4))
                isClickable = true
                setOnClickListener {
                    hotRange = key
                    renderHotChips()
                    renderHotList()
                }
            }
            chipRow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = host.dp(8) })
            tv
        }
        body.addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hotList = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        body.addView(hotList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        renderHotChips()
        renderHotList()

        // 最新回复区
        body.addView(sectionTitle(host.getString(R.string.forum_latest_replies)))
        latest.forEach { r ->
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(8))
                isClickable = true
                isFocusable = true
                background = host.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                setOnClickListener { if (r.postNo > 0) host.openNativeOrWeb("/post/${r.postNo}") }
            }
            row.addView(TextView(host).apply {
                text = "「${r.content}」— ${r.authorName}"
                textSize = 13f
                setTextColor(host.getColor(R.color.ink))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(host).apply {
                text = buildString {
                    if (r.boardName.isNotEmpty()) append("${r.boardName} · ")
                    append(r.postTitle)
                    append(" · ").append(relativeTime(r.createdAt))
                }
                textSize = 11f
                setTextColor(host.getColor(R.color.inkSoft))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = host.dp(2) })
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // 精华聚合入口（恒 WebView）
        body.addView(TextView(host).apply {
            text = host.getString(R.string.forum_essence_link)
            textSize = 13f
            setTextColor(host.getColor(R.color.brand))
            setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(4))
            isClickable = true
            setOnClickListener { host.openInWebView("/forum/essence") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun renderHotChips() {
        hotChips.forEach { (key, tv) ->
            val on = key == hotRange
            tv.setTextColor(host.getColor(if (on) R.color.paper else R.color.ink))
            tv.background = host.getDrawable(if (on) R.drawable.btn_primary else R.drawable.btn_outline)
        }
    }

    private fun renderHotList() {
        hotList.removeAllViews()
        val list = hot[hotRange] ?: emptyList()
        list.forEachIndexed { i, p ->
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(8))
                isClickable = true
                isFocusable = true
                background = host.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                setOnClickListener { if (p.no > 0) host.openNativeOrWeb("/post/${p.no}") }
            }
            row.addView(TextView(host).apply {
                text = "${i + 1}"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.getColor(if (i < 3) R.color.brand else R.color.inkSoft))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = host.dp(9) })
            val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(TextView(host).apply {
                text = p.title
                textSize = 14f
                setTextColor(host.getColor(R.color.ink))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(TextView(host).apply {
                text = "${p.boardName} · 💬${p.replyCount} · 👍${p.likeCount}"
                textSize = 11f
                setTextColor(host.getColor(R.color.inkSoft))
                maxLines = 1
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = host.dp(2) })
            hotList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
