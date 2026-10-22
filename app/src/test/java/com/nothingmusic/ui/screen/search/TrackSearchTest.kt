package com.nothingmusic.ui.screen.search

import com.nothingmusic.domain.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackSearchTest {

    private fun track(
        title: String = "Night Drive",
        artist: String = "Mono",
        album: String = "Hymn",
        albumArtist: String = "",
        documentName: String = "01-night-drive.flac",
    ): Track = Track(
        id = 1L,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        documentName = documentName,
        durationMs = 1_000L,
        path = "content://music/1",
        folderPath = "content://tree",
        dateAdded = 0,
        trackNumber = 1,
        year = 2020,
    )

    @Test
    fun `matches title case insensitively`() {
        assertTrue(track().matchesSearch("night"))
        assertTrue(track().matchesSearch("NIGHT DRIVE"))
    }

    @Test
    fun `matches artist album album artist and file name`() {
        val subject = track(albumArtist = "Mono & Friends", documentName = "hidden-bonus.flac")
        assertTrue(subject.matchesSearch("mono"))
        assertTrue(subject.matchesSearch("hymn"))
        assertTrue(subject.matchesSearch("friends"))
        assertTrue(subject.matchesSearch("bonus"))
    }

    @Test
    fun `blank query never matches`() {
        assertFalse(track().matchesSearch(""))
        assertFalse(track().matchesSearch("   "))
    }

    @Test
    fun `unrelated query does not match`() {
        assertFalse(track().matchesSearch("cello"))
    }
}
