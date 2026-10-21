package com.mica.music.data.remote.aliyundrive

import android.content.Context
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore

internal data class AliyunDriveEmbeddedArtworkRequest(
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val refreshToken: String,
    val driveId: String,
    val fileId: String,
    val sizeBytes: Long,
) {
    override fun toString(): String =
        "AliyunDriveEmbeddedArtworkRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, " +
            "sizeBytes=$sizeBytes)"
}

internal class AliyunDriveEmbeddedArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun resolve(ref: RemoteArtworkRef): AliyunDriveEmbeddedArtworkRequest? {
        val target = RemoteEmbeddedArtworkIdCodec.decode(ref.opaqueArtworkId) ?: return null
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.ALIYUNDRIVE || !source.enabled) return null
        val credential = credentialStore.resolve(source.credentialRef) ?: return null
        val refreshToken = (credential.material as? RemoteCredentialMaterial.BearerToken)?.token ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val (fileId, driveId) = decodeAliyunDriveOpaqueTrackId(target.resourceId) ?: return null
        return AliyunDriveEmbeddedArtworkRequest(
            sourceInstanceId = source.id,
            sourceConfigRevision = operation.source.configRevision,
            credentialRevision = credential.revision,
            refreshToken = refreshToken,
            driveId = driveId,
            fileId = fileId,
            sizeBytes = target.sizeBytes,
        )
    }
}

internal class AliyunDriveEmbeddedArtworkLoader(
    context: Context,
    private val byteLoader: AliyunDriveEmbeddedArtworkByteLoader = AliyunDriveEmbeddedArtworkByteLoader(context),
    private val client: AliyunDriveClient = AliyunDriveClient(),
) {
    suspend fun load(request: AliyunDriveEmbeddedArtworkRequest): ByteArray {
        // 直链有时效，只能用一次；解析失败不影响下一首曲目的解析。
        val url = client.downloadUrl(request.refreshToken, request.driveId, request.fileId)
        return byteLoader.load(url, request.sizeBytes)
    }
}
