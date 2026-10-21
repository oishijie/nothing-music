package com.nothingmusic.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nothingmusic.R

/**
 * 主题模式：跟随系统 / 黑暗 / 明亮。
 * 黑暗走 Nothing 纯黑配色，明亮走莫奈动态取色（Android 12+）或浅色兜底。
 */
object AppThemeMode {

    private const val PREFS = "nothing_music_prefs"
    private const val KEY_THEME_MODE = "app_theme_mode"

    const val SYSTEM = "system"
    const val DARK = "dark"
    const val LIGHT = "light"

    val options: List<Pair<String, Int>> = listOf(
        SYSTEM to R.string.theme_system,
        DARK to R.string.theme_dark,
        LIGHT to R.string.theme_light,
    )

    fun current(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, SYSTEM) ?: SYSTEM

    fun labelRes(context: Context): Int =
        options.firstOrNull { it.first == current(context) }?.second ?: R.string.theme_system

    fun apply(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME_MODE, value).apply()
        AppThemeModeState.mode.value = value
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 进程级主题模式状态：设置页写入后立即驱动全界面重组，无需重启 Activity。
 */
object AppThemeModeState {
    val mode = mutableStateOf(AppThemeMode.SYSTEM)

    fun init(context: Context) {
        mode.value = AppThemeMode.current(context)
    }
}
