package com.nothingmusic

import android.app.Application
import com.mica.music.RemoteRuntime
import com.mica.music.util.DiagnosticLog
import com.nothingmusic.util.AppThemeModeState
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NothingMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 远程曲库运行时与诊断日志需要 Application 上下文，必须在任何 provider/worker 前安装。
        RemoteRuntime.install(this)
        DiagnosticLog.install(this)
        AppThemeModeState.init(this)
    }
}
