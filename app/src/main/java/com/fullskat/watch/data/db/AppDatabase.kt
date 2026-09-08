package com.fullskat.watch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun roleToString(role: Role): String = role.name
    @TypeConverter fun stringToRole(value: String): Role = Role.valueOf(value)
}

@Database(entities = [DialogEntity::class, MessageEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dialogDao(): DialogDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fullskat.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
