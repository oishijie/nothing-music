package com.nothingmusic.remote

import com.mica.music.RemoteRuntime
import com.mica.music.data.remote.ReadAheadSeekableByteSource
import com.mica.music.data.remote.REMOTE_METADATA_PROBE_REVISION
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.SeekableByteSource
import com.mica.music.data.remote.alist.AListStreamRequestResolver
import com.mica.music.data.remote.aliyundrive.AliyunDriveStreamRequestResolver
import com.mica.music.data.remote.webdav.WebDavStreamRequestResolver
import com.mica.music.util.DiagnosticLog
import com.nothingmusic.domain.model.Track
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 播放时按需补全远程曲目的标签与内嵌封面（对齐棉花音乐的按需策略）：
 * 同步只建文件清单；第一次播放某首时 Range 下载文件头尾读标签，
 * 结果写回目录库，此后所有界面（曲库列表 / 播放栏 / 播放页）自动带上完整元数据。
 *
 * 同一曲目并发请求合并为一次探测；探测过的曲目（metadataProbeRevision 达标）直接跳过。
 */
object RemotePlaybackHydrator {

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Track?>>()

    /**
     * 解析器必须单例：内部按来源缓存 HTTP client 与阿里云盘 token（2 小时有效期），
     * 每次水合新建实例会绕过缓存，导致每首歌都强制刷新阿里 refresh_token；
     * 阿里刷新即轮换作废旧值，高频轮换会让同时进行的同步拿着上一代 token 被拒。
     */
    private val sharedResolver: RemoteHttpPlaybackRequestResolver by lazy {
        CompositePlaybackResolver(
            WebDavStreamRequestResolver(
                sourceOwnerById = { id -> RemoteRuntime.catalogRepository.sourceOwner(id) },
                credentialStore = RemoteRuntime.credentialStore,
            ),
            AliyunDriveStreamRequestResolver(
                sourceOwnerById = { id -> RemoteRuntime.catalogRepository.sourceOwner(id) },
                credentialStore = RemoteRuntime.credentialStore,
            ),
            AListStreamRequestResolver(
                sourceOwnerById = { id -> RemoteRuntime.catalogRepository.sourceOwner(id) },
                credentialStore = RemoteRuntime.credentialStore,
            ),
        )
    }

    suspend fun hydrate(track: Track): Track? {
        val mediaId = track.remoteId ?: return null
        while (true) {
            val existing = inFlight[mediaId]
            if (existing != null) {
                // 等在跑的那次完成即可；本次调用不重复探测。
                existing.await()
                return null
            }
            val created = CompletableDeferred<Track?>()
            if (inFlight.putIfAbsent(mediaId, created) == null) {
                try {
                    val result = withContext(Dispatchers.IO) { hydrateInternal(track) }
                    created.complete(result)
                    return result
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) {
                        created.complete(null)
                        throw failure
                    }
                    DiagnosticLog.event(
                        "RemoteHydrator",
                        "hydrate-failed song=${mediaId.takeLast(12)} error=${failure.javaClass.simpleName}",
                    )
                    created.complete(null)
                    return null
                } finally {
                    inFlight.remove(mediaId)
                }
            }
        }
    }

    private suspend fun hydrateInternal(track: Track): Track? {
        val catalog = RemoteRuntime.catalogRepository
        val mediaId = track.remoteId ?: return null
        val ref = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val summary = catalog.find(listOf(ref))[ref] ?: return null
        // 探测过就不再来：封面为空说明这条音轨本身没有内嵌封面（旁挂封面走同步期路径）。
        if (summary.metadataProbeRevision >= REMOTE_METADATA_PROBE_REVISION) return null
        if (summary.sizeBytes <= 0L) return null

        val request = sharedResolver.resolve(mediaId) ?: return null
        val probe: RemoteTrackMetadataProbe = RemoteRuntime.metadataProbe ?: return null
        val metadata = runCatching {
            HttpRangeSeekableByteSource(request.url, request.requestHeaders, summary.sizeBytes).use { raw ->
                ReadAheadSeekableByteSource(raw, readAheadBytes = 64 * 1024).use { buffered ->
                    probe.probe(summary.fileName, buffered)
                }
            }
        }.onFailure { failure ->
            DiagnosticLog.event(
                "RemoteHydrator",
                "probe-fallback song=${mediaId.takeLast(12)} error=${failure.javaClass.simpleName}",
            )
        }.getOrNull() ?: return null

        val updated = summary.copy(
            title = metadata.title.ifBlank { summary.title },
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
            artworkOpaqueId = summary.artworkOpaqueId.ifBlank {
                if (metadata.hasEmbeddedArtwork) {
                    RemoteEmbeddedArtworkIdCodec.encode(
                        summary.ref.opaqueTrackId,
                        summary.contentRevision,
                        summary.sizeBytes,
                    )
                } else {
                    ""
                }
            },
        )
        // 写回失败（例如曲库刚被重新发布）也不影响本次播放页展示。
        runCatching { catalog.updateTrackMetadata(updated) }
        return updated.toTrack().copy(isFavorite = track.isFavorite)
    }
}

/** 组合解析器：WebDAV / 阿里云盘 / AList 逐个尝试，命中即返回。 */
private class CompositePlaybackResolver(
    private vararg val delegates: RemoteHttpPlaybackRequestResolver,
) : RemoteHttpPlaybackRequestResolver {
    override suspend fun resolve(mediaId: String) =
        delegates.firstNotNullOfOrNull { it.resolve(mediaId) }
}

/** 直链的有限 Range 随机读取源：阿里/AList/WebDAV 的直链与代理都要求 206。 */
private class HttpRangeSeekableByteSource(
    private val url: String,
    private val headers: Map<String, String>,
    override val sizeBytes: Long,
) : SeekableByteSource {
    private val closed = AtomicBoolean(false)

    override fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int {
        check(!closed.get()) { "Remote byte source is closed" }
        require(fileOffset >= 0L) { "fileOffset must not be negative" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset + length <= buffer.size) {
            "Invalid destination range"
        }
        if (length == 0) return 0
        if (fileOffset >= sizeBytes) return -1

        val requested = minOf(length.toLong(), sizeBytes - fileOffset).toInt()
        val endInclusive = fileOffset + requested - 1L
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=$fileOffset-$endInclusive")
            .header("Accept-Encoding", "identity")
            .get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        try {
            RemotePlaybackHydratorHttp.client.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw IOException("Remote range request was not authorized (HTTP ${response.code})")
                    206 -> Unit
                    else -> throw IOException("Remote range request requires HTTP 206, got ${response.code}")
                }
                val body = response.body ?: throw IOException("Remote range response had no body")
                val input = body.byteStream()
                var total = 0
                while (total < requested) {
                    val count = input.read(buffer, bufferOffset + total, requested - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total != requested) {
                    throw IOException("Remote range response ended before the requested window")
                }
                return total
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("Remote range request failed", failure)
        }
    }

    override fun close() {
        closed.set(true)
    }
}

private object RemotePlaybackHydratorHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
