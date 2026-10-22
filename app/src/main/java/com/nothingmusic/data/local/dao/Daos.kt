package com.nothingmusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.nothingmusic.data.local.entity.PlaylistEntity
import com.nothingmusic.data.local.entity.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    data class PlaylistTrackCount(
        val playlistId: Long,
        val trackCount: Int,
    )

    @Query("SELECT playlistId, COUNT(*) AS trackCount FROM playlist_tracks GROUP BY playlistId")
    fun observePlaylistTrackCounts(): Flow<List<PlaylistTrackCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    fun observeTracksInPlaylist(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    /** 歌单内容按 position 排序；远程曲目依赖收藏/歌单时写入的快照行。 */
    @Query(
        "SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON pt.trackId = t.id " +
            "WHERE pt.playlistId = :playlistId ORDER BY pt.position"
    )
    fun observeTracksForPlaylist(playlistId: Long): Flow<List<com.nothingmusic.data.local.entity.TrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getTracksInPlaylist(playlistId: Long): List<PlaylistTrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun countTracks(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: Long)

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun updatePosition(playlistId: Long, trackId: Long, position: Int)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)
}

@Dao
interface ScannedFolderDao {
    @Query("SELECT * FROM scanned_folders ORDER BY addedAt")
    fun observeFolders(): Flow<List<com.nothingmusic.data.local.entity.ScannedFolderEntity>>

    @Query("SELECT path FROM scanned_folders")
    suspend fun getAllPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: com.nothingmusic.data.local.entity.ScannedFolderEntity)

    @Query("DELETE FROM scanned_folders WHERE path = :path")
    suspend fun delete(path: String)

    @Query("SELECT COUNT(*) FROM scanned_folders")
    suspend fun count(): Int
}

@Dao
interface TrackDao {
    /** 本地曲库只包含 SAF 扫描行；远程快照行仅通过收藏/歌单解析。 */
    @Query(
        "SELECT * FROM tracks WHERE remoteId = '' ORDER BY artist COLLATE NOCASE, " +
            "album COLLATE NOCASE, discNumber, trackNumber, title COLLATE NOCASE"
    )
    fun observeLocal(): Flow<List<com.nothingmusic.data.local.entity.TrackEntity>>

    @Query("SELECT * FROM tracks WHERE remoteId = ''")
    suspend fun getLocal(): List<com.nothingmusic.data.local.entity.TrackEntity>

    @Query("SELECT * FROM tracks")
    suspend fun getAll(): List<com.nothingmusic.data.local.entity.TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): com.nothingmusic.data.local.entity.TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<com.nothingmusic.data.local.entity.TrackEntity>

    @Upsert
    suspend fun upsertAll(tracks: List<com.nothingmusic.data.local.entity.TrackEntity>)

    @Query("DELETE FROM tracks WHERE remoteId = '' AND uri IN (:uris)")
    suspend fun deleteLocalByUris(uris: List<String>)

    @Query("DELETE FROM tracks WHERE remoteId = '' AND folderUri = :folderUri")
    suspend fun deleteLocalByFolder(folderUri: String)

    @Query("DELETE FROM tracks WHERE remoteId = '' AND folderUri NOT IN (:folders)")
    suspend fun deleteLocalOutsideFolders(folders: List<String>)

    @Query("DELETE FROM tracks WHERE remoteId = ''")
    suspend fun deleteAllLocal()
}

@Dao
interface FavoriteDao {
    @Query("SELECT trackId FROM favorites ORDER BY addedAt DESC")
    fun observeFavoriteIds(): Flow<List<Long>>

    /** 收藏列表同样走 tracks 快照，本地与远程曲目一视同仁。 */
    @Query(
        "SELECT t.* FROM tracks t INNER JOIN favorites f ON f.trackId = t.id " +
            "ORDER BY f.addedAt DESC"
    )
    fun observeFavoriteTracks(): Flow<List<com.nothingmusic.data.local.entity.TrackEntity>>

    @Query("SELECT trackId FROM favorites")
    suspend fun getFavoriteIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: com.nothingmusic.data.local.entity.FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun delete(trackId: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<com.nothingmusic.data.local.entity.SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(query: com.nothingmusic.data.local.entity.SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}