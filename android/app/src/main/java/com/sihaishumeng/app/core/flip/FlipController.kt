package com.sihaishumeng.app.core.flip

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.webkit.WebView
import com.sihaishumeng.app.core.tts.WebChannel
import org.json.JSONObject

/**
 * 批⑩ 仿真翻页会话状态机（唯一决策者）。
 *
 * 分工铁律：web 是决策大脑（翻页决策/跨章/进度/长尾），native 是效果器——本类只做
 * 手势→θ→绘制与四事件（settle/rollback/done/fail）回投，从不自作翻页决策。
 *
 * 会话状态：Idle / Dragging(page|edge) / Animating / Holding(跨章间隙)。
 * 位图=「一张起手帧」策略：PixelCopy 拍窗口当前帧（DOWN 投机），裁剪规范页框为页
 * 位图；「新页」=settle 事件让 web 无动画切页（被盖层盖住），卷曲露出的=WebView
 * 实时内容——html2canvas 三槽预截的老病根不存在。
 *
 * 物理换算=css px × webView.scale × density 现算（自适应捏合缩放；声明 cssRect
 * 不随 zoom 变，现算恒对）。双张复用位图（全窗+页），enabled=false/onDestroy 即
 * recycle。事件出口走 WebChannel.callGuarded（进程级弱引用，render 崩溃重建后
 * 仍可达）。
 */
class FlipController(
    private val activity: Activity,
    private val viewProvider: () -> CurlPageView?,
    private val webViewProvider: () -> WebView?,
) {

    // ── 声明缓存（web setFlipIntercept 最新值） ──
    data class Decl(
        val enabled: Boolean,
        val ready: Boolean,
        val edgePrev: Boolean,
        val edgeNext: Boolean,
        val first: Boolean,
        val last: Boolean,
        val cssRect: RectF,
        val bg: Int,
        val dark: Boolean,
        val eink: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private var decl: Decl? = null
    private var declEnabled = false

    // ── 位图（复用两张；尺寸变 recreate） ──
    private var windowBmp: Bitmap? = null
    private var pageBmp: Bitmap? = null
    private var windowW = 0
    private var windowH = 0
    private var captureToken = 0
    private var captureDeadline: Runnable? = null
    /** 最近一次成功拍照的 uptime——lock 时判「位图新鲜」（600ms 内=本手势 DOWN 投拍的帧） */
    private var captureFreshAt = 0L

    // ── 会话 ──
    private var session: Session? = null
    private var sessionSeq = 0

    private enum class Phase { DRAG, ANIM, REBOUND, HOLD }
    private enum class GMode { PAGE, EDGE }

    private inner class Session(
        val mode: GMode,
        val dir: Int,
        val seq: Int,
        val cross: Boolean,
    ) {
        var phase = Phase.DRAG
        var theta = 0f
        var settled = false
        var downX = 0f
        val samples = ArrayList<FlipDynamics.Sample>(16)
        var animFrom = 0f
        var animV0 = 0f
        var animTarget = 0f
        var animDur = 0f
        var animStart = 0L
        var reboundFrom = 0f
        var reboundStart = 0L
        var pendingBitmap = false
    }

    // ══ 声明 ══

    fun onDeclaration(json: String) {
        mainHandler.post { handleDeclaration(json) }
    }

    private fun handleDeclaration(json: String) {
        val d = try {
            val o = JSONObject(json)
            val r = o.optJSONObject("rect")
            Decl(
                enabled = o.optBoolean("enabled", false),
                ready = o.optBoolean("ready", false),
                edgePrev = o.optBoolean("edgePrev", false),
                edgeNext = o.optBoolean("edgeNext", false),
                first = o.optBoolean("first", false),
                last = o.optBoolean("last", false),
                cssRect = if (r != null) RectF(
                    r.optDouble("x", 0.0).toFloat(),
                    r.optDouble("y", 0.0).toFloat(),
                    r.optDouble("x", 0.0).toFloat() + r.optDouble("w", 0.0).toFloat(),
                    r.optDouble("y", 0.0).toFloat() + r.optDouble("h", 0.0).toFloat(),
                ) else RectF(),
                bg = try { Color.parseColor(o.optString("bg", "#F5F0E6")) } catch (_: Exception) { 0xFFF5F0E6.toInt() },
                dark = o.optBoolean("dark", false),
                eink = o.optBoolean("eink", false),
            )
        } catch (_: Exception) {
            null
        } ?: return
        val wasReady = decl?.ready ?: false
        decl = d
        declEnabled = d.enabled
        if (!d.enabled) {
            // 离开阅读器/换风格：在途会话全了结（enabled=false 只在组件卸载/风格切换时
            // 发，测量窗口抖动走 ready 字段；fastForward 对 HOLD 是 no-op，故直落 finish）
            val s = session
            if (s != null) finishSession(s, notifyDone = true) else hideLayer()
            return
        }
        // 跨章 hold 解除：ready false→true 翻转（新章首测完成）
        val s = session
        if (s != null && s.phase == Phase.HOLD && !wasReady && d.ready) {
            finishSession(s, notifyDone = true)
        }
    }

    /** 拦截器查询：声明 enabled&&ready 且无在途会话（动画期不拦——M295 惰性接管的
     *  v1 简化：动画 ~300ms 内新手势不响应拖拽，点区快进走 web→requestPageFlip 链） */
    fun interceptAllowed(): Boolean {
        val d = decl ?: return false
        return d.enabled && d.ready && session == null
    }

    // ══ 手势流（FlipInterceptorLayout 回调） ══

    fun onGestureDown() {
        startCapture()
    }

    fun onGestureLock(dir: Int, x: Float) {
        val d = decl ?: return
        if (!d.enabled || !d.ready) return
        // 快进在途会话（M295 惰性接管的简化：新手势=收束旧会话再起新）
        session?.let { if (it.phase != Phase.HOLD) fastForward(it) }
        val mode = when {
            dir == 1 && d.last -> if (d.edgeNext) GMode.EDGE else return // 真尽头=纹丝不动
            dir == -1 && d.first -> if (d.edgePrev) GMode.EDGE else return
            else -> GMode.PAGE
        }
        val s = Session(mode, dir, ++sessionSeq, cross = false)
        s.downX = x
        session = s
        val fresh = pageBmp != null && now() - captureFreshAt < 600f
        if (fresh) startVisual(s) else {
            // DOWN 投拍未归/失败/过期：等在途回调（onCaptureReady 起 startVisual），
            // 无在途则补拍——绝不用旧会话位图（会显示过期页帧）
            s.pendingBitmap = true
            if (captureToken <= 0) startCapture() // ≤0=无在途（0=从未拍/-1=已收束）
        }
    }

    fun onGestureMove(x: Float) {
        val s = session ?: return
        if (s.phase != Phase.DRAG) return
        val d = decl ?: return
        val w = physRect(d).width().coerceAtLeast(1f)
        s.samples.add(FlipDynamics.Sample(now(), x))
        if (s.samples.size > 16) s.samples.removeAt(0)
        s.theta = if (s.mode == GMode.PAGE) {
            FlipDynamics.clampTheta(Math.abs(x - s.downX) / w * FlipDynamics.THETA_CEIL)
        } else {
            FlipDynamics.softEdgeTheta(Math.abs(x - s.downX) / (w * FlipDynamics.SOFT_EDGE_FULL_W))
        }
        viewProvider()?.update(
            if (s.mode == GMode.PAGE) CurlPageView.Mode.DRAG_PAGE else CurlPageView.Mode.DRAG_EDGE,
            s.dir, s.theta,
        )
    }

    fun onGestureRelease(x: Float) {
        val s = session ?: return
        if (s.phase != Phase.DRAG) return
        val w = physRect(decl ?: return).width().coerceAtLeast(1f)
        val vx = FlipDynamics.releaseFlingVelocity(s.samples, 100f)
        val shouldFlip = if (s.mode == GMode.PAGE) {
            s.theta > FlipDynamics.RELEASE_THETA_DEG || vx > FlipDynamics.RELEASE_VX
        } else {
            s.theta >= FlipDynamics.SOFT_EDGE_TRIGGER_DEG || vx >= FlipDynamics.SOFT_EDGE_FLING_VX
        }
        if (shouldFlip) {
            if (!s.settled) {
                s.settled = true
                emit("settle", s.dir, s.seq)
            }
            s.cross = s.mode == GMode.EDGE
            animateTo(s, FlipDynamics.THETA_CEIL, vx)
        } else {
            rebound(s)
        }
    }

    fun onGestureCancel() {
        val s = session ?: return
        if (s.phase == Phase.DRAG) rebound(s) // 按「未完成」处理：page 模式回卷+rollback
        // 动画期 CANCEL（来电）：动画继续走完（不悬停）
    }

    // ══ 程序翻页（web 决策后 requestPageFlip） ══

    fun onProgramFlip(json: String) {
        mainHandler.post {
            val d = decl ?: return@post
            if (!d.enabled) return@post
            val o = try { JSONObject(json) } catch (_: Exception) { return@post }
            val dir = o.optInt("dir", 1)
            val cross = o.optBoolean("cross", false)
            val dur = o.optDouble("dur", 441.0).toFloat().coerceIn(60f, 600f)
            val webSeq = o.optInt("seq", 0)
            session?.let { if (it.phase != Phase.HOLD) fastForward(it) else finishSession(it, notifyDone = true) }
            val s = Session(GMode.PAGE, dir, if (webSeq != 0) webSeq else ++sessionSeq, cross)
            s.settled = true // settle 在 startVisual 发
            s.theta = 0f
            s.phase = Phase.ANIM
            s.animFrom = 0f; s.animV0 = 0f; s.animTarget = FlipDynamics.THETA_CEIL
            s.animDur = dur
            s.animStart = 0L // 哨兵：startVisual 起表（等位图就绪才算动画第 0 帧）
            session = s
            // 必须重拍（快进收束撤层的下一帧才是干净页面）——延一帧再拍
            s.pendingBitmap = true
            choreographer.postFrameCallback { startCapture() }
        }
    }

    // ══ 生命周期 ══

    fun onRenderGone() {
        mainHandler.post {
            session?.let { finishSession(it, notifyDone = false) }
            hideLayer()
            recycleBitmaps()
        }
    }

    fun destroy() {
        mainHandler.post {
            session = null
            hideLayer()
            recycleBitmaps()
        }
        captureDeadline?.let { mainHandler.removeCallbacks(it) }
    }

    // ══ 内部 ══

    private fun now(): Float = android.os.SystemClock.uptimeMillis().toFloat()

    /** cssRect → 窗口物理 px（css × webView.scale × density 现算 + getLocationInWindow
     *  含全父链偏移——getBoundingClientRect 相对视口（scroll 已扣），视口原点=WebView
     *  view 原点；CurlPageView.configure 再换算自身本地坐标） */
    private fun physRect(d: Decl): RectF {
        val wv = webViewProvider() ?: return RectF(d.cssRect)
        val density = activity.resources.displayMetrics.density
        val scale = wv.scale * density
        val rect = RectF(
            d.cssRect.left * scale, d.cssRect.top * scale,
            d.cssRect.right * scale, d.cssRect.bottom * scale,
        )
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        rect.offset(loc[0].toFloat(), loc[1].toFloat())
        return rect
    }

    private fun startVisual(s: Session) {
        val d = decl ?: return
        val view = viewProvider() ?: return
        view.configure(physRect(d), d.bg, d.dark, d.eink)
        view.setBitmap(pageBmp)
        if (!s.settled) {
            s.settled = true
            emit("settle", s.dir, s.seq)
        }
        when (s.phase) {
            Phase.DRAG -> view.show(
                if (s.mode == GMode.PAGE) CurlPageView.Mode.DRAG_PAGE else CurlPageView.Mode.DRAG_EDGE,
                s.dir,
            )
            Phase.ANIM -> {
                // program 会话（animStart 哨兵 0）：起表+入场帧
                if (s.animStart == 0L) {
                    s.animStart = android.os.SystemClock.uptimeMillis()
                    choreographer.postFrameCallback(animFrame)
                }
                view.show(animMode(s), s.dir)
            }
            else -> view.show(animMode(s), s.dir)
        }
    }

    private fun animateTo(s: Session, target: Float, vx: Float) {
        s.phase = Phase.ANIM
        s.animFrom = s.theta
        s.animV0 = Math.min(
            FlipDynamics.releaseV0(vx, physRect(decl ?: return).width().coerceAtLeast(1f)),
            2.2f * Math.abs(target - s.theta) / 300f,
        )
        s.animTarget = target
        s.animDur = FlipDynamics.flipDuration(vx, s.theta).coerceAtLeast(160f)
        s.animStart = android.os.SystemClock.uptimeMillis()
        viewProvider()?.show(animMode(s), s.dir)
        choreographer.postFrameCallback(animFrame)
    }

    private fun animMode(s: Session): CurlPageView.Mode =
        if (s.mode == GMode.PAGE) CurlPageView.Mode.ANIM_PAGE else CurlPageView.Mode.ANIM_EDGE

    private val animFrame = Choreographer.FrameCallback { frameTimeNanos ->
        val s = session ?: return@FrameCallback
        if (s.phase == Phase.ANIM) {
            val t = (frameTimeNanos / 1_000_000f) - s.animStart
            s.theta = FlipDynamics.hermiteTheta(s.animFrom, s.animV0, s.animTarget, s.animDur, t)
            viewProvider()?.update(animMode(s), s.dir, s.theta)
            if (t >= s.animDur) {
                s.theta = s.animTarget
                onAnimComplete(s)
            } else {
                choreographer.postFrameCallback(this.animFrame)
            }
        } else if (s.phase == Phase.REBOUND) {
            val t = (frameTimeNanos / 1_000_000f) - s.reboundStart
            val th = FlipDynamics.reboundTheta(s.reboundFrom, t)
            s.theta = th
            viewProvider()?.update(animMode(s), s.dir, Math.max(0f, th))
            val total = FlipDynamics.reboundDuration(s.reboundFrom)
            if (t >= total) {
                if (s.settled) emit("rollback", s.dir, s.seq) // page 模式回卷归零：web 复原
                finishSession(s, notifyDone = true)
            } else {
                choreographer.postFrameCallback(this.animFrame)
            }
        }
    }

    private fun onAnimComplete(s: Session) {
        if (s.cross) {
            s.phase = Phase.HOLD
            viewProvider()?.show(CurlPageView.Mode.HOLD, s.dir)
            mainHandler.postDelayed({
                if (session === s && s.phase == Phase.HOLD) {
                    // 4s 超时兜底（跨章失败/限速：网页自身 toast 兜底）
                    finishSession(s, notifyDone = true)
                }
            }, 4_000L)
        } else {
            finishSession(s, notifyDone = true)
        }
    }

    private fun rebound(s: Session) {
        s.phase = Phase.REBOUND
        s.reboundFrom = Math.max(0f, s.theta)
        s.reboundStart = android.os.SystemClock.uptimeMillis()
        choreographer.postFrameCallback(animFrame)
    }

    /** 快进在途会话：直跳终态（M214 语义；hold 会话不快进——由 ready/超时解除） */
    private fun fastForward(s: Session) {
        when (s.phase) {
            Phase.DRAG -> {
                // 未判阈的拖拽被新会话顶掉：按完成处理（settle 已发则 rollback 复原，否则直接了结）
                if (s.settled) emit("rollback", s.dir, s.seq)
                finishSession(s, notifyDone = true)
            }
            Phase.ANIM -> {
                s.theta = s.animTarget
                onAnimComplete(s)
            }
            Phase.REBOUND -> finishSession(s, notifyDone = true)
            Phase.HOLD -> { /* 不快进 hold */ }
        }
    }

    private fun finishSession(s: Session, notifyDone: Boolean) {
        if (session === s) session = null
        if (notifyDone) emit("done", 0, s.seq, withDir = false)
        hideLayer()
    }

    private fun hideLayer() {
        viewProvider()?.hide()
    }

    // ══ 拍照（PixelCopy window 版，API 26+） ══

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val wv = webViewProvider() ?: return
        val root = wv.rootView
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return
        var bmp = windowBmp
        if (bmp == null || windowW != w || windowH != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            windowBmp = bmp
            windowW = w
            windowH = h
        }
        val token = ++captureToken
        val deadline = Runnable {
            if (captureToken == token) {
                captureToken = -1
                onCaptureFailed()
            }
        }
        captureDeadline?.let { mainHandler.removeCallbacks(it) }
        captureDeadline = deadline
        mainHandler.postDelayed(deadline, 200L)
        try {
            PixelCopy.request(activity.window, bmp, { result ->
                mainHandler.post {
                    if (captureToken != token) return@post
                    captureDeadline?.let { mainHandler.removeCallbacks(it) }
                    captureToken = -1
                    if (result == PixelCopy.SUCCESS) onCaptureReady(bmp) else onCaptureFailed()
                }
            }, mainHandler)
        } catch (_: Exception) {
            captureDeadline?.let { mainHandler.removeCallbacks(it) }
            captureToken = -1
            onCaptureFailed()
        }
    }

    private fun onCaptureReady(window: Bitmap) {
        val d = decl ?: return
        val rect = physRect(d)
        val left = Math.max(0, rect.left.toInt())
        val top = Math.max(0, rect.top.toInt())
        val w = Math.min(window.width - left, rect.width().toInt()).coerceAtLeast(1)
        val h = Math.min(window.height - top, rect.height().toInt()).coerceAtLeast(1)
        if (left + w > window.width || top + h > window.height) return onCaptureFailed()
        var pb = pageBmp
        if (pb == null || pb.width != w || pb.height != h) {
            pb?.recycle()
            pb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pageBmp = pb
        }
        Canvas(pb).drawBitmap(window, Rect(left, top, left + w, top + h), RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
        captureFreshAt = android.os.SystemClock.uptimeMillis()
        val s = session
        if (s != null && s.pendingBitmap) {
            s.pendingBitmap = false
            startVisual(s)
        }
    }

    private fun onCaptureFailed() {
        val s = session
        if (s != null && s.pendingBitmap) {
            // 位图不可得：本会话放弃 + fail 事件（web 本实例停 native 回落 web 拖拽）
            if (s.settled) emit("rollback", s.dir, s.seq)
            finishSession(s, notifyDone = false)
            emit("fail", 0, s.seq, withDir = false, reason = "capture")
        }
    }

    private fun recycleBitmaps() {
        windowBmp?.recycle(); windowBmp = null
        pageBmp?.recycle(); pageBmp = null
        windowW = 0; windowH = 0
    }

    // ══ 事件出口（__wsPageFlip，批⑤R string 契约：quote 产出 JS 字符串字面量） ══

    private fun emit(kind: String, dir: Int, seq: Int, withDir: Boolean = true, reason: String? = null) {
        val o = JSONObject().put("kind", kind).put("seq", seq)
        if (withDir) o.put("dir", dir)
        if (reason != null) o.put("reason", reason)
        WebChannel.callGuarded("__wsPageFlip", JSONObject.quote(o.toString()))
    }
}
