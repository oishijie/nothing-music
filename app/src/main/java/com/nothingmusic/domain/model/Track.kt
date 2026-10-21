package com.nothingmusic.domain.model

import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val path: String,
    val albumArtUri: Uri? = null,
    val folderPath: String,
    val dateAdded: Long,
    val trackNumber: Int,
    val year: Int,
    val mimeType: String? = null,
    val isFavorite: Boolean = false,
    val sizeBytes: Long = 0L,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    /**
     * 远程曲目标识（Mica 的 opaque mediaId）。非空时播放 URI 用 mica-remote://track/<id>，
     * id 字段则使用远程标识派生的稳定 Long，避免改动 Room 主键与全部列表。
     */
    val remoteId: String? = null,
) {
    val displayDuration: String
        get() {
            val totalSeconds = ((durationMs / 1000).toInt()).takeIf { it > 0 } ?: 0
            return formatDuration(totalSeconds)
        }

    val isRemote: Boolean
        get() = remoteId != null

    val extension: String
        get() = path.substringAfterLast(".", "").lowercase()
}

fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

fun formatDurationMs(durationMs: Long): String =
    formatDuration((durationMs / 1000).toInt())