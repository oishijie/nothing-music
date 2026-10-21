package com.mica.music.data.remote.aliyundrive

import android.content.Context
import com.mica.music.data.remote.AndroidJavaEmbeddedArtworkLoader
import com.mica.music.data.remote.DEFAULT_REMOTE_ARTWORK_MAX_BYTES
import com.mica.music.data.remote.SeekableByteSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 阿里云盘签名直链的有限 Range 随机读取源，只供元数据/内嵌封面探测使用。
 * 通常套在共享 read-ahead 层之后，把 TagLib 的密集小读合并成一次 Range 请求。
 */
internal class AliyunDriveSeekableByteSource(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    override val sizeBytes: Long,
) : SeekableByteSource {
    private val closed = AtomicBoolean(false)

    init {
        require(sizeBytes >= 0L) { "AliyunDrive source size must not be negative" }
    }

    override fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int {
        check(!closed.get()) { "AliyunDrive byte source is closed" }
        require(fileOffset >= 0L) { "fileOffset must not be negative" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset + length <= buffer.size) {
            "Invalid destination range"
        }
        if (length == 0) return 0
        if (fileOffset >= sizeBytes) return -1

        val requested = minOf(length.toLong(), sizeBytes - fileOffset).toInt()
        val endInclusive = fileOffset + requested - 1L
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$fileOffset-$endInclusive")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw IOException("AliyunDrive range request was not authorized (HTTP ${response.code})")
                    206 -> Unit
                    else -> throw IOException("AliyunDrive range request requires HTTP 206, got ${response.code}")
                }
                val body = response.body ?: throw IOException("AliyunDrive range response had no body")
                val input = body.byteStream()
                var total = 0
                while (total < requested) {
                    val count = input.read(buffer, bufferOffset + total, requested - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total != requested) {
                    throw IOException("AliyunDrive range response ended before the requested window")
                }
                return total
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("AliyunDrive range request failed", failure)
        }
    }

    override fun close() {
        closed.set(true)
    }

    companion object {
        fun openClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** 直链有时效，不写进目录；解析结果由 client 侧内存缓存复用，重复探测不会重复换取。 */
internal fun interface AliyunDriveTrackUrlResolver {
    suspend fun downloadUrlFor(opaqueTrackId: String): String?
}

/** 内嵌封面读取：解析直链后用 Range 读 TagLib 图片帧。 */
internal class AliyunDriveEmbeddedArtworkByteLoader(
    context: Context,
    private val client: OkHttpClient = AliyunDriveSeekableByteSource.openClient(),
    private val maxBytes: Long = DEFAULT_REMOTE_ARTWORK_MAX_BYTES,
) {
    private val loader = AndroidJavaEmbeddedArtworkLoader(context)

    fun load(url: String, sizeBytes: Long): ByteArray {
        require(sizeBytes > 0L) { "AliyunDrive artwork source size is unavailable" }
        if (sizeBytes > maxBytes) throw IOException("AliyunDrive artwork source exceeds size limit")
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("Invalid AliyunDrive download URL")
        return AliyunDriveSeekableByteSource(client, httpUrl, sizeBytes).use(loader::load)
    }
}
