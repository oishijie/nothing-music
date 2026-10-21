package com.nothingmusic.data.lyrics

import android.content.Context
import android.net.Uri
import com.mica.music.RemoteRuntime
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricTextRole
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.data.scanner.EmbeddedLyricsReader
import com.mica.music.data.scanner.EmbeddedLyricsResolver
import com.mica.music.data.scanner.JAudioTaggerReader
import com.nothingmusic.domain.model.Lyrics
import com.nothingmusic.domain.model.LyricsLine
import com.nothingmusic.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一歌词入口，按可靠性从高到低尝试：
 * 1. jaudiotagger 读标签文本（MP3 的 USLT/SYLT、FLAC、M4A 等）——最可靠，Mica 远程链路同款做法
 * 2. EmbeddedLyricsReader 全量读取（含同目录旁挂 .lrc + 二进制帧解析）
 * 3. 本项目自有的 [LyricsParser]（纯旁挂 .lrc）作为最后回退
 *
 * 远程曲目改走 RemoteLyricsRepository，由各来源的 transport 负责取歌词。
 */
object LrcLyricsResolver {

    suspend fun resolve(context: Context, track: Track): Lyrics =
        withContext(Dispatchers.IO) {
            val document = if (track.isRemote) {
                remoteDocument(track)
            } else {
                localDocument(context, track)
            }
            val lines = document?.takeIf { it.lines.isNotEmpty() }?.toLyricsLines()
            val result = when {
                !lines.isNullOrEmpty() -> Lyrics(lines = lines)
                else -> LyricsParser.parse(track).takeIf { it.lines.isNotEmpty() }
                    ?: (lines?.let { Lyrics(lines = it) } ?: Lyrics.Empty)
            }
            android.util.Log.d(
                TAG,
                "resolve title=\"${track.title}\" remote=${track.isRemote} " +
                    "path=${track.path} docLines=${document?.lines?.size ?: 0} " +
                    "final=${result.lines.size}",
            )
            result
        }

    private fun localDocument(context: Context, track: Track): LyricsDocument? {
        val path = track.path
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) {
            android.util.Log.d(TAG, "local: file missing path=$path")
            return null
        }

        // 1) 标签文本优先：jaudiotagger 直接读 USLT 等帧，对 MP3 命中率最高。
        val tagDoc = runCatching {
            val tags = JAudioTaggerReader.read(context, Uri.fromFile(file))
            android.util.Log.d(
                TAG,
                "local: jat tags=${tags != null} " +
                    "candidates=${tags?.lyricsCandidates?.size ?: 0}",
            )
            if (tags != null) {
                EmbeddedLyricsResolver.selectTagTextCandidate(
                    candidates = tags.lyricsCandidates,
                    parse = EmbeddedLyricsReader::parseTagTextDocument,
                )
            } else {
                null
            }
        }.onFailure {
            android.util.Log.d(TAG, "local: jat failed ${it.javaClass.simpleName}")
        }.getOrNull()

        if (tagDoc != null && tagDoc.lines.isNotEmpty()) {
            android.util.Log.d(TAG, "local: tag hit lines=${tagDoc.lines.size}")
            return tagDoc
        }

        // 2) 全量读取：旁挂 .lrc + 二进制帧解析
        val full = runCatching {
            EmbeddedLyricsReader.readDocument(
                context = context,
                uri = Uri.fromFile(file),
                mimeType = track.mimeType,
                displayName = file.name,
                filePath = path,
            )
        }.onFailure {
            android.util.Log.d(TAG, "local: full read failed ${it.javaClass.simpleName}")
        }.getOrNull()

        android.util.Log.d(TAG, "local: full lines=${full?.lines?.size ?: 0}")
        return full?.takeIf { it.lines.isNotEmpty() }
    }

    private suspend fun remoteDocument(track: Track): LyricsDocument? {
        val mediaId = track.remoteId ?: return null
        val ref = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val summary = RemoteRuntime.catalogRepository.find(listOf(ref))[ref] ?: return null
        val hydrated = RemoteRuntime.lyricsRepository.songWithLyrics(summary.toPlaybackSong())
        hydrated.lyricsDocument?.takeIf { it.lines.isNotEmpty() }?.let { return it }
        // 内嵌/旁挂都没有：按标题+艺术家去 LRCLIB 在线歌词库搜（对齐棉花的刮削策略）。
        // 文件名派生的标题带着「艺术家 - 」前缀时先剥掉，提高命中率。
        val searchTitle = summary.title
            .takeIf { summary.artist.isNotBlank() && it.startsWith(summary.artist, ignoreCase = true) }
            ?.removePrefix(summary.artist)?.trim()?.removePrefix("-")?.trim()
            ?: summary.title
        return LrcLibLyricsProvider.fetch(searchTitle, summary.artist, summary.durationSec)
    }

    private const val TAG = "LrcResolver"

    private fun LyricsDocument.toLyricsLines(): List<LyricsLine> = lines.map { node ->
        // 只取原文与译文，过滤读音轨；双语渲染留待后续。
        val text = node.parts
            .filter { it.role == LyricTextRole.ORIGINAL || it.role == LyricTextRole.TRANSLATION }
            .joinToString(" ") { it.text }
            .trim()
        LyricsLine(timestampMs = node.startMs.toLong(), text = text)
    }.sortedBy { it.timestampMs }
}
