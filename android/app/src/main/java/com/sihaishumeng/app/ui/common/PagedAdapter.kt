package com.sihaishumeng.app.ui.common

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.api.PagedLoader

/**
 * 批④ 分页列表 Adapter 基类：数据条目 + 尾部状态位（加载中/到底了/点重试）三态脚注。
 * 数据变动走 notifyDataSetChanged（追加式分页，pageSize 20-48 规模下全量刷新开销
 * 可忽略）。
 *
 * 页面侧配套：recyclerView.addOnScrollListener 里调 loader.maybeLoadMore(lastVisible)
 * ——加载触发权在 loader（节流/终态闸都在那边）；loader 状态变化经 bind 回调
 * notifyDataSetChanged。
 */
abstract class PagedAdapter<T, VH : RecyclerView.ViewHolder>(
    protected val loader: PagedLoader<T>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_FOOTER = -9999
    }

    init {
        loader.bind { notifyDataSetChanged() }
    }

    /** 数据条目 viewType（多种行形态时按 position 覆写） */
    protected open fun itemViewType(position: Int): Int = 0

    /** 条目行创建（脚注之外的一切行形态） */
    abstract fun onCreateItem(parent: ViewGroup, viewType: Int): VH

    /** 条目行绑定 */
    abstract fun onBindItem(holder: VH, position: Int, item: T)

    override fun getItemViewType(position: Int): Int =
        if (position == loader.size) TYPE_FOOTER else itemViewType(position)

    override fun getItemCount(): Int = loader.size + if (showFooter()) 1 else 0

    private fun showFooter(): Boolean = loader.hasData

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_FOOTER) return FooterHolder(parent, loader)
        @Suppress("UNCHECKED_CAST")
        return onCreateItem(parent, viewType) as RecyclerView.ViewHolder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is FooterHolder) {
            holder.bind()
            return
        }
        @Suppress("UNCHECKED_CAST")
        onBindItem(holder as VH, position, loader.get(position))
    }

    /** 尾部三态脚注（静态嵌套类：泛型宿主的 inner 类会带类型参，is 判定麻烦） */
    private class FooterHolder(parent: ViewGroup, val loader: PagedLoader<*>) : RecyclerView.ViewHolder(
        LinearLayout(parent.context).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, parent.context.dp(14), 0, parent.context.dp(18))
        }
    ) {
        private val row = itemView as LinearLayout
        private val spinner = ProgressBar(row.context).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(row.context.getColor(R.color.brand))
        }
        private val text = TextView(row.context).apply {
            textSize = 13f
            setTextColor(row.context.getColor(R.color.inkSoft))
            gravity = Gravity.CENTER
        }

        fun bind() {
            row.removeAllViews()
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            when {
                loader.moreError -> {
                    text.text = row.context.getString(R.string.paged_retry)
                    row.isClickable = true
                    row.setOnClickListener { loader.retryMore() }
                    row.addView(text, lp)
                }
                loader.loadingMore -> {
                    spinner.layoutParams = LinearLayout.LayoutParams(row.context.dp(22), row.context.dp(22))
                    row.addView(spinner, lp)
                }
                loader.endReached -> {
                    text.text = row.context.getString(R.string.paged_end)
                    row.addView(text, lp)
                }
                else -> {
                    // 待触发的尾位（占位行高，防最后一条贴底）
                    row.addView(text.apply { text = "" }, lp)
                }
            }
        }
    }
}
