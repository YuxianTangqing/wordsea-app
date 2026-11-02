package com.sihaishumeng.app.core.tts

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 五十五批 开头静音真跳过：零 seek 剪裁器。
 * 2.0.22 的「起播前 seekTo」在站主设备上毁播放（2.0.20 webm seek 同款实锤；本轮
 * 判别实验「不跳过能播、跳过档不播」=seek 动作是唯一变量）——本件把「跳过」做成
 * 下载+纯字节剪裁：WAV 头带精确时长，PCM 数据按 fmt 块 byteRate 折算剪掉前
 * skipMs 毫秒、改写 RIFF/data 尺寸字段，产出本地文件从 0 播。播放链物理上无
 * seek——任何设备任何 ROM 都不可能再「跳过档不播放」。
 *
 * 失败恒返回 null（调用方回落播远端原件不跳——「出声不跳」铁律）。同步阻塞式
 * （网络 IO）——调用方必须置于后台线程。剪后保底 ≥100ms 内容（与 2.0.22 seek
 * 守卫同语义，防「剪到只剩半个音」空播）。
 */
object WavSlicer {

    /** 与服务端 WAV 顶格同档（10MB=5 分钟 16kHz mono 16bit 理论顶） */
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * @param skipMs 开头剪掉毫秒数（>0；调用方保证 ≤3000）
     * @return 剪裁后本地文件绝对路径；任何失败（网络/超长/格式意外/剪无可剪）null
     */
    fun downloadTrimmed(url: String, skipMs: Long, cacheDir: File?): String? {
        if (cacheDir == null || skipMs <= 0) return null
        val bytes = try {
            download(url) ?: return null
        } catch (_: Exception) {
            return null
        }
        val sliced = try {
            slice(bytes, skipMs) ?: return null
        } catch (_: Exception) {
            return null
        }
        val out = File(cacheDir, "ws-trim-${System.nanoTime()}.wav")
        return try {
            FileOutputStream(out).use { it.write(sliced) }
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun download(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return null
            val len = conn.contentLength
            if (len > MAX_BYTES) return null
            val buf = ByteArrayOutputStream(minOf(64 * 1024, if (len > 0) len else 64 * 1024))
            conn.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) return null
                }
            }
            return buf.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /** RIFF 头校验 → 沿 chunk 链找 fmt（byteRate）与 data → 剪掉前 skipMs 毫秒字节。 */
    private fun slice(b: ByteArray, skipMs: Long): ByteArray? {
        if (b.size < 44) return null
        val latin = Charsets.ISO_8859_1
        if (String(b, 0, 4, latin) != "RIFF" || String(b, 8, 4, latin) != "WAVE") return null
        var byteRate = 0L
        var dataOff = -1
        var dataSize = 0L
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, latin)
            val size = readU32(b, pos + 4)
            when (id) {
                // fmt 块数据偏移 8 处=byteRate（format2+channels2+sampleRate4 之后）
                "fmt " -> byteRate = readU32(b, pos + 16)
                "data" -> {
                    dataOff = pos
                    dataSize = size
                    break // data 通常在最后
                }
            }
            pos += 8 + size.toInt() + (size % 2).toInt()
        }
        if (byteRate <= 0 || dataOff < 0 || dataSize < 44) return null
        val skipBytes = skipMs * byteRate / 1000
        if (skipBytes <= 0) return b.copyOf() // 不足 1ms：原样不剪
        if (skipBytes >= dataSize) return null // 剪无可剪（防空播）
        // 剪后保底 ≥100ms 内容——否则回落播原样（调用方处理 null=不剪）
        if (dataSize - skipBytes < byteRate / 10) return null
        val dataContent = dataOff + 8 // data chunk 头（id+size）之后=数据起点
        val skip = skipBytes.toInt()
        val out = ByteArray(b.size - skip)
        // 头段（含 data 尺寸字段）原样复制
        System.arraycopy(b, 0, out, 0, dataContent)
        // 改写 data 尺寸=原尺寸-剪掉字节
        writeU32(out, dataOff + 4, dataSize - skipBytes)
        // data 内容跳过前 skip 字节
        System.arraycopy(b, dataContent + skip, out, dataContent, b.size - dataContent - skip)
        // 改写 RIFF 总尺寸（offset 4）=原值-剪掉字节
        val riffSize = readU32(b, 4)
        if (riffSize >= skipBytes) writeU32(out, 4, riffSize - skipBytes)
        return out
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)

    private fun writeU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte()
        b[off + 3] = ((v shr 24) and 0xff).toByte()
    }
}
