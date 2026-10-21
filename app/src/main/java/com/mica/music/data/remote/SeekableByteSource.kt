package com.mica.music.data.remote

import android.content.Context
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.scanner.AudioProbeBytes
import com.mica.music.data.scanner.AudioProbeRandomAccessSource
import com.mica.music.data.scanner.EmbeddedLyricsReader
import com.mica.music.data.scanner.EmbeddedLyricsResolver
import com.mica.music.data.scanner.JAudioTaggerReader
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Protocol-neutral, read-only random-access byte source for remote media. */
internal interface SeekableByteSource : Closeable {
    val sizeBytes: Long

    fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int
}

/**
 * One bounded window shared by all proxy-fd reads for a single metadata probe.
 * Tag readers commonly issue many small adjacent reads; collapsing those into one protocol read
 * removes an RTT per small read without turning catalog sync into whole-file download.
 */
internal class ReadAheadSeekableByteSource(
    private val delegate: SeekableByteSource,
    private val readAheadBytes: Int = DEFAULT_REMOTE_METADATA_READ_AHEAD_BYTES,
) : SeekableByteSource {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val cache = ByteArray(readAheadBytes)
    private var cacheStart = -1L
    private var cacheLength = 0

    init {
        require(readAheadBytes > 0) { "readAheadBytes must be positive" }
    }

    override val sizeBytes: Long
        get() = delegate.sizeBytes

    override fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int = synchronized(lock) {
        require(fileOffset >= 0L) { "fileOffset must not be negative" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset + length <= buffer.size) {
            "Invalid destination range"
        }
        if (length == 0) return@synchronized 0
        if (fileOffset >= sizeBytes) return@synchronized -1

        val target = minOf(length.toLong(), sizeBytes - fileOffset).toInt()
        var total = 0
        while (total < target) {
            val cursor = fileOffset + total
            val remaining = target - total

            if (cursor >= cacheStart && cursor < cacheStart + cacheLength) {
                val cachedOffset = (cursor - cacheStart).toInt()
                val copied = minOf(remaining, cacheLength - cachedOffset)
                cache.copyInto(
                    destination = buffer,
                    destinationOffset = bufferOffset + total,
                    startIndex = cachedOffset,
                    endIndex = cachedOffset + copied,
                )
                total += copied
                continue
            }

            if (remaining > cache.size) {
                val fetched = delegate.readAt(cursor, buffer, bufferOffset + total, remaining)
                if (fetched < 0) {
                    throw IOException("Remote metadata read ended before the known file boundary")
                }
                if (fetched == 0) {
                    throw IOException("Remote metadata read made no progress")
                }
                total += fetched
                continue
            }

            val requested = minOf(cache.size.toLong(), sizeBytes - cursor).toInt()
            val fetched = delegate.readAt(cursor, cache, 0, requested)
            if (fetched < 0) {
                cacheStart = -1L
                cacheLength = 0
                throw IOException("Remote metadata read ended before the known file boundary")
            }
            if (fetched == 0) {
                cacheStart = -1L
                cacheLength = 0
                throw IOException("Remote metadata read made no progress")
            }
            cacheStart = cursor
            cacheLength = fetched
        }
        total
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }
}

internal const val DEFAULT_REMOTE_METADATA_READ_AHEAD_BYTES = 1024 * 1024
internal const val REMOTE_METADATA_IO_CONCURRENCY = 8

/** Browse metadata that can be populated without downloading artwork or lyric payloads. */
internal data class RemoteTrackMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val durationSec: Int = 0,
    val sampleRateHz: Int = 0,
    val bitsPerSample: Int? = null,
    val bitrateKbps: Int = 0,
    val channelCount: Int = 0,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val hasEmbeddedArtwork: Boolean = false,
)

// Revision 3 re-probes catalogs to persist technical audio properties.
internal const val REMOTE_METADATA_PROBE_REVISION = 3

internal fun interface RemoteTrackMetadataProbe {
    fun probe(fileName: String, source: SeekableByteSource): RemoteTrackMetadata?
}

/**
 * Reuses Mica's existing TagLib fd reader over a proxy descriptor backed by protocol random reads.
 * Pictures stay disabled here: catalog sync must remain bounded for large remote libraries.
 */
internal class AndroidJavaRemoteTrackMetadataProbe(
    context: Context,
) : RemoteTrackMetadataProbe {
    private val appContext = context.applicationContext

    override fun probe(fileName: String, source: SeekableByteSource): RemoteTrackMetadata? {
        val tags = JAudioTaggerReader.readSeekable(source, appContext.cacheDir, fileName) ?: return null
        return RemoteTrackMetadata(
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            albumArtist = tags.albumArtist,
            durationSec = tags.durationSec.coerceAtLeast(0),
            sampleRateHz = tags.sampleRateHz.coerceAtLeast(0),
            bitsPerSample = tags.bitsPerSample.takeIf { it > 0 },
            bitrateKbps = tags.bitrateKbps.coerceAtLeast(0),
            channelCount = tags.channelCount.coerceAtLeast(0),
            year = tags.year.coerceAtLeast(0),
            trackNumber = tags.trackNumber.coerceAtLeast(0),
            discNumber = tags.discNumber.coerceAtLeast(0),
            hasEmbeddedArtwork = tags.hasPictures,
        )
    }
}

/** Reads the front embedded picture only when an artwork URI is actually opened. */
internal class AndroidJavaEmbeddedArtworkLoader(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun load(source: SeekableByteSource, fileName: String? = null): ByteArray {
        val result = JAudioTaggerReader.readSeekable(source, appContext.cacheDir, fileName)
        return result?.frontCoverBytes?.takeIf(ByteArray::isNotEmpty)
            ?: throw java.io.IOException("Remote track has no readable embedded artwork")
    }
}

internal fun interface RemoteEmbeddedLyricsLoader {
    fun load(source: SeekableByteSource, fileName: String, mimeType: String): LyricsDocument
}

/** Reads tag text candidates first, then the same bounded binary fallback used by local scan. */
internal class AndroidJavaEmbeddedLyricsLoader(
    context: Context,
) : RemoteEmbeddedLyricsLoader {
    private val appContext = context.applicationContext

    override fun load(source: SeekableByteSource, fileName: String, mimeType: String): LyricsDocument {
        JAudioTaggerReader.readSeekable(source, appContext.cacheDir, fileName)?.let { tags ->
            EmbeddedLyricsResolver.selectTagTextCandidate(
                candidates = tags.lyricsCandidates,
                parse = EmbeddedLyricsReader::parseTagTextDocument,
            )?.let { return it }
        }

        val randomAccess = object : AudioProbeRandomAccessSource {
            override val sizeBytes: Long
                get() = source.sizeBytes

            override fun readAt(
                fileOffset: Long,
                buffer: ByteArray,
                bufferOffset: Int,
                length: Int,
            ): Int = source.readAt(fileOffset, buffer, bufferOffset, length)
        }
        val bytes = AudioProbeBytes.readFastForLyricsOrThrow(
            source = randomAccess,
            mimeType = mimeType,
            displayName = fileName,
        ) ?: return LyricsDocument(origin = LyricsOrigin.EMBEDDED)
        val ext = fileName.substringAfterLast('.', "")
        return EmbeddedLyricsReader.parseBinaryDocument(bytes, mimeType, ext)
            ?: LyricsDocument(origin = LyricsOrigin.EMBEDDED)
    }
}
