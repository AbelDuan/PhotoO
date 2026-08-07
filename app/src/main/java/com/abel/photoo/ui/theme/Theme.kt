package com.abel.photoo.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.abel.photoo.data.prefs.ThemeMode

/** MIUX 的圆角比 Material3 默认更大更圆。 */
val PhotoOShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Material3 调色板里没有"危险/成功"这一档，自己补一个。 */
data class PhotoOAccents(
    val danger: Color,
    val success: Color,
    val warn: Color,
    val scrim: Color,
)

val LocalAccents = staticCompositionLocalOf {
    PhotoOAccents(DangerLight, SuccessLight, WarnLight, Color(0x99000000))
}

private val LightColors = lightColorScheme(
    primary = MiBlue,
    onPrimary = Color.White,
    primaryContainer = MiBlueContainer,
    onPrimaryContainer = MiOnBlueContainer,
    secondary = MiBlueDark,
    onSecondary = Color.White,
    secondaryContainer = MiBlueContainer,
    onSecondaryContainer = MiOnBlueContainer,
    tertiary = Color(0xFF7A5AF8),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = Color(0xFFF2F2F5),
    surfaceContainerHighest = Color(0xFFEAEAEE),
    surfaceContainerLow = Color(0xFFFBFBFC),
    surfaceContainerLowest = Color.White,
    outline = LightOutline,
    outlineVariant = Color(0xFFE6E6EB),
    error = DangerLight,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = DarkBlue,
    onPrimary = Color(0xFF0B2A5E),
    primaryContainer = DarkBlueContainer,
    onPrimaryContainer = DarkOnBlueContainer,
    secondary = DarkBlue,
    onSecondary = Color(0xFF0B2A5E),
    secondaryContainer = DarkBlueContainer,
    onSecondaryContainer = DarkOnBlueContainer,
    tertiary = Color(0xFFB9A6FF),
    onTertiary = Color(0xFF2A1A66),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = Color(0xFF1F1F23),
    surfaceContainerHighest = Color(0xFF27272C),
    surfaceContainerLow = Color(0xFF131316),
    surfaceContainerLowest = Color(0xFF0A0A0C),
    outline = DarkOutline,
    outlineVariant = Color(0xFF2C2C32),
    error = DangerDark,
    onError = Color(0xFF3A0A0C),
)

@Composable
fun PhotoOTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    // 动态取色是 Android 12 才有的能力；澎湃 OS 上会跟着壁纸走。
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        dynamicColor && supportsDynamic && dark -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    val accents = if (dark) {
        PhotoOAccents(DangerDark, SuccessDark, WarnDark, Color(0xB3000000))
    } else {
        PhotoOAccents(DangerLight, SuccessLight, WarnLight, Color(0x99000000))
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalAccents provides accents) {
        MaterialTheme(
            colorScheme = colors,
            typography = PhotoOTypography,
            shapes = PhotoOShapes,
            content = content,
        )
    }
}
