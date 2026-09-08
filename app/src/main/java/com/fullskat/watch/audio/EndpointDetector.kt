package com.fullskat.watch.audio

/**
 * Client-side end-of-utterance detection on frame RMS levels. It exists to stop streaming
 * (and the microphone) early without waiting for the server, which saves radio and battery.
 *
 * Adaptive noise floor: tracks the quietest recent level and treats anything clearly above
 * it as speech. Ends when speech was heard (or text arrived) and then silence lasted
 * [silenceMs]; times out after [noSpeechTimeoutMs] if nothing was ever heard.
 */
class EndpointDetector(
    private val frameMs: Int = AudioCapture.FRAME_MS,
    private val silenceMs: Int = SILENCE_AFTER_TEXT_MS,
    private val maxUtteranceMs: Int = MAX_UTTERANCE_MS,
    private val minSpeechMs: Int = 200,
    private val noSpeechTimeoutMs: Int = NO_TEXT_TIMEOUT_MS,
) {
    private var noiseFloor = 0.004f
    private var speechMs = 0
    private var silenceAfterSpeechMs = 0
    private var totalMs = 0
    var speaking: Boolean = false
        private set

    enum class Verdict { CONTINUE, END, TIMEOUT }

    /** The server sent recognised text: from now on the utterance ends after [silenceMs] of quiet. */
    fun markTextReceived() {
        speaking = true
        speechMs = maxOf(speechMs, minSpeechMs)
    }

    fun feed(rms: Float): Verdict {
        totalMs += frameMs
        if (rms < noiseFloor) noiseFloor = noiseFloor * 0.8f + rms * 0.2f
        else noiseFloor += (rms - noiseFloor) * 0.005f
        val threshold = maxOf(noiseFloor * 3.5f, 0.012f)
        val isSpeech = rms > threshold
        if (isSpeech) {
            speechMs += frameMs
            silenceAfterSpeechMs = 0
            if (speechMs >= minSpeechMs) speaking = true
        } else if (speaking) {
            silenceAfterSpeechMs += frameMs
        } else {
            speechMs = 0
        }
        return when {
            totalMs >= maxUtteranceMs -> Verdict.END
            speaking && silenceAfterSpeechMs >= silenceMs -> Verdict.END
            !speaking && totalMs >= noSpeechTimeoutMs -> Verdict.TIMEOUT
            else -> Verdict.CONTINUE
        }
    }

    companion object {
        /** Nothing heard and no text yet: give up after this long. */
        const val NO_TEXT_TIMEOUT_MS = 30_000
        /** Text already recognised: end the turn after this much quiet. */
        const val SILENCE_AFTER_TEXT_MS = 5_000
        const val MAX_UTTERANCE_MS = 60_000
    }
}
