# wordsea-app：无注解处理器、无反射依赖——除 JS 桥外零 keep。
# 出现混淆炸点再回来补（对照 logcat 的 ClassNotFoundException 行）。
# 批③ SDK 进包后：各家 SDK 的官方 keep 规则在此增补（微信/QQ/支付宝官方文档各带）。

# __wsShell JS 桥：WebView 靠 RUNTIME 注解识别可暴露方法——显式保留
# （R8 对 @JavascriptInterface 有内置处理，此规则兜底防 optimize 挡剥离；幂等无害）
# 验证走 dex 二进制 grep（dexdump 不显示注解=假阴性陷阱）
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 批③ 微信 OpenSDK（官方 keep 规则；wxop 是 opensdk 前身包名兼容保留）
-keep class com.tencent.mm.opensdk.** { *; }
-keep class com.tencent.wxop.** { *; }
# 批③ QQ open-sdk（社区通用规则——jar 无 proguard.cfg 附件须手 keep）
-keep class com.tencent.open.** { *; }
-keep class com.tencent.tauth.** { *; }
-dontwarn com.tencent.**
# 批③ okhttp/okio：R8 自带规则，仅消预警
-dontwarn okhttp3.**
-dontwarn okio.**
