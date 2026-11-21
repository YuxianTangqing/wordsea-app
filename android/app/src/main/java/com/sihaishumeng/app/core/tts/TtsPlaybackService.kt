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
 * 通知按钮经 PendingIntent.getService（M377：通知只在服务存活期存在，普通拉起即可
 * 送达；getForegroundService 每点一次武装 fgRequired 定时器=OPPO ColorOS 冻结拖窗
 * 必崩的雷，2.0.26 首份崩溃上报实锤）回到 onStartCommand 转 manager.control()；
 * 控制面永不 startService。
 *
 * 生命周期铁律（A6）：onStartCommand **首行** ServiceCompat.startForeground（先占位
 * 通知再干活，防秒级窗口 RemoteServiceException）；M377 起 onCreate 建通道后也立即
 * 进前台（ARGS 投递被 ColorOS 冻结/主线程忙拖迟时，CREATE 时刻已满足窗口）；M378
 * 起会话（MediaSession+PlaybackState）先于首个张贴、compact 下标按键数自适应（
 * 2.0.27 vivo/API36 崩溃=2 键窗口硬编码下标 2 越界+无 token 张贴）；
 * START_NOT_STICKY（无上下文自拉起只会哑读）；onTaskRemoved（用户划任务）继续读=音乐 App 通例。
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
        // M378：会话与回放态先于首个前台通知就位——MediaStyle 带 token+PlaybackState
        // 张贴（Android 13+ 对会话不活跃的 MediaStyle 用 minimal 模板重渲染，compact
        // 区最多 1 键、下标越界同样经 RemoteServiceException 崩——vivo/API36 上报的
        // 第二触发面；此前 setupMediaSession 在张贴之后=无 token 张贴）。
        setupMediaSession()
        syncMediaSession()
        // M377：onCreate 即进前台（占位通知）——onStartCommand 首行调用的兜底：ARGS 投递
        // 被 OPPO ColorOS 冻结/主线程忙拖过窗口时，CREATE 时刻已满足 fgRequired，超时崩
        // （RemoteServiceException）不再可能；onStartCommand 的再次调用=幂等刷新真状态。
        ServiceCompat.startForeground(
            this, NOTI_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        audioManager = getSystemService(AudioManager::class.java)
        TtsSessionManager.attachService(this)
        requestFocus()
        acquireWakeLock()
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
        // M378：无条件收摊——原门 intent?.action != null 拦不住「取消先于服务创建」
        // 路径（起播后立刻暂停时 service 尚未创建、preparing 取消分支的 stopSelfSafe
        // 空转，服务随后 null-action 照常拉起=state idle 的僵尸通知永不自清）。
        // null-action 起播路径恒 active（start() 先置 preparing 再拉服务，binder IPC
        // 保序——类头注释已论证），无副作用。
        if (!TtsSessionManager.active()) stopSelfSafe()
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
        val preparing = mgr.preparingForUi() // M378：起播窗口内按钮=暂停（取消启动）
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
                    when {
                        playing -> " · 正在朗读"
                        preparing -> " · 启动中" // M378：preparing 优先（原走 activeForUi 显示「已暂停」与按钮矛盾）
                        mgr.activeForUi() -> " · 已暂停"
                        else -> " · 启动中"
                    }
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // M378：先收集动作数再取 compact 下标——键数是条件渲染的（上/下章仅章位有效时
        // 加；tocPos==-1 的 preparing/收摊窗只有 2 键），硬编码 (0,1,2) 在 2 键时下标
        // 越界=Android 15/16 严格校验下 RemoteServiceException 崩（2.0.27 vivo/API36
        // 上报「setShowActionsInCompactView: action 2 out of bounds (max 1)」实锤）。
        // M381：action 图标此前恒 0=按键点击区在但不可见（站主「点了有效果就是看不到」）
        // ——四键补 vector 图标（ic_notif_*；暂停/播放随态换图标，与 title 分支同条件）。
        var actionCount = 0
        if (mgr.hasPrevForUi()) {
            builder.addAction(R.drawable.ic_notif_prev, "上一章", actionPending(ACTION_PREV, 1))
            actionCount++
        }
        builder.addAction(
            if (playing || preparing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
            if (playing || preparing) "暂停" else "播放",
            actionPending(if (playing || preparing) ACTION_PAUSE else ACTION_RESUME, 2)
        )
        actionCount++
        if (mgr.hasNextForUi()) {
            builder.addAction(R.drawable.ic_notif_next, "下一章", actionPending(ACTION_NEXT, 3))
            actionCount++
        }
        builder.addAction(R.drawable.ic_notif_stop, "停止", actionPending(ACTION_STOP, 4))
        actionCount++
        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(*IntArray(minOf(3, actionCount)) { it })
        mediaSession?.let { style.setMediaSession(it.sessionToken) }
        builder.setStyle(style)
        return builder.build()
    }

    private fun actionPending(action: String, code: Int): PendingIntent {
        // M377：通知四键改 getService——通知只在服务存活期存在（唯一张贴点=本服务的
        // startForeground/onSessionChanged），普通拉起即可送达。getForegroundService
        // 每点一次都武装系统 ~10s fgRequired 定时器（服务必须窗口内 startForeground
        // 否则 RemoteServiceException 主线程崩）——OPPO ColorOS 11 息屏冻结/后台启动
        // 限制拖过窗口=2.0.26 首份崩溃上报实锤（Android 11/OPPO，深夜点暂停）。
        // 通知点按属系统豁免路径，8+/12+ 后台 startService 均合法。getService 全
        // API 存在——M376 的 API26 守卫随此退役（安卓 7.x 一并根治，无越级调用）。
        val intent = Intent(this, TtsPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

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
