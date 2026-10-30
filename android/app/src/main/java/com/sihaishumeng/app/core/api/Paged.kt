package com.sihaishumeng.app.core.api

/**
 * 批④ 分页加载器（站点列表惯例 {list,total,page,pageSize} + ?page= 1-based）。
 * 书库/板块/回帖/通知/搜索共用：持有已加载条目、滚动尾加载（≥500ms 节流防连滑打爆
 * 60/分/IP 限速）、首页错误态与尾页错误态分开（尾错=「重试」脚注，不清已载内容）。
 *
 * fetch 契约：page 从 1 起；cb(list,total,error)——list=null 且 error=true 表示本次
 * 失败（网络或业务错）；total 仅首页必回（后续页沿用首页值即可）。
 */
class PagedLoader<T>(
    val pageSize: Int,
    private val fetch: (page: Int, cb: (list: List<T>?, total: Int?, error: Boolean) -> Unit) -> Unit,
) {
    /** 已载条目（主线程读写——所有回调经 ApiClient 收口主线程） */
    val items = mutableListOf<T>()

    /** 首页是否成功过（false=尚无内容可显，页面态据此切加载/错误） */
    var hasData = false
        private set
    /** 首页加载/失败态（内容区遮罩用） */
    var firstLoading = false
        private set
    var firstError = false
        private set
    /** 尾部状态（Adapter 脚注：LOADING/END/RETRY） */
    var loadingMore = false
        private set
    var moreError = false
        private set

    private var total = -1
    private var nextPage = 1
    private var lastRequestAt = 0L
    private var listeners = mutableListOf<() -> Unit>()

    fun bind(onChanged: () -> Unit) {
        listeners.add(onChanged)
    }

    private fun notifyChanged() {
        @Suppress("UNSAFE_CALLS_ON_RIGHT_TYPE_OF_LAMBDA")
        listeners.forEach { it.invoke() }
    }

    val size: Int get() = items.size

    fun get(index: Int): T = items[index]

    /** 是否已到底（首页 total 已知且条目数达到） */
    val endReached: Boolean get() = total >= 0 && items.size >= total

    /** 刷新（重置拉第 1 页）。已在首载中则忽略（防与下拉刷新双发）。 */
    fun refresh(): Boolean {
        if (firstLoading) return false
        items.clear()
        total = -1
        nextPage = 1
        hasData = false
        firstError = false
        firstLoading = true
        moreError = false
        loadingMore = false
        notifyChanged()
        loadPage(1)
        return true
    }

    /** 滚动尾加载入口：RecyclerView onScrolled 里以最后可见位传入；内部含节流与终态闸。 */
    fun maybeLoadMore(lastVisiblePosition: Int): Boolean {
        if (lastVisiblePosition + 4 < items.size) return false
        return loadMore()
    }

    fun loadMore(): Boolean {
        if (firstLoading || loadingMore || !hasData || endReached || moreError) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRequestAt < 500) return false // 节流：防连滑高频触发打爆限速
        loadingMore = true
        moreError = false
        notifyChanged()
        loadPage(nextPage)
        return true
    }

    /** 尾部「点击重试」 */
    fun retryMore(): Boolean {
        moreError = false
        return loadMore()
    }

    private fun loadPage(page: Int) {
        lastRequestAt = android.os.SystemClock.elapsedRealtime()
        fetch(page) { list, totalBack, error ->
            if (error) {
                if (page == 1) {
                    firstLoading = false
                    firstError = true
                } else {
                    loadingMore = false
                    moreError = true
                }
            } else {
                val newItems = list ?: emptyList()
                if (totalBack != null && totalBack >= 0) total = totalBack
                items.addAll(newItems)
                nextPage = page + 1
                if (page == 1) {
                    firstLoading = false
                    firstError = false
                    hasData = true
                    // 首页空响应=空列表态（total=0 走 endReached）
                }
                loadingMore = false
                moreError = false
                // 空页兜底：服务端 total 失准时防死循环追加
                if (newItems.isEmpty()) total = items.size
            }
            notifyChanged()
        }
    }
}
