package com.fullskat.phone.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Why dictation stopped without text. */
enum class VoiceError { NOTHING_HEARD, NO_PERMISSION, NETWORK, BUSY, UNAVAILABLE, OTHER }

/**
 * Dictation with Android's own recogniser: the on-device one when the phone has it (API 31+), else the default
 * service. Partial results arrive while the user speaks; [Listener.onFinal] ends a session. No audio reaches the
 * FullSkat server. Main thread only.
 */
class VoiceInput(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onLevel(value: Float)
        fun onError(error: VoiceError)
    }

    private var recognizer: SpeechRecognizer? = null
    private var heard = false
    private var lastPartial = ""

    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    val listening: Boolean get() = recognizer != null

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    fun start(locale: Locale = Locale.getDefault()) {
        destroy()
        when {
            onDeviceAvailable() -> begin(onDevice = true, locale)
            SpeechRecognizer.isRecognitionAvailable(context) -> begin(onDevice = false, locale)
            else -> listener.onError(VoiceError.UNAVAILABLE)
        }
    }

    private fun begin(onDevice: Boolean, locale: Locale) {
        heard = false
        lastPartial = ""
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults.best() ?: return
                if (text.isNotBlank()) {
                    heard = true
                    lastPartial = text
                    listener.onPartial(text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results.best()?.takeIf { it.isNotBlank() } ?: lastPartial
                destroy()
                if (text.isBlank()) listener.onError(VoiceError.NOTHING_HEARD) else listener.onFinal(text)
            }

            override fun onError(error: Int) {
                // The on-device model may lack this language: the default (online) service can still try.
                if (onDevice && !heard && error in LANGUAGE_ERRORS && SpeechRecognizer.isRecognitionAvailable(context)) {
                    destroy()
                    begin(onDevice = false, locale)
                    return
                }
                val partial = lastPartial
                destroy()
                if (partial.isNotBlank()) listener.onFinal(partial) else listener.onError(map(error))
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    /** Stops listening; the text heard so far still arrives as the final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.let {
            runCatching { it.cancel() }
            it.destroy()
        }
        recognizer = null
    }

    private fun Bundle?.best(): String? = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun map(error: Int): VoiceError = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NOTHING_HEARD
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.NO_PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> VoiceError.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.BUSY
        in LANGUAGE_ERRORS -> VoiceError.UNAVAILABLE
        else -> VoiceError.OTHER
    }

    private companion object {
        /** ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE (API 31). */
        val LANGUAGE_ERRORS = setOf(12, 13)
    }
}
