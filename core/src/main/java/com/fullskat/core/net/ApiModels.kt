package com.fullskat.core.net

/** Errors mapped from HTTP status / server error codes so the UI can pick a message. */
sealed class ApiException(message: String) : Exception(message) {
    class Network(message: String) : ApiException(message)

    /** The token is unknown or was revoked (the device was unlinked): forget the session. */
    class Unauthorized(message: String) : ApiException(message)
    class WrongPasscode : ApiException("invalid_passcode")
    class Locked(val retryAfterSec: Int) : ApiException("locked")

    /** [code] is the server's error code ("rate_limited" for a short pause, "daily_limit" for the daily cap). */
    class RateLimited(val retryAfterSec: Int, val code: String = "rate_limited") : ApiException("rate_limited")

    /** A link code that is wrong or expired, a link that timed out, an object that is gone. */
    class NotFound(val code: String, message: String) : ApiException(message)

    /** [code] is the server's error code, e.g. "upstream_quota"; empty when it sent none. */
    class Server(val status: Int, message: String, val code: String = "", val retryAfterSec: Int = 0) : ApiException(message)
}

data class User(val id: String, val name: String, val createdAt: Long = 0)

data class Device(
    val id: String,
    val kind: String,
    val name: String,
    val createdAt: Long = 0,
    val lastSeen: Long = 0,
    val current: Boolean = false,
)

object DeviceKind {
    const val PHONE = "phone"
    const val WATCH = "watch"
    const val OTHER = "other"
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

/** What the server offers: models of the working providers, their efforts, and its master switches. */
data class ModelInfo(
    val model: String? = null,
    val models: List<String> = emptyList(),
    val effort: String? = null,
    val modelEfforts: Map<String, ModelEfforts> = emptyMap(),
    val providers: List<ProviderInfo> = emptyList(),
    val webSearch: Boolean = true,
    val figures: Boolean = true,
)

/** A signed-in device: from sign-up, from a finished link, or from `GET /v1/me` (no token then). */
data class Session(
    val token: String,
    val serverName: String?,
    val user: User,
    val device: Device,
    val info: ModelInfo,
    val firstSetup: Boolean = false,
)

data class LinkStart(val linkId: String, val code: String, val expiresIn: Int, val period: Int)

sealed interface LinkStatus {
    data class Pending(val code: String, val expiresIn: Int, val period: Int) : LinkStatus
    data class Linked(val session: Session) : LinkStatus
}

/** The user's shared preferences; null = the server default. */
data class Prefs(
    val model: String? = null,
    val effort: String? = null,
    val webSearch: Boolean? = null,
    val figures: Boolean? = null,
)

data class AttachmentRef(val id: String, val mime: String = "image/jpeg", val width: Int = 0, val height: Int = 0)

data class DialogDto(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val seq: Long,
)

data class MessageDto(
    val id: String,
    val dialogId: String,
    val role: String,
    val content: String,
    val thinking: String,
    val sources: List<WebSource>,
    val attachments: List<AttachmentRef>,
    val model: String?,
    val status: String,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val seq: Long,
)

data class SyncPage(
    val dialogs: List<DialogDto>,
    val messages: List<MessageDto>,
    val prefs: Prefs?,
    val seq: Long,
    val more: Boolean,
)

/** The request of one chat turn (docs/PROTOCOL.md, Chat). Null options mean the user's prefs / server default. */
data class ChatRequest(
    val dialogId: String,
    val messageId: String,
    val content: String,
    val attachments: List<String>,
    val createdAt: Long,
    val answerId: String,
    val locale: String,
    val model: String? = null,
    val effort: String? = null,
    val webSearch: Boolean? = null,
    val figures: Boolean? = null,
)

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Thinking(val text: String) : ChatEvent

    /** [message]: the saved answer as the server has it; [seq]: the user's sequence after it. */
    data class Done(
        val text: String,
        val model: String?,
        val thinking: String,
        val sources: List<WebSource> = emptyList(),
        val message: MessageDto? = null,
        val seq: Long = 0,
    ) : ChatEvent

    data class Error(val code: String, val message: String, val retryAfterSec: Int = 0) : ChatEvent
}

/** What `GET /v1/events` reports. */
sealed interface LiveEvent {
    data class Changed(val seq: Long) : LiveEvent
    data object Revoked : LiveEvent
}

object MessageStatus {
    const val DONE = "done"
    const val STREAMING = "streaming"
    const val ERROR = "error"

    /** Local only: a user message on its way to the server. */
    const val SENDING = "sending"
}

object Roles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}
