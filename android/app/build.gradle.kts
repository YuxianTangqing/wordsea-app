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
    // 批③起按需增补：recyclerview / okhttp / 微信 opensdk / QQ open-sdk / 支付宝
    // OpenAuth 精简版（体积预算详见仓 CLAUDE.md——勿一次性全加）
}
