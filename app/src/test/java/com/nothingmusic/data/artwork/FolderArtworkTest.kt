package com.nothingmusic.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderArtworkTest {

    @Test
    fun `prefers cover over folder`() {
        assertEquals("cover.jpg", FolderArtwork.pick(listOf("folder.png", "cover.jpg")))
    }

    @Test
    fun `prefers albumart over album`() {
        assertEquals("albumart.png", FolderArtwork.pick(listOf("album.jpg", "albumart.png")))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals("Cover.JPG", FolderArtwork.pick(listOf("Cover.JPG")))
    }

    @Test
    fun `ignores unrelated images and non images`() {
        assertNull(FolderArtwork.pick(listOf("back.jpg", "scan.png", "cover.txt", "notes")))
    }

    @Test
    fun `returns null for empty directory`() {
        assertNull(FolderArtwork.pick(emptyList()))
    }
}
