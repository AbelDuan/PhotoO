package com.abel.photoo.data.exif

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.abel.photoo.model.ExifInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 读取一张照片的 EXIF。
 *
 * 两个容易踩的坑，这里都处理了：
 *  - GPS 信息默认会被系统抹掉，必须用 [MediaStore.setRequireOriginal] 拿原始流，
 *    并且要声明 ACCESS_MEDIA_LOCATION 权限。
 *  - 反地理编码在 Android 13+ 换成了异步回调版，老的同步方法会在主线程抛异常。
 */
class ExifReader(private val context: Context) {

    private val cache = ConcurrentHashMap<Long, ExifInfo>()
    private val placeCache = ConcurrentHashMap<String, String>()

    suspend fun read(id: Long, uri: Uri, resolvePlace: Boolean = true): ExifInfo =
        withContext(Dispatchers.IO) {
            cache[id]?.let { cached ->
                if (!resolvePlace || cached.place != null || !cached.hasLocation) return@withContext cached
            }

            val base = cache[id] ?: parse(uri).also { cache[id] = it }
            if (!resolvePlace || !base.hasLocation || base.place != null) return@withContext base

            val place = lookupPlace(base.latitude!!, base.longitude!!)
            val merged = base.copy(place = place)
            cache[id] = merged
            merged
        }

    fun peek(id: Long): ExifInfo? = cache[id]

    fun clear() {
        cache.clear()
    }

    // ------------------------------------------------------------------ 解析

    private fun parse(uri: Uri): ExifInfo {
        val exif = openExif(uri) ?: return ExifInfo()

        val latLong = FloatArray(2)
        val hasLatLong = runCatching { exif.getLatLong(latLong) }.getOrDefault(false)

        return ExifInfo(
            dateTimeOriginal = parseExifDate(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ),
            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.ifBlank { null },
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.ifBlank { null },
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifBlank { null },
            aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                .takeIf { it > 0.0 }
                ?.let { "f/${trimNumber(it)}" },
            shutter = formatShutter(exif),
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                .takeIf { it > 0 }
                ?.let { "ISO $it" },
            focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                .takeIf { it > 0.0 }
                ?.let { "${trimNumber(it)} mm" },
            focalLength35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                .takeIf { it > 0 }
                ?.let { "等效 $it mm" },
            whiteBalance = when (exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)) {
                ExifInterface.WHITE_BALANCE_AUTO.toInt() -> "自动白平衡"
                ExifInterface.WHITE_BALANCE_MANUAL.toInt() -> "手动白平衡"
                else -> null
            },
            flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 }
                ?.let { if (it and 0x1 == 1) "闪光灯已开" else "未使用闪光灯" },
            software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()?.ifBlank { null },
            latitude = if (hasLatLong) latLong[0].toDouble() else null,
            longitude = if (hasLatLong) latLong[1].toDouble() else null,
            altitude = exif.getAltitude(Double.NaN).takeIf { !it.isNaN() },
        )
    }

    private fun openExif(uri: Uri): ExifInterface? {
        // 优先请求"原始"文件流，否则 GPS 会被系统脱敏掉。
        val original = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        runCatching {
            context.contentResolver.openInputStream(original)?.use { return ExifInterface(it) }
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { return ExifInterface(it) }
        }
        return null
    }

    private fun formatShutter(exif: ExifInterface): String? {
        val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        if (exposure <= 0.0) return null
        return if (exposure >= 1.0) {
            "${trimNumber(exposure)} s"
        } else {
            "1/${(1.0 / exposure).roundToInt()} s"
        }
    }

    private fun parseExifDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        }.getOrNull()
    }

    private fun trimNumber(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (abs(rounded - rounded.toInt()) < 0.05) rounded.toInt().toString()
        else rounded.toString()
    }

    // -------------------------------------------------------------- 反地理编码

    private suspend fun lookupPlace(lat: Double, lon: Double): String? {
        val key = "%.4f,%.4f".format(Locale.US, lat, lon)
        placeCache[key]?.let { return it }
        if (!Geocoder.isPresent()) return null

        val geocoder = Geocoder(context, Locale.getDefault())
        val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    geocoder.getFromLocation(lat, lon, 1) { list ->
                        if (cont.isActive) cont.resume(list.firstOrNull())
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
        }

        val text = address?.let { formatAddress(it) } ?: return null
        placeCache[key] = text
        return text
    }

    private fun formatAddress(a: Address): String? {
        val parts = listOfNotNull(
            a.adminArea,
            a.locality?.takeIf { it != a.adminArea },
            a.subLocality,
            a.thoroughfare,
        ).distinct()
        val joined = parts.joinToString("")
        return joined.ifBlank { a.featureName ?: a.getAddressLine(0) }
    }
}
