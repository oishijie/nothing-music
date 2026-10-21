package com.mica.music

import android.content.Context
import com.mica.music.data.remote.AndroidJavaRemoteTrackMetadataProbe
import com.mica.music.data.remote.AndroidKeystoreRemoteCredentialStore
import com.mica.music.data.remote.RemoteAutoSyncScheduler
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteLyricsRepository
import com.mica.music.data.remote.RemoteSourceManager
import com.mica.music.data.remote.RemoteTrackMetadataProbe

/**
 * 远程曲库运行时：把 Mica 原本挂在 Application 上的远程依赖集中到一处，
 * 供 ContentProvider / Worker / UI 共用。进程内单例，不持有 Activity。
 */
object RemoteRuntime {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("RemoteRuntime.install() was not called")

    internal val catalogRepository: RemoteCatalogRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteCatalogRepository(requireContext())
    }

    internal val credentialStore: AndroidKeystoreRemoteCredentialStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidKeystoreRemoteCredentialStore(requireContext())
    }

    internal val sourceManager: RemoteSourceManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteSourceManager(
            catalogRepository,
            credentialStore,
            automaticSyncRequest = { RemoteAutoSyncScheduler.requestCatchUp(requireContext()) },
        )
    }

    /**
     * 标签/内嵌封面探测只在播放时水合使用（对齐棉花的按需策略），
     * 同步永远不下载文件载荷。
     */
    internal val metadataProbe: RemoteTrackMetadataProbe by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidJavaRemoteTrackMetadataProbe(requireContext())
    }

    internal val lyricsRepository: RemoteLyricsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteLyricsRepository(catalogRepository, credentialStore, context = requireContext())
    }
}
