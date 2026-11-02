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
 * 下沉原生前台服务+音色选择）；批⑩ 新增仿真翻页三法（nativeFlipSupported/
 * setFlipIntercept/requestPageFlip——翻页动画下沉原生）——增量不在冻结列，web
 * 特征探测（typeof）回落旧逐块模式=双向部署安全。批⑨R5~R7 的 ttsOpenVoiceSettings
 * （系统语音设置直达）R8 随站主令退役（站主 ROM 无 TTS 深链+引擎无 launcher=落
 * 输入法页无用；发音人诉求改由 ttsVoices 的 pitch 音调档承接——全引擎通用）。
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
    /** 批⑩ 仿真翻页声明（web setFlipIntercept 转发 FlipController；切主线程）。
     *  ⚠️ 同上命名铁律：参数名与方法 fun setFlipIntercept 严禁同名。 */
    private val onFlipStateChange: (String) -> Unit,
    /** 批⑩ 程序翻页请求（web requestPageFlip 转发 FlipController；切主线程）。
     *  ⚠️ 同上命名铁律：参数名与方法 fun requestPageFlip 严禁同名。 */
    private val onFlipRequest: (String) -> Unit,
    /** 五十一批 web 录音预检：web getUserMedia 之前经 ensureMicPermission 桥先把
     *  RECORD_AUDIO 落成已授（系统弹框挪到 WebView 挂起请求之外=挂起件取消竞态
     *  根治）。转发宿主 handleEnsureMicPermission（切主线程）。
     *  ⚠️ 同上命名铁律：参数名与方法 fun ensureMicPermission 严禁同名。 */
    private val onEnsureMicPermission: (String) -> Unit,
    /** 五十四批 录音自检：原生 AudioRecord 探针（失败路径专属）——判别「麦克风被其他
     *  应用占用」真凶。转发宿主 handleMicProbe（切主线程）。
     *  ⚠️ 同上命名铁律：参数名与方法 fun micProbe 严禁同名。 */
    private val onMicProbe: (String) -> Unit,
    /** 五十五批 原生录音宿主面（MainActivity 实现；binder 线程直调快照/动作——
     *  实现须线程安全）。web 侧 typeof 特征探测（旧壳无 micRec* 方法=跳过走
     *  getUserMedia 原路=双向部署安全）。 */
    private val micRec: MicRecHost,
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

    /** 五十一批（2.0.19）web 录音预检口：getUserMedia 之前先把 RECORD_AUDIO 落成
     *  已授——系统弹框发生在 WebView 挂起请求之外＝2.0.18 竞态（弹框期页面失焦
     *  Chromium 取消挂起请求、允许结果白给）根治；用户允许后到达的
     *  onPermissionRequest 恒走「已授→直接 grant」快路。callbackName=网页注册的一次
     *  性回呼名，结果经 window[callbackName](true/false)（已授立即回 true，未授弹
     *  系统框、用户选择后回）。跑在 binder 线程——切主线程（弹框/evalJs 均主线程面）。
     *  web 侧 typeof 特征探测（旧壳无此方法=跳过预检走 getUserMedia 原路=双向部署安全）。 */
    @JavascriptInterface
    fun ensureMicPermission(callbackName: String) {
        activity.runOnUiThread { onEnsureMicPermission(callbackName) }
    }

    /** 五十四批（2.0.22）录音自检口：原生 AudioRecord 探针（失败路径专属）——判别
     *  「麦克风被其他应用占用」真凶（HAL 忙 vs WebView 采集层）。callbackName=网页注册
     *  的一次性回呼名，结果经 window[callbackName]('ok'|'in-use'|'no-perm'|'fail:…')。
     *  跑在 binder 线程——切主线程（转发宿主）。web 侧 typeof 特征探测（旧壳无此
     *  方法=跳过=零变化；双向部署安全）。 */
    @JavascriptInterface
    fun micProbe(callbackName: String) {
        activity.runOnUiThread { onMicProbe(callbackName) }
    }

    // ══ 五十五批 原生录音链（2.0.23；web 特征探测缺失=回落 getUserMedia=双向部署安全） ══
    // 全同步面（无回呼）：web 侧轮询 micRecState() 驱动状态机；产物经分块拉取
    // （micRecChunk——Binder 单串 1MB 上限防 TransactionTooLarge）。批⑩R 同款
    // runCatching 纪律：任何异常吞掉回默认值，绝不回抛 JS。

    /** 开始原生录音（宿主自请求 RECORD_AUDIO+建实例+起线程）。false=未授权/忙/起录失败。 */
    @JavascriptInterface
    fun micRecStart(maxMs: Int): Boolean = try {
        micRec.start(maxMs)
    } catch (e: Exception) {
        android.util.Log.e("MicRec", "start", e)
        false
    }

    /** 五十六批 start() 失败原因（''|'perm'|'busy'|'start'）——false 时读它区分「未授权/
     *  忙/起录失败」，web 红字直达+perm 显「去设置」按钮。 */
    @JavascriptInterface
    fun micRecStartError(): String = try {
        micRec.startError()
    } catch (e: Exception) {
        android.util.Log.e("MicRec", "startError", e)
        ""
    }

    /** 五十六批 打开本 App 系统设置页（ACTION_APPLICATION_DETAILS_SETTINGS）——
     *  权限被系统自动拒（安卓 11 两次拒绝后不再弹框）时用户手动授权入口。 */
    @JavascriptInterface
    fun micRecOpenSettings() {
        try {
            micRec.openSettings()
        } catch (e: Exception) {
            android.util.Log.e("MicRec", "openSettings", e)
        }
    }

    @JavascriptInterface
    fun micRecPause() {
        try {
            micRec.pause()
        } catch (e: Exception) {
            android.util.Log.e("MicRec", "pause", e)
        }
    }

    @JavascriptInterface
    fun micRecResume() {
        try {
            micRec.resume()
        } catch (e: Exception) {
            android.util.Log.e("MicRec", "resume", e)
        }
    }

    /** 收尾请求（异步终局：web 轮询 state 到 stopped 后分块拉取） */
    @JavascriptInterface
    fun micRecStop() {
        try {
            micRec.stop()
        } catch (e: Exception) {
            android.util.Log.e("MicRec", "stop", e)
        }
    }

    /** 放弃（弃数据进 cancelled；web 端继续轮询终局态） */
    @JavascriptInterface
    fun micRecCancel() {
        try {
            micRec.cancel()
        } catch (e: Exception) {
            android.util.Log.e("MicRec", "cancel", e)
        }
    }

    /** 拉完产物/失败/放弃后释放实例（web 每次录音会话收尾必调） */
    @JavascriptInterface
    fun micRecClear() {
        try {
            micRec.clear()
        } catch (e: Exception) {
            android.util.Log.e("MicRec", "clear", e)
        }
    }

    /** idle|recording|paused|stopped|error:…|cancelled */
    @JavascriptInterface
    fun micRecState(): String = try {
        micRec.state()
    } catch (e: Exception) {
        android.util.Log.e("MicRec", "state", e)
        "idle"
    }

    /** 最近 100ms 片电平（0..1；暂停期冻结） */
    @JavascriptInterface
    fun micRecLevel(): Double = try {
        micRec.level()
    } catch (e: Exception) {
        android.util.Log.e("MicRec", "level", e)
        0.0
    }

    @JavascriptInterface
    fun micRecDuration(): Int = try {
        micRec.durationMs()
    } catch (e: Exception) {
        android.util.Log.e("MicRec", "duration", e)
        0
    }

    /** 开头静音实测毫秒（stopped 后有效；-1=无效/null） */
    @JavascriptInterface
    fun micRecLead(): Int = try {
        micRec.leadMs()
    } catch (e: Exception) {
        android.util.Log.e("MicRec", "lead", e)
        -1
    }

    @JavascriptInterface
    fun micRecChunkCount(): Int = try {
        micRec.chunkCount()
    } catch (e: Exception) {
        android.util.Log.e("MicRec", "chunkCount", e)
        0
    }

    /** 分块拉取（0..chunkCount-1；越界/未就绪=空串） */
    @JavascriptInterface
    fun micRecChunk(i: Int): String = try {
        micRec.chunk(i)
    } catch (e: Exception) {
        android.util.Log.e("MicRec", "chunk", e)
        ""
    }
    // 批⑩R：三方法全链 runCatching——真机站主实录「选仿真翻页弹降级提示」=桥异常
    // 以 JS Error 同步抛回网页（45 窗 web 已套防御保命，本侧再加纵深：任何未预见
    // 异常吞掉+Log.e 定位，绝不回抛 JS/绝不崩主线程）。

    /** 能力口：API≥26（PixelCopy window 版可用）才承接原生卷页；24/25 恒 false
     *  →web 面板藏「仿真翻页」档+存量偏好按 slide-h 分派。 */
    @JavascriptInterface
    fun nativeFlipSupported(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    } catch (e: Exception) {
        android.util.Log.e("FlipBridge", "nativeFlipSupported", e)
        false
    }

    /** web 声明翻页接管态（幂等，web 状态变化即重发）：{enabled,ready,edgePrev,
     *  edgeNext,first,last,rect:{x,y,w,h},bg,dark,eink}——binder 线程切主线程。 */
    @JavascriptInterface
    fun setFlipIntercept(stateJson: String) {
        try {
            activity.runOnUiThread {
                try {
                    onFlipStateChange(stateJson)
                } catch (e: Exception) {
                    android.util.Log.e("FlipBridge", "setFlipIntercept/ui", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FlipBridge", "setFlipIntercept", e)
        }
    }

    /** 程序翻页请求（点区/音量键/空格，web 决策后调用）：{dir,cross,dur,seq}。
     *  cross=true=末/首页向外翻（动画完 hold 至 web 就绪声明，跨章加载间隙用）。 */
    @JavascriptInterface
    fun requestPageFlip(payloadJson: String) {
        try {
            activity.runOnUiThread {
                try {
                    onFlipRequest(payloadJson)
                } catch (e: Exception) {
                    android.util.Log.e("FlipBridge", "requestPageFlip/ui", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FlipBridge", "requestPageFlip", e)
        }
    }
}

/**
 * 五十五批 原生录音宿主面：ShellBridge 的 micRec* 全同步方法落到此接口（MainActivity
 * 实现）——binder 线程直调，实现必须线程安全（NativeMicRecorder 内部锁保证）。
 * start=false=未授权/忙/起录失败（五十六批起自请求 RECORD_AUDIO：缺权限弹系统框
 * 阻塞等待，拒绝/超时返 false+startError='perm'）；clear=产物拉完/放弃后释放实例
 * （防内存驻留）。
 */
interface MicRecHost {
    fun start(maxMs: Int): Boolean
    fun startError(): String
    fun openSettings()
    fun pause()
    fun resume()
    fun stop()
    fun cancel()
    fun clear()
    fun state(): String
    fun level(): Double
    fun durationMs(): Int
    fun leadMs(): Int
    fun chunkCount(): Int
    fun chunk(i: Int): String
}
