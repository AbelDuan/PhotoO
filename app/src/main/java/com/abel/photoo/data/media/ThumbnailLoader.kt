package com.abel.photoo.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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

/**
 * 缩略图目标边长常量。取 360：够 5~6 列的高分屏网格保持清晰，
 * 又比 420 少解码约 26% 的像素、显存占用更低，长列表来回滚动时缓存能容纳更多张、
 * 减少重复解码带来的卡顿。
 */
object Thumbs {
    const val TARGET = 360
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
            // 视频走视频缩略图表，取不到再用 MediaMetadataRetriever 抽一帧（可靠兜底，
            // 视频不能用 BitmapFactory 解码，所以视频路径绝不再进 decodeSampled）；
            // 图片走图片缩略图表 + 下采样兜底。
            val bmp = if (isVideo()) videoThumbnail() ?: videoFrame()
            else systemThumbnail() ?: decodeSampled()
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

    /** 视频内容 Uri 形如 content://.../video/media/<id>，据此区分视频与图片。 */
    private fun isVideo(): Boolean = data.uri.toString().contains("/video/", ignoreCase = true)

    /** 走视频缩略图表（API 兼容的静态方法），拿不到返回 null。 */
    private fun videoThumbnail(): Bitmap? {
        val id = runCatching { ContentUris.parseId(data.uri) }.getOrNull() ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null,
            )
        }.getOrNull()
    }

    /**
     * 兜底：系统视频缩略图表取不到时，用 MediaMetadataRetriever 抽一帧作为封面。
     * 这是视频缩略图最终可靠的来源——视频无法用 BitmapFactory 解码，
     * 之前回退到 decodeSampled 会导致 fetch() 抛异常、视频在网格里变空白。
     */
    private fun videoFrame(): Bitmap? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, data.uri)
                // 取靠近开头的第一帧同步点，避开纯黑开场；拿不到再退回首帧。
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
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
