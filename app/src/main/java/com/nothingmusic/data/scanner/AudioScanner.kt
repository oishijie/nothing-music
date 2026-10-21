package com.nothingmusic.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.scanner.JAudioTaggerReader
import com.nothingmusic.data.artwork.FolderArtwork
import com.nothingmusic.data.artwork.LocalArtworkStore
import com.nothingmusic.domain.model.Track
import com.nothingmusic.util.Constants
import com.nothingmusic.util.SafUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 扫描器元数据逻辑版本。新增/修改标签或封面解析后递增，旧缓存行会在下次扫描时重读。
 */
internal const val SCANNER_METADATA_VERSION = 1

/**
 * 基于 SAF 的本地扫描：遍历用户授权的目录树，文档 URI 直接作为播放 URI。
 * 已缓存的曲目按大小 + 修改时间跳过标签解析，只有新增/变更文件才会走 jaudiotagger。
 * 封面优先取内嵌图片（按内容哈希落盘），缺失时回退同目录的 cover/folder 等侧挂图片。
 */
@Singleton
class AudioScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val artworkStore: LocalArtworkStore,
) {

    data class FolderScan(
        val folderUri: String,
        /** false 表示授权失效或目录不可读，调用方应保留该目录的既有曲目。 */
        val accessible: Boolean,
        val tracks: List<Track>,
    )

    suspend fun scanFolders(
        folderUris: List<String>,
        known: Map<String, Track>,
    ): List<FolderScan> = withContext(Dispatchers.IO) {
        folderUris.distinct().map { folderUri ->
            ensureActive()
            val root = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            }.getOrNull()
            if (root == null || !root.exists() || !root.isDirectory || !root.canRead() ||
                !hasPersistedRead(folderUri)
            ) {
                FolderScan(folderUri = folderUri, accessible = false, tracks = emptyList())
            } else {
                val collected = mutableListOf<Track>()
                val complete = walk(root, folderUri, known, collected, depth = 0)
                FolderScan(folderUri = folderUri, accessible = complete, tracks = collected)
            }
        }
    }

    /** 授权被系统回收后 provider 可能返回空列表而非抛错，先确认持久化读权限仍在。 */
    private fun hasPersistedRead(folderUri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri.toString() == folderUri
        }

    /** 返回 false 表示有目录读取失败，调用方应保留该目录的缓存而不是按空结果清理。 */
    private suspend fun walk(
        directory: DocumentFile,
        folderUri: String,
        known: Map<String, Track>,
        out: MutableList<Track>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_DEPTH) return true
        val children = runCatching { directory.listFiles() }.getOrNull() ?: return false
        val folderCover = folderCoverUri(children)
        var complete = true
        for (child in children) {
            currentCoroutineContext().ensureActive()
            when {
                child.isDirectory -> {
                    if (!walk(child, folderUri, known, out, depth + 1)) complete = false
                }
                child.isFile && isSupported(child) -> {
                    val uri = child.uri.toString()
                    val cached = known[uri]
                    val size = child.length()
                    val lastModified = child.lastModified()
                    // 之前没有封面但现在目录里出现了 cover 时也要重读一次。
                    val reusable = cached != null &&
                        lastModified > 0 &&
                        cached.lastModified == lastModified &&
                        cached.sizeBytes == size &&
                        (cached.albumArtUri != null || folderCover == null)
                    out += if (reusable) {
                        cached
                    } else {
                        readTrack(child, folderUri, uri, size, lastModified, folderCover)
                    }
                }
            }
        }
        return complete
    }

    /** 侧挂封面带上修改时间，文件被替换后 Coil 的 URI 缓存会自然失效。 */
    private fun folderCoverUri(children: Array<DocumentFile>): String? {
        val name = FolderArtwork.pick(children.mapNotNull { it.name }) ?: return null
        val cover = children.firstOrNull { it.isFile && it.name == name } ?: return null
        return cover.uri.buildUpon()
            .appendQueryParameter("v", cover.lastModified().toString())
            .build()
            .toString()
    }

    private fun readTrack(
        document: DocumentFile,
        folderUri: String,
        uri: String,
        size: Long,
        lastModified: Long,
        folderArtworkUri: String?,
    ): Track {
        val name = document.name ?: "audio"
        val extension = name.substringAfterLast('.', "").lowercase()
        val tags = runCatching { JAudioTaggerReader.read(context, document.uri) }.getOrNull()
        val durationMs = tags?.durationSec?.takeIf { it > 0 }?.times(1000L)
            ?: readDurationMs(document.uri)
        val embeddedArtwork = tags?.frontCoverBytes?.let(artworkStore::store)?.toString()
        return Track(
            id = SafUtils.stableTrackId(uri),
            title = tags?.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
            artist = tags?.artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST,
            album = tags?.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM,
            durationMs = durationMs,
            path = uri,
            albumArtUri = (embeddedArtwork ?: folderArtworkUri)?.let(Uri::parse),
            folderPath = folderUri,
            dateAdded = lastModified / 1000,
            trackNumber = tags?.trackNumber ?: 0,
            year = tags?.year ?: 0,
            mimeType = document.type ?: mimeTypeFor(extension),
            sizeBytes = size,
            sampleRate = tags?.sampleRateHz ?: 0,
            bitrate = tags?.bitrateKbps ?: 0,
            lastModified = lastModified,
            documentName = name,
        )
    }

    private fun readDurationMs(uri: Uri): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    private fun isSupported(document: DocumentFile): Boolean {
        val extension = document.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (extension in Constants.SUPPORTED_AUDIO_EXTENSIONS) return true
        val mimeType = runCatching { document.type }.getOrNull()
        return mimeType != null && mimeType in Constants.SUPPORTED_MIME_TYPES
    }

    private fun mimeTypeFor(extension: String): String = when (extension) {
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "m4a", "mp4", "alac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        "wma" -> "audio/x-ms-wma"
        "dsf" -> "audio/x-dsf"
        "dff" -> "audio/x-dff"
        else -> "audio/*"
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
    }
}
