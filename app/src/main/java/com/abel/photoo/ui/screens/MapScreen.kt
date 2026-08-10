package com.abel.photoo.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.abel.photoo.data.geo.CoordTransform
import com.abel.photoo.data.geo.GeoClusterer
import com.abel.photoo.data.media.ThumbRequest
import com.abel.photoo.data.media.Thumbs
import com.abel.photoo.model.GeoCluster
import com.abel.photoo.model.GeoScanState
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.util.Format
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按拍摄地点分布照片。
 *
 * 两种展示方式：
 *  · 地点卡片（默认）—— 纯本地聚类，不联网、不需要任何配置，断网也能用；
 *  · 腾讯地图底图 —— 需要用户自己在腾讯位置服务开放平台申请 key 并在设置里填入。
 *    应用不内置任何地图 key，也不使用境外地图服务。
 *
 * 坐标要点：EXIF 里是 WGS-84，国内地图用的是 GCJ-02，直接丢过去会整体偏移几百米，
 * 所以送进底图之前统一走一次 [CoordTransform.wgs84ToGcj02]。
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
    var showBaseMap by remember { mutableStateOf(true) }
    var focusKey by remember { mutableStateOf<String?>(null) }
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

    val keyConfigured = settings.tencentMapKey.isNotBlank()
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
                    FilterChip(
                        selected = showBaseMap,
                        onClick = { showBaseMap = !showBaseMap },
                        label = { Text(if (keyConfigured) "腾讯地图底图" else "底图未配置") },
                        enabled = keyConfigured,
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Map,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        if (keyConfigured && showBaseMap && clusters.isNotEmpty()) {
            item("map") {
                TencentMapView(
                    clusters = clusters,
                    places = places,
                    apiKey = settings.tencentMapKey,
                    darkTheme = MaterialTheme.colorScheme.surface.luminanceIsDark(),
                    surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClusterClick = { focusKey = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }
        }

        if (!keyConfigured) {
            item("keyhint") {
                Text(
                    "想看真实底图？到「设置 → 地图底图」填入自己在腾讯位置服务开放平台申请的 key。" +
                        "没有 key 也不影响使用，下面的地点卡片完全离线。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    highlighted = cluster.key == focusKey,
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
    highlighted: Boolean,
    onOpenPhoto: (PhotoItem) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (highlighted) scheme.primaryContainer else scheme.surfaceContainerHigh)
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
                    color = if (highlighted) scheme.onPrimaryContainer else scheme.onSurface,
                )
                Text(
                    buildString {
                        append("${cluster.count} 张 · ")
                        append(Format.dayRange(cluster.earliestDate, cluster.latestDate))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (highlighted) scheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else scheme.onSurfaceVariant,
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
 * 腾讯地图底图（JavaScript API GL）。
 *
 * key 完全由用户在设置里提供，应用不内置任何 key；不使用任何境外地图服务。
 * 送进去的坐标已经从 WGS-84 转成 GCJ-02。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TencentMapView(
    clusters: List<GeoCluster>,
    places: Map<String, String>,
    apiKey: String,
    darkTheme: Boolean,
    surfaceColor: Color,
    onClusterClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 地名是异步陆续解析出来的，不能把它算进 html —— 否则每解析出一个地名
    // 底图就整个重载一次，视野也跟着复位。地名交给下面的卡片展示。
    val html = remember(clusters, apiKey, darkTheme) {
        buildMapHtml(clusters, apiKey, darkTheme, surfaceColor.toArgb())
    }
    // 只在 html 真的变了才重新 load，避免每次重组都刷一遍地图。
    val loaded = remember { arrayOfNulls<String>(1) }
    Box(modifier.background(surfaceColor)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    webViewClient = WebViewClient()
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onCluster(key: String) {
                                post { onClusterClick(key) }
                            }
                        },
                        "PhotoOBridge",
                    )
                }
            },
            update = { web ->
                if (loaded[0] != html) {
                    loaded[0] = html
                    web.loadDataWithBaseURL(
                        "https://map.qq.com/",
                        html,
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun buildMapHtml(
    clusters: List<GeoCluster>,
    apiKey: String,
    darkTheme: Boolean,
    bgArgb: Int,
): String {
    val arr = JSONArray()
    clusters.take(400).forEach { c ->
        val gcj = CoordTransform.wgs84ToGcj02(c.lat, c.lon)
        arr.put(
            JSONObject()
                .put("key", c.key)
                .put("lat", gcj.lat)
                .put("lng", gcj.lon)
                .put("n", c.count)
        )
    }
    val bg = String.format("#%06X", 0xFFFFFF and bgArgb)
    val labelColor = if (darkTheme) "#FFFFFF" else "#1B1B1F"
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
<style>
  html,body{margin:0;padding:0;height:100%;background:$bg;}
  #map{width:100%;height:100%;}
  #tip{position:absolute;left:0;right:0;top:40%;text-align:center;font:14px/1.6 sans-serif;
       color:$labelColor;padding:0 24px;}
</style>
<script charset="utf-8" src="https://map.qq.com/api/gljs?v=1.exp&key=$apiKey"></script>
</head>
<body>
<div id="map"></div>
<div id="tip">正在加载腾讯地图…<br/>若长时间无响应，请检查网络或 key 是否有效。</div>
<script>
  var PTS = ${arr};
  function boot() {
    if (typeof TMap === 'undefined') {
      document.getElementById('tip').innerHTML =
        '腾讯地图加载失败。<br/>请检查网络连接，或到「设置 → 地图底图」确认 key 是否正确、是否已在腾讯位置服务后台配置了域名白名单。';
      return;
    }
    document.getElementById('tip').style.display = 'none';
    var first = PTS.length ? new TMap.LatLng(PTS[0].lat, PTS[0].lng)
                           : new TMap.LatLng(39.9088, 116.3975);
    var map = new TMap.Map(document.getElementById('map'), {
      center: first, zoom: 9,
      showControl: false, baseMap: { type: 'vector' }
    });
    if (PTS.length > 1) {
      var b = new TMap.LatLngBounds();
      PTS.forEach(function (p) { b.extend(new TMap.LatLng(p.lat, p.lng)); });
      map.fitBounds(b, { padding: 60 });
    }
    var markers = new TMap.MultiMarker({
      map: map,
      geometries: PTS.map(function (p) {
        return { id: p.key, position: new TMap.LatLng(p.lat, p.lng) };
      })
    });
    var labels = new TMap.MultiLabel({
      map: map,
      styles: {
        cnt: new TMap.LabelStyle({
          color: '$labelColor', size: 12, offset: { x: 0, y: -42 },
          alignment: 'center', verticalAlignment: 'middle'
        })
      },
      geometries: PTS.map(function (p) {
        return {
          id: p.key, styleId: 'cnt',
          position: new TMap.LatLng(p.lat, p.lng),
          content: (p.t ? p.t + ' ' : '') + p.n
        };
      })
    });
    function fire(e) {
      if (e && e.geometry && window.PhotoOBridge) {
        PhotoOBridge.onCluster(e.geometry.id);
      }
    }
    markers.on('click', fire);
    labels.on('click', fire);
  }
  if (document.readyState === 'complete') boot();
  else window.addEventListener('load', boot);
</script>
</body>
</html>
    """.trimIndent()
}

/** 粗略判断当前配色是不是深色，用来决定地图样式和文字颜色。 */
private fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
