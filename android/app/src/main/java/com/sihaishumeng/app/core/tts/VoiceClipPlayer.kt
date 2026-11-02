package com.sihaishumeng.app.core.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * M362 听书×段评联动：单条段评语音播放器。五十一批（2.0.19）换装 Media3 ExoPlayer——
 * MediaPlayer 解不了浏览器 MediaRecorder 产物 webm/opus（头部时长未知+ROM 抽取器差异；
 * 站主 2.0.18 真机实锤：段评列表 WebView 能播、听书四档全不播=解码层差异）。ExoPlayer
 * 自带 Matroska/Mp4 抽取器+统一 MediaCodec 管线（平台 c2.android.opus.decoder API21+
 * 强制），STATE_ENDED 走 EOS 不依赖头部时长。
 *
 * 设计约束（M362 压测+对抗审查定稿语义，换装后逐条保留）：
 * - 一切失败（IO/解析/解码/超时）走 onPlayerError→onError 静默跳过，持有方回落续读，
 *   绝不阻断朗读、绝不计入引擎 watchdogFails（语音健康≠TTS 引擎健康）。错误码
 *   Log.w 留证（IO/解析/解码三态各指不同层——五十一批换装本身即判定实验）。
 * - ⭐自包含线程安全（五十一批必修坑）：MediaPlayer 容忍跨线程、ExoPlayer 跨线程
 *   直接抛 IllegalStateException——构造与全部公开方法统一经自身 main Handler 收敛
 *   （TtsSessionManager.start 由桥直调跑 binder 线程=存量松动，本件不再依赖调用方
 *   线程纪律；main 队列 FIFO 保序=跨线程调用的先后关系不乱）。
 * - 双兜底照搬：prepare 10s 假死超时 + 完成看门狗（时长+15s——nginx 限速流断冻结
 *   防呆；时长缺失用 5 分钟全档兜底）。暂停挂起两只看门狗、恢复重挂——**暂停期
 *   不出声、不推进**。
 * - 暂停落在 prepare 窗口（M362「幽灵播放」审查修）：paused 旗标拦截 READY 的自动
 *   起播，保持「已就绪未起播」待 resumeX 接管——暂停承诺全域兑现。playWhenReady
 *   全程只在 beginPlay/resume 置 true；**绝不用 stop()**（ExoPlayer stop 重置 IDLE
 *   砸三态）。
 * - 一条评论一个实例（不池化不复用——暂停期严禁换 dataSource）；stopAndRelease 不
 *   触发任何回调（续体由调用方接管）；finish 对 ended/error/超时幂等（只结算一次）。
 */
class VoiceClipPlayer(
    context: Context,
    url: String,
    private val rate: Float,
    /** 五十四批 开头静音跳过（毫秒；0=不跳）。真跳过=READY 后起播前 seekTo——
     *  只在 WAV 上生效（WAV 头带精确时长、SeekMap 可寻，seek 100% 可靠；调用方对
     *  webm 恒传 0——MediaRecorder 产 webm 无时长头，站主真机实锤 2.0.20 的 seekTo
     *  让插播整个不播，webm 永不 seek）。失败静默按 0 播：跳过是增强项绝不毁播放链。 */
    private val skipMs: Long,
    private val onDone: () -> Unit,
    private val onError: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var done = false
    private var ready = false
    private var started = false
    private var paused = false
    private var capMs = 300_000L
    private val settle = Runnable { finish(error = true) }
    private val prepTimeout = Runnable { finish(error = true) }

    init {
        // 构造整体落 main（binder 线程构造不炸——ExoPlayer.Builder 要求 Looper 线程）；
        // stopAndRelease 先于初始化落地时 done 已置位（FIFO 保序），直接跳过
        main.post {
            if (done) return@post
            try {
                val p = ExoPlayer.Builder(context)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                            .build(),
                        /* handleAudioFocus= */ false, // 不与 TTS 引擎自身焦点互抢（保持 M362 无焦点语义）
                    )
                    .build()
                player = p
                p.setPlaybackParameters(PlaybackParameters(rate.coerceIn(0.5f, 3f)))
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            ready = true
                            main.removeCallbacks(prepTimeout)
                            if (!paused) beginPlay()
                            /* 暂停中保持「已就绪未起播」——resumeX 接管起播（幽灵播放审查修） */
                        } else if (playbackState == Player.STATE_ENDED) {
                            finish(error = false) // EOS 驱动——不依赖 webm 头部时长
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(
                            "VoiceClip",
                            "clip failed: code=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName}",
                            error
                        )
                        finish(error = true)
                    }
                })
                p.setMediaItem(MediaItem.fromUri(url))
                p.prepare() // playWhenReady 缺省 false：就绪不自动出声，起播统一走 beginPlay
                main.postDelayed(prepTimeout, 10_000L)
            } catch (e: Exception) {
                Log.w("VoiceClip", "construct failed", e)
                finish(error = true)
            }
        }
    }

    /** 完成看门狗（起播后由持有方安排；暂停挂起/恢复重挂全窗） */
    fun armCompletionWatchdog(ms: Long) {
        main.post {
            capMs = if (ms > 0) ms else 300_000L
            main.removeCallbacks(settle)
            main.postDelayed(settle, capMs + 15_000L)
        }
    }

    private fun beginPlay() {
        val p = player ?: return finish(error = true)
        try {
            // 五十四批 真跳过：WAV 上 seekTo（时长守卫：seek 点须留 ≥100ms 内容防「跳到
            // 末尾」空播；TIME_UNSET 不出现在 WAV——兜底分支恒真）。失败静默按 0 播，
            // 跳过绝不阻断朗读
            if (skipMs > 0) {
                try {
                    val d = p.duration
                    if (d == C.TIME_UNSET || skipMs < d - 100) p.seekTo(skipMs)
                } catch (_: Exception) {
                    /* 静默按 0 播 */
                }
            }
            p.play()
            started = true
        } catch (e: Exception) {
            finish(error = true)
        }
    }

    private fun finish(error: Boolean) {
        if (done) return
        done = true
        main.removeCallbacks(prepTimeout)
        main.removeCallbacks(settle)
        try {
            player?.release()
        } catch (_: Exception) {
            /* 已释放 */
        }
        player = null
        if (error) onError() else onDone()
    }

    /** 暂停（挂起两只看门狗——用户暂停期间不得超时误杀；未起播则记旗标待命。
     *  pause() 幂等：prepare 窗口本就 playWhenReady=false，no-op 无害） */
    fun pauseX() {
        main.post {
            paused = true
            main.removeCallbacks(settle)
            main.removeCallbacks(prepTimeout)
            try {
                player?.pause()
            } catch (_: Exception) {
                /* 状态异常交由上层续体兜底 */
            }
        }
    }

    /** 原位续播（三态：仍 prepare 中=重挂 prepare 超时；已就绪未起播=起播；已起播=续播） */
    fun resumeX() {
        main.post {
            paused = false
            when {
                !ready -> main.postDelayed(prepTimeout, 10_000L)
                !started -> {
                    beginPlay()
                    armCompletionWatchdog(capMs)
                }
                else -> {
                    armCompletionWatchdog(capMs)
                    try {
                        player?.play()
                    } catch (_: Exception) {
                        finish(error = true)
                    }
                }
            }
        }
    }

    /** 停止并放弃——不触发 onDone/onError（续体由调用方接管） */
    fun stopAndRelease() {
        main.post {
            if (done) return@post
            done = true
            main.removeCallbacks(prepTimeout)
            main.removeCallbacks(settle)
            try {
                player?.release()
            } catch (_: Exception) {
                /* 已释放 */
            }
            player = null
        }
    }
}
