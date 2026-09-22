package com.example.ktorserver.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [User::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao

    companion object {
        private const val NAME = "ktor-server.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addCallback(SeedCallback)
                .build()

        /** Absolute path of the .db file, shown in the UI. */
        fun path(context: Context): String =
            context.getDatabasePath(NAME).absolutePath

        /** Inserts demo rows once, when the file is first created. */
        private object SeedCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT INTO users (name, email, createdAt) VALUES (?, ?, ?)",
                    arrayOf<Any>("Ada Lovelace", "ada@example.com", now),
                )
                db.execSQL(
                    "INSERT INTO users (name, email, createdAt) VALUES (?, ?, ?)",
                    arrayOf<Any>("Alan Turing", "alan@example.com", now),
                )
            }
        }
    }
}
