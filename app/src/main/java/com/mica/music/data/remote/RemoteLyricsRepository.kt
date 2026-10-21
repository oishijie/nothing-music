package com.mica.music.data.remote

import android.content.Context
import com.mica.music.data.LyricsDocument
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.remote.navidrome.NavidromeHttpExecutor
import com.mica.music.data.remote.navidrome.NavidromeLyricsLoader
import com.mica.music.data.remote.navidrome.NavidromeRequestFactory
import com.mica.music.data.remote.navidrome.UrlConnectionNavidromeHttpExecutor
import com.mica.music.data.remote.smb.SmbLyricsLoader
import com.mica.music.data.remote.aliyundrive.AliyunDriveLyricsLoader
import com.mica.music.data.remote.aliyundrive.AliyunDriveStreamRequestResolver
import com.mica.music.data.remote.alist.AListLyricsLoader
import com.mica.music.data.remote.smb.SmbStreamRequestResolver
import com.mica.music.data.remote.webdav.WebDavLyricsLoader
import com.mica.music.data.remote.webdav.WebDavStreamRequestResolver
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CancellationException

/** On-demand remote lyric hydration. Remote catalog synchronization never downloads lyric payloads. */
class RemoteLyricsRepository internal constructor(
    private val catalogRepository: RemoteCatalogRepository,
    credentialStore: MutableSecureRemoteCredentialStore,
    context: Context? = null,
    navidromeExecutor: NavidromeHttpExecutor = UrlConnectionNavidromeHttpExecutor(),
    navidromeRequestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
    private val persistentCache: RemoteLyricsDiskCache? = context?.applicationContext?.cacheDir
        ?.resolve("remote_lyrics")
        ?.let(::RemoteLyricsDiskCache),
) {
    private val navidromeLoader = NavidromeLyricsLoader(
        catalogRepository = catalogRepository,
        credentialStore = credentialStore,
        executor = navidromeExecutor,
        requestFactory = navidromeRequestFactory,
    )
    private val embeddedLyricsLoader = context?.applicationContext?.let(::AndroidJavaEmbeddedLyricsLoader)
    private val webDavLoader = embeddedLyricsLoader?.let { embedded ->
        WebDavLyricsLoader(
            requestResolver = WebDavStreamRequestResolver(catalogRepository::sourceOwner, credentialStore),
            embeddedLoader = embedded,
        )
    }
    private val smbLoader = embeddedLyricsLoader?.let { embedded ->
        SmbLyricsLoader(
            requestResolver = SmbStreamRequestResolver(catalogRepository::sourceOwner, credentialStore),
            embeddedLoader = embedded,
        )
    }
    private val aliyunDriveLoader = embeddedLyricsLoader?.let { embedded ->
        AliyunDriveLyricsLoader(
            requestResolver = AliyunDriveStreamRequestResolver(
                sourceOwnerById = catalogRepository::sourceOwner,
                credentialStore = credentialStore,
            ),
            embeddedLoader = embedded,
        )
    }
    private val aListLoader = embeddedLyricsLoader?.let { embedded ->
        AListLyricsLoader(
            sourceOwnerById = catalogRepository::sourceOwner,
            credentialStore = credentialStore,
            embeddedLoader = embedded,
        )
    }

    suspend fun songWithLyrics(song: Song, isPrefetch: Boolean = false): Song {
        if (song.source != SongSource.REMOTE || song.lyricsLoaded) return song
        val ref = RemoteMediaIdCodec.decode(song.id)
            ?: return song.copy(lyricsDocument = LyricsDocument(), lyricsLoaded = true)
        val status = catalogRepository.sourceStatus(ref.sourceInstanceId)
            ?: return song.copy(lyricsDocument = LyricsDocument(), lyricsLoaded = true)
        if (!status.instance.enabled) {
            return song.copy(lyricsDocument = LyricsDocument(), lyricsLoaded = true)
        }
        if (isPrefetch && status.instance.type == RemoteSourceType.SMB) return song
        val persistedTrack = catalogRepository.find(listOf(ref))[ref]
        val payloadRevision = persistedTrack?.lyricsRevision
            ?.takeIf(String::isNotBlank)
            ?: persistedTrack?.contentRevision.orEmpty()
        val persistentRevisionReady =
            status.instance.type != RemoteSourceType.SMB || persistedTrack?.lyricsRevision?.isNotBlank() == true
        val revision = buildString {
            append("remote-lyrics-v2:")
            append(status.instance.type.name)
            append(':')
            append(status.configRevision)
            append(':')
            append(status.catalogRevision)
            append(':')
            append(song.lyricsCacheRevision)
            append(':')
            append(payloadRevision)
        }
        val document = try {
            SharedLyricsMemoryCache.load(
                songId = song.id,
                revision = revision,
                lyricsDataVersion = REMOTE_LYRICS_DATA_VERSION,
                isPrefetch = isPrefetch,
            ) {
                if (persistentRevisionReady) {
                    persistentCache?.get(song.id, revision, REMOTE_LYRICS_DATA_VERSION)?.let {
                        return@load it
                    }
                }
                val loaded = when (status.instance.type) {
                    RemoteSourceType.NAVIDROME -> navidromeLoader.load(song)
                    RemoteSourceType.WEBDAV -> webDavLoader?.load(song) ?: LyricsDocument()
                    RemoteSourceType.SMB -> com.mica.music.data.remote.smb.SmbOptionalIo.runWhenPlaybackReady(
                        sourceId = ref.sourceInstanceId,
                        mediaId = song.id,
                    ) {
                        smbLoader?.load(song) ?: LyricsDocument()
                    }
                    RemoteSourceType.ALIYUNDRIVE -> aliyunDriveLoader?.load(song) ?: LyricsDocument()
                    RemoteSourceType.ALIST -> aListLoader?.load(song) ?: LyricsDocument()
                }
                // 空歌词不落盘缓存：可能是网络抖动，下次播放必须重试，
                // 否则一次失败就把该曲永久锁死为「无歌词」。
                if (loaded.lines.isNotEmpty() && persistentRevisionReady) {
                    persistentCache?.put(song.id, revision, REMOTE_LYRICS_DATA_VERSION, loaded)
                }
                loaded
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DiagnosticLog.event(
                "RemoteLyrics",
                "load-failed source=${ref.sourceInstanceId} song=${song.id.takeLast(12)} " +
                    "error=${error.javaClass.simpleName}",
            )
            return song
        }
        // 不让空歌词占住内存缓存，否则本次会话内永远不会再尝试。
        if (document.lines.isEmpty()) {
            SharedLyricsMemoryCache.invalidateSongs(listOf(song.id))
        }
        return song.copy(lyricsDocument = document, lyricsLoaded = true)
    }

    private companion object {
        const val REMOTE_LYRICS_DATA_VERSION = 3
    }
}
