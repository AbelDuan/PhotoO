package com.abel.photoo.ui.util

import com.abel.photoo.model.PhotoItem
import com.abel.photoo.model.TimelineGrouping
import com.abel.photoo.model.TimelineSection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 展示层的格式化工具。
 *
 * 全部是纯函数，方便在 remember 里直接调用，也方便单测。
 */
object Format {

    private val dayFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
    private val dayShortFmt = SimpleDateFormat("M月d日", Locale.CHINA)
    private val monthFmt = SimpleDateFormat("yyyy年M月", Locale.CHINA)
    private val yearFmt = SimpleDateFormat("yyyy年", Locale.CHINA)
    private val weekFmt = SimpleDateFormat("EEEE", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0 || v >= 100) "${v.toInt()} ${units[i]}"
        else String.format(Locale.CHINA, "%.1f %s", v, units[i])
    }

    fun pixels(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "未知尺寸"
        val mp = w.toLong() * h / 1_000_000.0
        return if (mp >= 1) String.format(Locale.CHINA, "%d × %d · %.1fMP", w, h, mp)
        else "$w × $h"
    }

    fun fullTime(millis: Long): String = if (millis <= 0) "未知时间" else fullFmt.format(millis)

    fun clockTime(millis: Long): String = if (millis <= 0) "" else timeFmt.format(millis)

    fun dayTitle(millis: Long): String = dayFmt.format(millis)

    /** 地图页兜底展示：地名还没解析出来时先给经纬度。 */
    fun latLon(lat: Double, lon: Double): String =
        String.format(Locale.CHINA, "%.5f, %.5f", lat, lon)

    /** "2024年3月12日" 或 "2024年3月12日 – 2024年4月2日"。 */
    fun dayRange(from: Long, to: Long): String {
        if (from <= 0 && to <= 0) return "未知时间"
        val a = dayFmt.format(if (from > 0) from else to)
        val b = dayFmt.format(if (to > 0) to else from)
        return if (a == b) a else "$a – $b"
    }

    /** "今天 / 昨天 / 3月12日 星期二" 这种人话标题。 */
    fun friendlyDay(millis: Long): String {
        val now = Calendar.getInstance()
        val that = Calendar.getInstance().apply { timeInMillis = millis }
        val sameYear = now.get(Calendar.YEAR) == that.get(Calendar.YEAR)
        val diffDays = daysBetween(that, now)
        return when {
            diffDays == 0 -> "今天"
            diffDays == 1 -> "昨天"
            diffDays in 2..6 -> "${dayShortFmt.format(millis)} ${weekFmt.format(millis)}"
            sameYear -> dayShortFmt.format(millis)
            else -> dayFmt.format(millis)
        }
    }

    fun relativeSpan(millis: Long): String = when {
        millis < TimeUnit.SECONDS.toMillis(1) -> "几乎同时"
        millis < TimeUnit.MINUTES.toMillis(1) -> "${millis / 1000} 秒内"
        millis < TimeUnit.HOURS.toMillis(1) -> "${millis / 60_000} 分钟内"
        millis < TimeUnit.DAYS.toMillis(1) -> "${millis / 3_600_000} 小时内"
        else -> "${millis / 86_400_000} 天内"
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        val x = a.clone() as Calendar
        val y = b.clone() as Calendar
        listOf(x, y).forEach {
            it.set(Calendar.HOUR_OF_DAY, 0)
            it.set(Calendar.MINUTE, 0)
            it.set(Calendar.SECOND, 0)
            it.set(Calendar.MILLISECOND, 0)
        }
        return ((y.timeInMillis - x.timeInMillis) / 86_400_000L).toInt()
    }

    /** 把照片列表切成时间线分段。输入必须已按时间倒序排好。 */
    fun buildSections(
        photos: List<PhotoItem>,
        grouping: TimelineGrouping,
    ): List<TimelineSection> {
        if (photos.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        val buckets = LinkedHashMap<String, MutableList<PhotoItem>>()
        for (p in photos) {
            cal.timeInMillis = p.dateTaken
            val key = when (grouping) {
                TimelineGrouping.DAY ->
                    "%04d-%02d-%02d".format(
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH) + 1,
                        cal.get(Calendar.DAY_OF_MONTH),
                    )

                TimelineGrouping.MONTH ->
                    "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

                TimelineGrouping.YEAR -> "%04d".format(cal.get(Calendar.YEAR))
            }
            buckets.getOrPut(key) { ArrayList() }.add(p)
        }
        return buckets.map { (key, items) ->
            val head = items.first().dateTaken
            val title = when (grouping) {
                TimelineGrouping.DAY -> friendlyDay(head)
                TimelineGrouping.MONTH -> monthFmt.format(head)
                TimelineGrouping.YEAR -> yearFmt.format(head)
            }
            val subtitle = buildString {
                append("${items.size} 张")
                val places = items.mapNotNull { it.bucketName.takeIf(String::isNotBlank) }
                    .distinct()
                if (places.size == 1) append(" · ${places.first()}")
                else if (places.size > 1) append(" · ${places.size} 个位置")
            }
            TimelineSection(key = key, title = title, subtitle = subtitle, photos = items)
        }
    }
}
