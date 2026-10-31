package com.sihaishumeng.app.core.flip
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
        /** web 声明的 devicePixelRatio（CSS px→物理 px 的权威换算——批⑩R4 弃
         *  native scale×density 猜测：webView.scale 语义在缩放/宽视口下有出入） */
        val dpr: Float,
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
    /** 页代际：每次会话了结/声明 ready 翻转递增——预拍位图的有效域（当前页没变=恒有效） */
    private var pageGen = 0L
    /** 预拍位图的代际（==pageGen=预拍仍对应当前页，lock 直接用零延迟） */
    private var preheatGen = -1L

    // ── 会话 ──
    private var session: Session? = null
    private var sessionSeq = 0

    private enum class Phase { DRAG, ANIM, REBOUND, HOLD }
    private enum class GMode { PAGE, EDGE }

    private inner class Session(
        val mode: GMode,
        val dir: Int,
        val seq: Int,
        var cross: Boolean,
        /** 批⑩R5：角捏起手（A=所按角） */
        val corner: Boolean = false,
    ) {
        var phase = Phase.DRAG
        var theta = 0f
        // ⭐批⑩R6：贝塞尔 F 驱动（page 模式 θ 退役）：A=起翻角/F=手指（页内局部
        // 坐标）；动画对「行程比例 t」插值（0=贴角 1=对角 1.45=出页）
        var ax = 0f; var ay = 0f
        var fx = 0f; var fy = 0f
        var animTFrom = 1f
        var animTTarget = 1f
        var settled = false
        var downX = 0f
        var downY = 0f
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
                dpr = o.optDouble("density", 1.0).toFloat().coerceIn(0.1f, 6f),
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
        // ⭐批⑩R4 预拍：新页就绪（首测/重测完成）后台备好下一手势的起手帧——
        // ROM PixelCopy 慢/失败走软件回退 50-350ms，起手现拍=「按住死一下再跳」
        // （站主「一闪就过去」主因）；预拍让手势起手零延迟真跟手。
        if (d.ready) schedulePreheat()
    }

    /** 预拍（300ms 让位渲染稳定；同代重复调度幂等——removeCallbacks 后重挂） */
    private fun schedulePreheat() {
        mainHandler.removeCallbacks(preheatRun)
        mainHandler.postDelayed(preheatRun, 300L)
    }
    private val preheatRun = Runnable {
        if (session == null && decl?.enabled == true && decl?.ready == true) {
            startCapture()
        }
    }

    /** 批⑩R5 角部捏起热区（起点/番茄式：按住左/右下角轻拖即起卷）：落点在纸页
     *  角部 22%×22% 区域=true——拦截器对角热区降锁阈值（6px 任意方向即锁） */
    fun inCornerHot(x: Float, y: Float): Boolean {
        val d = decl ?: return false
        if (!d.enabled) return false
        val r = physRect(d)
        val cw = r.width() * 0.22f
        val ch = r.height() * 0.22f
        val inX = x < r.left + cw || x > r.right - cw
        val inY = y > r.bottom - ch * 2f || y < r.top + ch * 2f // 上下角都收（站主点名左上/右上）
        return inX && inY
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

    /** 批⑩R5：角捏起手标记+DOWN 纵坐标（拦截器在 lock 前传入；move 的综合行程用） */
    fun setCornerHint(corner: Boolean, downY: Float) {
        cornerGesture = corner
        downYLast = downY
    }
    @Volatile private var cornerGesture = false
    @Volatile private var downYLast = 0f
    @Volatile private var lastMoveY = 0f

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
        val s = Session(mode, dir, ++sessionSeq, cross = false, corner = cornerGesture)
        s.downX = x
        s.downY = downYLast
        session = s
        // 批⑩R6：A=起翻角（页内局部坐标）。角捏=DOWN 落点所在角；横拖=起手侧
        // 下角（dir=1 右下/dir=-1 左下——经典 PageWidget 语义）；F 初值=A
        if (mode == GMode.PAGE) {
            val r = physRect(d)
            s.ax = when {
                s.corner -> if (x - r.left < r.width() / 2f) 0f else r.width()
                dir == 1 -> r.width()
                else -> 0f
            }
            s.ay = if (s.corner) {
                if (downYLast - r.top < r.height() / 2f) 0f else r.height()
            } else r.height()
            s.fx = (x - r.left).coerceIn(0f, r.width())
            s.fy = (downYLast - r.top).coerceIn(0f, r.height())
        }
        // 批⑩R4：预拍代际命中=零延迟直起（起手即跟手）；miss=即时亮相（纸底色）
        // +后台拍（到位换真图，绝不显示过期页帧）
        val fresh = pageBmp != null && preheatGen == pageGen
        if (fresh) startVisual(s) else {
            s.pendingBitmap = true
            startVisualPlaceholder(s)
            if (captureToken <= 0) startCapture() // ≤0=无在途（0=从未拍/-1=已收束）
        }
    }

    fun onGestureMove(x: Float, y: Float) {
        lastMoveY = y
        val s = session ?: return
        if (s.phase != Phase.DRAG) return
        val d = decl ?: return
        val w = physRect(d).width().coerceAtLeast(1f)
        s.samples.add(FlipDynamics.Sample(now(), x))
        if (s.samples.size > 16) s.samples.removeAt(0)
        if (s.mode == GMode.PAGE) {
            // 批⑩R6：F 驱动——手指即 F（页内局部坐标），贝塞尔几何逐帧重算
            val r = physRect(d)
            s.fx = (x - r.left).coerceIn(0f, r.width())
            s.fy = (y - r.top).coerceIn(0f, r.height())
            viewProvider()?.updateF(CurlPageView.Mode.DRAG_PAGE, s.dir, s.ax, s.ay, s.fx, s.fy)
        } else {
            s.theta = FlipDynamics.softEdgeTheta(abs(x - s.downX) / (w * FlipDynamics.SOFT_EDGE_FULL_W))
            viewProvider()?.updateEdge(CurlPageView.Mode.DRAG_EDGE, s.dir, s.theta)
        }
    }

    fun onGestureRelease(x: Float) {
        val s = session ?: return
        if (s.phase != Phase.DRAG) return
        val w = physRect(decl ?: return).width().coerceAtLeast(1f)
        val vx = FlipDynamics.releaseFlingVelocity(s.samples, 100f)
        if (s.mode == GMode.PAGE) {
            // 批⑩R6：F 判阈=行程比例（|AF|/对角线>0.18 或快甩）
            val travel = currentTravel(s)
            if (travel > 0.18f || vx > FlipDynamics.RELEASE_VX) {
                if (!s.settled) { s.settled = true; emit("settle", s.dir, s.seq) }
                s.cross = false
                animateF(s, 1.45f, vx) // 出页
            } else {
                reboundF(s)
            }
            return
        }
        if (s.theta >= FlipDynamics.SOFT_EDGE_TRIGGER_DEG || vx >= FlipDynamics.SOFT_EDGE_FLING_VX) {
            if (!s.settled) { s.settled = true; emit("settle", s.dir, s.seq) }
            s.cross = true
            animateTo(s, FlipDynamics.THETA_CEIL, vx)
        } else {
            rebound(s)
        }
    }

    /** 批⑩R6：F 动画（t=行程比例；hermite 对 t 插值；t>1.0 后 fade 渐出=撤层零跳变） */
    private fun animateF(s: Session, target: Float, vx: Float) {
        s.phase = Phase.ANIM
        s.animTFrom = currentTravel(s)
        s.animTTarget = target
        s.animV0 = vx
        s.animDur = FlipDynamics.flipDuration(vx, s.animTFrom * FlipDynamics.THETA_CEIL)
            .coerceIn(200f, 650f)
        s.animStart = android.os.SystemClock.uptimeMillis()
        viewProvider()?.show(CurlPageView.Mode.ANIM_PAGE, s.dir)
        choreographer.postFrameCallback(animFrameF)
    }

    private fun reboundF(s: Session) {
        s.phase = Phase.REBOUND
        s.animTFrom = currentTravel(s)
        s.animTTarget = 0f
        s.animDur = FlipDynamics.reboundDuration(s.animTFrom * FlipDynamics.THETA_CEIL)
        s.animStart = android.os.SystemClock.uptimeMillis()
        choreographer.postFrameCallback(animFrameF)
    }

    private fun currentTravel(s: Session): Float {
        val d = decl ?: return 0f
        val r = physRect(d)
        val diag = hypot(r.width().toDouble(), r.height().toDouble()).toFloat().coerceAtLeast(1f)
        return hypot((s.fx - s.ax).toDouble(), (s.fy - s.ay).toDouble()).toFloat() / diag
    }

    /** F 动画帧：t 插值 → F 沿 A→对角方向推进 */
    private val animFrameF: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val s = session ?: return@FrameCallback
        if (s.phase != Phase.ANIM && s.phase != Phase.REBOUND) return@FrameCallback
        val elapsed = (frameTimeNanos / 1_000_000f - s.animStart).coerceIn(0f, s.animDur)
        val t = FlipDynamics.hermiteTheta(s.animTFrom, 0f, s.animTTarget, s.animDur, elapsed)
        val d = decl ?: return@FrameCallback
        val r = physRect(d)
        val w = r.width(); val h = r.height()
        val diag = hypot(w.toDouble(), h.toDouble()).toFloat().coerceAtLeast(1f)
        val cx = if (s.ax > w / 2f) 0f else w // A 的对角
        val cy = if (s.ay > h / 2f) 0f else h
        val ux = (cx - s.ax) / diag; val uy = (cy - s.ay) / diag
        s.fx = s.ax + ux * t * diag
        s.fy = s.ay + uy * t * diag
        val fade = if (t > 1.0f) (1f - (t - 1.0f) / 0.45f).coerceIn(0f, 1f) else 1f
        viewProvider()?.updateF(CurlPageView.Mode.ANIM_PAGE, s.dir, s.ax, s.ay, s.fx, s.fy, fade)
        if (elapsed >= s.animDur) {
            if (s.phase == Phase.ANIM) {
                if (s.cross) {
                    s.phase = Phase.HOLD
                    viewProvider()?.show(CurlPageView.Mode.HOLD, s.dir)
                    mainHandler.postDelayed({
                        if (session === s && s.phase == Phase.HOLD) finishSession(s, notifyDone = true)
                    }, 4_000L)
                } else finishSession(s, notifyDone = true)
            } else {
                if (s.settled) emit("rollback", s.dir, s.seq)
                finishSession(s, notifyDone = true)
            }
        } else {
            choreographer.postFrameCallback(this.animFrameF)
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
            s.phase = Phase.ANIM
            // 批⑩R6：程序翻页=F 动画 0→1.45（A=起手侧下角）
            val r = physRect(d)
            s.ax = if (dir == 1) r.width() else 0f
            s.ay = r.height()
            s.fx = s.ax; s.fy = s.ay
            s.animTFrom = 0f; s.animTTarget = 1.45f
            s.animDur = dur.coerceIn(200f, 650f)
            s.animStart = 0L // 哨兵：startVisual 起表
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
        // 批⑩R4：换算权威化——web 声明的 devicePixelRatio（浏览器自证 CSS→物理），
        // 不再用 native wv.scale×density（缩放/宽视口语义有出入）
        val scale = d.dpr
        val rect = RectF(
            d.cssRect.left * scale, d.cssRect.top * scale,
            d.cssRect.right * scale, d.cssRect.bottom * scale,
        )
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        rect.offset(loc[0].toFloat(), loc[1].toFloat())
        return rect
    }

    /** 即时亮相：位图未就绪时先亮纸底色（θ 阴影在；位图到了 startVisual 换真图）——
     *  消灭「按住死一下」的空窗（站主「一闪就过去」主因之二） */
    private fun startVisualPlaceholder(s: Session) {
        val d = decl ?: return
        val view = viewProvider() ?: return
        view.configure(physRect(d), d.bg, d.dark, d.eink)
        view.setBitmap(null)
        if (!s.settled) {
            s.settled = true
            emit("settle", s.dir, s.seq)
        }
        view.show(
            if (s.mode == GMode.PAGE) CurlPageView.Mode.DRAG_PAGE else CurlPageView.Mode.DRAG_EDGE,
            s.dir,
        )
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
                // program 会话（animStart 哨兵 0）：起表+入场帧（page=贝塞尔 F 帧；edge=θ 帧）
                if (s.animStart == 0L) {
                    s.animStart = android.os.SystemClock.uptimeMillis()
                    if (s.mode == GMode.PAGE) choreographer.postFrameCallback(animFrameF)
                    else choreographer.postFrameCallback(animFrame)
                }
                if (s.mode == GMode.PAGE) view.show(CurlPageView.Mode.ANIM_PAGE, s.dir)
                else view.show(animMode(s), s.dir)
            }
            else -> view.show(animMode(s), s.dir)
        }
    }

    private fun animateTo(s: Session, target: Float, vx: Float) {
        s.phase = Phase.ANIM
        s.animFrom = s.theta
        s.animV0 = min(
            FlipDynamics.releaseV0(vx, physRect(decl ?: return).width().coerceAtLeast(1f)),
            2.2f * abs(target - s.theta) / 300f,
        )
        s.animTarget = target
        s.animDur = FlipDynamics.flipDuration(vx, s.theta).coerceAtLeast(160f)
        s.animStart = android.os.SystemClock.uptimeMillis()
        viewProvider()?.show(animMode(s), s.dir)
        choreographer.postFrameCallback(animFrame)
    }

    private fun animMode(s: Session): CurlPageView.Mode =
        if (s.mode == GMode.PAGE) CurlPageView.Mode.ANIM_PAGE else CurlPageView.Mode.ANIM_EDGE

    private val animFrame: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val s = session ?: return@FrameCallback
        if (s.phase == Phase.ANIM) {
            val t = (frameTimeNanos / 1_000_000f) - s.animStart
            s.theta = FlipDynamics.hermiteTheta(s.animFrom, s.animV0, s.animTarget, s.animDur, t)
            viewProvider()?.updateEdge(animMode(s), s.dir, s.theta)
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
            viewProvider()?.updateEdge(animMode(s), s.dir, max(0f, th))
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
        s.reboundFrom = max(0f, s.theta)
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
                if (s.mode == GMode.PAGE) finishSession(s, notifyDone = true) // F 动画直跳出页
                else { s.theta = s.animTarget; onAnimComplete(s) }
            }
            Phase.REBOUND -> finishSession(s, notifyDone = true)
            Phase.HOLD -> { /* 不快进 hold */ }
        }
    }

    private fun finishSession(s: Session, notifyDone: Boolean) {
        if (session === s) session = null
        if (notifyDone) emit("done", 0, s.seq, withDir = false)
        hideLayer()
        // 批⑩R4：翻页了结=页已变，旧预拍作废；新页渲染稳定后后台备下一张
        pageGen++
        schedulePreheat()
    }

    private fun hideLayer() {
        viewProvider()?.hide()
    }

    // ══ 拍照（PixelCopy window 版，API 26+；失败回退软件绘制——批⑩R 站主 ROM 实录
    //    PixelCopy 可能失败/超时，软件 draw 慢 50-150ms 但保功能，比永远降级强） ══

    private fun ensureWindowBmp(w: Int, h: Int): Bitmap? {
        var bmp = windowBmp
        if (bmp == null || windowW != w || windowH != h) {
            bmp?.recycle()
            bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return null
            }
            windowBmp = bmp
            windowW = w
            windowH = h
        }
        return bmp
    }

    private fun startCapture() {
        // ⭐批⑩R4：全走软件绘制（PixelCopy 在站主 ROM 上 SUCCESS 但帧错位=裁剪区
        // 比纸页小→拉伸=「文字突然放大」；软件 rootView.draw 所见即所得零语义风险，
        // 慢 50-150ms 已被预拍移出关键路径——起手走预拍缓存/即时亮相）
        softwareCapture()
    }

    /** 软件绘制拍照：rootView.draw(canvas)（Chromium WebView 支持软件 canvas 输出；
     *  帧与 getLocationInWindow 同坐标语义=裁剪精确） */
    private fun softwareCapture() {
        try {
            val wv = webViewProvider() ?: return onCaptureFailed()
            val root = wv.rootView
            if (root.width <= 0 || root.height <= 0) return onCaptureFailed()
            val bmp = ensureWindowBmp(root.width, root.height) ?: return onCaptureFailed()
            captureToken = -1
            captureDeadline?.let { mainHandler.removeCallbacks(it) }
            root.draw(Canvas(bmp))
            onCaptureReady(bmp)
        } catch (e: Exception) {
            android.util.Log.e("FlipBridge", "softwareCapture", e)
            onCaptureFailed()
        }
    }

    private fun onCaptureReady(window: Bitmap) {
        val d = decl ?: return
        val rect = physRect(d)
        val left = max(0, rect.left.toInt())
        val top = max(0, rect.top.toInt())
        val w = min(window.width - left, rect.width().toInt()).coerceAtLeast(1)
        val h = min(window.height - top, rect.height().toInt()).coerceAtLeast(1)
        if (left + w > window.width || top + h > window.height) return onCaptureFailed()
        var pb = pageBmp
        if (pb == null || pb.width != w || pb.height != h) {
            pb?.recycle()
            pb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pageBmp = pb
        }
        Canvas(pb).drawBitmap(window, Rect(left, top, left + w, top + h), RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
        preheatGen = pageGen
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
