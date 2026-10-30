package com.sihaishumeng.app.ui.tab

import android.annotation.SuppressLint
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.core.api.PagedLoader
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.toast
import com.sihaishumeng.app.ui.novel.NovelCard
import com.sihaishumeng.app.ui.novel.NovelCardAdapter
import com.sihaishumeng.app.ui.novel.parseNovelCard
import org.json.JSONObject

/**
 * 批④ 书库 tab（公开）：三段（小说/文章/公版）+ 搜索框 + 排序 chips + 2 列网格
 * 尾加载（pageSize 48 拉满省请求）。切段/换序/搜索=整换 loader+adapter（状态各自
 * 独立）。文章段卡片点开走 WebView（M260 来路闸靠 WebView 语境天然过）。
 * 标签筛选本批不带（listTopTags 无 JSON 口，搜索 q 覆盖主场景——缺口记档）。
 */
@SuppressLint("SetTextI18n")
class LibraryScreen(host: MainActivity) : TabScreen(host) {

    private enum class Segment(val key: String) {
        NOVELS("novels"), ARTICLES("articles"), CLASSICS("classics")
    }

    private var segment = Segment.NOVELS
    private var sort = "updated"
    private var query = ""

    private lateinit var recycler: RecyclerView
    private lateinit var states: StateViews
    private lateinit var sortRow: LinearLayout
    private lateinit var sortChips: List<TextView>
    private var segmentChips: List<TextView> = emptyList()

    private val sortOptions: List<Pair<String, Int>>
        get() = when (segment) {
            Segment.NOVELS -> listOf(
                "updated" to R.string.library_sort_updated,
                "latest" to R.string.library_sort_latest,
                "views" to R.string.library_sort_views,
                "bookmarks" to R.string.library_sort_bookmarks,
            )
            Segment.CLASSICS -> listOf(
                "catalog" to R.string.library_sort_catalog,
                "views" to R.string.library_sort_views,
                "latest" to R.string.library_sort_latest,
            )
            Segment.ARTICLES -> listOf(
                "latest" to R.string.library_sort_latest,
                "views" to R.string.library_sort_views,
            )
        }

    override fun build(): View {
        val root = android.widget.FrameLayout(host).apply { setBackgroundColor(host.getColor(R.color.paper)) }
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 段切换 chips
        val segRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(host.dp(16), host.dp(10), host.dp(16), 0)
        }
        val segs = listOf(
            R.string.library_segment_novels to Segment.NOVELS,
            R.string.library_segment_articles to Segment.ARTICLES,
            R.string.library_segment_classics to Segment.CLASSICS,
        )
        segmentChips = segs.map { (label, seg) ->
            chip(host.getString(label), segRow) { switchSegment(seg) }
        }
        segRow.orientation = LinearLayout.HORIZONTAL
        column.addView(segRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 搜索框（触发本段 q 过滤）
        val search = EditText(host).apply {
            hint = host.getString(R.string.library_search_hint)
            textSize = 13f
            setTextColor(host.getColor(R.color.ink))
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            background = host.getDrawable(R.drawable.btn_outline)
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    query = text.toString().trim()
                    applyLoader()
                    true
                } else false
            }
        }
        column.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(host.dp(16), host.dp(8), host.dp(16), 0)
        })

        // 排序 chips 行（随段重建）
        sortRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(4))
        }
        column.addView(sortRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 列表
        val swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(host).apply {
            setColorSchemeResources(R.color.brand)
            setProgressBackgroundColorSchemeResource(R.color.paper)
        }
        recycler = RecyclerView(host).apply {
            layoutManager = GridLayoutManager(host, 2)
            clipToPadding = false
            setPadding(0, host.dp(4), 0, host.dp(16))
        }
        swipe.addView(recycler, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        states = StateViews(host, root)
        applyLoader()
        return root
    }

    override fun onShow() {
        // 公开内容常驻缓存；不自动重拉（下拉刷新/搜索/切段才动）
    }

    private fun chip(label: String, parent: LinearLayout, onClick: () -> Unit): TextView {
        val tv = TextView(host).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(host.dp(13), host.dp(5), host.dp(13), host.dp(5))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        parent.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = host.dp(8) })
        return tv
    }

    private fun switchSegment(seg: Segment) {
        if (segment == seg) return
        segment = seg
        sort = sortOptions.first().first
        renderChips()
        rebuildSortRow()
        applyLoader()
    }

    private fun renderChips() {
        segmentChips.forEachIndexed { i, tv ->
            val on = segLabelFor(i) == segment
            tv.setTextColor(host.getColor(if (on) R.color.paper else R.color.ink))
            tv.background = host.getDrawable(if (on) R.drawable.btn_primary else R.drawable.btn_outline)
        }
    }

    private fun segLabelFor(i: Int): Segment = when (i) {
        0 -> Segment.NOVELS; 1 -> Segment.ARTICLES; else -> Segment.CLASSICS
    }

    private fun rebuildSortRow() {
        sortRow.removeAllViews()
        sortChips = sortOptions.map { (key, label) ->
            chip(host.getString(label), sortRow) {
                if (sort != key) {
                    sort = key
                    renderSortChips()
                    applyLoader()
                }
            }
        }
        renderSortChips()
    }

    private fun renderSortChips() {
        sortChips.forEachIndexed { i, tv ->
            val on = sortOptions[i].first == sort
            tv.setTextColor(host.getColor(if (on) R.color.brand else R.color.inkSoft))
            tv.background = if (on) host.getDrawable(R.drawable.badge_tag) else null
        }
    }

    private fun applyLoader() {
        renderChips()
        if (sortChips.isEmpty() || sortRow.childCount != sortOptions.size) rebuildSortRow()
        val path = when (segment) {
            Segment.NOVELS -> "/api/v1/novels"
            Segment.ARTICLES -> "/api/v1/articles"
            Segment.CLASSICS -> "/api/v1/novels"
        }
        val loader = PagedLoader<NovelCard>(48) { page, done ->
            val params = LinkedHashMap<String, String?>()
            params["page"] = page.toString()
            params["pageSize"] = "48"
            params["sort"] = sort
            if (query.isNotEmpty()) params["q"] = query
            if (segment == Segment.CLASSICS) params["publicDomain"] = "only"
            Api.get(path, params) { r ->
                when (r) {
                    is ApiResult.Ok -> {
                        val list = r.data?.optJSONArray("list")
                        val cards = mutableListOf<NovelCard>()
                        if (list != null) {
                            for (i in 0 until list.length()) cards.add(mapRow(list.getJSONObject(i)))
                        }
                        done(cards, r.data?.optInt("total", -1), false)
                    }
                    is ApiResult.Err -> {
                        host.toast(r.message)
                        done(null, null, true)
                    }
                    ApiResult.Net -> done(null, null, true)
                }
            }
        }
        val adapter = NovelCardAdapter(loader) { card -> openCard(card) }
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                loader.maybeLoadMore(lm.findLastVisibleItemPosition())
            }
        })
        loader.bind {
            adapter.notifyDataSetChanged()
            if (loader.firstLoading) states.loading()
            else if (loader.firstError) states.error(host.getString(R.string.net_error)) { loader.refresh() }
            else if (loader.hasData && loader.items.isEmpty()) states.empty(host.getString(R.string.library_empty))
            else states.clear()
        }
        loader.refresh()
    }

    /** articles 行 → NovelCard 形态映射（href 走文章页 WebView） */
    private fun mapRow(o: JSONObject): NovelCard {
        return if (segment != Segment.ARTICLES) {
            parseNovelCard(o)
        } else {
            val author = o.optJSONObject("author")
            val no = if (o.isNull("no")) null else o.optInt("no")
            NovelCard(
                id = o.optString("id"),
                title = o.optString("title"),
                authorName = com.sihaishumeng.app.ui.common.displayName(author?.optString("nickname"), author?.optString("username")),
                authorUid = author?.let { if (it.isNull("uid")) null else it.optInt("uid") },
                coverUrl = o.optString("coverUrl").ifEmpty { null },
                description = o.optString("excerpt").ifEmpty { null },
                status = null,
                wordCount = o.optLong("wordCount", 0),
                chapterCount = 0,
                viewCount = o.optLong("viewCount", 0),
                isPublicDomain = false,
                workNo = null,
                pdNo = null,
                slug = null,
                sourceAuthor = null,
                hrefOverride = no?.let { "/articles/$it" },
            )
        }
    }

    private fun openCard(card: NovelCard) {
        if (segment == Segment.ARTICLES) {
            host.openInWebView(card.href)
        } else {
            host.openNativeOrWeb(card.href)
        }
    }
}
