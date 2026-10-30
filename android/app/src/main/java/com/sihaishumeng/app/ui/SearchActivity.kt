package com.sihaishumeng.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.core.api.PagedLoader
import com.sihaishumeng.app.ui.common.BasePageActivity
import com.sihaishumeng.app.ui.common.NotificationAdapter
import com.sihaishumeng.app.ui.common.PagedAdapter
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.UserRowAdapter
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.parseUserRow
import com.sihaishumeng.app.ui.common.toast
import com.sihaishumeng.app.ui.forum.PostRow
import com.sihaishumeng.app.ui.forum.PostRowAdapter
import com.sihaishumeng.app.ui.forum.parsePostRow
import com.sihaishumeng.app.ui.novel.NovelCard
import com.sihaishumeng.app.ui.novel.NovelCardAdapter
import com.sihaishumeng.app.ui.novel.parseNovelCard
import org.json.JSONObject

/** 空数组哨兵（data.list 缺失时按空集处理） */
private val EMPTY_ARR = org.json.JSONArray()

/**
 * 批④ 搜索页：四子 tab（小说/文章/帖子/用户）独立尾加载保态；novels 段
 * publicDomain=include（含公版书）与网页搜索页同口径；q 从路由表透传可预填。
 */
@SuppressLint("SetTextI18n")
class SearchActivity : BasePageActivity() {

    companion object {
        const val EXTRA_QUERY = "query"
    }

    private enum class Tab(val labelRes: Int) {
        NOVELS(R.string.search_tab_novels), ARTICLES(R.string.search_tab_articles),
        POSTS(R.string.search_tab_posts), USERS(R.string.search_tab_users),
    }

    private var tab = Tab.NOVELS
    private var q = ""

    private val loaders = mutableMapOf<Tab, PagedLoader<out Any>>()
    private val adapters = mutableMapOf<Tab, PagedAdapter<out Any, *>>()
    private lateinit var recycler: RecyclerView
    private lateinit var states: StateViews
    private lateinit var input: EditText
    private lateinit var chips: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPage(getString(R.string.search_title))
        q = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val c = this
        val searchRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        input = EditText(c).apply {
            hint = getString(R.string.search_hint)
            textSize = 13f
            setSingleLine()
            setText(q)
            setSelection(q.length)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            background = getDrawable(R.drawable.btn_outline)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
            }
        }
        searchRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val go = TextView(c).apply {
            text = "搜索"; textSize = 13f
            setTextColor(getColor(R.color.paper))
            background = getDrawable(R.drawable.btn_primary)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            isClickable = true; setOnClickListener { doSearch() }
        }
        searchRow.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        val chipRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(4)) }
        chips = Tab.values().map { t ->
            TextView(c).apply {
                text = getString(t.labelRes); textSize = 13f
                setPadding(dp(12), dp(5), dp(12), dp(5))
                isClickable = true
                setOnClickListener { switchTab(t) }
            }.also { chipRow.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }) }
        }
        recycler = RecyclerView(c)
        states = StateViews(c, pageRoot)
        body().addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body().addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body().addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 单一滚动监听分派到当前 tab 的 loader（buildTab 反复调不叠监听）
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = when (val lm = rv.layoutManager) {
                    is GridLayoutManager -> lm.findLastVisibleItemPosition()
                    is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    else -> return
                }
                @Suppress("UNCHECKED_CAST")
                (loaders[tab] as? PagedLoader<Any>)?.maybeLoadMore(last)
            }
        })
        renderChips()
        if (q.isEmpty()) states.empty(getString(R.string.search_empty_q)) else buildTab()
    }

    private fun doSearch() {
        q = input.text.toString().trim()
        if (q.isEmpty()) { toast(getString(R.string.search_empty_q)); return }
        loaders.clear()
        buildTab()
    }

    private fun switchTab(t: Tab) {
        tab = t
        renderChips()
        buildTab()
    }

    private fun renderChips() {
        chips.forEachIndexed { i, tv ->
            val on = Tab.values()[i] == tab
            tv.setTextColor(getColor(if (on) R.color.paper else R.color.ink))
            tv.background = getDrawable(if (on) R.drawable.btn_primary else R.drawable.btn_outline)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildTab() {
        if (q.isEmpty()) { states.empty(getString(R.string.search_empty_q)); return }
        states.clear()
        if (loaders[tab] == null) {
            when (tab) {
                Tab.NOVELS -> {
                    val loader = PagedLoader<NovelCard>(20) { p, done -> fetch("novels", p, done) }
                    val adapter = NovelCardAdapter(loader) { MainActivity.openNativeOrWeb(this, it.href) }
                    loaders[tab] = loader; adapters[tab] = adapter
                }
                Tab.ARTICLES -> {
                    val loader = PagedLoader<NovelCard>(20) { p, done -> fetch("articles", p, done) }
                    val adapter = NovelCardAdapter(loader) { MainActivity.openInWebView(this, it.href) }
                    loaders[tab] = loader; adapters[tab] = adapter
                }
                Tab.POSTS -> {
                    val loader = PagedLoader<PostRow>(20) { p, done -> fetch("posts", p, done) }
                    val adapter = PostRowAdapter(loader) { MainActivity.openNativeOrWeb(this, it.href) }
                    loaders[tab] = loader; adapters[tab] = adapter
                }
                Tab.USERS -> {
                    val loader = PagedLoader<com.sihaishumeng.app.ui.common.UserRow>(20) { p, done -> fetchUsers(p, done) }
                    val adapter = UserRowAdapter(loader) { MainActivity.openInWebView(this, it.href) }
                    loaders[tab] = loader; adapters[tab] = adapter
                }
            }
        }
        when (tab) {
            Tab.NOVELS, Tab.ARTICLES -> recycler.layoutManager = GridLayoutManager(this, 2)
            else -> recycler.layoutManager = LinearLayoutManager(this)
        }
        val adapter = adapters[tab]
        val loader = loaders[tab]
        if (adapter != null && loader != null) {
            recycler.adapter = adapter as RecyclerView.Adapter<*>
            @Suppress("UNCHECKED_CAST")
            (loader as PagedLoader<Any>).bind {
                adapter.notifyDataSetChanged()
                if (loader.firstLoading) states.loading()
                else if (loader.firstError) states.error(getString(R.string.net_error)) { loader.refresh() }
                else if (loader.hasData && loader.items.isEmpty()) states.empty(getString(R.string.search_no_result))
                else states.clear()
            }
            if (!loader.hasData) loader.refresh()
        }
    }

    private fun <T> fetch(kind: String, page: Int, done: (List<T>?, Int?, Boolean) -> Unit) {
        val (path, extra) = when (kind) {
            "novels" -> "/api/v1/novels" to mapOf("publicDomain" to "include", "sort" to "updated")
            "articles" -> "/api/v1/articles" to mapOf("sort" to "latest")
            else -> "/api/v1/forum/posts" to emptyMap()
        }
        Api.get(path, extra + mapOf("q" to q, "page" to page.toString(), "pageSize" to "20")) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val arr = r.data?.optJSONArray("list") ?: EMPTY_ARR
                    val rows = mutableListOf<Any>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        rows.add(if (kind == "posts") parsePostRow(o) else parseNovelCard(o))
                    }
                    @Suppress("UNCHECKED_CAST")
                    done(rows as List<T>, r.data?.optInt("total", -1), false)
                }
                is ApiResult.Err -> { toast(r.message); done(null, null, true) }
                ApiResult.Net -> { toast(getString(R.string.net_error)); done(null, null, true) }
            }
        }
    }

    private fun fetchUsers(page: Int, done: (List<com.sihaishumeng.app.ui.common.UserRow>?, Int?, Boolean) -> Unit) {
        Api.get("/api/v1/users", mapOf("q" to q, "page" to page.toString(), "pageSize" to "20")) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val arr = r.data?.optJSONArray("list") ?: EMPTY_ARR
                    val rows = mutableListOf<com.sihaishumeng.app.ui.common.UserRow>()
                    for (i in 0 until arr.length()) rows.add(parseUserRow(arr.getJSONObject(i)))
                    done(rows, r.data?.optInt("total", -1), false)
                }
                is ApiResult.Err -> { toast(r.message); done(null, null, true) }
                ApiResult.Net -> { toast(getString(R.string.net_error)); done(null, null, true) }
            }
        }
    }

}
