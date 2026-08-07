package com.abel.photoo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abel.photoo.model.AlbumItem
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.components.timelineSections
import com.abel.photoo.ui.util.Format

/** 单个相册的照片列表，复用时间线的分段网格。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    vm: PhotoOViewModel,
    album: AlbumItem,
    onBack: () -> Unit,
    onOpenPhoto: (PhotoItem) -> Unit,
) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val members = remember(photos, album.bucketId) {
        photos.filter { it.bucketId == album.bucketId }
    }
    val sections = remember(members, settings.grouping) {
        Format.buildSections(members, settings.grouping)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(album.name, maxLines = 1)
                        Text(
                            "${members.size} 张 · ${album.relativePath}",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
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
                    IconButton(onClick = {
                        val ids = members.map { it.id }
                        if (ids.all { it in selection }) vm.clearSelection()
                        else vm.replaceSelection(ids)
                    }) {
                        Icon(Icons.Rounded.SelectAll, "全选")
                    }
                },
            )
        },
    ) { inner ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(settings.gridColumns),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = inner.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (members.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = "相册是空的",
                        subtitle = if (album.pendingLocal)
                            "这是刚建好的相册，把照片归档进来后目录才会真正创建。"
                        else "这个相册里的照片可能已被移动或删除。",
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
                onPhotoLongClick = { vm.toggleSelect(it.id) },
                onToggleSection = { section ->
                    val ids = section.photos.map { it.id }
                    if (ids.all { it in selection }) vm.replaceSelection(selection - ids.toSet())
                    else vm.select(ids)
                },
            )
        }
    }
}
