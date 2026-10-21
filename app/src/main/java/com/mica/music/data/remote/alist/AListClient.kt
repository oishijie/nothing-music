package com.mica.music.data.remote.alist

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.util.DiagnosticLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal enum class AListFailureKind { AUTH, HTTP, PROTOCOL, STALE_OPERATION }

internal class AListException(
    val kind: AListFailureKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** AList 源的 opaque id 就是服务器上的绝对路径，路径运算全部在此收口。 */
internal object AListPathCodec {
    fun sourceRoot(endpoint: String): HttpUrl {
        val trimmed = endpoint.trim().trimEnd('/')
        val url = trimmed.toHttpUrlOrNull()
            ?: throw AListException(AListFailureKind.PROTOCOL, "无效的 AList 服务器地址")
        val basePath = if (url.encodedPath.endsWith("/")) url.encodedPath else "${url.encodedPath}/"
        return url.newBuilder().encodedPath(basePath).build()
    }

    fun normalizeDirectoryPath(raw: String): String {
        var path = raw.trim().ifBlank { "/" }
        if (!path.startsWith("/")) path = "/$path"
        while (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        return path
    }

    fun joinPath(directory: String, name: String): String =
        "${normalizeDirectoryPath(directory).trimEnd('/')}/$name"

    fun parentPath(path: String): String {
        val normalized = normalizeDirectoryPath(path)
        if (normalized == "/") return "/"
        val index = normalized.lastIndexOf('/')
        return if (index <= 0) "/" else normalized.substring(0, index)
    }

    fun siblingPath(filePath: String, fileName: String): String =
        "${parentPath(filePath).trimEnd('/')}/$fileName"

    /** raw_url 是相对路径（如 /p?path=...）时按配置端点补全成绝对 URL，并补齐缺失的 sign 查询参数。 */
    fun resolveRawUrl(endpoint: String, rawUrl: String, sign: String): HttpUrl? {
        val raw = rawUrl.trim()
        val resolved = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw.toHttpUrlOrNull()
        } else {
            sourceRoot(endpoint).resolve(raw.removePrefix("/"))
        } ?: return null
        if (sign.isBlank() || resolved.queryParameter("sign") != null) return resolved
        return resolved.newBuilder().addQueryParameter("sign", sign).build()
    }

    /**
     * 同源的 raw_url 由 AList 自己应答，需要携带登录态与签名头；
     * 网盘驱动的直链自带签名参数，多余的头反而可能破坏云存储的请求签名。
     */
    fun isSameOrigin(endpoint: String, rawUrl: HttpUrl): Boolean {
        val root = runCatching { sourceRoot(endpoint) }.getOrNull() ?: return false
        return root.scheme == rawUrl.scheme && root.host == rawUrl.host && root.port == rawUrl.port
    }
}

/**
 * AList v3 HTTP API 客户端（对齐 AList/OpenList 官方接口）：
 * - 登录：POST /api/auth/login {username, password} -> data.token
 * - 列目录：POST /api/fs/list {path, page, per_page, refresh}
 * - 文件详情：POST /api/fs/get {path} -> data.raw_url / sign / size
 *
 * raw_url 换取串行 + 内存缓存，播放 / 封面 / 歌词 / 元数据探测命中同一份，不重复请求。
 */
internal class AListClient(
    private val endpoint: String,
    private val username: String,
    private val password: String,
) {
    data class AListEntry(
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val modified: String,
        val sign: String,
    )

    data class AListFileDetail(
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val rawUrl: String,
        val sign: String,
        val modified: String,
    ) {
        override fun toString(): String =
            "AListFileDetail(name=$name, size=$size, isDir=$isDir, rawUrl=<redacted>, sign=<redacted>)"
    }

    @Volatile
    private var cachedToken: String? = null

    private val isGuest: Boolean get() = username.isBlank() && password.isBlank()

    private val tokenMutex = Mutex()
    private val urlCacheMutex = Mutex()
    private val urlCache = object : LinkedHashMap<String, CachedRawUrl>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRawUrl>): Boolean =
            size > URL_CACHE_MAX_ENTRIES
    }

    private data class CachedRawUrl(val detail: AListFileDetail, val expireAtMs: Long)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun login(forceRefresh: Boolean = false): String = tokenMutex.withLock {
        // 游客模式不发登录请求，直接匿名调用公开接口。
        if (isGuest) return@withLock ""
        val cached = cachedToken
        if (!forceRefresh && cached != null) return@withLock cached
        val json = post(
            "/api/auth/login",
            JSONObject()
                .put("username", username)
                .put("password", password),
            token = null,
        )
        val token = json.optJSONObject("data")?.optString("token").orEmpty()
        if (token.isBlank()) {
            throw AListException(AListFailureKind.PROTOCOL, "AList 登录响应缺少 token")
        }
        cachedToken = token
        token
    }

    /** 返回该目录一页条目；hasMore 表示可能还有下一页。 */
    suspend fun list(path: String, page: Int, perPage: Int): Pair<List<AListEntry>, Boolean> =
        withReLogin { token ->
            val json = post(
                "/api/fs/list",
                JSONObject()
                    .put("path", path)
                    .put("password", "")
                    .put("page", page)
                    .put("per_page", perPage)
                    .put("refresh", false),
                token,
            )
            parseList(json)
        }

    suspend fun fileInfo(path: String): AListFileDetail {
        urlCacheMutex.withLock {
            val cached = urlCache[path]
            if (cached != null && System.currentTimeMillis() < cached.expireAtMs) {
                return cached.detail
            }
        }
        val detail = withReLogin { token ->
            val json = post(
                "/api/fs/get",
                JSONObject()
                    .put("path", path)
                    .put("password", ""),
                token,
            )
            val data = json.optJSONObject("data")
                ?: throw AListException(AListFailureKind.PROTOCOL, "AList 文件信息响应缺少 data")
            val rawUrl = data.optString("raw_url")
            if (rawUrl.isBlank()) {
                throw AListException(AListFailureKind.PROTOCOL, "AList 未返回 raw_url")
            }
            AListFileDetail(
                name = data.optString("name"),
                size = data.optLong("size", 0L),
                isDir = data.optBoolean("is_dir", false),
                rawUrl = rawUrl,
                sign = data.optString("sign"),
                modified = data.optString("modified"),
            )
        }
        urlCacheMutex.withLock {
            urlCache[path] = CachedRawUrl(detail, System.currentTimeMillis() + RAW_URL_TTL_MS)
        }
        return detail
    }

    fun resolveRawUrl(detail: AListFileDetail): HttpUrl? =
        AListPathCodec.resolveRawUrl(endpoint, detail.rawUrl, detail.sign)

    /** 同源代理请求需要登录态；网盘直链自带签名，任何附加头都可能破坏其请求签名。 */
    fun playbackHeaders(detail: AListFileDetail): Map<String, String> {
        val url = resolveRawUrl(detail) ?: return emptyMap()
        if (!AListPathCodec.isSameOrigin(endpoint, url)) return emptyMap()
        val headers = LinkedHashMap<String, String>()
        cachedToken?.takeIf(String::isNotBlank)?.let { headers["Authorization"] = it }
        return headers
    }

    private suspend fun <T> withReLogin(block: suspend (String) -> T): T {
        val token = login()
        return try {
            block(token)
        } catch (failure: AListException) {
            if (failure.kind != AListFailureKind.AUTH) throw failure
            DiagnosticLog.important("AList", "登录态失效，重新登录重试")
            block(login(forceRefresh = true))
        }
    }

    private suspend fun post(path: String, body: JSONObject, token: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val url = AListPathCodec.sourceRoot(endpoint).resolve(path.trimStart('/'))
                ?: throw AListException(AListFailureKind.PROTOCOL, "无效的 AList 请求路径")
            val builder = Request.Builder().url(url).post(body.toString().toRequestBody(jsonMedia))
            if (!token.isNullOrBlank()) builder.header("Authorization", token)
            http.newCall(builder.build()).execute().use { response ->
                if (response.code == 401) {
                    throw AListException(AListFailureKind.AUTH, "AList 登录已失效（HTTP 401）")
                }
                if (!response.isSuccessful) {
                    throw AListException(AListFailureKind.HTTP, "AList 接口返回 HTTP ${response.code}")
                }
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse {
                    throw AListException(AListFailureKind.PROTOCOL, "AList 响应不是有效 JSON")
                }
                val code = json.optInt("code", -1)
                if (code != 200) {
                    val message = json.optString("message").ifBlank { "code=$code" }
                    throw AListException(
                        if (code == 401 || code == 403) AListFailureKind.AUTH else AListFailureKind.PROTOCOL,
                        "AList 请求失败：$message",
                    )
                }
                json
            }
        }

    private fun parseList(json: JSONObject): Pair<List<AListEntry>, Boolean> {
        val content = json.optJSONObject("data")?.optJSONArray("content")
        val entries = ArrayList<AListEntry>()
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (name.isBlank()) continue
                entries += AListEntry(
                    name = name,
                    size = item.optLong("size", 0L),
                    isDir = item.optBoolean("is_dir", false),
                    modified = item.optString("modified"),
                    sign = item.optString("sign"),
                )
            }
        }
        return entries to (entries.size >= LIST_PER_PAGE_DEFAULT)
    }

    private companion object {
        const val URL_CACHE_MAX_ENTRIES = 256
        const val RAW_URL_TTL_MS = 60 * 60_000L
        const val LIST_PER_PAGE_DEFAULT = 200
    }
}

/**
 * 按来源缓存 client：同一来源共享登录态与 raw_url 缓存；
 * 凭证轮换（revision 变化）时重建，旧登录态随之丢弃。
 */
internal object AListClients {
    private val clients = ConcurrentHashMap<String, AListClient>()
    private val credentialRevisions = ConcurrentHashMap<String, Long>()

    fun forSource(
        source: RemoteSourceInstance,
        credential: RemoteCredentialSnapshot,
    ): AListClient? {
        clients[source.id]?.takeIf { credentialRevisions[source.id] == credential.revision }?.let { return it }
        val (username, password) = resolveAListUsernamePassword(credential) ?: return null
        val created = AListClient(source.endpoint, username, password)
        clients[source.id] = created
        credentialRevisions[source.id] = credential.revision
        return created
    }
}

/** 凭证映射：AList 支持用户名密码与匿名（游客）访问；BearerToken 不支持。 */
internal fun resolveAListUsernamePassword(
    credential: RemoteCredentialSnapshot,
): Pair<String, String>? = when (val material = credential.material) {
    is RemoteCredentialMaterial.UsernamePassword -> material.username to material.password
    RemoteCredentialMaterial.Anonymous -> "" to ""
    is RemoteCredentialMaterial.BearerToken -> null
}
