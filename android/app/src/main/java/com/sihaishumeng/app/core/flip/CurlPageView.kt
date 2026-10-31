package com.sihaishumeng.app.core.flip
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 批⑩R6 仿真翻页盖层（经典 PageWidget 贝塞尔几何——Android 阅读器标准范式）。
 *
 * 形态：A=起翻角、F=手指点；折痕=过 AF 中点 G 的垂线，弧化为两段二次贝塞尔
 * （E→quad(D)→M→quad(I)→H，弧顶向 A 侧内凹=纸的弯曲感，凹深随 |AF| 变化）；
 * 折痕 A 侧=翻起区——**当前页位图沿折线反射镜像+暗化**（背面=镜像内容，旧
 * 条带卷筒的素色纸背随范式退役）；另一侧=未翻区（原样位图，靠绘制序天然分界
 * ——先画整页再画翻起区镜像盖住，全程只用 INTERSECTION 裁剪：硬件加速 Canvas
 * 不支持 Region.Op.DIFFERENCE）。双投影：底页投影=折痕弧 stroke 渐变（行程越
 * 深越浓）；背面暗化=透光衰减。
 *
 * 透明洞模型不变：盖层只画纸相关内容，未覆盖处透出 WebView 实时新页；动画终态
 * =F 出页+尾段 alpha 渐出→撤层零跳变。跨章 hold 同前。onTouchEvent 恒 false
 * （M214 快进链）。eink=无渐变无暗化。
 */
class CurlPageView(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint()
    private val shadowPaint = Paint()
    private val camera = Camera()
    private val camMatrix = Matrix()
    private val reflectMatrix = Matrix()
    private val foldPath = Path()
    private val liftPath = Path()
    private val pageRectF = RectF()

    // ── 会话绘制态（FlipController 驱动） ──
    var pageX = 0f
        private set
    var pageW = 0f
        private set
    private var pageH = 0f
    private var pageY = 0f
    private var bgColor = 0xFFF5F0E6.toInt()
    private var einkMode = false
    private var bitmap: Bitmap? = null

    /** 会话快照 */
    internal var mode = Mode.HIDDEN
        private set
    internal var dir = 1
        private set
    /** 贝塞尔输入：起翻角 A 与手指 F（页内局部坐标） */
    private var ax = 0f
    private var ay = 0f
    private var fx = 0f
    private var fy = 0f
    /** 终态渐出（动画尾段 F 出页后 alpha 收零=撤层零跳变） */
    private var fadeAlpha = 1f

    enum class Mode { HIDDEN, DRAG_PAGE, DRAG_EDGE, ANIM_PAGE, ANIM_EDGE, HOLD }

    fun configure(pageRect: RectF, bg: Int, dark: Boolean, eink: Boolean) {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        pageX = pageRect.left - loc[0]
        pageY = pageRect.top - loc[1]
        pageW = pageRect.width()
        pageH = pageRect.height()
        pageRectF.set(0f, 0f, pageW, pageH)
        bgColor = bg
        einkMode = eink
    }

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
    }

    fun show(mode: Mode, dir: Int) {
        this.mode = mode
        this.dir = dir
        fadeAlpha = 1f
        visibility = VISIBLE
        invalidate()
    }

    /** F 驱动更新（贝塞尔几何输入=A/F；θ 条带体系随卷筒退役） */
    fun updateF(mode: Mode, dir: Int, ax: Float, ay: Float, fx: Float, fy: Float, fade: Float = 1f) {
        this.mode = mode
        this.dir = dir
        this.ax = ax
        this.ay = ay
        this.fx = fx
        this.fy = fy
        this.fadeAlpha = fade
        invalidate()
    }

    fun hide() {
        mode = Mode.HIDDEN
        visibility = GONE
        invalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.HIDDEN -> return
            Mode.HOLD -> { drawHoldFrame(canvas); return }
            Mode.DRAG_EDGE, Mode.ANIM_EDGE -> { drawEdge(canvas); return }
            else -> drawFold(canvas)
        }
    }

    // ══ 跨章 hold 终态：整页 bg+右缘纸边 ══

    private fun drawHoldFrame(canvas: Canvas) {
        backPaint.color = bgColor
        canvas.drawRect(pageX, pageY, pageX + pageW, pageY + pageH, backPaint)
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

    // ══ edge 模式：M170 章缘刚体小角度阻尼（22° 封顶） ══

    private var edgeTheta = 0f

    fun updateEdge(mode: Mode, dir: Int, theta: Float) {
        this.mode = mode
        this.dir = dir
        this.edgeTheta = theta
        invalidate()
    }

    private fun drawEdge(canvas: Canvas) {
        val bmp = bitmap
        if (bmp == null) {
            backPaint.color = bgColor
            canvas.drawRect(pageX, pageY, pageX + pageW, pageY + pageH, backPaint)
            return
        }
        val angle = -edgeTheta * dirSign()
        val (scale, opacity) = FlipDynamics.underShadow(edgeTheta)
        if (opacity > 0.01f && !einkMode) {
            val fromX = if (dir == 1) pageX + pageW - pageW * scale else pageX
            shadowPaint.shader = LinearGradient(
                fromX, 0f, fromX + pageW * scale, 0f,
                (opacity * 255).toInt().shl(24), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                kotlin.math.min(fromX, fromX + pageW * scale), pageY,
                kotlin.math.max(fromX, fromX + pageW * scale), pageY + pageH, shadowPaint
            )
            shadowPaint.shader = null
        }
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

    // ══ page 模式：贝塞尔折页（批⑩R6 几何内核） ══

    /**
     * 折痕几何（页内局部坐标，(0,0)=页左上）：
     * - A=起翻角、F=手指（F 钳入页矩形）
     * - G=AF 中点；n=AF 垂线方向（指向远离 A 侧）
     * - 折痕直线（过 G 方向 n）与页边界交点 E/H（近竖直垂线交上下边，否则交左右边）
     * - 弧顶 M=G+(F−G)×0.30（向 A 侧收=弧内凹；凹深随行程变化=弯曲感）
     * - 控制点 D/I=E-M / M-H 中点向 A 侧偏 35%
     * - liftPath=折痕弧+沿边界经 A 角回 E 的闭合区（翻起区）
     */
    private fun computeFold(): FloatArray {
        val w = pageW
        val h = pageH
        val px = fx.coerceIn(0f, w)
        val py = fy.coerceIn(0f, h)
        val dx = px - ax
        val dy = py - ay
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(0.001f)
        val gx = (ax + px) / 2f
        val gy = (ay + py) / 2f
        var nx = -dy / len
        var ny = dx / len
        if (nx * (gx - ax) + ny * (gy - ay) > 0) { /* n 远离 A ✓ */ } else { nx = -nx; ny = -ny }
        val eps = 0.0001f
        val eX: Float; val eY: Float; val hX: Float; val hY: Float
        if (kotlin.math.abs(ny) > eps) {
            eX = (gx - (gy / ny) * nx).coerceIn(-w, 2 * w); eY = 0f
            hX = (gx + ((h - gy) / ny) * nx).coerceIn(-w, 2 * w); hY = h
        } else {
            eX = 0f; eY = (gy - (gx / nx) * ny).coerceIn(-h, 2 * h)
            hX = w; hY = (gy + ((w - gx) / nx) * ny).coerceIn(-h, 2 * h)
        }
        val sag = 0.30f
        val mx = gx + (px - gx) * sag
        val my = gy + (py - gy) * sag
        fun ctrl(ex: Float, ey: Float, mx0: Float, my0: Float): FloatArray {
            val cx = (ex + mx0) / 2f + (ax - (ex + mx0) / 2f) * 0.35f
            val cy = (ey + my0) / 2f + (ay - (ey + my0) / 2f) * 0.35f
            return floatArrayOf(cx, cy)
        }
        val d = ctrl(eX, eY, mx, my)
        val ii = ctrl(hX, hY, mx, my)
        val ext = 6f
        foldPath.reset()
        foldPath.moveTo(eX - nx * ext, eY - ny * ext)
        foldPath.quadTo(d[0], d[1], mx, my)
        foldPath.quadTo(ii[0], ii[1], hX + nx * ext, hY + ny * ext)
        // 翻起区：折痕弧 + 沿页边界经 A 角回到 E
        liftPath.reset()
        liftPath.moveTo(eX, eY)
        liftPath.quadTo(d[0], d[1], mx, my)
        liftPath.quadTo(ii[0], ii[1], hX, hY)
        if (hY >= h - 0.5f && eY <= 0.5f) {
            // E 在上边、H 在下边：经 A 角的竖边闭合
            if (ax > w / 2f) { liftPath.lineTo(w, h); liftPath.lineTo(w, 0f) }
            else { liftPath.lineTo(0f, h); liftPath.lineTo(0f, 0f) }
        } else if (hX >= w - 0.5f && eX <= 0.5f) {
            // E 在左边、H 在右边：经 A 角的横边闭合
            if (ay > h / 2f) { liftPath.lineTo(w, h); liftPath.lineTo(0f, h) }
            else { liftPath.lineTo(w, 0f); liftPath.lineTo(0f, 0f) }
        } else {
            liftPath.lineTo(ax, ay) // 退化（F 近对角线）：直连 A
        }
        liftPath.close()
        return floatArrayOf(gx, gy, nx, ny, len)
    }

    /** 2D 反射矩阵：沿过 (gx,gy)、方向单位向量 (nx,ny) 的直线镜像 */
    private fun reflectOverLine(gx: Float, gy: Float, nx: Float, ny: Float) {
        val a = nx * nx - ny * ny
        val b = 2f * nx * ny
        reflectMatrix.setValues(
            floatArrayOf(
                a, b, gx - a * gx - b * gy,
                b, -a, gy - b * gx + a * gy,
                0f, 0f, 1f,
            )
        )
    }

    private fun drawFold(canvas: Canvas) {
        val w = pageW
        val h = pageH
        if (w <= 0f || h <= 0f) return
        val geo = computeFold()
        val bmp = bitmap
        val px = fx.coerceIn(0f, w)
        val py = fy.coerceIn(0f, h)
        val diag = hypot(w.toDouble(), h.toDouble()).toFloat()
        val travel = (hypot((px - ax).toDouble(), (py - ay).toDouble()).toFloat() / diag).coerceIn(0f, 1f)

        canvas.save()
        canvas.translate(pageX, pageY)
        canvas.saveLayerAlpha(pageRectF, (fadeAlpha * 255).toInt().coerceIn(0, 255), Canvas.ALL_SAVE_FLAG)

        // ── 1. 整页原样（未翻区：翻起区将被镜像背面覆盖——硬件加速 Canvas 不支持
        //    Region.Op.DIFFERENCE，用绘制序天然分界） ──
        if (bmp != null) canvas.drawBitmap(bmp, null, pageRectF, paint)
        else { backPaint.color = bgColor; canvas.drawRect(pageRectF, backPaint) }

        // ── 2. 翻起区背面：位图沿折痕反射镜像 + 暗化（背面=镜像内容） ──
        if (bmp != null) {
            reflectOverLine(geo[0], geo[1], geo[2], geo[3])
            canvas.save()
            canvas.clipPath(liftPath)
            canvas.concat(reflectMatrix)
            canvas.drawBitmap(bmp, null, pageRectF, paint)
            canvas.restore()
            canvas.save()
            canvas.clipPath(liftPath)
            if (!einkMode) { backPaint.color = 0x52000000; canvas.drawRect(pageRectF, backPaint) }
            canvas.restore()
        } else {
            canvas.save()
            canvas.clipPath(liftPath)
            backPaint.color = if (einkMode) bgColor else dimColor(bgColor, 0.32f)
            canvas.drawRect(pageRectF, backPaint)
            canvas.restore()
        }

        // ── 3. 底页投影：折痕弧 stroke 渐变（A→F 方向，行程越深越浓） ──
        if (!einkMode && travel > 0.03f) {
            shadowPaint.shader = LinearGradient(
                ax, ay, px, py,
                Color.TRANSPARENT, (kotlin.math.min(0.38f, 0.12f + travel * 0.5f) * 255).toInt().shl(24),
                Shader.TileMode.CLAMP
            )
            shadowPaint.style = Paint.Style.STROKE
            shadowPaint.strokeWidth = w * 0.14f
            canvas.drawPath(foldPath, shadowPaint)
            shadowPaint.style = Paint.Style.FILL
            shadowPaint.shader = null
        }

        canvas.restore() // alpha layer
        canvas.restore()
    }

    private fun dimColor(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) * (1 - f)).toInt(),
        (Color.green(c) * (1 - f)).toInt(),
        (Color.blue(c) * (1 - f)).toInt(),
    )
}
