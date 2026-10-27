package com.fullskat.watch.speech

/** What the speech engine reports while an utterance is being recognised. */
sealed interface ListenEvent {
    data object Ready : ListenEvent
    data class Partial(val text: String) : ListenEvent
    data class Final(val text: String) : ListenEvent
    data class Error(val code: String, val message: String) : ListenEvent
}
