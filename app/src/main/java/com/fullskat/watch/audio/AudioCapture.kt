package com.fullskat.watch.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.sqrt

class AudioFrame(val pcm: ByteArray, val rms: Float)

/**
 * 16 kHz mono PCM16 capture in 100 ms frames. The microphone is opened only while the
 * returned flow is collected and released as soon as collection stops.
 */
class AudioCapture {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 100
        const val FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000
        private const val TAG = "AudioCapture"
    }

    @SuppressLint("MissingPermission") // permission is checked by the caller before collecting
    fun frames(): Flow<AudioFrame> = flow {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuf, FRAME_BYTES * 4)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        val suppressor = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull() else null
        val agc = if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(record.audioSessionId) }.getOrNull() else null
        suppressor?.enabled = true
        agc?.enabled = true
        try {
            record.startRecording()
            val buf = ByteArray(FRAME_BYTES)
            while (true) {
                var filled = 0
                while (filled < FRAME_BYTES) {
                    val n = record.read(buf, filled, FRAME_BYTES - filled, AudioRecord.READ_BLOCKING)
                    if (n < 0) throw IllegalStateException("AudioRecord read error $n")
                    filled += n
                }
                emit(AudioFrame(buf.copyOf(), rms(buf)))
            }
        } finally {
            runCatching { record.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
            suppressor?.release()
            agc?.release()
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun rms(pcm: ByteArray): Float {
        var sum = 0.0
        var i = 0
        val n = pcm.size / 2
        while (i < pcm.size - 1) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        return if (n == 0) 0f else (sqrt(sum / n) / 32768.0).toFloat()
    }
}
