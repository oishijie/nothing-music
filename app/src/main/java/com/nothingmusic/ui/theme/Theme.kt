package com.nothingmusic.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nothingmusic.util.AppThemeMode
import com.nothingmusic.util.AppThemeModeState

private val NothingDarkScheme = darkColorScheme(
    primary = NothingRed,
    onPrimary = NothingBlack,
    primaryContainer = NothingRed,
    onPrimaryContainer = NothingBlack,
    secondary = NothingWhite,
    onSecondary = NothingBlack,
    secondaryContainer = NothingSurfaceElevated,
    onSecondaryContainer = NothingWhite,
    tertiary = NothingTextTertiary,
    onTertiary = NothingBlack,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingSurface,
    onSurface = NothingOnSurface,
    surfaceVariant = NothingSurfaceElevated,
    onSurfaceVariant = NothingTextSecondary,
    outline = NothingOutline,
    outlineVariant = NothingOutlineVariant,
    error = NothingRed,
    onError = NothingBlack,
)

// 白天兜底：Nothing 风格的浅色主题，供 Android 8-11 使用（这些版本没有动态取色）。
private val NothingLightScheme = lightColorScheme(
    primary = NothingRed,
    onPrimary = NothingWhite,
    primaryContainer = NothingRed,
    onPrimaryContainer = NothingWhite,
    secondary = NothingLightTextPrimary,
    onSecondary = NothingWhite,
    secondaryContainer = NothingLightSurfaceVariant,
    onSecondaryContainer = NothingLightTextPrimary,
    tertiary = NothingLightTextTertiary,
    onTertiary = NothingWhite,
    background = NothingLightBackground,
    onBackground = NothingLightTextPrimary,
    surface = NothingWhite,
    onSurface = NothingLightTextPrimary,
    surfaceVariant = NothingLightSurfaceVariant,
    onSurfaceVariant = NothingLightTextSecondary,
    outline = NothingLightOutline,
    outlineVariant = NothingLightOutlineVariant,
    error = NothingRed,
    onError = NothingWhite,
)

@Composable
fun NothingMusicTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    // 主题模式可手动覆盖；AppThemeMode 为进程级状态，设置页修改后整个界面重组。
    var themeMode by AppThemeModeState.mode
    val darkTheme = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        else -> systemDark
    }

    val colorScheme: ColorScheme = when {
        darkTheme -> NothingDarkScheme
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        else -> NothingLightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NothingTypography,
        shapes = NothingShapes,
        content = content,
    )
}
