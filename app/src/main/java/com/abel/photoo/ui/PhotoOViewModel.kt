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
    val geoPoints = repo.geoPoints
    val geoScanState = repo.geoScanState

    /**
     * Live Photo 是否静音。
     *
     * 刻意不落盘：需求是"每次打开应用第一次播放都默认静音"，
     * 所以它的生命周期就应该跟着进程走 —— ViewModel 活多久它活多久。
     */
    private val _liveMuted = MutableStateFlow(true)
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
        val affected = similarGroups.value.filter { g -> g.items.any { it.id in ids } }
        repo.moveToTrash(ids)
        // 组内其余留下来的都算"已保留"，这样整理进度才会走。
        val keepers = affected.flatMap { g -> g.items.filterNot { it.id in ids } }.map { it.id }
        if (keepers.isNotEmpty()) repo.markReviewed(keepers, ReviewAction.KEPT)
        affected.forEach { repo.resolveGroup(it.key) }
        _similarPicks.value = emptySet()
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
        repo.moveToTrash(ids)
        _selection.value = _selection.value - ids.toSet()
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
    fun setAmapKey(key: String) = prefs.setAmapKey(key)

    /** 手动重新扫描实况照片（清空识别结果后重扫），设置页"重新扫描实况照片"调用。 */
    fun rescanLivePhotos() = repo.rescanLivePhotos()

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
