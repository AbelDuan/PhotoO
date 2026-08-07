package com.abel.photoo.data.media

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.abel.photoo.model.OpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 所有会改动系统相册的写操作都集中在这里。
 *
 * 三条规则：
 *  1. 先尝试直接写。拿到 MANAGE_MEDIA 权限、或文件本来就是本应用创建的，这一步就成了。
 *  2. 失败就退回到系统确认弹窗（createWriteRequest / createTrashRequest / createDeleteRequest）。
 *  3. 用户取消返回 [OpResult.Cancelled]，绝不静默吞掉。
 */
class MediaOps(
    private val context: Context,
    private val broker: MediaRequestBroker,
) {

    private val resolver get() = context.contentResolver

    /** 真正从系统里删除（PhotoO 回收站里点"彻底删除"时调用）。 */
    suspend fun deleteForever(uris: List<Uri>): OpResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext OpResult.Success(0)

        val remaining = ArrayList<Uri>()
        var deleted = 0
        for (uri in uris) {
            try {
                if (resolver.delete(uri, null, null) > 0) deleted++
            } catch (_: SecurityException) {
                remaining += uri
            } catch (_: Exception) {
                // 文件已经不存在了，当作删除成功处理。
                deleted++
            }
        }
        if (remaining.isEmpty()) return@withContext OpResult.Success(deleted)

        val granted = requestConfirm { MediaStore.createDeleteRequest(resolver, remaining) }
        when {
            granted == null -> OpResult.Failure("系统未受理删除请求")
            granted -> OpResult.Success(deleted + remaining.size)
            deleted > 0 -> OpResult.Success(deleted)
            else -> OpResult.Cancelled
        }
    }

    /** 移入 / 移出系统自带回收站（30 天后系统自动清理）。 */
    suspend fun setSystemTrashed(uris: List<Uri>, trashed: Boolean): OpResult =
        withContext(Dispatchers.IO) {
            if (uris.isEmpty()) return@withContext OpResult.Success(0)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
            }
            val remaining = ArrayList<Uri>()
            var done = 0
            for (uri in uris) {
                try {
                    if (resolver.update(uri, values, null) > 0) done++
                } catch (_: SecurityException) {
                    remaining += uri
                } catch (_: Exception) {
                    remaining += uri
                }
            }
            if (remaining.isEmpty()) return@withContext OpResult.Success(done)

            val granted = requestConfirm { MediaStore.createTrashRequest(resolver, remaining, trashed) }
            when {
                granted == null -> OpResult.Failure("系统未受理回收站请求")
                granted -> OpResult.Success(done + remaining.size)
                done > 0 -> OpResult.Success(done)
                else -> OpResult.Cancelled
            }
        }

    /**
     * 把照片归档到另一个相册。
     *
     * API 30 起可以直接改 RELATIVE_PATH，系统会真正搬运文件，
     * 不需要"复制 + 删除"那种会丢 EXIF、翻倍占用空间的老做法。
     */
    suspend fun moveToAlbum(uris: List<Uri>, relativePath: String): OpResult =
        withContext(Dispatchers.IO) {
            if (uris.isEmpty()) return@withContext OpResult.Success(0)
            val path = MediaStoreSource.normalizePath(relativePath)
            if (path.isEmpty()) return@withContext OpResult.Failure("相册路径无效")

            // 先争取一次性拿到整批的写权限，避免逐张弹窗。
            val pre = requestConfirm { MediaStore.createWriteRequest(resolver, uris) }
            if (pre == false) return@withContext OpResult.Cancelled

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            }

            var moved = 0
            val failures = ArrayList<String>()
            for (uri in uris) {
                try {
                    if (resolver.update(uri, values, null) > 0) moved++
                } catch (e: RecoverableSecurityException) {
                    val granted = runCatching {
                        broker.confirm(e.userAction.actionIntent.intentSender)
                    }.getOrDefault(false)
                    if (granted && runCatching { resolver.update(uri, values, null) > 0 }
                            .getOrDefault(false)
                    ) {
                        moved++
                    } else {
                        failures += uri.lastPathSegment.orEmpty()
                    }
                } catch (e: Exception) {
                    failures += (e.message ?: uri.lastPathSegment.orEmpty())
                }
            }

            when {
                moved == uris.size -> OpResult.Success(moved)
                moved > 0 -> OpResult.Success(moved)
                else -> OpResult.Failure(
                    failures.firstOrNull()?.let { "移动失败：$it" } ?: "移动失败"
                )
            }
        }

    /** 重命名相册目录 = 把该目录下所有照片的 RELATIVE_PATH 换成新的。 */
    suspend fun renameAlbum(uris: List<Uri>, newRelativePath: String): OpResult =
        moveToAlbum(uris, newRelativePath)

    /**
     * 发起系统确认。
     * @return true 用户同意；false 用户取消；null 表示这台设备压根没弹出来。
     */
    private suspend fun requestConfirm(build: () -> android.app.PendingIntent?): Boolean? {
        val sender = runCatching { build()?.intentSender }.getOrNull() ?: return null
        return runCatching { broker.confirm(sender) }.getOrDefault(false)
    }
}
