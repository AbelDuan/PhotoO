package com.abel.photoo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abel.photoo.PhotoOApp
import com.abel.photoo.model.AlbumItem
import com.abel.photoo.model.ExifInfo
import com.abel.photoo.model.GestureAction
import com.abel.photoo.model.GestureDirection
import com.abel.photoo.model.GestureSensitivity
import com.abel.photoo.model.KeepStrategy
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.ReviewAction
import com.abel.photoo.model.SimilarGroup
import com.abel.photoo.model.SimilarityLevel
import com.abel.photoo.model.TimelineGrouping
import com.abel.photoo.data.prefs.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "撤销"动作携带的信息：最近一次移入回收站的照片数量与 id 集合。
 * 界面据此弹出带"撤销"按钮的 Snackbar，点击后把这些照片放回图库。
 */
data class UndoEvent(
    val count: Int,
    val ids: Set<Long>,
    /** 被删除的照片本体，撤销时同步放回内存图库，大图页才能立刻跳回这张照片。 */
    val items: List<PhotoItem> = emptyList(),
)

/**
 * 全应用共用的一个 ViewModel。
 *
 * 这个应用的各个界面共享同一份图库快照与同一套选择状态（在时间线里选中的照片，
 * 切到相册页仍然有效），拆成多个 ViewModel 反而要额外做同步，得不偿失。
 */
class PhotoOViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PhotoOApp).container

    val repo = container.repository
    val prefs = container.prefs
    val broker = container.broker

    val photos = repo.photos
    val albums = repo.albums
    val trash = repo.trash
    val stats = repo.stats
    val similarGroups = repo.similarGroups
    val scanState = repo.scanState
    val loading = repo.loading
    val messages = repo.messages
    val settings = prefs.settings
    /** 设置页二级分组收起状态（持久化），供设置页记忆展开/收起。 */
    val collapsedGroups = prefs.collapsedGroups
    fun setGroupExpanded(key: String, expanded: Boolean) = prefs.setGroupExpanded(key, expanded)
    val geoPoints = repo.geoPoints
    val geoScanState = repo.geoScanState

    /**
     * Live Photo 是否静音。
     *
     * 初始值取自设置里的「Live Photo 默认静音」（仅影响下次启动）；
     * 本次会话中由大图页 LIVE 标旁的开关实时控制，不回写该设置。
     */
    private val _liveMuted = MutableStateFlow(prefs.current.liveMutedDefault)
    val liveMuted: StateFlow<Boolean> = _liveMuted.asStateFlow()

    fun setLiveMuted(muted: Boolean) {
        _liveMuted.value = muted
    }

    /** 多选集合。空集合代表当前不处于多选模式。 */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    /** 相似照片界面里被勾选待删除的照片。与主选择集合分开，避免互相干扰。 */
    private val _similarPicks = MutableStateFlow<Set<Long>>(emptySet())
    val similarPicks: StateFlow<Set<Long>> = _similarPicks.asStateFlow()

    private val _exifCache = MutableStateFlow<Map<Long, ExifInfo>>(emptyMap())
    val exifCache: StateFlow<Map<Long, ExifInfo>> = _exifCache.asStateFlow()

    /**
     * 最近一次"移入回收站"操作携带的照片 id，供界面弹出"撤销"动作恢复。
     * 撤销只把照片从回收站放回图库（不回退相似组的已处理标记）。
     */
    private val _undoEvent = MutableStateFlow<UndoEvent?>(null)
    val undoEvent: StateFlow<UndoEvent?> = _undoEvent.asStateFlow()

    fun undoLastTrash() {
        val ev = _undoEvent.value ?: return
        // 先把照片同步放回内存图库（不等系统/数据库回写），撤销后大图页能立刻
        // 跳回这张照片；随后异步走正式的恢复流程，refresh 会再校准一次。
        repo.reinsertPhotos(ev.items)
        _undoEvent.value = null
        repo.restoreFromTrash(ev.ids)
    }

    fun clearUndoEvent() {
        _undoEvent.value = null
    }

    private var permissionReady = false

    /** 权限是否已到位。没到位之前一切 MediaStore 查询都会抛 SecurityException。 */
    val ready: Boolean get() = permissionReady

    fun onPermissionGranted() {
        if (permissionReady) return
        permissionReady = true
        repo.startObserving()
    }

    fun refresh() {
        viewModelScope.launch { repo.refresh() }
    }

    /** 供 Activity 的 onResume 调用：权限还没拿到时什么都不做。 */
    fun refreshIfReady() {
        if (permissionReady) refresh()
    }

    // ---------------------------------------------------------------- 选择状态

    val selectionMode: Boolean get() = _selection.value.isNotEmpty()

    fun toggleSelect(id: Long) {
        val cur = _selection.value
        _selection.value = if (id in cur) cur - id else cur + id
    }

    fun select(ids: Collection<Long>) {
        _selection.value = _selection.value + ids
    }

    /** 长按拖动连续选择：只追加、不取消，起点之外滑过的照片一路选中。 */
    fun addSelection(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        _selection.value = _selection.value + ids
    }

    /** 相似照片的"长按拖动连续选择"追加版（只加不减）。 */
    fun addSimilarPicks(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        _similarPicks.value = _similarPicks.value + ids
    }

    fun replaceSelection(ids: Collection<Long>) {
        _selection.value = ids.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectedPhotos(): List<PhotoItem> {
        val ids = _selection.value
        return photos.value.filter { it.id in ids }
    }

    // -------------------------------------------------------------- 相似组选择

    fun toggleSimilarPick(id: Long) {
        val cur = _similarPicks.value
        _similarPicks.value = if (id in cur) cur - id else cur + id
    }

    fun setSimilarPicks(ids: Collection<Long>) {
        _similarPicks.value = ids.toSet()
    }

    fun clearSimilarPicks() {
        _similarPicks.value = emptySet()
    }

    /** 按当前策略，把所有未处理组里"该删的"预勾选出来，让用户复核后再动手。 */
    fun preselectByStrategy(groups: List<SimilarGroup>) {
        if (prefs.current.keepStrategy == KeepStrategy.MANUAL) {
            repo.emit("当前是手动策略，请逐组挑选")
            return
        }
        val victims = groups.filterNot { it.resolved }
            .flatMap { g -> g.items.filter { it.id != g.suggestedKeepId } }
            .map { it.id }
        _similarPicks.value = victims.toSet()
        repo.emit("已按「${prefs.current.keepStrategy.label}」预选 ${victims.size} 张")
    }

    fun trashSimilarPicks() {
        val ids = _similarPicks.value
        if (ids.isEmpty()) {
            repo.emit("还没有勾选任何照片")
            return
        }
        val items = photos.value.filter { it.id in ids }
        val affected = similarGroups.value.filter { g -> g.items.any { it.id in ids } }
        repo.moveToTrash(ids)
        // 组内其余留下来的都算"已保留"，这样整理进度才会走。
        val keepers = affected.flatMap { g -> g.items.filterNot { it.id in ids } }.map { it.id }
        if (keepers.isNotEmpty()) repo.markReviewed(keepers, ReviewAction.KEPT)
        affected.forEach { repo.resolveGroup(it.key) }
        _similarPicks.value = emptySet()
        if (ids.isNotEmpty()) _undoEvent.value = UndoEvent(ids.size, ids, items)
    }

    // ------------------------------------------------------------------ EXIF

    /** 大图页按需读取，读完缓存起来；同一张不会重复解析。 */
    fun loadExif(photo: PhotoItem) {
        if (_exifCache.value.containsKey(photo.id)) return
        viewModelScope.launch {
            val info = repo.exif.read(photo.id, photo.uri, prefs.current.showLocation)
            _exifCache.value = _exifCache.value + (photo.id to info)
        }
    }

    // ------------------------------------------------------------ 转发给数据层

    fun moveToTrash(ids: Collection<Long>) {
        val set = ids.toSet()
        val items = photos.value.filter { it.id in set }
        repo.moveToTrash(set)
        _selection.value = _selection.value - set
        if (set.isNotEmpty()) _undoEvent.value = UndoEvent(set.size, set, items)
    }

    fun restore(ids: Collection<Long>) = repo.restoreFromTrash(ids)
    fun deleteForever(ids: Collection<Long>) = repo.deleteForever(ids)
    fun emptyTrash() = repo.emptyTrash()

    fun markKept(ids: Collection<Long>) {
        repo.markReviewed(ids, ReviewAction.KEPT)
        _selection.value = _selection.value - ids.toSet()
    }

    fun markSkipped(ids: Collection<Long>) = repo.markReviewed(ids, ReviewAction.SKIPPED)
    fun toggleFavorite(id: Long) = repo.toggleFavorite(id)
    fun resetReviews() = repo.resetAllReviews()

    fun createAlbum(name: String) = repo.createAlbum(name)
    fun createAlbumAndMove(name: String, ids: Collection<Long>) = repo.createAlbumAndMove(name, ids)
    fun renameAlbum(album: AlbumItem, name: String) = repo.renameAlbum(album, name)
    fun deleteEmptyAlbum(album: AlbumItem) = repo.deleteEmptyAlbum(album)
    fun setAlbumOrder(names: List<String>) = repo.setAlbumOrder(names)

    fun moveToAlbum(ids: Collection<Long>, album: AlbumItem) {
        repo.moveToAlbum(ids, album)
        _selection.value = emptySet()
    }

    /** 大图页快捷归入：按相册名一键归档当前照片。 */
    fun moveToAlbumByName(name: String, ids: Collection<Long>) {
        repo.moveToAlbumByName(name, ids)
    }

    /** 解析 Live Photo 的可播放视频 Uri（内嵌型会按需抽取并缓存）。 */
    suspend fun resolveLiveVideo(photo: PhotoItem): Uri? = repo.resolveLiveVideo(photo)

    fun setQuickAlbums(list: List<String>) = prefs.setQuickAlbums(list)

    fun scanSimilar(force: Boolean = false) = repo.scanSimilar(force)
    fun cancelScan() = repo.cancelScan()
    fun resolveGroup(key: String) = repo.resolveGroup(key)
    fun clearGroupDecisions() = repo.clearGroupDecisions()

    fun setThemeMode(mode: ThemeMode) = prefs.setThemeMode(mode)
    fun setDynamicColor(on: Boolean) = prefs.setDynamicColor(on)
    fun setGrouping(g: TimelineGrouping) = prefs.setGrouping(g)
    fun setColumns(n: Int) = prefs.setGridColumns(n)
    fun setAlsoSystemTrash(on: Boolean) = prefs.setAlsoSystemTrash(on)
    fun setResumeReview(on: Boolean) = prefs.setResumeReview(on)
    fun setShowLocation(on: Boolean) = prefs.setShowLocation(on)

    // ------------------------------------------------------------------ 手势

    fun setGesture(dir: GestureDirection, action: GestureAction) = prefs.setGesture(dir, action)
    fun setGestureSensitivity(s: GestureSensitivity) = prefs.setGestureSensitivity(s)
    fun resetGestures() = prefs.resetGestures()

    // ------------------------------------------------------- Live Photo / 地图

    fun setLiveAutoPlay(on: Boolean) = prefs.setLiveAutoPlay(on)
    fun setLiveMutedDefault(on: Boolean) = prefs.setLiveMutedDefault(on)
    fun setAmapKey(key: String) = prefs.setAmapKey(key)
    fun setAmapCloud(on: Boolean) = prefs.setAmapCloud(on)

    /** 把调试日志写入「下载」目录并返回其 content Uri，供设置页通过系统分享面板发出。 */
    fun shareDebugLogUri(): Uri? = repo.shareDebugLogUri()

    /** 扫描全库 EXIF 里的 GPS 信息，结果落库复用。 */
    fun scanGeo(force: Boolean = false) = repo.scanGeo(force)

    fun setKeepStrategy(s: KeepStrategy) {
        prefs.setKeepStrategy(s)
        repo.rebuildGroups(strategy = s)
        _similarPicks.value = emptySet()
    }

    fun setSimilarityLevel(l: SimilarityLevel) {
        prefs.setSimilarityLevel(l)
        repo.rebuildGroups(level = l)
        _similarPicks.value = emptySet()
    }

    fun toast(text: String) = repo.emit(text)

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return PhotoOViewModel(app) as T
            }
        }
    }
}
