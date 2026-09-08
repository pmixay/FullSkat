package com.fullskat.watch.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointDetectorTest {
    private fun feed(detector: EndpointDetector, level: Float, frames: Int): EndpointDetector.Verdict {
        var last = EndpointDetector.Verdict.CONTINUE
        repeat(frames) { last = detector.feed(level) }
        return last
    }

    @Test
    fun `silence only times out without speech`() {
        val d = EndpointDetector(frameMs = 100, noSpeechTimeoutMs = 1_000)
        assertEquals(EndpointDetector.Verdict.CONTINUE, feed(d, 0.003f, 9))
        assertEquals(EndpointDetector.Verdict.TIMEOUT, d.feed(0.003f))
        assertFalse(d.speaking)
    }

    @Test
    fun `speech then silence ends the utterance`() {
        val d = EndpointDetector(frameMs = 100, silenceMs = 500)
        assertEquals(EndpointDetector.Verdict.CONTINUE, feed(d, 0.003f, 3))
        assertEquals(EndpointDetector.Verdict.CONTINUE, feed(d, 0.2f, 5))
        assertTrue(d.speaking)
        assertEquals(EndpointDetector.Verdict.CONTINUE, feed(d, 0.003f, 4))
        assertEquals(EndpointDetector.Verdict.END, d.feed(0.003f))
    }

    @Test
    fun `short blips do not count as speech`() {
        val d = EndpointDetector(frameMs = 100, minSpeechMs = 300)
        d.feed(0.3f)
        d.feed(0.003f)
        assertFalse(d.speaking)
    }

    @Test
    fun `hard cap ends even while speaking`() {
        val d = EndpointDetector(frameMs = 100, maxUtteranceMs = 1_000)
        assertEquals(EndpointDetector.Verdict.CONTINUE, feed(d, 0.2f, 9))
        assertEquals(EndpointDetector.Verdict.END, d.feed(0.2f))
    }
}
