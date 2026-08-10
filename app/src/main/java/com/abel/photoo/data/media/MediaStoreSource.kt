package com.abel.photoo.data.media

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.abel.photoo.model.AlbumItem
import com.abel.photoo.model.PhotoItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 只读地把系统相册（含小米自带相册的所有目录）读成 [PhotoItem] / [AlbumItem]。
 *
 * 小米 HyperOS 的相册本质上仍然是标准 MediaStore + 目录分组，
 * 所以这里不需要任何厂商私有 API：DCIM/Camera、Pictures/WeiXin、
 * Pictures/Screenshots 等都会作为独立 bucket 出现。
 */
class MediaStoreSource(private val context: Context) {

    private val collection: Uri
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.ORIENTATION,
    )

    /** 读取全部图片。已被系统回收站隐藏的项默认不会返回。 */
    fun queryPhotos(): List<PhotoItem> {
        // 一次性把缩略图、Live Photo 视频都预先查好，避免在每行循环里再发查询。
        val thumbMap = queryThumbnailMap()
        val liveMap = queryLiveVideoMap()

        val out = ArrayList<PhotoItem>(512)
        val cursor = runCatching {
            context.contentResolver.query(collection, projection, null, null, null)
        }.getOrNull() ?: return emptyList()

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIdIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val takenIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val pathIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val orientationIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)

            while (c.moveToNext()) {
                try {
                    val id = c.getLong(idIdx)
                    val modifiedMs = (c.getLongOrNull(modifiedIdx) ?: 0L) * 1000L
                    val takenMs = c.getLongOrNull(takenIdx)?.takeIf { it > 0L } ?: modifiedMs
                    val relative = c.getStringOrNull(pathIdx).orEmpty()
                    val bucketId = c.getLongOrNull(bucketIdIdx) ?: relative.hashCode().toLong()
                    val bucketName = c.getStringOrNull(bucketNameIdx)
                        ?: relative.trim('/').substringAfterLast('/').ifEmpty { "根目录" }
                    val fullUri = ContentUris.withAppendedId(collection, id)
                    // 缩略图 uri：MediaStore 维护的 MINI_KIND 缩略图。注意 Thumbnails 表的
                    // _ID 是缩略图自身 id，必须用 image_id 反查，直接 withAppendedId(uri, imageId)
                    // 会指向不存在的行 → 整屏缩略图空白。查不到时退回全图，保证一定能显示。
                    val thumbUri = thumbMap[id] ?: fullUri
                    val name = c.getStringOrNull(nameIdx).orEmpty()
                    // Live Photo：同 bucket 下存在同名视频（IMG_1234.HEIC + IMG_1234.MOV）。
                    val stem = name.substringBeforeLast('.', name)
                    val live = liveMap[Pair(bucketId, stem)]

                    out += PhotoItem(
                        id = id,
                        uri = fullUri,
                        thumbUri = thumbUri,
                        displayName = name,
                        bucketId = bucketId,
                        bucketName = bucketName,
                        relativePath = relative,
                        dateTaken = takenMs,
                        dateModified = modifiedMs,
                        size = c.getLongOrNull(sizeIdx) ?: 0L,
                        width = c.getIntOrNull(widthIdx) ?: 0,
                        height = c.getIntOrNull(heightIdx) ?: 0,
                        mimeType = c.getStringOrNull(mimeIdx).orEmpty(),
                        orientation = c.getIntOrNull(orientationIdx) ?: 0,
                        isLivePhoto = live != null,
                        liveVideoUri = live,
                    )
                } catch (e: Throwable) {
                    // 部分授权（Android 14+ 选了"仅部分照片"）下，未选中行的列读取会抛
                    // SecurityException，跳过这一行而不是让整次加载崩溃。
                    android.util.Log.w("PhotoO", "skip unreadable media row", e)
                }
            }
        }

        out.sortByDescending { it.dateTaken }
        return out
    }

    /**
     * 批量构建 image_id → 缩略图内容 Uri 映射。
     * Thumbnails 表一行对应一张缩略图，_ID 是缩略图自己的主键，IMAGE_ID 才指向原图。
     * 优先取 MINI_KIND（尺寸够网格用且体积小）。
     */
    @Suppress("DEPRECATION")
    private fun queryThumbnailMap(): Map<Long, Uri> {
        val out = HashMap<Long, Uri>()
        val uri = MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Thumbnails._ID,
            MediaStore.Images.Thumbnails.IMAGE_ID,
            MediaStore.Images.Thumbnails.KIND,
        )
        runCatching {
            context.contentResolver.query(uri, proj, null, null, null)
        }.getOrNull()?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Thumbnails._ID)
            val imageIdIdx = c.getColumnIndexOrThrow(MediaStore.Images.Thumbnails.IMAGE_ID)
            val kindIdx = c.getColumnIndexOrThrow(MediaStore.Images.Thumbnails.KIND)
            while (c.moveToNext()) {
                val imageId = c.getLong(imageIdIdx)
                val kind = c.getInt(kindIdx)
                val existing = out[imageId]
                // 第一次直接放；之后若遇到 MINI_KIND 则覆盖（MICRO_KIND 让位）。
                if (existing == null || kind == MediaStore.Images.Thumbnails.MINI_KIND) {
                    out[imageId] = ContentUris.withAppendedId(uri, c.getLong(idIdx))
                }
            }
        }
        return out
    }

    /**
     * 批量构建 (bucketId, 文件名主干) → 视频内容 Uri 映射，用于识别 Live Photo。
     * 无 READ_MEDIA_VIDEO 权限时查询返回空，Live Photo 自动降级为不可识别（不影响其它功能）。
     */
    private fun queryLiveVideoMap(): Map<Pair<Long, String>, Uri> {
        val out = HashMap<Pair<Long, String>, Uri>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DISPLAY_NAME,
        )
        runCatching {
            context.contentResolver.query(uri, proj, null, null, null)
        }.getOrNull()?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val bucketIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val relIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val vid = ContentUris.withAppendedId(uri, c.getLong(idIdx))
                val bucket = c.getLongOrNull(bucketIdx)
                    ?: c.getStringOrNull(relIdx)?.hashCode()?.toLong() ?: -1L
                val name = c.getStringOrNull(nameIdx).orEmpty()
                val stem = name.substringBeforeLast('.', name)
                if (stem.isNotEmpty()) out[Pair(bucket, stem)] = vid
            }
        }
        return out
    }

    /** 由照片列表聚合出相册列表，避免第二次查询。 */
    fun buildAlbums(photos: List<PhotoItem>): List<AlbumItem> {
        if (photos.isEmpty()) return emptyList()
        val grouped = photos.groupBy { it.bucketId }
        return grouped.map { (bucketId, items) ->
            val newest = items.maxByOrNull { it.dateTaken }
            AlbumItem(
                bucketId = bucketId,
                name = items.first().bucketName,
                relativePath = normalizePath(items.first().relativePath),
                count = items.size,
                coverUri = newest?.thumbUri,
                latestDate = newest?.dateTaken ?: 0L,
            )
        }.sortedWith(
            compareByDescending<AlbumItem> { it.isSystemCameraAlbum }
                .thenByDescending { it.latestDate }
        )
    }

    /** MediaStore 内容变化时发出信号，用于自动刷新。 */
    fun observeChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        trySend(Unit)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    companion object {
        /** 统一成 "Pictures/旅行/" 这种结尾带斜杠的形式。 */
        fun normalizePath(raw: String): String {
            val trimmed = raw.trim().trim('/')
            return if (trimmed.isEmpty()) "" else "$trimmed/"
        }

        /** 相册名 -> 默认相对路径。 */
        fun defaultPathFor(albumName: String): String =
            "${android.os.Environment.DIRECTORY_PICTURES}/${albumName.trim()}/"

        val supportsManageMedia: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
