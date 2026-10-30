package com.sihaishumeng.app.ui.common

import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.PagedLoader
import org.json.JSONObject

/**
 * 批④ 跨域共用行件：用户行（搜索用户 tab）+ 通知行（通知页）。
 */

data class UserRow(
    val id: String,
    val uid: Int?,
    val username: String,
    val nickname: String?,
    val bio: String?,
    val avatarUrl: String?,
) {
    val showName: String get() = displayName(nickname, username)
    val href: String get() = "/users/${uid ?: id}"
}

fun parseUserRow(o: JSONObject): UserRow = UserRow(
    id = o.optString("id"),
    uid = if (o.isNull("uid")) null else o.optInt("uid"),
    username = o.optString("username"),
    nickname = o.optString("nickname").ifEmpty { null },
    bio = o.optString("bio").ifEmpty { null },
    avatarUrl = o.optString("avatarUrl").ifEmpty { null },
)

class UserRowAdapter(
    loader: PagedLoader<UserRow>,
    private val onClick: (UserRow) -> Unit,
) : PagedAdapter<UserRow, UserRowAdapter.Holder>(loader) {

    class Holder(
        val root: LinearLayout,
        val avatar: ImageView,
        val name: TextView,
        val bio: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateItem(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(c.dp(16), c.dp(9), c.dp(16), c.dp(9))
            isClickable = true
            isFocusable = true
            background = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
        }
        val avatar = ImageView(c).apply { setBackgroundResource(R.drawable.bg_cover) }
        root.addView(avatar, LinearLayout.LayoutParams(c.dp(40), c.dp(40)).apply { marginEnd = c.dp(11) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val name = TextView(c).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.getColor(R.color.ink))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bio = TextView(c).apply {
            textSize = 12f
            setTextColor(c.getColor(R.color.inkSoft))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(bio, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(2) })
        return Holder(root, avatar, name, bio)
    }

    override fun onBindItem(holder: Holder, position: Int, item: UserRow) {
        holder.root.setOnClickListener { onClick(item) }
        holder.name.text = if (item.nickname != null) "${item.showName}（${item.username}）" else item.username
        holder.bio.text = item.bio ?: ""
        holder.avatar.load(item.avatarUrl) {
            placeholder(R.drawable.bg_cover)
            error(R.drawable.bg_cover)
            crossfade(true)
        }
    }
}

data class NotificationRow(
    val id: String,
    val type: String,
    val title: String,
    val content: String,
    val link: String?,
    val isRead: Boolean,
    val createdAt: String?,
    val senderName: String?,
)

fun parseNotificationRow(o: JSONObject): NotificationRow = NotificationRow(
    id = o.optString("id"),
    type = o.optString("type"),
    title = o.optString("title").ifEmpty { null } ?: "",
    content = o.optString("content"),
    link = o.optString("link").ifEmpty { null },
    isRead = o.optBoolean("isRead", false),
    createdAt = o.optString("createdAt").ifEmpty { null },
    senderName = o.optJSONObject("sender")?.let { displayName(it.optString("nickname"), it.optString("username")) },
)

class NotificationAdapter(
    loader: PagedLoader<NotificationRow>,
    private val onClick: (NotificationRow) -> Unit,
    private val onLongClick: (NotificationRow) -> Unit,
) : PagedAdapter<NotificationRow, NotificationAdapter.Holder>(loader) {

    class Holder(
        v: LinearLayout,
        val dot: View,
        val title: TextView,
        val content: TextView,
        val meta: TextView,
        val row: LinearLayout,
    ) : RecyclerView.ViewHolder(v)

    override fun onCreateItem(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(c.dp(16), c.dp(11), c.dp(16), c.dp(11))
            isClickable = true
            isFocusable = true
            background = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
        }
        val dot = View(c).apply {
            setBackgroundResource(R.drawable.dot_unread)
        }
        row.addView(dot, LinearLayout.LayoutParams(c.dp(8), c.dp(8)).apply { marginEnd = c.dp(9); topMargin = c.dp(5) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val title = TextView(c).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.getColor(R.color.ink))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val content = TextView(c).apply {
            textSize = 12f
            setTextColor(c.getColor(R.color.inkSoft))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(2) })
        val meta = TextView(c).apply {
            textSize = 11f
            setTextColor(c.getColor(R.color.inkSoft))
        }
        col.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(3) })
        return Holder(row, dot, title, content, meta, row)
    }

    override fun onBindItem(holder: Holder, position: Int, item: NotificationRow) {
        holder.row.setOnClickListener { onClick(item) }
        holder.row.setOnLongClickListener { onLongClick(item); true }
        holder.title.text = item.title.ifEmpty { item.type }
        holder.content.text = item.content
        holder.meta.text = buildString {
            item.senderName?.let { append("$it · ") }
            append(relativeTime(item.createdAt))
        }
        holder.dot.visibility = if (item.isRead) View.INVISIBLE else View.VISIBLE
    }
}
