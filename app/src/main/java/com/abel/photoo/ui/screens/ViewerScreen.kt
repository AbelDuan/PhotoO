package com.abel.photoo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.media.MediaPlayer
import android.widget.VideoView
import com.abel.photoo.model.ExifInfo
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.ui.components.ZoomableImage
import com.abel.photoo.ui.util.Format

/**
 * 全屏大图查看器。
 *
 * 交互约定：
 *   左右滑 → 切换照片      上滑 → 移入回收站
 *   下滑   → 退出          单击 → 显隐工具栏
 *   双击 / 双指 → 缩放
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
    quickAlbums: List<String> = emptyList(),
    onMoveToQuickAlbum: (PhotoItem, String) -> Unit = { _, _ -> },
    onCreateQuickAlbum: (PhotoItem) -> Unit = { _ -> },
) {
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

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

    val current = photos.getOrNull(pagerState.currentPage.coerceIn(0, photos.lastIndex))

    // 翻到哪张就解析哪张的 EXIF，不做全量预解析 —— 大图库上那会非常慢。
    // 相邻页的预载交给 HorizontalPager(beyondViewportPageCount=1)，这里只负责复位 Live 播放态。
    LaunchedEffect(pagerState, photos) {
        livePlaying = false
        snapshotFlow { pagerState.currentPage }.collect { page ->
            photos.getOrNull(page)?.let(onRequestExif)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photos[page]
            ZoomableImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                resetKey = photo.id,
                thumbModel = photo.thumbUri,
                onTap = { chromeVisible = !chromeVisible },
                onSwipeUp = { onTrash(photo) },
                onSwipeDown = onClose,
                onZoomChanged = { if (page == pagerState.currentPage) zoomed = it },
            )
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

        AnimatedVisibility(
            visible = chromeVisible && !infoVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 快捷归入：常看的那几个文件夹这里点一下就归，不用每次走相册选择器。
                if (quickAlbums.isNotEmpty() && current != null) {
                    QuickAlbumBar(
                        albums = quickAlbums,
                        onPick = { onMoveToQuickAlbum(current, it) },
                        onCreate = { current?.let(onCreateQuickAlbum) },
                    )
                }
                ViewerBottomBar(
                    photo = current,
                    onFavorite = { current?.let(onToggleFavorite) },
                    onMove = { current?.let(onMoveToAlbum) },
                    onDelete = { current?.let(onTrash) },
                    onInfo = { infoVisible = true },
                    onPlayLive = if (current?.isLivePhoto == true) ({ livePlaying = true }) else null,
                )
            }
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

        // Live Photo 全屏播放覆盖层。
        AnimatedVisibility(
            visible = livePlaying && current?.liveVideoUri != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            current?.liveVideoUri?.let { uri ->
                LivePhotoPlayer(uri = uri, onClose = { livePlaying = false })
            }
        }
    }
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

@Composable
private fun ViewerBottomBar(
    photo: PhotoItem?,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    onPlayLive: (() -> Unit)? = null,
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
            ViewerAction(Icons.Rounded.PlayArrow, "Live", onPlayLive)
        }
        ViewerAction(Icons.Rounded.Delete, "删除", onDelete, tint = Color(0xFFFF7B7F))
    }
}

@Composable
private fun QuickAlbumBar(
    albums: List<String>,
    onPick: (String) -> Unit,
    onCreate: () -> Unit = {},
) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = false,
                onClick = onCreate,
                label = { Text("+ 新建", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        items(albums) { name ->
            FilterChip(
                selected = false,
                onClick = { onPick(name) },
                label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

/** Live Photo 播放：用系统 VideoView 直接播同名短视频，全屏覆盖。 */
@Composable
private fun LivePhotoPlayer(uri: Uri, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(uri)
                    setOnPreparedListener { mp: MediaPlayer ->
                        mp.isLooping = true
                        start()
                    }
                    setOnErrorListener { _, _, _ -> true }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            Icon(Icons.Rounded.Close, "关闭", tint = Color.White)
        }
    }
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(MaterialTheme.colorScheme.surface)
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
