package com.mica.music.data.remote.alist

import com.mica.music.data.remote.SeekableByteSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AList raw_url 的有限 Range 随机读取源，只供元数据/内嵌封面探测使用。
 * 网盘直链与 AList 自身代理（/p）都要求 206 部分响应；认证头仅在解析时附带，
 * OkHttp 跨主机重定向会自动丢弃 Authorization，不会把登录态泄露给网盘。
 */
internal class AListSeekableByteSource(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    override val sizeBytes: Long,
    private val requestHeaders: Map<String, String> = emptyMap(),
) : SeekableByteSource {
    private val closed = AtomicBoolean(false)

    init {
        require(sizeBytes >= 0L) { "AList source size must not be negative" }
    }

    override fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int {
        check(!closed.get()) { "AList byte source is closed" }
        require(fileOffset >= 0L) { "fileOffset must not be negative" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset + length <= buffer.size) {
            "Invalid destination range"
        }
        if (length == 0) return 0
        if (fileOffset >= sizeBytes) return -1

        val requested = minOf(length.toLong(), sizeBytes - fileOffset).toInt()
        val endInclusive = fileOffset + requested - 1L
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=$fileOffset-$endInclusive")
            .header("Accept-Encoding", "identity")
            .get()
        requestHeaders.forEach { (name, value) -> builder.header(name, value) }
        try {
            client.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw IOException("AList range request was not authorized (HTTP ${response.code})")
                    206 -> Unit
                    else -> throw IOException("AList range request requires HTTP 206, got ${response.code}")
                }
                val body = response.body ?: throw IOException("AList range response had no body")
                val input = body.byteStream()
                var total = 0
                while (total < requested) {
                    val count = input.read(buffer, bufferOffset + total, requested - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total != requested) {
                    throw IOException("AList range response ended before the requested window")
                }
                return total
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("AList range request failed", failure)
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
