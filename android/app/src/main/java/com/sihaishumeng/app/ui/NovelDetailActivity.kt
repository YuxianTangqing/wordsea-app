package com.sihaishumeng.app.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.core.api.PagedLoader
import com.sihaishumeng.app.core.session.SessionStore
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.TitleBar
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.displayName
import com.sihaishumeng.app.ui.common.relativeTime
import com.sihaishumeng.app.ui.common.toast
import com.sihaishumeng.app.ui.novel.ChapterRow
import com.sihaishumeng.app.ui.novel.ReviewRow
import com.sihaishumeng.app.ui.novel.formatWan
import org.json.JSONObject

/**
 * 批④ 书籍详情（用户作品/公版两态共用；Intent 传 identifier+kind——数字链域）。
 * 单 RecyclerView 异构：头卡（封面/题名/作者/统计/操作行）→ 简介 → 章列表（卷分组）
 * → 书评（PagedLoader 尾加载）。写操作：加书架/点赞/催更/评分（POST 现成）；
 * 写书评/作者页/阅读=WebView（阅读器恒 WebView 铁律）。
 */
@SuppressLint("SetTextI18n")
class NovelDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IDENTIFIER = "identifier"
        const val EXTRA_KIND = "kind" // novels|classics（数字链域）
    }

    private data class Header(
        val id: String,
        val title: String,
        val authorName: String,
        val authorUid: Int?,
        val coverUrl: String?,
        val description: String?,
        val status: String?,
        val wordCount: Long,
        val chapterCount: Int,
        val viewCount: Long,
        val likeCount: Int,
        val likedByMe: Boolean,
        val href: String,
    )

    private var header: Header? = null
    private var chapters: List<ChapterRow> = emptyList()
    private var bookmarked = false
    private var progressChapter = 0
    private val reviewLoader = PagedLoader<ReviewRow>(20) { page, done ->
        val h = header ?: return@PagedLoader done(null, null, true)
        val id = h.id
        Api.get("/api/v1/novels/$id/reviews", mapOf("page" to page.toString(), "pageSize" to "20")) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val arr = r.data?.optJSONArray("list")
                    val rows = mutableListOf<ReviewRow>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val u = o.optJSONObject("user")
                        rows.add(
                            ReviewRow(
                                id = o.optString("id"),
                                userName = displayName(u?.optString("nickname"), u?.optString("username")),
                                content = o.optString("content"),
                                likeCount = o.optInt("likeCount", 0),
                                replyCount = o.optInt("replyCount", 0),
                                createdAt = o.optString("createdAt").ifEmpty { null },
                            )
                        )
                    }
                    done(rows, r.data?.optInt("total", -1), false)
                }
                is ApiResult.Err -> { toast(r.message); done(null, null, true) }
                ApiResult.Net -> { toast(getString(R.string.net_error)); done(null, null, true) }
            }
        }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var states: StateViews
    private lateinit var adapter: DetailAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identifier = intent.getStringExtra(EXTRA_IDENTIFIER) ?: run { finish(); return }
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "novels"
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(getColor(R.color.paper)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(TitleBar(this).apply { title(getString(R.string.loading_text)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@NovelDetailActivity) }
        column.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        states = StateViews(this, root)
        setContentView(root)
        applyInsets(column)
        adapter = DetailAdapter()
        recycler.adapter = adapter
        reviewLoader.bind { adapter.notifyDataSetChanged() }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                reviewLoader.maybeLoadMore(lm.findLastVisibleItemPosition())
            }
        })
        load(identifier, kind)
    }

    private fun applyInsets(column: LinearLayout) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val cb = androidx.core.view.OnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(column, cb)
    }

    private fun load(identifier: String, kind: String) {
        states.loading()
        Api.get("/api/v1/novels/$identifier", mapOf("kind" to kind)) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val o = r.data
                    if (o == null) {
                        states.error(getString(R.string.empty_retry)) { load(identifier, kind) }
                    } else {
                    val author = o.optJSONObject("author")
                    val isPub = o.optBoolean("isPublicDomain", false)
                    val href = if (isPub) "/classics/${if (o.isNull("pdNo")) o.optString("id") else o.optInt("pdNo")}"
                    else "/novels/${if (o.isNull("workNo")) o.optString("slug") else o.optInt("workNo")}"
                    header = Header(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        authorName = author?.optString("penName")?.ifEmpty { null }
                            ?: displayName(author?.optString("nickname"), author?.optString("username")),
                        authorUid = author?.let { if (it.isNull("uid")) null else it.optInt("uid") },
                        coverUrl = o.optString("coverUrl").ifEmpty { null },
                        description = o.optString("description").ifEmpty { null },
                        status = o.optString("status").ifEmpty { null },
                        wordCount = o.optLong("wordCount", 0),
                        chapterCount = o.optInt("chapterCount", 0),
                        viewCount = o.optLong("viewCount", 0),
                        likeCount = o.optInt("likeCount", 0),
                        likedByMe = o.optBoolean("likedByMe", false),
                        href = href,
                    )
                    loadChapters()
                    }
                }
                is ApiResult.Err -> states.error(r.message) { load(identifier, kind) }
                ApiResult.Net -> states.error(getString(R.string.net_error)) { load(identifier, kind) }
            }
        }
    }

    private fun loadChapters() {
        val h = header ?: return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "novels"
        Api.get("/api/v1/novels/${h.id}/chapters", mapOf("kind" to kind)) { r ->
            if (r is ApiResult.Ok) {
                // chapters 端点返回 {list}（e2e 实测形态）
                val array = r.data?.optJSONArray("list")
                val rows = mutableListOf<ChapterRow>()
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val o = array.getJSONObject(i)
                        val vol = o.optJSONObject("volume")?.optString("name")?.ifEmpty { null }
                        rows.add(
                            ChapterRow(
                                orderIndex = o.optInt("orderIndex", i + 1),
                                title = o.optString("title"),
                                wordCount = o.optInt("wordCount", 0),
                                volumeName = vol,
                            )
                        )
                    }
                }
                chapters = rows
                states.clear()
                adapter.notifyDataSetChanged()
                reviewLoader.refresh()
                loadBookmarkState()
                loadProgress()
            } else {
                // 章列表失败不拦头卡展示（书评照常）
                states.clear()
                adapter.notifyDataSetChanged()
                reviewLoader.refresh()
            }
        }
    }

    private fun loadBookmarkState() {
        val h = header ?: return
        Api.get("/api/v1/bookmarks") { r ->
            val arr = (r as? ApiResult.Ok)?.data?.optJSONArray("list")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("targetType") == "NOVEL" && o.optString("targetId") == h.id) {
                        bookmarked = true
                        adapter.notifyDataSetChanged()
                        break
                    }
                }
            }
        }
    }

    private fun loadProgress() {
        val h = header ?: return
        Api.get("/api/v1/me/progress", mapOf("novelId" to h.id)) { r ->
            val p = (r as? ApiResult.Ok)?.data?.optJSONObject("progress")
            if (p != null) {
                progressChapter = p.optInt("chapterOrderIndex", 0)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun requireLogin(): Boolean {
        if (SessionStore.peek() != null) return false
        toast(getString(R.string.detail_login_first))
        startActivity(android.content.Intent(this, LoginActivity::class.java))
        return true
    }

    private fun toggleBookmark() {
        val h = header ?: return
        if (requireLogin()) return
        Api.post("/api/v1/bookmarks", JSONObject().put("targetType", "NOVEL").put("targetId", h.id)) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val now = r.data?.optBoolean("bookmarked", !bookmarked) ?: !bookmarked
                    bookmarked = now
                    toast(getString(if (now) R.string.shelf_added else R.string.shelf_removed))
                    adapter.notifyDataSetChanged()
                }
                is ApiResult.Err -> toast(r.message)
                ApiResult.Net -> toast(getString(R.string.net_error))
            }
        }
    }

    private fun toggleLike() {
        val h = header ?: return
        if (requireLogin()) return
        Api.post("/api/v1/likes", JSONObject().put("targetType", "NOVEL").put("targetId", h.id)) { r ->
            if (r is ApiResult.Ok) {
                header = h.copy(likedByMe = !h.likedByMe, likeCount = h.likeCount + if (h.likedByMe) -1 else 1)
                adapter.notifyDataSetChanged()
            } else if (r is ApiResult.Err) toast(r.message)
        }
    }

    private fun urge() {
        val h = header ?: return
        if (requireLogin()) return
        Api.post("/api/v1/novels/${h.id}/urge", JSONObject()) { r ->
            when (r) {
                is ApiResult.Ok -> toast(getString(R.string.detail_urge_ok))
                is ApiResult.Err -> toast(r.message)
                ApiResult.Net -> toast(getString(R.string.net_error))
            }
        }
    }

    private fun rate() {
        val h = header ?: return
        if (requireLogin()) return
        val bar = RatingBar(this).apply {
            numStars = 5; stepSize = 1f; rating = 4f
        }
        AlertDialog.Builder(this)
            .setTitle("评分")
            .setView(bar)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Api.post("/api/v1/ratings", JSONObject().put("novelId", h.id).put("rating", bar.rating.toInt())) { r ->
                    if (r is ApiResult.Ok) toast("评分成功") else if (r is ApiResult.Err) toast(r.message)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openChapter(order: Int) {
        val h = header ?: return
        MainActivity.openInWebView(this, "${h.href}/chapter/$order")
    }

    // ── 异构 Adapter：头卡+简介+章列表（含卷头）+书评标题+书评行+尾注 ──

    private inner class DetailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val tHeader = 1; private val tIntro = 2; private val tVol = 3
        private val tChapter = 4; private val tReviewTitle = 5; private val tReview = 6; private val tFoot = 7

        /** 头卡+简介+章列表+书评标题（书评标题=本段最后一行） */
        private val fixedCount: Int get() = 2 + chapters.size + 1

        override fun getItemViewType(position: Int): Int = when {
            position == 0 -> tHeader
            position == 1 -> tIntro
            position == fixedCount - 1 -> tReviewTitle
            position < fixedCount -> if (chapters[position - 2].volumeName != null) tVol else tChapter
            position < itemCount - (if (reviewLoader.hasData) 1 else 0) -> tReview
            else -> tFoot
        }

        override fun getItemCount(): Int = fixedCount + reviewLoader.size + if (reviewLoader.hasData) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            tHeader -> object : RecyclerView.ViewHolder(buildHeaderView()) {}
            tIntro, tVol, tReviewTitle, tFoot -> object : RecyclerView.ViewHolder(TextView(this@NovelDetailActivity)) {}
            tChapter -> {
                val row = LinearLayout(this@NovelDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    isClickable = true
                    background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                }
                object : RecyclerView.ViewHolder(row) {}
            }
            else -> {
                val col = LinearLayout(this@NovelDetailActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(9), dp(16), dp(9)) }
                object : RecyclerView.ViewHolder(col) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = header ?: return
            when (getItemViewType(position)) {
                tHeader -> bindHeader(holder, h)
                tIntro -> (holder.itemView as TextView).apply {
                    text = h.description ?: "暂无简介"; textSize = 13f
                    setTextColor(getColor(R.color.ink))
                    setPadding(dp(16), dp(10), dp(16), dp(4))
                }
                tVol -> (holder.itemView as TextView).apply {
                    text = "卷 · ${chapters[position - 2].volumeName}"
                    textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.brand))
                    setPadding(dp(16), dp(10), dp(16), dp(2))
                }
                tChapter -> {
                    val row = chapters[position - 2]
                    val box = holder.itemView as LinearLayout
                    if (box.childCount == 0) {
                        box.addView(TextView(this@NovelDetailActivity).apply {
                            textSize = 14f; setTextColor(getColor(R.color.ink))
                            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        box.addView(TextView(this@NovelDetailActivity).apply {
                            textSize = 11f; setTextColor(getColor(R.color.inkSoft))
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
                    }
                    (box.getChildAt(0) as TextView).text = "${row.orderIndex}. ${row.title}"
                    (box.getChildAt(1) as TextView).text = if (row.wordCount > 0) "${row.wordCount} 字" else ""
                    box.setOnClickListener { openChapter(row.orderIndex) }
                }
                tReviewTitle -> (holder.itemView as TextView).apply {
                    text = getString(R.string.detail_reviews)
                    textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.brand))
                    setPadding(dp(16), dp(16), dp(16), dp(4))
                }
                tReview -> {
                    val item = reviewLoader.get(position - fixedCount)
                    val col = holder.itemView as LinearLayout
                    if (col.childCount == 0) {
                        col.addView(TextView(this@NovelDetailActivity).apply {
                            textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                            setTextColor(getColor(R.color.brand))
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        col.addView(TextView(this@NovelDetailActivity).apply {
                            textSize = 13f; setTextColor(getColor(R.color.ink))
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                        col.addView(TextView(this@NovelDetailActivity).apply {
                            textSize = 11f; setTextColor(getColor(R.color.inkSoft))
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                    }
                    (col.getChildAt(0) as TextView).text = item.userName
                    (col.getChildAt(1) as TextView).text = item.content
                    (col.getChildAt(2) as TextView).text = "👍 ${item.likeCount} · 💬 ${item.replyCount} · ${relativeTime(item.createdAt)}"
                }
                tFoot -> (holder.itemView as TextView).apply {
                    text = when {
                        reviewLoader.moreError -> getString(R.string.paged_retry)
                        reviewLoader.endReached -> getString(R.string.paged_end)
                        else -> ""
                    }
                    textSize = 12f; setTextColor(getColor(R.color.inkSoft))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(16))
                    if (reviewLoader.moreError) setOnClickListener { reviewLoader.retryMore() }
                }
            }
        }
    }

    private fun bindHeader(holder: RecyclerView.ViewHolder, h: Header) {
        val v = holder.itemView
        v.findTag<TextView>("title")?.text = h.title
        v.findTag<TextView>("author")?.let {
            it.text = "${h.authorName} 著"
            it.setOnClickListener { h.authorUid?.let { uid -> MainActivity.openInWebView(this, "/users/$uid") } }
        }
        v.findTag<TextView>("meta")?.text = buildString {
            append(getString(R.string.detail_words_fmt, formatWan(h.wordCount)))
            append(" · ").append(getString(R.string.detail_views_fmt, formatWan(h.viewCount)))
            if (h.chapterCount > 0) append(" · ").append(getString(R.string.detail_chapter_fmt, h.chapterCount))
            append(" · ").append(
                getString(
                    if (h.status == "FINISHED") R.string.detail_finished else R.string.detail_serializing,
                )
            )
        }
        v.findTag<ImageView>("cover")?.load(h.coverUrl) {
            placeholder(R.drawable.bg_cover); error(R.drawable.bg_cover); crossfade(true)
        }
        v.findTag<TextView>("btnShelf")?.let { btn ->
            btn.text = getString(if (bookmarked) R.string.detail_in_shelf else R.string.detail_add_shelf)
            btn.setOnClickListener { toggleBookmark() }
        }
        v.findTag<TextView>("btnLike")?.let { btn ->
            btn.text = "👍 ${h.likeCount}" + if (h.likedByMe) " ✓" else ""
            btn.setOnClickListener { toggleLike() }
        }
        v.findTag<TextView>("btnUrge")?.let { btn ->
            btn.visibility = if (h.status == "SERIALIZING") View.VISIBLE else View.GONE
            btn.setOnClickListener { urge() }
        }
        v.findTag<TextView>("btnRate")?.setOnClickListener { rate() }
        v.findTag<TextView>("btnRead")?.let { btn ->
            btn.text = getString(if (progressChapter > 0) R.string.detail_continue_reading else R.string.detail_start_reading)
            btn.setOnClickListener { openChapter(if (progressChapter > 0) progressChapter else 1) }
        }
        v.findTag<TextView>("btnReview")?.setOnClickListener {
            MainActivity.openInWebView(this, "${h.href}#reviews")
        }
    }

    private fun <T : View> View.findTag(key: String): T? {
        val v = findViewWithTag<View>(key)
        @Suppress("UNCHECKED_CAST")
        return v as? T
    }

    private fun buildHeaderView(): View {
        fun tag(v: View, key: String): View {
            v.tag = key
            return v
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 封面行
        val coverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        val cover = ImageView(this).apply { setBackgroundResource(R.drawable.bg_cover) }
        coverRow.addView(cover, LinearLayout.LayoutParams(dp(96), dp(128)).apply { marginEnd = dp(13) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        coverRow.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val title = tag(TextView(this).apply {
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.ink))
        }, "title")
        col.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val author = tag(TextView(this).apply {
            textSize = 13f; setTextColor(getColor(R.color.brand))
        }, "author")
        col.addView(author, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        val meta = tag(TextView(this).apply {
            textSize = 12f; setTextColor(getColor(R.color.inkSoft))
        }, "meta")
        col.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        box.addView(coverRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 操作行一（阅读主钮）
        val read = tag(TextView(this).apply {
            textSize = 15f; setTextColor(getColor(R.color.paper)); gravity = Gravity.CENTER
            background = getDrawable(R.drawable.btn_primary)
            setPadding(0, dp(11), 0, dp(11))
        }, "btnRead")
        box.addView(read, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(12), dp(16), 0)
        })
        // 操作行二（加书架/点赞/催更/评分）
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(9), dp(16), 0)
        }
        fun actionBtn(label: String, key: String): TextView {
            val tv = TextView(this@NovelDetailActivity).apply {
                text = label; textSize = 12f
                setTextColor(getColor(R.color.brand)); gravity = Gravity.CENTER
                background = getDrawable(R.drawable.btn_outline)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            actions.addView(tag(tv, key), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(7) })
            return tv
        }
        actionBtn(getString(R.string.detail_add_shelf), "btnShelf")
        actionBtn("👍", "btnLike")
        actionBtn(getString(R.string.detail_urge), "btnUrge")
        actionBtn("评分", "btnRate")
        box.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 目录标题 + 写书评入口
        val dirRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(2))
        }
        dirRow.addView(TextView(this).apply {
            text = getString(R.string.detail_chapters)
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.brand))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val writeReview = tag(TextView(this).apply {
            text = getString(R.string.detail_review_write)
            textSize = 11f; setTextColor(getColor(R.color.inkSoft))
        }, "btnReview")
        dirRow.addView(writeReview)
        box.addView(dirRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }
}
