package com.nothingmusic.data.local

import com.nothingmusic.data.scanner.SCANNER_METADATA_VERSION
import com.nothingmusic.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackEntityMapperTest {

    private fun track(
        id: Long,
        path: String,
        remoteId: String? = null,
    ) = Track(
        id = id,
        title = "Title",
        artist = "Artist",
        album = "Album",
        albumArtist = "Album Artist",
        discNumber = 2,
        durationMs = 180_000,
        path = path,
        folderPath = if (remoteId == null) "content://tree/folder" else "source-1",
        dateAdded = 0,
        trackNumber = 3,
        year = 2020,
        remoteId = remoteId,
    )

    @Test
    fun `local track entity stays a scanner row`() {
        val entity = track(1, "content://tree/document").toEntity()

        assertEquals("", entity.remoteId)
        assertEquals(SCANNER_METADATA_VERSION, entity.metadataVersion)
        assertNull(entity.toDomain().remoteId)
        assertEquals(entity.uri, entity.toDomain().path)
    }

    @Test
    fun `remote snapshot keeps media id and is not marked as scanned`() {
        val entity = track(2, "opaque-media-id", remoteId = "opaque-media-id").toEntity()

        assertEquals("opaque-media-id", entity.remoteId)
        assertEquals(0, entity.metadataVersion)
        val domain = entity.toDomain()
        assertEquals("opaque-media-id", domain.remoteId)
        assertEquals("opaque-media-id", domain.path)
        assertEquals("source-1", domain.folderPath)
    }

    @Test
    fun `album metadata survives roundtrip`() {
        val domain = track(3, "content://tree/document").toEntity().toDomain()

        assertEquals("Album Artist", domain.albumArtist)
        assertEquals(2, domain.discNumber)
        assertEquals(3, domain.trackNumber)
        assertEquals(2020, domain.year)
    }
}
