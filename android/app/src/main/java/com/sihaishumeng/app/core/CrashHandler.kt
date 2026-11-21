package com.sihaishumeng.app.core

import android.content.Context
import android.os.Build
import com.sihaishumeng.app.core.api.ApiClient
import com.sihaishumeng.app.core.tts.TtsSessionManager // M379 诊断磁带（崩溃文件附带）
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M376 崩溃兜底（站点盲区根治）：APP 此前零崩溃上报、闪退完全不可见。
 *
 * install() 挂全局 UncaughtExceptionHandler——任何线程（含 binder 桥线程）未捕获
 * 异常先落盘 filesDir/crash-logs/（栈+机型+系统+APP 版本），再交回旧 handler 让
 * 进程正常死；写盘全程 try/catch 绝不二崩。
 *
 * pendingCrashes() 启动扫描待反馈文件（顺手清 7 天外/超 10 个）；send() 经既有
 * /api/v1/feedback（分类=故障问题，摘要 ≤480 字适配端点 500 字上限）发回站里，
 * 管理后台「举报与反馈」直接可见；成功转 .done、失败/忽略转 .asked（不再重复弹窗，
 * 文件保留可手动导出）。UI 弹窗由 MainActivity 承担（core 与 UI 解耦铁律）。
 */
object CrashHandler {

    private const val DIR = "crash-logs"
    private const val MAX_KEEP = 10
    private const val KEEP_MS = 7 * 24 * 3600_000L

    private var installed = false

    /** MainActivity.onCreate 调用一次（进程级单例）。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appCtx = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeLog(appCtx, t, e) }
            prev?.uncaughtException(t, e) // 交回系统默认：进程正常死
        }
    }

    /** 启动扫描：待反馈的未处理崩溃文件（最新在前）；顺手清过期/超量旧件。 */
    fun pendingCrashes(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory) return emptyList()
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val all = (dir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }
        all.forEachIndexed { i, f ->
            if (f.lastModified() < cutoff || i >= MAX_KEEP) runCatching { f.delete() }
        }
        return all.filter { !it.name.endsWith(".done") && !it.name.endsWith(".asked") && it.exists() }
    }

    /** 用户选择不发送/发送失败：标记不再弹窗（文件保留可手动导出）。 */
    fun markAsked(file: File) {
        runCatching { file.renameTo(File(file.parentFile, file.name + ".asked")) }
    }

    /** 发送摘要进站内反馈（登录 cookie 由 ApiClient 自带）；cb(ok) 永远主线程。 */
    fun send(file: File, cb: (Boolean) -> Unit) {
        val content = digest(file)
        ApiClient.postJson(
            "/api/v1/feedback",
            JSONObject().apply {
                put("category", "故障问题")
                put("content", content)
                put("pagePath", "/app-crash")
                put("device", "MOBILE")
            }
        ) { code, _ ->
            if (code == 200) {
                runCatching { file.renameTo(File(file.parentFile, file.name + ".done")) }
                cb(true)
            } else {
                markAsked(file)
                cb(false)
            }
        }
    }

    /** 摘要 ≤480 字：异常类名+信息+前若干帧+机型/系统（端点 content 上限 500 字）。 */
    fun digest(file: File): String {
        val raw = runCatching { file.readText() }.getOrDefault("")
        val head = raw.lines().take(12).joinToString("\n")
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        return "[APP崩溃] $head\n设备: $device".take(480)
    }

    private fun writeLog(context: Context, t: Thread, e: Throwable) {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        // M379：听书诊断磁带（暂停失效定位器）——崩溃文件附带最近 200 条会话/引擎流水
        val tapePath = TtsSessionManager.persistTape()
        val tapeText = tapePath?.let { p ->
            runCatching { File(p).readText() }.getOrDefault("（磁带不可读）")
        } ?: ""
        val body = buildString {
            appendLine("时间: ${fmt.format(Date())}")
            appendLine("线程: ${t.name}")
            appendLine("APP: ${appVersion(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("---- 异常栈 ----")
            append(sw.toString())
            if (tapeText.isNotBlank()) {
                appendLine()
                appendLine("---- 听书磁带 ----")
                append(tapeText)
            }
        }
        File(dir, "crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
            .writeText(body)
    }

    private fun appVersion(context: Context): String = runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION") "${pkg.versionName} (${pkg.versionCode})"
    }.getOrDefault("?")
}
