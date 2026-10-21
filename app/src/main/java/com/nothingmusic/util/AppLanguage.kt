package com.nothingmusic.util

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用语言：跟随系统 / 简体中文 / English。
 * Android 13+ 走系统「单应用语言」机制（LocaleManager），低版本由宿主按需忽略。
 */
object AppLanguage {
    private const val PREFS = "nothing_music_prefs"
    private const val KEY = "app_language"

    const val SYSTEM = "system"
    const val CHINESE = "zh-CN"
    const val ENGLISH = "en"

    val options: List<Pair<String, String>> = listOf(
        SYSTEM to "跟随系统",
        CHINESE to "简体中文",
        ENGLISH to "English",
    )

    fun current(context: Context): String =
        prefs(context).getString(KEY, SYSTEM) ?: SYSTEM

    fun label(context: Context): String =
        options.firstOrNull { it.first == current(context) }?.second ?: "跟随系统"

    fun apply(context: Context, value: String) {
        prefs(context).edit().putString(KEY, value).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val locales = when (value) {
                CHINESE -> LocaleList(Locale.SIMPLIFIED_CHINESE)
                ENGLISH -> LocaleList(Locale.ENGLISH)
                else -> LocaleList.getEmptyLocaleList()
            }
            manager.applicationLocales = locales
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
