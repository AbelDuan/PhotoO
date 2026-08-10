package com.abel.photoo.data.prefs

import android.content.Context
import androidx.core.content.edit
import com.abel.photoo.model.GestureAction
import com.abel.photoo.model.GestureDirection
import com.abel.photoo.model.GestureSensitivity
import com.abel.photoo.model.KeepStrategy
import com.abel.photoo.model.SimilarityLevel
import com.abel.photoo.model.TimelineGrouping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式。DARK/LIGHT 是手动覆盖，默认 SYSTEM 跟随系统。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val grouping: TimelineGrouping = TimelineGrouping.DAY,
    val gridColumns: Int = 4,
    val keepStrategy: KeepStrategy = KeepStrategy.HIGHEST_RESOLUTION,
    val similarityLevel: SimilarityLevel = SimilarityLevel.BALANCED,
    /** 上滑删除时是否同时移入系统回收站。默认关，先只在 PhotoO 里隐藏。 */
    val alsoSystemTrash: Boolean = false,
    /** 启动时若有未处理照片，直接进入整理模式。 */
    val resumeReviewOnLaunch: Boolean = false,
    val showLocation: Boolean = true,
    /** 大图页一键归入的相册名列表（用户自选的常用文件夹）。 */
    val quickAlbums: List<String> = emptyList(),
    /** 相册展示顺序：按 relativePath 排序，列表里没有的落到末尾按时间排。 */
    val albumOrder: List<String> = emptyList(),
    /** 大图页四个滑动方向各自绑定的动作。 */
    val gestures: Map<GestureDirection, GestureAction> =
        GestureDirection.entries.associateWith { it.default },
    /** 手势灵敏度。 */
    val gestureSensitivity: GestureSensitivity = GestureSensitivity.NORMAL,
    /** 打开 Live Photo 时自动播放。 */
    val liveAutoPlay: Boolean = true,
    /** 高德地图 Web 端 key（可选）。留空则地图回退到离线示意图。 */
    val amapKey: String = "",
) {
    fun gesture(dir: GestureDirection): GestureAction = gestures[dir] ?: dir.default

    /** 左右是否维持"翻页"语义。是的话就让 Pager 自己处理，手感最好。 */
    val horizontalIsPaging: Boolean
        get() = gesture(GestureDirection.LEFT) == GestureAction.NEXT &&
            gesture(GestureDirection.RIGHT) == GestureAction.PREV
}

/**
 * 轻量设置存储。只有十来个标量，SharedPreferences 足够，
 * 不引入 DataStore 是为了少一个依赖、少一层协程包装。
 */
class AppPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("photoo_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun load() = Settings(
        themeMode = sp.getString(KEY_THEME, null).toEnum(ThemeMode.SYSTEM),
        dynamicColor = sp.getBoolean(KEY_DYNAMIC, true),
        grouping = sp.getString(KEY_GROUPING, null).toEnum(TimelineGrouping.DAY),
        gridColumns = sp.getInt(KEY_COLUMNS, 4).coerceIn(2, 6),
        keepStrategy = sp.getString(KEY_KEEP, null).toEnum(KeepStrategy.HIGHEST_RESOLUTION),
        similarityLevel = sp.getString(KEY_LEVEL, null).toEnum(SimilarityLevel.BALANCED),
        alsoSystemTrash = sp.getBoolean(KEY_SYSTEM_TRASH, false),
        resumeReviewOnLaunch = sp.getBoolean(KEY_RESUME, false),
        showLocation = sp.getBoolean(KEY_LOCATION, true),
        quickAlbums = sp.getString(KEY_QUICK, "")
            .orEmpty().split(QUICK_DELIM).filter { it.isNotEmpty() },
        albumOrder = sp.getString(KEY_ALBUM_ORDER, "")
            .orEmpty().split(QUICK_DELIM).filter { it.isNotEmpty() },
        gestures = GestureDirection.entries.associateWith { dir ->
            sp.getString(KEY_GESTURE_PREFIX + dir.name, null).toEnum(dir.default)
        },
        gestureSensitivity = sp.getString(KEY_SENSITIVITY, null).toEnum(GestureSensitivity.NORMAL),
        liveAutoPlay = sp.getBoolean(KEY_LIVE_AUTO, true),
        amapKey = sp.getString(KEY_AMAP_KEY, "").orEmpty(),
    )

    private fun update(block: Settings.() -> Settings) {
        val next = _settings.value.block()
        _settings.value = next
        sp.edit {
            putString(KEY_THEME, next.themeMode.name)
            putBoolean(KEY_DYNAMIC, next.dynamicColor)
            putString(KEY_GROUPING, next.grouping.name)
            putInt(KEY_COLUMNS, next.gridColumns)
            putString(KEY_KEEP, next.keepStrategy.name)
            putString(KEY_LEVEL, next.similarityLevel.name)
            putBoolean(KEY_SYSTEM_TRASH, next.alsoSystemTrash)
            putBoolean(KEY_RESUME, next.resumeReviewOnLaunch)
            putBoolean(KEY_LOCATION, next.showLocation)
            putString(KEY_QUICK, next.quickAlbums.joinToString(QUICK_DELIM))
            putString(KEY_ALBUM_ORDER, next.albumOrder.joinToString(QUICK_DELIM))
            GestureDirection.entries.forEach { dir ->
                putString(KEY_GESTURE_PREFIX + dir.name, next.gesture(dir).name)
            }
            putString(KEY_SENSITIVITY, next.gestureSensitivity.name)
            putBoolean(KEY_LIVE_AUTO, next.liveAutoPlay)
            putString(KEY_AMAP_KEY, next.amapKey)
        }
    }

    fun setThemeMode(mode: ThemeMode) = update { copy(themeMode = mode) }
    fun setDynamicColor(enabled: Boolean) = update { copy(dynamicColor = enabled) }
    fun setGrouping(g: TimelineGrouping) = update { copy(grouping = g) }
    fun setGridColumns(n: Int) = update { copy(gridColumns = n.coerceIn(2, 6)) }
    fun setKeepStrategy(s: KeepStrategy) = update { copy(keepStrategy = s) }
    fun setSimilarityLevel(l: SimilarityLevel) = update { copy(similarityLevel = l) }
    fun setAlsoSystemTrash(enabled: Boolean) = update { copy(alsoSystemTrash = enabled) }
    fun setResumeReview(enabled: Boolean) = update { copy(resumeReviewOnLaunch = enabled) }
    fun setShowLocation(enabled: Boolean) = update { copy(showLocation = enabled) }
    fun setQuickAlbums(list: List<String>) = update { copy(quickAlbums = list.distinct()) }
    fun setAlbumOrder(list: List<String>) = update { copy(albumOrder = list.distinct()) }

    fun setGesture(dir: GestureDirection, action: GestureAction) =
        update { copy(gestures = gestures + (dir to action)) }

    fun setGestureSensitivity(s: GestureSensitivity) = update { copy(gestureSensitivity = s) }
    fun setLiveAutoPlay(on: Boolean) = update { copy(liveAutoPlay = on) }
    fun setAmapKey(key: String) = update { copy(amapKey = key.trim()) }

    fun resetGestures() = update {
        copy(
            gestures = GestureDirection.entries.associateWith { it.default },
            gestureSensitivity = GestureSensitivity.NORMAL,
        )
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: fallback

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_GROUPING = "grouping"
        const val KEY_COLUMNS = "grid_columns"
        const val KEY_KEEP = "keep_strategy"
        const val KEY_LEVEL = "similarity_level"
        const val KEY_SYSTEM_TRASH = "also_system_trash"
        const val KEY_RESUME = "resume_review"
        const val KEY_LOCATION = "show_location"
        const val KEY_QUICK = "quick_albums"
        const val KEY_ALBUM_ORDER = "album_order"
        const val KEY_GESTURE_PREFIX = "gesture_"
        const val KEY_SENSITIVITY = "gesture_sensitivity"
        const val KEY_LIVE_AUTO = "live_auto_play"
        const val KEY_AMAP_KEY = "amap_key"
        const val QUICK_DELIM = "\u001f"
    }
}
