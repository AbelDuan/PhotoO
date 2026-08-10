package com.abel.photoo.data.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 与 GCJ-02 之间的坐标换算。
 *
 * 相机写进 EXIF 的是 WGS-84（GPS 原始坐标），而国内所有合规地图服务
 * （腾讯 / 高德 / 百度）使用的是 GCJ-02 加偏坐标。直接把 EXIF 坐标丢给地图，
 * 点位会整体偏移几百米——在城市里足够把你标到隔壁小区去，所以必须先转换。
 *
 * 中国大陆以外不加偏，这里按国界粗略盒判断，越界直接原样返回。
 */
object CoordTransform {

    private const val A = 6378245.0            // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594 // 偏心率平方

    data class LatLng(val lat: Double, val lon: Double)

    /** WGS-84 → GCJ-02（火星坐标）。境外坐标原样返回。 */
    fun wgs84ToGcj02(lat: Double, lon: Double): LatLng {
        if (outOfChina(lat, lon)) return LatLng(lat, lon)
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return LatLng(lat + dLat, lon + dLon)
    }

    /**
     * 是否在中国大陆范围外。用一个宽松的经纬度盒判断即可：
     * 判错的代价只是"加偏或不加偏"，而边界地带本来精度就有限。
     */
    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** 两点间的近似距离（米）。用等距圆柱投影，几公里内足够准，用于聚类。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val meanLat = (lat1 + lat2) / 2.0 / 180.0 * Math.PI
        val dx = (lon2 - lon1) * 111_320.0 * cos(meanLat)
        val dy = (lat2 - lat1) * 110_540.0
        return sqrt(dx * dx + dy * dy)
    }

    /** 经纬度是否有效（排除 0,0 这种解析失败留下的脏数据）。 */
    fun isValid(lat: Double, lon: Double): Boolean =
        abs(lat) <= 90.0 && abs(lon) <= 180.0 && (abs(lat) > 1e-6 || abs(lon) > 1e-6)

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
