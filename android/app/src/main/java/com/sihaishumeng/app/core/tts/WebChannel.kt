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

    /** 注入 JS（主线程；无有效 WebView=静默丢弃）。返回是否送达。 */
    fun evalJs(js: String): Boolean {
        val wv = ref.get() ?: return false
        if (main.looper === Looper.myLooper()) {
            wv.evaluateJavascript(js, null)
            return true
        }
        var delivered = true
        main.post {
            val cur = ref.get()
            if (cur != null) cur.evaluateJavascript(js, null) else delivered = false
        }
        return delivered
    }

    /** 注入「守卫式回调」（旧 web 不注册回调时零影响——__wsTtsEvent 同款范式）。 */
    fun callGuarded(fnName: String, jsonArg: String) {
        evalJs("window.$fnName && window.$fnName($jsonArg);")
    }
}
