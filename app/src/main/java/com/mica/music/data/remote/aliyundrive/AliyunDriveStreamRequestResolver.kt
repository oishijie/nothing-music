package com.mica.music.data.remote.aliyundrive

import com.mica.music.data.remote.MutableSecureRemoteCredentialStore
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteHttpRangePolicy
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 阿里云盘播放地址解析：mediaId -> (file_id, drive_id) -> 签名直链。
 *
 * 直链本身自鉴权（签名 URL），因此返回的 RemoteHttpPlaybackRequest 无需认证物料，
 * 也无需自定义请求头；URL 时效由解析时机（JIT）保证。
 */
internal class AliyunDriveStreamRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: MutableSecureRemoteCredentialStore,
) : RemoteHttpPlaybackRequestResolver {
    private val clients = ConcurrentHashMap<String, AliyunDriveClient>()
    private val clientCredentialRevisions = ConcurrentHashMap<String, Long>()

    override suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest? {
        val trackRef = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val owner = sourceOwnerById(trackRef.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.ALIYUNDRIVE || !source.enabled) return null
        val credential = credentialStore.resolve(source.credentialRef) ?: return null
        val refreshToken = (credential.material as? RemoteCredentialMaterial.BearerToken)?.token
            ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val (fileId, driveId) = decodeAliyunDriveOpaqueTrackId(trackRef.opaqueTrackId) ?: return null

        val cachedClient = clients[trackRef.sourceInstanceId]
            ?.takeIf { clientCredentialRevisions[trackRef.sourceInstanceId] == credential.revision }
        val client = cachedClient ?: newClientFor(trackRef.sourceInstanceId, source.credentialRef).also {
            clients[trackRef.sourceInstanceId] = it
            clientCredentialRevisions[trackRef.sourceInstanceId] = credential.revision
        }

        val url = try {
            client.downloadUrl(refreshToken, driveId, fileId)
        } catch (e: AliyunDriveException) {
            // AUTH 单独记日志：UI 需要区分“登录失效”与“普通播放失败”。
            if (e.kind == AliyunDriveFailureKind.AUTH) {
                DiagnosticLog.event("AliyunDrive", "直链解析失败（AUTH）: ${e.message}")
            }
            return null
        }
        // 刷新 token 会推进 credentialRevision，上面取的快照已过期，需要按新 revision 重新取。
        val current = credentialStore.resolve(source.credentialRef) ?: return null
        return RemoteHttpPlaybackRequest(
            url = url,
            sourceInstanceId = source.id,
            sourceConfigRevision = operation.source.configRevision,
            credentialRevision = current.revision,
            rangePolicy = RemoteHttpRangePolicy.MEDIA3_DEFAULT,
        )
    }

    /**
     * 播放路径同样会触发 token 刷新；新 refresh_token 必须回写持久化，
     * 否则进程重启后存的是已被作废的旧值。
     */
    private fun newClientFor(sourceInstanceId: String, credentialRef: String): AliyunDriveClient =
        AliyunDriveClient(
            onRefreshTokenRotated = { newRefreshToken ->
                val written = credentialStore.put(
                    credentialRef,
                    RemoteCredentialMaterial.BearerToken(
                        username = "aliyundrive",
                        token = newRefreshToken,
                    ),
                )
                // 轮换推进了 revision，缓存的 client 快照对应旧值，直接指向新 revision。
                clientCredentialRevisions[sourceInstanceId] = written.revision
            },
        )
}
