package com.example.ktorserver.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    /** Emits a new list every time the table changes — drives the UI. */
    @Query("SELECT * FROM users ORDER BY createdAt, id")
    fun observeAll(): Flow<List<User>>

    @Query("SELECT * FROM users ORDER BY createdAt, id")
    suspend fun all(): List<User>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun byId(id: String): User?

    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun byEmail(email: String): User?

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    /**
     * Rows that no change_log entry describes.
     *
     * These exist because MIGRATION_1_2 converted the pre-replication table without
     * logging anything — there was no node identity yet to attribute the writes to.
     * Left alone they would never reach a secondary, so a shop upgrading from the old
     * build would pair a replica that silently missed every historical row.
     */
    @Query(
        "SELECT * FROM users WHERE id NOT IN " +
            "(SELECT rowId FROM change_log WHERE tableName = 'users') ORDER BY createdAt, id"
    )
    suspend fun withoutLogEntries(): List<User>
}
