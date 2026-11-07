package com.konecto.series76.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** Local SQLite cache (Room). It is the single source of truth for the UI; the API only refreshes it. */
@Database(
    entities = [SessionEntity::class, MessageEntity::class, DebugTraceEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KonectoDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun debugTraceDao(): DebugTraceDao

    companion object {
        const val NAME = "konecto.db"
    }
}
