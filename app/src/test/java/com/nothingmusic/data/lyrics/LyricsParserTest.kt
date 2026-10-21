package com.nothingmusic.data.lyrics

import com.nothingmusic.domain.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsParserTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "lrc-test")
    }

    private fun writeLrc(name: String, content: String): File =
        File(tempDir, name).apply { writeText(content) }

    private fun trackFor(lrc: File?): Track {
        val audioFile = File(tempDir, "song.mp3").apply { createNewFile() }
        return Track(
            id = 1L,
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 0L,
            path = audioFile.absolutePath,
            folderPath = tempDir.absolutePath,
            dateAdded = 0L,
            trackNumber = 0,
            year = 0,
        )
    }

    @Test
    fun `returns empty lyrics when no lrc file exists`() = runTest {
        val lyrics = LyricsParser.parse(trackFor(null))
        assertTrue(lyrics.lines.isEmpty())
        assertNull(lyrics.sourceFile)
    }

    @Test
    fun `parses timestamps and text`() = runTest {
        val lrc = writeLrc(
            "song.lrc",
            """
            [00:12.00]Hello world
            [01:05.50]Second line
            """.trimIndent(),
        )
        val lyrics = LyricsParser.parse(trackFor(lrc))

        assertEquals(2, lyrics.lines.size)
        assertEquals(12_000L, lyrics.lines[0].timestampMs)
        assertEquals("Hello world", lyrics.lines[0].text)
        assertEquals(65_500L, lyrics.lines[1].timestampMs)
        assertEquals("Second line", lyrics.lines[1].text)
        // Case-insensitive: on case-insensitive filesystems the parser's
        // title-based candidate ("Song.lrc") may resolve to "song.lrc" on disk.
        assertTrue(lyrics.sourceFile != null && lyrics.sourceFile.equals(lrc.absolutePath, ignoreCase = true))
    }

    @Test
    fun `handles multiple timestamps on one line`() = runTest {
        val lrc = writeLrc("song.lrc", "[00:10.00][00:20.00]Chorus")
        val lyrics = LyricsParser.parse(trackFor(lrc))

        assertEquals(2, lyrics.lines.size)
        assertEquals("Chorus", lyrics.lines[0].text)
        assertEquals("Chorus", lyrics.lines[1].text)
        assertEquals(10_000L, lyrics.lines[0].timestampMs)
        assertEquals(20_000L, lyrics.lines[1].timestampMs)
    }

    @Test
    fun `applies positive offset tag`() = runTest {
        val lrc = writeLrc("song.lrc", "[offset:+500]\n[00:01.00]Shifted")
        val lyrics = LyricsParser.parse(trackFor(lrc))
        assertEquals(1_500L, lyrics.lines[0].timestampMs)
        assertEquals(500L, lyrics.offsetMs)
    }

    @Test
    fun `applies negative offset tag`() = runTest {
        val lrc = writeLrc("song.lrc", "[offset:-500]\n[00:01.00]Shifted")
        val lyrics = LyricsParser.parse(trackFor(lrc))
        assertEquals(500L, lyrics.lines[0].timestampMs)
    }

    @Test
    fun `parses id3 metadata tags`() = runTest {
        val lrc = writeLrc(
            "song.lrc",
            """
            [ti:Test Title]
            [ar:Test Artist]
            [al:Test Album]
            [00:01.00]Line
            """.trimIndent(),
        )
        val lyrics = LyricsParser.parse(trackFor(lrc))
        assertEquals("Test Title", lyrics.title)
        assertEquals("Test Artist", lyrics.artist)
        assertEquals("Test Album", lyrics.album)
    }

    @Test
    fun `sorts lines by timestamp regardless of file order`() = runTest {
        val lrc = writeLrc("song.lrc", "[00:20.00]Later\n[00:05.00]Earlier")
        val lyrics = LyricsParser.parse(trackFor(lrc))
        assertEquals("Earlier", lyrics.lines[0].text)
        assertEquals("Later", lyrics.lines[1].text)
    }

    @Test
    fun `handles two digit millisecond precision`() = runTest {
        val lrc = writeLrc("song.lrc", "[00:03.25]Quarter")
        val lyrics = LyricsParser.parse(trackFor(lrc))
        assertEquals(3_250L, lyrics.lines[0].timestampMs)
    }

    @Test
    fun `ignores lines without valid timestamps`() = runTest {
        val lrc = writeLrc(
            "song.lrc",
            """
            This is not a timestamped line
            [00:01.00]Valid
            """.trimIndent(),
        )
        val lyrics = LyricsParser.parse(trackFor(lrc))
        assertEquals(1, lyrics.lines.size)
        assertEquals("Valid", lyrics.lines[0].text)
    }

    @Test
    fun `finds lrc file matching track title`() = runTest {
        // Track title is "Song"; sidecar named after the title must be found.
        val lrc = writeLrc("Song.lrc", "[00:01.00]By title")
        val lyrics = LyricsParser.parse(trackFor(lrc))
        assertEquals(1, lyrics.lines.size)
    }
}
