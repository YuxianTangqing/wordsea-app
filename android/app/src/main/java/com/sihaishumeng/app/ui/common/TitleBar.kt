package com.sihaishumeng.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sihaishumeng.app.R

/**
 * 批④ 深页统一标题栏：返回箭头 + 标题 + 右侧动作位（图标钮）。代码构建
 * （LoginActivity 范式：无 ActionBar，自管 edge-to-edge padding 由页面根布局承担）。
 */
class TitleBar(context: Context) : LinearLayout(context) {

    private val titleView: TextView
    private val rightSlot: FrameLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(6), context.dp(8), context.dp(10), context.dp(8))
        setBackgroundColor(context.getColor(R.color.paper))

        val back = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ink))
            val pad = context.dp(10)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                .getDrawable(0)?.also { it.isAutoMirrored = true }
            setOnClickListener {
                (context as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
            }
        }
        addView(back, LayoutParams(context.dp(44), ViewGroup.LayoutParams.MATCH_PARENT))

        titleView = TextView(context).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.ink))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(6)
        })

        rightSlot = FrameLayout(context)
        addView(rightSlot, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun title(text: String) {
        titleView.text = text
    }

    /** 右侧动作钮（返回 View 便于页面控制显隐/换图） */
    fun action(iconRes: Int, contentDesc: String, onClick: (View) -> Unit): ImageView {
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ink))
            val pad = context.dp(10)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                .getDrawable(0)
            contentDescription = contentDesc
            setOnClickListener { onClick(it) }
        }
        rightSlot.removeAllViews()
        rightSlot.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
        return icon
    }

    fun clearAction() {
        rightSlot.removeAllViews()
    }
}
