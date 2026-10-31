package com.sihaishumeng.app.core.flip

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * 批⑩ 仿真翻页手势容器（包 WebView，代码创建挂 SwipeRefreshLayout 内——activity_main.xml
 * 零改动，SwipeRefreshLayout mTarget=本容器单子不变）。
 *
 * 拦截模式=ViewPager 经典：DOWN 放行（WebView 正常收到，web 侧 onDown 因 native 接管态
 * 早退无拖拽态）；MOVE 横向主导超 touchSlop 才 onInterceptTouchEvent=true 抢占（WebView
 * 收 ACTION_CANCEL 落空），随后手势流进本层 onTouchEvent 转发 controller。竖向主导=本
 * 手势放弃（滚动/下拉刷新归 WebView/SwipeRefreshLayout）；拦截瞬间
 * requestDisallowInterceptTouchEvent 防 SwipeRefreshLayout 抢斜拉。
 *
 * 水平意图锁 |dx|·1.2 ≥ |dy| 与 web PagedReader2 soft-curl 锁同款（对角拖成立、纯竖滑
 * 出局）。模式判定（page/edge/rubber）在 controller——读声明缓存纯本地，零桥往返。
 */
class FlipInterceptorLayout(
    context: Context,
    private val controller: FlipController,
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracking = false
    private var activePointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var gaveUp = false
    /** 批⑩R5：DOWN 落点在角部捏起热区（轻拖任意方向 6px 即锁） */
    private var cornerHot = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 批⑩R 手势链总防护：任何 FlipController/拍照异常绝不崩 APP（吞掉+日志——
        // 拦截层一炸=主线程死；功能面由 web 侧 fail 事件降级兜底）
        return try {
            interceptTouch(ev)
        } catch (e: Exception) {
            android.util.Log.e("FlipBridge", "onInterceptTouchEvent", e)
            tracking = false
            false
        }
    }

    private fun interceptTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = false
                gaveUp = false
                activePointerId = ev.getPointerId(0)
                downX = ev.x
                downY = ev.y
                // 批⑩R5 角部捏起：落点在角热区=记标记（MOVE 段降阈锁）
                cornerHot = controller.inCornerHot(ev.x, ev.y)
                // 投机拍照（enabled&&ready 才发起；手势未成立即弃）
                if (controller.interceptAllowed()) controller.onGestureDown()
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking || gaveUp || !controller.interceptAllowed()) return false
                val idx = ev.findPointerIndex(activePointerId)
                if (idx < 0) return false
                val dx = ev.getX(idx) - downX
                val dy = ev.getY(idx) - downY
                // 批⑩R5：角热区=任意方向 6px 即锁（捏页角）；常规=横向主导超 slop
                if (cornerHot) {
                    if (Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() > 6f) {
                        tracking = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // 角位定方向（起点语义：捏右角=翻下一页/左角=上一页，拖向不反转）
                        controller.setCornerHint(cornerHot, downY)
                        controller.onGestureLock(if (downX > width / 2f) 1 else -1, downX)
                        return true
                    }
                    return false
                }
                if (Math.abs(dx) > touchSlop && Math.abs(dx) * 1.2f >= Math.abs(dy)) {
                    tracking = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    controller.onGestureLock(if (dx < 0f) 1 else -1, ev.getX(idx))
                    return true
                }
                if (Math.abs(dy) > touchSlop) gaveUp = true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (tracking) controller.onGestureCancel()
                tracking = false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!tracking) return false
        return try {
            handleTouch(ev)
        } catch (e: Exception) {
            android.util.Log.e("FlipBridge", "onTouchEvent", e)
            tracking = false
            parent?.requestDisallowInterceptTouchEvent(false)
            true
        }
    }

    private fun handleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val idx = ev.findPointerIndex(activePointerId)
                if (idx >= 0) controller.onGestureMove(ev.getX(idx), ev.getY(idx))
            }
            MotionEvent.ACTION_UP -> {
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val idx = ev.findPointerIndex(activePointerId)
                if (idx >= 0) controller.onGestureRelease(ev.getX(idx))
                else controller.onGestureCancel()
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                controller.onGestureCancel()
            }
        }
        return true
    }
}
