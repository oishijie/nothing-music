package com.nothingmusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
) {
    @androidx.room.PrimaryKey(autoGenerate = true)
    var rowId: Long = 0
}

@Entity(tableName = "scanned_folders")
data class ScannedFolderEntity(
    /** SAF 树 URI；旧版裸路径在 v2 迁移中清理。 */
    @PrimaryKey val path: String,
    @androidx.room.ColumnInfo(defaultValue = "") val displayName: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "tracks",
    indices = [
        androidx.room.Index(value = ["uri"], unique = true),
        androidx.room.Index(value = ["folderUri"]),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: Long,
    /** SAF 文档 URI，同时作为播放 URI。 */
    val uri: String,
    val documentName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** 所属树 URI，用于按文件夹筛选与增量清理。 */
    val folderUri: String,
    val dateAdded: Long,
    val trackNumber: Int,
    val year: Int,
    val mimeType: String?,
    val sizeBytes: Long,
    val lastModified: Long,
    val sampleRate: Int,
    val bitrate: Int,
    val artworkUri: String?,
    /** 扫描器元数据版本；低于当前版本的缓存行会在下次扫描时重读，用于补齐新字段。 */
    @androidx.room.ColumnInfo(defaultValue = "0") val metadataVersion: Int = 0,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val trackId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val timestamp: Long = System.currentTimeMillis(),
)