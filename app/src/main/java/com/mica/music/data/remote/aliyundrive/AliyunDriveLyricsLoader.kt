package com.mica.music.data.remote.aliyundrive

import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSlots
import com.mica.music.data.Song
import com.mica.music.data.remote.ReadAheadSeekableByteSource
import com.mica.music.data.remote.RemoteEmbeddedLyricsLoader
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 按需读取阿里云盘曲目内嵌歌词。目录同步从不下载歌词载荷。
 *
 * 只读内嵌：同目录的 .lrc/.ttml 旁挂文件需要逐目录列目录，
 * 当前目录扫描结果里没有保存父目录信息，先不做。
 */
internal class AliyunDriveLyricsLoader(
    private val requestResolver: RemoteHttpPlaybackRequestResolver,
    private val embeddedLoader: RemoteEmbeddedLyricsLoader,
) {
    private val client = AliyunDriveSeekableByteSource.openClient()

    suspend fun load(song: Song): LyricsDocument {
        val request = requestResolver.resolve(song.id) ?: return LyricsDocument()
        if (song.sizeBytes <= 0L) return LyricsDocument()
        val httpUrl = request.url.toHttpUrlOrNull() ?: throw IOException("Invalid AliyunDrive lyrics track URL")
        return withContext(Dispatchers.IO) {
            val embedded = AliyunDriveSeekableByteSource(client, httpUrl, song.sizeBytes).use { source ->
                // 直链 Range 每次一个完整 HTTPS 往返；不套读合并层，jaudiotagger
                // 的几十个小读会超出歌词加载的 10s 超时（封面能出而歌词超时的根因）。
                ReadAheadSeekableByteSource(source, readAheadBytes = 64 * 1024).use { buffered ->
                    embeddedLoader.load(buffered, song.fileName, song.metadata.playbackMimeType)
                }
            }
            LyricsSlots(embedded = embedded).selected()
        }
    }
}
