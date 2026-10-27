package com.fullskat.watch.speech

import java.util.Locale

/**
 * The Russian model emits lower-case words without punctuation, with the odd stray space.
 * Make the text look like a sentence the way the chat model (and the user, on the watch screen) expects it.
 */
object SpeechText {
    private val ru = Locale.forLanguageTag("ru-RU")
    private val spaces = Regex("\\s+")

    fun normalize(raw: String): String {
        val text = raw.replace(spaces, " ").trim()
        if (text.isEmpty()) return ""
        return text.substring(0, 1).uppercase(ru) + text.substring(1)
    }
}
