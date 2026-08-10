package com.abel.photoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.abel.photoo.data.geo.GeoClusterer
import com.abel.photoo.data.media.ThumbRequest
import com.abel.photoo.data.media.Thumbs
import com.abel.photoo.model.GeoCluster
import com.abel.photoo.model.GeoScanState
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.util.Format
import kotlin.math.sqrt

/**
 * 按拍摄地点分布照片。
 *
 * 完全离线、不需要任何地图 key、不需要任何手动配置：
 *  · 上面的示意图：把每个聚类点按经纬度相对位置画成圆点，点一下就打开那组照片；
 *  · 下面的地点卡片：带封面、张数、时间跨度和缩略图，并尽量离线反查地名。
 *
 * 不接任何境外地图服务，也不在应用里内置 key。
 */
@Composable
fun MapScreen(
    vm: PhotoOViewModel,
    contentPadding: PaddingValues,
    onOpenPhoto: (List<PhotoItem>, PhotoItem) -> Unit,
) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val geoPoints by vm.geoPoints.collectAsStateWithLifecycle()
    val scanState by vm.geoScanState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var level by remember { mutableStateOf(GeoClusterer.Level.DISTRICT) }
    var places by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 进这个页面才开始扫 GPS：结果落库，第二次进来是秒开的。
    LaunchedEffect(photos.size) {
        if (photos.isNotEmpty()) vm.scanGeo()
    }

    val byId = remember(photos) { photos.associateBy { it.id } }
    val clusters = remember(geoPoints, byId, level) {
        GeoClusterer.cluster(geoPoints, byId, level)
    }

    // 地名按需解析，且只解析前若干个簇 —— Geocoder 很慢，全解析会拖垮列表。
    LaunchedEffect(clusters, settings.showLocation) {
        if (!settings.showLocation) return@LaunchedEffect
        clusters.take(20).forEach { c ->
            if (places.containsKey(c.key)) return@forEach
            val sample = c.cover ?: return@forEach
            val name = vm.repo.placeOf(c.lat, c.lon, sample.id, sample.uri)
            if (!name.isNullOrBlank()) places = places + (c.key to name)
        }
    }

    val located = geoPoints.size

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        item("controls") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$located 张照片带位置 · ${clusters.size} 个地点",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.scanGeo(force = true) }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("重扫")
                    }
                }

                (scanState as? GeoScanState.Running)?.let { running ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "正在读取 EXIF 位置… ${running.done} / ${running.total}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (running.total == 0) 0f
                                else running.done.toFloat() / running.total
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GeoClusterer.Level.entries.forEach { lv ->
                        FilterChip(
                            selected = lv == level,
                            onClick = { level = lv },
                            label = { Text(lv.label) },
                            leadingIcon = if (lv == level) {
                                {
                                    Icon(
                                        Icons.Rounded.Layers,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }

        if (clusters.isNotEmpty()) {
            item("plot") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "拍摄地点分布（相对位置示意，点圆点看照片）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MapPlot(
                        clusters = clusters,
                        onOpenCluster = { c ->
                            onOpenPhoto(c.photos, c.cover ?: c.photos.first())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(20.dp)),
                    )
                }
            }
        }

        if (clusters.isEmpty()) {
            item("empty") {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp)) {
                    EmptyState(
                        title = if (scanState is GeoScanState.Running) "正在扫描位置信息" else "还没有带位置的照片",
                        subtitle = "PhotoO 只读取照片 EXIF 里已有的经纬度，" +
                            "不会请求定位权限，也不会上传任何位置信息。\n" +
                            "如果照片确实拍摄时开了定位却读不到，检查一下系统是否给了「访问媒体位置」权限。",
                    )
                }
            }
        } else {
            items(clusters, key = { it.key }) { cluster ->
                ClusterCard(
                    cluster = cluster,
                    place = places[cluster.key],
                    onOpenPhoto = { photo -> onOpenPhoto(cluster.photos, photo) },
                )
            }
        }
    }
}

/** 一个地点的卡片：封面 + 张数 + 时间跨度 + 一排缩略图。 */
@Composable
private fun ClusterCard(
    cluster: GeoCluster,
    place: String?,
    onOpenPhoto: (PhotoItem) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            cluster.cover?.let { cover ->
                AsyncImage(
                    model = ThumbRequest(cover.uri, Thumbs.TARGET),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    place ?: Format.latLon(cluster.lat, cluster.lon),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    color = scheme.onSurface,
                )
                Text(
                    buildString {
                        append("${cluster.count} 张 · ")
                        append(Format.dayRange(cluster.earliestDate, cluster.latestDate))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(cluster.photos.take(24), key = { it.id }) { photo ->
                AsyncImage(
                    model = ThumbRequest(photo.uri, Thumbs.TARGET),
                    contentDescription = photo.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainerHighest)
                        .clickable { onOpenPhoto(photo) },
                )
            }
            if (cluster.photos.size > 24) {
                item {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+${cluster.photos.size - 24}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自绘的拍摄地点示意图，不需要任何地图 key、不联网。
 * 把每个聚类点按经纬度相对位置铺到画布上，圆点越大表示照片越多，点一下打开那组照片。
 */
@Composable
private fun MapPlot(
    clusters: List<GeoCluster>,
    onOpenCluster: (GeoCluster) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    BoxWithConstraints(modifier) {
        val pad = 22.dp
        val maxW = maxWidth
        val maxH = maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .background(scheme.surfaceContainerHigh),
        ) {
            // 淡网格，营造"地图"感
            Canvas(Modifier.fillMaxSize()) {
                val lines = 8
                val grid = scheme.onSurfaceVariant.copy(alpha = 0.08f)
                val w = 1.dp.toPx()
                repeat(lines + 1) { i ->
                    val f = i.toFloat() / lines
                    drawLine(grid, Offset(0f, f * size.height), Offset(size.width, f * size.height), w)
                    drawLine(grid, Offset(f * size.width, 0f), Offset(f * size.width, size.height), w)
                }
            }

            if (clusters.isNotEmpty()) {
                val minLat = clusters.minOf { it.lat }
                val maxLat = clusters.maxOf { it.lat }
                val minLon = clusters.minOf { it.lon }
                val maxLon = clusters.maxOf { it.lon }
                val spanLat = (maxLat - minLat).toFloat().coerceAtLeast(1e-3f)
                val spanLon = (maxLon - minLon).toFloat().coerceAtLeast(1e-3f)
                val singleCol = (maxLon - minLon) < 1e-3
                val singleRow = (maxLat - minLat) < 1e-3
                val innerW = maxW - pad * 2
                val innerH = maxH - pad * 2

                clusters.forEach { c ->
                    val nx = if (singleCol) 0.5f else (c.lon - minLon).toFloat() / spanLon
                    val ny = if (singleRow) 0.5f else (maxLat - c.lat).toFloat() / spanLat
                    val x = pad + innerW * nx
                    val y = pad + innerH * ny
                    val dia = (26.dp + 7.dp * sqrt(c.count.toFloat())).coerceAtMost(64.dp)
                    Box(
                        Modifier
                            .offset(x = x - dia / 2, y = y - dia / 2)
                            .size(dia)
                            .clip(CircleShape)
                            .background(scheme.primary)
                            .clickable { onOpenCluster(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            c.count.toString(),
                            color = scheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
