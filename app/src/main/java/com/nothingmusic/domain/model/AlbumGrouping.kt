package com.nothingmusic.domain.model

/**
 * 按「专辑名 + 专辑艺术家」聚合。曲目艺术家不同（合辑、feat.）但专辑艺术家相同的曲目
 * 仍属于同一张专辑；没有专辑艺术家时回退到曲目艺术家。
 */
internal fun List<Track>.groupIntoAlbums(): List<Album> =
    groupBy { it.album to it.resolvedAlbumArtist }
        .map { (key, tracks) ->
            Album(
                name = key.first,
                artist = key.second,
                albumArtUri = tracks.firstNotNullOfOrNull { it.albumArtUri?.toString() },
                trackCount = tracks.size,
                year = tracks.firstOrNull()?.year ?: 0,
                durationMs = tracks.sumOf { it.durationMs },
            )
        }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.artist.lowercase() }))
