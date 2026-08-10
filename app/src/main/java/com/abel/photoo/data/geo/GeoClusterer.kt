package com.abel.photoo.data.geo

import com.abel.photoo.model.GeoCluster
import com.abel.photoo.model.GeoPoint
import com.abel.photoo.model.PhotoItem
import kotlin.math.cos
import kotlin.math.floor

/**
 * 把带坐标的照片聚成"地点"。
 *
 * 用网格法而不是层次聚类：图库动辄上万张，两两算距离是 O(n²)，
 * 在手机上会明显卡顿；网格法一遍扫描就能分完，量级 O(n)，
 * 对"把同一个景点的照片归到一堆"这个需求来说精度完全够用。
 */
object GeoClusterer {

    /** 聚合粒度。半径越大，地图上的点越少、越"成片"。 */
    enum class Level(val label: String, val radiusMeters: Double) {
        STREET("街区", 300.0),
        DISTRICT("片区", 2_000.0),
        CITY("城市", 25_000.0),
    }

    fun cluster(
        points: List<GeoPoint>,
        photosById: Map<Long, PhotoItem>,
        level: Level,
    ): List<GeoCluster> {
        if (points.isEmpty()) return emptyList()

        val radius = level.radiusMeters
        val cellLat = radius / 110_540.0
        val buckets = HashMap<String, MutableList<GeoPoint>>()

        points.forEach { p ->
            // 经度方向的格子宽度随纬度收缩，高纬度地区才不会被拉成长条。
            val cosLat = cos(p.lat / 180.0 * Math.PI).coerceAtLeast(0.01)
            val cellLon = radius / (111_320.0 * cosLat)
            val gy = floor(p.lat / cellLat).toLong()
            val gx = floor(p.lon / cellLon).toLong()
            buckets.getOrPut("$gy:$gx") { mutableListOf() }.add(p)
        }

        return buckets.mapNotNull { (key, group) ->
            val photos = group.mapNotNull { photosById[it.id] }
            if (photos.isEmpty()) return@mapNotNull null
            GeoCluster(
                key = key,
                lat = group.sumOf { it.lat } / group.size,
                lon = group.sumOf { it.lon } / group.size,
                photos = photos.sortedByDescending { it.dateTaken },
            )
        }.sortedByDescending { it.latestDate }
    }
}
