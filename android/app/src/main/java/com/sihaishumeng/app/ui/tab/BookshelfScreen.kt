package com.sihaishumeng.app.ui.tab

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.Api
import com.sihaishumeng.app.core.api.ApiResult
import com.sihaishumeng.app.core.checkin.CheckinCurve
import com.sihaishumeng.app.core.session.SessionStore
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.displayName
import com.sihaishumeng.app.ui.common.relativeTime
import com.sihaishumeng.app.ui.common.toast
import com.sihaishumeng.app.ui.novel.formatWan
import org.json.JSONObject

/**
 * 批④ 书架 tab（登录墙）：签到条 + 继续阅读 + 书架列表（一次全量，长按移出）。
 * 数据：GET bookmarks + GET me/progress?take=12 + GET me/checkin；写：签到 POST、
 * 移出书架 POST bookmarks（幂等 toggle）。游客=游客墙（文案+去登录，对拍站方
 * CheckInCard 引导态）。SessionStore 失效（登录/登出/401）自动重载。
 */
@SuppressLint("SetTextI18n")
class BookshelfScreen(host: MainActivity) : TabScreen(host) {

    private sealed class Row {
        data class Progress(
            val title: String,
            val coverUrl: String?,
            val chapterOrderIndex: Int,
            val chapterCount: Int,
            val updatedAt: String?,
            val novelHref: String,
        ) : Row()

        data class Shelf(
            val targetType: String,
            val targetId: String,
            val title: String,
            val coverUrl: String?,
            val meta: String?,
            val href: String?, // null=已失效条目
        ) : Row()
    }

    private object Const {
        const val CHECKIN = 1
        const val SECTION = 2
        const val PROGRESS = 3
        const val SHELF = 4
        const val GUEST = 5
    }

    private var rows: List<Row> = emptyList()
    private var guestWall = false
    private var checkedToday = false
    private var streak = 0
    private var loading = false
    private lateinit var recycler: RecyclerView
    private lateinit var states: StateViews

    override fun build(): View {
        val root = android.widget.FrameLayout(host).apply { setBackgroundColor(host.getColor(R.color.paper)) }
        recycler = RecyclerView(host).apply {
            layoutManager = LinearLayoutManager(host)
            adapter = Adapter()
            clipToPadding = false
            setPadding(0, 0, 0, host.dp(16))
        }
        root.addView(recycler, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        states = StateViews(host, root)
        SessionStore.addListener { _ -> host.runOnUiThread { reload() } }
        reload()
        return root
    }

    override fun onShow() {
        if (rows.isEmpty() && !loading && !guestWall) reload()
    }

    private fun reload() {
        val user = SessionStore.peek()
        if (user == null) {
            if (!SessionStore.hasQueried) {
                states.loading()
                SessionStore.refresh { host.runOnUiThread { reload() } }
                return
            }
            rows = emptyList()
            guestWall = true
            recycler.adapter?.notifyDataSetChanged()
            states.clear()
            return
        }
        loading = true
        guestWall = false
        states.loading()
        var bookmarks: JSONObject? = null
        var progress: JSONObject? = null
        var checkin: JSONObject? = null
        var left = 3
        fun maybeDone() {
            if (left > 0) return
            loading = false
            if (bookmarks == null && progress == null && checkin == null) {
                states.error(host.getString(R.string.net_error)) { reload() }
                return
            }
            states.clear()
            val out = mutableListOf<Row>()
            progress?.optJSONArray("list")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val novel = o.optJSONObject("novel") ?: continue
                    val href = if (novel.optBoolean("isPublicDomain", false)) {
                        "/classics/${if (novel.isNull("pdNo")) novel.optString("id") else novel.optInt("pdNo")}"
                    } else {
                        "/novels/${if (novel.isNull("workNo")) novel.optString("slug") else novel.optInt("workNo")}"
                    }
                    out.add(
                        Row.Progress(
                            title = novel.optString("title"),
                            coverUrl = novel.optString("coverUrl").ifEmpty { null },
                            chapterOrderIndex = o.optInt("chapterOrderIndex", 1),
                            chapterCount = novel.optInt("chapterCount", 0),
                            updatedAt = o.optString("updatedAt").ifEmpty { null },
                            novelHref = href,
                        )
                    )
                }
            }
            var shelfCount = 0
            bookmarks?.optJSONArray("list")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val type = o.optString("targetType")
                    val target = o.optJSONObject("target")
                    val href = when {
                        target == null -> null
                        type == "NOVEL" -> {
                            if (target.optBoolean("isPublicDomain", false)) {
                                "/classics/${if (target.isNull("pdNo")) target.optString("id") else target.optInt("pdNo")}"
                            } else {
                                "/novels/${if (target.isNull("workNo")) target.optString("slug") else target.optInt("workNo")}"
                            }
                        }
                        else -> {
                            val no = if (target.isNull("no")) null else target.optInt("no")
                            if (no != null) "/articles/$no" else null
                        }
                    }
                    val meta = if (target != null && type == "NOVEL") {
                        val author = target.optJSONObject("author")
                        "${displayName(author?.optString("nickname"), author?.optString("username"))} · ${formatWan(target.optLong("wordCount", 0))}字"
                    } else {
                        null
                    }
                    out.add(
                        Row.Shelf(
                            targetType = type,
                            targetId = o.optString("targetId"),
                            title = target?.optString("title")?.ifEmpty { null } ?: "已失效条目",
                            coverUrl = target?.optString("coverUrl")?.ifEmpty { null },
                            meta = meta,
                            href = href,
                        )
                    )
                    shelfCount++
                }
            }
            rows = out
            checkin?.let {
                checkedToday = it.optBoolean("checkedToday", false)
                streak = it.optInt("streak", 0)
            }
            if (shelfCount == 0 && out.none { it is Row.Progress }) {
                states.empty(host.getString(R.string.shelf_empty), host.getString(R.string.shelf_goto_library)) {
                    host.selectTab(MainActivity.Tab.LIBRARY, "novels")
                }
            }
            recycler.adapter?.notifyDataSetChanged()
        }
        Api.get("/api/v1/bookmarks") { r ->
            bookmarks = (r as? ApiResult.Ok)?.data
            left--; maybeDone()
        }
        Api.get("/api/v1/me/progress", mapOf("take" to "12")) { r ->
            progress = (r as? ApiResult.Ok)?.data
            left--; maybeDone()
        }
        Api.get("/api/v1/me/checkin") { r ->
            if (r is ApiResult.Ok) checkin = r.data
            left--; maybeDone()
        }
    }

    private fun doCheckin() {
        if (checkedToday) return
        Api.post("/api/v1/me/checkin", JSONObject()) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    checkedToday = true
                    streak = r.data?.optInt("streak", streak) ?: streak
                    val tier = CheckinCurve.tierFor(streak)
                    host.toast(host.getString(R.string.checkin_success_fmt, tier.item, tier.morality))
                    recycler.adapter?.notifyDataSetChanged()
                }
                is ApiResult.Err -> host.toast(r.message)
                ApiResult.Net -> host.toast(host.getString(R.string.net_error))
            }
        }
    }

    private fun removeShelf(row: Row.Shelf) {
        Api.post(
            "/api/v1/bookmarks",
            JSONObject().put("targetType", row.targetType).put("targetId", row.targetId),
        ) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    host.toast(host.getString(R.string.shelf_removed))
                    reload()
                }
                is ApiResult.Err -> host.toast(r.message)
                ApiResult.Net -> host.toast(host.getString(R.string.net_error))
            }
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

    /** 横条（封面 44x58 + 标题/meta 双行）——继续阅读与书架条共用形态 */
    private fun buildMediaRow(): Pair<LinearLayout, Pair<ImageView, LinearLayout>> {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(16), host.dp(7), host.dp(16), host.dp(7))
            isClickable = true
            isFocusable = true
            background = host.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
        }
        val cover = ImageView(host).apply { setBackgroundResource(R.drawable.bg_cover) }
        row.addView(cover, LinearLayout.LayoutParams(host.dp(44), host.dp(58)).apply { marginEnd = host.dp(10) })
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun line(size: Float, bold: Boolean, color: Int, maxLines: Int): TextView = TextView(host).apply {
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(line(14f, true, host.getColor(R.color.ink), 1), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(line(11f, false, host.getColor(R.color.inkSoft), 1), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = host.dp(3) })
        return row to (cover to col)
    }

    private inner class MediaHolder(row: LinearLayout, cover: ImageView, col: LinearLayout) : RecyclerView.ViewHolder(row) {
        val thisRow: LinearLayout = row
        val coverImg: ImageView = cover
        val title: TextView = col.getChildAt(0) as TextView
        val meta: TextView = col.getChildAt(1) as TextView
    }

    private inner class CheckinHolder(v: LinearLayout) : RecyclerView.ViewHolder(v) {
        val status: TextView = TextView(host).apply {
            textSize = 13f
            setTextColor(host.getColor(R.color.inkSoft))
            maxLines = 2
        }

        val btn: Button = Button(host).apply {
            textSize = 13f
            setTextColor(host.getColor(R.color.paper))
            background = host.getDrawable(R.drawable.btn_primary)
            isAllCaps = false
            setPadding(host.dp(18), host.dp(6), host.dp(18), host.dp(6))
            minHeight = 0
            minWidth = 0
        }
    }

    private fun viewTypeOf(position: Int): Int {
        if (guestWall) return Const.GUEST
        if (position == 0) return Const.CHECKIN
        val cur = rows.getOrNull(position)
        val prev = rows.getOrNull(position - 1)
        return when {
            cur is Row.Progress && prev !is Row.Progress -> Const.SECTION
            cur is Row.Shelf && prev !is Row.Shelf -> Const.SECTION
            cur is Row.Progress -> Const.PROGRESS
            else -> Const.SHELF
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = viewTypeOf(position)

        override fun getItemCount(): Int = if (guestWall) 1 else rows.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                Const.GUEST -> object : RecyclerView.ViewHolder(buildGuestWall()) {}
                Const.CHECKIN -> {
                    val row = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(8))
                    }
                    val h = CheckinHolder(row)
                    row.addView(h.status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(h.btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = host.dp(10) })
                    h
                }
                Const.SECTION -> object : RecyclerView.ViewHolder(sectionTitle("")) {}
                else -> {
                    val (row, pair) = buildMediaRow()
                    MediaHolder(row, pair.first, pair.second)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (viewTypeOf(position)) {
                Const.GUEST -> return
                Const.CHECKIN -> bindCheckin(holder)
                Const.SECTION -> {
                    (holder.itemView as TextView).text = when (rows.getOrNull(position)) {
                        is Row.Shelf -> host.getString(R.string.shelf_title)
                        else -> host.getString(R.string.shelf_continue_reading)
                    }
                }
                Const.PROGRESS -> {
                    val row = rows[position] as Row.Progress
                    holder as MediaHolder
                    holder.title.text = row.title
                    holder.meta.text = buildString {
                        append("读到 第${row.chapterOrderIndex}章")
                        if (row.chapterCount > 0) append(" / 共${row.chapterCount}章")
                        append(" · ").append(relativeTime(row.updatedAt))
                    }
                    holder.coverImg.load(row.coverUrl) {
                        placeholder(R.drawable.bg_cover); error(R.drawable.bg_cover); crossfade(true)
                    }
                    holder.thisRow.setOnClickListener {
                        host.openInWebView("${row.novelHref}/chapter/${row.chapterOrderIndex}")
                    }
                }
                else -> {
                    val row = rows[position] as Row.Shelf
                    holder as MediaHolder
                    holder.title.text = row.title
                    holder.meta.text = row.meta ?: "该内容已下架或删除"
                    holder.coverImg.load(row.coverUrl) {
                        placeholder(R.drawable.bg_cover); error(R.drawable.bg_cover); crossfade(true)
                    }
                    holder.thisRow.setOnClickListener {
                        if (row.href != null) host.openNativeOrWeb(row.href) else host.toast("该内容已失效")
                    }
                    holder.thisRow.setOnLongClickListener {
                        removeShelf(row)
                        true
                    }
                }
            }
        }
    }

    private fun bindCheckin(holder: RecyclerView.ViewHolder) {
        holder as CheckinHolder
        val tier = CheckinCurve.tierFor(if (checkedToday) streak else streak + 1)
        holder.status.text = if (checkedToday) {
            "${host.getString(R.string.checkin_done_today)} · 连续 ${streak} 天"
        } else {
            "连续 ${streak} 天 · 签到 +${tier.item}墨点 +${tier.morality}侠义"
        }
        holder.btn.text = if (checkedToday) host.getString(R.string.checkin_done_today) else host.getString(R.string.checkin_btn)
        holder.btn.isEnabled = !checkedToday
        holder.btn.setOnClickListener { doCheckin() }
        holder.status.setOnClickListener { showTierDialog() }
    }

    private fun showTierDialog() {
        val sb = StringBuilder()
        CheckinCurve.TIERS.forEach { t ->
            sb.append(host.getString(R.string.checkin_tier_row_fmt, t.at, t.item, t.morality)).append('\n')
            if (t.xpBonus > 0) sb.append("　").append(host.getString(R.string.checkin_tier_bonus_fmt, t.at, t.xpBonus)).append('\n')
        }
        CheckinCurve.nextTier(streak)?.let { n -> sb.append('\n').append(host.getString(R.string.checkin_next_fmt, n.at - streak)) }
        android.app.AlertDialog.Builder(host)
            .setTitle(R.string.checkin_tier_title)
            .setMessage(sb.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildGuestWall(): View {
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(host.dp(40), host.dp(60), host.dp(40), host.dp(40))
        }
        box.addView(TextView(host).apply {
            text = host.getString(R.string.shelf_guest_hint)
            textSize = 14f
            setTextColor(host.getColor(R.color.inkSoft))
            gravity = Gravity.CENTER
        })
        val btn = Button(host).apply {
            text = host.getString(R.string.shelf_guest_login)
            textSize = 15f
            setTextColor(host.getColor(R.color.paper))
            background = host.getDrawable(R.drawable.btn_primary)
            isAllCaps = false
            setPadding(host.dp(30), host.dp(10), host.dp(30), host.dp(10))
            minHeight = 0
            minWidth = 0
            setOnClickListener { host.openLogin() }
        }
        box.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = host.dp(18) })
        return box
    }
}
