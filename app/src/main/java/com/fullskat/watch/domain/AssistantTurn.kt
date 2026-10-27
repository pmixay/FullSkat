package com.fullskat.watch.domain

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.fullskat.watch.audio.AudioCapture
import com.fullskat.watch.audio.EndpointDetector
import com.fullskat.watch.data.db.DialogDao
import com.fullskat.watch.data.db.DialogEntity
import com.fullskat.watch.data.db.MessageEntity
import com.fullskat.watch.data.db.Role
import com.fullskat.watch.data.net.ApiClient
import com.fullskat.watch.data.net.ApiException
import com.fullskat.watch.data.net.ChatEvent
import com.fullskat.watch.data.net.ChatMessage
import com.fullskat.watch.data.prefs.SettingsRepository
import com.fullskat.watch.speech.ListenEvent
import com.fullskat.watch.speech.LocalSpeech
import com.fullskat.watch.tile.FullSkatTileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

enum class ErrorKind { NO_CONNECTION, UNAUTHORIZED, RATE_LIMITED, SERVER, NOTHING_HEARD, MIC, SPEECH }

sealed interface TurnState {
    data object Idle : TurnState
    data class Listening(val partial: String, val level: Float, val speaking: Boolean) : TurnState
    data object Transcribing : TurnState
    data object Thinking : TurnState
    data class Answering(val text: String, val thinking: String = "") : TurnState
    data class Failed(val kind: ErrorKind, val detail: String?) : TurnState

    val busy: Boolean get() = this !is Idle && this !is Failed
}

/**
 * Runs one assistant turn: capture → on-device recognition → transcript → chat → persist.
 * Owned by a ViewModel; all work happens inside the scope passed to [start].
 */
class AssistantTurn(
    private val context: Context,
    private val dao: DialogDao,
    private val settings: SettingsRepository,
    private val api: ApiClient,
    private val speech: LocalSpeech,
    private val audio: AudioCapture,
) {
    private val _state = MutableStateFlow<TurnState>(TurnState.Idle)
    val state: StateFlow<TurnState> = _state.asStateFlow()

    private var job: Job? = null

    /** Stop signal of the turn currently listening; replay = 1 so an early stop is not lost. */
    @Volatile private var stopSignal: MutableSharedFlow<Unit>? = null

    suspend fun createDialog(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertDialog(DialogEntity(id, "", now, now))
        return id
    }

    fun start(scope: CoroutineScope, dialogId: String) {
        job?.cancel()
        job = scope.launch { runTurn(dialogId) }
    }

    /** User tapped the mic while listening: finish the utterance now. */
    fun finishListening() {
        if (_state.value is TurnState.Listening) stopSignal?.tryEmit(Unit)
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = TurnState.Idle
    }

    fun dismissError() {
        if (_state.value is TurnState.Failed) _state.value = TurnState.Idle
    }

    private suspend fun runTurn(dialogId: String) {
        val cfg = settings.current()
        val token = cfg.token
        if (!cfg.paired || token == null) {
            _state.value = TurnState.Failed(ErrorKind.UNAUTHORIZED, null)
            return
        }
        val transcript = try {
            listen()
        } catch (e: SpeechEngineException) {
            Log.e(TAG, "speech engine", e)
            _state.value = TurnState.Failed(ErrorKind.SPEECH, e.message)
            return
        } catch (e: IllegalStateException) {
            Log.e(TAG, "microphone", e)
            _state.value = TurnState.Failed(ErrorKind.MIC, e.message)
            return
        }
        if (transcript.isBlank()) {
            _state.value = TurnState.Failed(ErrorKind.NOTHING_HEARD, null)
            return
        }
        haptic(cfg.haptics, 20)
        val now = System.currentTimeMillis()
        dao.insertMessage(MessageEntity(dialogId = dialogId, role = Role.USER, content = transcript, createdAt = now))
        val dialog = dao.getDialog(dialogId)
        if (dialog != null && dialog.title.isBlank()) {
            dao.updateDialog(dialogId, transcript.take(60), now)
        } else {
            dao.touchDialog(dialogId, now)
        }
        _state.value = TurnState.Thinking
        answer(dialogId, cfg.serverUrl, token, cfg.model, cfg.effort, cfg.webSearch)
        FullSkatTileService.requestUpdate(context)
    }

    private suspend fun listen(): String = kotlinx.coroutines.coroutineScope {
        // Microphone frames are not replayed, so the model has to be ready before the microphone opens.
        if (!speech.loaded) {
            _state.value = TurnState.Transcribing
            speech.warmUp()
        }
        _state.value = TurnState.Listening("", 0f, false)
        val stopSignal = MutableSharedFlow<Unit>(replay = 1).also { this@AssistantTurn.stopSignal = it }
        val frames = audio.frames().shareIn(this, SharingStarted.WhileSubscribed(), replay = 0)
        val detector = EndpointDetector()
        // Level meter + endpoint detection. Ends (and releases the microphone) as soon as the
        // detector decides or the user taps, without waiting for the recognizer's final transcript.
        val detectorJob = launch {
            frames
                .takeWhile { f ->
                    _state.update { s -> if (s is TurnState.Listening) s.copy(level = f.rms, speaking = detector.speaking) else s }
                    detector.feed(f.rms) == EndpointDetector.Verdict.CONTINUE && stopSignal.replayCache.isEmpty()
                }
                .collect()
            stopSignal.tryEmit(Unit)
        }
        var finalText: String? = null
        var error: ListenEvent.Error? = null
        var hapticsOn = settings.current().haptics
        speech.listen(frames.map { it.pcm }, stopSignal).collect { ev ->
            when (ev) {
                ListenEvent.Ready -> haptic(hapticsOn, 12)
                is ListenEvent.Partial -> {
                    if (ev.text.isNotBlank()) detector.markTextReceived()
                    _state.update { s -> if (s is TurnState.Listening) s.copy(partial = ev.text) else s }
                }
                is ListenEvent.Final -> {
                    finalText = ev.text
                    _state.value = TurnState.Transcribing
                }
                is ListenEvent.Error -> error = ev
            }
        }
        detectorJob.cancel()
        this@AssistantTurn.stopSignal = null
        error?.let { throw SpeechEngineException(it.message) }
        finalText.orEmpty().trim()
    }

    private suspend fun answer(dialogId: String, serverUrl: String, token: String, model: String?, effort: String?, webSearch: Boolean) {
        val history = dao.messages(dialogId)
            .filter { !it.pending || it.content.isNotBlank() }
            .map { ChatMessage(if (it.role == Role.USER) "user" else "assistant", it.content) }
        val msgId = dao.insertMessage(
            MessageEntity(dialogId = dialogId, role = Role.ASSISTANT, content = "", createdAt = System.currentTimeMillis(), pending = true)
        )
        val sb = StringBuilder()
        val tb = StringBuilder() // the model's reasoning summary
        var lastWrite = 0L
        suspend fun persistThrottled() {
            val t = System.currentTimeMillis()
            if (t - lastWrite > 300) {
                lastWrite = t
                dao.updateMessageContent(msgId, sb.toString(), tb.toString(), pending = true)
            }
        }
        try {
            api.chat(serverUrl, token, dialogId, history, model, Locale.getDefault().toLanguageTag(), effort, webSearch).collect { ev ->
                when (ev) {
                    is ChatEvent.Thinking -> {
                        tb.append(ev.text)
                        _state.value = TurnState.Answering(sb.toString(), tb.toString())
                        persistThrottled()
                    }
                    is ChatEvent.Delta -> {
                        sb.append(ev.text)
                        _state.value = TurnState.Answering(sb.toString(), tb.toString())
                        persistThrottled()
                    }
                    is ChatEvent.Done -> {
                        val text = ev.text.ifBlank { sb.toString() }
                        dao.updateMessageContent(msgId, text, ev.thinking.ifBlank { tb.toString() }, pending = false)
                        dao.touchDialog(dialogId, System.currentTimeMillis())
                        _state.value = TurnState.Idle
                    }
                    is ChatEvent.Error -> throw ApiException.Server(502, ev.message.ifBlank { ev.code })
                }
            }
            if (_state.value is TurnState.Answering) {
                // Stream ended without a done event; keep what we have.
                dao.updateMessageContent(msgId, sb.toString(), tb.toString(), pending = false)
                _state.value = TurnState.Idle
            }
        } catch (e: ApiException) {
            finalizePartial(msgId, sb, tb)
            _state.value = TurnState.Failed(e.kind(), e.message)
        } catch (e: CancellationException) {
            // User tapped Stop: keep whatever streamed so far as the answer.
            withContext(NonCancellable) { finalizePartial(msgId, sb, tb) }
            throw e
        }
    }

    private suspend fun finalizePartial(msgId: Long, sb: StringBuilder, tb: StringBuilder) {
        if (sb.isEmpty()) dao.deleteMessage(msgId) else dao.updateMessageContent(msgId, sb.toString(), tb.toString(), pending = false)
    }

    private fun haptic(enabled: Boolean, ms: Long) {
        if (!enabled) return
        runCatching {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun ApiException.kind(): ErrorKind = when (this) {
        is ApiException.Network -> ErrorKind.NO_CONNECTION
        is ApiException.Unauthorized, is ApiException.WrongPasscode -> ErrorKind.UNAUTHORIZED
        is ApiException.RateLimited, is ApiException.Locked -> ErrorKind.RATE_LIMITED
        is ApiException.Server -> ErrorKind.SERVER
    }

    private class SpeechEngineException(message: String) : Exception(message)

    private companion object {
        const val TAG = "AssistantTurn"
    }
}
