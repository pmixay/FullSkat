package com.fullskat.watch.data.net

/** Errors mapped from HTTP status / server error codes so the UI can pick a message. */
sealed class ApiException(message: String) : Exception(message) {
    class Network(message: String) : ApiException(message)
    class Unauthorized(message: String) : ApiException(message)
    class WrongPasscode : ApiException("invalid_passcode")
    class Locked(val retryAfterSec: Int) : ApiException("locked")
    class RateLimited(val retryAfterSec: Int) : ApiException("rate_limited")
    class Server(val status: Int, message: String) : ApiException(message)
}

data class PairResult(
    val token: String,
    val firstSetup: Boolean,
    val serverName: String?,
    val model: String?,
    val models: List<String>,
)

data class MeResult(val model: String?, val models: List<String>, val deviceName: String?)

data class ChatMessage(val role: String, val content: String)

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Done(val text: String, val model: String?) : ChatEvent
    data class Error(val code: String, val message: String) : ChatEvent
}

sealed interface ListenEvent {
    data object Ready : ListenEvent
    data class Partial(val text: String) : ListenEvent
    data class Speech(val active: Boolean) : ListenEvent
    data class Final(val text: String, val lang: String?) : ListenEvent
    data class Error(val code: String, val message: String) : ListenEvent
}
