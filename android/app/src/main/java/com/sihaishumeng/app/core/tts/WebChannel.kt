package com.sihaishumeng.app.core.tts

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * 批⑨ 进程级 WebView 通道（原生→web 注 JS 唯一出口）。
 *
 * 为什么需要它（对抗审查 A5）：听书会话在前台服务里跑，用户可能随时退出
 * Activity（进程存活、服务继续读）或遭遇 render 崩溃重建——旧路径「控制器持
 * Activity 引用回投」在 WebView 已 destroy 后注入会崩/进事件黑洞。本类持当前
 * 有效 WebView 的**弱引用**：MainActivity.attachWebView 注册（render 崩溃重建
 * 走同一装配口自动换绑），onDestroy 后引用自然落空→事件静默丢弃（会话继续读，
 * 这正是正确行为）。evaluateJavascript 必须主线程。
 */
object WebChannel {

    private val main = Handler(Looper.getMainLooper())
    private var ref = WeakReference<WebView?>(null)

    /** MainActivity.attachWebView 装配口调用（每个新 WebView 实例换绑） */
    fun bind(webView: WebView?) {
        ref = WeakReference(webView)
    }

    /** 注入 JS（主线程；无有效 WebView=静默丢弃）。返回是否送达。
     *  M376：两处 evaluateJavascript 包 runCatching——onDestroy 后弱引用在 GC 前
     *  仍会返回已 destroy 的 WebView，部分内核版本对销毁后注入抛 IllegalStateException
     *  （主线程崩）。配合 MainActivity.onDestroy 的 bind(null) 双保险。 */
    fun evalJs(js: String): Boolean {
        val wv = ref.get() ?: return false
        if (main.looper === Looper.myLooper()) {
            return runCatching { wv.evaluateJavascript(js, null) }.isSuccess
        }
        var delivered = true
        main.post {
            val cur = ref.get()
            if (cur != null) runCatching { cur.evaluateJavascript(js, null) }
            else delivered = false
        }
        return delivered
    }

    /** 注入「守卫式回调」（旧 web 不注册回调时零影响——__wsTtsEvent 同款范式）。 */
    fun callGuarded(fnName: String, jsonArg: String) {
        evalJs("window.$fnName && window.$fnName($jsonArg);")
    }
}
