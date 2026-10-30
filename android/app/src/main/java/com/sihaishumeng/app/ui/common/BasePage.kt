package com.sihaishumeng.app.ui.common

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.sihaishumeng.app.R

/**
 * 批④ 深页基类：根 FrameLayout（paper 底）+ 竖列〔TitleBar ｜ 内容槽〕+ edge-to-edge
 * insets 自管（LoginActivity 同款 maxOf 语义，深页无输入框冲突面用 systemBars 即可）。
 * 子类 onCreate 里 body() 拿内容槽装内容。
 */
abstract class BasePageActivity : AppCompatActivity() {

    private lateinit var contentSlot: LinearLayout
    private lateinit var rootFrame: FrameLayout

    val pageRoot: FrameLayout get() = rootFrame

    protected fun setupPage(title: String) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        rootFrame = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.paper)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootFrame.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(TitleBar(this).apply { this.title(title) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(contentSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        setContentView(rootFrame)
    }

    protected fun body(): LinearLayout = contentSlot
}
