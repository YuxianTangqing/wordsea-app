package com.sihaishumeng.app.ui.novel

import android.graphics.Typeface
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
import org.json.JSONObject

/**
 * 批④ 作品域行件：NovelCard（书库/搜索共用卡片）+ ChapterRow（详情目录，卷分组头）
 * + ReviewRow（详情书评）。行视图代码构建（BiliAuthActivity 先例），字段名对拍
 * web 仓 novelInclude/Novel 模型列（coverUrl/status/workNo/pdNo/sourceAuthor…）。
 */

data class NovelCard(
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
    val isPublicDomain: Boolean,
    val workNo: Int?,
    val pdNo: Int?,
    val slug: String?,
    val sourceAuthor: String?,
    /** 显式地址覆盖（articles 段复用卡片形态时指文章页） */
    val hrefOverride: String? = null,
) {
    /** 详情页地址（数字规范链；公版走 classics 域；覆盖值优先） */
    val href: String
        get() = hrefOverride ?: if (isPublicDomain) "/classics/${pdNo ?: slug ?: id}"
        else "/novels/${workNo ?: slug ?: id}"
}

fun parseNovelCard(o: JSONObject): NovelCard = NovelCard(
    id = o.optString("id"),
    title = o.optString("title"),
    authorName = displayName(o.optJSONObject("author")?.optString("nickname"), o.optJSONObject("author")?.optString("username")),
    authorUid = o.optJSONObject("author")?.let { if (it.isNull("uid")) null else it.optInt("uid") },
    coverUrl = o.optString("coverUrl").ifEmpty { null },
    description = o.optString("description").ifEmpty { null },
    status = o.optString("status").ifEmpty { null },
    wordCount = o.optLong("wordCount", 0),
    chapterCount = o.optInt("chapterCount", 0),
    viewCount = o.optLong("viewCount", 0),
    isPublicDomain = o.optBoolean("isPublicDomain", false),
    workNo = if (o.isNull("workNo")) null else o.optInt("workNo"),
    pdNo = if (o.isNull("pdNo")) null else o.optInt("pdNo"),
    slug = o.optString("slug").ifEmpty { null },
    sourceAuthor = o.optString("sourceAuthor").ifEmpty { null },
)

fun formatWan(n: Long): String = when {
    n >= 10_000 -> {
        val w = n / 10_000.0
        if (w >= 100) "${w.toInt()}万" else String.format("%.1f万", w)
    }
    else -> n.toString()
}

/** 书卡（2 列网格）：封面 3:4 + 标题 + 作者/公版原作者 + 一行统计 */
class NovelCardAdapter(
    loader: PagedLoader<NovelCard>,
    private val onClick: (NovelCard) -> Unit,
) : PagedAdapter<NovelCard, NovelCardAdapter.Holder>(loader) {

    class Holder(val root: LinearLayout, val cover: ImageView, val title: TextView, val author: TextView, val meta: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateItem(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(7), c.dp(8), c.dp(7), c.dp(8))
            isClickable = true
            isFocusable = true
            background = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
        }
        val cover = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_cover)
        }
        root.addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(150)))
        val title = TextView(c).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.getColor(R.color.ink))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(6) })
        val author = TextView(c).apply {
            textSize = 12f
            setTextColor(c.getColor(R.color.inkSoft))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(author, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(2) })
        val meta = TextView(c).apply {
            textSize = 11f
            setTextColor(c.getColor(R.color.inkSoft))
            maxLines = 1
        }
        root.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(1) })
        return Holder(root, cover, title, author, meta)
    }

    override fun onBindItem(holder: Holder, position: Int, item: NovelCard) {
        holder.root.setOnClickListener { onClick(item) }
        holder.title.text = item.title
        holder.author.text = if (item.isPublicDomain && !item.sourceAuthor.isNullOrEmpty()) item.sourceAuthor else item.authorName
        holder.meta.text = buildString {
            append("${formatWan(item.wordCount)}字")
            if (item.chapterCount > 0) append(" · ${item.chapterCount}章")
            append(" · ${formatWan(item.viewCount)}阅")
        }
        holder.cover.load(item.coverUrl) {
            placeholder(R.drawable.bg_cover)
            error(R.drawable.bg_cover)
            crossfade(true)
        }
    }
}

/** 章节行（含卷分组头：volumeName 非空=头行） */
data class ChapterRow(
    val orderIndex: Int,
    val title: String,
    val wordCount: Int,
    val volumeName: String?, // 非空=分组头
)

class ChapterAdapter(
    private val rows: List<ChapterRow>,
    private val onClick: (ChapterRow) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_HEADER = 1
        const val TYPE_CHAPTER = 2
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v)
    private class ChapterHolder(
        v: LinearLayout,
        val title: TextView,
        val meta: TextView,
        val row: LinearLayout,
    ) : RecyclerView.ViewHolder(v)

    override fun getItemViewType(position: Int): Int = if (rows[position].volumeName != null) TYPE_HEADER else TYPE_CHAPTER

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val c = parent.context
        if (viewType == TYPE_HEADER) {
            val tv = TextView(c).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(c.getColor(R.color.brand))
                setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(4))
            }
            return HeaderHolder(tv)
        }
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(16), c.dp(9), c.dp(16), c.dp(9))
            isClickable = true
            isFocusable = true
            background = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
        }
        val title = TextView(c).apply {
            textSize = 14f
            setTextColor(c.getColor(R.color.ink))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val meta = TextView(c).apply {
            textSize = 11f
            setTextColor(c.getColor(R.color.inkSoft))
        }
        row.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(2) })
        return ChapterHolder(row, title, meta, row)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = rows[position]
        if (holder is HeaderHolder) {
            (holder.itemView as TextView).text = "卷 · ${item.volumeName}"
            return
        }
        holder as ChapterHolder
        holder.title.text = "${item.orderIndex}. ${item.title}"
        holder.meta.text = if (item.wordCount > 0) "${item.wordCount} 字" else ""
        holder.row.setOnClickListener {
            onClick(item)
        }
    }
}

/** 书评行（详情页书评区） */
data class ReviewRow(
    val id: String,
    val userName: String,
    val content: String,
    val likeCount: Int,
    val replyCount: Int,
    val createdAt: String?,
)

class ReviewAdapter(loader: PagedLoader<ReviewRow>) : PagedAdapter<ReviewRow, ReviewAdapter.Holder>(loader) {

    class Holder(
        v: LinearLayout,
        val name: TextView,
        val content: TextView,
        val meta: TextView,
    ) : RecyclerView.ViewHolder(v)

    override fun onCreateItem(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
        }
        val name = TextView(c).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.getColor(R.color.brand))
        }
        root.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val content = TextView(c).apply {
            textSize = 13f
            setTextColor(c.getColor(R.color.ink))
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(4) })
        val meta = TextView(c).apply {
            textSize = 11f
            setTextColor(c.getColor(R.color.inkSoft))
            gravity = Gravity.LEFT
        }
        root.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(4) })
        return Holder(root, name, content, meta)
    }

    override fun onBindItem(holder: Holder, position: Int, item: ReviewRow) {
        holder.name.text = item.userName
        holder.content.text = item.content
        holder.meta.text = "👍 ${item.likeCount} · 💬 ${item.replyCount} · ${relativeTimeSafe(item.createdAt)}"
    }

    private fun relativeTimeSafe(iso: String?): String =
        com.sihaishumeng.app.ui.common.relativeTime(iso)
}
