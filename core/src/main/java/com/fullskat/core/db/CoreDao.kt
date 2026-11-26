package com.fullskat.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CoreDao {
    @Query(
        """
        SELECT d.id AS id, d.title AS title, d.updatedAt AS updatedAt,
               (SELECT COUNT(*) FROM messages m WHERE m.dialogId = d.id) AS messageCount,
               (SELECT m.content FROM messages m WHERE m.dialogId = d.id ORDER BY m.createdAt DESC LIMIT 1) AS lastMessage
        FROM dialogs d ORDER BY d.updatedAt DESC
        """,
    )
    fun observeDialogs(): Flow<List<DialogSummary>>

    @Query("SELECT * FROM dialogs WHERE id = :id")
    suspend fun dialog(id: String): DialogEntity?

    @Query("SELECT * FROM dialogs WHERE id = :id")
    fun observeDialog(id: String): Flow<DialogEntity?>

    @Query("SELECT * FROM dialogs ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestDialog(): DialogEntity?

    @Query("SELECT * FROM messages WHERE dialogId = :dialogId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(dialogId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE dialogId = :dialogId ORDER BY createdAt ASC, id ASC")
    suspend fun messages(dialogId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): MessageEntity?

    @Query("SELECT content FROM messages WHERE role = 'user' ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastUserMessage(): String?

    @Query("SELECT COUNT(*) FROM dialogs WHERE updatedAt >= :since AND (SELECT COUNT(*) FROM messages m WHERE m.dialogId = dialogs.id) > 0")
    suspend fun countDialogsSince(since: Long): Int

    @Upsert
    suspend fun upsertDialog(dialog: DialogEntity)

    @Upsert
    suspend fun upsertDialogs(dialogs: List<DialogEntity>)

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET content = :content, thinking = :thinking, status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMessageText(id: String, content: String, thinking: String, status: String, updatedAt: Long)

    @Query("UPDATE messages SET status = :status, error = :error WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: String, error: String?)

    @Query("UPDATE dialogs SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDialog(id: String, title: String, updatedAt: Long)

    @Query("UPDATE dialogs SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchDialog(id: String, updatedAt: Long)

    @Query("DELETE FROM dialogs WHERE id = :id")
    suspend fun deleteDialogRow(id: String)

    @Query("DELETE FROM messages WHERE dialogId = :dialogId")
    suspend fun deleteMessagesOf(dialogId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Transaction
    suspend fun deleteDialog(id: String) {
        deleteMessagesOf(id)
        deleteDialogRow(id)
    }

    /** Dialogs made here and never used (no messages), except the one on screen. */
    @Query("DELETE FROM dialogs WHERE local = 1 AND id != :keepId AND (SELECT COUNT(*) FROM messages m WHERE m.dialogId = dialogs.id) = 0")
    suspend fun deleteEmptyLocalDialogsExcept(keepId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addOp(op: PendingOp): Long

    @Query("SELECT * FROM pending_ops ORDER BY id ASC")
    suspend fun ops(): List<PendingOp>

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun removeOp(id: Long)

    @Query("DELETE FROM pending_ops WHERE targetId = :targetId AND kind = :kind")
    suspend fun removeOps(targetId: String, kind: String)

    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun value(key: String): String?

    @Upsert
    suspend fun putValue(kv: KeyValue)

    @Query("DELETE FROM kv")
    suspend fun clearValues()

    @Query("DELETE FROM dialogs")
    suspend fun clearDialogs()

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("DELETE FROM pending_ops")
    suspend fun clearOps()

    /** Signing out forgets everything this device had of the account. */
    @Transaction
    suspend fun clearAll() {
        clearMessages()
        clearDialogs()
        clearOps()
        clearValues()
    }
}
