package com.sihaishumeng.app.ui.common

import android.content.Context
import android.view.View
import android.widget.Toast

/**
 * 批④ ui/common 微工具：dp 换算/显隐/Toast（LoginActivity 时代散落的惯用法收口）。
 * 色值统一走 res/colors.xml 品牌色板（paper/ink/inkSoft/brand/brandStroke）。
 */

fun Context.dp(v: Int): Int = (resources.displayMetrics.density * v + 0.5f).toInt()

fun View.show() {
    visibility = View.VISIBLE
}

fun View.gone() {
    visibility = View.GONE
}

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** 相对时间（ISO8601 → 「刚刚/N 分钟前/N 小时前/N 天前/日期」）——列表行meta 惯用。 */
fun relativeTime(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    return try {
        val t = java.time.Instant.parse(iso).toEpochMilli()
        val diff = System.currentTimeMillis() - t
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            diff < day -> "${diff / hour} 小时前"
            diff < 30 * day -> "${diff / day} 天前"
            else -> {
                val z = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                "${z.year}-${z.monthValue.toString().padStart(2, '0')}-${z.dayOfMonth.toString().padStart(2, '0')}"
            }
        }
    } catch (_: Exception) {
        ""
    }
}

/** 展示名口径（对齐站方 displayName）：昵称优先、回落用户名。 */
fun displayName(nickname: String?, username: String?): String =
    (nickname?.takeIf { it.isNotBlank() }) ?: (username?.takeIf { it.isNotBlank() }) ?: "书友"
