package com.fullskat.watch.data.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * One utterance = one WebSocket. Audio frames go up as binary; partial and final
 * transcripts come down as JSON text frames (docs/PROTOCOL.md, "Listening").
 */
class ListenClient(private val http: OkHttpClient) {

    /** Sending this many bytes ahead of the socket means the link is stalling; drop frames instead of buffering. */
    private val maxQueuedBytes = 64L * 1024

    fun listen(
        serverUrl: String,
        token: String,
        lang: String,
        audio: Flow<ByteArray>,
        stopSignal: Flow<Unit>,
    ): Flow<ListenEvent> = callbackFlow {
        val wsUrl = serverUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/v1/listen"
        val req = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()

        val scope = CoroutineScope(Dispatchers.IO + Job())
        var audioJob: Job? = null
        var stopJob: Job? = null
        var finished = false

        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject()
                        .put("type", "start")
                        .put("sample_rate", 16000)
                        .put("encoding", "pcm_s16le")
                        .put("channels", 1)
                        .put("lang", lang)
                        .toString()
                )
                audioJob = scope.launch {
                    audio.catch { Log.w(TAG, "audio flow failed", it) }.collect { frame ->
                        if (webSocket.queueSize() < maxQueuedBytes) {
                            webSocket.send(frame.toByteString())
                        }
                    }
                }
                stopJob = scope.launch {
                    stopSignal.collect {
                        audioJob?.cancel()
                        webSocket.send(JSONObject().put("type", "stop").toString())
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                val event = when (o.optString("type")) {
                    "ready" -> ListenEvent.Ready
                    "partial" -> ListenEvent.Partial(o.optString("text"))
                    "speech" -> ListenEvent.Speech(o.optBoolean("active"))
                    "final" -> ListenEvent.Final(o.optString("text"), o.optString("lang").ifBlank { null })
                    "error" -> ListenEvent.Error(o.optString("error", "error"), o.optString("message"))
                    else -> null
                } ?: return
                trySendBlocking(event)
                if (event is ListenEvent.Final || event is ListenEvent.Error) {
                    finished = true
                    audioJob?.cancel()
                    close()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished) trySendBlocking(ListenEvent.Error("closed", reason.ifBlank { "closed $code" }))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (finished) {
                    close()
                    return
                }
                val code = when (response?.code) {
                    401 -> "unauthorized"
                    429 -> "rate_limited"
                    else -> "network"
                }
                trySendBlocking(ListenEvent.Error(code, t.message ?: response?.message ?: "failure"))
                close()
            }
        })

        awaitClose {
            audioJob?.cancel()
            stopJob?.cancel()
            scope.coroutineContext[Job]?.cancel()
            ws.close(1000, null)
        }
    }

    private companion object {
        const val TAG = "ListenClient"
    }
}
