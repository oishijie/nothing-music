package com.mica.music.data.remote.alist

import com.mica.music.data.remote.MutableSecureRemoteCredentialStore
import com.mica.music.data.remote.ReadAheadSeekableByteSource
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.REMOTE_METADATA_IO_CONCURRENCY
import com.mica.music.data.remote.REMOTE_METADATA_PROBE_REVISION
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSidecarArtworkCandidate
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteSyncProgress
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.canUseRemoteFolderArtwork
import com.mica.music.data.remote.isRemoteSidecarArtworkFile
import com.mica.music.data.remote.remoteArtworkRevisionKey
import com.mica.music.data.remote.selectRemoteFolderSidecarArtwork
import com.mica.music.data.remote.selectRemoteTrackSidecarArtwork
import com.mica.music.util.DiagnosticLog
import java.util.ArrayDeque
import java.util.LinkedHashSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class AListSyncResult(
    val sourceInstanceId: String,
    val configRevision: Long,
    val trackCount: Int,
)

/**
 * AList 整源曲库刷新，与 WebDAV/阿里云盘同步引擎同构：
 * 单一 operation/credential 快照下 BFS 扫描目录树，经 RemoteCatalogRepository 原子发布。
 *
 * 曲目 opaque id 是服务器上的绝对路径（base64url 后进 mediaId，不受 `.` 约束）；
 * 封面与 WebDAV 一致支持同名旁挂图与 cover/folder/front 目录图。
 */
internal class AListSourceSync(
    private val catalogRepository: RemoteCatalogRepository,
    private val credentialStore: MutableSecureRemoteCredentialStore,
    private val metadataProbe: RemoteTrackMetadataProbe? = null,
    private val httpClient: okhttp3.OkHttpClient = AListSeekableByteSource.openClient(),
) {
    private data class Session(
        val sourceInstanceId: String,
        val operation: RemoteOperationSnapshot,
        val client: AListClient,
    )

    /** 目录完整扫描后暂存的目录级封面；专辑信息要等探针补测后才能校验，所以回填延后。 */
    private data class PendingFolderArtwork(
        val trackIndices: List<Int>,
        val candidate: RemoteSidecarArtworkCandidate,
    )

    suspend fun testConnection(sourceInstanceId: String) {
        val session = beginSession(sourceInstanceId)
        session.client.list("/", 1, 1)
    }

    suspend fun sync(
        sourceInstanceId: String,
        limit: Int = Int.MAX_VALUE,
    ): AListSyncResult {
        require(limit >= 0) { "limit must not be negative" }
        val session = beginSession(sourceInstanceId)
        DiagnosticLog.important("AListSync", "start source=$sourceInstanceId")
        val reusable = catalogRepository
            .reusableCatalogIfCurrent(session.operation.token)
            .orEmpty()

        val tracks = ArrayList<RemoteTrackSummary>()
        val pendingFolders = ArrayDeque<String>().also { it.addLast("/") }
        val pendingFolderArtwork = ArrayList<PendingFolderArtwork>()
        val visited = LinkedHashSet<String>()
        var listCalls = 0
        RemoteSyncProgress.begin(sourceInstanceId, 0, RemoteSyncProgress.Stage.SCANNING)
        while (pendingFolders.isNotEmpty() && tracks.size < limit) {
            RemoteSyncProgress.advance(sourceInstanceId, tracks.size, 0, RemoteSyncProgress.Stage.SCANNING)
            val directory = pendingFolders.removeFirst()
            if (!visited.add(directory)) continue
            scanDirectory(session, directory, reusable, tracks, limit, pendingFolderArtwork) {
                pendingFolders.addLast(it)
            }
            listCalls++
        }
        enrichMetadata(session, tracks)
        applyFolderArtwork(tracks, pendingFolderArtwork)
        RemoteSyncProgress.finish(sourceInstanceId, tracks.size)
        DiagnosticLog.important(
            "AListSync",
            "collected tracks=${tracks.size} listCalls=$listCalls folders=${visited.size}",
        )

        val published = catalogRepository.publishCatalogIfCurrent(
            token = session.operation.token,
            tracks = tracks,
        )
        if (!published) {
            throw AListException(
                AListFailureKind.STALE_OPERATION,
                "AList 曲库在发布前已过期（来源配置发生变化）",
            )
        }
        return AListSyncResult(
            sourceInstanceId = sourceInstanceId,
            configRevision = session.operation.source.configRevision,
            trackCount = tracks.size,
        ).also {
            DiagnosticLog.important("AListSync", "published tracks=${it.trackCount}")
        }
    }

    /**
     * 扫描单个目录：音频入库、子目录入队、同名旁挂封面内联回填。
     * 目录级 cover/folder/front 图只暂存，等补测出专辑信息后再按 WebDAV 同规则回填。
     */
    private suspend fun scanDirectory(
        session: Session,
        directory: String,
        reusable: Map<String, RemoteTrackSummary>,
        tracks: MutableList<RemoteTrackSummary>,
        limit: Int,
        folderArtworkOut: MutableList<PendingFolderArtwork>,
        enqueue: (String) -> Unit,
    ) {
        val artworkCandidates = ArrayList<RemoteSidecarArtworkCandidate>()
        val directoryTrackIndices = ArrayList<Int>()
        var directoryComplete = true
        var page = 1
        var hasMore = true
        while (hasMore) {
            val (entries, more) = session.client.list(directory, page, LIST_PER_PAGE)
            hasMore = more
            page++
            for (entry in entries) {
                val entryPath = AListPathCodec.joinPath(directory, entry.name)
                when {
                    entry.isDir -> enqueue(entryPath)
                    isAudioFile(entry.name) -> {
                        if (tracks.size >= limit) {
                            directoryComplete = false
                            break
                        }
                        val base = toSummary(session.sourceInstanceId, entryPath, entry)
                        val previous = reusable[base.ref.opaqueTrackId]
                        val summary = if (previous != null && isReusable(previous, base)) {
                            reuseMetadata(previous, base)
                        } else {
                            base
                        }
                        directoryTrackIndices += tracks.size
                        tracks += summary
                    }
                    isRemoteSidecarArtworkFile(entry.name) -> {
                        artworkCandidates += RemoteSidecarArtworkCandidate(
                            fileName = entry.name,
                            resourceId = entryPath,
                            contentRevision = remoteArtworkRevisionKey(
                                revisionOf(entry),
                                entry.size,
                            ),
                            sizeBytes = entry.size,
                        )
                    }
                }
            }
            if (tracks.size >= limit) directoryComplete = false
            if (!directoryComplete) break
        }

        if (directoryComplete && artworkCandidates.isNotEmpty()) {
            selectRemoteFolderSidecarArtwork(artworkCandidates)?.let { candidate ->
                folderArtworkOut += PendingFolderArtwork(directoryTrackIndices.toList(), candidate)
            }
        }
        for (index in directoryTrackIndices) {
            val track = tracks[index]
            val sidecar = selectRemoteTrackSidecarArtwork(track.fileName, artworkCandidates)
            val artworkId = sidecar?.let {
                RemoteFileArtworkIdCodec.encode(it.resourceId, it.contentRevision)
            } ?: ""
            if (artworkId.isNotBlank()) {
                tracks[index] = track.copy(artworkOpaqueId = artworkId)
            }
        }
    }

    /**
     * 目录级封面回填：仅当目录完整且目录内曲目专辑一致（或只有一首）时才可信，
     * 且只填仍无封面的曲目——同名旁挂图与内嵌封面的优先级都高于它。
     */
    private fun applyFolderArtwork(
        tracks: MutableList<RemoteTrackSummary>,
        pending: List<PendingFolderArtwork>,
    ) {
        for (entry in pending) {
            if (!canUseRemoteFolderArtwork(entry.trackIndices.map { tracks[it] })) continue
            val artworkId = RemoteFileArtworkIdCodec.encode(
                entry.candidate.resourceId,
                entry.candidate.contentRevision,
            )
            for (index in entry.trackIndices) {
                if (tracks[index].artworkOpaqueId.isBlank()) {
                    tracks[index] = tracks[index].copy(artworkOpaqueId = artworkId)
                }
            }
        }
    }

    private suspend fun beginSession(sourceInstanceId: String): Session {
        val owner = catalogRepository.sourceOwner(sourceInstanceId)
            ?: throw AListException(AListFailureKind.PROTOCOL, "未知的 AList 来源")
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.ALIST) {
            throw AListException(AListFailureKind.PROTOCOL, "远程来源不是 AList")
        }
        if (!source.enabled) {
            throw AListException(AListFailureKind.PROTOCOL, "AList 来源已停用")
        }
        val credential = credentialStore.resolve(source.credentialRef)
            ?: throw AListException(AListFailureKind.AUTH, "AList 登录信息不可用")
        if (!owner.isCurrent(operation.token)) {
            throw AListException(
                AListFailureKind.STALE_OPERATION,
                "AList 来源在扫描开始前已变化",
            )
        }
        return Session(
            sourceInstanceId = sourceInstanceId,
            operation = operation,
            client = AListClients.forSource(source, credential)
                ?: throw AListException(AListFailureKind.AUTH, "AList 来源凭证类型不受支持"),
        )
    }
    private fun toSummary(
        sourceInstanceId: String,
        path: String,
        entry: AListClient.AListEntry,
    ): RemoteTrackSummary {
        val suffix = entry.name.substringAfterLast('.', "").lowercase()
        val base = entry.name.substringBeforeLast('.', entry.name)
        // 不读标签时（默认）只能从文件名猜艺术家，至少让列表可读。
        val (title, artist) = splitArtistTitle(base)
        return RemoteTrackSummary(
            ref = RemoteTrackRef(
                sourceInstanceId = sourceInstanceId,
                opaqueTrackId = path,
            ),
            title = title,
            artist = artist,
            fileName = entry.name,
            suffix = suffix,
            sizeBytes = entry.size,
            mimeTypeHint = MIME_BY_EXTENSION[suffix].orEmpty(),
            contentRevision = revisionOf(entry),
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
            val detail = session.client.fileInfo(base.ref.opaqueTrackId)
            val url = session.client.resolveRawUrl(detail) ?: return base
            AListSeekableByteSource(
                client = httpClient,
                url = url,
                sizeBytes = base.sizeBytes,
                requestHeaders = session.client.playbackHeaders(detail),
            ).use { source ->
                ReadAheadSeekableByteSource(source, readAheadBytes = 64 * 1024).use { buffered ->
                    probe.probe(base.fileName, buffered)
                }
            }
        }.onFailure { failure ->
            DiagnosticLog.event(
                "RemoteMetadata",
                "alist-probe fallback song=${base.mediaId.takeLast(12)} error=${failure.javaClass.simpleName}",
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

    private fun revisionOf(entry: AListClient.AListEntry): String =
        "alist:${entry.modified}:${entry.size}"

    private companion object {
        const val LIST_PER_PAGE = 200

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
