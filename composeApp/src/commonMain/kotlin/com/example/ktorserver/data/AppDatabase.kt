package com.example.ktorserver.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.example.ktorserver.util.ioDispatcher
import kotlinx.datetime.Clock

const val DATABASE_NAME = "ktor-server.db"

@Database(entities = [User::class], version = 1, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
}

/**
 * Room's KSP processor generates the `actual` for this on each target, which is why
 * there is no hand-written actual anywhere in the tree.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/**
 * Shared tail of the builder chain. Each platform opens the file its own way — Android
 * through `Room.databaseBuilder(context, ...)`, desktop from an absolute path — then
 * hands the builder here so driver, dispatcher and seeding stay identical.
 */
fun RoomDatabase.Builder<AppDatabase>.configureAndBuild(): AppDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(ioDispatcher)
        .addCallback(SeedCallback)
        .build()

/** Inserts demo rows once, when the file is first created. */
private object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        val now = Clock.System.now().toEpochMilliseconds()
        // Literal values: these are fixed seed constants, not user input.
        connection.execSQL(
            "INSERT INTO users (name, email, createdAt) " +
                "VALUES ('Ada Lovelace', 'ada@example.com', $now)"
        )
        connection.execSQL(
            "INSERT INTO users (name, email, createdAt) " +
                "VALUES ('Alan Turing', 'alan@example.com', $now)"
        )
    }
}
