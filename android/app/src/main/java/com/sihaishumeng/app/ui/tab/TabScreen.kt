package com.sihaishumeng.app.ui.tab

import android.view.View
import com.sihaishumeng.app.MainActivity

/**
 * 批④ tab 屏基类（无 Fragment——纯 View 体系，View 树懒建后常驻复用：再次进入
 * 不重建，保滚动位置与已载数据；onShow/onHide 驱动「仅 SessionStore 失效或显式
 * 刷新才重拉」的省流策略）。
 */
abstract class TabScreen(protected val host: MainActivity) {

    private var built = false
    private var rootView: View? = null

    /** 懒建并取根视图（重复调用返回同一实例） */
    fun view(): View {
        if (!built) {
            rootView = build()
            built = true
        }
        return rootView!!
    }

    /** 构建屏根视图（只调一次） */
    protected abstract fun build(): View

    /** 每次切到本 tab（首建后） */
    open fun onShow() {}

    /** 切离本 tab */
    open fun onHide() {}
}
