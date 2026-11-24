package com.fullskat.core.chat

import com.fullskat.core.db.CoreDatabase
import com.fullskat.core.db.DialogEntity
import com.fullskat.core.db.MessageEntity
import com.fullskat.core.net.ApiClient
import com.fullskat.core.net.ApiException
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.net.ChatEvent
import com.fullskat.core.net.ChatRequest
import com.fullskat.core.net.JsonModels
import com.fullskat.core.net.MessageStatus
import com.fullskat.core.net.Roles
import com.fullskat.core.net.WebSources
import com.fullskat.core.session.SessionStore
import com.fullskat.core.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** How a turn is going, for the screen that asked. */
sealed interface ChatProgress {
    /** The question is saved locally; [answerId] is the assistant message that will hold the answer. */
    data class Started(val messageId: String, val answerId: String) : ChatProgress
    data class Answering(val text: String, val thinking: String) : ChatProgress
    data class Done(val text: String) : ChatProgress
}

/** Per-turn choices; null means the user's shared preferences (and then the server default). */
data class ChatOptions(
    val model: String? = null,
    val effort: String? = null,
    val webSearch: Boolean? = null,
    val figures: Boolean? = null,
)

/**
 * Sends a question and follows the answer (docs/PROTOCOL.md, Chat). The question and an empty answer are saved
 * locally first, so they show at once; the answer text is written as it streams (throttled) and replaced by
 * the server's copy when it is done. The server finishes an answer even if this device goes away; [stop]
 * ends it early, keeping what arrived.
 */
class ChatRepository(
    private val api: ApiClient,
    private val db: CoreDatabase,
    private val session: SessionStore,
    private val sync: SyncEngine,
) {
    private val dao = db.dao()

    /** Throws [ApiException] on failure (after saving what streamed); cancelling the collector stops the answer. */
    fun send(
        dialogId: String,
        text: String,
        attachments: List<AttachmentRef> = emptyList(),
        locale: String,
        options: ChatOptions = ChatOptions(),
    ): Flow<ChatProgress> = flow {
        val token = session.token() ?: throw ApiException.Unauthorized("not signed in")
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val answerId = UUID.randomUUID().toString()
        val existing = dao.dialog(dialogId)
        val title = existing?.title?.ifBlank { null } ?: text.trim().take(60)
        dao.upsertDialog(
            (existing ?: DialogEntity(dialogId, title, now, now, local = true)).copy(title = title, updatedAt = now),
        )
        dao.upsertMessage(
            MessageEntity(
                id = messageId, dialogId = dialogId, role = Roles.USER, content = text, createdAt = now,
                status = MessageStatus.SENDING, attachments = JsonModels.attachmentsJson(attachments),
            ),
        )
        dao.upsertMessage(MessageEntity(id = answerId, dialogId = dialogId, role = Roles.ASSISTANT, content = "", createdAt = now + 1, status = MessageStatus.STREAMING))
        emit(ChatProgress.Started(messageId, answerId))

        val sb = StringBuilder()
        val tb = StringBuilder()
        var lastWrite = 0L
        var finished = false
        var heard = false
        suspend fun write(status: String) {
            dao.updateMessageText(answerId, sb.toString(), tb.toString(), status, System.currentTimeMillis())
        }
        sync.activeAnswers += answerId
        try {
            val request = ChatRequest(
                dialogId = dialogId, messageId = messageId, content = text, attachments = attachments.map { it.id },
                createdAt = now, answerId = answerId, locale = locale,
                model = options.model, effort = options.effort, webSearch = options.webSearch, figures = options.figures,
            )
            api.chat(token, request).collect { ev ->
                if (!heard) {
                    heard = true
                    dao.updateMessageStatus(messageId, MessageStatus.DONE, null) // the server has the question
                }
                when (ev) {
                    is ChatEvent.Thinking, is ChatEvent.Delta -> {
                        if (ev is ChatEvent.Thinking) tb.append(ev.text) else sb.append((ev as ChatEvent.Delta).text)
                        val t = System.currentTimeMillis()
                        if (t - lastWrite > 250) {
                            lastWrite = t
                            write(MessageStatus.STREAMING)
                        }
                        emit(ChatProgress.Answering(sb.toString(), tb.toString()))
                    }
                    is ChatEvent.Done -> {
                        val saved = ev.message
                        if (saved != null) dao.upsertMessage(SyncEngine.entityOf(saved))
                        else dao.upsertMessage(
                            (dao.message(answerId) ?: return@collect).copy(
                                content = ev.text.ifBlank { sb.toString() }, thinking = ev.thinking.ifBlank { tb.toString() },
                                sources = WebSources.encode(ev.sources), model = ev.model, status = MessageStatus.DONE,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        dao.touchDialog(dialogId, System.currentTimeMillis())
                        finished = true
                        emit(ChatProgress.Done(ev.text.ifBlank { sb.toString() }))
                    }
                    is ChatEvent.Error -> {
                        finished = true
                        dao.updateMessageText(answerId, sb.toString(), tb.toString(), MessageStatus.ERROR, System.currentTimeMillis())
                        dao.updateMessageStatus(answerId, MessageStatus.ERROR, ev.code)
                        throw ApiException.Server(502, ev.message.ifBlank { ev.code }, ev.code, ev.retryAfterSec)
                    }
                }
            }
            if (!finished) {
                // the stream ended without done: the server still finishes the answer; sync brings it
                write(if (sb.isEmpty()) MessageStatus.STREAMING else MessageStatus.DONE)
            }
        } catch (e: CancellationException) {
            // Stop: the server ends the answer with what it has; keep the same text here
            withContext(NonCancellable) {
                runCatching { api.stop(token, answerId) }
                if (sb.isEmpty()) dao.deleteMessage(answerId) else write(MessageStatus.DONE)
            }
            throw e
        } catch (e: ApiException) {
            withContext(NonCancellable) {
                if (!heard) {
                    // the question never reached the server
                    dao.deleteMessage(answerId)
                    dao.updateMessageStatus(messageId, MessageStatus.ERROR, e.message)
                } else if (!finished) {
                    if (sb.isEmpty()) dao.deleteMessage(answerId) else write(MessageStatus.DONE)
                }
            }
            throw e
        } finally {
            sync.activeAnswers -= answerId
            withContext(NonCancellable) { runCatching { sync.sync() } }
        }
    }

    /** Ends an answer that is streaming (here or on another device of the user). */
    suspend fun stop(answerId: String) {
        val token = session.token() ?: return
        runCatching { api.stop(token, answerId) }
    }

    /** Sends a question that failed to reach the server again, as a new turn. */
    suspend fun discardFailed(messageId: String) = dao.deleteMessage(messageId)
}
