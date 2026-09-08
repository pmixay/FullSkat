package com.fullskat.watch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class DialogSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

@Dao
interface DialogDao {
    @Query(
        """
        SELECT d.id AS id, d.title AS title, d.updatedAt AS updatedAt,
               (SELECT COUNT(*) FROM messages m WHERE m.dialogId = d.id) AS messageCount
        FROM dialogs d ORDER BY d.updatedAt DESC
        """
    )
    fun observeDialogs(): Flow<List<DialogSummary>>

    @Query("SELECT * FROM dialogs WHERE id = :id")
    suspend fun getDialog(id: String): DialogEntity?

    @Query("SELECT * FROM dialogs ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestDialog(): DialogEntity?

    @Query("SELECT * FROM messages WHERE dialogId = :dialogId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(dialogId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE dialogId = :dialogId ORDER BY createdAt ASC, id ASC")
    suspend fun messages(dialogId: String): List<MessageEntity>

    @Query("SELECT content FROM messages WHERE role = 'USER' ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastUserMessage(): String?

    @Query("SELECT COUNT(*) FROM dialogs WHERE updatedAt >= :since AND (SELECT COUNT(*) FROM messages m WHERE m.dialogId = dialogs.id) > 0")
    suspend fun countDialogsSince(since: Long): Int

    @Insert
    suspend fun insertDialog(dialog: DialogEntity)

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, pending = :pending WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String, pending: Boolean)

    @Query("UPDATE dialogs SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDialog(id: String, title: String, updatedAt: Long)

    @Query("UPDATE dialogs SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchDialog(id: String, updatedAt: Long)

    @Query("DELETE FROM dialogs WHERE id = :id")
    suspend fun deleteDialog(id: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM dialogs WHERE id IN (SELECT id FROM dialogs WHERE (SELECT COUNT(*) FROM messages m WHERE m.dialogId = dialogs.id) = 0 AND id != :keepId)")
    suspend fun deleteEmptyDialogsExcept(keepId: String)

    @Transaction
    suspend fun deleteEmptyDialogs() = deleteEmptyDialogsExcept("")
}
