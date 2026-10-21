package com.mica.music.data.remote.aliyundrive

import android.net.Uri
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal enum class AliyunDriveFailureKind {
    AUTH,
    PROTOCOL,
    HTTP,
    STALE_OPERATION,
}

internal class AliyunDriveException(
    val kind: AliyunDriveFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 阿里云盘 OpenAPI 客户端（对齐 AList drivers/aliyundrive_open）。
 *
 * - 令牌换取：POST {refreshEndpoint} {client_id, client_secret, grant_type=refresh_token, refresh_token}
 * - 盘信息：POST {API_BASE}/adrive/v1.0/user/getDriveInfo -> default_drive_id
 * - 目录列举：POST {API_BASE}/adrive/v1.0/openFile/list（parent_file_id + marker 分页）
 * - 下载直链：POST {API_BASE}/adrive/v1.0/openFile/getDownloadUrl -> url
 *
 * 用户的 refresh_token 通过 Mica 的 Keystore 凭证库以 BearerToken 物料保存，永不落日志。
 * client_id/client_secret 为空表示走 AList 官方中转（见 REFRESH_ENDPOINT），
 * 中转由 AList 服务端代持应用凭据——AList 仓库里并不存在可复用的硬编码 client_id。
 */
internal class AliyunDriveClient(
    private val onRefreshTokenRotated: suspend (String) -> Unit = {},
) {
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
    )

    data class DriveFile(
        val fileId: String,
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val updatedAt: String,
    )

    private val tokenMutex = Mutex()
    private var cachedTokens: Tokens? = null
    private var activeRefreshToken: String? = null

    private data class CachedDownloadUrl(val url: String, val expireAtMs: Long)

    // 直链换取必须串行：并发打 getDownloadUrl 会撞 429 限流。等锁的调用方进去后会先命中前一个刚写入的缓存，
    // 因此同一文件的并发解析天然只发起一次请求。
    private val urlMutex = Mutex()
    private val urlCache = object : LinkedHashMap<String, CachedDownloadUrl>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedDownloadUrl>,
        ): Boolean = size > URL_CACHE_MAX_ENTRIES
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun accessToken(refreshToken: String, forceRefresh: Boolean = false): String =
        tokenMutex.withLock {
            val usable = cachedTokens
                ?.takeIf { !forceRefresh && System.currentTimeMillis() < it.expiresAtMs - 60_000L }
            // 调用方手里拿的是旧 refresh_token 时，缓存里的 access_token 是那一代凭据签发的，不能用。
            if (usable != null && activeRefreshToken == refreshToken) return@withLock usable.accessToken
            val refreshed = refreshTokens(refreshToken)
            cachedTokens = refreshed
            activeRefreshToken = refreshed.refreshToken
            refreshed.accessToken
        }

    suspend fun defaultDriveId(refreshToken: String): String {
        val json = apiCall(refreshToken, "/adrive/v1.0/user/getDriveInfo", JSONObject())
        val driveId = json.optString("default_drive_id")
        if (driveId.isBlank()) {
            throw AliyunDriveException(AliyunDriveFailureKind.PROTOCOL, "阿里云盘未返回 default_drive_id")
        }
        return driveId
    }

    /** 返回该目录一页文件/文件夹与下一页 marker（null 表示到底）。 */
    suspend fun list(
        refreshToken: String,
        driveId: String,
        parentFileId: String,
        marker: String?,
    ): Pair<List<DriveFile>, String?> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("drive_id", driveId)
            .put("parent_file_id", parentFileId)
            .put("limit", 200)
            .put("order_by", "name")
            .put("order_direction", "ASC")
        if (!marker.isNullOrBlank()) body.put("marker", marker)
        val json = apiCall(refreshToken, "/adrive/v1.0/openFile/list", body)
        val itemsJson = json.optJSONArray("items")
        val files = ArrayList<DriveFile>()
        if (itemsJson != null) {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.optJSONObject(index) ?: continue
                val fileId = item.optString("file_id")
                val name = item.optString("name")
                if (fileId.isBlank() || name.isBlank()) continue
                files += DriveFile(
                    fileId = fileId,
                    name = name,
                    isFolder = item.optString("type") == "folder",
                    size = item.optLong("size", 0L),
                    updatedAt = item.optString("updated_at"),
                )
            }
        }
        val nextMarker = json.optString("next_marker").takeIf(String::isNotBlank)
        files to nextMarker
    }

    /**
     * 返回有时效的签名直链（自鉴权，无需 Authorization 头）。
     *
     * 一次播放会分别向播放、封面、歌词、元数据探测要直链，命中缓存就不再重复换取。
     */
    suspend fun downloadUrl(refreshToken: String, driveId: String, fileId: String): String {
        val cacheKey = "$driveId/$fileId"
        return urlMutex.withLock {
            val cached = urlCache[cacheKey]
            if (cached != null && System.currentTimeMillis() < cached.expireAtMs - URL_CACHE_SAFETY_MARGIN_MS) {
                DiagnosticLog.important("AliyunDrive", "down-url hit fileId=$fileId")
                return@withLock cached.url
            }
            DiagnosticLog.important("AliyunDrive", "down-url fetch fileId=$fileId")
            val json = apiCall(
                refreshToken,
                "/adrive/v1.0/openFile/getDownloadUrl",
                JSONObject()
                    .put("drive_id", driveId)
                    .put("file_id", fileId)
                    .put("expire_sec", 14400),
            )
            val url = json.optString("url")
            if (url.isBlank()) {
                throw AliyunDriveException(AliyunDriveFailureKind.PROTOCOL, "阿里云盘未返回下载直链")
            }
            urlCache[cacheKey] = CachedDownloadUrl(url, resolveExpireAtMs(json, url))
            url
        }
    }

    /**
     * 直链有效期优先取响应里的 expiration；服务端没给或已过期时，
     * 退而从签名 URL 自带的 x-oss-expires（秒）推算。
     */
    private fun resolveExpireAtMs(json: JSONObject, url: String): Long {
        val now = System.currentTimeMillis()
        val declared = json.optString("expiration")
        if (declared.isNotBlank()) {
            val parsed = runCatching { expirationFormat().parse(declared)?.time }.getOrNull()
            if (parsed != null && parsed > now) return parsed
        }
        val ossExpires = runCatching {
            url.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("x-oss-expires=") }
                ?.substringAfter('=')
                ?.toLong()
        }.getOrNull()
        if (ossExpires != null && ossExpires > 0L) return ossExpires * 1000L
        return now + DEFAULT_URL_TTL_MS
    }

    // SimpleDateFormat 非线程安全，按需构造，调用频率低。
    private fun expirationFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    suspend fun invalidateTokens() = tokenMutex.withLock {
        cachedTokens = null
        activeRefreshToken = null
    }

    private suspend fun refreshTokens(refreshToken: String): Tokens = withContext(Dispatchers.IO) {
        val useOfficial = CLIENT_ID.isNotBlank() && CLIENT_SECRET.isNotBlank()
        val (response, url) = if (useOfficial) {
            execute(
                OFFICIAL_REFRESH_ENDPOINT,
                JSONObject()
                    .put("client_id", CLIENT_ID)
                    .put("client_secret", CLIENT_SECRET)
                    .put("grant_type", "refresh_token")
                    .put("refresh_token", refreshToken),
                null,
            ) to OFFICIAL_REFRESH_ENDPOINT
        } else {
            // OpenList 公益中转代持应用凭据：无需自建开发者应用。
            // 该端点只接受 GET + query 参数（POST 会 404），且用 HTTP 500 携带业务错误。
            var last: okhttp3.Response? = null
            var used = RELAY_ENDPOINTS.first()
            for (base in RELAY_ENDPOINTS) {
                val url = buildRelayUrl(base, refreshToken)
                val candidate = runCatching { executeGet(url) }.getOrNull() ?: continue
                last = candidate
                used = url
                // 中转以 500 + {"text": ...} 表示业务失败，也视为已拿到明确答复。
                if (candidate.isSuccessful || candidate.code in 400..599) break
                runCatching { candidate.close() }
            }
            val resolved = last
                ?: throw AliyunDriveException(AliyunDriveFailureKind.HTTP, "阿里云盘中转站点均不可达")
            resolved to used
        }
        // 官方端点用 4xx 表示拒绝；OpenList 中转用 500 + {"text":...} 表示业务失败。
        val rejected = if (useOfficial) response.code in 400..499 else response.code in 400..599
        if (rejected) {
            val detail = runCatching { response.peekBody(2048).string().take(300) }.getOrDefault("")
            runCatching { response.close() }
            throw AliyunDriveException(
                AliyunDriveFailureKind.AUTH,
                "阿里云盘 refresh_token 被拒绝（HTTP ${response.code}）$detail",
            )
        }
        val json = parseJson(response)
        // 中转可能把结果包一层，逐个常见字段名取值。
        val payload = when {
            json.has("data") && json.opt("data") is JSONObject -> json.getJSONObject("data")
            json.has("result") && json.opt("result") is JSONObject -> json.getJSONObject("result")
            else -> json
        }
        val access = payload.optString("access_token")
            .ifBlank { payload.optString("accessToken") }
            .ifBlank { json.optString("access_token") }
        val nextRefresh = payload.optString("refresh_token")
            .ifBlank { payload.optString("refreshToken") }
            .ifBlank { json.optString("refresh_token") }
            .ifBlank { refreshToken }
        val expiresIn = payload.optLong("expires_in", 0L)
            .takeIf { it > 0 }
            ?: payload.optLong("expiresIn", 0L).takeIf { it > 0 }
            ?: 7200L
        if (access.isBlank()) {
            // 中转失败时把原因放在 text 字段里，优先展示它。
            val reason = json.optString("text").ifBlank { payload.optString("text") }
            DiagnosticLog.important(
                "AliyunDrive",
                "refresh-missing-token url=$url body=${response.peekBodySafe()}",
            )
            throw AliyunDriveException(
                AliyunDriveFailureKind.PROTOCOL,
                if (reason.isBlank()) "阿里云盘令牌刷新响应缺少 access_token"
                else "阿里云盘刷新失败：$reason",
            )
        }
        // 阿里每次刷新都会作废旧 refresh_token，新值必须回写持久化，否则重启后登录态丢失。
        // 回调在 tokenMutex 内执行，实现方不得再取同一把锁。
        if (nextRefresh != refreshToken) {
            onRefreshTokenRotated(nextRefresh)
        }
        Tokens(
            accessToken = access,
            refreshToken = nextRefresh,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
        )
    }

    private suspend fun apiCall(refreshToken: String, path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val first = execute(API_BASE + path, body, bearerToken = accessToken(refreshToken))
            val retried = when (first.code) {
                401 -> {
                    DiagnosticLog.important("AliyunDrive", "http-401 retry path=$path")
                    execute(API_BASE + path, body, bearerToken = accessToken(refreshToken, forceRefresh = true))
                }
                // 换取直链与会话接口都有 QPS 限制，撞上限流退避一次再试。
                429 -> {
                    DiagnosticLog.important("AliyunDrive", "http-429 retry path=$path")
                    delay(RATE_LIMIT_RETRY_DELAY_MS)
                    execute(API_BASE + path, body, bearerToken = accessToken(refreshToken))
                }
                else -> first
            }
            if (retried.code in 400..499) {
                DiagnosticLog.important(
                    "AliyunDrive",
                    "http-fail path=$path code=${retried.code} body=${retried.peekBodySafe()}",
                )
                throw AliyunDriveException(
                    if (retried.code == 401 || retried.code == 403) {
                        AliyunDriveFailureKind.AUTH
                    } else {
                        AliyunDriveFailureKind.HTTP
                    },
                    "阿里云盘接口返回 HTTP ${retried.code}",
                )
            }
            if (!retried.isSuccessful) {
                DiagnosticLog.important(
                    "AliyunDrive",
                    "http-fail path=$path code=${retried.code} body=${retried.peekBodySafe()}",
                )
                throw AliyunDriveException(
                    AliyunDriveFailureKind.HTTP,
                    "阿里云盘接口返回 HTTP ${retried.code}",
                )
            }
            parseJson(retried)
        }

    /** 读响应体用于诊断日志，截断避免把整个文件列表写进日志。 */
    private fun okhttp3.Response.peekBodySafe(): String = runCatching {
        peekBody(2048).string().take(800)
    }.getOrDefault("<unreadable>")

    private fun execute(url: String, body: JSONObject, bearerToken: String?): okhttp3.Response {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return http.newCall(builder.build()).execute()
    }

    private fun executeGet(url: String): okhttp3.Response =
        http.newCall(Request.Builder().url(url).get().build()).execute()

    private fun buildRelayUrl(base: String, refreshToken: String): String {
        val query = listOf(
            "apps_types" to RELAY_APPS_TYPE,
            "refresh_ui" to refreshToken,
            "server_use" to "true",
        ).joinToString("&") { (k, v) ->
            "${Uri.encode(k)}=${Uri.encode(v)}"
        }
        return "$base?$query"
    }

    private fun parseJson(response: okhttp3.Response): JSONObject {
        val text = response.body?.string().orEmpty()
        return runCatching { JSONObject(text) }.getOrElse {
            throw AliyunDriveException(AliyunDriveFailureKind.PROTOCOL, "阿里云盘响应不是有效 JSON")
        }
    }

    private companion object {
        const val API_BASE = "https://openapi.alipan.com"

        const val URL_CACHE_MAX_ENTRIES = 256

        // 直链临过期前不再复用，避免交给播放器时已经失效。
        const val URL_CACHE_SAFETY_MARGIN_MS = 5 * 60_000L

        const val DEFAULT_URL_TTL_MS = 10 * 60_000L

        const val RATE_LIMIT_RETRY_DELAY_MS = 800L

        // 留空则走 OpenList 公益中转（代持应用凭据）。个人开发者申请已暂停，
        // 自建应用拿到后填入这里即自动切换为官方直连。
        const val CLIENT_ID = ""
        const val CLIENT_SECRET = ""

        const val OFFICIAL_REFRESH_ENDPOINT = "https://openapi.alipan.com/oauth/access_token"

        // AList 原 api.alistgo.com 已下线，改用其继任项目 OpenList 的公益中转；
        // 国内站点优先，全球站点兜底。
        val RELAY_ENDPOINTS = listOf(
            "https://api-cn.oplist.org/alicloud/renewapi",
            "https://api.oplist.org/alicloud/renewapi",
        )
        const val RELAY_APPS_TYPE = "alicloud_go"
    }
}
