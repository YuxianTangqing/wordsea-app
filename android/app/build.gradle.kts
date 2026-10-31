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
        versionCode = 21
        versionName = "2.0.6"
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
}
