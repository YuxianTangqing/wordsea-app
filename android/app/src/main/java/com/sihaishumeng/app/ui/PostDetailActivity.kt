package com.sihaishumeng.app.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.core.api.PagedLoader
import com.sihaishumeng.app.core.session.SessionStore
import com.sihaishumeng.app.ui.common.BasePageActivity
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.toast
import com.sihaishumeng.app.ui.forum.PostRow
import com.sihaishumeng.app.ui.forum.ReplyAdapter
import com.sihaishumeng.app.ui.forum.ReplyRow
import com.sihaishumeng.app.ui.forum.parsePostRow
import com.sihaishumeng.app.ui.forum.parseReplyRow
import org.json.JSONObject

/**
 * 批④ 帖子详情：主楼（纯文本渲染）+ 排序 chips + 回帖尾加载 + 底部简版纯文本回帖框
 * （站主裁定：富文本/编辑跳 WebView，TipTap 纪律）。进入即 POST view 上报。
 */
@SuppressLint("SetTextI18n")
class PostDetailActivity : BasePageActivity() {

    companion object {
        const val EXTRA_ID = "id" // 数字 no 或 cuid
    }

    private var post: PostRow? = null
    private var sort = "hot"
    private var replyTo: ReplyRow? = null

    private val loader = PagedLoader<ReplyRow>(20) { page, done ->
        val id = intent.getStringExtra(EXTRA_ID) ?: return@PagedLoader done(null, null, true)
        Api.get("/api/v1/forum/posts/$id/replies", mapOf("page" to page.toString(), "pageSize" to "20", "sort" to sort)) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val arr = r.data?.optJSONArray("list")
                    val rows = mutableListOf<ReplyRow>()
                    if (arr != null) for (i in 0 until arr.length()) rows.add(parseReplyRow(arr.getJSONObject(i)))
                    done(rows, r.data?.optInt("total", -1), false)
                }
                is ApiResult.Err -> { toast(r.message); done(null, null, true) }
                ApiResult.Net -> { toast(getString(R.string.net_error)); done(null, null, true) }
            }
        }
    }

    private lateinit var adapter: ReplyAdapter
    private lateinit var mainBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var replyToBar: TextView
    private lateinit var sortChips: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPage("帖子")
        val c = this
        mainBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        replyToBar = TextView(c).apply {
            visibility = View.GONE
            textSize = 12f
            setTextColor(getColor(R.color.brand))
            setPadding(dp(16), dp(6), dp(16), 0)
            setOnClickListener { replyTo = null; visibility = View.GONE }
        }
        val recycler = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c) }
        adapter = ReplyAdapter(loader, onAvatarClick = {}, onLikeClick = { like(it) }, onReplyClick = { pickReplyTo(it) })
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                (rv.layoutManager as? LinearLayoutManager)?.let { loader.maybeLoadMore(it.findLastVisibleItemPosition()) }
            }
        })
        // 底部回帖条
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = getDrawable(R.drawable.bg_banner)
        }
        input = EditText(c).apply {
            hint = getString(R.string.post_reply_hint)
            textSize = 13f
            setSingleLine()
            background = getDrawable(R.drawable.btn_outline)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = TextView(c).apply {
            text = getString(R.string.post_reply_send)
            textSize = 13f
            setTextColor(getColor(R.color.paper))
            background = getDrawable(R.drawable.btn_primary)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener { sendReply() }
        }
        bar.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })

        body().addView(mainBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body().addView(replyToBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body().addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body().addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        loader.bind { adapter.notifyDataSetChanged() }
        load()
    }

    private fun load() {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        Api.get("/api/v1/forum/posts/$id") { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val o = r.data
                    if (o != null) {
                        post = parsePostRow(o, full = true)
                        renderMain()
                        loader.refresh()
                        // 浏览上报（fire-and-forget）
                        Api.post("/api/v1/forum/posts/$id/view", JSONObject()) { _ -> }
                    }
                }
                is ApiResult.Err -> toast(r.message)
                ApiResult.Net -> toast(getString(R.string.net_error))
            }
        }
    }

    private fun renderMain() {
        val p = post ?: return
        mainBox.removeAllViews()
        fun tv(size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
            textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        mainBox.addView(tv(17f, getColor(R.color.ink), bold = true).apply {
            text = p.title
            setPadding(dp(16), dp(10), dp(16), 0)
        }, lp())
        mainBox.addView(tv(12f, getColor(R.color.inkSoft)).apply {
            text = "${p.boardName} · ${p.authorName} · 👀${p.viewCount} · 💬${p.replyCount}"
            setPadding(dp(16), dp(5), dp(16), 0)
        }, lp())
        mainBox.addView(tv(14f, getColor(R.color.ink)).apply {
            text = p.content ?: ""
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(10), dp(16), 0)
        }, lp())
        // 点赞行 + 排序 chips
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(2)) }
        val like = tv(13f, getColor(if (p.likedByMe) R.color.brand else R.color.inkSoft)).apply {
            text = "👍 ${p.likeCount}" + if (p.likedByMe) " ✓" else ""
            background = getDrawable(R.drawable.badge_tag)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            setOnClickListener { like(p) }
        }
        row.addView(like, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun sortChip(label: String, key: String) {
            row.addView(TextView(this).apply {
                text = label; textSize = 12f
                setTextColor(getColor(if (sort == key) R.color.paper else R.color.ink))
                background = getDrawable(if (sort == key) R.drawable.btn_primary else R.drawable.btn_outline)
                setPadding(dp(11), dp(4), dp(11), dp(4))
                isClickable = true
                setOnClickListener { if (sort != key) { sort = key; loader.refresh() } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(7) })
        }
        sortChip(getString(R.string.post_sort_hot), "hot")
        sortChip(getString(R.string.post_sort_time), "time")
        mainBox.addView(row, lp())
    }

    private fun lp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun like(p: PostRow) {
        if (SessionStore.peek() == null) { toast(getString(R.string.detail_login_first)); return }
        Api.post("/api/v1/likes", JSONObject().put("targetType", "POST").put("targetId", p.id)) { r ->
            if (r is ApiResult.Ok) { post = p.copy(likedByMe = !p.likedByMe, likeCount = p.likeCount + if (p.likedByMe) -1 else 1); renderMain() }
            else if (r is ApiResult.Err) toast(r.message)
        }
    }

    private fun like(reply: ReplyRow) {
        if (SessionStore.peek() == null) { toast(getString(R.string.detail_login_first)); return }
        Api.post("/api/v1/likes", JSONObject().put("targetType", "REPLY").put("targetId", reply.id)) { r ->
            if (r is ApiResult.Err) toast(r.message)
        }
    }

    private fun pickReplyTo(reply: ReplyRow) {
        replyTo = reply
        replyToBar.text = getString(R.string.post_reply_to_fmt, reply.authorName) + "（点击取消）"
        replyToBar.visibility = View.VISIBLE
    }

    private fun sendReply() {
        val content = input.text.toString().trim()
        if (content.isEmpty()) { toast(getString(R.string.post_reply_empty)); return }
        if (SessionStore.peek() == null) { toast(getString(R.string.detail_login_first)); return }
        val p = post ?: return
        val body = JSONObject().put("content", content)
        replyTo?.let { body.put("parentReplyId", it.id) }
        Api.post("/api/v1/forum/posts/${p.id}/replies", body) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    toast(getString(R.string.post_reply_ok))
                    input.setText("")
                    replyTo = null
                    replyToBar.visibility = View.GONE
                    loader.refresh()
                }
                is ApiResult.Err -> toast(r.message)
                ApiResult.Net -> toast(getString(R.string.net_error))
            }
        }
    }
}
