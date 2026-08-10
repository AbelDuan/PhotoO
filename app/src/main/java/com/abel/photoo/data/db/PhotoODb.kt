package com.abel.photoo.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import java.io.File
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getStringOrNull
import com.abel.photoo.model.ReviewAction

/**
 * PhotoO 的本地状态库。
 *
 * 这里刻意没有使用 Room —— 表结构非常简单（4 张表、全部按主键读写），
 * 手写 SQLiteOpenHelper 可以完全避免 KSP / 注解处理器带来的版本矩阵问题，
 * 同时批量写入时能直接用一个事务，速度比逐条 DAO 调用更快。
 *
 * 所有方法都是阻塞的，调用方负责切到 Dispatchers.IO。
 */
class PhotoODb(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photo_state (
                media_id     INTEGER PRIMARY KEY,
                reviewed     INTEGER NOT NULL DEFAULT 0,
                action       TEXT    NOT NULL DEFAULT 'NONE',
                favorite     INTEGER NOT NULL DEFAULT 0,
                in_trash     INTEGER NOT NULL DEFAULT 0,
                updated_at   INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_photo_state_reviewed ON photo_state(reviewed)")
        db.execSQL("CREATE INDEX idx_photo_state_trash ON photo_state(in_trash)")

        db.execSQL(
            """
            CREATE TABLE trash_item (
                media_id        INTEGER PRIMARY KEY,
                uri             TEXT    NOT NULL,
                display_name    TEXT    NOT NULL,
                bucket_name     TEXT    NOT NULL,
                relative_path   TEXT    NOT NULL,
                size            INTEGER NOT NULL,
                date_taken      INTEGER NOT NULL,
                deleted_at      INTEGER NOT NULL,
                system_trashed  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE photo_hash (
                media_id     INTEGER PRIMARY KEY,
                dhash        INTEGER NOT NULL,
                ahash        INTEGER NOT NULL,
                avg_color    INTEGER NOT NULL,
                signature    TEXT    NOT NULL,
                computed_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE custom_album (
                name           TEXT PRIMARY KEY,
                relative_path  TEXT NOT NULL,
                created_at     INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE group_decision (
                group_key    TEXT PRIMARY KEY,
                resolved_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE live_photo (
                media_id     INTEGER PRIMARY KEY,
                type         INTEGER NOT NULL DEFAULT 0,
                video_offset INTEGER NOT NULL DEFAULT 0,
                cached_path  TEXT
            )
            """.trimIndent()
        )

        db.execSQL(GEO_TABLE_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只在需要新增表时追加，绝不 DROP 已有数据（相似哈希/回收站对用户有价值）。
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS live_photo (
                    media_id     INTEGER PRIMARY KEY,
                    type         INTEGER NOT NULL DEFAULT 0,
                    video_offset INTEGER NOT NULL DEFAULT 0,
                    cached_path  TEXT
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 3) {
            db.execSQL(GEO_TABLE_SQL)
        }
    }

    // ---------------------------------------------------------------- 处理状态

    data class StateRow(
        val reviewed: Boolean,
        val action: ReviewAction,
        val favorite: Boolean,
        val inTrash: Boolean,
    )

    fun loadAllStates(): Map<Long, StateRow> {
        val out = HashMap<Long, StateRow>()
        readableDatabase.rawQuery(
            "SELECT media_id, reviewed, action, favorite, in_trash FROM photo_state", null
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = StateRow(
                    reviewed = c.getInt(1) != 0,
                    action = parseAction(c.getStringOrNull(2)),
                    favorite = c.getInt(3) != 0,
                    inTrash = c.getInt(4) != 0,
                )
            }
        }
        return out
    }

    fun markReviewed(ids: Collection<Long>, action: ReviewAction) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        writableDatabase.transaction { db ->
            ids.forEach { id ->
                db.execSQL(
                    """
                    INSERT INTO photo_state(media_id, reviewed, action, favorite, in_trash, updated_at)
                    VALUES(?, 1, ?, 0, 0, ?)
                    ON CONFLICT(media_id) DO UPDATE SET
                        reviewed = 1, action = excluded.action, updated_at = excluded.updated_at
                    """.trimIndent(),
                    arrayOf<Any>(id, action.name, now)
                )
            }
        }
    }

    fun setFavorite(id: Long, favorite: Boolean) {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            """
            INSERT INTO photo_state(media_id, reviewed, action, favorite, in_trash, updated_at)
            VALUES(?, 0, 'NONE', ?, 0, ?)
            ON CONFLICT(media_id) DO UPDATE SET favorite = excluded.favorite, updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf<Any>(id, if (favorite) 1 else 0, now)
        )
    }

    fun resetReview(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.transaction { db ->
            ids.forEach { id ->
                db.execSQL(
                    "UPDATE photo_state SET reviewed = 0, action = 'NONE', updated_at = ? WHERE media_id = ?",
                    arrayOf<Any>(System.currentTimeMillis(), id)
                )
            }
        }
    }

    fun resetAllReviews() {
        writableDatabase.execSQL(
            "UPDATE photo_state SET reviewed = 0, action = 'NONE', updated_at = ?",
            arrayOf<Any>(System.currentTimeMillis())
        )
    }

    // ------------------------------------------------------------------ 回收站

    data class TrashRow(
        val id: Long,
        val uri: String,
        val displayName: String,
        val bucketName: String,
        val relativePath: String,
        val size: Long,
        val dateTaken: Long,
        val deletedAt: Long,
        val systemTrashed: Boolean,
    )

    fun putTrash(rows: List<TrashRow>) {
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        writableDatabase.transaction { db ->
            rows.forEach { r ->
                val values = ContentValues().apply {
                    put("media_id", r.id)
                    put("uri", r.uri)
                    put("display_name", r.displayName)
                    put("bucket_name", r.bucketName)
                    put("relative_path", r.relativePath)
                    put("size", r.size)
                    put("date_taken", r.dateTaken)
                    put("deleted_at", r.deletedAt)
                    put("system_trashed", if (r.systemTrashed) 1 else 0)
                }
                db.insertWithOnConflict("trash_item", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL(
                    """
                    INSERT INTO photo_state(media_id, reviewed, action, favorite, in_trash, updated_at)
                    VALUES(?, 1, 'TRASHED', 0, 1, ?)
                    ON CONFLICT(media_id) DO UPDATE SET
                        reviewed = 1, action = 'TRASHED', in_trash = 1, updated_at = excluded.updated_at
                    """.trimIndent(),
                    arrayOf<Any>(r.id, now)
                )
            }
        }
    }

    fun listTrash(): List<TrashRow> {
        val out = ArrayList<TrashRow>()
        readableDatabase.rawQuery(
            """
            SELECT media_id, uri, display_name, bucket_name, relative_path,
                   size, date_taken, deleted_at, system_trashed
            FROM trash_item ORDER BY deleted_at DESC
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) out += c.readTrashRow()
        }
        return out
    }

    fun removeTrash(ids: Collection<Long>, alsoClearState: Boolean) {
        if (ids.isEmpty()) return
        writableDatabase.transaction { db ->
            ids.forEach { id ->
                db.delete("trash_item", "media_id = ?", arrayOf(id.toString()))
                if (alsoClearState) {
                    db.execSQL(
                        "UPDATE photo_state SET in_trash = 0, action = 'NONE', reviewed = 0, updated_at = ? WHERE media_id = ?",
                        arrayOf<Any>(System.currentTimeMillis(), id)
                    )
                } else {
                    db.execSQL(
                        "UPDATE photo_state SET in_trash = 0, updated_at = ? WHERE media_id = ?",
                        arrayOf<Any>(System.currentTimeMillis(), id)
                    )
                }
            }
        }
    }

    private fun Cursor.readTrashRow() = TrashRow(
        id = getLong(0),
        uri = getString(1),
        displayName = getString(2),
        bucketName = getString(3),
        relativePath = getString(4),
        size = getLong(5),
        dateTaken = getLong(6),
        deletedAt = getLong(7),
        systemTrashed = getInt(8) != 0,
    )

    // --------------------------------------------------------------- 感知哈希

    data class HashRow(
        val id: Long,
        val dHash: Long,
        val aHash: Long,
        val avgColor: Int,
        val signature: String,
    )

    fun loadHashes(): MutableMap<Long, HashRow> {
        val out = HashMap<Long, HashRow>()
        readableDatabase.rawQuery(
            "SELECT media_id, dhash, ahash, avg_color, signature FROM photo_hash", null
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = HashRow(
                    id = c.getLong(0),
                    dHash = c.getLong(1),
                    aHash = c.getLong(2),
                    avgColor = c.getInt(3),
                    signature = c.getString(4),
                )
            }
        }
        return out
    }

    fun putHashes(rows: List<HashRow>) {
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        writableDatabase.transaction { db ->
            rows.forEach { r ->
                val values = ContentValues().apply {
                    put("media_id", r.id)
                    put("dhash", r.dHash)
                    put("ahash", r.aHash)
                    put("avg_color", r.avgColor)
                    put("signature", r.signature)
                    put("computed_at", now)
                }
                db.insertWithOnConflict("photo_hash", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun deleteHashes(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.transaction { db ->
            ids.forEach { db.delete("photo_hash", "media_id = ?", arrayOf(it.toString())) }
        }
    }

    fun clearHashes() {
        writableDatabase.execSQL("DELETE FROM photo_hash")
    }

    // ------------------------------------------------------------- 自建空相册

    data class CustomAlbum(val name: String, val relativePath: String)

    fun listCustomAlbums(): List<CustomAlbum> {
        val out = ArrayList<CustomAlbum>()
        readableDatabase.rawQuery(
            "SELECT name, relative_path FROM custom_album ORDER BY created_at DESC", null
        ).use { c ->
            while (c.moveToNext()) out += CustomAlbum(c.getString(0), c.getString(1))
        }
        return out
    }

    fun addCustomAlbum(name: String, relativePath: String) {
        val values = ContentValues().apply {
            put("name", name)
            put("relative_path", relativePath)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "custom_album", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeCustomAlbum(name: String) {
        writableDatabase.delete("custom_album", "name = ?", arrayOf(name))
    }

    // --------------------------------------------------------------- 相似分组

    fun listResolvedGroups(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT group_key FROM group_decision", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun markGroupResolved(key: String) {
        val values = ContentValues().apply {
            put("group_key", key)
            put("resolved_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "group_decision", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clearGroupDecisions() {
        writableDatabase.execSQL("DELETE FROM group_decision")
    }

    // --------------------------------------------------------------- Live Photo

    data class LiveRow(
        val id: Long,
        /** 0 无, 1 同名视频文件, 2 图片内嵌视频流。 */
        val type: Int,
        /** type==2 时：内嵌视频在图片文件中的字节偏移。 */
        val videoOffset: Long,
        /** type==2 时：抽取后的视频缓存文件路径（首次播放时生成）。 */
        val cachedPath: String?,
    )

    /**
     * 加载全部 Live Photo 扫描结果，含 type==0 的"这张不是实况"。
     *
     * 负结果也要落库并读回来，否则每次启动都会把整库 JPEG 的文件头重读一遍——
     * 那正是之前实况扫描又慢又像没生效的原因之一。
     */
    fun loadLivePhotoMap(): Map<Long, LiveRow> {
        val out = HashMap<Long, LiveRow>()
        readableDatabase.rawQuery(
            "SELECT media_id, type, video_offset, cached_path FROM live_photo",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = LiveRow(
                    id = c.getLong(0),
                    type = c.getInt(1),
                    videoOffset = c.getLong(2),
                    cachedPath = c.getStringOrNull(3),
                )
            }
        }
        return out
    }

    fun putLivePhoto(row: LiveRow) {
        val values = ContentValues().apply {
            put("media_id", row.id)
            put("type", row.type)
            put("video_offset", row.videoOffset)
            put("cached_path", row.cachedPath)
        }
        writableDatabase.insertWithOnConflict("live_photo", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 读取已缓存的内嵌视频路径（若存在且文件仍存在则返回，否则返回 null）。 */
    fun getLiveCachePath(id: Long): String? {
        readableDatabase.rawQuery(
            "SELECT cached_path FROM live_photo WHERE media_id = ?",
            arrayOf(id.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                val p = c.getStringOrNull(0)
                if (p != null && File(p).exists()) return p
            }
        }
        return null
    }

    fun setLiveCachePath(id: Long, path: String) {
        writableDatabase.execSQL(
            "UPDATE live_photo SET cached_path = ? WHERE media_id = ?",
            arrayOf(path, id.toString()),
        )
    }

    /** 抽取阶段整文件扫描得到真实偏移后，回填偏移，下次无需再全扫。 */
    fun setLiveOffset(id: Long, offset: Long) {
        writableDatabase.execSQL(
            "UPDATE live_photo SET video_offset = ? WHERE media_id = ?",
            arrayOf<Any>(offset, id.toString()),
        )
    }

    /** 批量写入实况扫描结果（含负结果）。 */
    fun putLivePhotoBatch(rows: Collection<LiveRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { putLivePhoto(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 清空实况识别结果，用于"重新扫描实况照片"。 */
    fun clearLivePhotos() {
        writableDatabase.execSQL("DELETE FROM live_photo")
    }

    // ------------------------------------------------------------- 拍摄坐标

    /**
     * 一条 GPS 记录。[located] = 0 表示"扫过了但这张没有坐标"，
     * 记下来是为了避免每次启动都重复解析同一批无坐标的照片。
     */
    data class GeoRow(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val located: Boolean,
    )

    fun loadGeoMap(): Map<Long, GeoRow> {
        val out = HashMap<Long, GeoRow>()
        readableDatabase.rawQuery(
            "SELECT media_id, lat, lon, located FROM photo_geo", null
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = GeoRow(
                    id = c.getLong(0),
                    lat = c.getDouble(1),
                    lon = c.getDouble(2),
                    located = c.getInt(3) == 1,
                )
            }
        }
        return out
    }

    fun putGeoBatch(rows: Collection<GeoRow>) {
        if (rows.isEmpty()) return
        writableDatabase.transaction { db ->
            rows.forEach { r ->
                db.insertWithOnConflict(
                    "photo_geo",
                    null,
                    ContentValues().apply {
                        put("media_id", r.id)
                        put("lat", r.lat)
                        put("lon", r.lon)
                        put("located", if (r.located) 1 else 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    fun clearGeo() {
        writableDatabase.execSQL("DELETE FROM photo_geo")
    }

    // ------------------------------------------------------------------ 工具

    private inline fun SQLiteDatabase.transaction(block: (SQLiteDatabase) -> Unit) {
        beginTransaction()
        try {
            block(this)
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun parseAction(raw: String?): ReviewAction =
        ReviewAction.entries.firstOrNull { it.name == raw } ?: ReviewAction.NONE

    companion object {
        private const val DB_NAME = "photoo.db"
        private const val DB_VERSION = 3

        /** onCreate 与 onUpgrade 共用同一份建表语句，避免两边写歪了。 */
        private val GEO_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS photo_geo (
                media_id  INTEGER PRIMARY KEY,
                lat       REAL    NOT NULL DEFAULT 0,
                lon       REAL    NOT NULL DEFAULT 0,
                located   INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
    }
}
