import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名：android/keystore.properties（仓外 APP签名钥匙\ 原文拷入，gitignore）；
// storeFile=sihaishumeng-release.jks 纯文件名——file() 相对 app 模块目录解析正好命中
// android/app/sihaishumeng-release.jks。与轻壳同一枚钥匙（同包名+同签名=覆盖安装
// 无缝升级，第三方登录平台绑定包名+签名也靠它）。钥匙缺失=回退 debug 签名（出包后
// 必跑 apksigner verify --print-certs 与上版指纹比对 34bfed46…0711，防 debug 包流出）。
val ksProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.sihaishumeng.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sihaishumeng.app"
        minSdk = 24
        targetSdk = 36
        // ⭐ 出新版就在这里递增（versionCode 接轻壳 14/1.1.9 继续——覆盖安装线不断）
        // 批② 2.0.0 (15)：wordsea-app 原生工程起线——壳基建 817 行按 core 分层移植
        //   （bridge/web/update/device 四控制器），行为与壳 1.1.9 等价；五 tab 与
        //   social/ ui/ 目录预留待批③④填充。
        // 批⑤R 2.0.1 (16)：首发返修——支付宝授权回跳根治（manifest 补 AlipayResultActivity
        //   scheme filter + 独立 scheme sihaishumengalipay）+ 社交回呼归一 string 契约
        //   （quote 注入；2.0.0 对象直传致 web JSON.parse 必炸=「登录回调异常」）+ busy
        //   僵尸熔断（180s）。
        // 批⑥ 2.0.2 (17)：APP 内第三方绑定原生化——socialLogin 桥增 mode='link'
        //   （微信/QQ/支付宝 SDK 上送 /app-social/link 会话内关联；B站/GitHub 走
        //   /link-social 浮层授权屏）；web 侧绑定按钮 versionName>=2.0.2 门控。
        // 批⑨ 2.0.3 (18)：听书原生化——朗读会话下沉原生前台服务 TtsPlaybackService
        //   （锁屏/后台连读+通知栏三键+耳机线控+定时真可用+进度心跳+token rotate）；
        //   桥增会话五法 ttsStartSession/ttsControl/ttsSessionState/ttsVoices/ttsSetVoice
        //   （音色选择）；TtsController 退役→进程级 TtsEngineHolder（修「引擎启动中
        //   死循环」三坑+多引擎挑选）。
        // 批⑨R 2.0.4 (19)：TTS 引擎兜底真修——Manifest queries 补 TTS_SERVICE（30+
        //   枚举他引擎需要；缺声明=多引擎兜底链空转只剩默认引擎一发）+ 代际令牌防
        //   迟到 onInit 误杀新引擎 + 默认引擎失败记入已试集（不烧冤枉轮）+ 报错文案
        //   带失败码/备选数/已试清单（真机定位器）。
        // 批⑨R2 2.0.5 (20)：朗读推进双保险——部分引擎 onDone 欠奉（站主真机兜底引
        //   擎实录「不断重复一段」=推进死+看门狗 30s 重读循环）；speak 后轮询
        //   isSpeaking 连续两轮静默补发 done（消费方按 utteranceId 幂等），speakGen
        //   代际顶掉（stop/reinit/换引擎自止）。
        // 批⑨R3 2.0.6 (21)：站主 2.0.5 实测双欠奉引擎（onDone+isSpeaking 全谎报）→
        //   第三保险=按字数估算时长到点强制推进（中文≈3.2 字/秒×语速×1.5 系数，正
        //   常引擎上无害空炮）；音色下拉升级「引擎+音色」混合列表（eng:pkg=APP 内换
        //   引擎直达+voice:name 当前引擎音色）——治兜底引擎 voices 空=下拉只剩「自动」
        //   且系统设置换音色生效但 APP 内无法换。
        // 批⑨R4 2.0.7 (22)：⭐「不断重复一段」真根因=TtsSessionManager.advance 结构
        //   缺陷——chunkPos 推进路径从不自增+paraPos 只在 skip/空段走=正常段永远停
        //   在同段第一块循环（与引擎无关所有设备皆然；三轮引擎侧排查全打错靶层）。
        //   修=段内 chunkPos++ 推进+段尽 ++paraPos 连读。音色下拉「只有自动」=懒初始
        //   化期 appCtx/engine 俱空——桥 ttsVoices 首调 warm 预热（引擎条目段同步生效）。
        // 批⑨R5 2.0.8 (23)：⭐音色下拉三修「都是音色·zh 选谁都不变」——①复合唯一键
        //   voice:name#localeTag+按 (name,tag) 去重（站主引擎 voices name 恒 "zh"=N 行
        //   撞同 id，setVoice firstOrNull 恒命中第一条）；②name 形似语言码时标签用
        //   locale 中文显示名（「音色·中文（中国大陆）」）；③桥增 ttsOpenVoiceSettings
        //   一键直达系统语音设置页（厂商专有发音人不经 getVoices() 暴露的唯一正路）+
        //   回程 idle 态自动重 init 生效。
        // 批⑨R6 2.0.9 (24)：⭐自更新链断链修——批⑨R 改版本名直链后 SelfUpdateController
        //   的更新包圈定（URL 文件名==稳定槽固定名）失配=站主 2.0.7→2.0.8 真机实录
        //   「APP 内下载了新包但安装器不自动弹」（下载被当普通附件走公共目录+完成广播
        //   不注册）；放宽为文件名族匹配（稳定名+sihaishumeng-app-v*.apk 双吃）。
        //   +ttsOpenVoiceSettings 三级直达链（站主 ROM 实测 TTS_SETTINGS 未注册=点了
        //   跳设置主页：引擎 settingsActivity〔ServiceInfo 公开字段，「发音人下载」最
        //   可能就是它〕→TTS_SETTINGS raw→ACTION_LANGUAGE_INPUT_SETTINGS〔离目标一
        //   步〕→主页兜底）。站主设备单引擎（「语音设置」）+发音人不走 getVoices()=
        //   APP 内列出不可能（平台限制），直达链+回程自动生效=最优解。批⑧ SDK 顺延
        //   25/2.0.10（等B站工单批 union_id 权限）。
        // 批⑨R7 2.0.10 (25)：R6 直达链站主真机返修——INPUT_METHOD_SETTINGS 落的是
        //   「输入法」页非 TTS（ROM 分页），链首改 TTS 引擎应用本体拉起
        //   （getLaunchIntentForPackage——站主引擎 label「语音设置」，国产 TTS 引擎
        //   应用界面多含发音人管理；无 launcher 落 TTS_SETTINGS raw→输入法→主页）；
        //   web 侧配套=AppDownloadCta 对有桥 <2.0.9 版本加一次性手动安装提示（自举
        //   困境交底：修复在「被升级到的版本」里，2.0.8→2.0.9 那步必手动，此后恢复
        //   自动）。批⑧ SDK 顺延 26/2.0.11。
        // 批⑨R8 2.0.11 (26)：站主令「朗读声音里选出音色（换思路）」——厂商引擎发音人
        //   不经 getVoices() 暴露（平台限制，R5~R7 三轮实证），改 setPitch 声线档
        //   承接（全引擎通用）：下拉=引擎/音色/声线三族（声线·低沉 0.7/明亮 1.3/
        //   尖细 1.6），单选语义（真音色选中=声线回中性；空串=全重置治 pitch 残留）；
        //   ⭐直达链 ttsOpenVoiceSettings 随站主令退役（站主 ROM 无 TTS 深链+引擎无
        //   launcher=落输入法页无用）。批⑧ SDK 顺延 27/2.0.12。
        // 批⑩ 2.0.12 (27)：仿真翻页原生动画层（M355，起点/番茄式整页圆柱卷曲）——
        //   core/flip/ 四件：FlipInterceptorLayout 手势容器（横拖超 slop 接管，
        //   ViewPager 经典模式+requestDisallowInterceptTouchEvent 防 SwipeRefreshLayout
        //   抢）+CurlPageView 卷页盖层（条带绘制，透明洞模型=卷曲露 WebView 实时新页，
        //   终态全透明撤层零跳变；跨章 hold 画 bg 遮加载间隙）+FlipController 会话状态
        //   机（PixelCopy 拍起手帧一张/双张复用位图/fastForward 会话串行）+FlipDynamics
        //   （flip-dynamics.ts 直译，θ 判据与网页同源）。桥增三法 nativeFlipSupported/
        //   setFlipIntercept/requestPageFlip（24/25 恒 false=面板藏档）；事件回投
        //   __wsPageFlip settle/rollback/done/fail。web 是决策大脑（翻页决策/跨章/进度），
        //   native 是效果器（批⑨ 听书同构）。批⑧ SDK 顺延 29/2.0.14。
        // 批⑩R 2.0.13 (28)：真机返修「选仿真翻页弹降级提示」——三管齐下：①桥三法
        //   全链 runCatching+Log.e（任何未预见异常不回抛 JS/不崩主线程）；②PixelCopy
        //   失败/超时→软件绘制回退（rootView.draw——站主 ROM PixelCopy 兼容面，慢
        //   50-150ms 保功能）；③拦截层手势链总防护。
        // 批⑩R4 2.0.14 (29)：效果打磨四件（站主「一闪就过去+粗糙+文字突然放大」）——
        //   ①预拍缓存（settle 后 300ms 后台备下一手起手帧+pageGen 代际守卫）+即时亮相
        //   （位图未到先亮纸底色跟手）=治「按住死一下再跳」；②终态离屏过渡（θ150→170
        //   整纸滑出，治 25% 残贴跳变闪屏）；③⭐弃 PixelCopy 全软件绘制（站主 ROM
        //   PixelCopy SUCCESS 但帧错位=裁剪拉伸「文字突然放大」实锤；软件 draw 所见
        //   即所得）+physRect 改 web 声明 dpr 权威换算；④手感：满值拖距 0.62W+松手
        //   补间 1.5 系数+条带 24+暗峰 sin² 缓入+卷筒 0.15W。
        // 批⑩R5 2.0.15 (30)：站主「一滑没松手就变下一页/动画看不到/捏页角无反应」——
        //   ①⭐拖动态 θ 封顶 150°（离屏滑出段只留松手动画——R4 的 0.62W 拖距+离屏
        //   过渡叠加过度修正：快滑 θ 冲 165+ 盖层几乎全程透明=底下新页裸露=「一闪
        //   即逝」真凶）；②角部捏起热区（页角 22% 区轻拖 6px 任意方向即锁，角位定
        //   方向右角=下一页/左角=上一页，θ 用综合行程 hypot(dx,dy)）。
        // 批⑩R6 2.0.16 (31)：⭐几何范式重做——经典 PageWidget 贝塞尔（站主携技术材料
        //   探讨定稿）：折痕=AF 中垂线弧化（两段 quad，弧顶随行程内凹）；背面=位图
        //   沿折线反射镜像+暗化（素色纸背退役）；双投影（折痕弧 stroke 渐变+背面
        //   暗化）；F 点驱动替代 θ（行程比例判阈+hermite 对 t 插值+尾段 fade 渐出）。
        //   绘制序天然分界（整页位图+镜像盖翻起区）——全程 INTERSECTION 裁剪
        //   （硬件加速 Canvas 不支持 Region.Op.DIFFERENCE）。
        // 批⑧ SDK 2.0.17 (32)：B站登录/绑定换 accountoauth SDK 单路（BiliSdkAuth——
        //   拉起B站客户端授权，未装自兜 SDK 内置 H5；BiliAuthActivity 浮层网页流退役
        //   但保留给 GitHub 绑定）。⚠️预备内测批：union_id 探针复验（服务器回填脚本）
        //   通过前不发正式窗——web 侧端点 BILI_MOBILE_* 双键仍留空=404 隐藏，真机
        //   内测验收只到「拉起授权页+取消」（union_id 未批走完授权=openid 键裂号）。
        //   探针 ❌ 则本版本号作废（下批顺延 33/2.0.18 记档）。
        versionCode = 32
        versionName = "2.0.17"
    }

    signingConfigs {
        if (ksProps.isNotEmpty()) {
            create("release") {
                storeFile = file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 纯 Kotlin 无反射面（SDK 进包后按需补 keep）——R8 直开
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (ksProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // 批③（2026-10-30）：原生登录四件
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 微信 OpenSDK（Maven Central，aliyun public 代理可达）
    implementation("com.tencent.mm.opensdk:wechat-sdk-android:6.8.40")
    // QQ 互联 open-sdk：3.53.0 是 JCenter 遗物（Central 无），靠 aliyun public 代理
    // 拉取（2021 lastUpdated）。真机若遇 2024-06 平台政策不兼容 → 换 wiki.connect.qq.com
    // 手动 open_sdk_3.5.17_lite.jar 入 libs/ 并删此行（API 面兼容）。
    implementation("com.tencent.tauth:qqopensdk:3.53.0")
    // 支付宝 App 登录：官方 SDK 自 15.8.09 起 Maven 分发（Maven Central，aliyun
    // public 代理同步）——原「极简版 AAR 手动下载」待办作废（15.8.42 全量仅 460KB，
    // consumer proguard 随 AAR 自动生效；回跳接收器 AlipayResultActivity 声明在 AAR
    // manifest 内，走显式组件不占 sihaishumeng scheme）。
    implementation("com.alipay.sdk:alipaysdk-android:15.8.42")
    // 批⑧ B站 accountoauth SDK：无 maven 分发，官方 zip 手动 AAR 入 libs（24.9KB；
    // consumer proguard.txt 随 AAR 自动生效）。拉起=隐式 VIEW bilibili.oauth://main
    // （queries 须 scheme 形态）；未装B站客户端自兜 OauthWebActivity（H5 授权页，
    // AAR manifest 自带声明）。SDK 出包 gate 在 union_id 探针复验通过之后。
    implementation(files("libs/accountoauth-2.0.0.0.aar"))
}
