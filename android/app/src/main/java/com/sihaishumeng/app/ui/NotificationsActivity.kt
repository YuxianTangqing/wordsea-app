package com.sihaishumeng.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import com.sihaishumeng.app.ui.common.NotificationAdapter
import com.sihaishumeng.app.ui.common.StateViews
import com.sihaishumeng.app.ui.common.dp
import com.sihaishumeng.app.ui.common.parseNotificationRow
import com.sihaishumeng.app.ui.common.toast
import org.json.JSONObject

/**
 * 批④ 通知页（强登录）：六栏 chips（对拍 notification-tabs 白名单）+ 尾加载 +
 * 单条点击已读并跳转 link（NativeNav）、长按删除、全部已读/清空已读（标题栏右钮循环）。
 */
@SuppressLint("SetTextI18n")
class NotificationsActivity : BasePageActivity() {

    private data class TabDef(val value: String, val label: String)

    private val tabs = listOf(
        TabDef("", "全部"), TabDef("interactions", "互动"), TabDef("works", "作品"),
        TabDef("audit", "审核"), TabDef("broadcast", "广播"), TabDef("system", "系统"),
    )
    private var tab = ""

    private val loader = PagedLoader<com.sihaishumeng.app.ui.common.NotificationRow>(20) { page, done ->
        Api.get(
            "/api/v1/notifications",
            mapOf("page" to page.toString(), "pageSize" to "20", "tab" to tab.ifEmpty { null }),
        ) { r ->
            when (r) {
                is ApiResult.Ok -> {
                    val arr = r.data?.optJSONArray("list")
                    val rows = mutableListOf<com.sihaishumeng.app.ui.common.NotificationRow>()
                    if (arr != null) for (i in 0 until arr.length()) rows.add(parseNotificationRow(arr.getJSONObject(i)))
                    done(rows, r.data?.optInt("total", -1), false)
                }
                is ApiResult.Err -> { toast(r.message); done(null, null, true) }
                ApiResult.Net -> { toast(getString(R.string.net_error)); done(null, null, true) }
            }
        }
    }

    private lateinit var adapter: NotificationAdapter
    private lateinit var states: StateViews
    private lateinit var chips: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPage(getString(R.string.notifications_title))
        val c = this
        val top = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val chipRow = LinearLayout(c).apply { setPadding(dp(16), dp(8), dp(16), dp(2)); orientation = LinearLayout.HORIZONTAL }
        chips = tabs.map { t ->
            TextView(c).apply {
                text = t.label; textSize = 12f
                setPadding(dp(11), dp(4), dp(11), dp(4))
                isClickable = true
                setOnClickListener { tab = t.value; renderChips(); loader.refresh() }
            }.also { chipRow.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) }) }
        }
        val actRow = LinearLayout(c).apply { setPadding(dp(16), dp(4), dp(16), dp(4)); orientation = LinearLayout.HORIZONTAL }
        fun actBtn(label: String, onClick: () -> Unit): TextView =
            TextView(c).apply {
                text = label; textSize = 12f
                setTextColor(getColor(R.color.brand))
                background = getDrawable(R.drawable.badge_tag)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                isClickable = true; setOnClickListener { onClick() }
            }.also { actRow.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }) }
        actBtn(getString(R.string.notifications_mark_all)) { markAll() }
        actBtn(getString(R.string.notifications_clear_read)) { clearRead() }
        val recycler = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c) }
        adapter = NotificationAdapter(loader, onClick = { open(it) }, onLongClick = { delete(it) })
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                (rv.layoutManager as? LinearLayoutManager)?.let { loader.maybeLoadMore(it.findLastVisibleItemPosition()) }
            }
        })
        top.addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(actRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body().addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body().addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        states = StateViews(c, pageRoot)
        renderChips()
        loader.bind {
            adapter.notifyDataSetChanged()
            if (loader.firstLoading) states.loading()
            else if (loader.firstError) states.error(getString(R.string.net_error)) { loader.refresh() }
            else if (loader.hasData && loader.items.isEmpty()) states.empty(getString(R.string.notifications_empty))
            else states.clear()
        }
        if (SessionStore.peek() == null && !SessionStore.hasQueried) {
            states.loading()
            SessionStore.refresh { user -> runOnUiThread { if (user == null) states.empty(getString(R.string.notifications_login_hint)) else loader.refresh() } }
        } else if (SessionStore.peek() == null) {
            states.empty(getString(R.string.notifications_login_hint))
        } else {
            loader.refresh()
        }
    }

    private fun renderChips() {
        chips.forEachIndexed { i, tv ->
            val on = tabs[i].value == tab
            tv.setTextColor(getColor(if (on) R.color.paper else R.color.ink))
            tv.background = getDrawable(if (on) R.drawable.btn_primary else R.drawable.btn_outline)
        }
    }

    private fun open(row: com.sihaishumeng.app.ui.common.NotificationRow) {
        if (!row.isRead) Api.patch("/api/v1/notifications", JSONObject().put("id", row.id)) { _ -> }
        val link = row.link
        if (!link.isNullOrEmpty()) MainActivity.openNativeOrWeb(this, link)
    }

    private fun delete(row: com.sihaishumeng.app.ui.common.NotificationRow) {
        Api.delete("/api/v1/notifications/${row.id}") { r ->
            if (r is ApiResult.Ok) loader.refresh()
            else if (r is ApiResult.Err) toast(r.message)
        }
    }

    private fun markAll() {
        Api.patch("/api/v1/notifications", JSONObject().put("all", true)) { r ->
            if (r is ApiResult.Ok) loader.refresh() else if (r is ApiResult.Err) toast(r.message)
        }
    }

    private fun clearRead() {
        Api.delete("/api/v1/notifications/read") { r ->
            if (r is ApiResult.Ok) {
                toast(getString(R.string.notifications_cleared, r.data?.optInt("deleted", 0)))
                loader.refresh()
            } else if (r is ApiResult.Err) toast(r.message)
        }
    }
}
