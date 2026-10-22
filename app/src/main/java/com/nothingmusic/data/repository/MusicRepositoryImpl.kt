package com.nothingmusic.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nothingmusic.data.artwork.LocalArtworkStore
import com.nothingmusic.data.local.dao.FavoriteDao
import com.nothingmusic.data.local.dao.PlaylistDao
import com.nothingmusic.data.local.dao.ScannedFolderDao
import com.nothingmusic.data.local.dao.TrackDao
import com.nothingmusic.data.local.entity.FavoriteEntity
import com.nothingmusic.data.local.entity.PlaylistEntity
import com.nothingmusic.data.local.entity.PlaylistTrackEntity
import com.nothingmusic.data.local.entity.ScannedFolderEntity
import com.nothingmusic.data.local.entity.TrackEntity
import com.nothingmusic.data.local.toDomain
import com.nothingmusic.data.local.toEntity
import com.nothingmusic.data.lyrics.LrcLyricsResolver
import com.nothingmusic.data.scanner.AudioScanner
import com.nothingmusic.data.scanner.SCANNER_METADATA_VERSION
import com.nothingmusic.domain.model.Album
import com.nothingmusic.domain.model.Artist
import com.nothingmusic.domain.model.Folder
import com.nothingmusic.domain.model.Lyrics
import com.nothingmusic.domain.model.Playlist
import com.nothingmusic.domain.model.Track
import com.nothingmusic.domain.model.groupIntoAlbums
import com.nothingmusic.domain.repository.MusicRepository
import com.nothingmusic.util.SafUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: AudioScanner,
    private val playlistDao: PlaylistDao,
    private val scannedFolderDao: ScannedFolderDao,
    private val favoriteDao: FavoriteDao,
    private val trackDao: TrackDao,
    private val artworkStore: LocalArtworkStore,
) : MusicRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    override val tracks: StateFlow<List<Track>> =
        trackDao.observeLocal()
            .map { rows -> rows.map(TrackEntity::toDomain) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val favoriteIds: StateFlow<List<Long>> =
        favoriteDao.observeFavoriteIds()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val favoriteTracks: StateFlow<List<Track>> =
        favoriteDao.observeFavoriteTracks()
            .map { rows -> rows.map(TrackEntity::toDomain) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val albums: StateFlow<List<Album>> =
        tracks.map { list -> list.groupIntoAlbums() }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val artists: StateFlow<List<Artist>> =
        tracks.map { list ->
            list.groupBy { it.artist }
                .map { (name, groupTracks) ->
                    Artist(
                        name = name,
                        albumCount = groupTracks
                            .map { it.album to it.resolvedAlbumArtist }
                            .distinct()
                            .size,
                        trackCount = groupTracks.size,
                        avatarUri = groupTracks.firstNotNullOfOrNull { it.albumArtUri?.toString() },
                    )
                }
                .sortedBy { it.name.lowercase() }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val folders: StateFlow<List<Folder>> =
        combine(tracks, scannedFolderDao.observeFolders()) { allTracks, roots ->
            roots.map { entity ->
                Folder(
                    path = entity.path,
                    name = entity.displayName.ifBlank { SafUtils.treeDisplayName(entity.path) },
                    trackCount = allTracks.count { it.folderPath == entity.path },
                    isSelected = true,
                )
            }.sortedBy { it.name.lowercase() }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val playlists: StateFlow<List<Playlist>> =
        combine(
            playlistDao.observePlaylists(),
            playlistDao.observePlaylistTrackCounts(),
        ) { playlists, counts ->
            playlists.map { entity ->
                Playlist(
                    id = entity.id,
                    name = entity.name,
                    trackCount = counts.firstOrNull { it.playlistId == entity.id }?.trackCount ?: 0,
                    createdAt = entity.createdAt,
                )
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override suspend fun refreshLibrary(scannedFolders: List<String>) {
        refreshMutex.withLock { refreshLocked(scannedFolders) }
    }

    private suspend fun refreshLocked(scannedFolders: List<String>) {
        val roots = (scannedFolders.ifEmpty { getAllScannedFolderPaths() })
            .filter { it.startsWith("content://") }
        if (roots.isEmpty()) {
            trackDao.deleteAllLocal()
            pruneArtwork()
            return
        }
        val cachedRows = trackDao.getLocal()
        val known = cachedRows
            .filter { it.metadataVersion >= SCANNER_METADATA_VERSION }
            .associate { it.uri to it.toDomain() }
        val scans = scanner.scanFolders(roots, known)

        for (scan in scans) {
            if (!scan.accessible) continue
            if (scan.tracks.isNotEmpty()) {
                trackDao.upsertAll(scan.tracks.map(Track::toEntity))
            }
            val scannedUris = scan.tracks.mapTo(HashSet()) { it.path }
            val stale = cachedRows
                .filter { it.folderUri == scan.folderUri && it.uri !in scannedUris }
                .map { it.uri }
            stale.chunked(SQLITE_BATCH).forEach { trackDao.deleteLocalByUris(it) }
        }
        trackDao.deleteLocalOutsideFolders(roots)
        pruneArtwork()
    }

    private suspend fun pruneArtwork() {
        artworkStore.prune(trackDao.getLocal().mapNotNull { it.artworkUri }.toSet())
    }

    override suspend fun getAllScannedFolderPaths(): List<String> = scannedFolderDao.getAllPaths()

    override suspend fun getScannedFolders(): List<String> = scannedFolderDao.getAllPaths()

    override suspend fun addScannedFolder(path: String) {
        val existing = scannedFolderDao.getAllPaths()
        // 父目录已在扫描范围内时，子目录无需重复授权与扫描。
        if (existing.any { it != path && SafUtils.isNestedTree(path, it) }) return

        takePersistablePermission(path)
        val displayName = withContext(Dispatchers.IO) {
            runCatching { DocumentFile.fromTreeUri(context, Uri.parse(path))?.name }.getOrNull()
        } ?: SafUtils.treeDisplayName(path)
        scannedFolderDao.insert(ScannedFolderEntity(path = path, displayName = displayName))

        // 新加的父目录覆盖已选中的子目录，移除子目录避免重复计数。
        existing.filter { it != path && SafUtils.isNestedTree(it, path) }.forEach { child ->
            scannedFolderDao.delete(child)
            trackDao.deleteLocalByFolder(child)
            releasePersistablePermission(child)
        }
        refreshLibrary(getAllScannedFolderPaths())
    }

    override suspend fun removeScannedFolder(path: String) {
        refreshMutex.withLock {
            scannedFolderDao.delete(path)
            trackDao.deleteLocalByFolder(path)
            releasePersistablePermission(path)
            pruneArtwork()
        }
    }

    override suspend fun getAllTracks(): List<Track> = trackDao.getLocal().map(TrackEntity::toDomain)

    override suspend fun trackById(id: Long): Track? = trackDao.getById(id)?.toDomain()

    override suspend fun tracksByIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(SQLITE_BATCH)
            .flatMap { trackDao.getByIds(it) }
            .associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toDomain() }
    }

    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        playlistDao.observeTracksForPlaylist(playlistId)
            .map { rows -> rows.map(TrackEntity::toDomain) }

    override suspend fun toggleFavorite(track: Track) {
        val favorites = favoriteDao.getFavoriteIds()
        if (track.id in favorites) {
            favoriteDao.delete(track.id)
        } else {
            if (track.isRemote) trackDao.upsertAll(listOf(track.toEntity()))
            favoriteDao.insert(FavoriteEntity(trackId = track.id))
        }
    }

    override suspend fun isFavorite(trackId: Long): Boolean = trackId in favoriteDao.getFavoriteIds()

    override fun search(query: String): Flow<List<Track>> =
        tracks.map { list ->
            if (query.isBlank()) emptyList()
            else {
                val q = query.lowercase().trim()
                list.filter {
                    it.title.lowercase().contains(q) ||
                        it.artist.lowercase().contains(q) ||
                        it.album.lowercase().contains(q) ||
                        it.albumArtist.lowercase().contains(q) ||
                        it.documentName.lowercase().contains(q)
                }
            }
        }

    override suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name))

    override suspend fun deletePlaylist(id: Long) = playlistDao.deletePlaylist(id)

    override suspend fun addToPlaylist(playlistId: Long, tracks: List<Track>) {
        val existing = playlistDao.getTracksInPlaylist(playlistId).map { it.trackId }.toSet()
        val additions = tracks.distinctBy { it.id }.filterNot { it.id in existing }
        if (additions.isEmpty()) return
        val remoteRows = additions.filter { it.isRemote }.map { it.toEntity() }
        if (remoteRows.isNotEmpty()) trackDao.upsertAll(remoteRows)
        val startPosition = playlistDao.countTracks(playlistId)
        playlistDao.insertTracks(
            additions.mapIndexed { index, track ->
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    trackId = track.id,
                    position = startPosition + index,
                )
            },
        )
    }

    override suspend fun renamePlaylist(id: Long, newName: String) =
        playlistDao.renamePlaylist(id, newName)

    override suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) =
        playlistDao.removeTrack(playlistId, trackId)

    override suspend fun getLyrics(track: Track): Lyrics =
        LrcLyricsResolver.resolve(context, track)

    private fun takePersistablePermission(path: String) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(path),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun releasePersistablePermission(path: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(path),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        const val SQLITE_BATCH = 500
    }
}
