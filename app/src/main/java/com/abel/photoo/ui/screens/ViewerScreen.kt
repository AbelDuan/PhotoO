package com.abel.photoo.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.abel.photoo.data.media.Thumbs
import com.abel.photoo.data.media.ThumbRequest
import com.abel.photoo.model.ExifInfo
import com.abel.photoo.model.GestureAction
import com.abel.photoo.model.GestureDirection
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.ui.components.ZoomableImage
import com.abel.photoo.ui.util.Format
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 全屏大图查看器。
 *
 * 四个滑动方向绑定的动作可以在设置里改，默认：
 *   左右滑 → 切换照片      上滑 → 移入回收站
 *   下滑   → 退出          单击 → 显隐工具栏
 *   双击 / 双指 → 缩放
 *
 * Live Photo 打开即自动播放（可关）；视频则内置播放器，单击播放/暂停、上滑删除。
 * 单击隐藏工具栏时会同步隐藏系统状态栏与导航栏，实现完全全屏看照片。
 */
@Composable
fun ViewerScreen(
    photos: List<PhotoItem>,
    initialId: Long,
    exif: Map<Long, ExifInfo>,
    onRequestExif: (PhotoItem) -> Unit,
    onClose: () -> Unit,
    onTrash: (PhotoItem) -> Unit,
    onToggleFavorite: (PhotoItem) -> Unit,
    onMoveToAlbum: (PhotoItem) -> Unit,
    onResolveLiveVideo: suspend (PhotoItem) -> Uri? = { null },
    onUndo: () -> Unit = {},
    onMarkKept: (PhotoItem) -> Unit = {},
    /** 四个方向各自绑定的动作。 */
    gestures: Map<GestureDirection, GestureAction> = emptyMap(),
    /** 手势灵敏度系数。 */
    sensitivity: Float = 1f,
    liveAutoPlay: Boolean = true,
    liveMuted: Boolean = true,
    onSetLiveMuted: (Boolean) -> Unit = {},
    /** 大图底部快捷归入：用户自选的文件夹名（按出现顺序）。 */
    quickAlbums: List<String> = emptyList(),
    /** 全部可选文件夹名（用于「编辑快捷归入」面板）。 */
    allAlbums: List<String> = emptyList(),
    /** 保存用户勾选的快捷归入文件夹。 */
    onSetQuickAlbums: (List<String>) -> Unit = {},
    /** 把当前照片直接归入指定名称的文件夹（本页走"暂存"，不立即写系统、不弹确认）。 */
    onMoveToAlbumByName: (String, PhotoItem) -> Unit = { _, _ -> },
    /** 全部已暂存（照片 id -> 目标相册名），本页据此高亮当前照片对应的快捷按钮与计数。 */
    staged: Map<Long, String> = emptyMap(),
    /** 一次性把全部暂存真正写入系统（只弹一次权限确认）。 */
    onConfirmStaged: () -> Unit = {},
    /** 外部（如相册选择器完成）通知本页"某张照片已处理完，按浏览方向切走"。 */
    advanceSignal: Int = 0,
) {
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val view = LocalView.current
    // 退出查看器 / 显隐工具栏时同步隐藏系统状态栏、导航栏，照片才算"完全全屏"。
    DisposableEffect(Unit) { onDispose { setSystemBarsVisible(view, true) } }

    val startIndex = remember(initialId) {
        photos.indexOfFirst { it.id == initialId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, photos.lastIndex),
        pageCount = { photos.size },
    )
    var chromeVisible by remember { mutableStateOf(true) }
    var infoVisible by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var livePlaying by remember { mutableStateOf(false) }
    var liveUri by remember { mutableStateOf<Uri?>(null) }
    // 记录最近一次翻页方向：删除后据此决定停在"下一张"还是"上一张"。
    var lastNavDir by remember { mutableStateOf<GestureDirection?>(null) }
    // 删除后想要落到的页码，等列表收缩（recomposition）后再跳转，避免越界黑屏。
    var pendingDeleteTarget by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    // 是否正在编辑「快捷归入」的文件夹清单（底部的文件夹名由用户自行指定）。
    var showQuickPicker by remember { mutableStateOf(false) }

    val current = photos.getOrNull(pagerState.currentPage.coerceIn(0, photos.lastIndex))
    // 当前照片已暂存到的目标相册名（用于高亮），以及已暂存待归入的照片总数。
    val stagedName = staged[current?.id]
    val stagedCount = staged.size

    // 照片被删除后列表收缩，currentPage 可能越界（最常见：删掉最后一张）。
    // 越界时 Pager 会渲染出一页空白（黑屏），这里主动纠正回最后一个有效页。
    LaunchedEffect(photos.size) {
        if (photos.isNotEmpty() && pagerState.currentPage > photos.lastIndex) {
            scope.launch { pagerState.scrollToPage(photos.lastIndex) }
        }
    }

    // 单击隐藏工具栏时同步隐藏系统状态栏/导航栏，再点一下恢复。
    LaunchedEffect(chromeVisible) { setSystemBarsVisible(view, chromeVisible) }

    // 真实翻页方向跟踪：手指左右滑动（或程序化切页）都会改 currentPage，
    // 据此刷新 lastNavDir，供"处理后切走 / 删除停留"判定使用。
    // 默认左右滑动由 Pager 原生处理、runAction 的 NEXT/PREV 分支并不会被走到，
    // 所以必须在 currentPage 变化处统一跟踪，否则 lastNavDir 永远是初始值，
    // 操作后只会单向往后（下一张），不会逆序往前（上一张）。
    LaunchedEffect(pagerState) {
        var prev = pagerState.currentPage
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val d = page - prev
            if (d > 0) lastNavDir = GestureDirection.LEFT // 往后浏览：下一张
            else if (d < 0) lastNavDir = GestureDirection.RIGHT // 往前浏览：上一张
            prev = page
        }
    }

    // 删完照片后，按最近翻页方向落到目标页：往回看则停在上一张，否则停在下一张。
    LaunchedEffect(photos.size) {
        pendingDeleteTarget?.let { target ->
            pendingDeleteTarget = null
            if (photos.isEmpty()) return@LaunchedEffect
            val t = target.coerceIn(0, photos.lastIndex)
            if (pagerState.currentPage != t) {
                scope.launch { pagerState.scrollToPage(t) }
            }
        }
    }

    fun actionOf(dir: GestureDirection): GestureAction = gestures[dir] ?: dir.default

    // 左右都还是"翻页"时交给 Pager 自己滚，跟手感受最好；
    // 一旦用户把左右改成了别的动作，就关掉 Pager 滚动、由手势层接管。
    val horizontalIsPaging =
        actionOf(GestureDirection.LEFT) == GestureAction.NEXT &&
            actionOf(GestureDirection.RIGHT) == GestureAction.PREV

    fun goPage(delta: Int) {
        val target = (pagerState.currentPage + delta).coerceIn(0, photos.lastIndex)
        if (target != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }

    // 删除当前照片：先按最近翻页方向算出删除后要停留的页码，再真正删除
    // （列表收缩后由 LaunchedEffect 跳过去，避免停在越界空白页）。
    fun trash(photo: PhotoItem) {
        val k = pagerState.currentPage
        pendingDeleteTarget = if (lastNavDir == GestureDirection.RIGHT) {
            maxOf(0, k - 1) // 刚才在往回看，删完停在上一张
        } else {
            k // 默认停在"下一张"（删除后后面的项前移，当前页码正好落在新下一张）
        }
        onTrash(photo)
    }

    // 飞出方向已确定：删除立即移除内存照片（Pager 已预载下一张，直接切到下一张，
    // 不再用全屏"飞出副本"盖着——那样会先停留再消失）。退出则直接关闭。
    fun confirmFly(dir: GestureDirection, photo: PhotoItem, startOffset: Offset = Offset.Zero, painter: Painter? = null) {
        when (actionOf(dir)) {
            GestureAction.TRASH -> trash(photo)
            GestureAction.CLOSE -> onClose()
            else -> Unit
        }
    }

    /**
     * 照片"处理完成"后的统一前进逻辑：按当前浏览方向切走。
     * 从前往后浏览（lastNavDir=LEFT）→ 切下一张；从后往前浏览（lastNavDir=RIGHT）→ 切上一张。
     * 删除走自己的 pendingDeleteTarget（列表收缩后停在正确页），不在这里处理。
     */
    fun proceedAfterAction() {
        val delta = if (lastNavDir == GestureDirection.RIGHT) -1 else 1
        goPage(delta)
    }

    // 外部（相册选择器落定）通知：处理完当前照片后按浏览方向切走。
    LaunchedEffect(advanceSignal) {
        if (advanceSignal > 0) proceedAfterAction()
    }

    fun runAction(action: GestureAction, photo: PhotoItem) {
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.CLOSE -> onClose()
            GestureAction.TRASH -> trash(photo)
            GestureAction.FAVORITE -> onToggleFavorite(photo)
            GestureAction.MOVE_ALBUM -> onMoveToAlbum(photo)
            GestureAction.INFO -> infoVisible = true
            GestureAction.KEEP -> onMarkKept(photo)
            GestureAction.UNDO -> onUndo()
            GestureAction.NEXT -> {
                lastNavDir = GestureDirection.LEFT // 左滑 = 下一张
                goPage(1)
            }
            GestureAction.PREV -> {
                lastNavDir = GestureDirection.RIGHT // 右滑 = 上一张
                goPage(-1)
            }
        }
    }

    // 翻到哪张就解析哪张的 EXIF，不做全量预解析 —— 大图库上那会非常慢。
    // 相邻页的预载交给 HorizontalPager(beyondViewportPageCount=1)。
    LaunchedEffect(pagerState, photos) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            photos.getOrNull(page)?.let(onRequestExif)
        }
    }

    // Live 自动播放：切到哪张就解析哪张，非 Live 或关掉自动播放时直接清空。
    LaunchedEffect(current?.id, liveAutoPlay) {
        livePlaying = false
        liveUri = null
        val photo = current ?: return@LaunchedEffect
        if (!photo.isLivePhoto || !liveAutoPlay) return@LaunchedEffect
        val uri = onResolveLiveVideo(photo)
        // 解析是异步的，回来时用户可能已经翻页了，要再确认一次。
        if (uri != null && current?.id == photo.id) {
            liveUri = uri
            livePlaying = true
        }
    }

    fun toggleLive() {
        val photo = current ?: return
        if (!photo.isLivePhoto) return
        if (livePlaying) {
            livePlaying = false
            return
        }
        val cached = liveUri
        if (cached != null) {
            livePlaying = true
            return
        }
        scope.launch {
            val uri = onResolveLiveVideo(photo)
            if (uri != null && current?.id == photo.id) {
                liveUri = uri
                livePlaying = true
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = horizontalIsPaging && !zoomed,
            beyondViewportPageCount = 2,
            // 用照片 id 做页 key：删除中间某张后，后续页面的内容跟着 id 走，
            // 不会因为 position 前移而串页。
            key = { photos[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photos[page]
            val isCurrent = page == pagerState.currentPage
            if (photo.isVideo) {
                // 视频：内置播放器，上滑删除 / 下滑退出 / 单击显隐工具栏。
                VideoPage(
                    photo = photo,
                    sensitivity = sensitivity,
                    onSwipe = { runAction(actionOf(it), photo) },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                )
            } else {
                ZoomableImage(
                    model = photo.uri,
                    contentDescription = photo.displayName,
                    resetKey = photo.id,
                    // 传真实 360 缩略图占位：原图解码完前先秒出预览，且不会把原图再当占位加载一遍。
                    thumbModel = ThumbRequest(photo.uri, Thumbs.TARGET),
                    sensitivity = sensitivity,
                    horizontalEnabled = !horizontalIsPaging,
                    onTap = { chromeVisible = !chromeVisible },
                    onSwipe = { dir ->
                        // 滑动会翻页或关闭，先把正在播的 Live 停掉，免得声音跟着跑。
                        livePlaying = false
                        runAction(actionOf(dir), photo)
                    },
                    flyOut = { dir ->
                        // 只有"删除 / 退出"这类动作值得整张图飞出去；翻页飞出反而奇怪。
                        actionOf(dir) == GestureAction.TRASH || actionOf(dir) == GestureAction.CLOSE
                    },
                    // 飞出动画一开始（真正删除前）就停掉 Live，避免删除过程中视频还在播、
                    // 半透明静帧跟着手势划上去的割裂观感，保证所有照片删除动画一致。
                    onFlyStart = { livePlaying = false },
                    onFlyConfirm = { dir, off, painter -> confirmFly(dir, photo, off, painter) },
                    onZoomChanged = { if (isCurrent) zoomed = it },
                    overlay = if (isCurrent && livePlaying && liveUri != null) {
                        {
                            key(liveUri) {
                                LiveVideoLayer(
                                    uri = liveUri!!,
                                    muted = liveMuted,
                                    onFinished = { livePlaying = false },
                                )
                            }
                        }
                    } else null,
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                photo = current,
                index = pagerState.currentPage + 1,
                total = photos.size,
                onClose = onClose,
                onInfo = { infoVisible = !infoVisible },
            )
        }

        // 微信同款 LIVE 徽标：贴在画面左上角，点一下停 / 播，旁边是声音开关。
        if (current?.isLivePhoto == true) {
            LiveBadge(
                playing = livePlaying,
                muted = liveMuted,
                onToggle = ::toggleLive,
                onToggleMute = { onSetLiveMuted(!liveMuted) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 14.dp, top = 62.dp),
            )
        }

        // 「确认归入」按钮：浮在快捷归入条上方，与归入条各自独立定位、互不重叠；
        // 归入条始终固定在底部 96dp 处，按钮出现时不会被它顶上去（不再整体上移）。
        AnimatedVisibility(
            visible = stagedCount > 0 && chromeVisible && !infoVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 272.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.clickable { onConfirmStaged() },
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        "确认归入 $stagedCount 张",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && !infoVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        ) {
            QuickAlbumBar(
                albums = quickAlbums,
                stagedName = stagedName,
                onPick = { name ->
                    current?.let {
                        onMoveToAlbumByName(name, it)
                        // 归入和删除是同一个"处理完成"逻辑：处理后按浏览方向切到下一张/上一张。
                        proceedAfterAction()
                    }
                },
                onEdit = { showQuickPicker = true },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && !infoVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ViewerBottomBar(
                photo = current,
                onFavorite = { current?.let(onToggleFavorite) },
                onMove = { current?.let(onMoveToAlbum) },
                onDelete = { current?.let(::trash) },
                onInfo = { infoVisible = true },
                onPlayLive = if (current?.isLivePhoto == true) ::toggleLive else null,
                livePlaying = livePlaying,
            )
        }

        AnimatedVisibility(
            visible = infoVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ExifPanel(
                photo = current,
                info = current?.let { exif[it.id] },
                onClose = { infoVisible = false },
            )
        }

        if (showQuickPicker) {
            QuickAlbumPicker(
                all = allAlbums,
                selected = quickAlbums,
                onApply = { onSetQuickAlbums(it); showQuickPicker = false },
                onClose = { showQuickPicker = false },
            )
        }
    }
}

/** 让系统状态栏与导航栏显示 / 隐藏（隐藏后照片完全全屏；轻扫可临时呼出）。 */
private fun setSystemBarsVisible(view: View, visible: Boolean) {
    val activity = findActivity(view) ?: return
    val controller = WindowInsetsControllerCompat(activity.window, view)
    val bars = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    if (visible) {
        controller.show(bars)
    } else {
        controller.hide(bars)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** 从 View 向上找到所属 Activity（ContextWrapper 链）。 */
private fun findActivity(view: View): Activity? {
    var ctx = view.context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun ViewerTopBar(
    photo: PhotoItem?,
    index: Int,
    total: Int,
    onClose: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                photo?.displayName.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                "$index / $total · ${photo?.dateTaken?.let(Format::fullTime).orEmpty()}",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        IconButton(onClick = onInfo) {
            Icon(Icons.Rounded.Info, "详细信息", tint = Color.White)
        }
    }
}

/**
 * 微信风格的 LIVE 徽标：半透明黑底胶囊 + 同心圆图标 + "LIVE" 字样。
 * 播放中会连着显示一个声音开关。
 */
@Composable
private fun LiveBadge(
    playing: Boolean,
    muted: Boolean,
    onToggle: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = if (playing) 0.55f else 0.38f))
                .clickable { onToggle() }
                .padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveGlyph(active = playing)
            Text(
                "LIVE",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
            )
        }
        Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onToggleMute() }
                    .padding(6.dp),
            ) {
                Icon(
                    if (muted) Icons.AutoMirrored.Rounded.VolumeOff
                    else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = if (muted) "开启声音" else "静音",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
    }
}

/** LIVE 图标本体：外圈虚线环 + 中圈实线环 + 圆心点，和微信/iOS 的实况标一致。 */
@Composable
private fun LiveGlyph(active: Boolean) {
    val tint = if (active) Color.White else Color.White.copy(alpha = 0.85f)
    Canvas(Modifier.size(14.dp)) {
        val r = size.minDimension / 2f
        val stroke = 1.5.dp.toPx()
        drawCircle(
            color = tint,
            radius = r - stroke / 2f,
            style = Stroke(
                width = stroke,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(2.6.dp.toPx(), 2.0.dp.toPx()),
                    0f,
                ),
            ),
        )
        drawCircle(color = tint, radius = r * 0.50f, style = Stroke(width = stroke))
        drawCircle(color = tint, radius = r * 0.17f)
    }
}

/** Live Photo 视频层。放在手势 Box 内部，VideoView 不消费触摸，滑动手势照常可用。 */
@Composable
private fun LiveVideoLayer(uri: Uri, muted: Boolean, onFinished: () -> Unit) {
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    // 首帧渲染前视频层保持透明，避免切到 Live 照片时闪一下黑屏：
    // 静帧（ZoomableImage 的图）一直显示在下面，首帧就绪后再淡入覆盖。
    var ready by remember { mutableStateOf(false) }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                isClickable = false
                isFocusable = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                alpha = 0f
                setVideoURI(uri)
                setOnPreparedListener { mp: android.media.MediaPlayer ->
                    player = mp
                    // 播一遍就停（微信实况也是单次播放）：结束后停在最后一帧，
                    // LIVE 徽标回到未播放态，再点一下从头重播。
                    mp.isLooping = false
                    val v = if (muted) 0f else 1f
                    runCatching { mp.setVolume(v, v) }
                    start()
                }
                // 首帧真正画到 Surface 上时再淡入，消除黑屏闪烁。
                setOnInfoListener { _, what, _ ->
                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        animate().alpha(1f).setDuration(150).start()
                        ready = true
                    }
                    true
                }
                // 播放完成：通知外层把 livePlaying 复位（回调在主线程，可直接改状态）。
                setOnCompletionListener { onFinished() }
                setOnErrorListener { _, _, _ ->
                    // 解码失败也算"播完"，把状态复位，避免 LIVE 一直转圈。
                    onFinished()
                    true
                }
            }
        },
        update = {
            // muted 变化会触发重组，这里把音量同步到已经准备好的播放器上。
            player?.let { mp ->
                val v = if (muted) 0f else 1f
                runCatching { mp.setVolume(v, v) }
            }
        },
        onRelease = { view ->
            runCatching { view.stopPlayback() }
            player = null
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 视频播放页。单击播放/暂停（暂停时中央显示大播放按钮），
 * 上滑 / 下滑交给上层手势（删除 / 退出 / 撤销），左右横滑仍由 Pager 翻页。
 */
@Composable
private fun VideoPage(
    photo: PhotoItem,
    sensitivity: Float,
    onSwipe: (GestureDirection) -> Unit,
    onToggleChrome: () -> Unit,
) {
    var playing by remember(photo.id) { mutableStateOf(false) }
    var viewRef by remember { mutableStateOf<VideoView?>(null) }
    // 纵向拖拽：整段视频随手指平移（播放不中断），松手超阈值飞出删除/退出。
    var exiting by remember { mutableStateOf(false) }
    var exitDir by remember { mutableStateOf(GestureDirection.UP) }
    val offY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val flyProgress by animateFloatAsState(
        targetValue = if (exiting) 1f else 0f,
        animationSpec = tween(220, easing = FastOutLinearInEasing),
        label = "vidFly",
    )
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 拖拽阶段：视频不透明跟手；飞出阶段：按屏高比例位移 + 淡出 + 轻微旋转。
                    translationY = offY.value + if (exiting) {
                        (if (exitDir == GestureDirection.UP) -1f else 1f) *
                            flyProgress * size.height * 1.3f
                    } else 0f
                    alpha = if (exiting) (1f - flyProgress).coerceIn(0f, 1f) else 1f
                    rotationZ = if (exiting) {
                        (if (exitDir == GestureDirection.UP) -1f else 1f) * 10f * flyProgress
                    } else 0f
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(photo.uri)
                        setOnPreparedListener { it.isLooping = false }
                        setOnCompletionListener { playing = false }
                    }.also { viewRef = it }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 手势层：纵向拖拽时整段视频随手指平移（消费事件），横向滑交给 Pager 翻页，
        // 单击显隐工具栏。播放中拖拽不暂停，视频继续播。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var axisV = false
                        var totalX = 0f
                        var totalY = 0f
                        val slop = viewConfiguration.touchSlop
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.any { it.pressed }
                            if (!axisV) {
                                val pan = event.calculatePan()
                                totalX += pan.x
                                totalY += pan.y
                                if (abs(totalY) > slop) axisV = true
                            }
                            if (axisV) {
                                val pan = event.calculatePan()
                                scope.launch { offY.snapTo(offY.value + pan.y) }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (axisV) {
                            val threshold = size.height * 0.16f / sensitivity.coerceAtLeast(0.2f)
                            when {
                                totalY < -threshold -> {
                                    exitDir = GestureDirection.UP
                                    exiting = true
                                    scope.launch { kotlinx.coroutines.delay(220); onSwipe(GestureDirection.UP) }
                                }
                                totalY > threshold -> {
                                    exitDir = GestureDirection.DOWN
                                    exiting = true
                                    scope.launch { kotlinx.coroutines.delay(220); onSwipe(GestureDirection.DOWN) }
                                }
                                abs(totalX) < slop && abs(totalY) < slop -> onToggleChrome()
                                else -> Unit // 横向滑动交给 Pager 翻页，不切换工具栏
                            }
                        } else {
                            // 没怎么动：视为单击，显隐工具栏。
                            onToggleChrome()
                        }
                    }
                },
        )
        if (!playing) {
            IconButton(
                onClick = { viewRef?.start(); playing = true },
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewerBottomBar(
    photo: PhotoItem?,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    onPlayLive: (() -> Unit)? = null,
    livePlaying: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.62f),
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerAction(
            icon = if (photo?.favorite == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            label = "收藏",
            onClick = onFavorite,
        )
        ViewerAction(Icons.Rounded.Folder, "归入相册", onMove)
        ViewerAction(Icons.Rounded.Info, "信息", onInfo)
        if (onPlayLive != null) {
            ViewerAction(
                icon = if (livePlaying) Icons.Rounded.Close else Icons.Rounded.PlayArrow,
                label = if (livePlaying) "停止" else "Live",
                onClick = onPlayLive,
            )
        }
        ViewerAction(Icons.Rounded.Delete, "删除", onDelete, tint = Color(0xFFFF7B7F))
    }
}

/**
 * 大图页底部的「快捷归入」条：展示用户指定的文件夹名，点一下就归到对应文件夹。
 * 只占一小块、最多显示两排并可滚动，不影响看照片；右侧「编辑」可重新勾选要显示哪些文件夹。
 */
@Composable
private fun QuickAlbumBar(
    albums: List<String>,
    stagedName: String? = null,
    onPick: (String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        FlowRow(
            Modifier
                .padding(8.dp)
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (albums.isEmpty()) {
                QuickChip("＋ 添加快捷归入", leadingIcon = Icons.Rounded.Add, onClick = onEdit)
            } else {
                albums.forEach { name ->
                    QuickChip(
                        name,
                        onClick = { onPick(name) },
                        // 已标记到该相册时高亮，提示"这张照片待会儿会归入这里"。
                        highlighted = name == stagedName,
                    )
                }
                QuickChip("编辑", leadingIcon = Icons.Rounded.Add, onClick = onEdit)
            }
        }
    }
}

/** 快捷归入条上的小药丸：文件夹名 / 「编辑」「添加」等。 */
@Composable
private fun QuickChip(
    text: String,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    highlighted: Boolean = false,
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primary else Color.White
    val content = if (highlighted) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg.copy(alpha = if (highlighted) 1f else 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leadingIcon?.let {
            Icon(it, null, tint = content, modifier = Modifier.size(15.dp))
        }
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** 大图页直接勾选"哪些文件夹出现在快捷归入条上"。 */
@Composable
private fun QuickAlbumPicker(
    all: List<String>,
    selected: List<String>,
    onApply: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    var picks by remember(all, selected) { mutableStateOf(selected.toSet()) }
    // 居中弹窗：半透明遮罩点空白处即关，内容卡片在屏幕正中，不再贴顶。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "快捷归入的文件夹",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "已选 ${picks.size} 个 · 会按勾选顺序显示在大图底部",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClose) { Text("取消") }
            TextButton(
                onClick = {
                    // 保持原有顺序，新勾的追加在后面，避免每次开面板顺序都变。
                    val ordered = selected.filter { it in picks } +
                        all.filter { it in picks && it !in selected }
                    onApply(ordered)
                },
            ) { Text("完成") }
        }
        if (all.isEmpty()) {
            Text(
                "还没有任何相册文件夹",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(all) { name ->
                    val checked = name in picks
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                picks = if (checked) picks - name else picks + name
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
        }
    }
    BackHandler(enabled = true) { onClose() }
}

@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = tint) }
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

/** EXIF 面板。没有的字段直接不显示，不摆一堆"未知"。 */
@Composable
private fun ExifPanel(
    photo: PhotoItem?,
    info: ExifInfo?,
    onClose: () -> Unit,
) {
    if (photo == null) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "照片信息",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "收起",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onClose() }
                    .padding(8.dp),
            )
        }

        ExifRow(
            Icons.Rounded.Schedule,
            "拍摄时间",
            Format.fullTime(info?.dateTimeOriginal ?: photo.dateTaken),
        )
        ExifRow(
            Icons.Rounded.Image,
            photo.displayName,
            "${Format.pixels(photo.width, photo.height)} · ${Format.bytes(photo.size)}",
        )
        ExifRow(
            Icons.Rounded.Folder,
            "存放位置",
            photo.relativePath.ifBlank { photo.bucketName },
        )

        if (info?.hasCamera == true) {
            ExifRow(
                Icons.Rounded.CameraAlt,
                info.cameraTitle,
                listOfNotNull(info.lens, info.software).joinToString(" · ")
                    .ifBlank { "拍摄设备" },
            )
        }
        if (info?.hasShootingParams == true) {
            ExifRow(
                Icons.Rounded.CameraAlt,
                "拍摄参数",
                listOfNotNull(
                    info.focalLength,
                    info.aperture,
                    info.shutter,
                    info.iso,
                ).joinToString("  "),
            )
        }
        if (info?.hasLocation == true) {
            ExifRow(
                Icons.Rounded.LocationOn,
                info.place ?: "拍摄地点",
                String.format(
                    java.util.Locale.CHINA,
                    "%.5f, %.5f%s",
                    info.latitude,
                    info.longitude,
                    info.altitude?.let { " · 海拔 ${it.toInt()}m" } ?: "",
                ),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "关闭",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onClose() }
                    .padding(horizontal = 28.dp, vertical = 10.dp),
            )
        }
    }
    }
    // 面板打开时，返回键先收起面板而不是退出查看器。
    BackHandler(enabled = true) { onClose() }
}

@Composable
private fun ExifRow(icon: ImageVector, title: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
