package com.sihaishumeng.app.core.flip
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * 批⑩ 仿真翻页动力学（web 仓 src/lib/flip-dynamics.ts 直译——θ 判据/手感与网页端
 * 同源；纯函数无 Android 依赖，KMP 门保持）。
 *
 * θ 是全风格统一的「进度货币」（0=平铺、170=全翻过）；native 只承接 soft-curl 档
 * （起点/番茄式整页卷曲），FLIP_DYN 参数取 soft-curl 档值内置为常量。
 */
object FlipDynamics {
    /** Float 版 π（kotlin.math.PI 是 Double——全文 Float 语境单源） */
    private val PI_F = Math.PI.toFloat()


    /** θ 下限（回弹过冲安全垫，与 web THETA_FLOOR 同源） */
    const val THETA_FLOOR = -8f
    /** θ 上限（web MAX_THETA=170：留 10° 侧对无缝换页） */
    const val THETA_CEIL = 170f
    /** 松手补间时长下限（快甩） */
    const val FLIP_MIN_MS = 160f
    /** 松手补间时长上限（慢拖） */
    const val FLIP_MAX_MS = 380f
    /** 点区/程序翻页固定档（web TAP_FLIP_MS；soft-curl 系数 1.05 由调用方乘） */
    const val TAP_FLIP_MS = 420f
    /** 回弹平复段时长 */
    const val REBOUND_SETTLE_MS = 150f
    /** 松手翻页判阈：角度（度） */
    const val RELEASE_THETA_DEG = 38f
    /** 松手翻页判阈：尾窗速度（px/ms） */
    const val RELEASE_VX = 0.5f

    // ── soft-curl 档光影参数（web FLIP_DYN['soft-curl']，M338 提档值） ──
    const val SHADE_PEAK = 0.52f
    const val UNDER_PEAK = 0.58f
    const val UNDER_SCALE_BASE = 0.32f
    const val UNDER_SCALE_SPAN = 1f
    const val OVERSHOOT_MAX_DEG = 6f
    const val REBOUND_STRETCH = 1.1f

    // ── M170 章缘软弹性（edge 模式刚体抬起） ──
    const val SOFT_EDGE_MAX_DEG = 22f
    const val SOFT_EDGE_TRIGGER_DEG = 14f
    const val SOFT_EDGE_FLING_VX = 0.5f
    /** 满抬所需拖距（页宽比例） */
    const val SOFT_EDGE_FULL_W = 0.5f

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    /** θ 安全夹取（[THETA_FLOOR, THETA_CEIL]） */
    fun clampTheta(th: Float): Float = max(THETA_FLOOR, min(THETA_CEIL, th))

    /**
     * 「翻过去」三次 Hermite：θ(0)=from、θ'(0)=v0(deg/ms)、θ(dur)=target、θ'(dur)=0。
     * 位置与初速双连续（松手瞬间不跳变）；v0=0 精确退化 smoothstep。
     */
    fun hermiteTheta(from: Float, v0: Float, target: Float, dur: Float, t: Float): Float {
        val d = max(1f, dur)
        val x = min(max(t, 0f), d)
        val delta = target - from
        val a = (v0 * d - 2f * delta) / (d * d * d)
        val b = (3f * delta - 2f * v0 * d) / (d * d)
        val theta = from + v0 * x + b * x * x + a * x * x * x
        return clampTheta(theta)
    }

    /** 回弹总时长（随 θ0；soft-curl reboundStretch 拉长=绵感） */
    fun reboundDuration(theta0: Float): Float =
        (110f + 0.95f * theta0 + REBOUND_SETTLE_MS) * REBOUND_STRETCH

    /**
     * 回弹两段式（自然过冲）：θ0 → −OV（easeOutQuart 冲过 0）→ 0（smoothstep 平复）。
     * OV = min(过冲上限, 0.16×θ0)；soft-curl 档 6° 封顶。
     */
    fun reboundTheta(theta0: Float, t: Float): Float {
        val ov = min(OVERSHOOT_MAX_DEG, 0.16f * theta0)
        val dA = 110f + 0.95f * theta0
        if (t <= 0f) return theta0
        if (t < dA) {
            val p = t / dA
            val e = 1f - Math.pow((1f - p).toDouble(), 4.0).toFloat()
            return theta0 + (-ov - theta0) * e
        }
        val p = min(1f, (t - dA) / REBOUND_SETTLE_MS)
        val e = p * p * (3f - 2f * p)
        return -ov * (1f - e)
    }

    /** 松手速度感应时长（快甩 160 / 慢拖 380，soft-curl 系数 1.05 内置） */
    fun flipDuration(vx: Float, theta: Float): Float {
        val speed = clamp01(vx / 1.2f)
        val dist = clamp01(theta / THETA_CEIL)
        // 批⑩R4：卷页系数 1.05→1.5（240-570ms 绵感——rotateY 轻感的时长对卷曲观感偏短）
        val dur = (FLIP_MIN_MS + (1f - speed) * (1f - 0.35f * dist) * (FLIP_MAX_MS - FLIP_MIN_MS)) * 1.5f
        return max(60f, round(dur).toFloat())
    }

    /** 光影安全值：sin(θπ/180)，负角恒 0（opacity 永不为负） */
    fun glowOf(theta: Float): Float {
        if (theta <= 0f) return 0f
        return sin((min(theta, THETA_CEIL) / 180f) * PI_F).toFloat()
    }

    /** 底层纸页投影参数（θ → scaleX + opacity；soft-curl 档） */
    fun underShadow(theta: Float): Pair<Float, Float> {
        val s = glowOf(theta)
        return (UNDER_SCALE_BASE + UNDER_SCALE_SPAN * s) to UNDER_PEAK * s
    }

    /** 松手初速（deg/ms）：vx(px/ms) × 180/W */
    fun releaseV0(vx: Float, pageWidth: Float): Float = (vx * 180f) / max(1f, pageWidth)

    /** 章缘阻尼曲线：p（拖距/满抬拖距 0..1）→ 抬起角（0..22°，2.4 次缓出） */
    fun softEdgeTheta(p: Float): Float {
        val c = min(1f, max(0f, p))
        return SOFT_EDGE_MAX_DEG * (1f - Math.pow((1f - c).toDouble(), 2.4).toFloat())
    }

    /**
     * M295 松手尾窗平均速度（px/ms）：触屏抬指前自然减速/零位移末事件致瞬时速度恒≈0
     * 的根治——取尾窗 100ms 内首末两点 |Δpos|/Δt。样本 <2 或窗内跨度 ≤0 → fallback。
     */
    fun releaseFlingVelocity(samples: List<Sample>, windowMs: Float = 100f, fallbackVx: Float = 0f): Float {
        if (samples.size < 2) return fallbackVx
        val last = samples[samples.size - 1]
        var firstIdx = samples.size - 1
        while (firstIdx > 0 && last.t - samples[firstIdx - 1].t <= windowMs) firstIdx--
        val first = samples[firstIdx]
        val dt = last.t - first.t
        if (dt <= 0f) return fallbackVx
        return Math.abs(last.pos - first.pos) / dt
    }

    /** 速度采样点（t=毫秒钟，pos=主轴坐标 px） */
    data class Sample(val t: Float, val pos: Float)
}
