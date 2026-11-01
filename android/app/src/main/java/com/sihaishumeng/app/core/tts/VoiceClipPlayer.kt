package com.sihaishumeng.app.core.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * M362 听书×段评联动：单条段评语音播放器（MediaPlayer 封装）。
 *
 * 设计约束（设计期压测+对抗审查修定稿）：
 * - webm/opus 的 MediaPlayer 设备解码兼容性不保证——一切失败（prepare/decode/超时）
 *   走 onError 静默跳过，持有方回落续读，绝不阻断朗读、绝不计入引擎 watchdogFails
 *   （语音健康≠TTS 引擎健康）。
 * - 主线程串行（会话状态机同一线序列；MediaPlayer 回调随创建线程 Looper 落主线程）；
 *   rate=会话语速快照（变速失败回落 1.0）。
 * - 双兜底：prepare 10s 假死超时 + 完成看门狗（时长+15s——nginx 限速流断冻结防呆；
 *   时长缺失用 5 分钟全档兜底）。暂停挂起两只看门狗（审查修：原版只挂完成看门狗，
 *   prepare 窗口内暂停会被 10s 超时误杀）、恢复时重挂——**暂停期不出声、不推进**。
 * - 暂停落在 prepare 窗口（审查修「幽灵播放」）：paused 标志拦截 onPrepared 的自动
 *   start，保持「已准备未起播」待 resumeX 接管——暂停承诺全域兑现。
 * - stopAndRelease 不触发任何回调（续体由调用方接管）；finish 对
 *   completion/error/超时幂等（只结算一次）。
 */
class VoiceClipPlayer(
    url: String,
    private val rate: Float,
    private val onDone: () -> Unit,
    private val onError: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var done = false
    private var prepared = false
    private var started = false
    private var paused = false
    private var capMs = 300_000L
    private val settle = Runnable { finish(error = true) }
    private val prepTimeout = Runnable { finish(error = true) }

    init {
        try {
            val m = MediaPlayer()
            mp = m
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            m.setDataSource(url)
            m.setOnPreparedListener {
                prepared = true
                main.removeCallbacks(prepTimeout)
                if (!paused) beginPlay()
                /* 暂停中保持「已准备未起播」——resumeX 接管起播（审查修幽灵播放） */
            }
            m.setOnCompletionListener { finish(error = false) }
            m.setOnErrorListener { _, _, _ ->
                finish(error = true)
                true
            }
            m.prepareAsync()
            main.postDelayed(prepTimeout, 10_000L)
        } catch (_: Exception) {
            finish(error = true)
        }
    }

    /** 完成看门狗（起播后由持有方安排；暂停挂起/恢复重挂全窗） */
    fun armCompletionWatchdog(ms: Long) {
        capMs = if (ms > 0) ms else 300_000L
        main.removeCallbacks(settle)
        main.postDelayed(settle, capMs + 15_000L)
    }

    private fun beginPlay() {
        try {
            mp?.playbackParams = mp?.playbackParams?.setSpeed(rate) ?: return finish(error = true)
            mp?.start()
            started = true
        } catch (_: Exception) {
            finish(error = true)
        }
    }

    private fun finish(error: Boolean) {
        if (done) return
        done = true
        main.removeCallbacks(prepTimeout)
        main.removeCallbacks(settle)
        try {
            mp?.release()
        } catch (_: Exception) {
            /* 已释放 */
        }
        mp = null
        if (error) onError() else onDone()
    }

    /** 暂停（挂起两只看门狗——用户暂停期间不得超时误杀；未起播则记旗标待命） */
    fun pauseX() {
        paused = true
        main.removeCallbacks(settle)
        main.removeCallbacks(prepTimeout)
        try {
            if (mp?.isPlaying == true) mp?.pause()
        } catch (_: Exception) {
            /* 状态异常交由上层续体兜底 */
        }
    }

    /** 原位续播（三态：仍 prepare 中=重挂 prepare 超时；已准备未起播=起播；已起播=续播） */
    fun resumeX() {
        paused = false
        when {
            !prepared -> main.postDelayed(prepTimeout, 10_000L)
            !started -> {
                beginPlay()
                armCompletionWatchdog(capMs)
            }
            else -> {
                armCompletionWatchdog(capMs)
                try {
                    mp?.start()
                } catch (_: Exception) {
                    finish(error = true)
                }
            }
        }
    }

    /** 停止并放弃——不触发 onDone/onError（续体由调用方接管） */
    fun stopAndRelease() {
        if (done) return
        done = true
        main.removeCallbacks(prepTimeout)
        main.removeCallbacks(settle)
        try {
            mp?.stop()
        } catch (_: Exception) {
            /* 未prepared 等态 */
        }
        try {
            mp?.release()
        } catch (_: Exception) {
            /* 已释放 */
        }
        mp = null
    }
}
