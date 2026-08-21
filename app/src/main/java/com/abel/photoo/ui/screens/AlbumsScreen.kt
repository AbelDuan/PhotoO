package com.abel.photoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abel.photoo.model.AlbumItem
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.AlbumCard
import com.abel.photoo.ui.components.ConfirmDialog
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.components.LazyGridFastScroller
import com.abel.photoo.ui.components.TextInputDialog

/** 相册页。长按相册可以重命名 / 删除空相册。 */
@Composable
fun AlbumsScreen(
    vm: PhotoOViewModel,
    contentPadding: PaddingValues,
    onOpenAlbum: (AlbumItem) -> Unit,
    /** 收藏照片数量（用于"收藏"入口的计数）。 */
    favoriteCount: Int = 0,
    /** 收藏入口的封面（首张收藏照片的缩略图），没有收藏时传 null。 */
    favoriteCoverUri: android.net.Uri? = null,
    /** 打开"收藏"列表，单独 / 批量处理收藏的照片。 */
    onOpenFavorites: () -> Unit = {},
) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AlbumItem?>(null) }
    var deleting by remember { mutableStateOf<AlbumItem?>(null) }
    var actionTarget by remember { mutableStateOf<AlbumItem?>(null) }
    var sortMode by remember { mutableStateOf(false) }

    // 相册网格的快速滚动状态（仅在内容可滚动且非排序模式时显示拖动手柄）。
    val gridState = rememberLazyGridState()

    // 排序模式下展示的相册顺序（名字 + 拖动手柄调整）。外部顺序变化时同步回 albums。
    var ordered by remember(albums) { mutableStateOf(albums) }
    LaunchedEffect(albums) { ordered = albums }
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    // 排序卡片的实时尺寸（2 列布局下用于把拖拽位移换算成行列位移）。
    var itemSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    fun swap(from: Int, to: Int) {
        if (from == to || to !in ordered.indices) return
        val l = ordered.toMutableList()
        val it = l.removeAt(from)
        l.add(to, it)
        ordered = l
    }

    fun commitReorder() {
        vm.setAlbumOrder(ordered.map { it.relativePath })
        draggingIndex = -1
        dragDelta = Offset.Zero
    }

    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 40.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(if (sortMode) 8.dp else 18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            androidx.compose.foundation.layout.Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "相册",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            if (sortMode) commitReorder()
                            sortMode = !sortMode
                        },
                    ) {
                        Icon(Icons.Rounded.SwapVert, null, modifier = Modifier.padding(end = 6.dp))
                        Text(if (sortMode) "完成" else "排序")
                    }
                    Button(onClick = { creating = true }) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.padding(end = 6.dp))
                        Text("新建")
                    }
                }
            }
        }

        // 收藏入口：点进去看全部已收藏照片，可单独 / 批量处理（删除、归入、取消收藏等）。
        // 没有任何收藏时不显示，避免一个永远进不去的空入口。
        if (favoriteCount > 0) item(key = "favorites") {
            AlbumCard(
                name = "收藏",
                count = favoriteCount,
                coverUri = favoriteCoverUri,
                pending = false,
                latestDate = 0L,
                onClick = onOpenFavorites,
                onLongClick = {},
                trailing = {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }

        if (albums.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                EmptyState("还没有相册", "系统相册会在读取照片后自动出现。")
            }
        }

        val list = if (sortMode) ordered else albums
        items(list.size, key = { list[it].bucketId }) { i ->
            val album = list[i]
        if (sortMode) {
            // 排序模式：一行两个相册，左侧拖动手柄拖动调整顺序（支持跨列跨行 2D 拖拽）。
            val isDrag = i == draggingIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .onSizeChanged { itemSize = it }
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isDrag) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .then(
                        if (isDrag) {
                            Modifier.offset { IntOffset(dragDelta.x.roundToInt(), dragDelta.y.roundToInt()) }
                        } else Modifier
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.DragHandle,
                        contentDescription = "拖动排序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(28.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { draggingIndex = i; dragDelta = Offset.Zero },
                                    onDrag = { change: PointerInputChange, dragAmount: Offset ->
                                        dragDelta += dragAmount
                                        // 2 列网格：拖拽位移换算成"列偏移 + 行偏移"。
                                        val shiftCol = (dragDelta.x / itemSize.width.coerceAtLeast(1))
                                            .roundToInt()
                                        val shiftRow = (dragDelta.y / itemSize.height.coerceAtLeast(1))
                                            .roundToInt()
                                        val t = (draggingIndex + shiftRow * 2 + shiftCol)
                                            .coerceIn(0, ordered.lastIndex)
                                        if (t != draggingIndex) {
                                            swap(draggingIndex, t)
                                            draggingIndex = t
                                            dragDelta -= Offset(
                                                shiftCol * itemSize.width.toFloat(),
                                                shiftRow * itemSize.height.toFloat(),
                                            )
                                        }
                                    },
                                    onDragEnd = { commitReorder() },
                                    onDragCancel = { draggingIndex = -1; dragDelta = Offset.Zero },
                                )
                            },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        album.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                    )
                }
                Text(
                    "${album.count} 张",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        } else {
                AlbumCard(
                    name = album.name,
                    count = album.count,
                    coverUri = album.coverUri,
                    pending = album.pendingLocal,
                    latestDate = album.latestDate,
                    onClick = { onOpenAlbum(album) },
                    onLongClick = { actionTarget = album },
                    trailing = null,
                )
            }
        }
    }
        if (!sortMode) {
            LazyGridFastScroller(
                state = gridState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
            )
        }
    }

    if (creating) {
        TextInputDialog(
            title = "新建相册",
            label = "相册名称",
            confirmText = "创建",
            onConfirm = vm::createAlbum,
            onDismiss = { creating = false },
        )
    }

    renaming?.let { album ->
        TextInputDialog(
            title = "重命名相册",
            label = "新名称",
            initial = album.name,
            confirmText = "重命名",
            onConfirm = { vm.renameAlbum(album, it) },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { album ->
        ConfirmDialog(
            title = "删除相册",
            message = "「${album.name}」是 PhotoO 里新建、还没有照片的空相册，删除它不会影响任何文件。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteEmptyAlbum(album) },
            onDismiss = { deleting = null },
        )
    }

    actionTarget?.let { album ->
        AlbumActionDialog(
            album = album,
            onRename = { renaming = album },
            onDelete = { deleting = album },
            onDismiss = { actionTarget = null },
        )
    }
}

@Composable
private fun AlbumActionDialog(
    album: AlbumItem,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(album.name) },
        text = {
            Text(
                if (album.pendingLocal) "这个相册目前还是空的。"
                else "${album.count} 张照片 · ${album.relativePath}\n\n" +
                    "重命名会把相册里的照片移动到新目录，系统可能要求确认一次。"
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDismiss(); onRename() }) {
                Text("重命名")
            }
        },
        dismissButton = {
            if (album.pendingLocal) {
                androidx.compose.material3.TextButton(onClick = { onDismiss(); onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            } else {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
