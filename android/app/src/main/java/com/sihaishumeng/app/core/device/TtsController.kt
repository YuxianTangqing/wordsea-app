package com.sihaishumeng.app.core.device

import android.app.Activity
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * M248 原生听书（App 内 TTS；批②自壳迁出）：Android WebView 不带
 * speechSynthesis，App 内听书由原生代读——android.speech.tts 系统引擎（多数机型
 * 随系统/讯飞预装，离线可用）。web 侧 tts.ts 检测 __wsShell.ttsSpeak 桥在场即切
 * 原生分支：逐块 speak，块推进经 UtteranceProgressListener 回调
 * window.__wsTtsEvent('done'|'error', id)。懒初始化：阅读器挂载时 web 先
 * ttsPrepare() 预热，用户点听书时已就绪。
 *
 * 线程规矩照抄壳：@Volatile ttsReady（桥 binder 线程读写、TTS 回调线程读）；
 * 引擎操作一律 runOnUiThread；evaluateJavascript 必须主线程（经 [evalJs] 回呼，
 * 由宿主做 webView isInitialized 守卫）。
 */
class TtsController(
    private val activity: Activity,
    private val evalJs: (String) -> Unit,
) {
    private var ttsEngine: TextToSpeech? = null

    @Volatile
    var ttsReady = false
        private set

    /** M248 懒初始化系统 TTS：onInit 成功 → 中文优先 + 挂进度回调（done/error 经
     *  evaluateJavascript 回投 web）。applicationContext 建引擎避 Activity 泄漏；
     *  onDestroy shutdown 兜底（宿主调 [shutdown]）。 */
    fun ensureTts() {
        if (ttsEngine != null) return
        ttsEngine = TextToSpeech(activity.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val engine = ttsEngine ?: return@TextToSpeech
            runCatching { engine.language = Locale.SIMPLIFIED_CHINESE } // 缺中文时引擎自行回落
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(utteranceId: String) {
                    ttsEventToWeb("done", utteranceId)
                }
                override fun onError(utteranceId: String) {
                    ttsEventToWeb("error", utteranceId)
                }
                @Deprecated("Deprecated in Java")
                override fun onStart(utteranceId: String) { /* web 不消费 */ }
            })
            ttsReady = true
        }
    }

    /** M248 朗读一块（web 侧 chunk，id=自铸 `c${n}`/`one${n}`）。返回 ttsReady：
     *  false=引擎尚在初始化，web 侧置暂停态提示用户稍候点继续。跑在桥 binder 线程，
     *  就绪检查读 @Volatile、引擎操作转主线程。 */
    fun speak(id: String, text: String, rate: Float): Boolean {
        if (!ttsReady) return false
        activity.runOnUiThread {
            val engine = ttsEngine ?: return@runOnUiThread
            engine.setSpeechRate(rate) // 与 web rate 同标尺（1.0=常速）
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
        return true
    }

    /** M248 停读（web pause/stop 两义共用——pause 后 resume=当前块重读，由 web 记块位） */
    fun stop() {
        ttsEngine?.let { engine -> activity.runOnUiThread { engine.stop() } }
    }

    /** M248 TTS 回调回投 web（id 是 web 侧 c${n}` 自铸串，无注入面） */
    private fun ttsEventToWeb(kind: String, id: String) {
        activity.runOnUiThread {
            evalJs("window.__wsTtsEvent && window.__wsTtsEvent('$kind','$id');")
        }
    }

    fun shutdown() {
        ttsEngine?.let { engine -> runCatching { engine.shutdown() } }
        ttsEngine = null
        ttsReady = false
    }
}
