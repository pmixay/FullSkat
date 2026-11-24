package com.fullskat.phone.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fullskat.core.db.DialogEntity
import com.fullskat.core.db.MessageEntity
import com.fullskat.core.net.MessageStatus
import com.fullskat.core.session.SessionState
import com.fullskat.phone.PhoneContainer
import com.fullskat.phone.chat.Draft
import com.fullskat.phone.chat.Turn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One dialog: its messages, the turn in progress and the composer's draft. */
class ChatViewModel(private val app: PhoneContainer, val dialogId: String) : ViewModel() {
    private val core = app.core
    private val dao = core.db.dao()

    val messages: StateFlow<List<MessageEntity>?> = dao.observeMessages(dialogId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val dialog: StateFlow<DialogEntity?> = dao.observeDialog(dialogId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val turn: StateFlow<Turn?> = app.turns.turn(dialogId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val session: StateFlow<SessionState?> = core.session.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var input by mutableStateOf(TextFieldValue(""))
    val images = mutableStateListOf<Uri>()

    init {
        // Something shared into the app was given this new dialog.
        app.pendingShare.value?.takeIf { it.dialogId == dialogId }?.let { share ->
            setText(share.text)
            images += share.images.take(MAX_IMAGES)
            app.pendingShare.value = null
        }
        // A failed upload hands its draft back to the composer.
        viewModelScope.launch {
            turn.collect { t ->
                if (t is Turn.Failed && t.draft != null && input.text.isBlank() && images.isEmpty()) {
                    app.turns.takeDraft(dialogId)?.let { d ->
                        setText(d.text)
                        images += d.images
                    }
                }
            }
        }
    }

    val canSend: Boolean get() = (input.text.isNotBlank() || images.isNotEmpty()) && !app.turns.busy(dialogId)

    fun setText(text: String) {
        input = TextFieldValue(text, TextRange(text.length))
    }

    fun addImages(uris: List<Uri>) {
        images += uris.filter { it !in images }.take(MAX_IMAGES - images.size)
    }

    fun removeImage(uri: Uri) {
        images.remove(uri)
    }

    /** Sends the composer's text and images; the composer empties at once (a failed upload brings them back). */
    fun send(): Boolean {
        val draft = Draft(input.text, images.toList())
        if (!app.turns.send(dialogId, draft)) return false
        setText("")
        images.clear()
        return true
    }

    /** Stops this device's turn, or an answer another device of the user is streaming into this dialog. */
    fun stop() {
        if (app.turns.busy(dialogId)) {
            app.turns.stop(dialogId)
        } else {
            val streaming = messages.value?.lastOrNull { !it.isUser && it.status == MessageStatus.STREAMING } ?: return
            viewModelScope.launch { core.chat.stop(streaming.id) }
        }
    }

    /** Sends a question that never reached the server again (its images are already uploaded). */
    fun retry(m: MessageEntity) {
        if (app.turns.busy(dialogId)) return
        viewModelScope.launch {
            core.chat.discardFailed(m.id)
            app.turns.send(dialogId, Draft(m.content, uploaded = m.attachmentList))
        }
    }

    fun dismissError() = app.turns.dismiss(dialogId)

    fun deleteMessage(id: String) = viewModelScope.launch { core.sync.deleteMessage(id) }

    fun rename(title: String) = viewModelScope.launch { core.sync.renameDialog(dialogId, title) }

    fun deleteDialog() {
        app.turns.stop(dialogId)
        viewModelScope.launch { core.sync.deleteDialog(dialogId) }
    }

    companion object {
        const val MAX_IMAGES = 4
    }
}
