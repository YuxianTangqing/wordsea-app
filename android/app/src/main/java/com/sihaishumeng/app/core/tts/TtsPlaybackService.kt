package com.sihaishumeng.app.core.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.sihaishumeng.app.MainActivity
import com.sihaishumeng.app.R

/**
 * 批⑨ 听书前台服务——**纯宿主**：前台通知（MediaStyle 三键：上一章/播放暂停/下一章）
 * +平台 MediaSession（耳机线控/MEDIA_BUTTON 自动映射）+音频焦点（让位来电/他 App
 * 播放）+PARTIAL WakeLock（兜块间合成间隙的 onDone 不被挂起）。
 *
 * 对抗审查 A1：状态机全在 TtsSessionManager（进程单例）——本服务零 Intent 通信，
 * 通知按钮经 PendingIntent.getForegroundService（豁免路径）回到 onStartCommand 转
 * manager.control()；控制面永不 startService。
 *
 * 生命周期铁律（A6）：onStartCommand **首行** ServiceCompat.startForeground（先占位
 * 通知再干活，防秒级窗口 RemoteServiceException）；START_NOT_STICKY（无上下文自拉起
 * 只会哑读）；onTaskRemoved（用户划任务）继续读=音乐 App 通例。
 */
class TtsPlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTI_ID = 248
        const val ACTION_PREV = "prev"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_NEXT = "next"
        const val ACTION_STOP = "stop"

        /** 手势窗内拉起（TtsSessionManager.start 直调——绝不在后台路径调它） */
        fun start(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    // ── 音频焦点：让位语义（听书宁停勿抢） ──
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                // 永久让位/来电：暂停（GAIN 回来不自动续——听书语义由用户决定）
                TtsSessionManager.control("""{"a":"pause"}""")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_LOW)
            )
        }
        audioManager = getSystemService(AudioManager::class.java)
        TtsSessionManager.attachService(this)
        requestFocus()
        acquireWakeLock()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⭐首行进前台（占位通知）——秒级窗口内必须 startForeground，否则系统崩
        ServiceCompat.startForeground(
            this, NOTI_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        when (intent?.action) {
            ACTION_PREV -> TtsSessionManager.control("""{"a":"prev"}""")
            ACTION_PAUSE -> TtsSessionManager.control("""{"a":"pause"}""")
            ACTION_RESUME -> TtsSessionManager.control("""{"a":"resume"}""")
            ACTION_NEXT -> TtsSessionManager.control("""{"a":"next"}""")
            ACTION_STOP -> TtsSessionManager.control("""{"a":"stop"}""")
        }
        onSessionChanged() // 占位→真状态
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 用户划掉任务卡：继续读（音乐 App 通例；会话由停止键/定时/书完收摊） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    /** manager 状态变化口：刷新通知+MediaSession（回放态/元数据/可用键位） */
    fun onSessionChanged() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTI_ID, buildNotification())
        syncMediaSession()
    }

    fun stopSelfSafe() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        TtsSessionManager.attachService(null)
        mediaSession?.release()
        mediaSession = null
        runCatching { focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) } }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ── 通知（MediaStyle 三键；按播放态与章边界换按钮集） ──

    private fun buildNotification(): Notification {
        val mgr = TtsSessionManager
        val playing = mgr.playing()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setSmallIcon(R.drawable.ic_tts_notification)
            .setContentTitle(mgr.bookTitleForUi())
            .setContentText(
                (if (mgr.chapterTitleForUi().isNotEmpty()) "「${mgr.chapterTitleForUi()}」" else "") +
                    if (playing) " · 正在朗读" else if (mgr.activeForUi()) " · 已暂停" else " · 启动中"
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (mgr.hasPrevForUi()) builder.addAction(0, "上一章", actionPending(ACTION_PREV, 1))
        builder.addAction(0, if (playing) "暂停" else "播放", actionPending(if (playing) ACTION_PAUSE else ACTION_RESUME, 2))
        if (mgr.hasNextForUi()) builder.addAction(0, "下一章", actionPending(ACTION_NEXT, 3))
        builder.addAction(0, "停止", actionPending(ACTION_STOP, 4))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        mediaSession?.let { style.setMediaSession(it.sessionToken) }
        builder.setStyle(style)
        return builder.build()
    }

    private fun actionPending(action: String, code: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, code,
            Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ── MediaSession（耳机线控/MEDIA_BUTTON 自动映射；锁屏媒体卡读活跃会话） ──

    private fun setupMediaSession() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        mediaSession = MediaSession(applicationContext, "ws-tts").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = TtsSessionManager.control("""{"a":"resume"}""")
                override fun onPause() = TtsSessionManager.control("""{"a":"pause"}""")
                override fun onSkipToNext() = TtsSessionManager.control("""{"a":"next"}""")
                override fun onSkipToPrevious() = TtsSessionManager.control("""{"a":"prev"}""")
                override fun onStop() = TtsSessionManager.control("""{"a":"stop"}""")
            })
            setPlaybackToLocal(attrs)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@TtsPlaybackService, 0,
                    Intent(this@TtsPlaybackService, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            isActive = true
        }
    }

    private fun syncMediaSession() {
        val ms = mediaSession ?: return
        val mgr = TtsSessionManager
        val playing = mgr.playing()
        var actions = android.media.session.PlaybackState.ACTION_PLAY or
            android.media.session.PlaybackState.ACTION_PAUSE or
            android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
            android.media.session.PlaybackState.ACTION_STOP
        if (mgr.hasPrevForUi()) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (mgr.hasNextForUi()) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
        ms.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) android.media.session.PlaybackState.STATE_PLAYING
                    else android.media.session.PlaybackState.STATE_PAUSED,
                    0, mgr.rateForUi()
                )
                .build()
        )
        ms.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, mgr.chapterTitleForUi())
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, mgr.bookTitleForUi())
                .build()
        )
    }

    // ── 音频焦点与 WakeLock ──

    private fun requestFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true) // 语音内容不可压低——系统改发瞬时丧失
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ws:tts-playback").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60_000L) // 上限 4h（一书连读帽；到期自然释放不崩）
        }
    }
}
