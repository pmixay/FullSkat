package com.fullskat.watch.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "dialogs")
data class DialogEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class Role { USER, ASSISTANT }

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = DialogEntity::class,
            parentColumns = ["id"],
            childColumns = ["dialogId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dialogId", "createdAt")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dialogId: String,
    val role: Role,
    val content: String,
    val createdAt: Long,
    /** True while the assistant answer is still streaming or failed midway. */
    val pending: Boolean = false,
    /** The model's reasoning summary for an assistant message; empty when the model sent none. */
    @ColumnInfo(defaultValue = "") val thinking: String = "",
)
