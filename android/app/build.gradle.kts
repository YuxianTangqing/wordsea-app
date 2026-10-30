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
        versionCode = 15
        versionName = "2.0.0"
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
    // 批④（2026-10-30）：T1 原生页两件——RecyclerView 列表体系 + Coil 图片加载
    // （2.x 非 3.x：3.x 要 2.x 工具链语法重写；2.7 与 OkHttp 4.12 同轴，consumer
    // proguard 自带，封面/头像量级不必手写 LruCache）。
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.7.0")
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
