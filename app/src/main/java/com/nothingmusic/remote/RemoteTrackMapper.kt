package com.nothingmusic.remote

import com.mica.music.data.remote.RemoteArtworkUriCodec
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteTrackSummary
import com.nothingmusic.domain.model.Track

/**
 * 把 Mica 的远程曲目摘要映射为宿主的 [Track]。
 *
 * 远程曲目没有数值主键，这里用媒体标识的稳定哈希填 [Track.id]（仅用于列表键与去重），
 * 真正的播放寻址走 [Track.remoteId]。
 */
fun RemoteTrackSummary.toTrack(): Track = Track(
    id = stableRemoteTrackId(mediaId),
    title = title.ifBlank { fileName },
    artist = artist,
    album = album,
    durationMs = durationSec.coerceAtLeast(0).toLong() * 1000L,
    // path 保留媒体标识；实际播放 URI 由 Track.remoteId 生成（见 toMediaItem）。
    path = mediaId,
    albumArtUri = artworkOpaqueId
        .takeIf(String::isNotBlank)
        ?.let { RemoteArtworkUriCodec.encode(com.mica.music.data.remote.RemoteArtworkRef(ref.sourceInstanceId, it)) }
        ?.let(android.net.Uri::parse),
    folderPath = ref.sourceInstanceId,
    dateAdded = 0L,
    trackNumber = trackNumber,
    year = year,
    mimeType = mimeTypeHint.takeIf(String::isNotBlank),
    sizeBytes = sizeBytes,
    sampleRate = sampleRateHz,
    bitrate = bitrateKbps,
    documentName = fileName,
    remoteId = mediaId,
)

/** 远程标识 → 稳定 Long（列表键用；不参与播放寻址）。 */
fun stableRemoteTrackId(mediaId: String): Long {
    require(RemoteMediaIdCodec.isRemoteId(mediaId)) { "Not a remote media id" }
    return mediaId.hashCode().toLong()
}
