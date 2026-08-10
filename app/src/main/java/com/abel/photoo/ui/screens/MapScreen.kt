package com.abel.photoo.ui.screens

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.abel.photoo.data.geo.CoordTransform
import com.abel.photoo.data.media.ThumbRequest
import com.abel.photoo.data.media.Thumbs
import com.abel.photoo.model.GeoCluster
import com.abel.photoo.model.GeoScanState
import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.TimelineGrouping
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.ConfirmDialog
import com.abel.photoo.ui.components.EmptyState
import com.abel.photoo.ui.components.timelineSections
import com.abel.photoo.ui.util.Format
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * 按拍摄地点分布照片。
 *
 * 两种底图：
 *  · 联网（填了高德 key）：用高德 JS API 渲染真实地图，双指缩放 / 拖动平移 / 点标记进相册；
 *    坐标已做 WGS-84→GCJ-02 纠偏，点位不会偏移。
 *  · 离线（没 key）：自绘相对位置散点图，零网络、零 key。
 * 两种模式下，下面的地点卡片都会尽量解析出可读地址（联网走高德逆地理，离线走设备 Geocoder）。
 */
@Composable
fun MapScreen(
    vm: PhotoOViewModel,
    contentPadding: PaddingValues,
    onOpenPhoto: (List<PhotoItem>, PhotoItem) -> Unit,
    onOpenCluster: (GeoCluster) -> Unit,
) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val geoPoints by vm.geoPoints.collectAsStateWithLifecycle()
    val scanState by vm.geoScanState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var level by remember { mutableStateOf(com.abel.photoo.data.geo.GeoClusterer.Level.DISTRICT) }
    // 已经解析出的地址名（cluster.key -> 地址）。一律由 Kotlin 侧解析：
    // 有高德 key 时内部走高德 REST 逆地理（失败回退设备 Geocoder），没 key 直接走设备 Geocoder。
    // 不依赖 WebView 里 JS 的逆地理——key 无效 / JS 加载失败时地址依然能出来。
    val places = remember { mutableStateMapOf<String, String>() }
    // 高德底图加载失败（key 无效 / 断网）时回退离线示意图，不再白屏。
    var amapFailed by remember(settings.amapKey) { mutableStateOf(false) }

    LaunchedEffect(photos.size) {
        if (photos.isNotEmpty()) vm.scanGeo()
    }

    val byId = remember(photos) { photos.associateBy { it.id } }
    val clusters = remember(geoPoints, byId, level) {
        com.abel.photoo.data.geo.GeoClusterer.cluster(geoPoints, byId, level)
    }

    // 地址解析：逐簇渐进，两种底图模式共用这一条路径。
    LaunchedEffect(clusters, settings.amapKey) {
        if (!settings.showLocation) return@LaunchedEffect
        clusters.forEach { c ->
            if (places.containsKey(c.key)) return@forEach
            val name = vm.repo.reverseGeocode(c.lat, c.lon)
            if (!name.isNullOrBlank()) places[c.key] = name
        }
    }

    val located = geoPoints.size
    val useOnline = settings.amapKey.isNotBlank()

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
                    com.abel.photoo.data.geo.GeoClusterer.Level.entries.forEach { lv ->
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
                        when {
                            !useOnline -> "拍摄地点分布（离线示意图，点圆点进相册）"
                            amapFailed -> "高德底图加载失败（Key 无效或网络问题），已切换离线示意图"
                            else -> "拍摄地点分布（高德联网底图，双指缩放 / 拖动平移，点圆点进相册）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (useOnline && !amapFailed) {
                        AMapView(
                            clusters = clusters,
                            amapKey = settings.amapKey,
                            places = places,
                            onOpenCluster = onOpenCluster,
                            onName = { key, name -> if (name.isNotBlank()) places[key] = name },
                            onFailed = { amapFailed = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                                .clip(RoundedCornerShape(20.dp)),
                        )
                    } else {
                        MapPlot(
                            clusters = clusters,
                            onOpenCluster = { c -> onOpenCluster(c.copy(place = places[c.key])) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .clip(RoundedCornerShape(20.dp)),
                        )
                    }
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
                    onOpenCluster = { onOpenCluster(cluster.copy(place = places[cluster.key])) },
                )
            }
        }
    }
}

/** 一个地点的卡片：标题 + 张数/时间跨度 + 一排缩略图；点标题区进入该地址相册。布局对齐相似栏目。 */
@Composable
private fun ClusterCard(
    cluster: GeoCluster,
    place: String?,
    onOpenPhoto: (PhotoItem) -> Unit,
    onOpenCluster: () -> Unit,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenCluster() }
                .padding(vertical = 2.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    place ?: Format.latLon(cluster.lat, cluster.lon),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    color = scheme.onSurface,
                )
                Text(
                    buildString {
                        append("${cluster.count} 张 · ")
                        append(Format.dayRange(cluster.earliestDate, cluster.latestDate))
                        append(" · 点击查看")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.Place,
                contentDescription = "进入该地点",
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
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

/** AMapView 的 JS 桥回调容器：WebView 线程里取最新闭包用。 */
private class AmapBridge(
    val open: (GeoCluster) -> Unit,
    val name: (String, String) -> Unit,
    val byKey: Map<String, GeoCluster>,
)

/**
 * 高德地图 WebView 底图。
 *
 * 通过 JS 接口把"标记点击 / 地名回传"抛回 Compose；坐标先转 GCJ-02 再交给高德，
 * 点位与街道对齐。无 key 时由调用方回退到 [MapPlot]。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AMapView(
    clusters: List<GeoCluster>,
    amapKey: String,
    places: Map<String, String>,
    onOpenCluster: (GeoCluster) -> Unit,
    onName: (String, String) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 让 WebView 线程里创建的回调能拿到最新的 lambda / 簇表。
    // 用显式类型（而非匿名 object）避免两次组合产生不同匿名类导致类型不匹配。
    val cb = remember {
        AtomicReference(
            AmapBridge(onOpenCluster, onName, clusters.associateBy { it.key })
        )
    }
    cb.set(AmapBridge(onOpenCluster, onName, clusters.associateBy { it.key }))
    val renderArgs = remember { AtomicReference<Pair<List<GeoCluster>, Map<String, String>>>(clusters to places) }
    renderArgs.set(clusters to places)

    val html = remember(amapKey) { buildAmapHtml(amapKey) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setGeolocationEnabled(false)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val (cs, ps) = renderArgs.get()
                        view?.postDelayed({ doRender(view, cs, ps) }, 250)
                        // 高德 JS 若因 key 无效 / 网络被墙而没初始化，onAmapReady 不会执行。
                        // 等 5 秒后探一次 window.amapReady，仍是 false 就判定失败回退离线图。
                        view?.postDelayed({
                            view.evaluateJavascript("window.amapReady?true:false") { r ->
                                if (r != "true") onFailed()
                            }
                        }, 5000)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        // 只有主框架（页面 / 高德脚本本身）失败才回退；
                        // 地图瓦片等子资源偶发失败不能误判。
                        if (request?.isForMainFrame == true) onFailed()
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun openCluster(key: String) {
                        val target = cb.get().byKey[key] ?: return
                        val named = target.copy(place = renderArgs.get().second[key] ?: target.place)
                        scope.launch { cb.get().open(named) }
                    }

                    @JavascriptInterface
                    fun setName(key: String, name: String) {
                        scope.launch { cb.get().name(key, name) }
                    }
                }, "photoomap")
                loadDataWithBaseURL("https://amap.com/", html, "text/html", "utf-8", null)
            }
        },
        update = { webView -> doRender(webView, clusters, places) },
    )
}

/** 把簇渲染成高德标记（坐标已转 GCJ-02）；名字回传给 Kotlin 填充列表。 */
private fun doRender(webView: WebView, clusters: List<GeoCluster>, places: Map<String, String>) {
    val arr = JSONArray()
    var slat = 0.0
    var slon = 0.0
    clusters.forEach { c ->
        val g = CoordTransform.wgs84ToGcj02(c.lat, c.lon)
        slat += g.lat
        slon += g.lon
        val o = JSONObject().apply {
            put("key", c.key)
            put("lat", g.lat)
            put("lon", g.lon)
            put("count", c.count)
            put("name", places[c.key] ?: "")
        }
        arr.put(o)
    }
    val clat = if (clusters.isEmpty()) 35.0 else slat / clusters.size
    val clon = if (clusters.isEmpty()) 105.0 else slon / clusters.size
    webView.evaluateJavascript("render($arr, $clat, $clon)", null)
}

/** 高德 JS API v1.4.15（无需 securityJsCode）；标记点击 / 逆地理结果经 photoomap 回传。 */
private fun buildAmapHtml(key: String): String = """
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#eee}#map{width:100%;height:100%}</style>
<script src="https://webapi.amap.com/maps?v=1.4.15&key=$key&callback=onAmapReady"></script>
</head><body><div id="map"></div>
<script>
window.amapReady=false;
window.geocoded={};
window.render=function(points, centerLat, centerLon){
  if(!window.amapReady){ window.pending=[points,centerLat,centerLon]; return; }
  if(!window.map){
    window.map=new AMap.Map('map',{zoom:11,center:[centerLon,centerLat],resizeEnable:true});
  }
  if(window.markers){ window.map.remove(window.markers); }
  window.markers=[];
  points.forEach(function(p){
    var html='<div style="background:#3a7afe;color:#fff;border-radius:50%;min-width:34px;height:34px;line-height:34px;text-align:center;font-size:13px;font-weight:600;box-shadow:0 2px 6px rgba(0,0,0,.3);padding:0 6px;">'+p.count+'</div>';
    var mk=new AMap.Marker({position:[p.lon,p.lat],content:html,offset:new AMap.Pixel(-17,-17),title:p.name||(p.count+' 张')});
    mk.on('click',function(){ photoomap.openCluster(p.key); });
    window.markers.push(mk);
  });
  window.map.add(window.markers);
  if(window.markers.length>0){ window.map.setFitView(window.markers,false,[40,40,40,40]); }
  // 逐个逆地理，名字回传给列表卡片
  if(!window.geoc){ window.geoc=new AMap.Geocoder({radius:1000,extensions:'base'}); }
  points.forEach(function(p){
    if(window.geocoded[p.key]) return;
    window.geocoded[p.key]=true;
    window.geoc.getAddress([p.lon,p.lat],function(st,res){
      if(st==='complete'&&res.regeocode){ photoomap.setName(p.key,res.regeocode.formattedAddress||''); }
    });
  });
};
function onAmapReady(){ window.amapReady=true; if(window.pending){ window.render(window.pending[0],window.pending[1],window.pending[2]); } }
</script></body></html>
""".trimIndent()

/**
 * 自绘的拍摄地点示意图（离线、无需任何 key、不联网）。
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
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
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

/**
 * 一个地点的"相册"：点地图卡片标题后进来。
 *
 * 交互与相似组详情保持一致——单点看大图、长按进多选、再点增减，底部批量条可归档 / 删除；
 * 顶部可以在「按日 / 按月 / 按年」之间切分段，并切换新→旧 / 旧→新，逻辑与主界面时间线一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapClusterDetailScreen(
    vm: PhotoOViewModel,
    title: String,
    photos: List<PhotoItem>,
    onBack: () -> Unit,
    onOpenPhoto: (PhotoItem) -> Unit,
    onMovePicks: (List<Long>) -> Unit,
) {
    val picks by vm.similarPicks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var grouping by remember { mutableStateOf(settings.grouping) }
    var newestFirst by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }

    val ordered = remember(photos, newestFirst) {
        if (newestFirst) photos.sortedByDescending { it.dateTaken }
        else photos.sortedBy { it.dateTaken }
    }
    val sections = remember(ordered, grouping) { Format.buildSections(ordered, grouping) }
    val pickedBytes = remember(picks, photos) {
        photos.filter { it.id in picks }.sumOf { it.size }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        Text(
                            "${photos.size} 张 · " +
                                Format.dayRange(
                                    photos.minOfOrNull { it.dateTaken } ?: 0L,
                                    photos.maxOfOrNull { it.dateTaken } ?: 0L,
                                ),
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
                item(span = { GridItemSpan(maxLineSpan) }, key = "sort") {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TimelineGrouping.entries.forEach { g ->
                            FilterChip(
                                selected = g == grouping,
                                onClick = { grouping = g },
                                label = { Text(g.label) },
                            )
                        }
                        FilterChip(
                            selected = true,
                            onClick = { newestFirst = !newestFirst },
                            label = { Text(if (newestFirst) "新 → 旧" else "旧 → 新") },
                            leadingIcon = {
                                Icon(
                                    if (newestFirst) Icons.Rounded.ArrowDownward
                                    else Icons.Rounded.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }

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
                    onSelectAll = { vm.setSimilarPicks(photos.map { it.id }) },
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
            message = "${picks.size} 张照片将移入 PhotoO 回收站，可释放约 ${Format.bytes(pickedBytes)}。" +
                "回收站里还能恢复，确认彻底删除时才会同步给系统。",
            confirmText = "移入回收站",
            danger = true,
            onConfirm = vm::trashSimilarPicks,
            onDismiss = { confirmDelete = false },
        )
    }
}
