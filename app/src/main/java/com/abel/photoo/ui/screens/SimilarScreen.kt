package com.abel.photoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import coil3.compose.AsyncImage
import com.abel.photoo.model.KeepStrategy
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.ScanState
import com.abel.photoo.model.SimilarGroup
import com.abel.photoo.model.SimilarityLevel
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.ConfirmDialog
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.components.LazyListFastScroller
import com.abel.photoo.ui.components.detectDragSelect
import com.abel.photoo.ui.components.rememberDragSelectState
import com.abel.photoo.ui.components.timelineSections
import com.abel.photoo.ui.util.Format

/**
 * 相似照片比对。
 *
 * 这个界面的设计原则是"永远不替用户做删除决定"：策略只负责把该删的预勾选出来，
 * 最终点删除的一定是用户自己，而且删除也只是进回收站，还能反悔。
 */
@Composable
fun SimilarScreen(
    vm: PhotoOViewModel,
    contentPadding: PaddingValues,
    onOpenPhoto: (PhotoItem) -> Unit,
    onOpenGroup: (String) -> Unit,
    onMovePicks: (List<Long>) -> Unit,
) {
    val groups by vm.similarGroups.collectAsStateWithLifecycle()
    val picks by vm.similarPicks.collectAsStateWithLifecycle()
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var showResolved by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRescan by remember { mutableStateOf(false) }
    // 组的排序：默认最近拍的排前面，也可以反过来先看老照片。
    var newestFirst by remember { mutableStateOf(true) }
    // 相似列表的快速滚动状态（仅在内容可滚动时显示拖动手柄）。
    val listState = rememberLazyListState()

    val visibleGroups = remember(groups, showResolved, newestFirst) {
        val base = if (showResolved) groups else groups.filterNot { it.resolved }
        if (newestFirst) {
            base.sortedByDescending { g -> g.items.maxOfOrNull { it.dateTaken } ?: 0L }
        } else {
            base.sortedBy { g -> g.items.minOfOrNull { it.dateTaken } ?: 0L }
        }
    }
    val pickedBytes = remember(picks, groups) {
        groups.flatMap { it.items }.filter { it.id in picks }.sumOf { it.size }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 36.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + (if (picks.isEmpty()) 24.dp else 96.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "head") {
                ScanPanel(
                    scan = scan,
                    groupCount = groups.size,
                    onScan = { vm.scanSimilar(false) },
                    onRescan = { confirmRescan = true },
                    onCancel = vm::cancelScan,
                )
            }

            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledChips(
                        label = "相似判定",
                        options = SimilarityLevel.entries.map { it.label },
                        selectedIndex = SimilarityLevel.entries.indexOf(settings.similarityLevel),
                        onSelect = { vm.setSimilarityLevel(SimilarityLevel.entries[it]) },
                    )
                    LabeledChips(
                        label = "保留策略",
                        options = KeepStrategy.entries.map { it.label },
                        selectedIndex = KeepStrategy.entries.indexOf(settings.keepStrategy),
                        onSelect = { vm.setKeepStrategy(KeepStrategy.entries[it]) },
                    )
                    LabeledChips(
                        label = "拍摄时间排序",
                        options = listOf("由新到旧", "由旧到新"),
                        selectedIndex = if (newestFirst) 0 else 1,
                        onSelect = { newestFirst = it == 0 },
                    )
                    Text(
                        settings.keepStrategy.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.preselectByStrategy(visibleGroups) },
                            enabled = visibleGroups.isNotEmpty(),
                        ) { Text("按策略预选") }
                        if (picks.isNotEmpty()) {
                            TextButton(onClick = vm::clearSimilarPicks) { Text("清空勾选") }
                        }
                        TextButton(onClick = { showResolved = !showResolved }) {
                            Text(if (showResolved) "隐藏已处理" else "显示已处理")
                        }
                    }
                }
            }

            if (visibleGroups.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = if (scan is ScanState.Done) "没有发现相似照片" else "还没有扫描",
                        subtitle = if (scan is ScanState.Done)
                            "当前判定档位下，图库里的照片彼此都够不一样。可以把档位调到「宽松」再试。"
                        else "点上面的按钮开始扫描。哈希算好后会缓存，之后再进来几乎是秒开。",
                    )
                }
            }

            items(visibleGroups, key = { it.key }) { group ->
                SimilarGroupCard(
                    group = group,
                    picks = picks,
                    onTogglePick = vm::toggleSimilarPick,
                    onAddPicks = vm::addSimilarPicks,
                    onRemovePicks = vm::removeSimilarPicks,
                    onOpenPhoto = onOpenPhoto,
                    onOpenGroup = onOpenGroup,
                    onIgnore = { vm.resolveGroup(group.key) },
                )
            }
        }
        LazyListFastScroller(
            state = listState,
            contentPadding = contentPadding,
        )

        if (picks.isNotEmpty()) {
            SimilarBatchBar(
                count = picks.size,
                onSelectAll = {
                    vm.setSimilarPicks(visibleGroups.flatMap { it.items }.map { it.id })
                },
                onMove = { onMovePicks(picks.toList()) },
                onTrash = { confirmDelete = true },
                onClear = vm::clearSimilarPicks,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 14.dp,
                        end = 14.dp,
                        bottom = contentPadding.calculateBottomPadding() + 14.dp,
                    ),
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "移入回收站",
            message = "${picks.size} 张照片将移入 PhotoO 回收站，可释放约 ${Format.bytes(pickedBytes)}。" +
                "回收站里还能恢复，确认彻底删除时才会同步给系统。",
            confirmText = "移入回收站",
            danger = true,
            onConfirm = vm::trashSimilarPicks,
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmRescan) {
        ConfirmDialog(
            title = "重新扫描",
            message = "会清空已算好的哈希并全部重来。图库很大时可能要花几分钟。",
            confirmText = "重新扫描",
            onConfirm = { vm.scanSimilar(true) },
            onDismiss = { confirmRescan = false },
        )
    }
}

@Composable
private fun ScanPanel(
    scan: ScanState,
    groupCount: Int,
    onScan: () -> Unit,
    onRescan: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.AutoAwesome,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text("相似照片", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (scan) {
                        is ScanState.Idle -> "尚未扫描"
                        is ScanState.Running -> "正在计算指纹 ${scan.done} / ${scan.total}"
                        is ScanState.Done -> "共 ${scan.groups} 组 · ${scan.photos} 张"
                        is ScanState.Failed -> scan.message
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (groupCount > 0) {
                AssistChip(
                    onClick = onRescan,
                    label = { Text("重扫") },
                    leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)) },
                )
            }
        }

        when (scan) {
            is ScanState.Running -> {
                LinearProgressIndicator(
                    progress = {
                        if (scan.total == 0) 0f else scan.done.toFloat() / scan.total
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                )
                TextButton(onClick = onCancel) { Text("停止扫描") }
            }

            else -> Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Text(if (groupCount > 0) "继续扫描新照片" else "开始扫描")
            }
        }
    }
}

@Composable
private fun LabeledChips(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { i, text ->
                FilterChip(
                    selected = i == selectedIndex,
                    onClick = { onSelect(i) },
                    label = { Text(text) },
                )
            }
        }
    }
}

@Composable
private fun SimilarGroupCard(
    group: SimilarGroup,
    picks: Set<Long>,
    onTogglePick: (Long) -> Unit,
    onAddPicks: (Collection<Long>) -> Unit,
    onRemovePicks: (Collection<Long>) -> Unit,
    onOpenPhoto: (PhotoItem) -> Unit,
    onOpenGroup: (String) -> Unit,
    onIgnore: () -> Unit,
) {
    // 组内横向行的"长按拖动连续选择"：长按某张不松手横向划过，划过的都追加勾选。
    val dragSelect = rememberDragSelectState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenGroup(group.key) }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    "${group.size} 张相似 · ${Format.relativeSpan(group.timeSpanMillis)}拍摄",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "差异度 ${group.maxDistance} · 清理后可省 ${Format.bytes(group.reclaimableBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "进入该组",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            if (group.resolved) {
                Text(
                    "已处理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onIgnore) { Text("忽略此组") }
            }
        }

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .onGloballyPositioned { dragSelect.containerTopLeft = it.boundsInRoot().topLeft }
                .pointerInput(Unit) {
                    detectDragSelect(
                        state = dragSelect,
                        isSelected = { id -> picks.contains(id) },
                        onPick = { id, sel ->
                            if (sel) onAddPicks(setOf(id)) else onRemovePicks(setOf(id))
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            group.items.forEach { photo ->
                Box(
                    Modifier.onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            dragSelect.bounds[photo.id] = coords.boundsInRoot()
                        }
                    }
                ) {
                    SimilarTile(
                        photo = photo,
                        picked = photo.id in picks,
                        suggested = photo.id == group.suggestedKeepId,
                        onOpen = {
                            // 已处于多选态时，单击继续增减选择；否则单击查看大图
                            if (picks.isNotEmpty()) onTogglePick(photo.id) else onOpenPhoto(photo)
                        },
                        onTogglePick = { onTogglePick(photo.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarTile(
    photo: PhotoItem,
    picked: Boolean,
    suggested: Boolean,
    onOpen: () -> Unit,
    onTogglePick: () -> Unit,
) {
    Column(
        Modifier.width(112.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = onTogglePick,
                ),
        ) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (picked) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.34f)),
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (picked) MaterialTheme.colorScheme.error
                        else Color.Black.copy(alpha = 0.3f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (picked) {
                    Icon(
                        Icons.Rounded.Check,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            if (suggested) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Star,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        "建议留",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
        }
        Text(
            Format.pixels(photo.width, photo.height),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            "${Format.bytes(photo.size)} · ${Format.clockTime(photo.dateTaken)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 多选操作条：相似列表页、相似组详情、地图地点相册共用同一条。 */
@Composable
internal fun SimilarBatchBar(
    count: Int,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f))
        BatchAct(Icons.Rounded.SelectAll, "全选", onSelectAll)
        BatchAct(Icons.Rounded.FolderOpen, "归档", onMove)
        BatchAct(Icons.Rounded.Delete, "删除", onTrash, MaterialTheme.colorScheme.error)
        BatchAct(Icons.Rounded.Close, "取消", onClear)
    }
}

@Composable
private fun BatchAct(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .width(58.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** 相似组详情底部常驻栏：上一组 / 下一组 / 忽略此组，并显示当前第几组。 */
@Composable
private fun SimilarDetailBar(
    hasPrev: Boolean,
    hasNext: Boolean,
    index: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        DetailAct(Icons.Rounded.ChevronLeft, "上一组", onPrev, enabled = hasPrev)
        Text(
            "$index / $total",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        DetailAct(Icons.Rounded.ChevronRight, "下一组", onNext, enabled = hasNext)
        DetailAct(
            Icons.Rounded.NotInterested,
            "忽略此组",
            onIgnore,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DetailAct(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val a = if (enabled) 1f else 0.38f
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .width(64.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(30.dp)) {
            Icon(icon, contentDescription = label, tint = tint.copy(alpha = a), modifier = Modifier.size(21.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = a))
    }
}

/**
 * 单个相似组的详情页：点标题进入后，这里把整组照片铺成网格。
 * 交互与时间线一致——单点看大图，长按进入多选，再点切换选中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarGroupDetailScreen(
    vm: PhotoOViewModel,
    groups: List<SimilarGroup>,
    currentKey: String,
    onBack: () -> Unit,
    onOpenPhoto: (PhotoItem) -> Unit,
    onIgnore: () -> Unit,
    onMovePicks: (List<Long>) -> Unit,
    onNavigateToGroup: (String) -> Unit,
) {
    val picks by vm.similarPicks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    val group = groups.firstOrNull { it.key == currentKey }
    // 当前组被删空或已不在列表里时，自动退出详情。
    LaunchedEffect(group) { if (group == null) onBack() }
    if (group == null) return

    val index = groups.indexOfFirst { it.key == currentKey }
    val prevKey = if (index > 0) groups[index - 1].key else null
    val nextKey = if (index in 0 until groups.lastIndex) groups[index + 1].key else null

    val ids = remember(group) { group.items.map { it.id } }
    // buildSections 要求输入按时间倒序，这里统一排一次，组内顺序才稳定。
    val sections = remember(group, settings.grouping) {
        Format.buildSections(group.items.sortedByDescending { it.dateTaken }, settings.grouping)
    }

    // 一组只保留 1 张（其余都删了）时，自动跳到下一组（优先往后，没有则往前），
    // 没有可跳的组就退出，避免停在一张没法再整理的孤图上。
    var lastSize by remember { mutableStateOf(group.size) }
    LaunchedEffect(group.size) {
        val s = group.size
        if (s <= 1 && lastSize > 1) {
            val target = groups.drop(index + 1).firstOrNull { !it.resolved && it.size > 1 }?.key
                ?: groups.take(index).lastOrNull { !it.resolved && it.size > 1 }?.key
            if (target != null) onNavigateToGroup(target) else onBack()
        }
        lastSize = s
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${group.size} 张相似", maxLines = 1)
                        Text(
                            "差异度 ${group.maxDistance} · 清理后可省 ${Format.bytes(group.reclaimableBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    }
                },
                actions = {},
            )
        },
    ) { inner ->
        // 相似组详情网格的"长按拖动连续选择"：划过的照片一路追加勾选。
        val dragSelect = rememberDragSelectState()
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { dragSelect.containerTopLeft = it.boundsInRoot().topLeft }
                .pointerInput(Unit) {
                    detectDragSelect(
                        state = dragSelect,
                        isSelected = { id -> picks.contains(id) },
                        onPick = { id, sel ->
                            if (sel) vm.addSimilarPicks(setOf(id)) else vm.removeSimilarPicks(setOf(id))
                        },
                    )
                },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(settings.gridColumns),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = inner.calculateTopPadding(),
                    bottom = inner.calculateBottomPadding() + if (picks.isEmpty()) 96.dp else 160.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                timelineSections(
                    sections = sections,
                    selection = picks,
                    selectionMode = picks.isNotEmpty(),
                    onPhotoClick = { photo ->
                        if (picks.isNotEmpty()) vm.toggleSimilarPick(photo.id) else onOpenPhoto(photo)
                    },
                    // 长按进入多选由外层 detectDragSelect 统一处理，置空避免与拖选 onPick 抵消。
                    onPhotoLongClick = { },
                    onToggleSection = { section ->
                        val sids = section.photos.map { it.id }
                        if (sids.all { it in picks }) vm.setSimilarPicks(picks - sids.toSet())
                        else vm.setSimilarPicks(picks + sids.toSet())
                    },
                    dragSelect = dragSelect,
                )
            }

            if (picks.isNotEmpty()) {
                SimilarBatchBar(
                    count = picks.size,
                    onSelectAll = { vm.setSimilarPicks(ids) },
                    onMove = { onMovePicks(picks.toList()) },
                    onTrash = { confirmDelete = true },
                    onClear = vm::clearSimilarPicks,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 14.dp, end = 14.dp, bottom = 92.dp),
                )
            }
            // 切换组 / 忽略此组 常驻底部，单手即可操作。
            SimilarDetailBar(
                hasPrev = prevKey != null,
                hasNext = nextKey != null,
                index = index + 1,
                total = groups.size,
                onPrev = { if (prevKey != null) onNavigateToGroup(prevKey) },
                onNext = { if (nextKey != null) onNavigateToGroup(nextKey) },
                onIgnore = onIgnore,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "移入回收站",
            message = "${picks.size} 张照片将移入 PhotoO 回收站，可释放约 " +
                "${Format.bytes(group.reclaimableBytes)}。" +
                "回收站里还能恢复，确认彻底删除时才会同步给系统。",
            confirmText = "移入回收站",
            danger = true,
            onConfirm = vm::trashSimilarPicks,
            onDismiss = { confirmDelete = false },
        )
    }
}
