package com.fullskat.watch.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.fullskat.watch.FullSkatApp
import com.fullskat.watch.audio.EndpointDetector
import com.fullskat.watch.speech.ListenEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * A RecognitionService is required for an app to be selectable as the digital assistant.
 * This one is real: it recognises Russian speech on the watch with the same on-device engine
 * as the app and reports partial and final results through the standard SpeechRecognizer
 * callbacks, so other apps on the watch can use FullSkat's recognition too.
 */
class FullSkatRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var current: Job? = null
    @Volatile private var stopSignal: MutableSharedFlow<Unit>? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        current?.cancel()
        val container = FullSkatApp.container(this)
        current = scope.launch {
            if (!container.speech.loaded) container.speech.warmUp()
            try {
                coroutineScope {
                    val stopSignal = MutableSharedFlow<Unit>(replay = 1).also { this@FullSkatRecognitionService.stopSignal = it }
                    val frames = container.audioCapture.frames().shareIn(this, SharingStarted.WhileSubscribed(), replay = 0)
                    val detector = EndpointDetector()
                    val detectorJob = launch {
                        var announced = false
                        frames
                            .takeWhile { f ->
                                listener.rmsChanged(f.rms * 40f)
                                val verdict = detector.feed(f.rms)
                                if (!announced && detector.speaking) {
                                    announced = true
                                    listener.beginningOfSpeech()
                                }
                                verdict == EndpointDetector.Verdict.CONTINUE && stopSignal.replayCache.isEmpty()
                            }
                            .collect()
                        stopSignal.tryEmit(Unit)
                    }
                    var delivered = false
                    container.speech.listen(frames.map { it.pcm }, stopSignal).collect { ev ->
                        when (ev) {
                            ListenEvent.Ready -> listener.readyForSpeech(Bundle())
                            is ListenEvent.Partial -> {
                                if (ev.text.isNotBlank()) detector.markTextReceived()
                                listener.partialResults(resultsBundle(ev.text))
                            }
                            is ListenEvent.Final -> {
                                listener.endOfSpeech()
                                delivered = true
                                if (ev.text.isBlank()) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                                else listener.results(resultsBundle(ev.text))
                            }
                            is ListenEvent.Error -> {
                                delivered = true
                                listener.error(SpeechRecognizer.ERROR_CLIENT)
                            }
                        }
                    }
                    detectorJob.cancel()
                    if (!delivered) listener.error(SpeechRecognizer.ERROR_CLIENT)
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "audio", e)
                listener.error(SpeechRecognizer.ERROR_AUDIO)
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        stopSignal?.tryEmit(Unit)
    }

    override fun onCancel(listener: Callback) {
        current?.cancel()
        current = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun resultsBundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.9f))
    }

    private companion object {
        const val TAG = "FullSkatRecognition"
    }
}
