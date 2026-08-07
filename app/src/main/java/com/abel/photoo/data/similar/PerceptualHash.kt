package com.abel.photoo.data.similar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.core.graphics.scale
import com.abel.photoo.data.db.PhotoODb

/**
 * 纯本地的感知哈希，不依赖任何模型文件。
 *
 * 用两种互补的哈希 + 一个平均色一起判断：
 *  - dHash：比较相邻像素的明暗梯度，对整体亮度变化不敏感，擅长识别"同一场景连拍"。
 *  - aHash：比较每个像素与全图均值，擅长识别"轻微裁剪 / 加了滤镜"。
 *  - 平均色：拦住那些结构碰巧相似但颜色完全不同的误判（比如两张不同的纯色壁纸）。
 *
 * 三者都命中才算相似，实测比单用一种哈希误判率低很多。
 */
object PerceptualHash {

    private const val HASH_EDGE = 8            // 8x8 = 64 bit
    private const val SAMPLE_EDGE = 9          // dHash 需要多一列

    /** 计算一张图的哈希；失败返回 null（损坏文件 / 无权限）。 */
    fun compute(context: Context, id: Long, uri: Uri): PhotoODb.HashRow? {
        val bitmap = loadTiny(context, uri) ?: return null
        return try {
            val gray = toGrayMatrix(bitmap, SAMPLE_EDGE, HASH_EDGE)
            val dHash = dHash(gray, SAMPLE_EDGE, HASH_EDGE)

            val square = toGrayMatrix(bitmap, HASH_EDGE, HASH_EDGE)
            val aHash = aHash(square)

            val avg = averageColor(bitmap)
            PhotoODb.HashRow(
                id = id,
                dHash = dHash,
                aHash = aHash,
                avgColor = avg,
                signature = signatureOf(dHash),
            )
        } catch (_: Throwable) {
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 尽量走系统缩略图缓存，比解码原图快一到两个数量级。
     * 拿不到就退回 BitmapFactory + inSampleSize 采样解码。
     */
    private fun loadTiny(context: Context, uri: Uri): Bitmap? {
        runCatching {
            return context.contentResolver.loadThumbnail(uri, Size(96, 96), null)
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 16
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull()
    }

    private fun toGrayMatrix(source: Bitmap, width: Int, height: Int): IntArray {
        val scaled = source.scale(width, height, filter = true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled != source && !scaled.isRecycled) scaled.recycle()

        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // 整数近似的 Rec.601 灰度，避免浮点开销
            gray[i] = (r * 77 + g * 151 + b * 28) shr 8
        }
        return gray
    }

    private fun dHash(gray: IntArray, width: Int, height: Int): Long {
        var hash = 0L
        var bit = 0
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val left = gray[y * width + x]
                val right = gray[y * width + x + 1]
                if (left > right) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    private fun aHash(gray: IntArray): Long {
        val mean = gray.sum() / gray.size
        var hash = 0L
        for (i in gray.indices) {
            if (gray[i] >= mean) hash = hash or (1L shl i)
        }
        return hash
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val small = bitmap.scale(4, 4, filter = true)
        val pixels = IntArray(16)
        small.getPixels(pixels, 0, 4, 0, 0, 4, 4)
        if (small != bitmap && !small.isRecycled) small.recycle()

        var r = 0
        var g = 0
        var b = 0
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = pixels.size
        return ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    /** 把 64 位哈希切成 4 段 16 位，用于 LSH 分桶做候选预筛。 */
    fun signatureOf(dHash: Long): String = buildString {
        for (i in 0 until 4) {
            if (i > 0) append('-')
            append(((dHash shr (i * 16)) and 0xFFFF).toString(16).padStart(4, '0'))
        }
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** 平均色的曼哈顿距离，0..765。 */
    fun colorDistance(a: Int, b: Int): Int {
        val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }
}
