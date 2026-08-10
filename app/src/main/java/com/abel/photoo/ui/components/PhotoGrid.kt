package com.abel.photoo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.abel.photoo.data.media.Thumbs
import com.abel.photoo.data.media.ThumbRequest
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.TimelineSection
import com.abel.photoo.ui.util.Format

/** 网格里的一张缩略图。选中态用缩放 + 蓝色描边表达，比盖一层灰更接近澎湃的观感。 */
@Composable
fun PhotoThumb(
    photo: PhotoItem,
    selected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "thumbScale")
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(if (selected) 14.dp else 6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // 用系统缩略图服务按单元格尺寸取图（后台线程、系统缓存），比直接解码原图快很多。
        AsyncImage(
            model = ThumbRequest(photo.uri, Thumbs.TARGET),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .clip(RoundedCornerShape(if (selected) 14.dp else 6.dp)),
        )

        // 未处理的照片右上角点一个小圆点，扫一眼就知道还剩多少没整理。
        if (!photo.reviewed && !selectionMode) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        if (photo.favorite) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .size(14.dp),
            )
        }

        if (photo.isLivePhoto) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "Live",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (selectionMode) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.28f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** 时间线分段的标题行，带"全选本段"。 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String,
    allSelected: Boolean,
    selectionMode: Boolean,
    onToggleAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .clip(CircleShape)
                .combinedClickable(onClick = onToggleAll)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                if (selectionMode && allSelected) "取消" else "选择",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 把时间线分段铺进 LazyVerticalGrid。
 *
 * 不用 stickyHeader 是因为 LazyVerticalGrid 还没有稳定版实现，
 * 用整行 span 的普通 item 效果相近且不依赖实验 API。
 */
fun LazyGridScope.timelineSections(
    sections: List<TimelineSection>,
    selection: Set<Long>,
    selectionMode: Boolean,
    onPhotoClick: (PhotoItem) -> Unit,
    onPhotoLongClick: (PhotoItem) -> Unit,
    onToggleSection: (TimelineSection) -> Unit,
) {
    sections.forEach { section ->
        item(
            key = "h_${section.key}",
            span = { GridItemSpan(maxLineSpan) },
            contentType = "header",
        ) {
            SectionHeader(
                title = section.title,
                subtitle = section.subtitle,
                allSelected = section.photos.all { it.id in selection },
                selectionMode = selectionMode,
                onToggleAll = { onToggleSection(section) },
            )
        }
        items(
            count = section.photos.size,
            key = { i -> section.photos[i].id },
            contentType = { "photo" },
        ) { i ->
            val photo = section.photos[i]
            PhotoThumb(
                photo = photo,
                selected = photo.id in selection,
                selectionMode = selectionMode,
                onClick = { onPhotoClick(photo) },
                onLongClick = { onPhotoLongClick(photo) },
            )
        }
    }
}

/** 相册封面卡片。 */
@Composable
fun AlbumCard(
    name: String,
    count: Int,
    coverUri: android.net.Uri?,
    pending: Boolean,
    latestDate: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (coverUri != null) {
                AsyncImage(
                    model = ThumbRequest(coverUri, Thumbs.TARGET),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.42f),
                            )
                        ),
                )
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                )
            } else {
                Text(
                    if (pending) "空相册" else "无封面",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Row(
            Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    if (pending) "待建立" else "$count 张 · ${Format.friendlyDay(latestDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            trailing?.invoke()
        }
    }
}

/** 统一的空状态。 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Box(Modifier.padding(top = 8.dp)) { action() }
        }
    }
}
