package com.sihaishumeng.app.ui.forum

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.PagedLoader
import com.sihaishumeng.app.ui.common.PagedAdapter
import com.sihaishumeng.app.ui.common.displayName
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.relativeTime
import org.json.JSONObject

/**
 * 批④ 论坛域行件：帖子行（门面热帖/板块列表/搜索帖 tab 共用）+ 回帖行 + 版块行
 * + 最新回复行。字段对拍 postInclude/replyInclude/ForumPost 模型列。
 */

data class PostRow(
    val id: String,
    val no: Int,
    val title: String,
    val excerpt: String?,
    val content: String?, // 详情口才有全文（列表口是 excerpt）
    val authorName: String,
    val authorAvatar: String?,
    val boardSlug: String,
    val boardName: String,
    val replyCount: Int,
    val likeCount: Int,
    val viewCount: Int,
    val isPinned: Boolean,
    val isEssenced: Boolean,
    val isLocked: Boolean,
    val category: String?,
    val isActivity: Boolean?,
    val createdAt: String?,
    val likedByMe: Boolean,
) {
    val href: String get() = "/post/$no"
}

fun parsePostRow(o: JSONObject, full: Boolean = false): PostRow {
    val author = o.optJSONObject("author")
    val board = o.optJSONObject("board")
    return PostRow(
        id = o.optString("id"),
        no = if (o.isNull("no")) 0 else o.optInt("no"),
        title = o.optString("title"),
        excerpt = o.optString("excerpt").ifEmpty { null },
        content = if (full) o.optString("content").ifEmpty { null } else null,
        authorName = displayName(author?.optString("nickname"), author?.optString("username")),
        authorAvatar = author?.optString("avatarUrl")?.ifEmpty { null },
        boardSlug = board?.optString("slug") ?: "",
        boardName = board?.optString("name") ?: "",
        replyCount = o.optInt("replyCount", 0),
        likeCount = o.optInt("likeCount", 0),
        viewCount = o.optInt("viewCount", 0),
        isPinned = o.optBoolean("isPinned", false),
        isEssenced = o.optBoolean("isEssenced", false),
        isLocked = o.optBoolean("isLocked", false),
        category = o.optString("category").ifEmpty { null },
        isActivity = if (o.isNull("isActivity")) null else o.optBoolean("isActivity"),
        createdAt = o.optString("createdAt").ifEmpty { null },
        likedByMe = o.optBoolean("likedByMe", false),
    )
}

/** 帖行（列表形态）：徽章行〔置顶/精华/锁定/分类〕+ 标题 + meta（板块·作者·回复·时间） */
class PostRowAdapter(
    loader: PagedLoader<PostRow>,
    private val onClick: (PostRow) -> Unit,
) : PagedAdapter<PostRow, PostRowAdapter.Holder>(loader) {

    class Holder(
        val root: LinearLayout,
        val badges: LinearLayout,
        val title: TextView,
        val excerpt: TextView,
        val meta: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateItem(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
            isClickable = true
            isFocusable = true
            background = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
        }
        val badges = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(badges, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val title = TextView(c).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.getColor(R.color.ink))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(3) })
        val excerpt = TextView(c).apply {
            textSize = 12f
            setTextColor(c.getColor(R.color.inkSoft))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(excerpt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(3) })
        val meta = TextView(c).apply {
            textSize = 11f
            setTextColor(c.getColor(R.color.inkSoft))
        }
        root.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(4) })
        return Holder(root, badges, title, excerpt, meta)
    }

    override fun onBindItem(holder: Holder, position: Int, item: PostRow) {
        holder.root.setOnClickListener { onClick(item) }
        holder.title.text = item.title
        holder.excerpt.text = item.excerpt ?: ""
        holder.excerpt.visibility = if (item.excerpt != null) View.VISIBLE else View.GONE
        holder.meta.text = buildString {
            if (item.boardName.isNotEmpty()) append("${item.boardName} · ")
            append("${item.authorName} · 💬${item.replyCount} · 👍${item.likeCount} · ${relativeTime(item.createdAt)}")
        }
        holder.badges.removeAllViews()
        val c = holder.badges.context
        fun badge(text: String, color: Int) {
            val tv = TextView(c).apply {
                this.text = text
                textSize = 10f
                setTextColor(color)
                background = c.getDrawable(R.drawable.badge_tag)
                setPadding(c.dp(7), c.dp(2), c.dp(7), c.dp(2))
            }
            holder.badges.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = c.dp(5) })
        }
        val brand = c.getColor(R.color.brand)
        val soft = c.getColor(R.color.inkSoft)
        if (item.isPinned) badge("置顶", brand)
        if (item.isEssenced) badge("精华", brand)
        if (item.isLocked) badge("锁", soft)
        item.category?.let { badge(it, soft) }
        if (item.isActivity == true) badge("活动", brand)
    }
}

/** 回帖行：头像+名字+楼层/采纳+内容+meta（点赞/时间/回复对象） */
data class ReplyRow(
    val id: String,
    val content: String,
    val authorName: String,
    val authorAvatar: String?,
    val likeCount: Int,
    val accepted: Boolean,
    val pinned: Boolean,
    val replyTo: String?, // 被回复者名（parentReply）
    val createdAt: String?,
    val likedByMe: Boolean,
)

fun parseReplyRow(o: JSONObject): ReplyRow {    val author = o.optJSONObject("author")
    val parent = o.optJSONObject("parentReply")
    return ReplyRow(
        id = o.optString("id"),
        content = o.optString("content"),
        authorName = displayName(author?.optString("nickname"), author?.optString("username")),
        authorAvatar = author?.optString("avatarUrl")?.ifEmpty { null },
        likeCount = o.optInt("likeCount", 0),
        accepted = o.optBoolean("accepted", false),
        pinned = o.optBoolean("pinned", false),
        replyTo = parent?.optJSONObject("author")?.let { displayName(it.optString("nickname"), it.optString("username")) },
        createdAt = o.optString("createdAt").ifEmpty { null },
        likedByMe = o.optBoolean("likedByMe", false),
    )
}

class ReplyAdapter(
    loader: PagedLoader<ReplyRow>,
    private val onAvatarClick: (ReplyRow) -> Unit,
    private val onLikeClick: (ReplyRow) -> Unit,
    private val onReplyClick: (ReplyRow) -> Unit,
) : PagedAdapter<ReplyRow, ReplyAdapter.Holder>(loader) {

    class Holder(
        v: LinearLayout,
        val avatar: ImageView,
        val name: TextView,
        val badges: LinearLayout,
        val content: TextView,
        val replyTo: TextView,
        val meta: TextView,
        val like: TextView,
    ) : RecyclerView.ViewHolder(v)

    override fun onCreateItem(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val root = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(6)) }
        val avatar = ImageView(c).apply {
            setBackgroundResource(R.drawable.bg_cover)
            clipToOutline = false
        }
        root.addView(avatar, LinearLayout.LayoutParams(c.dp(34), c.dp(34)).apply { marginEnd = c.dp(9); topMargin = c.dp(2) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val nameLine = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(nameLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val name = TextView(c).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.getColor(R.color.brand))
        }
        nameLine.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val badges = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        nameLine.addView(badges, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(6) })

        val replyTo = TextView(c).apply {
            textSize = 11f
            setTextColor(c.getColor(R.color.inkSoft))
            visibility = View.GONE
        }
        col.addView(replyTo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(1) })
        val content = TextView(c).apply {
            textSize = 14f
            setTextColor(c.getColor(R.color.ink))
        }
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(3) })
        val metaLine = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(metaLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(5) })
        val meta = TextView(c).apply {
            textSize = 11f
            setTextColor(c.getColor(R.color.inkSoft))
        }
        metaLine.addView(meta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val like = TextView(c).apply {
            textSize = 12f
            setTextColor(c.getColor(R.color.inkSoft))
            isClickable = true
            background = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
            setPadding(c.dp(8), c.dp(2), c.dp(8), c.dp(2))
        }
        metaLine.addView(like, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(root, avatar, name, badges, content, replyTo, meta, like)
    }

    override fun onBindItem(holder: Holder, position: Int, item: ReplyRow) {
        holder.name.text = item.authorName
        holder.content.text = item.content
        holder.meta.text = relativeTime(item.createdAt)
        holder.like.text = if (item.likedByMe) "👍 ${item.likeCount}" else "👍 ${item.likeCount}"
        holder.like.setTextColor(holder.like.context.getColor(if (item.likedByMe) R.color.brand else R.color.inkSoft))
        holder.replyTo.visibility = if (item.replyTo != null) View.VISIBLE else View.GONE
        holder.replyTo.text = "回复 ${item.replyTo}"
        holder.badges.removeAllViews()
        val c = holder.badges.context
        fun badge(text: String, color: Int) {
            val tv = TextView(c).apply {
                this.text = text
                textSize = 10f
                setTextColor(color)
                background = c.getDrawable(R.drawable.badge_tag)
                setPadding(c.dp(6), c.dp(1), c.dp(6), c.dp(1))
            }
            holder.badges.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = c.dp(4) })
        }
        val brand = c.getColor(R.color.brand)
        val soft = c.getColor(R.color.inkSoft)
        if (item.accepted) badge("✓ 采纳", brand)
        if (item.pinned) badge("置顶", brand)
        holder.avatar.load(item.authorAvatar) {
            placeholder(R.drawable.bg_cover)
            error(R.drawable.bg_cover)
            crossfade(true)
        }
        holder.avatar.setOnClickListener { onAvatarClick(item) }
        holder.like.setOnClickListener { onLikeClick(item) }
        holder.content.setOnClickListener { onReplyClick(item) }
    }
}

// ── 论坛门面（forum/home 聚合口）数据件：定长内容，不进 RecyclerView ──

data class BoardLite(
    val id: String,
    val slug: String,
    val name: String,
    val description: String,
    val todayCount: Int,
)

data class HotPostItem(
    val no: Int,
    val title: String,
    val replyCount: Int,
    val likeCount: Int,
    val boardSlug: String,
    val boardName: String,
    val isActivity: Boolean,
)

data class LatestReplyItem(
    val id: String,
    val content: String,
    val authorName: String,
    val postNo: Int,
    val postTitle: String,
    val boardName: String,
    val createdAt: String?,
)

fun parseForumHome(data: JSONObject): Triple<List<BoardLite>, Map<String, List<HotPostItem>>, List<LatestReplyItem>> {
    val boards = data.optJSONArray("boards")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BoardLite(
                id = o.optString("id"),
                slug = o.optString("slug"),
                name = o.optString("name"),
                description = o.optString("description"),
                todayCount = o.optInt("todayCount", 0),
            )
        }
    } ?: emptyList()
    val hot = data.optJSONObject("hot")
    fun parseHot(key: String): List<HotPostItem> = hot?.optJSONArray(key)?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HotPostItem(
                no = if (o.isNull("no")) 0 else o.optInt("no"),
                title = o.optString("title"),
                replyCount = o.optInt("replyCount", 0),
                likeCount = o.optInt("likeCount", 0),
                boardSlug = o.optJSONObject("board")?.optString("slug") ?: "",
                boardName = o.optJSONObject("board")?.optString("name") ?: "",
                isActivity = o.optBoolean("isActivity", false),
            )
        }
    } ?: emptyList()
    val latest = data.optJSONArray("latestReplies")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val post = o.optJSONObject("post")
            LatestReplyItem(
                id = o.optString("id"),
                content = o.optString("content"),
                authorName = displayName(o.optJSONObject("author")?.optString("nickname"), o.optJSONObject("author")?.optString("username")),
                postNo = if (post?.isNull("no") != false) 0 else post.optInt("no"),
                postTitle = post?.optString("title") ?: "",
                boardName = post?.optJSONObject("board")?.optString("name") ?: "",
                createdAt = o.optString("createdAt").ifEmpty { null },
            )
        }
    } ?: emptyList()
    return Triple(boards, mapOf("today" to parseHot("today"), "week" to parseHot("week"), "month" to parseHot("month")), latest)
}
