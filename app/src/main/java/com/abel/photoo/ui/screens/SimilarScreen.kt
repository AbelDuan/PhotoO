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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.abel.photoo.model.KeepStrategy
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.ScanState
import com.abel.photoo.model.SimilarGroup
import com.abel.photoo.model.SimilarityLevel
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.ConfirmDialog
import com.abel.photoo.ui.components.EmptyState
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

    val visibleGroups = remember(groups, showResolved) {
        if (showResolved) groups else groups.filterNot { it.resolved }
    }
    val pickedBytes = remember(picks, groups) {
        groups.flatMap { it.items }.filter { it.id in picks }.sumOf { it.size }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
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
                    onOpenPhoto = onOpenPhoto,
                    onOpenGroup = onOpenGroup,
                    onIgnore = { vm.resolveGroup(group.key) },
                )
            }
        }

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
    onOpenPhoto: (PhotoItem) -> Unit,
    onOpenGroup: (String) -> Unit,
    onIgnore: () -> Unit,
) {
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
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            group.items.forEach { photo ->
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

/** 相似照片的多选操作条，列表页和组内详情页共用。 */
@Composable
private fun SimilarBatchBar(
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

/**
 * 单个相似组的详情页：点标题进入后，这里把整组照片铺成网格。
 * 交互与时间线一致——单点看大图，长按进入多选，再点切换选中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarGroupDetailScreen(
    vm: PhotoOViewModel,
    group: SimilarGroup,
    onBack: () -> Unit,
    onOpenPhoto: (PhotoItem) -> Unit,
    onIgnore: () -> Unit,
    onMovePicks: (List<Long>) -> Unit,
) {
    val picks by vm.similarPicks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    val ids = remember(group) { group.items.map { it.id } }
    val sections = remember(group, settings.grouping) {
        Format.buildSections(group.items, settings.grouping)
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
                actions = {
                    if (group.resolved) {
                        Text(
                            "已处理",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = onIgnore) { Text("忽略此组") }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(settings.gridColumns),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = inner.calculateTopPadding(),
                    bottom = inner.calculateBottomPadding() + if (picks.isEmpty()) 24.dp else 96.dp,
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
                    onPhotoLongClick = { vm.toggleSimilarPick(it.id) },
                    onToggleSection = { section ->
                        val sids = section.photos.map { it.id }
                        if (sids.all { it in picks }) vm.setSimilarPicks(picks - sids.toSet())
                        else vm.setSimilarPicks(picks + sids.toSet())
                    },
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
                        .padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                )
            }
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
