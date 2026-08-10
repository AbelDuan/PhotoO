package com.abel.photoo.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.abel.photoo.data.db.PhotoODb
import com.abel.photoo.data.exif.ExifReader
import com.abel.photoo.data.media.MediaOps
import com.abel.photoo.data.media.MediaRequestBroker
import com.abel.photoo.data.media.MediaStoreSource
import com.abel.photoo.data.prefs.AppPrefs
import com.abel.photoo.data.similar.PerceptualHash
import com.abel.photoo.data.similar.SimilarityEngine
import com.abel.photoo.model.AlbumItem
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

    private val _messages = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var observing = false

    /** 权限到位后调用一次；重复调用是安全的。 */
    fun startObserving() {
        if (observing) return
        observing = true
        scope.launch { refresh() }
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

            val liveIds = raw.mapTo(HashSet(raw.size)) { it.id }
            // 系统里已经消失的条目，回收站里也要清掉，否则会留下点不开的空壳。
            val stale = trashRows.filter { it.id !in liveIds }.map { it.id }
            if (stale.isNotEmpty()) {
                withContext(Dispatchers.IO) { db.removeTrash(stale, alsoClearState = false) }
            }
            val trashIds = trashRows.mapNotNull { it.id.takeIf { id -> id in liveIds } }.toHashSet()

            val decorated = raw.map { p ->
                val s = states[p.id]
                if (s == null) p else p.copy(
                    reviewed = s.reviewed,
                    reviewAction = s.action,
                    favorite = s.favorite,
                )
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
            _albums.value = mediaAlbums + placeholders

            _stats.value = LibraryStats(
                total = visible.size,
                reviewed = visible.count { it.reviewed },
                trashed = _trash.value.size,
                albums = _albums.value.size,
            )

            // 图库变化后旧的分组可能已经失效，用现存哈希重算一遍（很快，不需要重新扫描）。
            if (hashCache.isNotEmpty()) rebuildGroups()
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
        val targets = _photos.value.filter { it.id in ids.toHashSet() }
        if (targets.isEmpty()) return

        scope.launch {
            if (prefs.current.alsoSystemTrash) {
                val result = ops.setSystemTrashed(targets.map { it.uri }, true)
                if (result is OpResult.Cancelled) {
                    emit("已取消删除")
                    return@launch
                }
                if (result is OpResult.Failure) {
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
            refresh()
            emit("已移入回收站 ${rows.size} 张")
        }
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
