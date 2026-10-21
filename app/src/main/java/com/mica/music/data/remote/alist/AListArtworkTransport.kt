package com.mica.music.data.remote.alist

import android.content.Context
import com.mica.music.data.remote.AndroidJavaEmbeddedArtworkLoader
import com.mica.music.data.remote.DEFAULT_REMOTE_ARTWORK_MAX_BYTES
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Request

internal data class AListSidecarArtworkRequest(
    val url: HttpUrl,
    val headers: Map<String, String>,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
)

internal data class AListEmbeddedArtworkRequest(
    val url: HttpUrl,
    val headers: Map<String, String>,
    val sizeBytes: Long,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
) {
    override fun toString(): String =
        "AListEmbeddedArtworkRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, " +
            "sizeBytes=$sizeBytes, url=<redacted>, headers=<redacted>)"
}

/** 旁挂图片（同名图 / cover.jpg 等）解析：opaque id 里的路径 -> fs/get -> raw_url。 */
internal class AListSidecarArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun resolve(ref: RemoteArtworkRef): AListSidecarArtworkRequest? {
        val target = RemoteFileArtworkIdCodec.decode(ref.opaqueArtworkId) ?: return null
        val context = resolveAListSourceContext(ref.sourceInstanceId, sourceOwnerById, credentialStore)
            ?: return null
        val detail = try {
            context.client.fileInfo(target.resourceId)
        } catch (_: AListException) {
            return null
        }
        if (detail.isDir) return null
        val url = context.client.resolveRawUrl(detail) ?: return null
        if (!context.isCurrent()) return null
        return AListSidecarArtworkRequest(
            url = url,
            headers = context.client.playbackHeaders(detail),
            sourceInstanceId = context.sourceId,
            sourceConfigRevision = context.configRevision,
            credentialRevision = context.credentialRevision,
        )
    }
}

/** 内嵌封面解析：音频文件的 raw_url 走 Range 读，尺寸来自同步时记录的 embedded-v1 id。 */
internal class AListEmbeddedArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun resolve(ref: RemoteArtworkRef): AListEmbeddedArtworkRequest? {
        val target = RemoteEmbeddedArtworkIdCodec.decode(ref.opaqueArtworkId) ?: return null
        val context = resolveAListSourceContext(ref.sourceInstanceId, sourceOwnerById, credentialStore)
            ?: return null
        val detail = try {
            context.client.fileInfo(target.resourceId)
        } catch (_: AListException) {
            return null
        }
        if (detail.isDir) return null
        val url = context.client.resolveRawUrl(detail) ?: return null
        if (!context.isCurrent()) return null
        return AListEmbeddedArtworkRequest(
            url = url,
            headers = context.client.playbackHeaders(detail),
            sizeBytes = target.sizeBytes,
            sourceInstanceId = context.sourceId,
            sourceConfigRevision = context.configRevision,
            credentialRevision = context.credentialRevision,
        )
    }
}

internal class AListArtworkByteLoader(
    private val maxBytes: Long = DEFAULT_REMOTE_ARTWORK_MAX_BYTES,
) {
    init {
        require(maxBytes >= 0L) { "AList artwork byte limit must be non-negative" }
    }

    fun load(request: AListSidecarArtworkRequest): ByteArray {
        val builder = Request.Builder()
            .url(request.url)
            .header("Accept-Encoding", "identity")
            .get()
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        try {
            AListSeekableByteSource.openClient().newCall(builder.build()).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw IOException("AList artwork authentication failed")
                }
                if (response.code != 200) {
                    throw IOException("AList artwork HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("AList artwork response had no body")
                val declaredLength = body.contentLength()
                if (declaredLength > maxBytes) throw IOException("AList artwork exceeds size limit")
                val initialCapacity = declaredLength
                    .takeIf { it in 1..minOf(maxBytes, Int.MAX_VALUE.toLong()) }
                    ?.toInt()
                    ?: DEFAULT_BUFFER_SIZE
                return ByteArrayOutputStream(initialCapacity).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > maxBytes) throw IOException("AList artwork exceeds size limit")
                            output.write(buffer, 0, read)
                        }
                    }
                    output.toByteArray()
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("AList artwork read failed", failure)
        }
    }
}

internal class AListEmbeddedArtworkByteLoader(
    context: Context,
    private val client: okhttp3.OkHttpClient = AListSeekableByteSource.openClient(),
) {
    private val embeddedLoader = AndroidJavaEmbeddedArtworkLoader(context)

    fun load(request: AListEmbeddedArtworkRequest): ByteArray {
        require(request.sizeBytes > 0L) { "AList artwork source size is unavailable" }
        return AListSeekableByteSource(
            client = client,
            url = request.url,
            sizeBytes = request.sizeBytes,
            requestHeaders = request.headers,
        ).use(embeddedLoader::load)
    }
}

private const val DEFAULT_BUFFER_SIZE = 8 * 1024
