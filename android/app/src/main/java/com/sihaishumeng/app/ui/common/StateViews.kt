package com.sihaishumeng.app.ui.common

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.sihaishumeng.app.R

/**
 * 批④ 通用三态遮罩（加载中/空/错误+重试）：盖进页面根 FrameLayout，互斥单显。
 * 列表页与详情页共用——首屏态由 PagedLoader 的 firstLoading/firstError 驱动，
 * 本件只管呈现（业务态判定留在页面）。
 */
class StateViews(context: Context, parent: ViewGroup) {

    private val overlay = FrameLayout(context).apply {
        visibility = android.view.View.GONE
        setBackgroundColor(context.getColor(R.color.paper))
        isClickable = true
        isFocusable = true
    }

    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(40), 0, context.dp(40), 0)
    }

    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.brand))
    }

    private val message = TextView(context).apply {
        textSize = 14f
        setTextColor(context.getColor(R.color.inkSoft))
        gravity = Gravity.CENTER
    }

    private val retry = TextView(context).apply {
        setText(R.string.state_retry)
        textSize = 15f
        setTextColor(context.getColor(R.color.paper))
        gravity = Gravity.CENTER
        background = context.getDrawable(R.drawable.btn_primary)
        setPadding(context.dp(34), context.dp(10), context.dp(34), context.dp(10))
        visibility = android.view.View.GONE
    }

    init {
        box.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(10) })
        box.addView(retry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(18) })
        overlay.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        parent.addView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    fun loading(msg: String? = null) {
        spinner.show()
        message.text = msg ?: ""
        retry.gone()
        overlay.show()
    }

    fun empty(msg: String) {
        spinner.gone()
        message.text = msg
        retry.gone()
        overlay.show()
    }

    /** 空态+动作钮（书架空态「去书库逛逛」/游客墙「去登录」等） */
    fun empty(msg: String, actionLabel: String, onAction: () -> Unit) {
        spinner.gone()
        message.text = msg
        retry.text = actionLabel
        retry.setOnClickListener { onAction() }
        retry.show()
        overlay.show()
    }

    fun error(msg: String, onRetry: (() -> Unit)?) {
        spinner.gone()
        message.text = msg
        if (onRetry != null) {
            retry.setOnClickListener { onRetry() }
            retry.show()
        } else {
            retry.gone()
        }
        overlay.show()
    }

    fun clear() {
        overlay.gone()
    }
}
