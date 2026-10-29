// wordsea-app 原生工程（批② 2026-10-30 起线）：底座克隆自轻壳仓——版本组合取自
// 本机多次出包实证缓存（Gradle 9.3.1 + JDK17）
// ⚠️ AGP 9.x 硬性要求 JDK 21——勿顺手升级（灶台是 build-tools\jdk17）
plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}
