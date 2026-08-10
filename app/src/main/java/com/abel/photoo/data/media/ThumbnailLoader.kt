package com.abel.photoo.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.ImageLoader
import okio.*
import java.io.File
import java.io.IOException

/**
 * 网格/封面用的缩略图请求。把"原图 Uri + 目标边长"打包交给自定义 Fetcher，
 * 由它在后台取一张小而快的缩略图，避免每次都解码整张原图。
 */
data class ThumbRequest(
    val uri: Uri,
    /** 目标边长（px）。 */
    val size: Int,
)

/** 缩略图目标边长常量：略大于常见网格单元格，保证高分屏也清晰。 */
object Thumbs {
    const val TARGET = 420
}

/**
 * 缩略图 Fetcher。
 *
 * 取图策略（全部在 Coil 的 IO 线程执行，不阻塞主线程）：
 * 1. 优先写入/读取应用缓存目录里的缩略图文件 `cacheDir/thumbs/<key>.jpg`。
 *    这个文件跨启动持久存在 —— 所以"退出重进"不需要重新解码原图，直接读小文件，秒出、不卡。
 * 2. 文件不存在时，先尝试系统缩略图表（MediaStore.Images.Thumbnails.getThumbnail），
 *    拿不到再按目标尺寸下采样解码原图，压缩成 JPEG 写进缓存文件，后续复用。
 */
class ThumbnailFetcher(
    private val context: Context,
    private val data: ThumbRequest,
) : Fetcher {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun fetch(): SourceFetchResult {
        val file = thumbFile()
        if (!file.exists()) {
            val bmp = systemThumbnail() ?: decodeSampled()
            val bitmap = bmp ?: throw IOException("无法生成缩略图 ${data.uri}")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        val src: BufferedSource = file.source().buffer()
        return SourceFetchResult(
            source = ImageSource(src, FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    /** 走系统缩略图表（API 兼容的静态方法），拿不到返回 null。 */
    private fun systemThumbnail(): Bitmap? {
        val id = runCatching { ContentUris.parseId(data.uri) }.getOrNull() ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null,
            )
        }.getOrNull()
    }

    /** 回退：直接按目标尺寸下采样解码原图，控制内存占用。 */
    private fun decodeSampled(): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(data.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        opts.inSampleSize = maxOf(1, (maxOf(w, h) / data.size).toInt())
        opts.inJustDecodeBounds = false
        opts.inPreferredConfig = Bitmap.Config.RGB_565
        return resolver.openInputStream(data.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** 稳定的缓存文件名：同一张图 + 同一尺寸只会生成一次。 */
    private fun thumbFile(): File {
        val key = "%x_${data.size}".format(data.uri.toString().hashCode())
        return File(context.cacheDir, "thumbs/$key.jpg")
    }
}

/** 把 [ThumbRequest] 交给 [ThumbnailFetcher] 处理。 */
class ThumbnailFetcherFactory(private val context: Context) : Fetcher.Factory<ThumbRequest> {
    override fun create(
        data: ThumbRequest,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher = ThumbnailFetcher(context, data)
}
