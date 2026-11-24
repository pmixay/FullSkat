package com.fullskat.phone.chat

import com.fullskat.core.net.ApiException

/** Why a turn failed, as the chat screen explains it. */
enum class ChatError {
    NO_CONNECTION,
    SIGNED_OUT,
    RATE_LIMITED,
    DAILY_LIMIT,
    QUOTA,
    PROVIDER_BUSY,
    PROVIDER_AUTH,
    NO_PROVIDER,
    MODEL_UNAVAILABLE,
    IMAGE_UNSUPPORTED,
    IMAGE_UNREADABLE,
    SERVER,
    ;

    /** Limits get the bigger card with a wait time. */
    val isUsageLimit: Boolean get() = this == RATE_LIMITED || this == DAILY_LIMIT || this == QUOTA || this == PROVIDER_BUSY

    companion object {
        /** [preparing]: the failure happened while reading or uploading the user's images. */
        fun of(e: Throwable, preparing: Boolean = false): ChatError = when (e) {
            is ApiException.Network -> NO_CONNECTION
            is ApiException.Unauthorized, is ApiException.WrongPasscode -> SIGNED_OUT
            is ApiException.Locked -> RATE_LIMITED
            is ApiException.RateLimited -> if (e.code == "daily_limit") DAILY_LIMIT else RATE_LIMITED
            is ApiException.Server -> fromCode(e.code) ?: if (e.status == 415) IMAGE_UNSUPPORTED else SERVER
            is ApiException -> SERVER
            else -> if (preparing) IMAGE_UNREADABLE else SERVER
        }

        /** The server's error code (also stored on a failed answer) as a kind; null for codes without a special text. */
        fun fromCode(code: String?): ChatError? = when (code) {
            "upstream_quota" -> QUOTA
            "upstream_rate_limited" -> PROVIDER_BUSY
            "upstream_auth" -> PROVIDER_AUTH
            "no_provider", "provider_unavailable" -> NO_PROVIDER
            "model_not_allowed" -> MODEL_UNAVAILABLE
            "unsupported_media" -> IMAGE_UNSUPPORTED
            "rate_limited" -> RATE_LIMITED
            "daily_limit" -> DAILY_LIMIT
            "invalid_token", "missing_token" -> SIGNED_OUT
            else -> null
        }

        fun retryAfter(e: Throwable): Int = when (e) {
            is ApiException.RateLimited -> e.retryAfterSec
            is ApiException.Locked -> e.retryAfterSec
            is ApiException.Server -> e.retryAfterSec
            else -> 0
        }
    }
}
