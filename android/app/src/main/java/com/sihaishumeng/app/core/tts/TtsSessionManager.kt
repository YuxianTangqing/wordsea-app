package com.sihaishumeng.app.core.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.sihaishumeng.app.core.api.ApiClient
import com.sihaishumeng.app.core.SiteConfig
import com.sihaishumeng.app.core.tts.TtsEngineHolder as Engine
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 批⑨ 原生听书会话状态机（进程单例；前台服务只是它的「通知栏+耳机+焦点」宿主——
 * 对抗审查 A1：控制面全部直接调本对象，零 Service Intent 通信，杜绝后台
 * startService 崩溃面）。
 *
 * 职责闭环：拉章（segments?ct=，消费服务端剥好的 data.tts 纯文本——零 Markdown
 * 解析）→切块（splitChunks 逐字复刻 web tts.ts：句读「。！？；\n」、cut>limit*0.5
 * 否则硬切、limit=300）→逐块朗读→章尽按 **toc 数组位置**连读（禁 orderIndex±1
 * 算术——web 侧同红线）→进度心跳（15s，PUT /api/v1/me/progress，锚+活跃秒——
 * 会话期间 web 侧 Tracker 静默，本机是唯一写者）→token 临期 rotate（持旧换新，
 * POST /me/reading-token）→预取下一章（读度过半）→看门狗（块超时重 init 引擎
 * 自当前块续读，连败 2 次收摊）→事件经 WebChannel 注 window.__wsTtsSession。
 *
 * M362 听书×段评联动：advance 拆「块推进 advance() + 段推进 moveToNextPara()」，
 * 段边界按四档（off/before/after/replace）用 VoiceClipPlayer 插播该段最热语音
 * （tts[].va={k,d}；失败静默跳过/replace 回落读文字）；语音期 curUtterId=null+
 * 作废看门狗票=引擎对账免疫；暂停恢复/停机跳段/auth 五路全接语音急停或原位暂停。
 *
 * 全状态机跑主线程（Handler 串行）；引擎就绪后拉起 TtsPlaybackService（前台
 * 通知+MediaSession+音频焦点——通知栏/耳机三键经 Service 回调 control()）。
 */
object TtsSessionManager {

    private val main = Handler(Looper.getMainLooper())

    /** M362 联动四档（与 web READER_TTS_PARA_VOICE 同源） */
    private val PARA_VOICE_MODES = setOf("off", "before", "after", "replace")

    // ── 会话态（全部主线程读写） ──
    private var state = "idle" // idle | preparing | playing | paused
    private var appContext: Context? = null
    private var novelId = ""
    private var bookTitle = ""
    private var rate = 1f
    private var sleepTimer = "off" // off | chapter | 分钟数字串
    private var sleepDeadline = 0L // 分钟档到期时刻（ms）
    private var toc: List<Int> = emptyList()
    private var tocPos = -1
    private var startOrderIndex = -1 // 「本章完」档的「本章」=起播章（web 逐块同语义）
    private var contentToken = ""

    // M362 听书×段评联动：off|before|after|replace（缺省 off=dormant 家法——旧页面×
    // 新 APK 不自作主张替换正文朗读；用户可感默认档由 web 起播显式下发承担）
    private var paraVoice = "off"

    // 当前章数据
    private class Para(
        val text: String,
        val hash: String,
        val skip: Boolean,
        val voiceKey: String? = null, // M362 该段最热语音（segments tts[].va.k；旧服务端无 va=null）
        val voiceMs: Long = 0L
    )
    private var chapterTitle = ""
    private var paras: List<Para> = emptyList()
    private var paraPos = 0
    private var chunks: List<String> = emptyList()
    private var chunkPos = 0
    private var nextChapterCache: Pair<Int, Pair<String, List<Para>>>? = null // orderIndex to (title, paras)
    private var fetchingChapter = false

    // 引擎对账与看门狗
    private var curUtterId: String? = null
    private var seq = 0
    private var watchdogRunnables = 0
    private var watchdogFails = 0
    private var speakAt = 0L

    // M362 段评语音插播态
    private var voice: VoiceClipPlayer? = null
    private var voiceStage = "text" // text | voice（resume 分流：语音中原位续播而非重读文字）
    private var voiceTicket = 0 // 代际计数：停机/跳段作废迟到回调（stopAndRelease 之外的双保险）
    private var voicePlayedPara = -1 // after 档防双播（段号；beginChapter 重置）

    // 心跳
    private var heartbeatSeq = 0
    private var activeSince = 0L // playing 累计秒基准（pause 时结算）
    private var pendingSeconds = 0

    /** Service 宿主回执（状态变化刷新通知/MediaSession；弱引用防泄漏） */
    private var service: TtsPlaybackService? = null

    /** 引擎块事件口（id 对账+state 门；会话空闲期事件零副作用）——声明先于下方
     *  init 注册（Kotlin 属性与 init 块按声明顺序执行） */
    private val utterance: (String, String) -> Unit = { kind: String, id: String ->
        if (curUtterId != null && id == curUtterId && state == "playing") {
            when (kind) {
                "done" -> {
                    watchdogFails = 0
                    advance()
                }
                "error" -> watchdogRecover()
                // "stop"=自家 stop 触发（pause/jump），状态机已处理——忽略
            }
        }
    }

    init {
        // 块事件自注册（object 单例挂一次）
        Engine.addUtteranceListener(utterance)
    }

    fun attachService(s: TtsPlaybackService?) {
        service = s
    }

    fun playing(): Boolean = state == "playing"
    fun active(): Boolean = state != "idle"

    // Service 通知/MediaSession 读数口（首尾章自动裁剪 SKIP 键位）
    fun bookTitleForUi() = bookTitle
    fun chapterTitleForUi() = chapterTitle
    fun activeForUi() = active()
    fun hasPrevForUi() = tocPos > 0
    fun hasNextForUi() = tocPos >= 0 && tocPos < toc.size - 1
    fun rateForUi() = rate

    // ══════════ 启动与控制（桥直调口） ══════════

    /** 桥 ttsStartSession：options={novelId,bookTitle,rate,sleepTimer,voiceId?,orderIndex,
     *  paraIndex,ct,toc:[orderIndex 数字]}。false=参数坏（web 回落逐块）。引擎初始化
     *  异步——起播成败经事件回告（no-voice/error）。 */
    fun start(context: Context, optionsJson: String): Boolean {
        val o = try {
            JSONObject(optionsJson)
        } catch (_: Exception) {
            return false
        }
        val novel = o.optString("novelId")
        val token = o.optString("ct")
        val tocArr = o.optJSONArray("toc")
        if (novel.isEmpty() || token.isEmpty() || tocArr == null || tocArr.length() == 0) return false
        appContext = context.applicationContext
        novelId = novel
        bookTitle = o.optString("bookTitle")
        rate = o.optDouble("rate", 1.0).toFloat()
        sleepTimer = o.optString("sleepTimer", "off")
        // M362 联动四档白名单（缺省 off；脏值回落 off）
        val pv = o.optString("paraVoice", "off")
        paraVoice = if (pv in PARA_VOICE_MODES) pv else "off"
        toc = (0 until tocArr.length()).map { tocArr.optInt(it) }
        contentToken = token
        startOrderIndex = o.optInt("orderIndex", toc.first())
        state = "preparing"
        stopVoicePlayback() // ⭐审查修：会话重入（「朗读本段」重起播）先止旧语音——防旧评论外放+迟到回调污染新会话
        resetChapterRuntime()
        val voiceId = o.optString("voiceId")
        if (voiceId.isNotEmpty()) Engine.setVoice(voiceId)
        // ⭐前台服务必须在「用户手势时刻」拉起（本方法由桥 ttsStartSession 直调=App
        // 在前台窗内）：引擎初始化要 1-3s，若等 ensure 回调再拉、用户恰好按 Home，
        // Android 12+ 后台 startForegroundService 直接抛
        // ForegroundServiceStartNotAllowedException（对抗审查 A1/A6）。通知先占位
        // 「正在启动语音」，引擎就绪后由 onSessionChanged 刷新真状态。
        TtsPlaybackService.start(context)
        Engine.ensure(appContext!!) { ok, reason ->
            if (!ok) {
                emit("no-voice") { put("message", reason) }
                state = "idle"
                service?.onSessionChanged()
                service?.stopSelfSafe()
                return@ensure
            }
            openChapter(startOrderIndex, o.optInt("paraIndex", 0))
        }
        return true
    }

    /** 桥/通知栏/耳机统一控制口（actionJson={a,v}）。
     *  ⭐M362 审查修：@JavascriptInterface 跑 binder 线程——收敛主线程执行再动状态机
     *  （批⑨ 起本口被桥直调属存量松动，M362 语音外设操作（MediaPlayer/pauseX）使其
     *  成为必修的正确性问题；通知栏/耳机路径本就在主线程，post 幂等无害）。 */
    fun control(actionJson: String) {
        main.post { controlOnMain(actionJson) }
    }

    private fun controlOnMain(actionJson: String) {
        val a = try {
            JSONObject(actionJson)
        } catch (_: Exception) {
            return
        }
        when (a.optString("a")) {
            "pause" -> pause()
            "resume" -> resume()
            "stop" -> stopSession("user")
            "setRate" -> rate = a.optDouble("v", 1.0).toFloat()
            "setSleep" -> applySleep(a.optString("v", "off"))
            "setParaVoice" -> applyParaVoice(a.optString("v", "off")) // M362 联动档热更
            "next" -> jumpChapter(1)
            "prev" -> jumpChapter(-1)
        }
    }

    /** M362 联动档热更：off 立即止当前评论续读；其余下一 para 边界自然生效（半程不改形态） */
    private fun applyParaVoice(v: String) {
        if (v !in PARA_VOICE_MODES) return
        paraVoice = v
        if (v == "off" && voiceStage == "voice") {
            stopVoicePlayback()
            if (state == "playing") {
                // 审查修：off 止播即刻补发 para（清 web「段评♪」徽标+高亮复位当前段，幂等）
                emit("para") {
                    put("orderIndex", toc[tocPos])
                    put("paraIndex", paraPos)
                    put("chapterTitle", chapterTitle)
                }
                if (chunkPos < chunks.size) speakCurrentChunk() else moveToNextPara()
            }
        }
    }

    /** 桥 ttsSessionState：render 崩溃/跨页回来重同步快照（无会话="null"） */
    fun stateJson(): String {
        if (!active() || tocPos < 0 || tocPos >= toc.size) return "null"
        return JSONObject()
            .put("novelId", novelId)
            .put("bookTitle", bookTitle)
            .put("orderIndex", toc[tocPos])
            .put("paraIndex", paraPos)
            .put("playing", playing())
            .toString()
    }

    private fun pause() {
        if (state == "playing") {
            settleActiveSeconds()
            state = "paused"
            curUtterId = null
            voice?.pauseX() // M362 语音中原位暂停（看门狗挂起；resume 续播不重头）
            Engine.stop()
            emitPaused()
            service?.onSessionChanged()
        }
    }

    private fun resume() {
        if (state == "paused") {
            state = "playing"
            activeSince = System.currentTimeMillis()
            emitResumed()
            service?.onSessionChanged()
            // M362 分流：语音态原位续播；文字态 chunks 空守卫（replace 段语音被停后
            // 直接段推进——该段本就不读文字，不续读=不空转卡死）
            if (voiceStage == "voice" && voice != null) {
                voice?.resumeX()
            } else if (chunkPos < chunks.size) {
                speakCurrentChunk()
            } else {
                moveToNextPara()
            }
        }
    }

    private fun stopSession(reason: String) {
        if (state == "idle") return
        settleActiveSeconds()
        reportProgress(final = true)
        state = "idle"
        curUtterId = null
        stopVoicePlayback() // M362 停机五路之一：语音残响必止
        resetChapterRuntime()
        nextChapterCache = null
        Engine.stop()
        emit("stopped") { put("reason", reason) }
        service?.onSessionChanged()
        service?.stopSelfSafe()
    }

    private fun applySleep(v: String) {
        sleepTimer = v
        if (v == "off" || v == "chapter") {
            sleepDeadline = 0L
        } else {
            sleepDeadline = System.currentTimeMillis() + ((v.toDoubleOrNull() ?: 0.0) * 60_000).toLong()
        }
    }

    private fun jumpChapter(dir: Int) {
        if (!active()) return
        val next = tocPos + dir
        if (next < 0 || next >= toc.size) return
        stopVoicePlayback() // M362 停机五路之一：跳章先止当前语音
        Engine.stop()
        curUtterId = null
        openChapter(toc[next], 0)
    }

    private fun resetChapterRuntime() {
        chapterTitle = ""
        paras = emptyList()
        paraPos = 0
        chunks = emptyList()
        chunkPos = 0
        tocPos = -1
        fetchingChapter = false
        voicePlayedPara = -1
    }

    // ══════════ 章节拉取与推进 ══════════

    private fun openChapter(orderIndex: Int, paraStart: Int) {
        val idx = toc.indexOf(orderIndex)
        if (idx < 0) { // toc 窗口外（书章数据变了）——收摊如实上报
            emit("error") { put("message", "章节目录已变化，请刷新页面后重试") }
            state = "idle"
            service?.stopSelfSafe()
            return
        }
        tocPos = idx
        paraPos = 0
        state = "preparing"
        val cached = nextChapterCache
        if (cached != null && cached.first == orderIndex) {
            nextChapterCache = null
            beginChapter(cached.second.first, cached.second.second, paraStart)
            return
        }
        fetchChapter(orderIndex) { ok ->
            if (!ok) return@fetchChapter // 失败路径在 fetch 内已处理
            beginChapter(chapterTitle, paras, paraStart)
        }
    }

    /** 拉一章 segments（data.tts 纯文本直用）。401→auth 暂停；40302→rotate 一次重试。 */
    private fun fetchChapter(orderIndex: Int, cb: (Boolean) -> Unit) {
        if (fetchingChapter) return
        fetchingChapter = true
        val doFetch = {
            ApiClient.getJson(segmentsPath(novelId, orderIndex, contentToken)) { code, json ->
                main.post {
                    fetchingChapter = false
                    when {
                        code == 200 && json?.optInt("code") == 0 -> {
                            val d = json.optJSONObject("data") ?: run { failChapter(); return@post }
                            val ttsArr = d.optJSONArray("tts") ?: run { failChapter(); return@post }
                            val list = ArrayList<Para>(ttsArr.length())
                            for (i in 0 until ttsArr.length()) {
                                val p = ttsArr.optJSONObject(i) ?: continue
                                val t = p.optString("t")
                                val br = p.optBoolean("br")
                                // M362 该段最热语音（旧服务端无 va 键=null → 联动自然失活，
                                // 双向部署安全；预取章同载）
                                val va = p.optJSONObject("va")
                                val vk = va?.optString("k")?.takeIf { it.isNotEmpty() }
                                list.add(Para(t, p.optString("h"), br || t.isEmpty(), vk, va?.optLong("d") ?: 0L))
                            }
                            if (tocPos >= 0 && toc[tocPos] == orderIndex) {
                                chapterTitle = d.optString("title")
                                paras = list
                            } else {
                                nextChapterCache = orderIndex to (d.optString("title") to list)
                            }
                            cb(true)
                        }
                        code == 401 -> {
                            emit("auth")
                            state = "paused"
                            service?.onSessionChanged()
                        }
                        code == 403 || json?.optInt("code") == 40302 -> rotateThen(orderIndex, cb)
                        else -> { // 404/墙/网络——收摊（文案区分游客墙）
                            val wall = json?.optBoolean("guestWall") == true
                            emit("error") {
                                put(
                                    "message",
                                    if (wall) "试读已到尽头，登录后免费收听全本" else "章节加载失败，请稍后再试"
                                )
                            }
                            failChapter()
                        }
                    }
                }
            }
        }
        if (tokenNearExpiry()) rotateToken(doFetch, fallback = doFetch) else doFetch()
    }

    private fun failChapter() {
        state = "idle"
        stopVoicePlayback() // M362 停机五路之一：拉章失败收摊必止语音
        service?.stopSelfSafe()
    }

    /** token 临期（<10min）或已 40302：POST /me/reading-token 持旧换新一次，成功即重拉 */
    private fun rotateThen(orderIndex: Int, cb: (Boolean) -> Unit) {
        rotateToken({
            // 新 token 已就位——复用标准拉章路径（解析/缓存/事件全套）
            fetchChapter(orderIndex, cb)
        }, fallback = {
            emit("error") { put("message", "阅读校验已过期，请回到页面刷新后重试") }
            failChapter()
        })
    }

    private fun rotateToken(then: () -> Unit, fallback: () -> Unit) {
        val body = JSONObject().put("novelId", novelId).put("ct", contentToken)
        ApiClient.postJson("/api/v1/me/reading-token", body) { code, json ->
            main.post {
                val fresh = json?.optJSONObject("data")?.optString("ct")
                if (code == 200 && !fresh.isNullOrEmpty()) {
                    contentToken = fresh
                    then()
                } else if (code == 401) {
                    emit("auth")
                    state = "paused"
                    service?.onSessionChanged()
                } else fallback()
            }
        }
    }

    /** 解码 token payload 读 e（wsct_<b64url>.<sig>——无密钥可读，仅排续期时机） */
    private fun tokenNearExpiry(): Boolean = try {
        val payload = contentToken.removePrefix("wsct_").substringBefore(".")
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val e = JSONObject(json).optLong("e")
        e - System.currentTimeMillis() < 10 * 60_000
    } catch (_: Exception) {
        false
    }

    private fun segmentsPath(novelId: String, orderIndex: Int, ct: String): String {
        val enc = URLEncoder.encode(novelId, "UTF-8")
        val ctEnc = URLEncoder.encode(ct, "UTF-8")
        return "/api/v1/novels/$enc/chapters/$orderIndex/segments?ct=$ctEnc"
    }

    private fun beginChapter(title: String, list: List<Para>, paraStart: Int) {
        chapterTitle = title
        paras = list
        // ⭐F1 存量修复（M362 随批）：置 paraStart-1，让 moveToNextPara 的前置自增精确
        // 落在 paraStart——旧版 paraPos=paraStart 直调 advance()，前置自增使起播/跳章/
        // 连读/断点续听全部跳过目标段本身（单段章整章跳空直落 chapterDone；批⑨R4 换
        // 推进语义时入口补偿缺失）
        paraPos = paraStart.coerceIn(0, (list.size - 1).coerceAtLeast(0)) - 1
        voicePlayedPara = -1
        state = "playing"
        activeSince = System.currentTimeMillis()
        startHeartbeat()
        service?.onSessionChanged()
        emit("chapter") {
            put("orderIndex", toc[tocPos])
            put("chapterTitle", title)
        }
        moveToNextPara()
    }

    // ══════════ 朗读推进（块状态机） ══════════

    /** 块推进口（utterance done 的唯一续入口）。
     *  ⭐批⑨R4 结构修：原版恒「重切当前段+chunkPos=0 重读」=站主真机「不断重复一段」
     *  真根因——chunkPos 在推进路径上从不自增、paraPos 只在 skip/空段才走，正常段
     *  永远停在同段第一块循环（与引擎无关，所有设备皆然；站主三轮引擎侧排查
     *  〔queries/轮询补发/估算补发〕全打在错误的靶层——补发越勤循环越欢）。
     *  M362 拆分：本函数只管当前段文本块；段推进下沉 moveToNextPara()（语音槽位挂
     *  段边界；after 档在段文本读尽处插语音——播完再段推进，末段评论先于 chapterDone）。 */
    private fun advance() {
        chunkPos++
        if (chunkPos < chunks.size) {
            speakCurrentChunk()
            return
        }
        val cur = paras.getOrNull(paraPos)
        if (paraVoice == "after" && cur != null && cur.voiceKey != null && voicePlayedPara != paraPos) {
            voicePlayedPara = paraPos
            playVoice(
                onDone = { moveToNextPara() },
                onError = { moveToNextPara() },
                voiceKey = cur.voiceKey,
                voiceMs = cur.voiceMs
            )
            return
        }
        moveToNextPara()
    }

    /** 段推进（原 advance 的 while 体）：落位段决策语音槽位（replace=不读文字只播评论、
     *  before=评论先于文字、after=文字后的槽在 advance 块尽处）后 speak；章尽收口。 */
    private fun moveToNextPara() {
        while (++paraPos < paras.size) {
            val p = paras[paraPos]
            if (p.skip) continue
            val hasVoice = paraVoice != "off" && p.voiceKey != null
            chunks = if (paraVoice == "replace" && hasVoice) emptyList() else splitChunks(p.text, 300)
            chunkPos = 0
            if (chunks.isNotEmpty() || (paraVoice == "replace" && hasVoice)) {
                reportAnchor()
                emit("para") {
                    put("orderIndex", toc[tocPos])
                    put("paraIndex", paraPos)
                    put("chapterTitle", chapterTitle)
                }
                // 审查修：voice 事件移至 playVoice 真实起播处发（after 档段首即亮=整段文字
                // 朗读期谎报「正在播放」）
                when {
                    paraVoice == "replace" && hasVoice -> playVoice(
                        onDone = { moveToNextPara() },
                        onError = { // 播放失败回落读文字——该段内容不丢
                            chunks = splitChunks(p.text, 300)
                            chunkPos = 0
                            if (chunks.isNotEmpty()) speakCurrentChunk() else moveToNextPara()
                        },
                        voiceKey = p.voiceKey,
                        voiceMs = p.voiceMs
                    )
                    paraVoice == "before" && hasVoice -> playVoice(
                        onDone = { speakCurrentChunk() },
                        onError = { speakCurrentChunk() },
                        voiceKey = p.voiceKey,
                        voiceMs = p.voiceMs
                    )
                    else -> speakCurrentChunk()
                }
                return
            }
        }
        chapterDone()
    }

    /** M362 段评语音起播（进语音态三件套——设计压测 R2/R3 落点）：
     *  ①curUtterId=null 关 utterance 三门（Engine 轮询/字数估算的迟到 done 全被 id
     *    对账拦下）；②watchdogRunnables++ 作废残留文本看门狗票（长语音期
     *    watchdogRecover=双声道的双保险）；③voiceTicket 代际作废停机后的迟到回调。
     *  ⭐审查修（睡眠复活+暂停越权同根）：完成/失败续体统一 state 门——checkSleepDeadline
     *  收摊（idle）或暂停（paused）后绝不执行续体；暂停场景由 resume() 三路分流接管
     *  （voiceStage 已复位 text：before=读文字块、after/replace=段推进，原位语义保持）。 */
    private fun playVoice(onDone: () -> Unit, onError: () -> Unit, voiceKey: String?, voiceMs: Long) {
        val key = voiceKey ?: return onError()
        curUtterId = null
        watchdogRunnables++
        voice?.stopAndRelease() // 防重入
        val ticket = ++voiceTicket
        voiceStage = "voice"
        emit("voice") {
            put("orderIndex", toc[tocPos])
            put("paraIndex", paraPos)
        }
        voice = VoiceClipPlayer(
            url = SiteConfig.SITE_URL + "/uploads/voice/" + key,
            rate = rate,
            onDone = {
                if (ticket == voiceTicket && voiceStage == "voice") {
                    voiceStage = "text"
                    voice = null
                    checkSleepDeadline()
                    if (state == "playing") onDone()
                }
            },
            onError = {
                if (ticket == voiceTicket && voiceStage == "voice") {
                    voiceStage = "text"
                    voice = null
                    if (state == "playing") onError()
                }
            }
        )
        voice?.armCompletionWatchdog(voiceMs)
    }

    /** M362 语音急停（停机/跳段/off 热更）——不触发续体回调，由调用方接管走向 */
    private fun stopVoicePlayback() {
        voiceTicket++
        voice?.stopAndRelease()
        voice = null
        voiceStage = "text"
    }

    private fun speakCurrentChunk() {
        if (state != "playing" || chunkPos >= chunks.size) return
        val text = chunks[chunkPos]
        val id = "s${++seq}"
        curUtterId = id
        speakAt = System.currentTimeMillis()
        val ok = Engine.speak(id, text, rate)
        if (!ok) { // 引擎未就绪（reinit 中）——短暂等待自愈一次
            main.postDelayed({ if (state == "playing" && curUtterId == id) speakCurrentChunk() }, 800)
            return
        }
        scheduleWatchdog(text)
        checkSleepDeadline()
    }

    private fun chapterDone() {
        // 「本章完」档：起播章读完即停（web 逐块同语义）
        if (sleepTimer == "chapter" && toc[tocPos] == startOrderIndex) {
            stopSession("chapter")
            return
        }
        // 心跳末笔（章边界立即落锚，不等 15s）
        settleActiveSeconds()
        reportProgress(final = true)
        if (tocPos + 1 >= toc.size) {
            state = "idle"
            stopHeartbeat()
            Engine.stop()
            emit("ended")
            service?.onSessionChanged()
            service?.stopSelfSafe()
            return
        }
        val next = toc[tocPos + 1]
        state = "preparing"
        val cached = nextChapterCache
        if (cached != null && cached.first == next) {
            tocPos++
            nextChapterCache = null
            beginChapter(cached.second.first, cached.second.second, 0)
        } else {
            openChapter(next, 0)
        }
    }

    /** 看门狗：块超时（>max(30s, 2×预估)）无回调=引擎僵死——reinit 从当前块续读 */
    private fun scheduleWatchdog(text: String) {
        val estMs = (text.length / (4.5 * rate.coerceAtLeast(0.5f)) * 1000).toLong()
        val delay = maxOf(30_000L, estMs * 2)
        val ticket = ++watchdogRunnables
        main.postDelayed({
            if (ticket != watchdogRunnables) return@postDelayed
            if (state == "playing" && curUtterId != null) watchdogRecover()
        }, delay)
    }

    private fun watchdogRecover() {
        watchdogFails++
        if (watchdogFails >= 2) {
            emit("error") { put("message", "朗读引擎多次无响应，已停止；请稍后重试") }
            stopSession("error")
            return
        }
        Engine.reinit(appContext ?: return)
        // 引擎重建就绪后由 ensure 回调驱动不了这里（会话已过 start 口）——直接重读当前块
        main.postDelayed({
            if (state == "playing") speakCurrentChunk()
        }, 1_500)
    }

    // ══════════ 进度心跳（唯一写者） ══════════

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatSeq++
        val seqAtStart = heartbeatSeq
        activeSince = System.currentTimeMillis()
        main.postDelayed(object : Runnable {
            override fun run() {
                if (seqAtStart != heartbeatSeq || !active()) return
                if (state == "playing") {
                    settleActiveSeconds()
                    reportProgress(final = false)
                }
                checkSleepDeadline()
                main.postDelayed(this, 15_000)
            }
        }, 15_000)
    }

    private fun stopHeartbeat() {
        heartbeatSeq++
    }

    private fun settleActiveSeconds() {
        val now = System.currentTimeMillis()
        if (activeSince > 0 && state == "playing") {
            pendingSeconds += ((now - activeSince) / 1000).toInt()
        }
        activeSince = if (state == "playing") now else 0
    }

    /** 段锚上报（advance 每段一笔——服务端「锚点比较去重」幂等，高频无害） */
    private fun reportAnchor() {
        if (paraPos !in paras.indices) return
        reportProgress(final = false, forceAnchor = true)
    }

    private fun reportProgress(final: Boolean, forceAnchor: Boolean = false) {
        val ctx = appContext ?: return
        if (tocPos !in toc.indices) return
        val seconds = pendingSeconds.coerceAtMost(30) // 服务端 zod ≤30 同口径
        pendingSeconds = 0
        if (!final && seconds == 0 && !forceAnchor) return
        val body = JSONObject()
            .put("novelId", novelId)
            .put("chapterOrderIndex", toc[tocPos])
        if (paraPos in paras.indices) {
            body.put("paragraphIndex", paraPos)
            body.put("paragraphHash", paras[paraPos].hash)
        }
        if (seconds > 0) body.put("seconds", seconds)
        ApiClient.putJson("/api/v1/me/progress", body) { code, _ ->
            if (code == 401) { // 会话过期：暂停待续（auth 事件，回前台重登录后可继续）
                main.post {
                    if (state == "playing") {
                        settleActiveSeconds()
                        state = "paused"
                        voice?.pauseX() // M362 停机五路之一：auth 暂停（语音原位待续）
                        Engine.stop()
                        emit("auth")
                        service?.onSessionChanged()
                    }
                }
            }
        }
    }

    private fun checkSleepDeadline() {
        if (sleepTimer != "off" && sleepTimer != "chapter" && sleepDeadline > 0) {
            val left = sleepDeadline - System.currentTimeMillis()
            if (left <= 0) {
                stopSession("sleep")
                return
            }
            emit("sleep-left") { put("left", ((left + 59_999) / 60_000).toInt()) }
        }
    }

    // ══════════ 预取 ══════════

    /** 当前章读度过半即预取下一章（对冲 Doze 网络限制与慢网章间断读） */
    private fun maybePrefetch() {
        if (nextChapterCache != null || fetchingChapter) return
        if (tocPos + 1 >= toc.size) return
        if (paraPos < paras.size / 2) return
        fetchChapter(toc[tocPos + 1]) { _ -> }
    }

    // ══════════ 事件 ══════════

    private fun emit(kind: String, build: JSONObject.() -> Unit = {}) {
        val json = JSONObject().put("kind", kind).apply(build).toString()
        // 批⑤R 铁律：U+2028/2029 字面经 shell/Edit 传输不可靠，一律 0x2028.toChar() 构造；
        // JSONObject.quote 不转义它们（JSON 合法）但 JS 字符串字面量非法（唯一残余缝）
        val payload = org.json.JSONObject.quote(json)
            .replace(0x2028.toChar().toString(), "\\u2028")
            .replace(0x2029.toChar().toString(), "\\u2029")
        WebChannel.callGuarded("__wsTtsSession", payload)
        maybePrefetch() // para 事件后顺手预取（自带防重门）
    }

    private fun emitPaused() = emit("paused")
    private fun emitResumed() = emit("resumed")

    // ══════════ 切块（web tts.ts splitChunks 逐字复刻） ══════════

    private fun splitChunks(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > limit) {
            val slice = rest.substring(0, limit)
            var cut = -1
            for (ch in charArrayOf('。', '！', '？', '；', '\n')) {
                val at = slice.lastIndexOf(ch)
                if (at > cut) cut = at
            }
            val at = if (cut > limit * 0.5) cut + 1 else limit
            out.add(rest.substring(0, at))
            rest = rest.substring(at)
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
