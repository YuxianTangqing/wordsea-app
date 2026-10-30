package com.sihaishumeng.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.core.api.PagedLoader
import com.sihaishumeng.app.ui.common.BasePageActivity
import com.sihaishumeng.app.ui.common.PagedAdapter
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.toast
import com.sihaishumeng.app.ui.forum.PostRow
import com.sihaishumeng.app.ui.forum.PostRowAdapter
import com.sihaishumeng.app.ui.forum.parsePostRow
import org.json.JSONObject

/**
 * 批④ 板块页：置顶条（GET boards/{slug}/pinned，M305 结算在服务端）+ 排序/精华 chips
 * + 帖子尾加载（excludePinned 口径）。发帖跳 WebView（TipTap 纪律）。
 */
@SuppressLint("SetTextI18n")
class ForumBoardActivity : BasePageActivity() {

    companion object {
        const val EXTRA_SLUG = "slug"
        const val EXTRA_NAME = "name"
    }

    private var sort = "reply" // reply=最新回复（缺省）| new | hot
    private var essenceOnly = false
    private var pinned: List<PostRow> = emptyList()

    private val loader = PagedLoader<PostRow>(20) { page, done ->
        val slug = intent.getStringExtra(EXTRA_SLUG) ?: return@PagedLoader done(null, null, true)
        Api.get(
            "/api/v1/forum/boards/$slug/posts",
            mapOf(
                "page" to page.toString(), "pageSize" to "20",
                "sort" to sort, "filter" to if (essenceOnly) "essence" else null,
            ),
        ) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val arr = r.data?.optJSONArray("list")
                    val rows = mutableListOf<PostRow>()
                    if (arr != null) for (i in 0 until arr.length()) rows.add(parsePostRow(arr.getJSONObject(i)))
                    done(rows, r.data?.optInt("total", -1), false)
                }
                is ApiResult.Err -> { toast(r.message); done(null, null, true) }
                ApiResult.Net -> { toast(getString(R.string.net_error)); done(null, null, true) }
            }
        }
    }

    private lateinit var adapter: PostRowAdapter
    private lateinit var states: StateViews
    private lateinit var pinnedBox: LinearLayout
    private lateinit var chips: List<TextView>
    private lateinit var swipe: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPage(intent.getStringExtra(EXTRA_NAME) ?: "板块")
        val c = this
        // 顶行：排序 chips + 精华开关
        val chipRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        fun chip(label: String, onClick: () -> Unit): TextView {
            val tv = TextView(c).apply {
                text = label; textSize = 12f
                setPadding(dp(11), dp(4), dp(11), dp(4))
                isClickable = true; setOnClickListener { onClick() }
            }
            chipRow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) })
            return tv
        }
        val sorts = listOf(
            "reply" to getString(R.string.board_sort_reply),
            "new" to getString(R.string.board_sort_new),
            "hot" to getString(R.string.board_sort_hot),
        )
        chips = sorts.map { (key, label) ->
            chip(label) { if (sort != key) { sort = key; renderChips(sorts); applyLoader() } }
        } + chip(getString(R.string.board_essence_only)) {
            essenceOnly = !essenceOnly; renderChips(sorts); applyLoader()
        }
        renderChips(sorts)
        // 发帖入口（TitleBar 右钮）
        // 置顶条容器
        pinnedBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        // 列表
        swipe = SwipeRefreshLayout(c).apply {
            setColorSchemeResources(R.color.brand)
            setProgressBackgroundColorSchemeResource(R.color.paper)
            setOnRefreshListener { loadPinned(); loader.refresh(); isRefreshing = false }
        }
        val recycler = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c) }
        swipe.addView(recycler, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        adapter = PostRowAdapter(loader) { post -> MainActivity.openNativeOrWeb(this, post.href) }
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                (rv.layoutManager as? LinearLayoutManager)?.let { loader.maybeLoadMore(it.findLastVisibleItemPosition()) }
            }
        })
        body().addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body().addView(pinnedBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body().addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        states = StateViews(c, pageRoot)
        loader.bind {
            adapter.notifyDataSetChanged()
            renderPinned()
            if (loader.firstLoading) states.loading()
            else if (loader.firstError) states.error(getString(R.string.net_error)) { loader.refresh() }
            else if (loader.hasData && loader.items.isEmpty()) states.empty(getString(R.string.board_empty))
            else states.clear()
        }
        loadPinned()
        loader.refresh()
    }

    private fun renderChips(sorts: List<Pair<String, String>>) {
        chips.forEachIndexed { i, tv ->
            val on = if (i < sorts.size) sorts[i].first == sort else essenceOnly
            tv.setTextColor(getColor(if (on) R.color.paper else R.color.ink))
            tv.background = getDrawable(if (on) R.drawable.btn_primary else R.drawable.btn_outline)
        }
    }

    private fun applyLoader() { loader.refresh() }

    private fun loadPinned() {
        val slug = intent.getStringExtra(EXTRA_SLUG) ?: return
        Api.get("/api/v1/forum/boards/$slug/pinned") { r ->
            if (r is ApiResult.Ok) {
                val arr = r.data?.optJSONArray("list")
                pinned = mutableListOf<PostRow>().apply {
                    if (arr != null) for (i in 0 until arr.length()) add(parsePostRow(arr.getJSONObject(i)))
                }
                renderPinned()
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun renderPinned() {
        pinnedBox.removeAllViews()
        pinned.takeIf { it.isNotEmpty() }?.forEach { p ->
            pinnedBox.addView(TextView(this).apply {
                text = "📌 ${p.title}"
                textSize = 13f
                setTextColor(getColor(R.color.brand))
                maxLines = 1
                android.text.TextUtils.TruncateAt.END.let { ellipsize = it }
                setPadding(dp(16), dp(8), dp(16), dp(2))
                isClickable = true
                setOnClickListener { MainActivity.openNativeOrWeb(this@ForumBoardActivity, p.href) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
