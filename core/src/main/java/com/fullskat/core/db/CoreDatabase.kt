package com.fullskat.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The device's copy of the user's dialogs. The server is the source of truth (docs/PROTOCOL.md, Sync), so a
 * schema change may simply rebuild the copy: the next sync downloads it again.
 */
@Database(entities = [DialogEntity::class, MessageEntity::class, PendingOp::class, KeyValue::class], version = 1, exportSchema = true)
abstract class CoreDatabase : RoomDatabase() {
    abstract fun dao(): CoreDao

    companion object {
        fun build(context: Context, name: String = "fullskat-sync.db"): CoreDatabase =
            Room.databaseBuilder(context.applicationContext, CoreDatabase::class.java, name)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): CoreDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, CoreDatabase::class.java).allowMainThreadQueries().build()
    }
}
