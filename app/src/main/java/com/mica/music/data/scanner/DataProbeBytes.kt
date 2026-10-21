package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

internal interface AudioProbeRandomAccessSource {
    val sizeBytes: Long

    fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int
}

/** ID3/文件探测共用二进制工具 */
internal object AudioProbeBytes {
    private const val MAX_FAST_LYRICS_BYTES = 4 * 1024 * 1024
    private val flacMarker = "fLaC".toByteArray(Charsets.US_ASCII)

    fun read(context: Context, uri: Uri): ByteArray? = readOrNull {
        readBytes(context, uri, headBytes = 2 * 1024 * 1024, tailBytes = 8 * 1024 * 1024)
    }

    /** 歌词扫描：大文件加大头尾窗口，避免 ID3/标签落在中间被截断。 */
    fun readForLyrics(context: Context, uri: Uri): ByteArray? =
        readOrNull { readBytes(context, uri, headBytes = 8 * 1024 * 1024, tailBytes = 16 * 1024 * 1024) }

    fun readFastForLyrics(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String?,
    ): ByteArray? = readOrNull { readFastForLyricsOrThrow(context, uri, mimeType, displayName) }

    private inline fun <T> readOrNull(block: () -> T): T? = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    fun readFastForLyricsOrThrow(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String?,
    ): ByteArray? {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.lowercase()
        return when {
            ext == "mp3" || mime.contains("mpeg") -> readId3Tag(context, uri)
            ext == "flac" || mime.contains("flac") -> readFlacMetadata(context, uri)
            ext == "ape" -> readTail(context, uri, 2 * 1024 * 1024)
            ext in setOf("m4a", "m4b", "m4p", "mp4", "alac") || mime.contains("mp4") -> {
                readMp4Moov(context, uri)
                    ?: readHeadAndTail(context, uri, headBytes = 2 * 1024 * 1024, tailBytes = 4 * 1024 * 1024)
            }
            ext == "aac" || mime.contains("aac") -> readId3Tag(context, uri)
            else -> readId3Tag(context, uri)
                ?: readHeadAndTail(context, uri, headBytes = 512 * 1024, tailBytes = 512 * 1024)
        }
    }

    /** Remote/JIT counterpart of [readFastForLyricsOrThrow] using bounded protocol random reads. */
    fun readFastForLyricsOrThrow(
        source: AudioProbeRandomAccessSource,
        mimeType: String,
        displayName: String?,
    ): ByteArray? {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.lowercase()
        return when {
            ext == "mp3" || mime.contains("mpeg") -> readId3Tag(source)
            ext == "flac" || mime.contains("flac") -> readFlacMetadata(source)
            ext == "ape" -> readTail(source, 2 * 1024 * 1024)
            ext in setOf("m4a", "m4b", "m4p", "mp4", "alac") || mime.contains("mp4") -> {
                readMp4Moov(source)
                    ?: readHeadAndTail(source, headBytes = 2 * 1024 * 1024, tailBytes = 4 * 1024 * 1024)
            }
            ext == "aac" || mime.contains("aac") -> readId3Tag(source)
            else -> readId3Tag(source)
                ?: readHeadAndTail(source, headBytes = 512 * 1024, tailBytes = 512 * 1024)
        }
    }

    private fun readBytes(
        context: Context,
        uri: Uri,
        headBytes: Int,
        tailBytes: Int,
    ): ByteArray? {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        return when {
            size in 0..25_000_000 -> readAll(context, uri)
            else -> readHeadAndTail(context, uri, headBytes = headBytes, tailBytes = tailBytes)
        }
    }

    private fun readAll(context: Context, uri: Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Unable to open $uri")

    private fun readId3Tag(context: Context, uri: Uri): ByteArray? =
        (context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Unable to open $uri")).use { input ->
                val header = input.readUpToCompat(10)
                if (header.size < 10 || !Id3Binary.isId3Header(header, 0)) return null
                val tagSize = Id3Binary.synchsafeSize(header, 6)
                val totalSize = (tagSize + 10).coerceAtMost(4 * 1024 * 1024)
                header + input.readUpToCompat(totalSize - header.size)
            }

    private fun readFlacMetadata(context: Context, uri: Uri): ByteArray =
        (context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open $uri")).use { input ->
                readFlacMetadata(input, MAX_FAST_LYRICS_BYTES)
            }

    internal fun readFlacMetadata(input: InputStream, maxBytes: Int = MAX_FAST_LYRICS_BYTES): ByteArray {
        require(maxBytes >= 8) { "FLAC metadata window must hold the marker and one block header" }
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val first = input.readExactlyCompat(4)
        out.write(first)

        var marker = first
        if (first.copyOfRange(0, 3).contentEquals("ID3".toByteArray(Charsets.US_ASCII))) {
            val headerTail = input.readExactlyCompat(6)
            out.write(headerTail)
            val header = first + headerTail
            if (!Id3Binary.isId3Header(header, 0)) return readToLimit(input, out, maxBytes)
            val footerBytes = if (header[5].toInt() and 0x10 != 0) 10 else 0
            val tagBytesRemaining = Id3Binary.synchsafeSize(header, 6) + footerBytes
            if (tagBytesRemaining > maxBytes - out.size()) return readToLimit(input, out, maxBytes)
            out.write(input.readExactlyCompat(tagBytesRemaining))
            if (maxBytes - out.size() < 4) return out.toByteArray()
            marker = input.readExactlyCompat(4)
            out.write(marker)
        }

        if (!marker.contentEquals(flacMarker)) return readToLimit(input, out, maxBytes)
        while (maxBytes - out.size() >= 4) {
            val blockHeader = input.readExactlyCompat(4)
            out.write(blockHeader)
            val isLast = blockHeader[0].toInt() and 0x80 != 0
            val blockLength = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)
            val readable = minOf(blockLength, maxBytes - out.size())
            out.write(input.readExactlyCompat(readable))
            if (readable < blockLength || isLast) return out.toByteArray()
        }
        return out.toByteArray()
    }

    private fun readToLimit(input: InputStream, out: ByteArrayOutputStream, maxBytes: Int): ByteArray {
        val remaining = maxBytes - out.size()
        if (remaining > 0) out.write(input.readUpToCompat(remaining))
        return out.toByteArray()
    }

    private fun InputStream.readExactlyCompat(byteCount: Int): ByteArray =
        readUpToCompat(byteCount).also { bytes ->
            if (bytes.size != byteCount) throw IOException("Unexpected end of FLAC metadata")
        }

    private fun readTail(context: Context, uri: Uri, maxBytes: Int): ByteArray? =
        (context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("Unable to open $uri")).use { pfd ->
                val size = pfd.statSize
                if (size <= 0L) throw java.io.IOException("Unknown size for $uri")
                val tailLen = maxBytes.toLong().coerceAtMost(size).toInt()
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    if (size > tailLen) fis.skipFully(size - tailLen)
                    fis.readUpToCompat(tailLen)
                }
            }

    private fun readMp4Moov(context: Context, uri: Uri): ByteArray? =
        (context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("Unable to open $uri")).use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val fileSize = pfd.statSize.takeIf { it > 0L } ?: channel.size()
                    if (fileSize <= 0L) return null
                    var offset = 0L
                    while (offset + 8 <= fileSize) {
                        val header = channel.readAt(offset, 16) ?: return null
                        if (header.size < 8) return null
                        val boxSize32 = header.readUInt32Be(0)
                        val type = String(header, 4, 4, Charsets.US_ASCII)
                        val headerSize: Long
                        val boxSize: Long
                        when (boxSize32) {
                            0L -> {
                                headerSize = 8L
                                boxSize = fileSize - offset
                            }
                            1L -> {
                                if (header.size < 16) return null
                                headerSize = 16L
                                boxSize = header.readUInt64Be(8)
                            }
                            else -> {
                                headerSize = 8L
                                boxSize = boxSize32
                            }
                        }
                        if (boxSize < headerSize) return null
                        if (type == "moov") {
                            val safeSize = boxSize.coerceAtMost(16L * 1024L * 1024L).toInt()
                            return@use channel.readAt(offset, safeSize)
                        }
                        offset += boxSize
                    }
                    null
                }
            }

    private fun readHeadAndTail(context: Context, uri: Uri, headBytes: Int, tailBytes: Int): ByteArray? =
        (context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Unable to open $uri")).use { it.readUpToCompat(headBytes) }.let { head ->
            val tail = (context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw java.io.IOException("Unable to open $uri")).use { pfd ->
                val size = pfd.statSize
                if (size <= 0L) throw java.io.IOException("Unknown size for $uri")
                val tailLen = tailBytes.toLong().coerceAtMost(size).toInt()
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    if (size > tailLen) fis.skipFully(size - tailLen)
                    fis.readUpToCompat(tailLen)
                }
            }
            head + tail
        }

    private fun readId3Tag(source: AudioProbeRandomAccessSource): ByteArray? {
        val header = source.readUpTo(0L, 10)
        if (header.size < 10 || !Id3Binary.isId3Header(header, 0)) return null
        val tagSize = Id3Binary.synchsafeSize(header, 6)
        val totalSize = (tagSize + 10).coerceAtMost(MAX_FAST_LYRICS_BYTES)
        return source.readUpTo(0L, totalSize)
    }

    private fun readFlacMetadata(source: AudioProbeRandomAccessSource): ByteArray =
        RandomAccessProbeInputStream(source).use { input ->
            readFlacMetadata(input, MAX_FAST_LYRICS_BYTES)
        }

    private fun readTail(source: AudioProbeRandomAccessSource, maxBytes: Int): ByteArray? {
        val size = source.sizeBytes
        if (size <= 0L) throw IOException("Unknown remote source size")
        val tailLen = maxBytes.toLong().coerceAtMost(size).toInt()
        return source.readUpTo(size - tailLen, tailLen)
    }

    private fun readMp4Moov(source: AudioProbeRandomAccessSource): ByteArray? {
        val fileSize = source.sizeBytes
        if (fileSize <= 0L) return null
        var offset = 0L
        while (offset + 8 <= fileSize) {
            val header = source.readUpTo(offset, 16)
            if (header.size < 8) return null
            val boxSize32 = header.readUInt32Be(0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            val headerSize: Long
            val boxSize: Long
            when (boxSize32) {
                0L -> {
                    headerSize = 8L
                    boxSize = fileSize - offset
                }
                1L -> {
                    if (header.size < 16) return null
                    headerSize = 16L
                    boxSize = header.readUInt64Be(8)
                }
                else -> {
                    headerSize = 8L
                    boxSize = boxSize32
                }
            }
            if (boxSize < headerSize) return null
            if (type == "moov") {
                val safeSize = boxSize.coerceAtMost(16L * 1024L * 1024L).toInt()
                return source.readUpTo(offset, safeSize)
            }
            if (boxSize <= 0L || offset > Long.MAX_VALUE - boxSize) return null
            offset += boxSize
        }
        return null
    }

    private fun readHeadAndTail(
        source: AudioProbeRandomAccessSource,
        headBytes: Int,
        tailBytes: Int,
    ): ByteArray? {
        val size = source.sizeBytes
        if (size <= 0L) throw IOException("Unknown remote source size")
        val head = source.readUpTo(0L, minOf(headBytes.toLong(), size).toInt())
        val tailLen = minOf(tailBytes.toLong(), size).toInt()
        val tail = source.readUpTo(size - tailLen, tailLen)
        return head + tail
    }

    private fun AudioProbeRandomAccessSource.readUpTo(fileOffset: Long, maxBytes: Int): ByteArray {
        require(fileOffset >= 0L) { "fileOffset must not be negative" }
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        if (maxBytes == 0 || fileOffset >= sizeBytes) return ByteArray(0)
        val target = minOf(maxBytes.toLong(), sizeBytes - fileOffset).toInt()
        val bytes = ByteArray(target)
        var total = 0
        while (total < target) {
            val read = readAt(fileOffset + total, bytes, total, target - total)
            if (read < 0) break
            if (read == 0) throw IOException("Random-access probe read made no progress")
            total += read
        }
        return if (total == bytes.size) bytes else bytes.copyOf(total)
    }

    private class RandomAccessProbeInputStream(
        private val source: AudioProbeRandomAccessSource,
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= source.sizeBytes) return -1
            val requested = minOf(length.toLong(), source.sizeBytes - position).toInt()
            val read = source.readAt(position, buffer, offset, requested)
            if (read == 0) throw IOException("Random-access probe stream made no progress")
            if (read > 0) position += read
            return read
        }
    }

    private fun java.io.InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped <= 0L) {
                if (read() < 0) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun java.nio.channels.FileChannel.readAt(offset: Long, byteCount: Int): ByteArray? {
        val buffer = java.nio.ByteBuffer.allocate(byteCount)
        position(offset)
        while (buffer.hasRemaining()) {
            val read = read(buffer)
            if (read < 0) break
        }
        val size = buffer.position()
        if (size <= 0) return null
        return buffer.array().copyOf(size)
    }

    private fun ByteArray.readUInt32Be(offset: Int): Long {
        if (offset + 4 > size) return 0L
        return ((this[offset].toLong() and 0xFF) shl 24) or
            ((this[offset + 1].toLong() and 0xFF) shl 16) or
            ((this[offset + 2].toLong() and 0xFF) shl 8) or
            (this[offset + 3].toLong() and 0xFF)
    }

    private fun ByteArray.readUInt64Be(offset: Int): Long {
        if (offset + 8 > size) return 0L
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
        }
        return value
    }

}

internal object Id3Binary {
    fun isId3Header(bytes: ByteArray, start: Int): Boolean =
        start + 3 <= bytes.size &&
            bytes[start] == 'I'.code.toByte() &&
            bytes[start + 1] == 'D'.code.toByte() &&
            bytes[start + 2] == '3'.code.toByte()

    fun extractTextAfterMarker(bytes: ByteArray, start: Int): String {
        val maxEnd = (start + 64 * 1024).coerceAtMost(bytes.size)
        var i = start
        while (i < maxEnd && bytes[i] == 0.toByte()) i++
        var end = i
        while (end < maxEnd) {
            if (bytes[end] == 0.toByte()) break
            end++
        }
        if (end <= i) return ""
        return LyricsEncoding.decodeBytes(bytes.copyOfRange(i, end))
    }

    fun decodeTextSlice(bytes: ByteArray, offset: Int, encoding: Int): String? {
        if (offset >= bytes.size) return null
        val end = when (encoding) {
            1, 2 -> bytes.size
            else -> {
                val fieldEnd = textFieldEnd(bytes, offset, encoding)
                if (fieldEnd <= offset) bytes.size else fieldEnd
            }
        }
        if (end <= offset) return null
        return LyricsEncoding.decodeId3Bytes(bytes.copyOfRange(offset, end), encoding)
            .takeIf { it.isNotEmpty() }
    }

    fun textFieldEnd(bytes: ByteArray, offset: Int, encoding: Int): Int {
        return when (encoding) {
            1, 2 -> {
                var i = offset
                while (i + 1 < bytes.size) {
                    if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
                    i++
                }
                bytes.size
            }
            else -> indexOfByte(bytes, 0.toByte(), offset).let { if (it < 0) bytes.size else it }
        }
    }

    fun deunsynchronize(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte()) {
                out.add(0.toByte())
                i += 2
            } else {
                out.add(data[i])
                i++
            }
        }
        return out.toByteArray()
    }

    fun synchsafeSize(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    fun readUInt32Be(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    fun readUInt32Le(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    fun readUInt24(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from >= bytes.size) return -1
        outer@ for (i in from..bytes.size - needle.size) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun indexOfByte(bytes: ByteArray, byte: Byte, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == byte) return i
        return -1
    }
}

