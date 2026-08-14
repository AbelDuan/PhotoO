package com.abel.photoo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abel.photoo.model.AlbumItem
import com.abel.photoo.model.GeoCluster
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.ui.components.AlbumPickerSheet
import com.abel.photoo.ui.components.ConfirmDialog
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.components.TextInputDialog
import com.abel.photoo.ui.screens.AlbumDetailScreen
import com.abel.photoo.ui.screens.AlbumsScreen
import com.abel.photoo.ui.screens.MapClusterDetailScreen
import com.abel.photoo.ui.screens.MapScreen
import com.abel.photoo.ui.screens.ReviewScreen
import com.abel.photoo.ui.screens.SettingsScreen
import com.abel.photoo.ui.screens.SimilarGroupDetailScreen
import com.abel.photoo.ui.screens.SimilarScreen
import com.abel.photoo.ui.screens.TimelineScreen
import com.abel.photoo.ui.screens.TrashScreen
import com.abel.photoo.ui.screens.ViewerScreen
import com.abel.photoo.ui.util.Format

/** 底部主 Tab。 */
private enum class Tab(val label: String, val icon: ImageVector) {
    TIMELINE("时间线", Icons.Rounded.Photo),
    ALBUMS("相册", Icons.Rounded.Folder),
    MAP("地图", Icons.Rounded.Map),
    SIMILAR("相似", Icons.Rounded.ContentCopy),
    SETTINGS("设置", Icons.Rounded.Settings),
}

/** 大图查看器的打开参数。存 id 而不是对象，照片被删后列表会自动收缩。 */
private data class ViewerRequest(val ids: List<Long>, val initialId: Long)

/**
 * 应用的导航根。
 *
 * 页面很少且大量共享同一份状态，用 Navigation 组件反而要为传递 AlbumItem 做序列化，
 * 所以这里用最直白的状态机：四个 Tab + 若干覆盖层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoORoot(vm: PhotoOViewModel) {

    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasMediaPermission(context) }

    LaunchedEffect(granted) {
        if (granted) vm.onPermissionGranted()
    }
    // 首次进入直接弹权限框，不让用户先看一屏空白再点一次。
    LaunchedEffect(Unit) {
        if (!hasMediaPermission(context)) permissionLauncher.launch(requiredPermissions())
    }

    if (!granted) {
        PermissionGate(
            onRequest = { permissionLauncher.launch(requiredPermissions()) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
        return
    }

    val photos by vm.photos.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val exif by vm.exifCache.collectAsStateWithLifecycle()
    val liveMuted by vm.liveMuted.collectAsStateWithLifecycle()
    val similarGroups by vm.similarGroups.collectAsStateWithLifecycle()
    val undoEvent by vm.undoEvent.collectAsStateWithLifecycle()
    val staged by vm.stagedMoves.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.TIMELINE) }
    var albumDetail by remember { mutableStateOf<AlbumItem?>(null) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var trashOpen by rememberSaveable { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var similarDrillKey by remember { mutableStateOf<String?>(null) }
    var mapCluster by remember { mutableStateOf<GeoCluster?>(null) }

    var pickerTargets by remember { mutableStateOf<List<Long>?>(null) }
    var creatingAlbum by remember { mutableStateOf(false) }
    var confirmTrashSelection by remember { mutableStateOf(false) }
    // 相册选择器把照片归入完成后，用它通知大图页"处理完→按方向切走"。
    var advanceSignal by remember { mutableStateOf(0) }

    val snackbar = remember { SnackbarHostState() }

    // 数据层的提示统一走 Snackbar，各界面就不用各自处理了。
    LaunchedEffect(Unit) {
        vm.messages.collect { text ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    // 删除后弹一条轻提示；撤销交给右下角悬浮按钮（FAB 常驻显示直到被消费），
    // 这里不再带"撤销"动作，避免和 FAB 重复。
    LaunchedEffect(Unit) {
        vm.undoToast.collect { text ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    // 撤销并跳回被删（已恢复）的那张照片：恢复后照片同步回到内存图库，
    // 用新的 ViewerRequest 重启大图页，停留在刚恢复的照片上。
    fun handleUndo() {
        val ev = vm.undoEvent.value ?: return
        val target = ev.ids.firstOrNull() ?: return
        vm.undoLastTrash()
        val ids = vm.photos.value.map { it.id }
        viewer = ViewerRequest(ids, target)
    }

    // "启动时继续整理"：等第一批数据到位再判断，否则开屏时 stats 还是 0。
    var resumeChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(stats.total, settings.resumeReviewOnLaunch) {
        if (!resumeChecked && settings.resumeReviewOnLaunch && stats.total > 0) {
            resumeChecked = true
            if (stats.pending > 0) reviewing = true
        }
    }

    // ------------------------------------------------------- 独占式全屏覆盖页

    if (reviewing) {
        BackHandler { reviewing = false }
        ReviewScreen(
            vm = vm,
            onExit = { reviewing = false },
            onMoveToAlbum = { pickerTargets = listOf(it.id) },
        )
        AlbumPickerHost(
            vm, albums, pickerTargets, { creatingAlbum = true },
            onDismiss = { pickerTargets = null },
            onAfterPick = {
                if (viewer != null) advanceSignal++
                else vm.flushStagedMoves()
            },
        )
        NewAlbumDialogHost(vm, creatingAlbum) { creatingAlbum = false }
        return
    }

    if (trashOpen) {
        BackHandler { trashOpen = false }
        TrashScreen(vm = vm, onBack = { trashOpen = false })
        return
    }

    // ---------------------------------------------------------------- 主框架

    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when (tab) {
                                Tab.TIMELINE -> "PhotoO"
                                Tab.ALBUMS -> "相册"
                                Tab.MAP -> "地图分布"
                                Tab.SIMILAR -> "相似照片"
                                Tab.SETTINGS -> "设置"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        if (tab != Tab.SETTINGS) {
                            IconButton(onClick = { trashOpen = true }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "回收站")
                            }
                            IconButton(onClick = vm::refresh) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = {
                                if (tab != entry) vm.clearSelection()
                                tab = entry
                            },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            },
        ) { inner ->
            when (tab) {
                Tab.TIMELINE -> TimelineScreen(
                    vm = vm,
                    contentPadding = inner,
                    onOpenPhoto = { photo ->
                        viewer = ViewerRequest(photos.map { it.id }, photo.id)
                    },
                    onStartReview = { reviewing = true },
                    onOpenSimilar = { tab = Tab.SIMILAR },
                )

                Tab.ALBUMS -> AlbumsScreen(
                    vm = vm,
                    contentPadding = inner,
                    onOpenAlbum = { albumDetail = it },
                    favoriteCount = photos.count { it.favorite },
                    favoriteCoverUri = photos.firstOrNull { it.favorite }?.let { p -> p.thumbUri ?: p.uri },
                    onOpenFavorites = {
                        val favs = photos.filter { it.favorite }
                        if (favs.isNotEmpty()) {
                            viewer = ViewerRequest(favs.map { it.id }, favs.first().id)
                        } else {
                            vm.toast("还没有收藏的照片，点开照片后点底部的 ♥ 即可收藏")
                        }
                    },
                )

                Tab.MAP -> MapScreen(
                    vm = vm,
                    contentPadding = inner,
                    onOpenPhoto = { list, photo ->
                        viewer = ViewerRequest(list.map { it.id }, photo.id)
                    },
                    onOpenCluster = { cluster ->
                        vm.clearSimilarPicks()
                        mapCluster = cluster
                    },
                )

                Tab.SIMILAR -> SimilarScreen(
                    vm = vm,
                    contentPadding = inner,
                    onOpenPhoto = { photo ->
                        viewer = ViewerRequest(photos.map { it.id }, photo.id)
                    },
                    onOpenGroup = { similarDrillKey = it },
                    onMovePicks = { pickerTargets = it },
                )

                Tab.SETTINGS -> SettingsScreen(
                    vm = vm,
                    contentPadding = inner,
                    onOpenTrash = { trashOpen = true },
                )
            }
        }

        // ------------------------------------------------------- 相册详情覆盖层

        albumDetail?.let { opened ->
            // 相册可能在后台被改名或清空，这里始终取最新的一份。
            val live = albums.firstOrNull { it.bucketId == opened.bucketId } ?: opened
            val closeDetail = {
                vm.clearSelection()
                albumDetail = null
            }
            BackHandler(onBack = closeDetail)
            AlbumDetailScreen(
                vm = vm,
                album = live,
                onBack = closeDetail,
                onOpenPhoto = { photo ->
                    val ids = photos.filter { it.bucketId == live.bucketId }.map { it.id }
                    viewer = ViewerRequest(ids, photo.id)
                },
            )
        }

        // ----------------------------------------------------- 相似组详情覆盖层
        similarDrillKey?.let { key ->
            val closeDetail = {
                vm.clearSimilarPicks()
                similarDrillKey = null
            }
            BackHandler(onBack = closeDetail)
            SimilarGroupDetailScreen(
                vm = vm,
                groups = similarGroups,
                currentKey = key,
                onBack = closeDetail,
                onOpenPhoto = { photo ->
                    val g = similarGroups.firstOrNull { it.key == key }
                    viewer = ViewerRequest(g?.items?.map { it.id }.orEmpty(), photo.id)
                },
                onIgnore = { vm.resolveGroup(key) },
                onMovePicks = { pickerTargets = it },
                onNavigateToGroup = { similarDrillKey = it },
            )
        }

        // --------------------------------------------------- 地图地点相册覆盖层
        mapCluster?.let { cluster ->
            // 照片可能被删掉，这里始终按 id 取最新的一份；全没了就自动退出。
            val live = remember(cluster, photos) {
                val ids = cluster.photos.mapTo(HashSet()) { it.id }
                photos.filter { it.id in ids }
            }
            if (live.isEmpty()) {
                LaunchedEffect(Unit) { mapCluster = null }
            } else {
                val closeCluster = {
                    vm.clearSimilarPicks()
                    mapCluster = null
                }
                BackHandler(onBack = closeCluster)
                MapClusterDetailScreen(
                    vm = vm,
                    title = cluster.place ?: Format.latLon(cluster.lat, cluster.lon),
                    photos = live,
                    onBack = closeCluster,
                    onOpenPhoto = { photo ->
                        viewer = ViewerRequest(live.map { it.id }, photo.id)
                    },
                    onMovePicks = { pickerTargets = it },
                )
            }
        }

        // --------------------------------------------------------- 多选操作条
        // 放在 Scaffold 外面，这样相册详情页也能共用同一条。

        AnimatedVisibility(
            visible = selection.isNotEmpty() && viewer == null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionBar(
                count = selection.size,
                onSelectAll = {
                    val pool = albumDetail
                        ?.let { a -> photos.filter { it.bucketId == a.bucketId } }
                        ?: photos
                    vm.replaceSelection(pool.map { it.id })
                },
                onMove = { pickerTargets = selection.toList() },
                onKeep = { vm.markKept(selection.toList()) },
                onTrash = { confirmTrashSelection = true },
                onClear = vm::clearSelection,
                modifier = Modifier.padding(
                    bottom = if (albumDetail != null) navBarInset + 12.dp else navBarInset + 92.dp
                ),
            )
        }

        // ----------------------------------------------------------- 大图查看

        viewer?.let { request ->
            val byId = remember(photos) { photos.associateBy { it.id } }
            val list = remember(request, photos) { request.ids.mapNotNull { byId[it] } }
            if (list.isEmpty()) {
                LaunchedEffect(Unit) { viewer = null }
            } else {
                // 用 request 做 key：撤销后重建 ViewerRequest 会重启大图页并跳回被恢复的照片。
                key(request) {
                    BackHandler { viewer = null }
                    ViewerScreen(
                        photos = list,
                        initialId = request.initialId,
                        exif = exif,
                        onRequestExif = vm::loadExif,
                        onClose = { vm.flushStagedMoves(); vm.flushAlbumMoves(); viewer = null },
                        onTrash = { vm.moveToTrash(listOf(it.id)) },
                        onToggleFavorite = { vm.toggleFavorite(it.id) },
                        onMoveToAlbum = { pickerTargets = listOf(it.id) },
                        onUndo = { handleUndo() },
                        onResolveLiveVideo = { vm.resolveLiveVideo(it) },
                        onMarkKept = { vm.markKept(listOf(it.id)) },
                        gestures = settings.gestures,
                        sensitivity = settings.gestureSensitivity.factor,
                        // 实况识别与自动播放默认开启且不再提供设置开关，这里恒为 true
                        // （避免老版本把 liveAutoPlay 存成 false 的用户无法自动播放）。
                        liveAutoPlay = true,
                        liveMuted = liveMuted,
                        onSetLiveMuted = vm::setLiveMuted,
                        // 大图底部「快捷归入」：用户自选的文件夹名，点一下即把当前照片
                        // "暂存"到该相册（仅内存、不写系统、不弹确认），可一路标记多张，
                        // 最后点"确认归入"或退出大图页时整批一次性写盘，全程只弹一次权限确认。
                        quickAlbums = settings.quickAlbums,
                        allAlbums = albums.map { it.name }.distinct(),
                        onSetQuickAlbums = vm::setQuickAlbums,
                        onMoveToAlbumByName = { name, photo ->
                            vm.stageMoveToAlbumByName(name, listOf(photo.id))
                        },
                        staged = staged,
                        onConfirmStaged = { vm.flushStagedMoves() },
                        advanceSignal = advanceSignal,
                    )
                }
            }
        }

        // 整理操作的悬浮撤销按钮：删除后常驻显示，直到被点击撤销或新的操作覆盖。
        // 放在 Box 最后一层，所以大图查看器全屏时也能看到并一键找回。
        AnimatedVisibility(
            visible = undoEvent != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 18.dp,
                    // 大图页底部有快捷归入条 + 操作栏，浮钮再往上提一点，避免遮挡。
                    bottom = if (viewer != null) navBarInset + 168.dp else navBarInset + 96.dp,
                ),
        ) {
            ExtendedFloatingActionButton(
                onClick = { handleUndo() },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(Icons.Rounded.Undo, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("撤销 ${undoEvent?.count ?: 0}", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // -------------------------------------------------------------- 公共弹窗

    AlbumPickerHost(
        vm, albums, pickerTargets, { creatingAlbum = true },
        onDismiss = { pickerTargets = null },
        // 查看器外（批量 / 整理）选完相册即整体落盘：一次操作只弹一次权限确认。
        onAfterPick = { if (viewer == null) vm.flushStagedMoves() },
    )

    NewAlbumDialogHost(vm, creatingAlbum) { creatingAlbum = false }

    if (confirmTrashSelection) {
        ConfirmDialog(
            title = "移入回收站",
            message = "${selection.size} 张照片会从 PhotoO 的图库里隐藏，" +
                if (settings.alsoSystemTrash) "并同时移入系统回收站。"
                else "原文件仍在手机上，在回收站里再次删除才会真正删掉。",
            confirmText = "移入回收站",
            danger = true,
            onConfirm = { vm.moveToTrash(selection.toList()) },
            onDismiss = { confirmTrashSelection = false },
        )
    }
}

// ------------------------------------------------------------------ 子组件

/** 相册选择器。归档动作在三处触发（批量、大图、整理），收口到这里避免重复。 */
@Composable
private fun AlbumPickerHost(
    vm: PhotoOViewModel,
    albums: List<AlbumItem>,
    targets: List<Long>?,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
    /** 选完相册后的回调（如大图页里要"处理完→切下一张"）。 */
    onAfterPick: () -> Unit = {},
) {
    if (targets.isNullOrEmpty()) return
    AlbumPickerSheet(
        albums = albums,
        photoCount = targets.size,
        onPick = { album ->
            // 只"暂存"目标相册（不写系统、不弹确认）；在查看器内会继续累积，
            // 在查看器外（批量 / 整理）则选完立即整体落盘 —— 一次操作只弹一次权限确认。
            vm.stageMoveToAlbumByName(album.name, targets)
            vm.clearSimilarPicks()
            onAfterPick()
            onDismiss()
        },
        onCreateNew = onCreateNew,
        onDismiss = onDismiss,
    )
}

@Composable
private fun NewAlbumDialogHost(
    vm: PhotoOViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    TextInputDialog(
        title = "新建相册",
        label = "相册名称",
        confirmText = "创建",
        onConfirm = vm::createAlbum,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onKeep: () -> Unit,
    onTrash: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Rounded.Close, contentDescription = "取消选择")
        }
        Text(
            "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(Modifier.weight(1f))
        BarAction(Icons.Rounded.SelectAll, "全选", onSelectAll)
        BarAction(Icons.Rounded.FolderOpen, "归档", onMove)
        BarAction(Icons.Rounded.DoneAll, "已看", onKeep)
        BarAction(Icons.Rounded.Delete, "删除", onTrash, MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .width(62.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

// ------------------------------------------------------------------ 权限

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        // Live Photo 需要读取同目录同名视频，权限一起申请；未授予时自动降级为不可识别。
        add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
}.toTypedArray()

private fun hasMediaPermission(context: Context): Boolean {
    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (ContextCompat.checkSelfPermission(context, key) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    // Android 14 起用户可以只授权"选中的照片"：上面那个权限是拒绝态，
    // 但 READ_MEDIA_VISUAL_USER_SELECTED 被授予，依然能读到部分图片。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED
    }
    return false
}

/** 没权限时的引导页。 */
@Composable
private fun PermissionGate(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            EmptyState(
                title = "需要相册权限",
                subtitle = "PhotoO 需要读取本机图片才能显示时间线、识别相似照片。" +
                    "所有处理都在手机本地完成，不会上传任何内容。",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRequest) { Text("授予权限") }
                OutlinedButton(onClick = onOpenSettings) { Text("去系统设置") }
            }
        }
    }
}
