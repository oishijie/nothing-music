package com.nothingmusic.domain.repository

import com.nothingmusic.domain.model.Album
import com.nothingmusic.domain.model.Artist
import com.nothingmusic.domain.model.Folder
import com.nothingmusic.domain.model.Lyrics
import com.nothingmusic.domain.model.Playlist
import com.nothingmusic.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface MusicRepository {
    val tracks: Flow<List<Track>>
    val albums: Flow<List<Album>>
    val artists: Flow<List<Artist>>
    val folders: Flow<List<Folder>>
    val playlists: Flow<List<Playlist>>
    val favoriteIds: Flow<List<Long>>

    suspend fun refreshLibrary(scannedFolders: List<String>)
    suspend fun getScannedFolders(): List<String>
    suspend fun getAllScannedFolderPaths(): List<String>
    suspend fun addScannedFolder(path: String)
    suspend fun removeScannedFolder(path: String)
    suspend fun getAllTracks(): List<Track>
    suspend fun trackById(id: Long): Track?
    suspend fun tracksByIds(ids: List<Long>): List<Track>
    fun observePlaylistTrackIds(playlistId: Long): Flow<List<Long>>
    suspend fun toggleFavorite(trackId: Long)
    suspend fun isFavorite(trackId: Long): Boolean
    fun search(query: String): Flow<List<Track>>
    suspend fun createPlaylist(name: String): Long
    suspend fun deletePlaylist(id: Long)
    suspend fun addToPlaylist(playlistId: Long, trackIds: List<Long>)
    suspend fun renamePlaylist(id: Long, newName: String)
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)
    suspend fun getLyrics(track: Track): Lyrics
}