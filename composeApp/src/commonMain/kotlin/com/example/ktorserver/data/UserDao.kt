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
}
