package com.abel.photoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abel.photoo.model.LibraryStats
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.TimelineGrouping
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.components.detectDragSelect
import com.abel.photoo.ui.components.rememberDragSelectState
import com.abel.photoo.ui.components.timelineSections
import com.abel.photoo.ui.util.Format

/** 首页时间线：统计卡片 + 分组粒度切换 + 照片网格。 */
@Composable
fun TimelineScreen(
    vm: PhotoOViewModel,
    contentPadding: PaddingValues,
    onOpenPhoto: (PhotoItem) -> Unit,
    onStartReview: () -> Unit,
    onOpenSimilar: () -> Unit,
) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    val sections = remember(photos, settings.grouping) {
        Format.buildSections(photos, settings.grouping)
    }
    val gridState = rememberLazyGridState()
    // 长按拖动连续选择：网格容器上报位置，手势检测器命中后追加选中。
    val dragSelect = rememberDragSelectState()

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { dragSelect.containerTopLeft = it.boundsInRoot().topLeft }
            .pointerInput(Unit) {
                detectDragSelect(
                    state = dragSelect,
                    onPickStart = { id -> if (vm.selection.value.isEmpty()) vm.addSelection(listOf(id)) },
                    onPickOver = { vm.addSelection(listOf(it)) },
                )
            },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(settings.gridColumns),
            state = gridState,
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "stats") {
                StatsCard(
                    stats = stats,
                    onContinue = onStartReview,
                    onSimilar = onOpenSimilar,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "grouping") {
                GroupingRow(
                    current = settings.grouping,
                    onPick = vm::setGrouping,
                )
            }

            if (photos.isEmpty() && !loading) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyState(
                        title = "还没有照片",
                        subtitle = "确认已授予相册读取权限，或先用相机拍几张。",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                }
            }

            timelineSections(
                sections = sections,
                selection = selection,
                selectionMode = selection.isNotEmpty(),
                onPhotoClick = { photo ->
                    if (selection.isNotEmpty()) vm.toggleSelect(photo.id) else onOpenPhoto(photo)
                },
                onPhotoLongClick = { photo -> vm.toggleSelect(photo.id) },
                onToggleSection = { section ->
                    val ids = section.photos.map { it.id }
                    if (ids.all { it in selection }) {
                        vm.replaceSelection(selection - ids.toSet())
                    } else {
                        vm.select(ids)
                    }
                },
                dragSelect = dragSelect,
            )
        }

        if (loading && photos.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun StatsCard(
    stats: LibraryStats,
    onContinue: () -> Unit,
    onSimilar: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${stats.total}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "张照片 · ${stats.albums} 个相册",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${stats.pending}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (stats.pending > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "待整理",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LinearProgressIndicator(
            progress = { stats.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionPill(
                icon = Icons.Rounded.PhotoLibrary,
                text = if (stats.pending > 0) "继续整理 ${stats.pending} 张" else "整理已完成",
                primary = stats.pending > 0,
                modifier = Modifier.weight(1f),
                onClick = onContinue,
            )
            ActionPill(
                icon = Icons.Rounded.AutoAwesome,
                text = "找相似",
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = onSimilar,
            )
        }
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (primary) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun GroupingRow(
    current: TimelineGrouping,
    onPick: (TimelineGrouping) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimelineGrouping.entries.forEach { g ->
            FilterChip(
                selected = g == current,
                onClick = { onPick(g) },
                label = { Text(g.label) },
            )
        }
    }
}
