package com.fullskat.watch.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device Russian speech recognition: sherpa-onnx with the streaming Zipformer "small-ru-vosk"
 * transducer (int8, ~23 MB, Apache-2.0). Audio never leaves the watch.
 *
 * Why this model: it is the only Russian model that streams (words appear while you are still
 * talking) and is small enough for a watch. The offline Russian models (GigaAM, Zipformer-ru) are
 * 60-200 MB and cannot give live partials; T-one (123 MB, telephone-band) is too heavy.
 */
class LocalSpeech(private val context: Context) {
    private val lock = Any()
    @Volatile private var recognizer: OnlineRecognizer? = null

    /** Lock-free: callers on the main thread must not wait for a model load that is still in progress. */
    val loaded: Boolean get() = recognizer != null

    /** Loads the model (about a second) so the first utterance starts without delay. */
    suspend fun warmUp() {
        withContext(Dispatchers.Default) { runCatching { recognizer() }.onFailure { Log.w(TAG, "warm-up failed", it) } }
    }

    private fun recognizer(): OnlineRecognizer = synchronized(lock) {
        recognizer ?: create().also { recognizer = it }
    }

    /**
     * Loads from a copy of the model in internal storage rather than through the AssetManager: the
     * files stay in the OS page cache across process restarts (swiping the app away), so a cold start
     * no longer re-reads and inflates ~23 MB out of the APK. The copy is made once per installed
     * build; if anything about it fails, loading falls back to the APK assets.
     */
    private fun create(): OnlineRecognizer {
        val dir = runCatching { persistentModelDir() }.onFailure { Log.w(TAG, "model copy failed, using assets", it) }.getOrNull()
        if (dir != null) {
            try {
                return OnlineRecognizer(config = config(dir.absolutePath))
            } catch (e: Throwable) {
                Log.w(TAG, "loading the copied model failed, using assets", e)
                dir.deleteRecursively()
            }
        }
        return OnlineRecognizer(assetManager = context.assets, config = config(ASSET_DIR))
    }

    private fun persistentModelDir(): File {
        val build = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val root = File(context.noBackupFilesDir, "asr")
        val target = File(root, "ru-$build")
        if (MODEL_FILES.all { File(target, it).length() > 0 }) return target
        // Drop copies of older builds, then copy into a temp dir and rename so a kill mid-copy never leaves a half model.
        root.listFiles()?.forEach { it.deleteRecursively() }
        val tmp = File(root, "tmp-$build").apply { mkdirs() }
        for (name in MODEL_FILES) {
            context.assets.open("$ASSET_DIR/$name").use { input -> File(tmp, name).outputStream().use { input.copyTo(it) } }
        }
        if (!tmp.renameTo(target)) throw java.io.IOException("cannot publish the model copy")
        return target
    }

    /**
     * Recognises one utterance. [audio] is 16 kHz mono PCM16 little-endian; collection of it stops
     * (and the final transcript is produced) when [stopSignal] emits or [audio] completes.
     */
    fun listen(audio: Flow<ByteArray>, stopSignal: Flow<Unit>): Flow<ListenEvent> = channelFlow {
        val rec = try {
            recognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "cannot load the speech model", e)
            send(ListenEvent.Error("model", e.message ?: "speech model failed to load"))
            return@channelFlow
        }
        val stream = rec.createStream()
        try {
            send(ListenEvent.Ready)
            var last = ""
            val capture = launch {
                audio.collect { pcm ->
                    stream.acceptWaveform(toFloats(pcm), SAMPLE_RATE)
                    while (rec.isReady(stream)) rec.decode(stream)
                    val text = SpeechText.normalize(rec.getResult(stream).text)
                    if (text != last) {
                        last = text
                        send(ListenEvent.Partial(text))
                    }
                }
            }
            val stopper = launch {
                stopSignal.first()
                capture.cancel()
            }
            capture.join()
            stopper.cancel()

            // Flush: trailing silence lets the transducer emit the last word, then close the stream.
            stream.acceptWaveform(FloatArray(TAIL_SILENCE_SAMPLES), SAMPLE_RATE)
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            send(ListenEvent.Final(SpeechText.normalize(rec.getResult(stream).text)))
        } finally {
            stream.release()
        }
    }.flowOn(Dispatchers.Default)

    private fun config(dir: String): OnlineRecognizerConfig {
        return OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.onnx",
                    joiner = "$dir/joiner.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = THREADS,
            ),
            // The app's own endpoint detector decides when the user is done (2.5 s after the last word),
            // so the recognizer must not cut the utterance on its shorter pauses.
            enableEndpoint = false,
            // Beam search keeps a few hypotheses alive; it fixes many Russian word-ending errors that
            // greedy decoding commits to early, for a small CPU cost.
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
        )
    }

    private fun toFloats(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        var i = 0
        for (n in out.indices) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort()
            out[n] = sample / 32768f
            i += 2
        }
        return out
    }

    private companion object {
        const val TAG = "LocalSpeech"
        const val ASSET_DIR = "asr/ru"
        val MODEL_FILES = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
        const val SAMPLE_RATE = 16_000
        const val TAIL_SILENCE_SAMPLES = SAMPLE_RATE * 2 / 3
        const val THREADS = 2
    }
}
