package com.nothingmusic.util

import com.nothingmusic.domain.model.formatDuration
import com.nothingmusic.domain.model.formatDurationMs
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun `formatDuration formats zero as 0_00`() {
        assertEquals("0:00", formatDuration(0))
    }

    @Test
    fun `formatDuration formats seconds only`() {
        assertEquals("0:59", formatDuration(59))
    }

    @Test
    fun `formatDuration pads single digit seconds`() {
        assertEquals("1:05", formatDuration(65))
    }

    @Test
    fun `formatDuration formats ten minutes`() {
        assertEquals("10:00", formatDuration(600))
    }

    @Test
    fun `formatDuration handles just under an hour`() {
        assertEquals("59:59", formatDuration(3599))
    }

    @Test
    fun `formatDurationMs converts milliseconds`() {
        assertEquals("1:05", formatDurationMs(65_000L))
        assertEquals("0:00", formatDurationMs(999L))
    }

    @Test
    fun `formatBytes renders bytes`() {
        assertEquals("512 B", FileUtils.formatBytes(512L))
    }

    @Test
    fun `formatBytes renders kilobytes`() {
        assertEquals("1.0 KB", FileUtils.formatBytes(1024L))
    }

    @Test
    fun `formatBytes renders megabytes`() {
        assertEquals("2.5 MB", FileUtils.formatBytes(2L * 1024 * 1024 + 512 * 1024))
    }

    @Test
    fun `formatBytes renders gigabytes`() {
        assertEquals("1.00 GB", FileUtils.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `readableDuration formats minutes and seconds`() {
        assertEquals("1:05", FileUtils.readableDuration(65_000L))
    }

    @Test
    fun `readableDuration includes hours when present`() {
        assertEquals("1:01:01", FileUtils.readableDuration(3_661_000L))
    }
}
