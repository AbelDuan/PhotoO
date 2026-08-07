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
                val id = c.getLong(idIdx)
                val modifiedMs = (c.getLongOrNull(modifiedIdx) ?: 0L) * 1000L
                val takenMs = c.getLongOrNull(takenIdx)?.takeIf { it > 0L } ?: modifiedMs
                val relative = c.getStringOrNull(pathIdx).orEmpty()
                val bucketName = c.getStringOrNull(bucketNameIdx)
                    ?: relative.trim('/').substringAfterLast('/').ifEmpty { "根目录" }

                out += PhotoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getStringOrNull(nameIdx).orEmpty(),
                    bucketId = c.getLongOrNull(bucketIdIdx) ?: relative.hashCode().toLong(),
                    bucketName = bucketName,
                    relativePath = relative,
                    dateTaken = takenMs,
                    dateModified = modifiedMs,
                    size = c.getLongOrNull(sizeIdx) ?: 0L,
                    width = c.getIntOrNull(widthIdx) ?: 0,
                    height = c.getIntOrNull(heightIdx) ?: 0,
                    mimeType = c.getStringOrNull(mimeIdx).orEmpty(),
                    orientation = c.getIntOrNull(orientationIdx) ?: 0,
                )
            }
        }

        out.sortByDescending { it.dateTaken }
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
                coverUri = newest?.uri,
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
