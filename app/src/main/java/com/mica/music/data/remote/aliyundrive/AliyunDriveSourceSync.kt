package com.mica.music.data.remote.aliyundrive

import com.mica.music.data.remote.MutableSecureRemoteCredentialStore
import com.mica.music.data.remote.ReadAheadSeekableByteSource
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.REMOTE_METADATA_IO_CONCURRENCY
import com.mica.music.data.remote.REMOTE_METADATA_PROBE_REVISION
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.RemoteSyncProgress
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.ArrayDeque
import java.util.LinkedHashSet

internal data class AliyunDriveSyncResult(
    val sourceInstanceId: String,
    val configRevision: Long,
    val trackCount: Int,
)

/**
 * 阿里云盘整源曲库刷新，与 WebDAV 同步引擎同构：
 * 单一 operation/credential 快照下扫描目录树，经由 RemoteCatalogRepository 原子发布。
 *
 * 曲目 opaque id 形如 `<file_id>@<drive_id>`（不含 `.`，满足 RemoteMediaIdCodec 约束），
 * 解析直链时无需二次查询 drive_id。
 */
internal class AliyunDriveSourceSync(
    private val catalogRepository: RemoteCatalogRepository,
    private val credentialStore: MutableSecureRemoteCredentialStore,
    private val metadataProbe: RemoteTrackMetadataProbe? = null,
    private val httpClient: okhttp3.OkHttpClient = AliyunDriveSeekableByteSource.openClient(),
) {
    private data class Session(
        val sourceInstanceId: String,
        val operation: com.mica.music.data.remote.RemoteOperationSnapshot,
        val refreshToken: String,
        val client: AliyunDriveClient,
    )

    suspend fun testConnection(sourceInstanceId: String) {
        val session = beginSession(sourceInstanceId)
        session.client.defaultDriveId(session.refreshToken)
    }

    suspend fun sync(
        sourceInstanceId: String,
        limit: Int = Int.MAX_VALUE,
    ): AliyunDriveSyncResult {
        require(limit >= 0) { "limit must not be negative" }
        val session = beginSession(sourceInstanceId)
        DiagnosticLog.important("AliyunDriveSync", "start source=$sourceInstanceId apiBase=openapi.alipan.com")
        val reusable = catalogRepository
            .reusableCatalogIfCurrent(session.operation.token)
            .orEmpty()
        val driveId = session.client.defaultDriveId(session.refreshToken)
        DiagnosticLog.important("AliyunDriveSync", "driveId resolved=${driveId.take(6)}… reusable=${reusable.size}")

        val tracks = ArrayList<RemoteTrackSummary>()
        val pending = ArrayDeque<String>().also { it.addLast(ROOT_FOLDER_ID) }
        val visited = LinkedHashSet<String>()
        var listCalls = 0
        RemoteSyncProgress.begin(sourceInstanceId, 0, RemoteSyncProgress.Stage.SCANNING)
        while (pending.isNotEmpty() && tracks.size < limit) {
            RemoteSyncProgress.advance(sourceInstanceId, tracks.size, 0, RemoteSyncProgress.Stage.SCANNING)
            val parentId = pending.removeFirst()
            if (!visited.add(parentId)) continue
            var marker: String? = null
            do {
                val (files, nextMarker) = session.client.list(
                    refreshToken = session.refreshToken,
                    driveId = driveId,
                    parentFileId = parentId,
                    marker = marker,
                )
                listCalls++
                if (listCalls <= 3 || files.isEmpty()) {
                    DiagnosticLog.important(
                        "AliyunDriveSync",
                        "list parent=${parentId.take(10)} files=${files.size} next=${nextMarker != null} calls=$listCalls",
                    )
                }
                for (file in files) {
                    when {
                        file.isFolder -> pending.addLast(file.fileId)
                        isAudioFile(file.name) -> {
                            if (tracks.size >= limit) break
                            val base = toSummary(sourceInstanceId, driveId, file)
                            val previous = reusable[base.ref.opaqueTrackId]
                            tracks += if (previous != null && isReusable(previous, base)) {
                                reuseMetadata(previous, base)
                            } else {
                                base
                            }
                        }
                    }
                }
                marker = nextMarker
            } while (marker != null && tracks.size < limit)
        }
        enrichMetadata(session, tracks)
        RemoteSyncProgress.finish(sourceInstanceId, tracks.size)
        DiagnosticLog.important(
            "AliyunDriveSync",
            "collected tracks=${tracks.size} listCalls=$listCalls folders=${visited.size}",
        )

        val published = catalogRepository.publishCatalogIfCurrent(
            token = session.operation.token,
            tracks = tracks,
        )
        if (!published) {
            throw AliyunDriveException(
                AliyunDriveFailureKind.STALE_OPERATION,
                "阿里云盘曲库在发布前已过期（来源配置发生变化）",
            )
        }
        return AliyunDriveSyncResult(
            sourceInstanceId = sourceInstanceId,
            configRevision = session.operation.source.configRevision,
            trackCount = tracks.size,
        ).also {
            DiagnosticLog.important("AliyunDriveSync", "published tracks=${it.trackCount}")
        }
    }

    private suspend fun beginSession(sourceInstanceId: String): Session {
        val owner = catalogRepository.sourceOwner(sourceInstanceId)
            ?: throw AliyunDriveException(AliyunDriveFailureKind.PROTOCOL, "未知的阿里云盘来源")
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.ALIYUNDRIVE) {
            throw AliyunDriveException(AliyunDriveFailureKind.PROTOCOL, "远程来源不是阿里云盘")
        }
        if (!source.enabled) {
            throw AliyunDriveException(AliyunDriveFailureKind.PROTOCOL, "阿里云盘来源已停用")
        }
        return try {
            beginSessionWithCredential(sourceInstanceId, owner, operation)
        } catch (failure: AliyunDriveException) {
            if (failure.kind != AliyunDriveFailureKind.AUTH) throw failure
            // refresh_token 每次刷新都会轮换作废旧值；播放水合可能刚刚刷新过，
            // 这里拿着上一代 token 必然被拒。重读最新凭证再试一次。
            beginSessionWithCredential(sourceInstanceId, owner, operation)
        }
    }

    private suspend fun beginSessionWithCredential(
        sourceInstanceId: String,
        owner: com.mica.music.data.remote.RemoteSourceOwner,
        operation: com.mica.music.data.remote.RemoteOperationSnapshot,
    ): Session {
        val source = operation.source.instance
        val credential = credentialStore.resolve(source.credentialRef)
            ?: throw AliyunDriveException(AliyunDriveFailureKind.AUTH, "阿里云盘登录信息不可用")
        val refreshToken = (credential.material as? RemoteCredentialMaterial.BearerToken)?.token
            ?: throw AliyunDriveException(AliyunDriveFailureKind.AUTH, "阿里云盘来源缺少 refresh_token")
        if (!owner.isCurrent(operation.token)) {
            throw AliyunDriveException(
                AliyunDriveFailureKind.STALE_OPERATION,
                "阿里云盘来源在扫描开始前已变化",
            )
        }
        return Session(
            sourceInstanceId = sourceInstanceId,
            operation = operation,
            refreshToken = refreshToken,
            client = AliyunDriveClient(
                onRefreshTokenRotated = { newRefreshToken ->
                    credentialStore.put(
                        source.credentialRef,
                        RemoteCredentialMaterial.BearerToken(
                            username = ALIYUN_USERNAME,
                            token = newRefreshToken,
                        ),
                    )
                },
            ),
        )
    }

    /**
     * 阿里每次刷新都会作废旧 refresh_token 并下发新的；新值必须回写同一 credentialRef，
     * 否则进程重启后 SDK 里存的仍是被作废的旧值，登录态丢失。
     */
    private fun newClientFor(credentialRef: String): AliyunDriveClient =
        AliyunDriveClient(
            onRefreshTokenRotated = { newRefreshToken ->
                credentialStore.put(
                    credentialRef,
                    RemoteCredentialMaterial.BearerToken(
                        username = ALIYUN_USERNAME,
                        token = newRefreshToken,
                    ),
                )
            },
        )
    private fun toSummary(sourceInstanceId: String, driveId: String, file: AliyunDriveClient.DriveFile): RemoteTrackSummary {
        val suffix = file.name.substringAfterLast('.', "").lowercase()
        val base = file.name.substringBeforeLast('.', file.name)
        // 不读标签时（默认）只能从文件名猜艺术家，至少让列表可读。
        val (title, artist) = splitArtistTitle(base)
        return RemoteTrackSummary(
            ref = RemoteTrackRef(
                sourceInstanceId = sourceInstanceId,
                opaqueTrackId = "${file.fileId}@$driveId",
            ),
            title = title,
            artist = artist,
            fileName = file.name,
            suffix = suffix,
            sizeBytes = file.size,
            mimeTypeHint = MIME_BY_EXTENSION[suffix].orEmpty(),
            contentRevision = "alipan:${file.updatedAt}:${file.size}",
        )
    }

    /** 识别「艺术家 - 标题」命名；不匹配时整名归为标题。 */
    private fun splitArtistTitle(base: String): Pair<String, String> {
        for (separator in ARTIST_TITLE_SEPARATORS) {
            val index = base.indexOf(separator)
            if (index <= 0) continue
            val artist = base.substring(0, index).trim()
            val title = base.substring(index + separator.length).trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) return title to artist
        }
        return base to ""
    }

    private fun isReusable(previous: RemoteTrackSummary, base: RemoteTrackSummary): Boolean =
        previous.contentRevision == base.contentRevision &&
            previous.sizeBytes == base.sizeBytes &&
            (metadataProbe == null || previous.metadataProbeRevision == REMOTE_METADATA_PROBE_REVISION)

    private fun reuseMetadata(previous: RemoteTrackSummary, base: RemoteTrackSummary): RemoteTrackSummary =
        previous.copy(
            ref = base.ref,
            mimeTypeHint = base.mimeTypeHint,
            fileName = base.fileName,
            suffix = base.suffix,
            sizeBytes = base.sizeBytes,
            contentRevision = base.contentRevision,
            artworkOpaqueId = base.artworkOpaqueId.ifBlank {
                previous.artworkOpaqueId.takeIf { RemoteEmbeddedArtworkIdCodec.decode(it) != null }.orEmpty()
            },
        )

    /**
     * 目录扫描只拿到文件名，艺术家/专辑/时长/内嵌封面都得读文件标签。
     * 未标记探测版本的曲目逐条补测，结果写入目录；失败不影响曲目本身。
     */
    private suspend fun enrichMetadata(
        session: Session,
        tracks: MutableList<RemoteTrackSummary>,
    ) {
        val probe = metadataProbe ?: return
        val candidates = tracks.indices.filter { tracks[it].metadataProbeRevision != REMOTE_METADATA_PROBE_REVISION }
        RemoteSyncProgress.advance(session.sourceInstanceId, 0, candidates.size, RemoteSyncProgress.Stage.METADATA)
        var done = 0
        candidates.chunked(REMOTE_METADATA_IO_CONCURRENCY).forEach { chunk ->
            val enriched = coroutineScope {
                chunk.map { index ->
                    async(Dispatchers.IO) {
                        index to probeTrack(session, tracks[index], probe)
                    }
                }.awaitAll()
            }
            enriched.forEach { (index, track) -> tracks[index] = track }
            done += chunk.size
            RemoteSyncProgress.advance(session.sourceInstanceId, done, candidates.size, RemoteSyncProgress.Stage.METADATA)
        }
    }

    private suspend fun probeTrack(
        session: Session,
        base: RemoteTrackSummary,
        probe: RemoteTrackMetadataProbe,
    ): RemoteTrackSummary {
        if (base.sizeBytes <= 0L) return base
        val metadata = runCatching {
            val (fileId, driveId) = decodeAliyunDriveOpaqueTrackId(base.ref.opaqueTrackId) ?: return base
            val url = session.client.downloadUrl(session.refreshToken, driveId, fileId)
            val httpUrl = url.toHttpUrlOrNull() ?: return base
            AliyunDriveSeekableByteSource(httpClient, httpUrl, base.sizeBytes).use { source ->
                ReadAheadSeekableByteSource(source, readAheadBytes = 64 * 1024).use { buffered ->
                    probe.probe(base.fileName, buffered)
                }
            }
        }.onFailure { failure ->
            DiagnosticLog.event(
                "RemoteMetadata",
                "aliyundrive-probe fallback song=${base.mediaId.takeLast(12)} error=${failure.javaClass.simpleName}",
            )
        }.getOrNull() ?: return base
        return base.copy(
            title = metadata.title.ifBlank { base.title },
            artist = metadata.artist,
            album = metadata.album,
            albumArtist = metadata.albumArtist,
            durationSec = metadata.durationSec,
            sampleRateHz = metadata.sampleRateHz,
            bitsPerSample = metadata.bitsPerSample,
            bitrateKbps = metadata.bitrateKbps,
            channelCount = metadata.channelCount,
            year = metadata.year,
            trackNumber = metadata.trackNumber,
            discNumber = metadata.discNumber,
            metadataProbeRevision = REMOTE_METADATA_PROBE_REVISION,
            artworkOpaqueId = base.artworkOpaqueId.ifBlank {
                if (metadata.hasEmbeddedArtwork) {
                    RemoteEmbeddedArtworkIdCodec.encode(
                        base.ref.opaqueTrackId,
                        base.contentRevision,
                        base.sizeBytes,
                    )
                } else {
                    ""
                }
            },
        )
    }

    private fun isAudioFile(name: String): Boolean {
        val suffix = name.substringAfterLast('.', "").lowercase()
        return suffix in AUDIO_EXTENSIONS && !name.startsWith(".")
    }

    private companion object {
        const val ROOT_FOLDER_ID = "root"
        const val ALIYUN_USERNAME = "aliyundrive"

        /** 常见「艺术家 - 标题」分隔符。只认带空格的写法，避免误拆含连字符的曲名。 */
        val ARTIST_TITLE_SEPARATORS = listOf(" - ", " – ", " — ")
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac", "aiff", "aif", "dsf", "dff",
        )
        val MIME_BY_EXTENSION = mapOf(
            "mp3" to "audio/mpeg",
            "flac" to "audio/flac",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus",
            "wav" to "audio/wav",
            "ape" to "audio/ape",
            "wma" to "audio/x-ms-wma",
            "alac" to "audio/alac",
            "aiff" to "audio/aiff",
            "aif" to "audio/aiff",
            "dsf" to "audio/dsf",
            "dff" to "audio/dsd",
        )
    }
}

/** `mediaId` 由 RemoteMediaIdCodec 编码，此处把来源内 opaque id 拆回 (file_id, drive_id)。 */
internal fun decodeAliyunDriveOpaqueTrackId(opaqueTrackId: String): Pair<String, String>? {
    val fileId = opaqueTrackId.substringBeforeLast('@')
    val driveId = opaqueTrackId.substringAfterLast('@', "")
    if (fileId.isBlank() || driveId.isBlank()) return null
    return fileId to driveId
}
