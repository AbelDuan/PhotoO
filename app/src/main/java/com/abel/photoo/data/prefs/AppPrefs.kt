package com.abel.photoo.data.prefs

import android.content.Context
import androidx.core.content.edit
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
)

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
    }
}
