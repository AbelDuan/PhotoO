package com.abel.photoo.model

import android.net.Uri

/** 一张照片在 MediaStore 中的元信息 + PhotoO 自己维护的处理状态。 */
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    /** 预览用缩略图（MediaStore 维护的 MINI_KIND 缩略图），比全图小几个数量级，网格加载才快。 */
    val thumbUri: Uri,
    val displayName: String,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val dateTaken: Long,          // 毫秒，优先 DATE_TAKEN，回落到 DATE_MODIFIED
    val dateModified: Long,       // 毫秒
    val size: Long,               // 字节
    val width: Int,
    val height: Int,
    val mimeType: String,
    val orientation: Int,
    /** 是否为视频（普通 mp4/mov 等，区别于图片与 Live Photo）。 */
    val isVideo: Boolean = false,
    /** 是否已被用户筛选/归类处理过 */
    val reviewed: Boolean = false,
    val reviewAction: ReviewAction = ReviewAction.NONE,
    val favorite: Boolean = false,
    /**
     * Live Photo 类型：0 无；1 同相册目录下存在同名 .mov/.mp4 视频文件；
     * 2 图片文件内部内嵌视频流（小米/华为/部分机型把实况视频直接写进 JPG）。
     */
    val liveType: Int = 0,
    /** 仅 type==1 使用：同名视频的内容 Uri。 */
    val liveVideoUri: Uri? = null,
    /** 仅 type==2 使用：内嵌视频在图片文件中的字节偏移（从文件头算起）。 */
    val liveOffset: Long = 0,
) {
    /** 是否为 Live Photo（任一类型都算）。 */
    val isLivePhoto: Boolean get() = liveType != 0
    val pixels: Long get() = width.toLong() * height.toLong()
}

/** 用户对一张照片做过的处理动作。 */
enum class ReviewAction {
    NONE,       // 未处理
    KEPT,       // 明确保留
    MOVED,      // 已归类到某相册
    TRASHED,    // 已丢进回收站
    SKIPPED,    // 跳过，稍后再说
}

/** 相册（对应 MediaStore 的 bucket，或 PhotoO 里新建但尚无照片的占位相册）。 */
data class AlbumItem(
    val bucketId: Long,
    val name: String,
    /** 形如 "Pictures/旅行/"，结尾带斜杠 */
    val relativePath: String,
    val count: Int,
    val coverUri: Uri?,
    val latestDate: Long,
    /** true 表示这是 PhotoO 里新建、目录尚未实际创建的空相册 */
    val pendingLocal: Boolean = false,
) {
    val isSystemCameraAlbum: Boolean
        get() = relativePath.startsWith("DCIM/Camera", ignoreCase = true)
}

/** 回收站条目。照片仍在系统里（或已进入系统回收站），但在 PhotoO 中被隐藏。 */
data class TrashItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val bucketName: String,
    val size: Long,
    val dateTaken: Long,
    val deletedAt: Long,
    /** 删除时是否同时移入了系统回收站 */
    val systemTrashed: Boolean,
)

/** 从 EXIF 解析出的可展示信息。 */
data class ExifInfo(
    val dateTimeOriginal: Long? = null,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val aperture: String? = null,        // f/1.8
    val shutter: String? = null,         // 1/125 s
    val iso: String? = null,             // ISO 200
    val focalLength: String? = null,     // 24 mm
    val focalLength35: String? = null,   // 等效 35mm
    val whiteBalance: String? = null,
    val flash: String? = null,
    val software: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val place: String? = null,           // 反地理编码结果
) {
    val hasCamera: Boolean get() = !make.isNullOrBlank() || !model.isNullOrBlank()
    val hasShootingParams: Boolean
        get() = aperture != null || shutter != null || iso != null || focalLength != null
    val hasLocation: Boolean get() = latitude != null && longitude != null

    val cameraTitle: String
        get() {
            val mk = make?.trim().orEmpty()
            val md = model?.trim().orEmpty()
            return when {
                md.isEmpty() -> mk
                mk.isEmpty() -> md
                md.startsWith(mk, ignoreCase = true) -> md
                else -> "$mk $md"
            }
        }
}

/** 一组互相相似的照片。 */
data class SimilarGroup(
    val key: String,
    val items: List<PhotoItem>,
    /** 组内两两之间的最大汉明距离，越小越像 */
    val maxDistance: Int,
    /** 建议保留的那张（按当前策略计算） */
    val suggestedKeepId: Long,
    val resolved: Boolean = false,
) {
    val size: Int get() = items.size
    val totalBytes: Long get() = items.sumOf { it.size }
    val reclaimableBytes: Long get() = items.filter { it.id != suggestedKeepId }.sumOf { it.size }
    val timeSpanMillis: Long
        get() = if (items.size < 2) 0L
        else (items.maxOf { it.dateTaken } - items.minOf { it.dateTaken })
}

/** 相似组内的自动保留策略。 */
enum class KeepStrategy(val label: String, val description: String) {
    HIGHEST_RESOLUTION("最高分辨率", "保留像素最多的一张"),
    LARGEST_FILE("最大文件", "保留体积最大的一张，通常画质最好"),
    NEWEST("最新拍摄", "保留时间最晚的一张"),
    OLDEST("最早拍摄", "保留原始的第一张"),
    MANUAL("手动选择", "不自动决定，逐组自己挑"),
}

/** 相似度松紧档位（对应汉明距离阈值）。 */
enum class SimilarityLevel(val label: String, val threshold: Int) {
    STRICT("严格", 4),
    BALANCED("均衡", 8),
    LOOSE("宽松", 12),
}

/** 相似照片扫描进度。 */
sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val done: Int, val total: Int) : ScanState
    data class Done(val groups: Int, val photos: Int) : ScanState
    data class Failed(val message: String) : ScanState
}

/** 图库整体统计，用于首页卡片与"继续整理"入口。 */
data class LibraryStats(
    val total: Int = 0,
    val reviewed: Int = 0,
    val trashed: Int = 0,
    val albums: Int = 0,
    /** 视频数量（含普通视频与 Live Photo 的内嵌视频）。用于确认设备是否授予了视频读取权限。 */
    val videoCount: Int = 0,
) {
    val pending: Int get() = (total - reviewed).coerceAtLeast(0)
    val progress: Float get() = if (total == 0) 1f else reviewed.toFloat() / total.toFloat()
}

/** 需要系统确认的媒体操作的返回结果。 */
sealed interface OpResult {
    data class Success(val affected: Int) : OpResult
    data object Cancelled : OpResult
    data class Failure(val message: String) : OpResult
}

// ------------------------------------------------------------------ 手势

/** 大图页一个滑动方向可以绑定的动作。 */
enum class GestureAction(val label: String) {
    NONE("不响应"),
    CLOSE("退出查看"),
    TRASH("移入回收站"),
    FAVORITE("收藏 / 取消"),
    MOVE_ALBUM("归入相册"),
    INFO("照片信息"),
    KEEP("标记已看"),
    NEXT("下一张"),
    PREV("上一张"),
    UNDO("撤销上一步"),
}

/** 四个方向。用枚举而不是四个字段，设置页可以直接遍历渲染。 */
enum class GestureDirection(val label: String, val default: GestureAction) {
    UP("上滑", GestureAction.TRASH),
    DOWN("下滑", GestureAction.CLOSE),
    LEFT("左滑", GestureAction.NEXT),
    RIGHT("右滑", GestureAction.PREV),
}

/**
 * 手势灵敏度。数值是"触发所需位移占屏幕高度的比例"的缩放系数：
 * 系数越大越灵敏（阈值越小），轻轻一划就触发。
 */
enum class GestureSensitivity(val label: String, val factor: Float) {
    VERY_LOW("很迟钝", 0.55f),
    LOW("偏迟钝", 0.75f),
    NORMAL("标准", 1.0f),
    HIGH("偏灵敏", 1.4f),
    VERY_HIGH("很灵敏", 2.0f),
}

// ------------------------------------------------------------------ 地理

/** 一张照片的拍摄坐标（WGS-84，来自 EXIF）。 */
data class GeoPoint(
    val id: Long,
    val lat: Double,
    val lon: Double,
)

/** 地图上的一个聚合点：地理位置相近的一批照片。 */
data class GeoCluster(
    val key: String,
    /** 簇中心（WGS-84） */
    val lat: Double,
    val lon: Double,
    val photos: List<PhotoItem>,
    /** 反地理编码得到的地名，可能还没解析出来 */
    val place: String? = null,
) {
    val count: Int get() = photos.size
    val cover: PhotoItem? get() = photos.maxByOrNull { it.dateTaken }
    val latestDate: Long get() = photos.maxOfOrNull { it.dateTaken } ?: 0L
    val earliestDate: Long get() = photos.minOfOrNull { it.dateTaken } ?: 0L
}

/** GPS 扫描进度。 */
sealed interface GeoScanState {
    data object Idle : GeoScanState
    data class Running(val done: Int, val total: Int) : GeoScanState
    data class Done(val located: Int) : GeoScanState
}

/** 时间线的分组粒度。 */
enum class TimelineGrouping(val label: String) {
    DAY("按日"),
    MONTH("按月"),
    YEAR("按年"),
}

/** 时间线里的一段（一个标题 + 若干照片）。 */
data class TimelineSection(
    val key: String,
    val title: String,
    val subtitle: String,
    val photos: List<PhotoItem>,
)
