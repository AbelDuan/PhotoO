package com.abel.photoo.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.ConfirmDialog
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.util.Format
import kotlin.math.abs

/**
 * PhotoO 回收站。
 *
 * 这里的"彻底删除"才会真正调用系统删除接口 —— 这是需求里"回收站中删除再同步给系统删除"
 * 的落点，也是整个应用唯一会造成不可逆后果的地方，所以做了二次确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    vm: PhotoOViewModel,
    onBack: () -> Unit,
) {
    val trash by vm.trash.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }

    val totalBytes = remember(trash) { trash.sumOf { it.size } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("回收站")
                        Text(
                            "${trash.size} 张 · ${Format.bytes(totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (trash.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = inner.calculateTopPadding() + 6.dp,
                    bottom = inner.calculateBottomPadding() + if (picked.isEmpty()) 24.dp else 100.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (trash.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            title = "回收站是空的",
                            subtitle = "在照片上向上滑动就能把它丢进来。丢进来的照片不会立刻从系统删除。",
                            modifier = Modifier.padding(top = 60.dp),
                        )
                    }
                }
                items(trash.size, key = { trash[it].id }) { i ->
                    val item = trash[i]
                    val selected = item.id in picked
                    // 视频：以 uri 是否落在 /video/ 路径下判断（与网格层 ThumbnailLoader 一致）。
                    val isVideo = item.uri.toString().contains("/video/", ignoreCase = true)
                    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
                    val animOffset by animateFloatAsState(offsetX, spring(), label = "trashSwipe")
                    // 右滑选中（底层露主色 + ✓），左滑取消（露错误色 + ✕）；松手回弹。
                    val revealColor = if (animOffset >= 0f) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(revealColor)
                            .pointerInput(item.id) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var totalX = 0f
                                    var axisLocked = false
                                    do {
                                        val event = awaitPointerEvent()
                                        val pan = event.calculatePan()
                                        totalX += pan.x
                                        if (!axisLocked && abs(totalX) > viewConfiguration.touchSlop) {
                                            axisLocked = true
                                        }
                                        if (axisLocked) {
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            offsetX = totalX.coerceIn(-size.width * 0.5f, size.width * 0.5f)
                                        }
                                    } while (event.changes.any { it.pressed })
                                    val threshold = size.width * 0.3f
                                    when {
                                        totalX > threshold -> if (!selected) picked = picked + item.id
                                        totalX < -threshold -> if (selected) picked = picked - item.id
                                    }
                                    offsetX = 0f
                                }
                            }
                            .clickable {
                                picked = if (selected) picked - item.id else picked + item.id
                            },
                    ) {
                        // 滑动时露出的底层图标（方向不同图标不同）。
                        Box(Modifier.fillMaxSize()) {
                            Icon(
                                if (animOffset >= 0f) Icons.Rounded.Check else Icons.Rounded.Close,
                                null,
                                tint = if (animOffset >= 0f) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .align(if (animOffset >= 0f) Alignment.CenterStart else Alignment.CenterEnd)
                                    .padding(horizontal = 16.dp)
                                    .size(22.dp),
                            )
                        }
                        // 卡片内容：随手指横向位移，松手回弹归位。
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = animOffset }
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clip(RoundedCornerShape(10.dp)),
                        ) {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = if (selected) 0.32f else 0.12f)),
                            )
                            if (isVideo) {
                                Box(
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else Color.Black.copy(alpha = 0.3f)
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Text(
                                Format.friendlyDay(item.deletedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            }

            if (picked.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(14.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "已选 ${picked.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        vm.restore(picked)
                        picked = emptySet()
                    }) {
                        Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp))
                        Text("恢复", Modifier.padding(start = 6.dp))
                    }
                    Button(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                        Text("彻底删除", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "彻底删除",
            message = "${picked.size} 张照片会从系统里真正删除，无法恢复。系统可能会再弹一次确认框。",
            confirmText = "永久删除",
            danger = true,
            onConfirm = {
                vm.deleteForever(picked)
                picked = emptySet()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = "清空回收站",
            message = "回收站里的 ${trash.size} 张照片会全部从系统删除，无法恢复。",
            confirmText = "全部删除",
            danger = true,
            onConfirm = {
                vm.emptyTrash()
                picked = emptySet()
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}
