package com.nothingmusic.data.artwork

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalArtworkStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = LocalArtworkStore(context)

    private fun jpeg(vararg payload: Byte): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), *payload)

    @Test
    fun `identical covers share one file`() {
        val first = store.store(jpeg(1, 2, 3))
        val second = store.store(jpeg(1, 2, 3))

        assertEquals(first, second)
        assertTrue(File(requireNotNull(first).path!!).isFile)
    }

    @Test
    fun `different covers produce different files`() {
        val first = store.store(jpeg(1))
        val second = store.store(jpeg(2))

        assertNotEquals(first, second)
    }

    @Test
    fun `empty payload is ignored`() {
        assertEquals(null, store.store(ByteArray(0)))
    }

    @Test
    fun `prune removes files that are no longer referenced`() {
        val kept = requireNotNull(store.store(jpeg(7)))
        val dropped = requireNotNull(store.store(jpeg(8)))

        store.prune(setOf(kept.toString()))

        assertTrue(File(kept.path!!).isFile)
        assertFalse(File(dropped.path!!).exists())
    }
}
