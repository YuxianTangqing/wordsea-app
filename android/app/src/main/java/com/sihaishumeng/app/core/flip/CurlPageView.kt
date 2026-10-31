package com.sihaishumeng.app.core.flip
import kotlin.math.cos
import kotlin.math.sin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View

/**
 * 批⑩ 仿真翻页盖层（起点/番茄式整页圆柱卷曲）。
 *
 * 架构=「透明洞」模型：盖层只画「翻动中的纸」，纸未覆盖处全透明——透出下方
 * WebView 实时内容（手势锁定瞬间 web 已无动画切页被本层盖住），卷曲逐渐露出的
 * 就是真实新页；动画终态（章内翻页）全透明=撤层零跳变。跨章（cross）终态例外：
 * hold 画 bg+纸边遮住下一章加载间隙，等 web 就绪声明再撤。
 *
 * 几何（M342 web 端 FlipCurlCanvas 同族的整页竖直折线特例）：圆柱半径 R 卷在屏上，
 * 折线 x=ℓ 处贴地；纸长守恒 ℓ+Rφ+free=W 由拖距解出；卷筒条带按 α 升序绘制
 * （z=R−R·cosα 单调增=后画高纸面盖低纸面，M342「遮挡序=纸面高度排序」教训
 * 直译）。α<π/2 画纸正面（位图对应段）、α≥π/2 画素色纸背（站主 M342 拍板素色）。
 * dir=-1 回翻=镜像几何（x 取反）+src 取镜像区间（内容不镜像）——素材同一张
 * 起手帧位图，无三槽预截。
 *
 * onTouchEvent 恒 false：点击穿透到 WebView（M214 动画期快进链依赖此）。
 */
class CurlPageView(context: Context) : View(context) {
    /** Float 版 π/2（条带正/背面分界） */
    private val HALF_PI = Math.PI.toFloat() / 2f


    /** 卷筒半径占页宽比（起点式细卷筒——批⑩R4 0.18→0.15 更像纸） */
    private val curlRadiusRatio = 0.15f
    /** 卷筒条带数（批⑩R4 16→24 卷曲面更顺） */
    private val stripCount = 24
    /** θ 达此值画全透明终态（章内=撤层前视效；THETA_CEIL=170 留 2° 余量） */
    private val thetaFull = 168f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint()
    private val backPaint = Paint()
    private val shadowPaint = Paint()
    private val camera = Camera()
    private val camMatrix = Matrix()

    // ── 会话绘制态（FlipController 驱动） ──
    var pageX = 0f
        private set
    var pageW = 0f
        private set
    private var pageH = 0f
    private var pageY = 0f
    private var bgColor = 0xFFF5F0E6.toInt()
    private var backColor = 0xFFE8E0D0.toInt()
    private var einkMode = false
    private var bitmap: Bitmap? = null

    /** 会话快照：controller 每帧调用 */
    internal var mode = Mode.HIDDEN
        private set
    internal var dir = 1
        private set
    internal var theta = 0f
        private set

    enum class Mode { HIDDEN, DRAG_PAGE, DRAG_EDGE, ANIM_PAGE, ANIM_EDGE, HOLD }

    /** pageRect=窗口物理坐标（controller.physRect 产出）；内部换算本 view 本地坐标 */
    fun configure(pageRect: RectF, bg: Int, dark: Boolean, eink: Boolean) {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        pageX = pageRect.left - loc[0]
        pageY = pageRect.top - loc[1]
        pageW = pageRect.width()
        pageH = pageRect.height()
        bgColor = bg
        einkMode = eink
        backColor = if (eink) bg else warmDark(bg, if (dark) 0.45f else 0.12f)
    }

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
    }

    fun show(mode: Mode, dir: Int) {
        this.mode = mode
        this.dir = dir
        visibility = VISIBLE
        invalidate()
    }

    fun update(mode: Mode, dir: Int, theta: Float) {
        this.mode = mode
        this.dir = dir
        this.theta = theta
        invalidate()
    }

    /** 撤层（GONE；位图由 controller 管理生命周期） */
    fun hide() {
        mode = Mode.HIDDEN
        visibility = GONE
        invalidate()
    }

    /** 跨章 hold 终态：整页 bg+右缘纸边（遮下一章加载间隙；章序号右侧=翻去方向） */
    private fun drawHoldFrame(canvas: Canvas) {
        canvas.drawRect(pageX, pageY, pageX + pageW, pageY + pageH, backPaint.apply { color = bgColor })
        if (!einkMode) {
            val edge = pageX + pageW
            shadowPaint.shader = LinearGradient(
                edge - pageW * 0.10f, 0f, edge, 0f,
                Color.TRANSPARENT, 0x33000000, Shader.TileMode.CLAMP
            )
            canvas.drawRect(edge - pageW * 0.10f, pageY, edge, pageY + pageH, shadowPaint)
            shadowPaint.shader = null
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.HIDDEN -> return
            Mode.HOLD -> { drawHoldFrame(canvas); return }
            Mode.DRAG_EDGE, Mode.ANIM_EDGE -> { drawEdge(canvas); return }
            else -> drawCurl(canvas)
        }
    }

    // ══ edge 模式：M170 章缘刚体小角度阻尼抬起（22° 封顶，2.4 次缓出由 controller 算 θ） ══

    private fun drawEdge(canvas: Canvas) {
        val bmp = bitmap ?: return
        val angle = -(theta / FlipDynamics.SOFT_EDGE_MAX_DEG) * FlipDynamics.SOFT_EDGE_MAX_DEG * dirSign()
        // 底页投影（抬起角越大越深）
        val (scale, opacity) = FlipDynamics.underShadow(theta)
        if (opacity > 0.01f && !einkMode) {
            val fromX = if (dir == 1) pageX + pageW - pageW * scale else pageX
            shadowPaint.shader = LinearGradient(
                fromX, 0f, fromX + pageW * scale, 0f,
                (opacity * 255).toInt().shl(24), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                minOf(fromX, fromX + pageW * scale), pageY,
                maxOf(fromX, fromX + pageW * scale), pageY + pageH, shadowPaint
            )
            shadowPaint.shader = null
        }
        // 刚体绕翻起缘竖直轴 rotateY（dir=1 绕左缘向内抬、dir=-1 镜像）
        camera.save()
        camera.rotateY(angle)
        camera.getMatrix(camMatrix)
        camera.restore()
        val pivotX = if (dir == 1) pageX else pageX + pageW
        camMatrix.preTranslate(-pivotX, -(pageY + pageH / 2f))
        camMatrix.postTranslate(pivotX, pageY + pageH / 2f)
        canvas.drawBitmap(bmp, camMatrix, paint)
    }

    private fun dirSign(): Float = if (dir == 1) 1f else -1f

    // ══ page 模式：整页圆柱卷曲（条带绘制） ══

    private fun drawCurl(canvas: Canvas) {
        val bmp = bitmap
        if (theta >= thetaFull) return // 全透明终态：透出 WebView 实时新页（撤层前视效）
        // 批⑩R4 即时亮相：位图未就绪（预拍 miss+软件拍在途）先画纸底色跟手——
        // 消灭「按住死一下」空窗；位图到位 startVisual 换真图
        if (bmp == null) {
            backPaint.color = bgColor
            canvas.drawRect(pageX, pageY, pageX + pageW, pageY + pageH, backPaint)
            return
        }
        if (theta <= 0.5f) { // 平铺原样（锁定瞬间零跳变）
            canvas.drawBitmap(bmp, pageX, pageY, paint)
            return
        }
        val w = pageW
        val r = w * curlRadiusRatio
        val p = (theta / FlipDynamics.THETA_CEIL).coerceIn(0f, 1f)
        val phi = p * Math.PI.toFloat() * 0.999f
        val fingerX = w * (1f - p)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val denom = 1f - cosPhi
        var ell = if (denom < 0.01f) 0f else
            (fingerX - w * cosPhi - r * sinPhi + r * phi * cosPhi) / denom
        ell = ell.coerceIn(0f, w)

        // ⭐批⑩R4 终态离屏过渡：θ 150→170 整纸向左滑出一页宽（20° 行程）——几何在
        // θ≈168 仍有 ~25% 平贴残段，直接跳全透明=闪变（站主「一闪就过去」之二）；
        // 连续滑出=与撤层零跳变。回弹/回拖反向同式连续。
        val offW = if (theta > 150f) (theta - 150f) / 20f * w else 0f
        if (offW > 0f) {
            canvas.save()
            canvas.translate(-offW * dirSign(), 0f)
        }

        // ── 1. 平贴残段（未翻部分，纸正面原位）：dst=src=[0, ell]（dir 镜像在 drawSeg 内换算） ──
        if (ell > 1f) {
            drawSeg(canvas, bmp, 0f, ell, 0f, ell, 1f)
        }

        // ── 2. 柱面条带（α 升序绘制=高纸面后画=正确遮挡） ──
        val xC = ell
        var prevX = xC + r * 0f
        var sAt = ell
        for (k in 1..stripCount) {
            val a1 = phi * k / stripCount
            val aMid = phi * (k - 0.5f) / stripCount
            val s1 = ell + r * a1
            if (s1 - sAt < 0.5f && k < stripCount) continue
            val xk = xC + r * sin(a1)
            val shade = shadeOf(aMid)
            if (aMid < HALF_PI && s1 <= w + 0.5f) {
                // 正面条带：位图对应段（纸长坐标 s）
                drawSeg(canvas, bmp, minOf(prevX, xk), maxOf(prevX, xk), sAt, minOf(s1, w), shade, true)
            } else {
                // 背面条带：素色纸背
                drawBack(canvas, minOf(prevX, xk), maxOf(prevX, xk), shade)
            }
            prevX = xk
            sAt = s1
            if (sAt >= w) break
        }

        // ── 3. 自由段（离开柱面的尾段） ──
        if (sAt < w - 0.5f) {
            val free = w - sAt
            val xEnd = prevX + free * cosPhi
            val shade = shadeOf(phi)
            if (phi < HALF_PI) {
                drawSeg(canvas, bmp, minOf(prevX, xEnd), maxOf(prevX, xEnd), sAt, w, shade, true)
            } else {
                drawBack(canvas, minOf(prevX, xEnd), maxOf(prevX, xEnd), shade)
            }
        }

        if (offW > 0f) canvas.restore()

        // ── 4. 底页投影（翻起纸落在屏上的阴影，透明洞侧；不随纸离屏平移——底页物） ──
        val (us, uo) = FlipDynamics.underShadow(theta)
        if (uo > 0.01f && !einkMode) {
            val fromX = if (dir == 1) pageX + ell else pageX + w - ell - w * us
            shadowPaint.shader = LinearGradient(
                fromX, 0f, fromX + w * us, 0f,
                (uo * 0.8f * 255).toInt().shl(24), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                minOf(fromX, fromX + w * us), pageY,
                maxOf(fromX, fromX + w * us), pageY + pageH, shadowPaint
            )
            shadowPaint.shader = null
        }
    }

    /** 条带明暗（卷筒暗带在 α≈π/2；批⑩R4 缓入曲线 sin²——硬 sin 起步陡=「粗糙」感来源） */
    private fun shadeOf(alpha: Float): Float {
        if (alpha < HALF_PI) {
            val s = sin(alpha)
            return 1f - FlipDynamics.SHADE_PEAK * s * s
        }
        return 1f - FlipDynamics.SHADE_PEAK * (1f - (alpha - HALF_PI) / HALF_PI) * 0.5f
    }

    /**
     * 画一段纸（dir 镜像换算：dst 与 src 的 x 都取镜像区间，内容不镜像）。
     * dstX0/dstX1=页内几何 x；srcS0/srcS1=纸长坐标 s（位图列）。
     */
    private fun drawSeg(
        canvas: Canvas, bmp: Bitmap,
        dstX0: Float, dstX1: Float, srcS0: Float, srcS1: Float,
        shade: Float, applyShade: Boolean = false,
    ) {
        if (dstX1 - dstX0 < 0.3f || srcS1 - srcS0 <= 0f) return
        val dx0: Float; val dx1: Float; val sx0: Float; val sx1: Float
        if (dir == 1) {
            dx0 = pageX + dstX0; dx1 = pageX + dstX1
            sx0 = srcS0 * bmp.width / pageW; sx1 = srcS1 * bmp.width / pageW
        } else {
            dx0 = pageX + pageW - dstX1; dx1 = pageX + pageW - dstX0
            sx0 = (pageW - srcS1) * bmp.width / pageW; sx1 = (pageW - srcS0) * bmp.width / pageW
        }
        val src = Rect(sx0.toInt(), 0, Math.min(bmp.width, Math.max(sx0.toInt() + 1, sx1.toInt())), bmp.height)
        val dstTop = if (dir == 1) pageY else pageY
        val dst = RectF(
            Math.min(dx0, dx1), dstTop, Math.max(dx0, dx1), dstTop + pageH
        )
        if (applyShade && shade < 0.999f && !einkMode) {
            val alpha = ((1f - shade) * 255).toInt().coerceIn(0, 200)
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(
                    shade, 0f, 0f, 0f, 0f,
                    0f, shade, 0f, 0f, 0f,
                    0f, 0f, shade, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            canvas.drawBitmap(bmp, src, dst, paint)
            paint.colorFilter = null
        } else {
            canvas.drawBitmap(bmp, src, dst, paint)
        }
    }

    /** 素色纸背段（bg 调暗暖偏移；eink=纯 bg） */
    private fun drawBack(canvas: Canvas, dstX0: Float, dstX1: Float, shade: Float) {
        if (dstX1 - dstX0 < 0.3f) return
        val dx0: Float; val dx1: Float
        if (dir == 1) { dx0 = pageX + dstX0; dx1 = pageX + dstX1 }
        else { dx0 = pageX + pageW - dstX1; dx1 = pageX + pageW - dstX0 }
        val c = if (einkMode) backColor else mix(backColor, 0xFF221100.toInt(), (1f - shade) * 0.25f)
        backPaint.color = c
        canvas.drawRect(Math.min(dx0, dx1), pageY, Math.max(dx0, dx1), pageY + pageH, backPaint)
    }

    private fun warmDark(color: Int, amount: Float): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return Color.rgb(
            (r * (1f - amount) + 0xE0 * amount * 0.3f).toInt().coerceIn(0, 255),
            (g * (1f - amount)).toInt().coerceIn(0, 255),
            (b * (1f - amount)).toInt().coerceIn(0, 255),
        )
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) * (1 - tt) + Color.red(b) * tt).toInt(),
            (Color.green(a) * (1 - tt) + Color.green(b) * tt).toInt(),
            (Color.blue(a) * (1 - tt) + Color.blue(b) * tt).toInt(),
        )
    }

    companion object {
        /** 帧驱动口（controller 每帧调 view.update 后 invalidate 由 update 自带） */
        fun choreographer(): Choreographer = Choreographer.getInstance()
    }
}
