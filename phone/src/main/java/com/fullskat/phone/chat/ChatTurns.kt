package com.fullskat.phone.chat

import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.fullskat.core.FullSkatCore
import com.fullskat.core.chat.ChatProgress
import com.fullskat.core.net.AttachmentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What the user sends: text plus images to upload ([images]) or already uploaded ones ([uploaded], a retry). */
data class Draft(val text: String, val images: List<Uri> = emptyList(), val uploaded: List<AttachmentRef> = emptyList()) {
    val isEmpty: Boolean get() = text.isBlank() && images.isEmpty() && uploaded.isEmpty()
}

/** One dialog's turn in progress, for its chat screen. */
sealed interface Turn {
    /** Images are being prepared and uploaded; [done] of them are on the server. */
    data class Uploading(val draft: Draft, val done: Int) : Turn

    /** The question is on its way; [answerId] once it is saved locally. */
    data class Waiting(val answerId: String?) : Turn

    /** The answer streams in (the live text is ahead of the throttled copy in the database). */
    data class Answering(val answerId: String, val text: String, val thinking: String) : Turn

    /** [draft] comes back when nothing reached the server (an image failed), so the composer can offer it again. */
    data class Failed(val error: ChatError, val retryAfterSec: Int = 0, val draft: Draft? = null) : Turn
}

/**
 * Turns run here, for the whole process, not in a screen: leaving a chat must not stop its answer (cancelling the
 * collection of [com.fullskat.core.chat.ChatRepository.send] means "stop"). One turn per dialog at a time.
 */
class ChatTurns(private val core: FullSkatCore, private val scope: CoroutineScope = core.scope) {
    private val _turns = MutableStateFlow<Map<String, Turn>>(emptyMap())
    val turns: StateFlow<Map<String, Turn>> = _turns.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun turn(dialogId: String): Flow<Turn?> = turns.map { it[dialogId] }.distinctUntilChanged()

    fun busy(dialogId: String): Boolean = jobs[dialogId]?.isActive == true

    /** Starts a turn; false when there is nothing to send or the dialog is busy. */
    fun send(dialogId: String, draft: Draft, locale: String = Locale.getDefault().toLanguageTag()): Boolean {
        if (draft.isEmpty || busy(dialogId)) return false
        set(dialogId, if (draft.images.isEmpty()) Turn.Waiting(null) else Turn.Uploading(draft, 0))
        val job = scope.launch(start = CoroutineStart.LAZY) { run(dialogId, draft, locale) }
        jobs[dialogId] = job
        job.invokeOnCompletion { jobs.remove(dialogId, job) }
        job.start()
        return true
    }

    private suspend fun run(dialogId: String, draft: Draft, locale: String) {
        var preparing = true
        try {
            val refs = draft.uploaded.toMutableList()
            draft.images.forEachIndexed { i, uri ->
                set(dialogId, Turn.Uploading(draft, i))
                refs += core.attachments.upload(core.attachments.prepare(uri))
            }
            preparing = false
            set(dialogId, Turn.Waiting(null))
            var answerId: String? = null
            core.chat.send(dialogId, draft.text.trim(), refs, locale).collect { p ->
                when (p) {
                    is ChatProgress.Started -> {
                        answerId = p.answerId
                        set(dialogId, Turn.Waiting(p.answerId))
                    }
                    is ChatProgress.Answering -> answerId?.let { set(dialogId, Turn.Answering(it, p.text, p.thinking)) }
                    is ChatProgress.Done -> Unit
                }
            }
            clear(dialogId)
        } catch (e: CancellationException) {
            clear(dialogId)
            throw e
        } catch (e: Exception) {
            set(dialogId, Turn.Failed(ChatError.of(e, preparing), ChatError.retryAfter(e), draft.takeIf { preparing }))
        }
    }

    /** Stops the turn of [dialogId]: an upload is abandoned, an answer ends with what it has. */
    fun stop(dialogId: String) {
        jobs[dialogId]?.cancel()
    }

    /** The draft of a failed upload, handed back once (to the composer). */
    fun takeDraft(dialogId: String): Draft? {
        var draft: Draft? = null
        _turns.update { all ->
            val failed = all[dialogId] as? Turn.Failed
            draft = failed?.draft
            if (failed?.draft != null) all + (dialogId to failed.copy(draft = null)) else all
        }
        return draft
    }

    fun dismiss(dialogId: String) {
        _turns.update { if (it[dialogId] is Turn.Failed) it - dialogId else it }
    }

    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        _turns.value = emptyMap()
    }

    /** Shows [turn] for [dialogId] without running anything (screenshots). */
    @VisibleForTesting
    internal fun show(dialogId: String, turn: Turn) = set(dialogId, turn)

    private fun set(dialogId: String, turn: Turn) = _turns.update { it + (dialogId to turn) }

    private fun clear(dialogId: String) = _turns.update { it - dialogId }
}
