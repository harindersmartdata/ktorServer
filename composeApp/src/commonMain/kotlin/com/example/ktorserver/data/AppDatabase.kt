package com.example.ktorserver.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.example.ktorserver.util.ioDispatcher

const val DATABASE_NAME = "ktor-server.db"

@Database(
    entities = [
        User::class,
        ChangeLogEntry::class,
        ReplicationState::class,
        PromotionRecord::class,
    ],
    version = 7,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun replicationStateDao(): ReplicationStateDao
    abstract fun writeDao(): WriteDao
    abstract fun promotionDao(): PromotionDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/**
 * v1 -> v2: makes the schema replicable.
 *
 * `users.id` changes from an autoincrement INTEGER to a globally unique TEXT id, which
 * means recreating the table — SQLite cannot alter a primary key in place. Existing
 * rows keep their data and are given random ids; they predate replication, so no peer
 * can disagree about them.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE users RENAME TO users_v1")
        connection.execSQL(
            """
            CREATE TABLE users (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                email TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            INSERT INTO users (id, name, email, createdAt)
            SELECT lower(hex(randomblob(16))), name, email, createdAt FROM users_v1
            """.trimIndent()
        )
        connection.execSQL("DROP TABLE users_v1")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_users_email ON users (email)")

        connection.execSQL(
            """
            CREATE TABLE change_log (
                nodeId TEXT NOT NULL,
                seq INTEGER NOT NULL,
                txId TEXT NOT NULL,
                tableName TEXT NOT NULL,
                rowId TEXT NOT NULL,
                op TEXT NOT NULL,
                payload TEXT,
                epoch INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(nodeId, seq)
            )
            """.trimIndent()
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_txId ON change_log (txId)")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_change_log_tableName_rowId " +
                "ON change_log (tableName, rowId)"
        )

        connection.execSQL(
            """
            CREATE TABLE replication_state (
                id INTEGER NOT NULL,
                nodeId TEXT NOT NULL,
                role TEXT NOT NULL,
                epoch INTEGER NOT NULL,
                lastAppliedNodeId TEXT,
                lastAppliedSeq INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
    }
}

/**
 * v2 -> v3: remembers where the peer is.
 *
 * Both columns are nullable, so SQLite can add them in place — no table rebuild.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE replication_state ADD COLUMN peerAddress TEXT")
        connection.execSQL("ALTER TABLE replication_state ADD COLUMN peerNodeId TEXT")
    }
}

/** v3 -> v4: tracks how far the peer has acknowledged our changes. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE replication_state ADD COLUMN peerAckedSeq INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/** v4 -> v5: permanent record of every failover. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE promotion_log (
                nodeId TEXT NOT NULL,
                epoch INTEGER NOT NULL,
                at INTEGER NOT NULL,
                forced INTEGER NOT NULL,
                peerReachable INTEGER NOT NULL,
                peerRoleAtPromotion TEXT,
                peerEpochAtPromotion INTEGER,
                reason TEXT,
                PRIMARY KEY(nodeId, epoch)
            )
            """.trimIndent()
        )
    }
}

/** v5 -> v6: shared secrets for replication and client traffic. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE replication_state ADD COLUMN nodeToken TEXT")
        connection.execSQL("ALTER TABLE replication_state ADD COLUMN clientToken TEXT")
    }
}

/** v6 -> v7: data-route authentication became optional. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE replication_state ADD COLUMN requireClientAuth INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * Shared tail of the builder chain.
 *
 * Note there is no seed callback any more. Two servers each inventing "ada@example.com"
 * with different ids would collide on the unique email index the first time they synced.
 * In a replicated system a node must not invent rows on its own — a fresh secondary gets
 * its data from the primary, not from a local seed.
 */
fun RoomDatabase.Builder<AppDatabase>.configureAndBuild(): AppDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(ioDispatcher)
        .addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        )
        .build()
