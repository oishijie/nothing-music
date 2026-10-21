package com.nothingmusic.domain.model

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumGroupingTest {

    private fun track(
        id: Long,
        artist: String,
        album: String = "Hits",
        albumArtist: String = "",
        durationMs: Long = 1_000L,
        art: String? = null,
        year: Int = 2020,
    ): Track = Track(
        id = id,
        title = "Song $id",
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationMs = durationMs,
        path = "content://music/$id",
        folderPath = "content://tree",
        dateAdded = 0,
        trackNumber = id.toInt(),
        year = year,
        albumArtUri = art?.let(Uri::parse),
    )

    @Test
    fun `compilation stays one album when track artists differ`() {
        val albums = listOf(
            track(1, artist = "Artist A", albumArtist = "Various Artists"),
            track(2, artist = "Artist B", albumArtist = "Various Artists"),
            track(3, artist = "Artist C", albumArtist = "Various Artists"),
        ).groupIntoAlbums()

        assertEquals(1, albums.size)
        assertEquals("Various Artists", albums.single().artist)
        assertEquals(3, albums.single().trackCount)
    }

    @Test
    fun `missing album artist falls back to track artist`() {
        val albums = listOf(
            track(1, artist = "Artist A"),
            track(2, artist = "Artist B"),
        ).groupIntoAlbums()

        assertEquals(2, albums.size)
    }

    @Test
    fun `album summary sums durations and picks artwork`() {
        val albums = listOf(
            track(1, artist = "Artist A", durationMs = 1_000L),
            track(2, artist = "Artist A", durationMs = 2_500L, art = "content://art/1"),
        ).groupIntoAlbums()

        val album = albums.single()
        assertEquals(3_500L, album.durationMs)
        assertEquals("content://art/1", album.albumArtUri)
        assertEquals(2020, album.year)
    }

    @Test
    fun `albums without artwork stay null`() {
        val album = listOf(track(1, artist = "Artist A")).groupIntoAlbums().single()

        assertNull(album.albumArtUri)
    }

    @Test
    fun `albums are sorted by name then artist`() {
        val albums = listOf(
            track(1, artist = "B", album = "Zebra"),
            track(2, artist = "C", album = "Alpha"),
            track(3, artist = "A", album = "Alpha"),
        ).groupIntoAlbums()

        assertEquals(listOf("Alpha" to "A", "Alpha" to "C", "Zebra" to "B"),
            albums.map { it.name to it.artist })
    }
}
