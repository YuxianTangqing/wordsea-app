package com.sihaishumeng.app.core.bridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import com.sihaishumeng.app.core.device.KeyboardInsetController
import com.sihaishumeng.app.core.tts.TtsEngineHolder
import com.sihaishumeng.app.core.tts.TtsSessionManager

/**
 * __wsShell JS 桥（M-APP15 版本桥起家；M247 音量键/M248 TTS/M249 键盘通道随批；
 * 批②自壳迁出）——**桥契约冻结**：9 个方法签名与行为逐字保留（M248 三法批⑨ 起
 * 转打进程级 TtsEngineHolder，签名/返回语义不变），web 层零改动同时适配轻壳与
 * 本原生工程。批④W 第 9 方法 socialLogin（登录页网页化）；批⑨ 新增会话五法
 * （ttsStartSession/ttsControl/ttsSessionState/ttsVoices/ttsSetVoice——听书会话
 * 下沉原生前台服务+音色选择）——增量不在冻结列，web 特征探测（typeof）回落旧
 * 逐块模式=双向部署安全。批⑨R5~R7 的 ttsOpenVoiceSettings（系统语音设置直达）
 * R8 随站主令退役（站主 ROM 无 TTS 深链+引擎无 launcher=落输入法页无用；发音人
 * 诉求改由 ttsVoices 的 pitch 音调档承接——全引擎通用）。
 *
 * 只暴露只读版本号+声明性动作——授权大域页面（qq/alipay/bilibili/github）拿到的
 * 也只是数字与无副作用的开关。@JavascriptInterface 方法跑在 binder 线程。
 */
class ShellBridge(
    private val activity: Activity,
    private val keyboard: KeyboardInsetController,
    /** M247 接管态写回宿主 @Volatile 字段（渲染进程重建不丢态——宿主持有）。
     *  ⚠️ 勿改名回 setVolumePaging：与方法 fun setVolumePaging 同名时，Kotlin 调用点
     *  `setVolumePaging(enabled)` 的重载解析「同名函数优先于函数类型属性的 invoke
     *  约定」→ 编译成方法自调用无限递归 → StackOverflowError 经 WebView 包装成
     *  JS Error「Java exception was raised during method invocation」抛回网页 →
     *  阅读器挂载即 error.tsx 死循环（批②真机验收实锤，2026-10-30）。 */
    private val onVolumePagingChange: (Boolean) -> Unit,
    /** 批④W 桥面社交登录（切主线程后转发宿主 handleSocialLogin）。
     *  ⚠️ 同上命名铁律：参数名与方法 fun socialLogin 严禁同名（递归坑同源）。 */
    private val onSocialLogin: (String, String, String) -> Unit,
) {
    @JavascriptInterface
    fun versionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    @JavascriptInterface
    fun versionCode(): Int = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) {
        0
    }

    /** M247 web 声明音量键接管态（阅读器挂载且听书未播=true）。跑在 binder 线程，
     *  仅写宿主 @Volatile 布尔——无 UI 动作，无需 runOnUiThread。 */
    @JavascriptInterface
    fun setVolumePaging(enabled: Boolean) {
        onVolumePagingChange(enabled)
    }

    /** M248 预热原生 TTS（web 阅读器挂载时调；批⑨ 起引擎=TtsEngineHolder 进程单例——
     *  懒初始化+多引擎挑选+失败如实上报；签名与返回语义逐字不变）。 */
    @JavascriptInterface
    fun ttsPrepare() {
        activity.runOnUiThread { TtsEngineHolder.ensure(activity) { _, _ -> } }
    }

    /** M248 朗读一块（web 侧 chunk，id=自铸 `c${n}`/`one${n}`）。返回就绪态：
     *  false=引擎尚在初始化，web 侧置暂停态提示用户稍候点继续。 */
    @JavascriptInterface
    fun ttsSpeak(id: String, text: String, rate: Float): Boolean = TtsEngineHolder.speak(id, text, rate)

    /** M248 停读（web pause/stop 两义共用——pause 后 resume=当前块重读，由 web 记块位） */
    @JavascriptInterface
    fun ttsStop() {
        TtsEngineHolder.stop()
    }

    // ══ 批⑨ 会话五法（听书会话下沉原生前台服务；web 特征探测在场才调用） ══

    /** 起播会话：options={novelId,bookTitle,rate,sleepTimer,voiceId?,orderIndex,
     *  paraIndex,ct,toc:[章序数组]}。false=参数坏/不可会话（web 回落逐块）。
     *  首次起播顺带请求 POST_NOTIFICATIONS（33+；未授权 FGS 照跑、锁屏媒体卡
     *  读 MediaSession 不依赖通知——请求只为通知栏可见，拒两次系统静默不再弹）。 */
    @JavascriptInterface
    fun ttsStartSession(optionsJson: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2481)
        }
        return TtsSessionManager.start(activity, optionsJson)
    }

    /** 会话控制：{"a":"pause|resume|stop|setRate|setSleep|next|prev","v":...}
     *  （web 播放控件/通知栏/耳机线控统一汇流口——通知栏与耳机在 Service 侧直调）。 */
    @JavascriptInterface
    fun ttsControl(actionJson: String) {
        TtsSessionManager.control(actionJson)
    }

    /** 会话快照（render 崩溃重建/跨页返回重同步用；无会话="null"） */
    @JavascriptInterface
    fun ttsSessionState(): String = TtsSessionManager.stateJson()

    /** 当前引擎 zh 音色列表 JSON（[{"id","name"}]；引擎未就绪="[]"）。
     *  批⑨R4：面板一开 web 即拉本口——引擎懒初始化期 appCtx/engine 俱空=恒 "[]"，
     *  站主实录「下拉只有自动」。首调顺手 warm（存 appCtx+触发 init）：引擎条目段
     *  依赖 appCtx（同步立即生效），音色段等 init 完成后重开面板可见。 */
    @JavascriptInterface
    fun ttsVoices(): String {
        TtsEngineHolder.warm(activity.applicationContext)
        val arr = org.json.JSONArray()
        TtsEngineHolder.voices().forEach { (id, name) ->
            arr.put(org.json.JSONObject().put("id", id).put("name", name))
        }
        return arr.toString()
    }

    /** 设置音色（立即作用于后续朗读；false=失效，web 提示重选回「自动」） */
    @JavascriptInterface
    fun ttsSetVoice(voiceId: String): Boolean = TtsEngineHolder.setVoice(voiceId)


    /** M249 初始同步：页面加载时键盘已开的罕见态（SPA 恢复/换页）。物理 px；0=键盘关。
     *  只读无动作面（与 versionName 同口径，三方页拿到的只是数字）。 */
    @JavascriptInterface
    fun keyboardHeight(): Int = keyboard.lastKbPx

    /** M249 换算分母：最近一次推送时刻的 WebView 物理 px（keyboardHeight 为 0 时无意义） */
    @JavascriptInterface
    fun keyboardViewportPx(): Int = keyboard.lastViewPx

    /** 批④W 网页登录页四家按钮的 SDK 拉起口（登录页=网页本尊，桥只做能力层）。
     *  provider=wechat/qq/alipay/bilibili/github（批⑥：github 仅绑定态）；optionsJson=
     *  {invite?, mode?}（批⑥：mode='link'=绑定态上送 /app-social/link 或 /link-social
     *  浮层，缺省登录；web 侧绑定按钮带 versionName>=2.0.2 门控——老 APK 桥无视
     *  mode 会照跑登录）；callbackName=网页注册的一次性回呼名（结果 JSON 由
     *  SocialAuthController 产出）。跑在 binder 线程——切主线程再动 SDK/Activity。 */
    @JavascriptInterface
    fun socialLogin(provider: String, optionsJson: String, callbackName: String) {
        activity.runOnUiThread { onSocialLogin(provider, optionsJson, callbackName) }
    }
}
