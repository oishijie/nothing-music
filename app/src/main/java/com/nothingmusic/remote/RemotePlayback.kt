package com.nothingmusic.remote

import android.content.Context
import androidx.media3.datasource.DataSource
import com.mica.music.RemoteRuntime
import com.mica.music.data.remote.CompositeRemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.aliyundrive.AliyunDriveStreamRequestResolver
import com.mica.music.data.remote.alist.AListStreamRequestResolver
import com.mica.music.data.remote.navidrome.NavidromeStreamRequestResolver
import com.mica.music.data.remote.smb.SmbStreamRequestResolver
import com.mica.music.data.remote.webdav.WebDavStreamRequestResolver
import com.mica.music.media.MicaRoutingDataSourceFactory

/**
 * 远程播放接入点：把四种远程来源的解析器组装成 Media3 数据源工厂。
 * mica-remote://track/<mediaId> → 带鉴权与时效的真实直链。
 */
object RemotePlayback {

    const val URI_SCHEME = "mica-remote"

    fun resolveDataSourceFactory(context: Context): DataSource.Factory {
        RemoteRuntime.install(context)
        val ownerById: suspend (String) -> com.mica.music.data.remote.RemoteSourceOwner? = { sourceId ->
            RemoteRuntime.catalogRepository.sourceOwner(sourceId)
        }
        val httpResolver = CompositeRemoteHttpPlaybackRequestResolver(
            NavidromeStreamRequestResolver(
                sourceOwnerById = ownerById,
                credentialStore = RemoteRuntime.credentialStore,
            ),
            WebDavStreamRequestResolver(
                sourceOwnerById = ownerById,
                credentialStore = RemoteRuntime.credentialStore,
            ),
            AliyunDriveStreamRequestResolver(
                sourceOwnerById = ownerById,
                credentialStore = RemoteRuntime.credentialStore,
            ),
            AListStreamRequestResolver(
                sourceOwnerById = ownerById,
                credentialStore = RemoteRuntime.credentialStore,
            ),
        )
        val smbResolver = SmbStreamRequestResolver(
            sourceOwnerById = ownerById,
            credentialStore = RemoteRuntime.credentialStore,
        )
        return MicaRoutingDataSourceFactory(
            context = context.applicationContext,
            remoteResolver = httpResolver,
            smbResolver = smbResolver,
        )
    }
}
