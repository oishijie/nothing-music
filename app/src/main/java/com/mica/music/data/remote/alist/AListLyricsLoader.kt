package com.mica.music.data.remote.alist

import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSlots
import com.mica.music.data.Song
import com.mica.music.data.remote.ReadAheadSeekableByteSource
import com.mica.music.data.remote.RemoteEmbeddedLyricsLoader
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.remote.alist.AListPathCodec.siblingPath
import com.mica.music.data.scanner.ExternalLyricsReader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/**
 * AList 歌词按需加载：优先同目录同名 .ttml/.lrc 旁挂（走 AList 路径而非直链推导，
 * 因为 raw_url 可能是网盘签名链接，兄弟路径无法从它推导），再回退音频内嵌歌词。
 * 目录同步从不下载歌词载荷。
 */
internal class AListLyricsLoader(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
    private val embeddedLoader: RemoteEmbeddedLyricsLoader,
) {
    suspend fun load(song: Song): LyricsDocument {
        val trackRef = RemoteMediaIdCodec.decode(song.id) ?: return LyricsDocument()
        val context = resolveAListSourceContext(trackRef.sourceInstanceId, sourceOwnerById, credentialStore)
            ?: return LyricsDocument()
        return withContext(Dispatchers.IO) {
            val audioPath = trackRef.opaqueTrackId
            val audioName = song.fileName.ifBlank { audioPath.substringAfterLast('/') }
            val baseName = audioName.substringBeforeLast('.').trim()
            if (baseName.isNotEmpty()) {
                firstSidecarDocument(context, audioPath, baseName)?.let { return@withContext it }
            }

            val detail = context.client.fileInfo(audioPath)
            if (detail.isDir) throw IOException("AList lyrics track path is a directory")
            if (detail.size <= 0L) throw IOException("AList embedded lyrics source size is unavailable")
            val url = context.client.resolveRawUrl(detail)
                ?: throw IOException("Invalid AList lyrics track URL")
            val embedded = AListSeekableByteSource(
                client = AListSeekableByteSource.openClient(),
                url = url,
                sizeBytes = detail.size,
                requestHeaders = context.client.playbackHeaders(detail),
            ).use { source ->
                // 每个 Range 读都是一个完整请求，必须合并小读，否则超出歌词加载超时。
                ReadAheadSeekableByteSource(source, readAheadBytes = 64 * 1024).use { buffered ->
                    embeddedLoader.load(buffered, song.fileName, song.metadata.playbackMimeType)
                }
            }
            LyricsSlots(embedded = embedded).selected()
        }
    }

    private suspend fun firstSidecarDocument(
        context: AListSourceContext,
        audioPath: String,
        baseName: String,
    ): LyricsDocument? {
        // 与 WebDAV 同序：TTML 优先，LRC 其后；每个扩展名先试小写再试大写。
        for (extension in listOf("ttml", "TTML", "lrc", "LRC")) {
            val path = siblingPath(audioPath, "$baseName.$extension")
            when (val result = readSidecar(context, path)) {
                SidecarResult.Missing -> Unit
                is SidecarResult.Found -> result.document?.let { document ->
                    return if (extension.equals("ttml", ignoreCase = true)) {
                        LyricsSlots(externalTtml = document).selected()
                    } else {
                        LyricsSlots(externalLrc = document).selected()
                    }
                }
            }
        }
        return null
    }

    private suspend fun readSidecar(context: AListSourceContext, path: String): SidecarResult {
        val detail = try {
            context.client.fileInfo(path)
        } catch (failure: AListException) {
            if (failure.kind == AListFailureKind.AUTH) throw failure
            return SidecarResult.Missing
        }
        if (detail.isDir || detail.size <= 0L) return SidecarResult.Missing
        if (detail.size > ExternalLyricsReader.MAX_EXTERNAL_LYRICS_BYTES) {
            throw IOException("AList lyrics exceeds size limit")
        }
        val url: HttpUrl = context.client.resolveRawUrl(detail)
            ?: throw IOException("Invalid AList lyrics URL")
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .get()
        context.client.playbackHeaders(detail).forEach { (name, value) -> builder.header(name, value) }
        try {
            AListSeekableByteSource.openClient().newCall(builder.build()).execute().use { response ->
                if (response.code == 404) return SidecarResult.Missing
                if (response.code == 401 || response.code == 403) {
                    throw IOException("AList lyrics authentication failed")
                }
                if (response.code != 200) throw IOException("AList lyrics HTTP ${response.code}")
                val body = response.body ?: throw IOException("AList lyrics response had no body")
                val bytes = body.byteStream().use(ExternalLyricsReader::readBoundedLyricsBytes)
                    ?: throw IOException("AList lyrics exceeds size limit")
                return SidecarResult.Found(ExternalLyricsReader.parseRemoteDocument(bytes))
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("AList lyrics read failed", failure)
        }
    }

    private sealed interface SidecarResult {
        data object Missing : SidecarResult
        data class Found(val document: LyricsDocument?) : SidecarResult
    }
}
