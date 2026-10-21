package com.nothingmusic.util

import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafUtilsTest {

    private fun treeUri(documentId: String): String =
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId)
            .toString()

    @Test
    fun `tree display name uses leaf folder`() {
        assertEquals("Albums", SafUtils.treeDisplayName(treeUri("primary:Music/Albums")))
    }

    @Test
    fun `tree display name falls back to volume for storage root`() {
        assertEquals("Internal storage", SafUtils.treeDisplayName(treeUri("primary:")))
        assertEquals("1234-5678", SafUtils.treeDisplayName(treeUri("1234-5678:")))
    }

    @Test
    fun `nested tree detection compares document ids`() {
        val parent = treeUri("primary:Music")
        val child = treeUri("primary:Music/Albums")
        val sibling = treeUri("primary:Podcasts")

        assertTrue(SafUtils.isNestedTree(child, parent))
        assertFalse(SafUtils.isNestedTree(parent, child))
        assertFalse(SafUtils.isNestedTree(sibling, parent))
        assertFalse(SafUtils.isNestedTree(parent, parent))
    }

    @Test
    fun `stable track id is deterministic and positive`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.mp3"
        val first = SafUtils.stableTrackId(uri)
        val second = SafUtils.stableTrackId(uri)

        assertEquals(first, second)
        assertTrue(first > 0)
    }

    @Test
    fun `stable track id differs across files`() {
        val a = SafUtils.stableTrackId("content://provider/document/a.mp3")
        val b = SafUtils.stableTrackId("content://provider/document/b.mp3")

        assertNotEquals(a, b)
    }
}
