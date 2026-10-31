package com.sihaishumeng.app.core.tts

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 批⑨ 进程级 TTS 引擎持有者（自 TtsController 演进——引擎脱离 Activity 生命周期：
 * 听书会话在前台服务里跑，Activity 销毁引擎不死）。
 *
 * 修复 TtsController 三存量坑（站主真机「引擎启动中→一直没声」根因群）：
 * ①onInit 失败/中文缺数据不再静默——明确回调失败原因，web 如实提示；
 * ②onError 覆写 API21+ 双参版（旧版只覆写 deprecated 单参=错误被吞）+onStop
 *   （自家 stop 必到，消费方按状态机门掉，勿当章推进）；
 * ③setLanguage 返回值必检——非 AVAILABLE 时枚举设备引擎三参构造逐个重试（上限 3），
 *   全败如实上报（no-voice）。
 *
 * 引擎操作一律主线程（Handler 串行——setSpeechRate 紧跟 speak 同队列无竞态）。
 * 事件经 [onUtteranceListeners] 分发（双消费者：逐块旧协议=__wsTtsEvent 经
 * WebChannel；会话=TtsSessionManager 块推进）。
 */
object TtsEngineHolder {

    private val main = Handler(Looper.getMainLooper())

    private var engine: TextToSpeech? = null
    private var enginePkg: String? = null // 实际选中的引擎包名（reinit 复用）
    private var triedPkgs = mutableSetOf<String>()

    /** 批⑨R 代际令牌：每代 createEngine 自增；fallback/reinit 换代后旧实例的迟到
     *  onInit 直接作废（多引擎兜底链上旧引擎的 ERROR 回调曾可把刚建的新引擎误杀） */
    private var generation = 0

    /** 批⑨R2 朗读块代际：新 speak 顶掉旧块的完成探测轮询（stop/reinit/换代一并自止） */
    private var speakGen = 0

    /** 批⑨R3 首个 appContext（voices() 枚举设备引擎取应用名用） */
    private var appCtx: Context? = null

    @Volatile
    var ready = false
        private set

    /** 用户音色（跨 reinit 重放；null=自动=引擎默认中文音色） */
    private var currentVoiceId: String? = null

    /** 引擎初始化结果回调（ok=false 时 reason 给 web 提示） */
    private val readyCallbacks = CopyOnWriteArrayList<(Boolean, String) -> Unit>()

    /** 朗读块事件（kind ∈ done/error/stop；id=调用方自铸对账串） */
    private val utteranceListeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    fun addUtteranceListener(l: (String, String) -> Unit) {
        utteranceListeners.add(l)
    }

    fun removeUtteranceListener(l: (String, String) -> Unit) {
        utteranceListeners.remove(l)
    }

    /** 懒初始化（幂等）：就绪即回 true；初始化中登记回调（主线程回调） */
    fun ensure(context: Context, cb: ((Boolean, String) -> Unit)?) {
        if (cb != null) {
            readyCallbacks.add(cb)
            // 就绪态立即回调（消费方无需等）
            if (ready) {
                main.post { cb(true, "") }
            }
        }
        if (engine != null) return
        createEngine(context.applicationContext, null)
    }

    /** 看门狗/会话起点热重建：同引擎包重建（语音数据/引擎进程偶死自愈），音色重放 */
    fun reinit(context: Context) {
        val pkg = enginePkg
        main.post {
            speakGen++ // 重建期旧轮询作废
            runCatching { engine?.shutdown() }
            engine = null
            ready = false
            triedPkgs.clear()
            createEngine(context.applicationContext, pkg)
        }
    }

    private fun createEngine(appContext: Context, pkg: String?) {
        if (appCtx == null) appCtx = appContext // voices() 枚举引擎 label 用（批⑨R3）
        if (pkg != null) triedPkgs.add(pkg)
        val gen = ++generation
        val tts = if (pkg != null) {
            TextToSpeech(appContext, { status -> main.post { if (gen == generation) onInit(status, appContext, pkg) } }, pkg)
        } else {
            TextToSpeech(appContext) { status -> main.post { if (gen == generation) onInit(status, appContext, null) } }
        }
        engine = tts
        enginePkg = pkg
    }

    private fun onInit(status: Int, appContext: Context, pkg: String?) {
        val eng = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            fallbackOrReport(appContext, pkg, "init-failed($status)")
            return
        }
        eng.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String) {
                main.post { utteranceListeners.forEach { it("done", utteranceId) } }
            }

            override fun onError(utteranceId: String) { // 旧版（API<21 路径保留）
                main.post { utteranceListeners.forEach { it("error", utteranceId) } }
            }

            override fun onError(utteranceId: String, errorCode: Int) { // ⭐API21+ 实际走这
                main.post { utteranceListeners.forEach { it("error", utteranceId) } }
            }

            override fun onStart(utteranceId: String) { /* 消费方不需要 */ }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                // 自家 stop()（暂停/停止）必到——消费方按状态机门掉，勿当推进
                main.post { utteranceListeners.forEach { it("stop", utteranceId) } }
            }
        })
        // 中文可用性检查+用户音色重放（voice 隐含 locale，选了 voice 不必再 setLanguage）
        val voiceOk = applyVoiceOrLanguage(eng)
        if (!voiceOk) {
            fallbackOrReport(appContext, pkg, "no-chinese")
            return
        }
        ready = true
        val cbs = readyCallbacks.toList()
        readyCallbacks.clear()
        cbs.forEach { it(true, "") }
    }

    /** 音色优先（用户选过→voices 里找同 id 重放）；否则 setLanguage(zh) 且检查返回值。
     *  返回 false=引擎读不了中文（换引擎或上报）。 */
    private fun applyVoiceOrLanguage(eng: TextToSpeech): Boolean {
        val vid = currentVoiceId
        if (vid != null) {
            val v = eng.voices?.firstOrNull { it.name == vid }
            if (v != null && eng.setVoice(v) == TextToSpeech.SUCCESS) return true
            // 音色失效（换引擎/卸语音数据）：回落语言检查，web 侧 ttsSetVoice 失败提示重选
        }
        val r = runCatching { eng.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return r >= 0 // LANG_MISSING_DATA(-1)/LANG_NOT_SUPPORTED(-2) 都算不可用
    }

    /** 当前引擎不可用 → 枚举设备其他引擎逐个试（上限 3 个）；全败如实上报。
     *  批⑨R：Manifest queries 补 TTS_SERVICE 后枚举在 30+ 真正可见；默认引擎（pkg=null）
     *  失败时读系统默认引擎包名记入 triedPkgs——否则 candidates.first() 可能仍是它白白
     *  烧一轮重试。报错文案带失败码与已试清单（真机定位器）。 */
    private fun fallbackOrReport(appContext: Context, failedPkg: String?, reason: String) {
        speakGen++ // 旧引擎的完成探测轮询作废
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        if (failedPkg == null) {
            runCatching {
                val def = android.provider.Settings.Secure.getString(
                    appContext.contentResolver, android.provider.Settings.Secure.TTS_DEFAULT_SYNTH
                )
                if (!def.isNullOrBlank()) triedPkgs.add(def)
            }
        }
        val candidates = runCatching {
            // 系统引擎解析（TTS_SERVICE intent）：常见=讯飞/谷歌/厂商。常量在嵌套接口
            // Engine 里（Java 实现类可直引 TextToSpeech.ENGINE_INTENT_ACTION，Kotlin
            // 不继承接口常量——须全路径）
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                .addCategory("android.intent.category.DEFAULT")
            appContext.packageManager.queryIntentServices(intent, 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .filter { it !in triedPkgs }
        }.getOrDefault(emptyList())
        if (candidates.isNotEmpty() && triedPkgs.size < 3) {
            createEngine(appContext, candidates.first())
            return
        }
        // 全败：清回调并如实上报（web toast 引导去系统设置装/换引擎；批⑨R 文案带
        // 失败码+枚举数+已试清单——真机一跑便知卡在哪层）
        val tried = (listOfNotNull(enginePkg) + triedPkgs.toList()).distinct().joinToString("/")
        val msg = if (reason.startsWith("no-chinese")) {
            "设备语音引擎缺中文语音数据（系统设置→文字转语音输出 可安装或切换引擎；已试 $tried）"
        } else {
            "语音引擎初始化失败 $reason（备选 ${candidates.size}；已试 $tried；系统设置→文字转语音输出 切换引擎后重试）"
        }
        val cbs = readyCallbacks.toList()
        readyCallbacks.clear()
        cbs.forEach { it(false, msg) }
    }

    /** 批⑨R3 混合列表：设备全部引擎（id=eng:包名，名=引擎应用名）+ 当前引擎 zh 音色
     *  （id=voice:Voice.name）。站主真机实录：兜底引擎 voices 空=下拉只剩「自动」且
     *  系统设置换音色生效但 APP 内无法换——引擎级自选=APP 内直达（切引擎三参重建）。
     *  web 下拉只吃 [{id,name}] 零改动；旧存量裸 voiceId 无前缀=不匹配回落「自动」✓ */
    fun voices(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val ctx = appCtx
        if (ctx != null) {
            runCatching {
                val pm = ctx.packageManager
                val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                    .addCategory("android.intent.category.DEFAULT")
                pm.queryIntentServices(intent, 0).mapNotNull { it.serviceInfo }.forEach { si ->
                    val label = runCatching {
                        pm.getApplicationLabel(si.applicationInfo).toString()
                    }.getOrDefault(si.packageName)
                    val mark = if (si.packageName == enginePkg) "（当前）" else ""
                    out.add("eng:${si.packageName}" to "引擎·$label$mark")
                }
            }
        }
        val eng = engine
        if (eng != null) {
            runCatching {
                eng.voices
                    ?.filter { it.locale?.language?.equals("zh", ignoreCase = true) == true }
                    ?.take(30) // 长列表防下拉爆炸（部分引擎暴露几十个）
                    ?.forEach { v -> out.add("voice:${v.name}" to "音色·${v.name}") }
            }
        }
        return out
    }

    /** 设置音色/引擎（立即作用于后续 speak）。eng:=换引擎重建（音色清空回「自动」）；
     *  voice:=当前引擎内选音色。false=失效（web 提示重选回「自动」） */
    fun setVoice(voiceId: String): Boolean {
        if (voiceId.startsWith("eng:")) {
            val pkg = voiceId.removePrefix("eng:")
            val ctx = appCtx ?: return false
            if (pkg == enginePkg) return true // 幂等
            reinitTo(ctx, pkg)
            return true
        }
        val vName = voiceId.removePrefix("voice:")
        val eng = engine ?: return false
        val v = runCatching { eng.voices }.getOrDefault(null)?.firstOrNull { it.name == vName } ?: return false
        val ok = runCatching { eng.setVoice(v) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS
        if (ok) currentVoiceId = vName
        return ok
    }

    /** 批⑨R3 显式换引擎（音色下拉 eng: 条目）：三参构造重建；音色选择清空（新引擎
     *  音色集不同，回「自动」；会话中切换=Manager 的 speak !ok 重试自接重建间隙） */
    private fun reinitTo(appContext: Context, pkg: String) {
        main.post {
            speakGen++
            runCatching { engine?.shutdown() }
            engine = null
            ready = false
            currentVoiceId = null
            triedPkgs.clear()
            createEngine(appContext, pkg)
        }
    }

    /** 朗读一块（逐块协议/会话共用）。false=引擎未就绪（调用方按旧语义处理）。 */
    fun speak(id: String, text: String, rate: Float): Boolean {
        if (!ready) return false
        val gen = ++speakGen
        main.post {
            val eng = engine ?: return@post
            eng.setSpeechRate(rate)
            eng.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            // 批⑨R2 完成探测双保险：部分引擎（站主真机兜底引擎实录）onDone 欠奉——
            // 推进死=看门狗 30s 重读循环「不断重复一段」。speak 后轮询 isSpeaking，
            // 连续两轮静默=补发 done（消费方按 utteranceId 核对，与真 onDone 幂等；
            // 新块 speak 顶代（speakGen）自止；15min 帽防泄漏——真僵死由看门狗接管）
            var silent = 0
            val start = android.os.SystemClock.elapsedRealtime()
            val poll = object : Runnable {
                override fun run() {
                    if (gen != speakGen) return
                    val e = engine ?: return
                    val speaking = runCatching { e.isSpeaking }.getOrDefault(true)
                    if (!speaking) {
                        silent++
                        if (silent >= 2) {
                            utteranceListeners.forEach { it("done", id) }
                            return
                        }
                    } else {
                        silent = 0
                    }
                    if (android.os.SystemClock.elapsedRealtime() - start < 15 * 60_000L) {
                        main.postDelayed(this, 600)
                    }
                }
            }
            main.postDelayed(poll, 1_500) // 首查延迟：入调合成间隙不误判
            // 批⑨R3 第三保险（站主真机 2.0.5 实测双欠奉引擎实录：onDone 与 isSpeaking
            // 全谎报——轮询等不到静默依旧重读循环）：按字数估算块时长到点强制补发 done。
            // 中文合成 ≈3.2 字/秒×语速，1.5 系数防引擎偏慢截断；正常引擎上 onDone/轮询
            // 先到、估算只是无害空炮（幂等核对）。真拖堂由看门狗 30s 接管重续。
            val estMs = (text.length / (3.2f * rate.coerceAtLeast(0.5f)) * 1000f * 1.5f)
                .toLong().coerceIn(2_000, 10 * 60_000L)
            main.postDelayed({
                if (gen == speakGen) utteranceListeners.forEach { it("done", id) }
            }, estMs)
        }
        return true
    }

    fun stop() {
        main.post {
            speakGen++
            runCatching { engine?.stop() }
        }
    }

    /** 进程级关停（实际无人调——Activity 销毁不杀引擎：会话期间进程存活） */
    fun shutdown() {
        main.post {
            speakGen++
            runCatching { engine?.shutdown() }
            engine = null
            ready = false
        }
    }
}
