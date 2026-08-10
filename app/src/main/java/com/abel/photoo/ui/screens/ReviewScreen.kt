package com.abel.photoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abel.photoo.model.GestureDirection
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.components.ZoomableImage
import com.abel.photoo.ui.util.Format
import kotlinx.coroutines.launch

/**
 * 未处理照片的整理模式。
 *
 * 一次只面对一张，四个动作：保留 / 归档 / 跳过 / 删除。
 * 处理过的照片会被标记，下次进来直接从没处理过的那批继续，
 * 这也是设置里"启动即进入整理"依赖的入口。
 */
@Composable
fun ReviewScreen(
    vm: PhotoOViewModel,
    onExit: () -> Unit,
    onMoveToAlbum: (PhotoItem) -> Unit,
) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val exif by vm.exifCache.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 队列在进入时冻结一次。否则每处理一张，列表就重排，手指下的照片会突然跳走。
    val queue = remember {
        photos.filterNot { it.reviewed }.sortedByDescending { it.dateTaken }.map { it.id }
    }
    val live = remember(photos, queue) {
        val byId = photos.associateBy { it.id }
        queue.mapNotNull { byId[it] }
    }
    val doneCount = remember(live) { live.count { it.reviewed } }

    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "全部整理完了",
                subtitle = "所有照片都已经过一次筛选。想重来的话，可以在设置里重置整理进度。",
                action = {
                    Text(
                        "返回",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onExit)
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                },
            )
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { live.size })
    var zoomed by remember { mutableStateOf(false) }
    val current = live.getOrNull(pagerState.currentPage)

    LaunchedEffect(current?.id) { current?.let(vm::loadExif) }

    fun advance() {
        scope.launch {
            val next = pagerState.currentPage + 1
            if (next < live.size) pagerState.animateScrollToPage(next)
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
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = live[page]
            ZoomableImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                resetKey = photo.id,
                onTap = {},
                // 整理模式的手势是固定语义（上滑删、下滑退），不跟设置里的自定义走，
                // 否则"整理"这个流程本身就没法保证一致的肌肉记忆了。
                sensitivity = vm.prefs.current.gestureSensitivity.factor,
                onSwipe = { dir ->
                    when (dir) {
                        GestureDirection.UP -> {
                            vm.moveToTrash(listOf(photo.id))
                            advance()
                        }
                        GestureDirection.DOWN -> onExit()
                        else -> Unit
                    }
                },
                flyOut = { true },
                onZoomChanged = { if (page == pagerState.currentPage) zoomed = it },
            )
        }

        // 顶部：进度
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 6.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Rounded.Close, "退出整理", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "整理中 ${pagerState.currentPage + 1} / ${live.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildString {
                            append(current?.let { Format.friendlyDay(it.dateTaken) }.orEmpty())
                            current?.let { append(" · ${Format.bytes(it.size)}") }
                            exif[current?.id]?.cameraTitle
                                ?.takeIf { it.isNotBlank() }
                                ?.let { append(" · $it") }
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                Text(
                    "已处理 $doneCount",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            LinearProgressIndicator(
                progress = { if (live.isEmpty()) 0f else doneCount.toFloat() / live.size },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                    .height(4.dp)
                    .clip(CircleShape),
            )
        }

        // 底部：四个动作
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReviewAction(Icons.Rounded.Delete, "删除", Color(0xFFFF7B7F)) {
                current?.let { vm.moveToTrash(listOf(it.id)) }
                advance()
            }
            ReviewAction(Icons.Rounded.Schedule, "稍后", Color.White) {
                current?.let { vm.markSkipped(listOf(it.id)) }
                advance()
            }
            ReviewAction(Icons.Rounded.Folder, "归档", Color.White) {
                current?.let(onMoveToAlbum)
            }
            ReviewAction(Icons.Rounded.Check, "保留", Color(0xFF7BE2A6)) {
                current?.let { vm.markKept(listOf(it.id)) }
                advance()
            }
        }
    }
}

@Composable
private fun ReviewAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tint)
        }
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}
