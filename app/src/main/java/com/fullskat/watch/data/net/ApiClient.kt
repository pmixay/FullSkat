package com.fullskat.watch.data.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client for the FullSkat server (see docs/PROTOCOL.md).
 * One shared OkHttpClient keeps the TLS session and connection warm between turns,
 * which is the single biggest network power saver on a watch.
 */
class ApiClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun pair(passcode: String, deviceId: String, deviceName: String): PairResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("passcode", passcode)
                .put("device_id", deviceId)
                .put("device_name", deviceName)
            val req = Request.Builder()
                .url("${ServerEndpoint.URL}/v1/pair")
                .post(body.toString().toRequestBody(json))
                .build()
            execute(req).use { resp ->
                val text = resp.body.string()
                when (resp.code) {
                    200, 201 -> {
                        val o = JSONObject(text)
                        PairResult(
                            token = o.getString("token"),
                            firstSetup = o.optBoolean("first_setup", resp.code == 201),
                            serverName = o.optString("server_name").ifBlank { null },
                            model = o.optString("model").ifBlank { null },
                            models = o.optStrings("models"),
                            effort = o.optString("effort").ifBlank { null },
                            modelEfforts = o.optModelEfforts(),
                            providers = o.optProviders(),
                        )
                    }
                    401 -> throw ApiException.WrongPasscode()
                    429 -> throw ApiException.Locked(resp.retryAfter())
                    else -> throw serverError(resp.code, text)
                }
            }
        }

    suspend fun me(serverUrl: String, token: String): MeResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/v1/me")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        execute(req).use { resp ->
            val text = resp.body.string()
            when (resp.code) {
                200 -> JSONObject(text).let {
                    MeResult(
                        model = it.optString("model").ifBlank { null },
                        models = it.optStrings("models"),
                        effort = it.optString("effort").ifBlank { null },
                        deviceName = it.optString("device_name").ifBlank { null },
                        modelEfforts = it.optModelEfforts(),
                        providers = it.optProviders(),
                    )
                }
                401 -> throw ApiException.Unauthorized(errorMessage(text))
                429 -> throw ApiException.RateLimited(resp.retryAfter(), errorCode(text).ifBlank { "rate_limited" })
                else -> throw serverError(resp.code, text)
            }
        }
    }

    /** Streams the assistant answer as server-sent events. */
    fun chat(
        serverUrl: String,
        token: String,
        dialogId: String,
        messages: List<ChatMessage>,
        model: String?,
        locale: String,
        effort: String?,
        webSearch: Boolean,
    ): Flow<ChatEvent> = flow {
        val body = JSONObject()
            .put("dialog_id", dialogId)
            .put("locale", locale)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        if (model != null) body.put("model", model)
        if (effort != null) body.put("effort", effort)
        body.put("web_search", webSearch)
        val req = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/v1/chat")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(json))
            .build()
        execute(req).use { resp ->
            if (resp.code != 200) {
                val text = resp.body.string()
                when (resp.code) {
                    401 -> throw ApiException.Unauthorized(errorMessage(text))
                    429 -> throw ApiException.RateLimited(resp.retryAfter(), errorCode(text).ifBlank { "rate_limited" })
                    else -> throw serverError(resp.code, text)
                }
            }
            val source = resp.body.source()
            var event = "message"
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            parseChatEvent(event, data.toString())?.let { emit(it) }
                            if (event == "done" || event == "error") return@use
                        }
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
    }.flowOn(Dispatchers.IO)

    private fun parseChatEvent(event: String, data: String): ChatEvent? = runCatching {
        val o = JSONObject(data)
        when (event) {
            "delta" -> ChatEvent.Delta(o.optString("text"))
            "thinking" -> ChatEvent.Thinking(o.optString("text"))
            "done" -> ChatEvent.Done(o.optString("text"), o.optString("model").ifBlank { null }, o.optString("thinking"))
            "error" -> ChatEvent.Error(o.optString("error", "error"), o.optString("message"), o.optInt("retry_after", 0))
            else -> null
        }
    }.getOrElse {
        Log.w(TAG, "Bad SSE payload: $data")
        null
    }

    private fun execute(req: Request): Response = try {
        http.newCall(req).execute()
    } catch (e: IOException) {
        throw ApiException.Network(e.message ?: "network")
    }

    private fun JSONObject.optStrings(name: String): List<String> {
        val a = optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
    }

    private fun JSONObject.optModelEfforts(): Map<String, ModelEfforts> {
        val all = optJSONObject("model_efforts") ?: return emptyMap()
        return all.keys().asSequence().associateWith { model ->
            val o = all.optJSONObject(model)
            ModelEfforts(o?.optStrings("efforts").orEmpty(), o?.optString("default")?.ifBlank { null })
        }
    }

    /** Working providers in server order (it lists them by priority). */
    private fun JSONObject.optProviders(): List<ProviderInfo> {
        val all = optJSONObject("providers") ?: return emptyList()
        return all.keys().asSequence().mapNotNull { id ->
            val o = all.optJSONObject(id) ?: return@mapNotNull null
            if (!o.optBoolean("ok", false)) return@mapNotNull null
            val usage = o.optJSONObject("usage")
            ProviderInfo(
                id = id,
                models = o.optStrings("models"),
                usageRemaining = usage?.takeIf { !it.isNull("remaining") }?.optDouble("remaining")?.toFloat(),
                usageLabel = usage?.optString("label").orEmpty(),
            )
        }.toList()
    }

    private fun serverError(status: Int, text: String) =
        ApiException.Server(status, errorMessage(text), errorCode(text))

    private fun errorCode(text: String): String = runCatching { JSONObject(text).optString("error") }.getOrDefault("")

    private fun Response.retryAfter(): Int = header("Retry-After")?.toIntOrNull() ?: 30

    private fun errorMessage(text: String): String = runCatching {
        val o = JSONObject(text)
        o.optString("message").ifBlank { o.optString("error") }
    }.getOrDefault(text.take(120))

    private companion object {
        const val TAG = "ApiClient"
    }
}
