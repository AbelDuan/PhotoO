package com.abel.photoo.data.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量文件日志：Logcat + 私有外部目录文件双写。
 *
 * 为什么需要：Live Photo 识别/播放、高德地图、删除等链路在真机上才能复现，
 * 没有 IDE 时只能靠日志文件定位。文件写在 getExternalFilesDir(null)/logs/photoo.log，
 * 不需要任何权限；超过上限时截掉前半段继续写（环形覆盖）。
 * 设置页「导出日志」可把文件复制到系统下载目录分享出去。
 *
 * 是否落盘由 [enabled] 控制（对应设置里的「记录调试日志」开关）：
 * 关闭时只保留 Logcat 输出、不写文件，并且立即清空旧日志文件，
 * 保证导出的内容只反映开关打开之后产生的记录。
 */
object PhotoLog {

    /** 单文件上限：512KB，够跑很久，也不会占太多空间。 */
    private const val MAX_BYTES = 512 * 1024

    private var dir: File? = null

    /** 是否把日志写入文件。默认关，由设置开关控制。Logcat 输出不受此影响。 */
    @Volatile
    private var enabled = false

    fun init(context: Context, enabled: Boolean = false) {
        if (dir != null) return
        val d = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "logs",
        )
        d.mkdirs()
        dir = d
        this.enabled = enabled
    }

    /** 当前是否正在记录日志到文件。 */
    fun isEnabled(): Boolean = enabled

    /** 开关日志落盘。关闭时立即清空已有日志文件，避免导出到陈旧内容。 */
    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            enabled = on
            if (!on) runCatching { file()?.writeBytes(ByteArray(0)) }
        }
    }

    /** 当前日志文件；未初始化或创建失败返回 null。 */
    fun file(): File? = dir?.let { File(it, "photoo.log") }

    fun i(tag: String, msg: String) = write('I', tag, msg)
    fun w(tag: String, msg: String) = write('W', tag, msg)
    fun e(tag: String, msg: String) = write('E', tag, msg)

    private val lock = Any()

    private fun write(level: Char, tag: String, msg: String) {
        if (level == 'E') Log.e("PhotoO", "[$tag] $msg")
        else if (level == 'W') Log.w("PhotoO", "[$tag] $msg")
        else Log.i("PhotoO", "[$tag] $msg")
        // 开关关闭时不落盘。
        if (!enabled) return
        val f = file() ?: return
        val line = "${ts()} $level/$tag: $msg\n"
        synchronized(lock) {
            runCatching {
                if (f.length() + line.length > MAX_BYTES) {
                    // 环形覆盖：砍掉前半段，保留最近一半再接新行。
                    val keep = f.readBytes()
                    val tail = if (keep.size <= MAX_BYTES / 2) keep
                    else keep.copyOfRange(keep.size / 2, keep.size)
                    f.writeBytes(tail + line.toByteArray())
                } else {
                    f.appendBytes(line.toByteArray())
                }
            }
        }
    }

    private fun ts(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
