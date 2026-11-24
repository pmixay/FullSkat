package com.fullskat.core.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the FullSkat server, protocol v2 (docs/PROTOCOL.md): accounts, code linking, prefs, sync,
 * live events, attachments and chat. One shared OkHttpClient keeps the TLS session warm between calls, which
 * is the single biggest network power saver on a watch. [baseUrl] is fixed per build (ServerEndpoint).
 */
class ApiClient(private val baseUrl: String) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Long-lived streams (live events) must not time out between pings. */
    private val streaming: OkHttpClient = http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()

    private val json = "application/json; charset=utf-8".toMediaType()

    // ---------------------------------------------------------------- accounts

    suspend fun register(passcode: String, name: String, deviceId: String, deviceName: String, kind: String): Session = io {
        val body = JSONObject().put("passcode", passcode).put("name", name).put("device_id", deviceId)
            .put("device_name", deviceName).put("device_kind", kind)
        call(post("/v1/register", body, token = null)) { code, text ->
            when (code) {
                200, 201 -> JsonModels.session(JSONObject(text), firstSetupDefault = code == 201)
                401 -> throw ApiException.WrongPasscode()
                else -> throw error(code, text)
            }
        }
    }

    suspend fun linkStart(deviceId: String, deviceName: String, kind: String): LinkStart = io {
        val body = JSONObject().put("device_id", deviceId).put("device_name", deviceName).put("device_kind", kind)
        call(post("/v1/link/start", body, token = null)) { code, text ->
            if (code != 200) throw error(code, text)
            val o = JSONObject(text)
            LinkStart(o.getString("link_id"), o.getString("code"), o.optInt("expires_in", 30), o.optInt("period", 30))
        }
    }

    /** Long-polls for up to [waitSec]: the next code when it rotates, or the session once a phone entered the code. */
    suspend fun linkStatus(linkId: String, waitSec: Int = 25): LinkStatus = io {
        val req = Request.Builder().url("$baseUrl/v1/link/status?link_id=${enc(linkId)}&wait=$waitSec").get().build()
        callWith(streaming, req) { code, text ->
            if (code != 200) throw error(code, text)
            val o = JSONObject(text)
            if (o.optString("status") == "linked") LinkStatus.Linked(JsonModels.session(o, firstSetupDefault = false))
            else LinkStatus.Pending(o.optString("code"), o.optInt("expires_in", 30), o.optInt("period", 30))
        }
    }

    /** The signed-in user enters a code a new device shows; returns that device. */
    suspend fun linkClaim(token: String, code: String): Device = io {
        call(post("/v1/link/claim", JSONObject().put("code", code), token)) { status, text ->
            if (status != 200) throw error(status, text)
            JsonModels.device(JSONObject(text).getJSONObject("device"))
        }
    }

    suspend fun me(token: String): Session = io {
        call(get("/v1/me", token)) { code, text ->
            if (code != 200) throw error(code, text)
            JsonModels.session(JSONObject(text), firstSetupDefault = false, token = token)
        }
    }

    suspend fun rename(token: String, name: String): User = io {
        val req = Request.Builder().url("$baseUrl/v1/me").header("Authorization", "Bearer $token")
            .patch(JSONObject().put("name", name).toString().toRequestBody(json)).build()
        call(req) { code, text ->
            if (code != 200) throw error(code, text)
            val o = JSONObject(text)
            JsonModels.user(o.optJSONObject("user") ?: o)
        }
    }

    suspend fun devices(token: String): List<Device> = io {
        call(get("/v1/devices", token)) { code, text ->
            if (code != 200) throw error(code, text)
            JsonModels.list(JSONObject(text).optJSONArray("devices"), JsonModels::device)
        }
    }

    suspend fun unlink(token: String, deviceId: String) = io {
        call(delete("/v1/devices/${enc(deviceId)}", token)) { code, text -> if (code !in 200..299) throw error(code, text) }
    }

    // ---------------------------------------------------------------- prefs, dialogs, sync

    suspend fun prefs(token: String): Prefs = io {
        call(get("/v1/prefs", token)) { code, text ->
            if (code != 200) throw error(code, text)
            JsonModels.prefs(JSONObject(text).optJSONObject("prefs"))
        }
    }

    /** Sends the given keys only; [clear] lists keys to reset to the server default (sent as null). */
    suspend fun putPrefs(token: String, prefs: Prefs, clear: Set<String> = emptySet()): Prefs = io {
        val body = JsonModels.prefsJson(prefs)
        for (key in clear) body.put(key, JSONObject.NULL)
        val req = Request.Builder().url("$baseUrl/v1/prefs").header("Authorization", "Bearer $token")
            .put(body.toString().toRequestBody(json)).build()
        call(req) { code, text ->
            if (code != 200) throw error(code, text)
            JsonModels.prefs(JSONObject(text).optJSONObject("prefs"))
        }
    }

    suspend fun createDialog(token: String, id: String, title: String, createdAt: Long) = io {
        val body = JSONObject().put("id", id).put("title", title).put("created_at", createdAt)
        call(post("/v1/dialogs", body, token)) { code, text -> if (code !in 200..299) throw error(code, text) }
    }

    suspend fun renameDialog(token: String, id: String, title: String) = io {
        val req = Request.Builder().url("$baseUrl/v1/dialogs/${enc(id)}").header("Authorization", "Bearer $token")
            .patch(JSONObject().put("title", title).toString().toRequestBody(json)).build()
        call(req) { code, text -> if (code !in 200..299 && code != 404) throw error(code, text) }
    }

    suspend fun deleteDialog(token: String, id: String) = io {
        call(delete("/v1/dialogs/${enc(id)}", token)) { code, text -> if (code !in 200..299 && code != 404) throw error(code, text) }
    }

    suspend fun deleteMessage(token: String, id: String) = io {
        call(delete("/v1/messages/${enc(id)}", token)) { code, text -> if (code !in 200..299 && code != 404) throw error(code, text) }
    }

    suspend fun sync(token: String, since: Long, limit: Int = 500): SyncPage = io {
        call(get("/v1/sync?since=$since&limit=$limit", token)) { code, text ->
            if (code != 200) throw error(code, text)
            JsonModels.syncPage(JSONObject(text), since)
        }
    }

    // ---------------------------------------------------------------- attachments

    suspend fun uploadAttachment(token: String, bytes: ByteArray, mime: String): AttachmentRef = io {
        val req = Request.Builder().url("$baseUrl/v1/attachments").header("Authorization", "Bearer $token")
            .post(bytes.toRequestBody(mime.toMediaType())).build()
        call(req) { code, text ->
            if (code != 201 && code != 200) throw error(code, text)
            JsonModels.attachment(JSONObject(text))
        }
    }

    /** The JPEG of an attachment ([thumb]: at most 320 px), or null when it is gone. */
    suspend fun downloadAttachment(token: String, id: String, thumb: Boolean): ByteArray? = io {
        val req = Request.Builder().url("$baseUrl/v1/attachments/${enc(id)}" + if (thumb) "?size=thumb" else "")
            .header("Authorization", "Bearer $token").get().build()
        execute(http, req).use { resp ->
            when (resp.code) {
                200 -> resp.body.bytes()
                404 -> null
                else -> throw error(resp.code, resp.body.string())
            }
        }
    }

    // ---------------------------------------------------------------- chat and events

    /** One chat turn as server-sent events. Closing the flow does not stop the answer on the server; [stop] does. */
    fun chat(token: String, request: ChatRequest): Flow<ChatEvent> = flow {
        val message = JSONObject().put("id", request.messageId).put("content", request.content)
            .put("attachments", JSONArray(request.attachments)).put("created_at", request.createdAt)
        val body = JSONObject().put("dialog_id", request.dialogId).put("message", message)
            .put("answer_id", request.answerId).put("locale", request.locale)
        request.model?.let { body.put("model", it) }
        request.effort?.let { body.put("effort", it) }
        request.webSearch?.let { body.put("web_search", it) }
        request.figures?.let { body.put("figures", it) }
        val req = post("/v1/chat", body, token).newBuilder().header("Accept", "text/event-stream").build()
        execute(http, req).use { resp ->
            if (resp.code != 200) throw error(resp.code, resp.body.string(), resp.header("Retry-After")?.toIntOrNull())
            readEvents(resp.body.source()) { event, data ->
                val ev = JsonModels.chatEvent(event, data) ?: return@readEvents true
                emit(ev)
                ev !is ChatEvent.Done && ev !is ChatEvent.Error
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun stop(token: String, answerId: String) = io {
        call(post("/v1/messages/${enc(answerId)}/stop", JSONObject(), token)) { code, text ->
            if (code !in 200..299 && code != 404) throw error(code, text)
        }
    }

    /** `GET /v1/events` until the server closes it or the collector stops; reconnecting is up to the caller. */
    fun events(token: String): Flow<LiveEvent> = flow {
        val req = get("/v1/events", token).newBuilder().header("Accept", "text/event-stream").build()
        execute(streaming, req).use { resp ->
            if (resp.code != 200) throw error(resp.code, resp.body.string())
            readEvents(resp.body.source()) { event, data ->
                when (event) {
                    "changed" -> emit(LiveEvent.Changed(runCatching { JSONObject(data).optLong("seq") }.getOrDefault(0L)))
                    "revoked" -> { emit(LiveEvent.Revoked); return@readEvents false }
                }
                true
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- plumbing

    /** Reads SSE frames, calling [onEvent] for each until it returns false or the stream ends. */
    private suspend fun readEvents(source: BufferedSource, onEvent: suspend (String, String) -> Boolean) {
        var event = "message"
        val data = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = try { source.readUtf8Line() } catch (e: IOException) { throw ApiException.Network(e.message ?: "stream") } ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty() && !onEvent(event, data.toString())) return
                    event = "message"
                    data.setLength(0)
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
        }
    }

    private fun get(path: String, token: String) =
        Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token").get().build()

    private fun delete(path: String, token: String) =
        Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token").delete().build()

    private fun post(path: String, body: JSONObject, token: String?): Request {
        val b = Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody(json) as RequestBody)
        if (token != null) b.header("Authorization", "Bearer $token")
        return b.build()
    }

    private fun <T> call(req: Request, handle: (Int, String) -> T): T = callWith(http, req, handle)

    private fun <T> callWith(client: OkHttpClient, req: Request, handle: (Int, String) -> T): T =
        execute(client, req).use { resp ->
            val text = resp.body.string()
            if (resp.code == 429) throw error(429, text, resp.header("Retry-After")?.toIntOrNull())
            handle(resp.code, text)
        }

    private fun execute(client: OkHttpClient, req: Request): Response = try {
        client.newCall(req).execute()
    } catch (e: IOException) {
        throw ApiException.Network(e.message ?: "network")
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** The exception for a failed response: 401 → Unauthorized, 429 → RateLimited/Locked, 404 → NotFound. */
    private fun error(status: Int, text: String, retryAfterHeader: Int? = null): ApiException {
        val (code, message, bodyRetry) = runCatching {
            val o = JSONObject(text)
            Triple(o.optString("error"), o.optString("message").ifBlank { o.optString("error") }, o.optInt("retry_after", 0))
        }.getOrDefault(Triple("", text.take(120), 0))
        val retry = retryAfterHeader ?: bodyRetry
        return when (status) {
            401 -> if (code == "invalid_passcode") ApiException.WrongPasscode() else ApiException.Unauthorized(message)
            404 -> ApiException.NotFound(code, message)
            429 -> if (code == "locked") ApiException.Locked(retry.takeIf { it > 0 } ?: 60)
            else ApiException.RateLimited(retry.takeIf { it > 0 } ?: 30, code.ifBlank { "rate_limited" })
            else -> ApiException.Server(status, message, code, retry)
        }
    }

    companion object {
        private const val TAG = "ApiClient"

        internal fun warn(message: String) {
            runCatching { Log.w(TAG, message) }
        }
    }
}
