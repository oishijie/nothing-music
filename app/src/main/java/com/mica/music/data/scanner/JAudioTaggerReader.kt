package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mica.music.data.ReleaseDates
import com.mica.music.data.ReplayGainTags
import com.mica.music.data.remote.ReadAheadSeekableByteSource
import com.mica.music.data.remote.SeekableByteSource
import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag

/**
 * 纯 Java 标签/封面/歌词/音频属性读取（jaudiotagger 3.0.1），替代 TagLib native。
 * 任何失败返回 null，由调用方回退 MediaMetadataRetriever。
 *
 * 远程场景：jaudiotagger 只接受 File，因此把 SeekableByteSource 的头尾窗口（足够覆盖
 * ID3v2 头部 / FLAC 元数据块 / MP4 moov / APE 尾部标签）落到 cacheDir 临时文件再解析。
 */
internal object JAudioTaggerReader {


    class Result(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val copyright: String,
        val comment: String,
        val year: Int,
        val releaseDate: String,
        val durationSec: Int,
        val sampleRateHz: Int,
        val bitrateKbps: Int,
        val channelCount: Int,
        /** 位深；0 表示未知，由 [AudioTechnicalProbe] 补全 */
        val bitsPerSample: Int,
        /** 0 表示未知 */
        val trackNumber: Int,
        /** 0 表示未知 */
        val discNumber: Int,
        val lyricsCandidates: List<EmbeddedLyricsTextCandidate>,
        val frontCoverBytes: ByteArray?,
        val hasPictures: Boolean,
        val replayGain: ReplayGainTags,
    )

    fun read(
        context: Context,
        uri: Uri,
    ): Result? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            readDescriptor(pfd)
        }
    }.getOrNull()

    internal fun readDescriptor(pfd: ParcelFileDescriptor): Result? = runCatching {
        // /proc/self/fd/<fd> 让 AudioFileIO 直接读已打开的描述符，无需复制整个文件。
        readFile(File("/proc/self/fd/${pfd.detachFd()}"))
    }.getOrNull()

    /**
     * 远程探测：jaudiotagger 只接受 File，且按扩展名选择解析器，因此把 SeekableByteSource 的
     * 头尾窗口（覆盖 ID3v2 头部 / FLAC 元数据块 / MP4 moov / APE 尾部标签）落到带真实扩展名的
     * 临时文件再解析。
     */
    internal fun readSeekable(
        source: SeekableByteSource,
        cacheDir: File,
        fileName: String? = null,
        maxHeadBytes: Long = MAX_REMOTE_METADATA_HEAD_BYTES,
        maxTailBytes: Long = MAX_REMOTE_METADATA_TAIL_BYTES,
    ): Result? = runCatching {
        val windowed = ReadAheadSeekableByteSource(source, readAheadBytes = 64 * 1024)
        val headBytes = source.sizeBytes.coerceAtMost(maxHeadBytes).toInt()
        val head = ByteArray(headBytes)
        var headRead = 0
        while (headRead < headBytes) {
            val n = windowed.readAt(headRead.toLong(), head, headRead, headBytes - headRead)
            if (n <= 0) break
            headRead += n
        }
        val tailStart = (source.sizeBytes - maxTailBytes).coerceAtLeast(headRead.toLong())
        val tailLength = (source.sizeBytes - tailStart).toInt().coerceAtLeast(0)
        val tail = ByteArray(tailLength)
        var tailRead = 0
        if (tailLength > 0 && tailStart > headRead) {
            while (tailRead < tailLength) {
                val n = windowed.readAt(tailStart + tailRead, tail, tailRead, tailLength - tailRead)
                if (n <= 0) break
                tailRead += n
            }
        }
        windowed.close()

        val tmp = File.createTempFile("jat_remote_", "." + resolveExtension(fileName, head, headRead), cacheDir)
        try {
            tmp.outputStream().use { out ->
                out.write(head, 0, headRead)
                // 中间音频载荷对标签解析无关，用零填充保持尾部标签的偏移正确。
                val gap = (tailStart - headRead).toInt()
                if (gap > 0) out.write(ByteArray(gap))
                out.write(tail, 0, tailRead)
            }
            readFile(tmp)
        } finally {
            runCatching { tmp.delete() }
        }
    }.getOrNull()

    private fun readFile(file: File): Result? {        val audioFile = AudioFileIO.read(file)
        val header = audioFile.audioHeader ?: return null
        val sampleRateHz = header.sampleRateAsNumber
        if (sampleRateHz <= 0) return null
        val tag = audioFile.tag
        val properties = mapOf(
            "REPLAYGAIN_TRACK_GAIN" to arrayOf(tag.firstValue("REPLAYGAIN_TRACK_GAIN").orEmpty()),
            "REPLAYGAIN_TRACK_PEAK" to arrayOf(tag.firstValue("REPLAYGAIN_TRACK_PEAK").orEmpty()),
            "REPLAYGAIN_ALBUM_GAIN" to arrayOf(tag.firstValue("REPLAYGAIN_ALBUM_GAIN").orEmpty()),
            "REPLAYGAIN_ALBUM_PEAK" to arrayOf(tag.firstValue("REPLAYGAIN_ALBUM_PEAK").orEmpty()),
        )
        val rawDate = tag.firstValue("DATE") ?: tag.firstValue(FieldKey.YEAR)
        val releaseDate = rawDate?.let { ReleaseDates.canonicalFullDate(it) }.orEmpty()
        val artwork = tag?.artworkList.orEmpty()
        val frontCover = artwork.firstOrNull { it.pictureType == FRONT_COVER_PICTURE_TYPE }
            ?: artwork.firstOrNull()
        return Result(
            title = tag.firstValue(FieldKey.TITLE).orEmpty(),
            artist = tag.firstValue(FieldKey.ARTIST).orEmpty(),
            album = tag.firstValue(FieldKey.ALBUM).orEmpty(),
            albumArtist = tag.firstValue(FieldKey.ALBUM_ARTIST).orEmpty(),
            copyright = tag.firstValue(FieldKey.COPYRIGHT).orEmpty(),
            comment = tag.firstValue(FieldKey.COMMENT).orEmpty(),
            year = ReleaseDates.yearFromFullDate(releaseDate).takeIf { it > 0 }
                ?: rawDate?.let(::parseYear).takeIf { it != null && it > 0 }
                ?: 0,
            releaseDate = releaseDate,
            durationSec = header.trackLength.coerceAtLeast(0),
            sampleRateHz = sampleRateHz,
            bitrateKbps = if (header.isVariableBitRate) 0 else header.bitRateAsNumber.toInt().coerceAtLeast(0),
            channelCount = header.channels.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            bitsPerSample = header.bitsPerSample.coerceAtLeast(0),
            trackNumber = MetadataTextFix.parseTrackNumber(tag.firstValue(FieldKey.TRACK)),
            discNumber = MetadataTextFix.parseDiscNumber(tag.firstValue(FieldKey.DISC_NO)),
            lyricsCandidates = lyricsCandidates(tag),
            frontCoverBytes = frontCover?.binaryData?.takeIf { it.isNotEmpty() },
            hasPictures = artwork.isNotEmpty(),
            replayGain = ReplayGainTags.fromProperties(properties),
        )
    }

    private fun Tag?.firstValue(key: FieldKey): String? =
        runCatching { this?.getFirst(key)?.trim()?.takeIf(String::isNotBlank) }.getOrNull()

    /** ReplayGain 在 Vorbis/FLAC 里是普通键；ID3 里为 TXXX 描述符，两种命名都试一次。 */
    private fun Tag?.firstValue(rawKey: String): String? {
        val keys = listOf(rawKey, "TXXX:$rawKey", "TXXX:$rawKey")
        for (key in keys) {
            runCatching { this?.getFirst(key)?.trim()?.takeIf(String::isNotBlank) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun lyricsCandidates(tag: Tag?): List<EmbeddedLyricsTextCandidate> {
        if (tag == null) return emptyList()
        val values = runCatching { tag.getAll(FieldKey.LYRICS) }.getOrNull().orEmpty()
        return values
            .filter(String::isNotBlank)
            .map { EmbeddedLyricsTextCandidate("LYRICS", it.trim()) }
    }

    private val yearRegex = Regex("""\d{4}""")

    private fun parseYear(raw: String): Int =
        yearRegex.find(raw)?.value?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /**
     * jaudiotagger 按扩展名选解析器：优先用文件名后缀，缺失或未知时嗅探文件头。
     * 猜不出来时回落 mp3（ID3 嗅探路径容错最好）。
     */
    private fun resolveExtension(fileName: String?, head: ByteArray, headLength: Int): String {
        val fromName = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName in KNOWN_EXTENSIONS) return fromName
        return sniffExtension(head, headLength)
    }

    private fun sniffExtension(bytes: ByteArray, length: Int): String {
        fun at(offset: Int): Int = if (offset < length) bytes[offset].toInt() and 0xFF else -1
        fun magic(start: Int, text: String): Boolean = text.indices.all { at(start + it) == text[it].code }
        return when {
            magic(0, "fLaC") -> "flac"
            magic(0, "OggS") -> "ogg"
            magic(0, "MAC ") -> "ape"
            magic(0, "DSD ") -> "dsf"
            magic(0, "RIFF") && magic(8, "WAVE") -> "wav"
            magic(0, "FORM") -> "aif"
            magic(4, "ftyp") -> "m4a"
            magic(0, "ID3") -> "mp3"
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "mp3"
            else -> "mp3"
        }
    }

    private val KNOWN_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "m4b", "mp4", "ogg", "oga", "opus", "wav", "aif", "aiff",
        "wma", "ape", "dsf", "aac",
    )

    /** ID3 APIC / FLAC PICTURE 的 Front Cover 类型值。 */
    private const val FRONT_COVER_PICTURE_TYPE = 3

    // 元数据探测只需文本标签（ID3 头部 / FLAC Vorbis 块 / APE 尾部），实际都在几百 KB 内。
    // 原先统一取 16MB 头 + 8MB 尾，一首歌下载 24MB 只为读几 KB 标签——
    // 这是整库同步慢与流量消耗的主因，故元数据用小窗口。
    private const val MAX_REMOTE_METADATA_HEAD_BYTES = 512L * 1024
    private const val MAX_REMOTE_METADATA_TAIL_BYTES = 256L * 1024

    // 内嵌封面可能有几百 KB 到数 MB，按需加载时单独用大窗口，与元数据探测分开。
    internal const val MAX_REMOTE_ARTWORK_HEAD_BYTES = 16L * 1024 * 1024
    internal const val MAX_REMOTE_ARTWORK_TAIL_BYTES = 8L * 1024 * 1024

    /** 内嵌封面读取窗口：封面数据比文本标签大得多，需单独放宽。 */
    internal val ARTWORK_WINDOW: Pair<Long, Long>
        get() = MAX_REMOTE_ARTWORK_HEAD_BYTES to MAX_REMOTE_ARTWORK_TAIL_BYTES
}
