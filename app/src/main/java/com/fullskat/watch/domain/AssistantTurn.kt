package com.fullskat.watch.domain

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.fullskat.watch.audio.AudioCapture
import com.fullskat.watch.audio.EndpointDetector
import com.fullskat.watch.audio.untilEndpoint
import com.fullskat.core.FullSkatCore
import com.fullskat.core.chat.ChatOptions
import com.fullskat.core.chat.ChatProgress
import com.fullskat.core.net.ApiException
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class ErrorKind {
    NO_CONNECTION,
    UNAUTHORIZED,

    /** Usage limits, shown as their own block: a short pause, the server's daily cap, spent provider credits. */
    RATE_LIMITED,
    DAILY_LIMIT,
    QUOTA,
    PROVIDER_BUSY,

    /** The model provider rejected the server's key, or none works right now, or the chosen model is gone. */
    PROVIDER_AUTH,
    NO_PROVIDER,
    MODEL_UNAVAILABLE,
    SERVER,
    NOTHING_HEARD,
    MIC,
    SPEECH,
    ;

    val isUsageLimit: Boolean get() = this == RATE_LIMITED || this == DAILY_LIMIT || this == QUOTA || this == PROVIDER_BUSY
}

sealed interface TurnState {
    data object Idle : TurnState
    data class Listening(val partial: String, val level: Float, val speaking: Boolean) : TurnState
    data object Transcribing : TurnState
    data object Thinking : TurnState
    data class Answering(val text: String, val thinking: String = "") : TurnState
    data class Failed(val kind: ErrorKind, val detail: String?, val retryAfterSec: Int = 0) : TurnState

    val busy: Boolean get() = this !is Idle && this !is Failed
}

/**
 * Runs one assistant turn: capture → on-device recognition → transcript → chat → persist.
 * Owned by a ViewModel; all work happens inside the scope passed to [start].
 */
class AssistantTurn(
    private val context: Context,
    private val core: FullSkatCore,
    private val settings: SettingsRepository,
    private val speech: LocalSpeech,
    private val audio: AudioCapture,
) {
    private val _state = MutableStateFlow<TurnState>(TurnState.Idle)
    val state: StateFlow<TurnState> = _state.asStateFlow()

    private var job: Job? = null

    /** Stop signal of the turn currently listening; replay = 1 so an early stop is not lost. */
    @Volatile private var stopSignal: MutableSharedFlow<Unit>? = null

    /** A new empty dialog (local until its first question reaches the server). */
    suspend fun createDialog(): String = core.sync.newDialog()

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

    /**
     * Closes the microphone if this turn is still listening (or loading the speech model / finishing the transcript),
     * so nothing is recorded or sent; an answer that is already on its way is left alone. True if it stopped one.
     */
    fun cancelListening(): Boolean {
        val s = _state.value
        if (s !is TurnState.Listening && s !is TurnState.Transcribing) return false
        cancel()
        return true
    }

    fun dismissError() {
        if (_state.value is TurnState.Failed) _state.value = TurnState.Idle
    }

    private suspend fun runTurn(dialogId: String) {
        val cfg = settings.current()
        if (!cfg.paired) {
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
        _state.value = TurnState.Thinking
        answer(dialogId, transcript)
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
        val detector = EndpointDetector()
        // Level meter + endpoint detection ride on the audio stream itself: it ends (and releases the
        // microphone) as soon as the detector decides or the user taps, and the recognizer then flushes.
        val pcm = audio.frames().untilEndpoint(detector, stopRequested = { stopSignal.replayCache.isNotEmpty() }) { f ->
            _state.update { s -> if (s is TurnState.Listening) s.copy(level = f.rms, speaking = detector.speaking) else s }
        }
        var finalText: String? = null
        var error: ListenEvent.Error? = null
        var hapticsOn = settings.current().haptics
        speech.listen(pcm, stopSignal).collect { ev ->
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
        this@AssistantTurn.stopSignal = null
        error?.let { throw SpeechEngineException(it.message) }
        finalText.orEmpty().trim()
    }

    /**
     * Sends the question and follows the answer. The question and the growing answer are saved in the synced copy
     * (so the phone sees them too); the screen shows the live text from [TurnState.Answering]. The model, effort,
     * web search and drawings come from the account's shared preferences on the server.
     */
    private suspend fun answer(dialogId: String, question: String) {
        try {
            core.chat.send(dialogId, question, locale = Locale.getDefault().toLanguageTag(), options = ChatOptions()).collect { p ->
                when (p) {
                    is ChatProgress.Started -> Unit
                    is ChatProgress.Answering -> _state.value = TurnState.Answering(p.text, p.thinking)
                    is ChatProgress.Done -> _state.value = TurnState.Idle
                }
            }
            if (_state.value is TurnState.Answering || _state.value is TurnState.Thinking) _state.value = TurnState.Idle
        } catch (e: ApiException) {
            val kind = e.kind()
            _state.value = TurnState.Failed(kind, e.message, e.retryAfterSec())
            // The provider list changes while the server runs; relearn it so the next try uses a working model.
            if (kind == ErrorKind.MODEL_UNAVAILABLE || kind == ErrorKind.NO_PROVIDER || kind == ErrorKind.PROVIDER_AUTH) {
                core.account.refresh()
            }
        }
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
        is ApiException.NotFound -> ErrorKind.SERVER
        is ApiException.Locked -> ErrorKind.RATE_LIMITED
        is ApiException.RateLimited -> if (code == "daily_limit") ErrorKind.DAILY_LIMIT else ErrorKind.RATE_LIMITED
        is ApiException.Server -> when (code) {
            "upstream_quota" -> ErrorKind.QUOTA
            "upstream_rate_limited" -> ErrorKind.PROVIDER_BUSY
            "upstream_auth" -> ErrorKind.PROVIDER_AUTH
            "no_provider", "provider_unavailable" -> ErrorKind.NO_PROVIDER
            "model_not_allowed" -> ErrorKind.MODEL_UNAVAILABLE
            "invalid_token", "missing_token" -> ErrorKind.UNAUTHORIZED
            else -> ErrorKind.SERVER
        }
    }

    private fun ApiException.retryAfterSec(): Int = when (this) {
        is ApiException.RateLimited -> retryAfterSec
        is ApiException.Locked -> retryAfterSec
        is ApiException.Server -> retryAfterSec
        else -> 0
    }

    private class SpeechEngineException(message: String) : Exception(message)

    private companion object {
        const val TAG = "AssistantTurn"
    }
}
