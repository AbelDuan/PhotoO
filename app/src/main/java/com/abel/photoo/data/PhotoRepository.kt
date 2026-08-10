package com.abel.photoo.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.abel.photoo.data.db.PhotoODb
import com.abel.photoo.data.exif.ExifReader
import com.abel.photoo.data.log.PhotoLog
import com.abel.photoo.data.media.MediaOps
import com.abel.photoo.data.media.MediaRequestBroker
import com.abel.photoo.data.media.MediaStoreSource
import com.abel.photoo.data.prefs.AppPrefs
import com.abel.photoo.data.geo.CoordTransform
import com.abel.photoo.data.similar.PerceptualHash
import com.abel.photoo.data.similar.SimilarityEngine
import com.abel.photoo.model.AlbumItem
import com.abel.photoo.model.GeoPoint
import com.abel.photoo.model.GeoScanState
import com.abel.photoo.model.KeepStrategy
import com.abel.photoo.model.LibraryStats
import com.abel.photoo.model.OpResult
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.ReviewAction
import com.abel.photoo.model.ScanState
import com.abel.photoo.model.SimilarGroup
import com.abel.photoo.model.SimilarityLevel
import com.abel.photoo.model.TrashItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全应用唯一的数据门面。
 *
 * 职责边界很清楚：MediaStore 只负责"系统里有什么"，本地 SQLite 只负责"我对它做过什么"，
 * Repository 把两者拼起来对外暴露成一份可观察的状态。
 * 这样即便用户在小米相册里直接删了照片，下次刷新也能自动对齐，不会留下幽灵条目。
 */
class PhotoRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    val prefs: AppPrefs,
    val broker: MediaRequestBroker,
) {

    private val db = PhotoODb(context)
    private val source = MediaStoreSource(context)
    private val ops = MediaOps(context, broker)
    val exif = ExifReader(context)

    private val refreshLock = Mutex()
    private var scanJob: Job? = null
    private var hashCache: MutableMap<Long, PhotoODb.HashRow> = HashMap()

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumItem>>(emptyList())
    val albums: StateFlow<List<AlbumItem>> = _albums.asStateFlow()

    private val _trash = MutableStateFlow<List<TrashItem>>(emptyList())
    val trash: StateFlow<List<TrashItem>> = _trash.asStateFlow()

    private val _stats = MutableStateFlow(LibraryStats())
    val stats: StateFlow<LibraryStats> = _stats.asStateFlow()

    private val _similarGroups = MutableStateFlow<List<SimilarGroup>>(emptyList())
    val similarGroups: StateFlow<List<SimilarGroup>> = _similarGroups.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _geoPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
    val geoPoints: StateFlow<List<GeoPoint>> = _geoPoints.asStateFlow()

    private val _geoScanState = MutableStateFlow<GeoScanState>(GeoScanState.Idle)
    val geoScanState: StateFlow<GeoScanState> = _geoScanState.asStateFlow()

    private var geoScanning = false

    private val _messages = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var observing = false

    /** 权限到位后调用一次；重复调用是安全的。 */
    fun startObserving() {
        if (observing) return
        observing = true
        scope.launch {
            // refresh() 末尾会自动触发一次实况扫描（结果落库，稳定后几乎零开销）。
            refresh()
        }
        source.observeChanges()
            .drop(1)                 // 注册时会立刻发一次，跳过它避免重复加载
            .debounce(700)
            .onEach { refresh() }
            .launchIn(scope)
    }

    // ------------------------------------------------------------------ 读取

    suspend fun refresh() = refreshLock.withLock {
        _loading.value = true
        try {
            val raw = withContext(Dispatchers.IO) { source.queryPhotos() }
            val states = withContext(Dispatchers.IO) { db.loadAllStates() }
            val trashRows = withContext(Dispatchers.IO) { db.listTrash() }
            val customAlbums = withContext(Dispatchers.IO) { db.listCustomAlbums() }
            // 已识别的 Live Photo（尤其是内嵌视频）落库后直接复用，避免每次重扫。
            val liveMap = withContext(Dispatchers.IO) { db.loadLivePhotoMap() }

            val liveIds = raw.mapTo(HashSet(raw.size)) { it.id }
            // 系统里已经消失的条目，回收站里也要清掉，否则会留下点不开的空壳。
            val stale = trashRows.filter { it.id !in liveIds }.map { it.id }
            if (stale.isNotEmpty()) {
                withContext(Dispatchers.IO) { db.removeTrash(stale, alsoClearState = false) }
            }
            val trashIds = trashRows.mapNotNull { it.id.takeIf { id -> id in liveIds } }.toHashSet()

            val decorated = raw.map { p ->
                val s = states[p.id]
                var cp = if (s == null) p else p.copy(
                    reviewed = s.reviewed,
                    reviewAction = s.action,
                    favorite = s.favorite,
                )
                // 复用已持久化的内嵌 Live Photo 识别结果。
                val lv = liveMap[p.id]
                if (lv != null && lv.type == 2) {
                    cp = cp.copy(liveType = 2, liveOffset = lv.videoOffset)
                }
                cp
            }
            val visible = decorated.filter { it.id !in trashIds }

            _photos.value = visible
            _trash.value = trashRows
                .filter { it.id in liveIds }
                .map {
                    TrashItem(
                        id = it.id,
                        uri = Uri.parse(it.uri),
                        displayName = it.displayName,
                        bucketName = it.bucketName,
                        size = it.size,
                        dateTaken = it.dateTaken,
                        deletedAt = it.deletedAt,
                        systemTrashed = it.systemTrashed,
                    )
                }

            val mediaAlbums = source.buildAlbums(visible)
            val existingPaths = mediaAlbums.mapTo(HashSet()) { it.relativePath.lowercase() }
            val placeholders = customAlbums
                .filter { it.relativePath.lowercase() !in existingPaths }
                .map {
                    AlbumItem(
                        bucketId = -(it.relativePath.hashCode().toLong().and(0xFFFFFFFFL) + 1),
                        name = it.name,
                        relativePath = it.relativePath,
                        count = 0,
                        coverUri = null,
                        latestDate = 0L,
                        pendingLocal = true,
                    )
                }
            _albums.value = orderAlbums(mediaAlbums + placeholders)

            _stats.value = LibraryStats(
                total = visible.size,
                reviewed = visible.count { it.reviewed },
                trashed = _trash.value.size,
                albums = _albums.value.size,
            )

            // 图库变化后用已持久化的哈希重建相似分组。哈希存在本地 photo_hash 表，
            // 这里只聚类不重算，所以下次打开直接就有相似结果、无需重新扫描整库。
            rebuildGroups()

            // 新拍的照片也要认出实况来。扫描结果（含"不是实况"）都落库，
            // 所以这一步在稳定状态下几乎不做任何 IO。
            scanLivePhotos()
        } catch (e: Throwable) {
            // 任何加载异常都兜底成提示，绝不向上抛到 scope 导致进程被杀（闪退）。
            Log.e("PhotoO", "refresh failed", e)
            emit("加载失败：${e::class.simpleName ?: "Error"}${e.message?.let { "：$it" } ?: ""}")
        } finally {
            _loading.value = false
        }
    }

    fun photosOf(bucketId: Long): List<PhotoItem> =
        _photos.value.filter { it.bucketId == bucketId }

    fun pendingPhotos(): List<PhotoItem> =
        _photos.value.filterNot { it.reviewed }.sortedByDescending { it.dateTaken }

    fun findPhoto(id: Long): PhotoItem? = _photos.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- 处理状态

    fun markReviewed(ids: Collection<Long>, action: ReviewAction) {
        if (ids.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { db.markReviewed(ids, action) }
            applyLocalState(ids) { it.copy(reviewed = true, reviewAction = action) }
        }
    }

    fun toggleFavorite(id: Long) {
        val target = findPhoto(id) ?: return
        val next = !target.favorite
        scope.launch {
            withContext(Dispatchers.IO) { db.setFavorite(id, next) }
            applyLocalState(listOf(id)) { it.copy(favorite = next) }
        }
    }

    fun resetAllReviews() {
        scope.launch {
            withContext(Dispatchers.IO) { db.resetAllReviews() }
            refresh()
            emit("已重置全部整理进度")
        }
    }

    private fun applyLocalState(ids: Collection<Long>, transform: (PhotoItem) -> PhotoItem) {
        val idSet = ids.toHashSet()
        _photos.value = _photos.value.map { if (it.id in idSet) transform(it) else it }
        val visible = _photos.value
        _stats.value = _stats.value.copy(
            total = visible.size,
            reviewed = visible.count { it.reviewed },
        )
    }

    // ------------------------------------------------------------------ 回收站

    /**
     * 上滑删除 / 批量删除的入口。
     *
     * 默认只在 PhotoO 内部隐藏，系统文件原封不动 —— 这样回收站里还能看到缩略图，
     * 也不会有"在小米相册回收站和 PhotoO 回收站各躺一份"的割裂感。
     * 想要双保险的话在设置里打开"同时移入系统回收站"。
     */
    fun moveToTrash(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        val targets = _photos.value.filter { it.id in idSet }
        if (targets.isEmpty()) return
        PhotoLog.i("Trash", "move: ids=$ids systemTrash=${prefs.current.alsoSystemTrash}")

        scope.launch {
            if (prefs.current.alsoSystemTrash) {
                val result = ops.setSystemTrashed(targets.map { it.uri }, true)
                if (result is OpResult.Cancelled) {
                    PhotoLog.i("Trash", "cancelled by user")
                    emit("已取消删除")
                    return@launch
                }
                if (result is OpResult.Failure) {
                    PhotoLog.w("Trash", "system-trash-failed: ${result.message}")
                    emit(result.message)
                    return@launch
                }
            }
            val now = System.currentTimeMillis()
            val rows = targets.map {
                PhotoODb.TrashRow(
                    id = it.id,
                    uri = it.uri.toString(),
                    displayName = it.displayName,
                    bucketName = it.bucketName,
                    relativePath = it.relativePath,
                    size = it.size,
                    dateTaken = it.dateTaken,
                    deletedAt = now,
                    systemTrashed = prefs.current.alsoSystemTrash,
                )
            }
            withContext(Dispatchers.IO) { db.putTrash(rows) }
            // 关键：内存内立即隐藏，列表/大图瞬间更新，不再全量重查 MediaStore
            // （之前上滑"等一会"的根因就是 refresh() 把整库重新拉了一遍）。
            _photos.value = _photos.value.filterNot { it.id in idSet }
            // 同步把被删照片从相似组里剔除，否则相似组 / 详情页的缩略图还留在原处。
            _similarGroups.value = _similarGroups.value.map { g ->
                if (g.items.any { it.id in idSet }) {
                    g.copy(items = g.items.filterNot { it.id in idSet })
                } else g
            }
            recomputeDerived()
        }
    }

    /**
     * 不碰 MediaStore，仅用内存中的照片 + 两次轻量小查询（自定义相册、回收站）
     * 重算相册列表与统计。删除/彻底删除后调用，避免每次都全量刷新卡顿。
     */
    private suspend fun recomputeDerived() {
        val customAlbums = withContext(Dispatchers.IO) { db.listCustomAlbums() }
        val trashRows = withContext(Dispatchers.IO) { db.listTrash() }
        val visible = _photos.value
        val mediaAlbums = source.buildAlbums(visible)
        val existingPaths = mediaAlbums.mapTo(HashSet()) { it.relativePath.lowercase() }
        val placeholders = customAlbums
            .filter { it.relativePath.lowercase() !in existingPaths }
            .map {
                AlbumItem(
                    bucketId = -(it.relativePath.hashCode().toLong().and(0xFFFFFFFFL) + 1),
                    name = it.name,
                    relativePath = it.relativePath,
                    count = 0,
                    coverUri = null,
                    latestDate = 0L,
                    pendingLocal = true,
                )
            }
        _albums.value = orderAlbums(mediaAlbums + placeholders)
        _trash.value = trashRows.map {
            TrashItem(
                id = it.id,
                uri = Uri.parse(it.uri),
                displayName = it.displayName,
                bucketName = it.bucketName,
                size = it.size,
                dateTaken = it.dateTaken,
                deletedAt = it.deletedAt,
                systemTrashed = it.systemTrashed,
            )
        }
        _stats.value = LibraryStats(
            total = visible.size,
            reviewed = visible.count { it.reviewed },
            trashed = trashRows.size,
            albums = _albums.value.size,
        )
    }

    fun restoreFromTrash(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val rows = _trash.value.filter { it.id in ids.toHashSet() }
        scope.launch {
            val needSystemRestore = rows.filter { it.systemTrashed }
            if (needSystemRestore.isNotEmpty()) {
                val result = ops.setSystemTrashed(needSystemRestore.map { it.uri }, false)
                if (result is OpResult.Cancelled) {
                    emit("已取消恢复")
                    return@launch
                }
            }
            withContext(Dispatchers.IO) { db.removeTrash(ids, alsoClearState = true) }
            refresh()
            emit("已恢复 ${ids.size} 张")
        }
    }

    /** 回收站里的"彻底删除"：这一步才真正同步给系统。 */
    fun deleteForever(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val rows = _trash.value.filter { it.id in ids.toHashSet() }
        if (rows.isEmpty()) return
        scope.launch {
            when (val result = ops.deleteForever(rows.map { it.uri })) {
                is OpResult.Cancelled -> emit("已取消，照片仍在回收站")
                is OpResult.Failure -> emit(result.message)
                is OpResult.Success -> {
                    withContext(Dispatchers.IO) {
                        db.removeTrash(ids, alsoClearState = false)
                        db.deleteHashes(ids)
                    }
                    ids.forEach { hashCache.remove(it) }
                    refresh()
                    emit("已从系统中永久删除 ${result.affected} 张")
                }
            }
        }
    }

    fun emptyTrash() = deleteForever(_trash.value.map { it.id })

    // -------------------------------------------------------------------- 相册

    fun createAlbum(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        scope.launch {
            val path = MediaStoreSource.defaultPathFor(clean)
            withContext(Dispatchers.IO) { db.addCustomAlbum(clean, path) }
            refresh()
            emit("已创建相册「$clean」")
        }
    }

    fun moveToAlbum(ids: Collection<Long>, album: AlbumItem) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        val targets = _photos.value.filter { it.id in idSet && it.bucketId != album.bucketId }
        if (targets.isEmpty()) {
            emit("这些照片已经在「${album.name}」里了")
            return
        }
        scope.launch {
            when (val result = ops.moveToAlbum(targets.map { it.uri }, album.relativePath)) {
                is OpResult.Cancelled -> emit("已取消归档")
                is OpResult.Failure -> emit(result.message)
                is OpResult.Success -> {
                    withContext(Dispatchers.IO) {
                        db.markReviewed(targets.map { it.id }, ReviewAction.MOVED)
                        if (album.pendingLocal) db.removeCustomAlbum(album.name)
                    }
                    refresh()
                    emit("已归档 ${result.affected} 张到「${album.name}」")
                }
            }
        }
    }

    fun renameAlbum(album: AlbumItem, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == album.name) return
        scope.launch {
            if (album.pendingLocal) {
                withContext(Dispatchers.IO) {
                    db.removeCustomAlbum(album.name)
                    db.addCustomAlbum(clean, MediaStoreSource.defaultPathFor(clean))
                }
                refresh()
                emit("已重命名为「$clean」")
                return@launch
            }
            val members = photosOf(album.bucketId)
            if (members.isEmpty()) {
                emit("相册是空的")
                return@launch
            }
            val parent = album.relativePath.trim('/').substringBeforeLast('/', "")
            val newPath = if (parent.isEmpty()) "$clean/" else "$parent/$clean/"
            when (val result = ops.renameAlbum(members.map { it.uri }, newPath)) {
                is OpResult.Cancelled -> emit("已取消重命名")
                is OpResult.Failure -> emit(result.message)
                is OpResult.Success -> {
                    refresh()
                    emit("已重命名为「$clean」，移动了 ${result.affected} 张")
                }
            }
        }
    }

    fun deleteEmptyAlbum(album: AlbumItem) {
        if (!album.pendingLocal) return
        scope.launch {
            withContext(Dispatchers.IO) { db.removeCustomAlbum(album.name) }
            refresh()
            emit("已删除空相册「${album.name}」")
        }
    }

    /**
     * 大图页"快捷归入"里的"新建"用：建一个空相册并立刻把当前照片归入，
     * 一步到位，不用先建再选。
     */
    fun createAlbumAndMove(name: String, ids: Collection<Long>) {
        val clean = name.trim()
        if (clean.isEmpty() || ids.isEmpty()) return
        val idSet = ids.toHashSet()
        val targets = _photos.value.filter { it.id in idSet }
        if (targets.isEmpty()) return
        scope.launch {
            val path = MediaStoreSource.defaultPathFor(clean)
            withContext(Dispatchers.IO) { db.addCustomAlbum(clean, path) }
            val album = AlbumItem(
                bucketId = -(path.hashCode().toLong().and(0xFFFFFFFFL) + 1),
                name = clean,
                relativePath = path,
                count = 0,
                coverUri = null,
                latestDate = 0L,
                pendingLocal = true,
            )
            moveToAlbum(ids, album)
        }
    }

    /** 设置相册展示顺序（按 relativePath）。 */
    fun setAlbumOrder(names: List<String>) {
        prefs.setAlbumOrder(names)
        scope.launch { recomputeDerived() }
    }

    /** 按用户在设置/相册页排好的顺序展示，没排过的按时间落到末尾。 */
    private fun orderAlbums(albums: List<AlbumItem>): List<AlbumItem> {
        val order = prefs.current.albumOrder
        if (order.isEmpty()) return albums
        return albums.sortedWith(
            compareBy<AlbumItem> { idx ->
                val i = order.indexOf(idx.relativePath)
                if (i < 0) Int.MAX_VALUE else i
            }.thenByDescending { it.latestDate }
        )
    }

    /**
     * 大图页"快捷归入"用：按相册名直接归档，省去先弹相册选择器的两步操作。
     * 找不到同名相册直接提示，不抛异常。
     */
    fun moveToAlbumByName(name: String, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        // 同名的相册可能对应多个 bucket（如小米的"截图"在两个目录都有），
        // 优先选 DCIM 下或照片数最多的那个，保证归入到用户预期的位置。
        val album = _albums.value.filter { it.name == name }.maxByOrNull {
            if (it.relativePath.contains("DCIM", ignoreCase = true)) Int.MAX_VALUE else it.count
        } ?: _albums.value.firstOrNull { it.name == name }
        if (album == null) {
            emit("没有相册「$name」，请先在设置里添加快捷相册")
            return
        }
        moveToAlbum(ids, album)
    }

    // ------------------------------------------------------------------ Live Photo

    /**
     * 后台扫描"图片文件内嵌视频"型 Live Photo（小米/华为等把实况视频直接写进 JPG 的 XMP）。
     *
     * 只扫 JPEG、只读本文件头部约 256KB（XMP 元数据在文件开头），不在主线程、不读整张原图，
     * 因此即便几千张照片也只是一段一次性的后台 IO，识别结果写入 [PhotoODb.live_photo] 表，
     * 下次启动直接复用、不再重扫。同目录同名视频文件（type==1）由 queryPhotos 同步识别，不在此处理。
     */
    /**
     * 后台扫描"图片文件内嵌视频"型 Live Photo（小米/华为等把实况视频直接写进 JPG/HEIC 的 XMP）。
     *
     * 只扫图片类、只读本文件头部约 256KB（XMP 元数据在文件开头），不在主线程、不读整张原图，
     * 因此即便几千张照片也只是一段一次性的后台 IO。识别结果（含"不是实况"的负结果）一次性
     * 批量写入 [PhotoODb.live_photo] 表，下次启动直接复用、不再重扫。
     * 同目录同名视频文件（type==1）由 queryPhotos 同步识别，不在此处理。
     *
     * detectEmbeddedLive 的返回值语义：
     *   > 0 ：明确是实况，返回内嵌视频在文件中的字节偏移；
     *   - 1 ：有实况标记但 XMP 里偏移损坏/读不出，抽取阶段会整文件扫描定位视频；
     *   0   ：不是实况（落库负结果，避免每次启动重扫）。
     */
    fun scanLivePhotos() {
        scope.launch(Dispatchers.IO) {
            try {
                val known = db.loadLivePhotoMap()
                val photos = _photos.value
                // 已经识别过的（含落库的内嵌/外部）跳过。
                val toCheck = photos.filter {
                    it.liveType == 0 &&
                        it.mimeType.startsWith("image/") &&
                        it.id !in known
                }
                PhotoLog.i("LiveScan", "start: checked=${toCheck.size}, known=${known.size}, total=${photos.size}")
                // 分块扫描，每处理若干张让出一次，避免长期占用 IO 线程影响其它加载。
                var recognized = 0
                toCheck.chunked(40).forEach { chunk ->
                    if (!isActive) return@launch
                    val batch = ArrayList<PhotoODb.LiveRow>(chunk.size)
                    val updates = HashMap<Long, Long>() // id -> offset（仅实况需要更新内存标记）
                    chunk.forEach { photo ->
                        val offset = detectEmbeddedLive(photo)
                        when {
                            offset > 0 -> {
                                batch += PhotoODb.LiveRow(id = photo.id, type = 2, videoOffset = offset, cachedPath = null)
                                updates[photo.id] = offset
                                recognized++
                            }
                            offset == -1L -> {
                                // 确定是实况但偏移未知，标记 type2/offset=0，抽取时整文件扫描。
                                batch += PhotoODb.LiveRow(id = photo.id, type = 2, videoOffset = 0, cachedPath = null)
                                updates[photo.id] = 0
                                recognized++
                            }
                            else -> {
                                // 不是实况，落库负结果，下次启动直接跳过。
                                batch += PhotoODb.LiveRow(id = photo.id, type = 0, videoOffset = 0, cachedPath = null)
                            }
                        }
                    }
                    if (batch.isNotEmpty()) db.putLivePhotoBatch(batch)
                    if (updates.isNotEmpty()) {
                        _photos.value = _photos.value.map { p ->
                            updates[p.id]?.let { off -> p.copy(liveType = 2, liveOffset = off) } ?: p
                        }
                    }
                    kotlinx.coroutines.yield()
                }
                PhotoLog.i("LiveScan", "done: recognized=$recognized")
            } catch (e: Throwable) {
                PhotoLog.e("LiveScan", "failed: $e")
                Log.w("PhotoO", "scanLivePhotos failed", e)
            }
        }
    }

    /**
     * 把日志写入系统「下载」目录（MediaStore，无需权限）并返回其 content Uri，
     * 供调用方通过系统分享面板发出；没有日志或写入失败返回 null。
     */
    fun shareDebugLogUri(): Uri? {
        val log = PhotoLog.file()
        if (log == null || !log.exists()) return null
        return runCatching {
            val name = "PhotoO-log-${java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.US
            ).format(java.util.Date())}.txt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                log.inputStream().use { it.copyTo(out) }
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            uri
        }.getOrNull()
    }

    // ------------------------------------------------------------ 拍摄坐标

    /**
     * 后台扫描 EXIF 里的 GPS 坐标，供地图页使用。
     *
     * 与 Live 扫描同样的思路：结果（含"这张没有坐标"）一律落库，
     * 下次启动只处理新增照片，避免每次开图库都把上万张 EXIF 重读一遍。
     * 不做反地理编码——那要走系统 Geocoder、很慢，交给地图页对可见的簇按需解析。
     */
    fun scanGeo(force: Boolean = false) {
        if (geoScanning) return
        geoScanning = true
        scope.launch(Dispatchers.IO) {
            try {
                if (force) db.clearGeo()
                val known = if (force) emptyMap() else db.loadGeoMap()
                val all = _photos.value
                // 先把已知结果推上去，界面立刻有内容，不用等这轮扫描跑完。
                publishGeo(known.values.filter { it.located })

                val todo = all.filter { it.id !in known }
                if (todo.isEmpty()) {
                    _geoScanState.value = GeoScanState.Done(known.values.count { it.located })
                    return@launch
                }

                var done = 0
                val collected = known.values.filter { it.located }.toMutableList()
                todo.chunked(50).forEach { chunk ->
                    if (!isActive) return@launch
                    val rows = chunk.map { photo ->
                        val info = runCatching {
                            exif.read(photo.id, photo.uri, resolvePlace = false)
                        }.getOrNull()
                        val lat = info?.latitude
                        val lon = info?.longitude
                        val ok = lat != null && lon != null && CoordTransform.isValid(lat, lon)
                        PhotoODb.GeoRow(
                            id = photo.id,
                            lat = if (ok) lat!! else 0.0,
                            lon = if (ok) lon!! else 0.0,
                            located = ok,
                        )
                    }
                    db.putGeoBatch(rows)
                    collected += rows.filter { it.located }
                    done += chunk.size
                    _geoScanState.value = GeoScanState.Running(done, todo.size)
                    publishGeo(collected)
                    kotlinx.coroutines.yield()
                }
                _geoScanState.value = GeoScanState.Done(collected.size)
            } catch (e: Throwable) {
                Log.w("PhotoO", "scanGeo failed", e)
                _geoScanState.value = GeoScanState.Idle
            } finally {
                geoScanning = false
            }
        }
    }

    private fun publishGeo(rows: Collection<PhotoODb.GeoRow>) {
        _geoPoints.value = rows.map { GeoPoint(it.id, it.lat, it.lon) }
    }

    /** 地图页按需解析某个簇的地名，结果走 ExifReader 自己的缓存。 */
    suspend fun placeOf(lat: Double, lon: Double, sampleId: Long, sampleUri: Uri): String? =
        runCatching { exif.read(sampleId, sampleUri, resolvePlace = true).place }.getOrNull()

    /**
     * 反查一个坐标的地名，优先用高德（联网、地址更准），失败或没填 key 时回退到设备 Geocoder。
     * 注意：地图渲染走的是高德 JS API（同一个 Web 端 key），所以这里不再额外要 Web 服务 key。
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        if (!CoordTransform.isValid(lat, lon)) return null
        val s = prefs.current
        // 开启云端且填了 key：走高德 REST 逆地理（更准）；
        // 否则一律用本机 Geocoder（离线 / 隐私）。开关在设置「云端地址解析」里控制。
        if (s.amapKey.isNotBlank() && s.amapCloud) {
            val fromAmap = amapRegeo(lat, lon, s.amapKey)
            if (fromAmap != null) {
                PhotoLog.i("Geo", "regeo-amap-ok: $lat,$lon -> $fromAmap")
                return fromAmap
            }
            PhotoLog.w("Geo", "regeo-amap-failed: $lat,$lon fallback-to-geocoder")
        }
        val local = runCatching { exif.geocode(lat, lon) }.getOrNull()
        if (local == null) PhotoLog.w("Geo", "regeo-geocoder-failed: $lat,$lon")
        return local
    }

    /** 高德逆地理编码 REST（坐标需先转 GCJ-02）。 */
    private suspend fun amapRegeo(lat: Double, lon: Double, key: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val g = CoordTransform.wgs84ToGcj02(lat, lon)
                val loc = "%.6f,%.6f".format(java.util.Locale.US, g.lon, g.lat)
                val url =
                    "https://restapi.amap.com/v3/geocode/regeo?output=JSON&location=$loc" +
                        "&key=$key&radius=1000&extensions=base"
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val resp = if (conn.responseCode == 200) {
                    conn.inputStream.bufferedReader().readText()
                } else null
                conn.disconnect()
                resp?.let { parseAmapRegeo(it) }
            }.getOrNull()
        }

    private fun parseAmapRegeo(json: String): String? {
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("status") != "1") return null
        val regeo = root.optJSONObject("regeocode") ?: return null
        regeo.optString("formatted_address").takeIf { it.isNotBlank() }?.let { return it }
        val ac = regeo.optJSONObject("addressComponent") ?: return null
        val province = ac.optString("province").orEmpty()
        val city = ac.optString("city").takeIf { it.isNotBlank() && it != province } ?: ""
        return listOf(
            province,
            city,
            ac.optString("district"),
            ac.optString("township"),
            ac.optString("street"),
            ac.optString("streetNumber"),
        ).filter { it.isNotBlank() }.joinToString("")
    }

    /**
     * 解析某张 Live Photo 的可播放视频 Uri。
     * - type==1：直接返回同名视频的内容 Uri。
     * - type==2：首次播放时从图片文件抽取内嵌视频到缓存目录（只做一次，路径落库），
     *            之后直接返回缓存文件 Uri。
     */
    suspend fun resolveLiveVideo(photo: PhotoItem): Uri? {
        return withContext(Dispatchers.IO) {
            when (photo.liveType) {
                1 -> {
                    PhotoLog.i("LivePlay", "type1: id=${photo.id} name=${photo.displayName} uri=${photo.liveVideoUri}")
                    photo.liveVideoUri
                }
                2 -> {
                    val cached = db.getLiveCachePath(photo.id)
                    if (cached != null) {
                        PhotoLog.i("LivePlay", "type2-cached: id=${photo.id} name=${photo.displayName} path=$cached")
                        return@withContext Uri.fromFile(java.io.File(cached))
                    }
                    val dir = java.io.File(context.cacheDir, "livephoto").also { it.mkdirs() }
                    val out = java.io.File(dir, "${photo.id}.mp4")
                    if (extractEmbeddedVideo(photo, out)) {
                        db.setLiveCachePath(photo.id, out.absolutePath)
                        PhotoLog.i("LivePlay", "type2-extracted: id=${photo.id} name=${photo.displayName} size=${out.length()}")
                        Uri.fromFile(out)
                    } else {
                        PhotoLog.e("LivePlay", "type2-extract-failed: id=${photo.id} name=${photo.displayName} type=${photo.liveType} offset=${photo.liveOffset} size=${photo.size}")
                        null
                    }
                }
                else -> {
                    PhotoLog.w("LivePlay", "not-live: id=${photo.id} name=${photo.displayName} type=${photo.liveType}")
                    null
                }
            }
        }
    }

    /**
     * 读取图片头部，从 XMP 里找内嵌视频的字节偏移。
     * 覆盖 Google Motion Photo / 小米(HyperOS) / 华为 / Pixel / 三星 HEIC 用的方案：
     * - `GCamera:MicroVideoOffset="N"`：视频流从文件头第 N 字节开始（属性式、最常见）；
     * - `<GCamera:MicroVideoOffset>N</GCamera:MicroVideoOffset>`：元素式写法；
     * - 三星 HEIC：`Container:ItemLocation="N"` 给出内嵌视频在文件中的偏移。
     * 读不到标记返回 0（不是实况）；有标记但偏移读不出返回 -1（让抽取阶段整文件扫描）。
     */
    private fun detectEmbeddedLive(photo: PhotoItem): Long {
        val result = runCatching {
            val head = ByteArray(256 * 1024)
            val read = resolver().openInputStream(photo.uri)?.use { it.read(head) } ?: return 0
            if (read <= 0) return 0
            val text = String(head, 0, read, Charsets.US_ASCII)
            // 必须先确认这是带实况标记的文件，再取偏移，避免误判普通图片。
            val hasMarker = text.contains("MicroVideo", ignoreCase = true) ||
                text.contains("MotionPhoto", ignoreCase = true) ||
                text.contains("GCamera", ignoreCase = true) ||
                text.contains("MotionPhotoData", ignoreCase = true) ||
                text.contains("Container:Directory", ignoreCase = true) ||
                text.contains("Container:Item", ignoreCase = true)
            if (!hasMarker) {
                PhotoLog.i("LiveScan", "no-marker: id=${photo.id} name=${photo.displayName} mime=${photo.mimeType}")
                return 0
            }
            // 依次尝试几种偏移写法；命中任意一个就直接用。
            parseOffset(text, "MicroVideoOffset")?.let { return it }
            parseOffset(text, "Container:ItemLocation")?.let { return it }
            PhotoLog.w("LiveScan", "marker-but-no-offset: id=${photo.id} name=${photo.displayName} mime=${photo.mimeType}")
            // 有实况标记但 XMP 偏移损坏/读不出：返回 -1 让抽取阶段整文件扫描定位视频。
            -1L
        }.getOrDefault(0L)
        if (result > 0) {
            PhotoLog.i("LiveScan", "embedded: id=${photo.id} name=${photo.displayName} offset=$result mime=${photo.mimeType}")
        }
        return result
    }

    /**
     * 在 XMP 文本里按属性式（`name="123"` / `name:'123'` / `name: 123`）和元素式
     * （`<name>123</name>`）两种写法提取第一个数字偏移，读不出返回 null。
     */
    private fun parseOffset(text: String, name: String): Long? {
        val attr = Regex("""$name["']?\s*[:=]\s*['"]?(\d+)""", RegexOption.IGNORE_CASE)
        attr.find(text)?.let { return it.groupValues[1].toLongOrNull() }
        val elem = Regex("""$name>\s*(\d+)\s*<""", RegexOption.IGNORE_CASE)
        elem.find(text)?.let { return it.groupValues[1].toLongOrNull() }
        return null
    }

    /**
     * 从图片文件抽取内嵌视频字节到 out 文件。
     * 依次尝试：(1) XMP 给出的偏移（从头算）；(2) 从文件尾算起的剩余长度（部分机型）；
     * (3) 整文件扫描定位 ISO BMFF 视频盒（Xiaomi 偏移常不准，这一步兜底）。
     * 第 (3) 步成功后把真实偏移回填数据库，下次抽取直接走 (1)。
     */
    private fun extractEmbeddedVideo(photo: PhotoItem, out: java.io.File): Boolean {
        return try {
            val resolver = resolver()
            val offset = photo.liveOffset
            // 优先：偏移从文件头算起。
            if (offset > 0 &&
                tryExtract(resolver, photo.uri, offset, out) && isValidVideo(out)
            ) {
                PhotoLog.i("LiveExtract", "ok-by-offset: id=${photo.id} name=${photo.displayName} offset=$offset")
                true
            } else if (offset > 0) {
                // 回退：部分机型偏移是从文件尾算起的剩余字节长度。
                val fromEnd = (photo.size - offset).coerceAtLeast(0)
                if (fromEnd > 0 && tryExtract(resolver, photo.uri, fromEnd, out) && isValidVideo(out)) {
                    PhotoLog.i("LiveExtract", "ok-by-fromEnd: id=${photo.id} name=${photo.displayName} offset=$offset size=${photo.size}")
                    true
                } else {
                    fullScanFallback(photo, out, offset)
                }
            } else {
                fullScanFallback(photo, out, offset)
            }
        } catch (e: Throwable) {
            PhotoLog.e("LiveExtract", "exception: id=${photo.id} name=${photo.displayName} e=$e")
            false
        }
    }

    /** 整文件扫描定位内嵌视频（Xiaomi 偏移常不准 / 偏移缺失时兜底），成功后回填真实偏移。 */
    private fun fullScanFallback(photo: PhotoItem, out: java.io.File, offset: Long): Boolean {
        val found = findEmbeddedVideoStart(resolver(), photo.uri, photo.size)
        if (found >= 0 && tryExtract(resolver(), photo.uri, found, out) && isValidVideo(out)) {
            db.setLiveOffset(photo.id, found) // 记下真实偏移，下次无需再全扫
            PhotoLog.i("LiveExtract", "ok-by-fullscan: id=${photo.id} name=${photo.displayName} found=$found")
            return true
        }
        PhotoLog.w("LiveExtract", "FAIL: id=${photo.id} name=${photo.displayName} offset=$offset size=${photo.size} found=$found")
        return false
    }

    private fun tryExtract(resolver: android.content.ContentResolver, uri: Uri, skip: Long, out: java.io.File): Boolean {
        resolver.openInputStream(uri)?.use { input ->
            val buffered = java.io.BufferedInputStream(input)
            // 跳过指定字节。
            var remaining = skip
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val n = buffered.read(buf, 0, minOf(buf.size, remaining.toInt()))
                if (n < 0) break
                remaining -= n
            }
            out.outputStream().use { outStream ->
                var total = 0L
                var r: Int
                while (buffered.read(buf).also { r = it } >= 0) {
                    outStream.write(buf, 0, r)
                    total += r
                    if (total > 200L * 1024 * 1024) break // 安全上限
                }
            }
        }
        return out.exists() && out.length() > 0
    }

    /**
     * 简单校验抽出来的确实是视频（ISO BMFF：以 size + 'ftyp' 开头的盒）。
     * MP4/MOV/3GP 都符合，品牌（isom / mp42 / 1sav / 3g2 / qt 等）不影响判定。
     */
    private fun isValidVideo(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        val head = ByteArray(12)
        file.inputStream().use { it.read(head) }
        val isFtyp = head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        if (!isFtyp) return false
        val boxSize = ((head[0].toLong() and 0xFF) shl 24) or
            ((head[1].toLong() and 0xFF) shl 16) or
            ((head[2].toLong() and 0xFF) shl 8) or
            (head[3].toLong() and 0xFF)
        return boxSize >= 8L && boxSize <= file.length()
    }

    /**
     * 整文件扫描定位内嵌视频起始字节。
     *
     * 实况视频通常追加在 JPEG 之后、文件末尾，盒起点 = ftyp 位置 - 4。
     * 早期只扫尾部 8MB，对小米"录像时同步拍"这类大文件（视频常落在离尾部 8MB 之外）
     * 会漏掉 ftyp 导致整段无法播放。这里改为**扫描整个文件**，取最后一个有效的
     * 'ftyp' 盒作为视频起点（文件里唯一的 ftyp 就是实况视频容器）。
     *
     * 找不到返回 -1。
     */
    private fun findEmbeddedVideoStart(
        resolver: android.content.ContentResolver,
        uri: Uri,
        fileLen: Long,
    ): Long {
        if (fileLen <= 16) return -1
        val chunk = 4 * 1024 * 1024
        val overlap = 32 // 足够容纳 8 字节盒头，避免 ftyp 被分块边界截断时漏检
        val buf = ByteArray(chunk + overlap)
        var lastFound = -1L
        var base = 0L // buf[0] 对应的文件绝对偏移
        var prevLen = 0 // 上一块末尾带过来的重叠字节数（下一个 ftyp 起始可能跨块）
        resolver.openInputStream(uri)?.use { input ->
            while (true) {
                val space = buf.size - prevLen
                val n = input.read(buf, prevLen, space)
                if (n <= 0) break
                val total = prevLen + n
                for (i in 0 until total - 4) {
                    if (buf[i] == 'f'.code.toByte() && buf[i + 1] == 't'.code.toByte() &&
                        buf[i + 2] == 'y'.code.toByte() && buf[i + 3] == 'p'.code.toByte()
                    ) {
                        val boxStart = i - 4
                        if (boxStart >= 0) {
                            val boxSize = readU32(buf, boxStart)
                            val abs = base + boxStart
                            // 盒要落在文件内；末尾不足一个完整盒的尾巴也接受
                            // （实况视频常紧贴文件尾，盒大小字段可能因填充略有偏差）。
                            if (boxSize >= 8L &&
                                (abs + boxSize <= fileLen || fileLen - abs < 2L * 1024 * 1024)
                            ) {
                                lastFound = abs
                            }
                        }
                    }
                }
                prevLen = minOf(overlap, total)
                // 把本块末尾 prevLen 字节搬到 buf 开头，作为下一块的重叠区。
                System.arraycopy(buf, total - prevLen, buf, 0, prevLen)
                base += total - prevLen
            }
        }
        return lastFound
    }

    private fun readU32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or
            ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or
            (b[i + 3].toLong() and 0xFF)

    private fun resolver() = context.contentResolver

    // ------------------------------------------------------------------ 相似图

    /** 全量扫描；已经算过哈希的照片会跳过，所以第二次扫描非常快。 */
    fun scanSimilar(force: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _scanState.value = ScanState.Running(0, 0)
            try {
                if (force) {
                    withContext(Dispatchers.IO) { db.clearHashes() }
                    hashCache.clear()
                } else if (hashCache.isEmpty()) {
                    hashCache = withContext(Dispatchers.IO) { db.loadHashes() }
                }

                val all = _photos.value
                val todo = all.filter { it.id !in hashCache }
                _scanState.value = ScanState.Running(0, todo.size)

                if (todo.isNotEmpty()) {
                    val done = AtomicInteger(0)
                    val chunkSize = 64
                    todo.chunked(chunkSize).forEach { chunk ->
                        if (!isActive) return@launch
                        val computed = withContext(Dispatchers.IO) {
                            chunk.map { photo ->
                                async {
                                    PerceptualHash.compute(context, photo.id, photo.uri)
                                        .also { done.incrementAndGet() }
                                }
                            }.awaitAll().filterNotNull()
                        }
                        withContext(Dispatchers.IO) { db.putHashes(computed) }
                        computed.forEach { hashCache[it.id] = it }
                        _scanState.value = ScanState.Running(done.get(), todo.size)
                    }
                }

                rebuildGroups()
                val groups = _similarGroups.value
                _scanState.value = ScanState.Done(
                    groups = groups.size,
                    photos = groups.sumOf { it.size },
                )
            } catch (e: Throwable) {
                _scanState.value = ScanState.Failed(e.message ?: "扫描失败")
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _scanState.value = ScanState.Idle
    }

    /** 阈值 / 保留策略变了不需要重新算哈希，重新聚类即可。 */
    fun rebuildGroups(
        level: SimilarityLevel = prefs.current.similarityLevel,
        strategy: KeepStrategy = prefs.current.keepStrategy,
    ) {
        scope.launch {
            if (hashCache.isEmpty()) {
                hashCache = withContext(Dispatchers.IO) { db.loadHashes() }
            }
            if (hashCache.isEmpty()) {
                _similarGroups.value = emptyList()
                return@launch
            }
            val resolved = withContext(Dispatchers.IO) { db.listResolvedGroups() }
            val snapshot = _photos.value
            val groups = withContext(Dispatchers.Default) {
                SimilarityEngine.buildGroups(snapshot, hashCache, level, strategy, resolved)
            }
            _similarGroups.value = groups
        }
    }

    fun resolveGroup(key: String) {
        scope.launch {
            withContext(Dispatchers.IO) { db.markGroupResolved(key) }
            _similarGroups.value = _similarGroups.value.map {
                if (it.key == key) it.copy(resolved = true) else it
            }
        }
    }

    fun clearGroupDecisions() {
        scope.launch {
            withContext(Dispatchers.IO) { db.clearGroupDecisions() }
            rebuildGroups()
            emit("已清空相似组处理记录")
        }
    }

    /** 按当前策略，把每组里"不保留"的那些一次性丢进回收站。 */
    fun applyStrategyToGroups(groups: List<SimilarGroup>) {
        val victims = groups.flatMap { g -> g.items.filter { it.id != g.suggestedKeepId } }
        if (victims.isEmpty()) {
            emit("没有需要清理的照片")
            return
        }
        moveToTrash(victims.map { it.id })
        groups.forEach { resolveGroup(it.key) }
        val keepers = groups.map { it.suggestedKeepId }
        markReviewed(keepers, ReviewAction.KEPT)
    }

    fun emit(text: String) {
        _messages.tryEmit(text)
    }
}
