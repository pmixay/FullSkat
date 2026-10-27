package com.fullskat.watch.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test
    fun capitalizesFirstRussianLetter() {
        assertEquals("Привет, как дела", SpeechText.normalize("привет, как дела"))
    }

    @Test
    fun collapsesWhitespaceAndTrims() {
        assertEquals("Какая погода завтра", SpeechText.normalize("  какая   погода\nзавтра "))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", SpeechText.normalize("   "))
    }

    @Test
    fun keepsDigitsAsTheyAre() {
        assertEquals("25 градусов", SpeechText.normalize("25 градусов"))
    }
}
