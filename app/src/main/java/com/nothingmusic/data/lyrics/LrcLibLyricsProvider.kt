package com.nothingmusic.data.lyrics

import com.mica.music.data.LyricsDocument
import com.mica.music.data.scanner.ExternalLyricsReader
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * LRCLIB 在线歌词回退（对齐棉花音乐的在线刮削策略）：
 * 网盘歌曲大多没有内嵌歌词，内嵌/旁挂都拿不到时按「标题+艺术家」去 lrclib.net 搜。
 * 免费、无需鉴权，返回标准 LRC（syncedLyrics）或纯文本（plainLyrics）。
 */
internal object LrcLibLyricsProvider {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(title: String, artist: String, durationSec: Int): LyricsDocument? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NothingMusic/1.0 (https://github.com/nothingmusic)")
                .get()
                .build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val array = JSONArray(response.body?.string().orEmpty())
                    if (array.length() == 0) return@use null
                    pickBest(array, durationSec)
                }
            }.onFailure { failure ->
                DiagnosticLog.event(
                    "LrcLib",
                    "search-failed title=${title.take(24)} error=${failure.javaClass.simpleName}",
                )
            }.getOrNull()
        }

    /** 优先带同步歌词的结果；时长接近的优先，其次取第一个。 */
    private fun pickBest(array: JSONArray, durationSec: Int): LyricsDocument? {
        var firstSynced: LyricsDocument? = null
        var bestSynced: LyricsDocument? = null
        var bestDelta = Int.MAX_VALUE
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val synced = item.optString("syncedLyrics")
            if (synced.isBlank()) continue
            val document = ExternalLyricsReader.parseRemoteDocument(
                synced.toByteArray(Charsets.UTF_8),
            ) ?: continue
            if (document.lines.isEmpty()) continue
            if (firstSynced == null) firstSynced = document
            if (durationSec > 0) {
                val candidateDuration = item.optInt("duration", 0)
                val delta = if (candidateDuration > 0) kotlin.math.abs(candidateDuration - durationSec) else Int.MAX_VALUE
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestSynced = document
                }
            }
        }
        // 时长差超过 5 秒视为没匹配上，宁可不给也别给错歌的歌词。
        return bestSynced?.takeIf { bestDelta <= 5 } ?: firstSynced
    }
}
