package com.fullskat.watch.data.net

/** Errors mapped from HTTP status / server error codes so the UI can pick a message. */
sealed class ApiException(message: String) : Exception(message) {
    class Network(message: String) : ApiException(message)
    class Unauthorized(message: String) : ApiException(message)
    class WrongPasscode : ApiException("invalid_passcode")
    class Locked(val retryAfterSec: Int) : ApiException("locked")
    /** [code] is the server's error code ("rate_limited" for a short pause, "daily_limit" for the daily cap). */
    class RateLimited(val retryAfterSec: Int, val code: String = "rate_limited") : ApiException("rate_limited")

    /** [code] is the server's error code, e.g. "upstream_quota"; empty when it sent none. */
    class Server(val status: Int, message: String, val code: String = "", val retryAfterSec: Int = 0) : ApiException(message)
}

/** Reasoning-effort levels one model accepts (empty = not adjustable) and the one used when none is sent. */
data class ModelEfforts(val efforts: List<String>, val default: String?)

/**
 * One working model provider with its models. [usageRemaining] is the share of its budget left (0..1) for the
 * ring next to its name, null when the provider can't tell; [usageLabel] is e.g. "$4.20".
 */
data class ProviderInfo(
    val id: String,
    val models: List<String>,
    val usageRemaining: Float?,
    val usageLabel: String,
)

data class PairResult(
    val token: String,
    val firstSetup: Boolean,
    val serverName: String?,
    val model: String?,
    val models: List<String>,
    val effort: String?,
    val modelEfforts: Map<String, ModelEfforts> = emptyMap(),
    val providers: List<ProviderInfo> = emptyList(),
)

data class MeResult(
    val model: String?,
    val models: List<String>,
    val effort: String?,
    val deviceName: String?,
    val modelEfforts: Map<String, ModelEfforts> = emptyMap(),
    val providers: List<ProviderInfo> = emptyList(),
)

data class ChatMessage(val role: String, val content: String)

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Thinking(val text: String) : ChatEvent
    data class Done(val text: String, val model: String?, val thinking: String) : ChatEvent
    data class Error(val code: String, val message: String, val retryAfterSec: Int = 0) : ChatEvent
}
