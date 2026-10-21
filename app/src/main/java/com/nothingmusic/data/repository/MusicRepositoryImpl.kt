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
import com.nothingmusic.data.lyrics.LrcLyricsResolver
import com.nothingmusic.data.scanner.AudioScanner
import com.nothingmusic.data.scanner.SCANNER_METADATA_VERSION
import com.nothingmusic.domain.model.Album
import com.nothingmusic.domain.model.Artist
import com.nothingmusic.domain.model.Folder
import com.nothingmusic.domain.model.Lyrics
import com.nothingmusic.domain.model.Playlist
import com.nothingmusic.domain.model.Track
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
        trackDao.observeAll()
            .map { rows -> rows.map(TrackEntity::toDomain) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val favoriteIds: StateFlow<List<Long>> =
        favoriteDao.observeFavoriteIds()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val albums: StateFlow<List<Album>> =
        tracks.map { list ->
            list.groupBy { it.album to it.artist }
                .map { (key, groupTracks) ->
                    Album(
                        name = key.first,
                        artist = key.second,
                        albumArtUri = groupTracks.firstNotNullOfOrNull { it.albumArtUri?.toString() },
                        trackCount = groupTracks.size,
                        year = groupTracks.firstOrNull()?.year ?: 0,
                        durationMs = groupTracks.sumOf { it.durationMs },
                    )
                }
                .sortedBy { it.name.lowercase() }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val artists: StateFlow<List<Artist>> =
        tracks.map { list ->
            list.groupBy { it.artist }
                .map { (name, groupTracks) ->
                    Artist(
                        name = name,
                        albumCount = groupTracks.map { it.album }.distinct().size,
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
            trackDao.deleteAll()
            pruneArtwork()
            return
        }
        val cachedRows = trackDao.getAll()
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
            stale.chunked(SQLITE_BATCH).forEach { trackDao.deleteByUris(it) }
        }
        trackDao.deleteOutsideFolders(roots)
        pruneArtwork()
    }

    private suspend fun pruneArtwork() {
        artworkStore.prune(trackDao.getAll().mapNotNull { it.artworkUri }.toSet())
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
            trackDao.deleteByFolder(child)
            releasePersistablePermission(child)
        }
        refreshLibrary(getAllScannedFolderPaths())
    }

    override suspend fun removeScannedFolder(path: String) {
        refreshMutex.withLock {
            scannedFolderDao.delete(path)
            trackDao.deleteByFolder(path)
            releasePersistablePermission(path)
            pruneArtwork()
        }
    }

    override suspend fun getAllTracks(): List<Track> = trackDao.getAll().map(TrackEntity::toDomain)

    override suspend fun trackById(id: Long): Track? = trackDao.getById(id)?.toDomain()

    override suspend fun tracksByIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(SQLITE_BATCH)
            .flatMap { trackDao.getByIds(it) }
            .associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toDomain() }
    }

    override fun observePlaylistTrackIds(playlistId: Long): Flow<List<Long>> =
        playlistDao.observeTracksInPlaylist(playlistId).map { list -> list.map { it.trackId } }

    override suspend fun toggleFavorite(trackId: Long) {
        val favorites = favoriteDao.getFavoriteIds()
        if (trackId in favorites) favoriteDao.delete(trackId)
        else favoriteDao.insert(FavoriteEntity(trackId = trackId))
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
                        it.documentName.lowercase().contains(q)
                }
            }
        }

    override suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name))

    override suspend fun deletePlaylist(id: Long) = playlistDao.deletePlaylist(id)

    override suspend fun addToPlaylist(playlistId: Long, trackIds: List<Long>) {
        val existing = playlistDao.getTracksInPlaylist(playlistId).map { it.trackId }.toSet()
        val startPosition = playlistDao.countTracks(playlistId)
        val tracks = trackIds.filterNot { it in existing }
            .mapIndexed { index, trackId ->
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = startPosition + index,
                )
            }
        if (tracks.isNotEmpty()) playlistDao.insertTracks(tracks)
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

private fun TrackEntity.toDomain(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    path = uri,
    albumArtUri = artworkUri?.let(Uri::parse),
    folderPath = folderUri,
    dateAdded = dateAdded,
    trackNumber = trackNumber,
    year = year,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sampleRate = sampleRate,
    bitrate = bitrate,
    lastModified = lastModified,
    documentName = documentName,
)

private fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    uri = path,
    documentName = documentName,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    folderUri = folderPath,
    dateAdded = dateAdded,
    trackNumber = trackNumber,
    year = year,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    sampleRate = sampleRate,
    bitrate = bitrate,
    artworkUri = albumArtUri?.toString(),
    metadataVersion = SCANNER_METADATA_VERSION,
)
