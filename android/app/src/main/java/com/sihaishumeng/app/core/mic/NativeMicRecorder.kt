package com.sihaishumeng.app.core.mic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * 五十五批 App 原生录音链（站主 OPPO 安卓 11 真机：壳内 WebView getUserMedia 恒
 * NotReadableError——浏览器能录=系统层健康、WebView 采集层坏；绕开整层，AudioRecord
 * 直录 16kHz 单声道 16bit PCM，录完手写 WAV 头供网页分块拉取上传）。
 *
 * 线程模型：录音循环跑专用线程；控制方法任意线程可调（lock 同步 + 状态闸）；电平/
 * 时长/分块为快照式读。铁律：任何路径 try/finally release 录音器——绝不把麦克风
 * 留给本 App（2.0.22 探针无硬超时卡死占麦=站主「APP 自己在用」直觉的教训）。
 *
 * 状态机：idle → recording ⇄ paused → stopped(ok) / error:code / cancelled。
 * 产物：WAV（44 字节头+int16 PCM，与 web encodeWav16kMono 同格式）；leadMs 同款
 * 检测（20ms 窗 RMS 0.02 阈值、连续 2 窗确认、-60ms 回退、cap 3000；0=无静音；
 * null=检测异常→听书「自动掐头」档回落固定档）。
 */
class NativeMicRecorder(private val maxMs: Int) {

    companion object {
        const val SAMPLE_RATE = 16000
        /** 分块拉取单片原始字节上限（base64 后 ≈682KB<Binder 1MB 事务上限） */
        private const val CHUNK_BYTES = 384 * 1024
    }

    private val lock = Object()
    private var state = "idle" // idle|recording|paused|stopped|error:…|cancelled
    private var rec: AudioRecord? = null
    private var stopRequested = false // stop/自动停 → 终局 WAV
    private var cancelRequested = false // 放弃 → 弃数据
    private var data = ByteArrayOutputStream()
    private var level = 0.0
    private var durationMs = 0
    private var leadMs: Int? = null
    private var chunks: Array<String>? = null
    // 32KB/s×时长+余量（16kHz mono 16bit）
    private val maxBytes = (maxMs.toLong() * 32000 / 1000 + 64 * 1024).toInt()

    /** 开始录音（仅 idle）。true=已进入 recording（初始化在录音线程异步进行，失败走 error:…） */
    fun start(): Boolean {
        synchronized(lock) {
            if (state != "idle") return false
            state = "recording"
        }
        Thread({ recordLoop() }, "ws-mic-rec").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun pause() {
        synchronized(lock) {
            if (state != "recording") return
            state = "paused"
            lock.notifyAll()
        }
    }

    fun resume() {
        synchronized(lock) {
            if (state != "paused") return
            state = "recording"
            lock.notifyAll()
        }
    }

    /** 请求收尾（异步：录音线程归拢数据后进 stopped） */
    fun stop() {
        synchronized(lock) {
            if (state == "recording" || state == "paused") {
                stopRequested = true
                lock.notifyAll()
            }
        }
    }

    /** 放弃（异步：丢数据进 cancelled） */
    fun cancel() {
        synchronized(lock) {
            if (state == "recording" || state == "paused") {
                cancelRequested = true
                lock.notifyAll()
            }
        }
    }

    /** 宿主看门狗强杀（初始化卡死兜底；release 线程安全可解除阻塞中的 read） */
    fun forceError(code: String) {
        synchronized(lock) {
            if (state == "recording" || state == "paused") {
                state = "error:$code"
                cancelRequested = true
                runCatching { rec?.release() }
                lock.notifyAll()
            }
        }
    }

    fun state(): String = synchronized(lock) { state }

    /** 最近 100ms 片电平（0..1；暂停期冻结） */
    fun level(): Double = synchronized(lock) { level }

    /** 已录毫秒（纯录音口径——暂停段不计） */
    fun durationMs(): Int = synchronized(lock) { durationMs }

    /** 开头静音实测（仅 stopped 后有效；-1=无效/null） */
    fun leadMs(): Int = synchronized(lock) { leadMs ?: -1 }

    fun chunkCount(): Int = synchronized(lock) { chunks?.size ?: 0 }

    /** 分块拉取（0..chunkCount-1；越界/未就绪=空串） */
    fun chunk(i: Int): String = synchronized(lock) { chunks?.getOrNull(i) ?: "" }

    // ══ 录音线程（唯一持有 AudioRecord 读写方） ══

    private fun recordLoop() {
        var ar: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf * 2, SAMPLE_RATE / 10 * 2) // ≥200ms 缓冲
            // 构造可能阻塞（个别 ROM 音频服务卡死态）——宿主 10s 看门狗兜底
            val created = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            ar = created
            synchronized(lock) { rec = created }
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                setError("init")
                return
            }
            created.startRecording()
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                setError("start")
                return
            }
            val buf = ShortArray(SAMPLE_RATE / 10) // 100ms 片
            while (true) {
                synchronized(lock) {
                    if (cancelRequested) return
                    if (stopRequested) return
                    if (state == "paused") {
                        runCatching { created.stop() }
                        while (true) {
                            lock.wait(200)
                            if (cancelRequested || stopRequested) return
                            if (state == "recording") break
                        }
                        runCatching { created.startRecording() }
                    }
                }
                val n = created.read(buf, 0, buf.size)
                if (n < 0) {
                    setError("read$n")
                    return
                }
                // 电平（与 web 同款：RMS×3 钳 1）
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i].toDouble() / 32768.0
                    sum += s * s
                }
                synchronized(lock) {
                    level = minOf(1.0, sqrt(sum / n) * 3)
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        bytes[i * 2] = (v and 0xff).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                    }
                    data.write(bytes, 0, bytes.size)
                    durationMs = data.size() / 2 * 1000 / SAMPLE_RATE
                    if (data.size() >= maxBytes) {
                        stopRequested = true // 到上限自动收（口径=纯录音时长）
                    }
                }
            }
        } catch (e: Exception) {
            setError("exc:${e.javaClass.simpleName}")
        } finally {
            runCatching { ar?.release() }
            synchronized(lock) {
                rec = null
                when {
                    state.startsWith("error") -> {
                        /* 错误态保持（含 setError 与 forceError） */
                    }
                    cancelRequested -> state = "cancelled"
                    stopRequested -> state = if (finalizeWavLocked()) "stopped" else "error:finalize"
                    else -> state = "cancelled" // 异常逃逸兜底
                }
            }
        }
    }

    private fun setError(code: String) {
        synchronized(lock) {
            state = "error:$code"
            cancelRequested = true
            lock.notifyAll()
        }
    }

    /** 终局：手写 WAV 头 + leadMs 检测 + base64 分块（须持 lock）；false=终局失败 */
    private fun finalizeWavLocked(): Boolean {
        return try {
            val pcm = data.toByteArray()
            val w = ByteArray(44 + pcm.size)
            val ascii = { off: Int, s: String -> s.forEachIndexed { i, c -> w[off + i] = c.code.toByte() } }
            ascii(0, "RIFF")
            writeU32(w, 4, 36L + pcm.size)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            writeU32(w, 16, 16L) // fmt 块长
            writeU16(w, 20, 1) // PCM
            writeU16(w, 22, 1) // 单声道
            writeU32(w, 24, SAMPLE_RATE.toLong())
            writeU32(w, 28, (SAMPLE_RATE * 2).toLong()) // byteRate
            writeU16(w, 32, 2) // blockAlign
            writeU16(w, 34, 16) // bitsPerSample
            ascii(36, "data")
            writeU32(w, 40, pcm.size.toLong())
            System.arraycopy(pcm, 0, w, 44, pcm.size)
            leadMs = detectLeadMs(pcm)
            // 分块（≤384KB/片 → base64 ≤512KB）
            val list = ArrayList<String>()
            var off = 0
            while (off < w.size) {
                val end = minOf(off + CHUNK_BYTES, w.size)
                list.add(Base64.encodeToString(w, off, end - off, Base64.NO_WRAP))
                off = end
            }
            chunks = list.toTypedArray()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 开头静音检测（与 web detectLeadMs 同算法：20ms 窗 RMS>0.02、连续 2 窗确认、
     *  -60ms 回退、cap 3000；整段无起音=0；异常=null） */
    private fun detectLeadMs(pcm: ByteArray): Int? {
        try {
            val n = pcm.size / 2
            val win = SAMPLE_RATE * 20 / 1000 // 320 采样
            var onset = -1
            var i = 0
            while (i + win <= n) {
                var sum = 0.0
                for (j in i until i + win) {
                    val lo = pcm[j * 2].toInt() and 0xff
                    val hi = pcm[j * 2 + 1].toInt()
                    val s = ((hi shl 8) or lo).toShort().toDouble() / 32768.0
                    sum += s * s
                }
                if (sqrt(sum / win) > 0.02) {
                    if (onset < 0) {
                        onset = i
                        i += win
                        continue // 第一窗记位，待下一窗确认
                    }
                    return minOf(3000, maxOf(0, onset * 1000 / SAMPLE_RATE - 60))
                }
                onset = -1
                i += win
            }
            return 0
        } catch (_: Exception) {
            return null
        }
    }

    private fun writeU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte()
        b[off + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
    }
}
