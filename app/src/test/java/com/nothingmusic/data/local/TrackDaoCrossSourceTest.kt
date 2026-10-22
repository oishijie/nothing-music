package com.nothingmusic.data.local

import androidx.room.Room
import com.nothingmusic.data.local.entity.FavoriteEntity
import com.nothingmusic.data.local.entity.PlaylistEntity
import com.nothingmusic.data.local.entity.PlaylistTrackEntity
import com.nothingmusic.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackDaoCrossSourceTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(id: Long, remoteId: String = ""): TrackEntity = TrackEntity(
        id = id,
        uri = if (remoteId.isEmpty()) "content://tree/document/$id" else remoteId,
        documentName = "track$id.flac",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMs = 60_000,
        folderUri = if (remoteId.isEmpty()) "content://tree" else "source-1",
        dateAdded = 0,
        trackNumber = 0,
        year = 0,
        mimeType = "audio/flac",
        sizeBytes = 0,
        lastModified = 0,
        sampleRate = 44_100,
        bitrate = 900,
        artworkUri = null,
        remoteId = remoteId,
    )

    @Test
    fun `local library ignores remote snapshots`() = runBlocking {
        db.trackDao().upsertAll(listOf(entity(1), entity(2, remoteId = "remote-2")))

        assertEquals(listOf(1L), db.trackDao().observeLocal().first().map { it.id })
        assertEquals(2, db.trackDao().getAll().size)
    }

    @Test
    fun `favorite list resolves local and remote tracks`() = runBlocking {
        db.trackDao().upsertAll(listOf(entity(1), entity(2, remoteId = "remote-2")))
        db.favoriteDao().insert(FavoriteEntity(trackId = 1, addedAt = 100))
        db.favoriteDao().insert(FavoriteEntity(trackId = 2, addedAt = 200))

        assertEquals(
            listOf(2L, 1L),
            db.favoriteDao().observeFavoriteTracks().first().map { it.id },
        )
    }

    @Test
    fun `playlist keeps position order across local and remote tracks`() = runBlocking {
        db.trackDao().upsertAll(listOf(entity(1), entity(2, remoteId = "remote-2")))
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = "Mixed"))
        db.playlistDao().insertTracks(
            listOf(
                PlaylistTrackEntity(playlistId = playlistId, trackId = 2, position = 0),
                PlaylistTrackEntity(playlistId = playlistId, trackId = 1, position = 1),
            ),
        )

        val resolved = db.playlistDao().observeTracksForPlaylist(playlistId).first()

        assertEquals(listOf(2L, 1L), resolved.map { it.id })
        assertEquals("remote-2", resolved.first().remoteId)
    }

    @Test
    fun `scanner cleanup keeps remote snapshots`() = runBlocking {
        db.trackDao().upsertAll(
            listOf(entity(1), entity(2, remoteId = "remote-2")),
        )

        db.trackDao().deleteLocalOutsideFolders(listOf("content://other-tree"))

        assertEquals(listOf(2L), db.trackDao().getAll().map { it.id })
    }
}
