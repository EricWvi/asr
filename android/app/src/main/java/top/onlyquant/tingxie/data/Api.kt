package top.onlyquant.tingxie.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Shared by every session so connections and threads are pooled. */
val baseHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/** A request the server answered with an error, carrying its Chinese message. */
class ApiError(val status: Int, message: String) : IOException(message)

/** What to tell the user about a failed request. */
fun Throwable.userMessage(): String = when (this) {
    is ApiError -> message ?: "请求失败"
    is IOException -> "网络连接失败"
    else -> "出错了，请重试"
}

@Serializable
data class Me(val name: String, val email: String = "", @SerialName("avatar_url") val avatarUrl: String = "")

@Serializable
data class Doc(
    val id: Long,
    val title: String,
    val color: String,
    val content: String = "",
    val snippet: String = "",
    @SerialName("tidy_ranges") val tidyRanges: List<List<Int>> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    val updated: Instant get() = java.time.OffsetDateTime.parse(updatedAt).toInstant()
}

@Serializable
data class DocPatch(
    val title: String? = null,
    val color: String? = null,
    val content: String? = null,
    @SerialName("tidy_ranges") val tidyRanges: List<List<Int>>? = null,
)

@Serializable
data class Device(
    val id: Long,
    val name: String,
    val platform: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long,
    val online: Boolean = false,
    @SerialName("last_active_at") val lastActiveAt: Long = 0,
)

@Serializable
data class SignedIn(val token: String, val device: Device)

@Serializable
data class TidyRequest(val text: String, val headings: List<String>, val prefix: String, val suffix: String)

@Serializable
private data class TidyLine(val type: String, val text: String = "", val message: String = "")

@Serializable
private data class AvatarSet(@SerialName("avatar_url") val avatarUrl: String)

@Serializable
private data class SendBody(val text: String, @SerialName("device_id") val deviceId: Long)

@Serializable
data class SentTo(val id: Long, val name: String)

@Serializable
private data class SendReply(val device: SentTo)

@Serializable
private data class ErrorBody(val error: String = "")

@Serializable
private data class NewDoc(val title: String, val color: String? = null)

@Serializable
private data class TokenRequest(val code: String, @SerialName("code_verifier") val verifier: String)

private val jsonType = "application/json; charset=utf-8".toMediaType()

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { cancel() }
}

/**
 * Reads the body, or throws ApiError with the server's message. A body
 * larger than what OkHttp has buffered is read from the socket, which
 * Android forbids on the main thread, so it is read on the IO dispatcher.
 */
private suspend fun Response.text(): String = withContext(Dispatchers.IO) { readText() }

private fun Response.readText(): String = use {
    val body = it.body.string()
    if (!it.isSuccessful) {
        val msg = runCatching { json.decodeFromString<ErrorBody>(body).error }.getOrNull()
        throw ApiError(it.code, msg?.ifEmpty { null } ?: "请求失败（${it.code}）")
    }
    body
}

/** Exchanges the sign-in code for a device token. */
suspend fun exchangeCode(server: String, code: String, verifier: String): SignedIn {
    val req = Request.Builder()
        .url("$server/auth/device/token")
        .post(json.encodeToString(TokenRequest(code, verifier)).toRequestBody(jsonType))
        .build()
    return json.decodeFromString(baseHttp.newCall(req).await().text())
}

/**
 * The tingxie API as a device sees it. Calls throw ApiError or IOException;
 * a 401 means the token was revoked and [onUnauthorized] fires first.
 */
class Api(val session: Session, private val onUnauthorized: () -> Unit) {
    val server = session.server

    /** Adds the device token; shared with image loading. */
    val http: OkHttpClient = baseHttp.newBuilder()
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.url.host != server.toHttpUrl().host) return@addInterceptor chain.proceed(req)
            chain.proceed(req.newBuilder().header("Authorization", "Bearer ${session.token}").build())
        }
        .build()

    fun url(path: String) = server + path

    private suspend fun call(method: String, path: String, body: RequestBody? = null): String {
        val req = Request.Builder().url(url(path)).method(method, body).build()
        try {
            return http.newCall(req).await().text()
        } catch (e: ApiError) {
            if (e.status == 401) onUnauthorized()
            throw e
        }
    }

    private suspend inline fun <reified T> get(path: String): T = json.decodeFromString(call("GET", path))

    private inline fun <reified T> T.body(): RequestBody = json.encodeToString(this).toRequestBody(jsonType)

    suspend fun me(): Me = get("/api/me")

    suspend fun documents(): List<Doc> = get("/api/documents")

    suspend fun search(q: String): List<Doc> =
        get("/api/search?q=" + java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20"))

    suspend fun document(id: Long): Doc = get("/api/documents/$id")

    suspend fun create(title: String, color: String? = null): Doc =
        json.decodeFromString(call("POST", "/api/documents", NewDoc(title, color).body()))

    suspend fun update(id: Long, patch: DocPatch): Doc =
        json.decodeFromString(call("PATCH", "/api/documents/$id", patch.body()))

    suspend fun delete(id: Long) {
        call("DELETE", "/api/documents/$id")
    }

    suspend fun restore(id: Long) {
        call("POST", "/api/documents/$id/restore", ByteArray(0).toRequestBody())
    }

    suspend fun devices(): List<Device> = get("/api/devices")

    /** Inserts [text] at the cursor on a Mac (0 picks the one in use) and returns who took it. */
    suspend fun send(text: String, deviceId: Long): SentTo =
        json.decodeFromString<SendReply>(call("POST", "/api/send", SendBody(text, deviceId).body())).device

    /** Uploads a new avatar (the image's bytes) and returns its URL. */
    suspend fun setAvatar(image: ByteArray, type: String): String =
        json.decodeFromString<AvatarSet>(call("PUT", "/api/me/avatar", image.toRequestBody(type.toMediaType()))).avatarUrl

    suspend fun deleteAvatar() {
        call("DELETE", "/api/me/avatar")
    }

    /** The model may think for a while before writing; the server gives up after two minutes. */
    private val slow: OkHttpClient by lazy { http.newBuilder().readTimeout(150, TimeUnit.SECONDS).build() }

    /**
     * Asks the server to tidy a transcript and returns the written text once
     * the model has finished; the server streams it as JSON lines.
     */
    suspend fun tidy(req: TidyRequest): String {
        val c = slow.newCall(Request.Builder().url(url("/api/tidy")).post(req.body()).build())
        val res = try {
            c.await()
        } catch (e: IOException) {
            throw ApiError(0, "无法连接服务器")
        }
        if (!res.isSuccessful) {
            try {
                res.text()
            } catch (e: ApiError) {
                if (e.status == 401) onUnauthorized()
                throw e
            }
        }
        // Reading blocks; cancelling the coroutine cancels the call, which ends the read.
        return kotlinx.coroutines.coroutineScope {
            val watch = launch {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    c.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) { res.use { readTidy(it) } }
            } finally {
                watch.cancel()
            }
        }
    }

    private fun readTidy(r: Response): String {
        val out = StringBuilder()
        val src = r.body.source()
        while (true) {
            val line = try {
                src.readUtf8Line()
            } catch (e: IOException) {
                null
            } ?: break
            if (line.isBlank()) continue
            val m = runCatching { json.decodeFromString<TidyLine>(line) }.getOrNull() ?: continue
            when (m.type) {
                "delta" -> out.append(m.text)
                "error" -> throw ApiError(502, m.message.ifEmpty { "整理失败，请稍后再试" })
                "done" -> return out.toString()
            }
        }
        throw ApiError(0, "整理中断，请稍后再试")
    }

    /** Signs this device out on the server. */
    suspend fun signOut() {
        call("DELETE", "/api/devices/${session.deviceId}")
    }
}
