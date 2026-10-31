package com.sihaishumeng.app.core.update

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.SiteConfig
import java.io.File

/**
 * 壳内自更新链（M-APP15；批②自壳迁出）：本站 APK 下载 → 完成自动弹系统安装器。
 *
 * DownloadListener 圈定更新包：host∈本站 + 本站 APK 文件名族（稳定名
 * `sihaishumeng-app.apk` 与批⑨R 起的版本名直链 `sihaishumeng-app-v*.apk`）——
 * URL `?v=` ≤ 本机 versionName 时 Toast「已是最新版本」不下载；否则 DownloadManager
 * 下到 app 私有外部目录 `files/Download/`，完成广播（必须 RECEIVER_EXPORTED）后
 * 自动弹系统安装器；后台完成由 onResume 补弹（宿主在 onResume 调 [tryStartInstall]）。
 * 两个实测坑（雷电 Android 9，勿回头踩）：
 * ①Android≤9 的 DownloadManager 对公共 Downloads 目的地要 WRITE_EXTERNAL_STORAGE
 *   运行时权限=enqueue 被系统侧静默拒绝——更新包必须走私有目录；
 * ②完成广播必须 RECEIVER_EXPORTED：DownloadProvider 是普通 app uid，NOT_EXPORTED
 *   的动态权限墙连它一起挡（Permission Denial 实锤、安装器永不弹）。
 * 其他下载（工作室导出/admin 备份/CSV）仍走公共 Downloads 原样（Android≤9 上的
 * 存量权限坑与本链无关，不扩面）。
 */
class SelfUpdateController(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
) {
    private var pendingDownloadId = -1L        // 我们 enqueue 的更新包 id（-1=无进行中/待安装）
    private var pendingApkName: String? = null // DownloadManager 目的地文件名（enqueue 时记下）
    private var downloadReceiver: BroadcastReceiver? = null

    fun createDownloadListener(): DownloadListener =
        DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val uri = Uri.parse(url)
            // 圈定更新包：本站域＋本站 APK 文件名（防误伤站内其它 attachment 下载）。
            // ⭐批⑨R6：批⑨R 把 apkPath 改版本名直链（sihaishumeng-app-v2.0.8.apk）后
            // 原精确等（==稳定槽固定名）断链=站主 2.0.7→2.0.8 真机实录「下载了不自动
            // 弹安装器」——下载被当普通附件走公共目录+不注册完成广播。放宽为前缀族匹配：
            // 稳定名 sihaishumeng-app.apk 与版本名直链 sihaishumeng-app-v*.apk 双吃
            val seg = uri.lastPathSegment ?: ""
            val isUpdateApk = uri.host?.lowercase() in SiteConfig.SITE_HOSTS &&
                (seg == "sihaishumeng-app.apk" ||
                    (seg.startsWith("sihaishumeng-app-v") && seg.endsWith(".apk")))
            if (isUpdateApk) {
                if (pendingDownloadId != -1L) { // 重复点击去重
                    Toast.makeText(activity, R.string.download_in_progress, Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }
                val target = uri.getQueryParameter("v")
                val local = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }
                // 目标 ≤ 本机＝已是最新不下载；缺 ?v=（异常直链）不拦，按更新走＝安全侧退化
                if (target != null && local.isNotEmpty() && compareVersions(target, local) <= 0) {
                    Toast.makeText(
                        activity, activity.getString(R.string.download_already_latest, local), Toast.LENGTH_SHORT
                    ).show()
                    return@DownloadListener
                }
            }
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(uri).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    if (isUpdateApk) {
                        // M-APP15：更新包落 app 私有外部目录（坑①见类注释）
                        setDestinationInExternalFilesDir(
                            activity, Environment.DIRECTORY_DOWNLOADS, name
                        )
                    } else {
                        // 其他下载（导出/备份/CSV）保持公共 Downloads＝用户要能找到文件
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    }
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                }
                val id = (activity.getSystemService(Activity.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                if (isUpdateApk) {
                    pendingDownloadId = id
                    pendingApkName = name
                    ensureDownloadReceiver()
                }
            } catch (_: Exception) {
                // 静默：下载失败不炸壳（与壳 1.1.9 行为一致）
            }
        }

    /** ACTION_DOWNLOAD_COMPLETE 对本 app 全部下载都会广播——按自己 enqueue 的 id 过滤。
     *  Activity 级注册（onDestroy 注销）：退出后无自动弹窗＝可接受降级（通知栏仍在，
     *  重进再点下载钮即重走链）。⚠️ 必须 RECEIVER_EXPORTED（坑②见类注释）。 */
    private fun ensureDownloadReceiver() {
        if (downloadReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != pendingDownloadId) return
                tryStartInstall()
            }
        }
        downloadReceiver = receiver
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /** 有待装更新包就弹系统安装器（幂等：弹出或终态即清 pending）。
     *  非 RESUMED 态不弹也不清——Android 10+ 后台 startActivity 被静默拦（不抛异常，
     *  无法靠 catch 判失败），留给 onResume 补发；STATUS_FAILED 清 pending 解锁重下。
     *  文件按 enqueue 时记下的目的地构造（nginx 头文件名固定 → guessFileName 确定），
     *  失配时按 id 反查 DownloadManager 兜底。 */
    fun tryStartInstall() {
        val id = pendingDownloadId
        val name = pendingApkName ?: return
        if (id == -1L) return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val dm = activity.getSystemService(Activity.DOWNLOAD_SERVICE) as DownloadManager
        val status = try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
            }
        } catch (_: Exception) {
            -1
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            if (status == DownloadManager.STATUS_FAILED) {
                pendingDownloadId = -1L
                pendingApkName = null
            }
            return // RUNNING/PAUSED/-1：等下一次广播或 resume
        }
        val constructed =
            File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return, name)
        val file = if (constructed.exists() && constructed.length() > 0) constructed else try {
            dm.getUriForDownloadedFile(id)?.path?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        }
        if (file == null) {
            pendingDownloadId = -1L
            pendingApkName = null
            return
        }
        try {
            val apkUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            // 弹出即清：幂等；用户取消安装器不再骚扰（系统通知栏仍是手动出口）
            pendingDownloadId = -1L
            pendingApkName = null
        } catch (_: Exception) {
            // 无安装器等极端环境：保留 pending，onResume 再试
        }
    }

    fun destroy() {
        downloadReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        downloadReceiver = null
        pendingDownloadId = -1L
        pendingApkName = null
    }

    companion object {
        /** 数值段版本比较（M-APP15）：'1.1.10' > '1.1.9'；非数值段按 0、缺段补 0。
         *  @return 负=a<b、0=相等、正=a>b */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x - y
            }
            return 0
        }
    }
}
