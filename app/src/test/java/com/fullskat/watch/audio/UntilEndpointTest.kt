package com.fullskat.watch.audio

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UntilEndpointTest {
    private fun frames(levels: List<Float>) = levels.map { AudioFrame(ByteArray(2), it) }.asFlow()

    @Test
    fun `completes after the detector decides`() = runBlocking {
        val detector = EndpointDetector(frameMs = 100, noSpeechTimeoutMs = 500)
        val seen = mutableListOf<Float>()
        val out = frames(List(20) { 0.003f }).untilEndpoint(detector, stopRequested = { false }) { seen += it.rms }.toList()
        assertEquals(5, out.size) // the frame that triggers the timeout is the last one
        assertEquals(5, seen.size)
    }

    @Test
    fun `completes when a stop is requested`() = runBlocking {
        val detector = EndpointDetector(frameMs = 100)
        var count = 0
        val out = frames(List(20) { 0.003f }).untilEndpoint(detector, stopRequested = { count >= 3 }) { count++ }.toList()
        assertEquals(3, out.size)
    }
}
