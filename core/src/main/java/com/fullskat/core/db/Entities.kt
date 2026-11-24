package com.fullskat.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.net.JsonModels
import com.fullskat.core.net.MessageStatus
import com.fullskat.core.net.Roles
import com.fullskat.core.net.WebSource
import com.fullskat.core.net.WebSources

/**
 * A dialog as this device knows it. [local] is true for a dialog made here that the server has not heard of
 * yet (it learns about it with the first message, or from a queued create).
 */
@Entity(tableName = "dialogs", indices = [Index("updatedAt")])
data class DialogEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val seq: Long = 0,
    val local: Boolean = false,
)

/**
 * A message as this device knows it. Ids come from whichever device created it (UUIDs), so the copy the server
 * sends back replaces the local one in place. No foreign key to the dialog: sync may deliver a message before
 * its dialog.
 */
@Entity(tableName = "messages", indices = [Index("dialogId", "createdAt")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val dialogId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** done, streaming, error, or sending (a user message on its way, local only). */
    val status: String = MessageStatus.DONE,
    val thinking: String = "",
    /** Web pages the answer drew on, as JSON text (see WebSources); empty when it did not search. */
    val sources: String = "",
    /** Attached images, as JSON text (see JsonModels.attachmentsJson); empty when none. */
    val attachments: String = "",
    val model: String? = null,
    val error: String? = null,
    val seq: Long = 0,
) {
    val isUser: Boolean get() = role == Roles.USER

    /** Still being written: the model is answering (here or on another device). */
    val pending: Boolean get() = status == MessageStatus.STREAMING || status == MessageStatus.SENDING

    val sourceList: List<WebSource> get() = WebSources.decode(sources)
    val attachmentList: List<AttachmentRef> get() = JsonModels.attachmentsFrom(attachments)
}

/** A change made here while offline (or not yet confirmed), replayed to the server by the sync engine in order. */
@Entity(tableName = "pending_ops")
data class PendingOp(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** create_dialog, rename_dialog, delete_dialog, delete_message */
    val kind: String,
    val targetId: String,
    val payload: String = "",
    val createdAt: Long,
)

data class DialogSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val lastMessage: String?,
)

/** Small values that belong with the copy of the data, such as the sync cursor (rebuilt together with it). */
@Entity(tableName = "kv")
data class KeyValue(@PrimaryKey val key: String, val value: String)
