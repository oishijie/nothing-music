package com.mica.music.data.remote.alist

import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteHttpRangePolicy
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.util.DiagnosticLog

/** 一次 JIT 解析拿全的来源上下文：快照 + 共享 client，各解析器据此构造请求。 */
internal data class AListSourceContext(
    val owner: RemoteSourceOwner,
    val token: com.mica.music.data.remote.RemoteOperationToken,
    val sourceId: String,
    val configRevision: Long,
    val credentialRevision: Long,
    val client: AListClient,
) {
    fun isCurrent(): Boolean = owner.isCurrent(token)
}

/**
 * 快照式解析：来源校验 + 凭证解析 + 共享 client，任一环节失败返回 null 由调用方跳过。
 * 复用前调用方须再验一次 [AListSourceContext.owner] 的 isCurrent，保证不跨代发布。
 */
internal suspend fun resolveAListSourceContext(
    sourceInstanceId: String,
    sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    credentialStore: SecureRemoteCredentialStore,
): AListSourceContext? {
    val owner = sourceOwnerById(sourceInstanceId) ?: return null
    val operation = owner.beginOperationSnapshot()
    val source = operation.source.instance
    if (source.type != RemoteSourceType.ALIST || !source.enabled) return null
    val credential = credentialStore.resolve(source.credentialRef) ?: return null
    if (!owner.isCurrent(operation.token)) return null
    val client = AListClients.forSource(source, credential) ?: return null
    return AListSourceContext(
        owner = owner,
        token = operation.token,
        sourceId = source.id,
        configRevision = operation.source.configRevision,
        credentialRevision = credential.revision,
        client = client,
    )
}

/**
 * AList 播放地址解析：mediaId -> 服务器路径 -> fs/get -> raw_url。
 *
 * 同源 raw_url（AList 自身代理）附带 Authorization/Sign 头；网盘直链自带签名，不加头。
 */
internal class AListStreamRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) : RemoteHttpPlaybackRequestResolver {
    override suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest? {
        val trackRef = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val context = resolveAListSourceContext(trackRef.sourceInstanceId, sourceOwnerById, credentialStore)
            ?: return null
        val detail = try {
            context.client.fileInfo(trackRef.opaqueTrackId)
        } catch (failure: AListException) {
            if (failure.kind == AListFailureKind.AUTH) {
                DiagnosticLog.event("AList", "直链解析失败（AUTH）: ${failure.message}")
            }
            return null
        }
        if (detail.isDir) return null
        val url = context.client.resolveRawUrl(detail) ?: return null
        if (!context.isCurrent()) return null
        return RemoteHttpPlaybackRequest(
            url = url.toString(),
            sourceInstanceId = context.sourceId,
            sourceConfigRevision = context.configRevision,
            credentialRevision = context.credentialRevision,
            requestHeaders = context.client.playbackHeaders(detail),
            rangePolicy = RemoteHttpRangePolicy.MEDIA3_DEFAULT,
        )
    }
}
