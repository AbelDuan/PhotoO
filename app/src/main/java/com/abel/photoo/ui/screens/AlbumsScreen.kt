package com.abel.photoo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abel.photoo.model.AlbumItem
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.AlbumCard
import com.abel.photoo.ui.components.ConfirmDialog
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.components.TextInputDialog

/** 相册页。长按相册可以重命名 / 删除空相册。 */
@Composable
fun AlbumsScreen(
    vm: PhotoOViewModel,
    contentPadding: PaddingValues,
    onOpenAlbum: (AlbumItem) -> Unit,
) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AlbumItem?>(null) }
    var deleting by remember { mutableStateOf<AlbumItem?>(null) }
    var actionTarget by remember { mutableStateOf<AlbumItem?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            androidx.compose.foundation.layout.Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "相册",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { creating = true }) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.padding(end = 6.dp))
                    Text("新建")
                }
            }
        }

        if (albums.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                EmptyState("还没有相册", "系统相册会在读取照片后自动出现。")
            }
        }

        items(albums.size, key = { albums[it].bucketId }) { i ->
            val album = albums[i]
            AlbumCard(
                name = album.name,
                count = album.count,
                coverUri = album.coverUri,
                pending = album.pendingLocal,
                latestDate = album.latestDate,
                onClick = { onOpenAlbum(album) },
                onLongClick = { actionTarget = album },
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
