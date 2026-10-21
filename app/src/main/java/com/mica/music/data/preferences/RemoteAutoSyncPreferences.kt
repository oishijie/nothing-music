package com.mica.music.data.preferences

import android.content.Context

/** 远程来源自动同步开关（迁移后自带存储，不依赖宿主 App 的设置实现）。 */
internal object RemoteAutoSyncPreferences {
    private const val PREFS = "mica_remote_prefs"
    private const val KEY_ENABLED = "remote_auto_sync_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
