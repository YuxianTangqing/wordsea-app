package com.sihaishumeng.app.core.bridge

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import com.sihaishumeng.app.core.device.KeyboardInsetController
import com.sihaishumeng.app.core.device.TtsController

/**
 * __wsShell JS 桥（M-APP15 版本桥起家；M247 音量键/M248 TTS/M249 键盘通道随批；
 * 批②自壳迁出）——**桥契约冻结**：8 个方法签名与行为逐字保留，web 层零改动同时
 * 适配轻壳与本原生工程（wordsea-app 路线定稿 2026-10-29）。批④W 新增第 9 方法
 * socialLogin（登录页网页化：四家按钮走桥拉 SDK）——增量不在冻结列，老壳 1.1.9
 * 无此方法，web 侧特征探测（typeof）回落网页 OAuth。
 *
 * 只暴露只读版本号+声明性动作——授权大域页面（qq/alipay/bilibili/github）拿到的
 * 也只是数字与无副作用的开关（socialLogin 只对主 WebView 生效页面有意义：结果
 * 回呼注入本 WebView，三方页拿到也拿不到会话）。@JavascriptInterface 方法跑在
 * binder 线程。旧壳（1.1.4-）无此桥：web 侧回退 UA 尾标判定。
 */
class ShellBridge(
    private val activity: Activity,
    private val tts: TtsController,
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

    /** M248 预热原生 TTS（web 阅读器挂载时调；引擎懒初始化零动作面——授权大域
     *  页面最坏也只是提前拉起系统语音引擎，speak/ttsStop 仍只在自家阅读器语境）。 */
    @JavascriptInterface
    fun ttsPrepare() {
        activity.runOnUiThread { tts.ensureTts() }
    }

    /** M248 朗读一块（web 侧 chunk，id=自铸 `c${n}`/`one${n}`）。返回 ttsReady：
     *  false=引擎尚在初始化，web 侧置暂停态提示用户稍候点继续。 */
    @JavascriptInterface
    fun ttsSpeak(id: String, text: String, rate: Float): Boolean = tts.speak(id, text, rate)

    /** M248 停读（web pause/stop 两义共用——pause 后 resume=当前块重读，由 web 记块位） */
    @JavascriptInterface
    fun ttsStop() {
        tts.stop()
    }

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
