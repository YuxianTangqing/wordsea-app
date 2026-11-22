package com.sihaishumeng.app.core.download

import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.sihaishumeng.app.R
import com.sihaishumeng.app.core.SiteConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * M382 长按图片保存（站主报障「App 内无法像手机浏览器一样长按保存图片」）：
 * web 长按两键菜单「保存图片」经 __wsShell.saveImage 转来（宿主 MainActivity 转发）。
 * - host 白名单双保险（桥面已拦，本处照拦——SiteConfig.SITE_HOSTS，与
 *   SelfUpdateController 同口径）；
 * - API 29+：OkHttp 下载字节 → MediaStore.Images 插入 `Pictures/四海书盟`（IS_PENDING
 *   置位→写字节→清位；失败删残行）——零权限、相册立即可见；
 * - API ≤28：WRITE_EXTERNAL_STORAGE 运行时授权（manifest maxSdkVersion=28 声明；
 *   launcher 由宿主注册传入）→ DownloadManager 公共 Pictures（雷电 Android 9 实测
 *   坑：公共目录必带此权限，SelfUpdateController 类注释①同源）；
 * - 结果一律本类 toast（binder 线程→runOnUiThread）——web 侧只对「受理失败」toast，
 *   双端不重不漏。命名铁律（ShellBridge 类注释）：参数名不得与方法/桥方法同名。
 */
object ImageSaver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private class Pending(val url: String, val fileName: String?)

    private var pending: Pending? = null

    /** 入口（宿主已切主线程）：权限齐→后台下载入库；≤28 缺权限→挂起等授权回执 */
    fun save(activity: Activity, url: String, fileName: String?, permLauncher: ActivityResultLauncher<String>) {
        val uri = Uri.parse(url)
        if (uri.host?.lowercase() !in SiteConfig.SITE_HOSTS) return
        if (Build.VERSION.SDK_INT < 29 &&
            activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = Pending(url, fileName)
            permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        thread(name = "image-save") {
            val ok = runSave(activity, uri, fileName)
            activity.runOnUiThread { toast(activity, ok) }
        }
    }

    /** 授权回执（launcher 回调转来）：拒绝→如实提示；通过→继续保存 */
    fun onPermissionResult(activity: Activity, granted: Boolean) {
        val p = pending ?: return
        pending = null
        if (!granted) {
            Toast.makeText(activity, R.string.image_save_permission_denied, Toast.LENGTH_SHORT).show()
            return
        }
        thread(name = "image-save") {
            val ok = runSave(activity, Uri.parse(p.url), p.fileName)
            activity.runOnUiThread { toast(activity, ok) }
        }
    }

    private fun toast(activity: Activity, ok: Boolean) {
        Toast.makeText(
            activity,
            activity.getString(if (ok) R.string.image_saved else R.string.image_save_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 下载+入库；不抛（异常=失败如实提示） */
    private fun runSave(activity: Activity, uri: Uri, fileName: String?): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) saveViaMediaStore(activity, uri, fileName)
            else saveViaDownloadManager(activity, uri, fileName)
        } catch (e: Exception) {
            Log.e("ImageSaver", "save", e)
            false
        }
    }

    /** API 29+：MediaStore 插入（零权限）；IS_PENDING 置位→写字节→清位，失败删残行 */
    private fun saveViaMediaStore(context: Context, uri: Uri, fileName: String?): Boolean {
        val (bytes, contentType) = download(uri)
        val mime = resolveMime(uri, contentType)
        val name = sanitizeName(fileName, uri, mime)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/四海书盟")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val item = resolver.insert(collection, values) ?: return false
        return try {
            val out = resolver.openOutputStream(item) ?: throw IOException("openOutputStream null")
            out.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(item, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(item, null, null)
            throw e
        }
    }

    /** API ≤28：DownloadManager 公共 Pictures（权限已在入口确保；通知栏可见下载进度） */
    private fun saveViaDownloadManager(context: Context, uri: Uri, fileName: String?): Boolean {
        val name = sanitizeName(fileName, uri, resolveMime(uri, null))
        val request = DownloadManager.Request(uri).apply {
            setMimeType(resolveMime(uri, null))
            addRequestHeader("User-Agent", SiteConfig.DESKTOP_UA)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        return true
    }

    private fun download(uri: Uri): Pair<ByteArray, String?> {
        val req = Request.Builder().url(uri.toString())
            .header("User-Agent", SiteConfig.DESKTOP_UA)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            return body.bytes() to resp.header("Content-Type")
        }
    }

    // mime：响应头 image 类型优先 → 扩展名映射 → image/jpeg 兜底
    // （Kotlin 块注释可嵌套——注释里禁写「星号斜杠」序列，会把后面全吞）
    private fun resolveMime(uri: Uri, contentType: String?): String {
        if (!contentType.isNullOrBlank() && contentType.startsWith("image/")) {
            return contentType.substringBefore(';').trim()
        }
        return when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    /** 文件名：入参 → URL 末段 → 时间戳兜底；去路径分隔/控制符、限长、补扩展名 */
    private fun sanitizeName(fileName: String?, uri: Uri, mime: String): String {
        val raw = fileName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "图片_${System.currentTimeMillis()}"
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "").take(80)
            .ifBlank { "图片_${System.currentTimeMillis()}" }
        if (clean.contains('.')) return clean
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return "$clean.$ext"
    }
}
