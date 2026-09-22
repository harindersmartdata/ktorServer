package com.example.ktorserver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    /** Emits a new list every time the table changes — drives the UI. */
    @Query("SELECT * FROM users ORDER BY id")
    fun observeAll(): Flow<List<User>>

    @Query("SELECT * FROM users ORDER BY id")
    suspend fun all(): List<User>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun byId(id: Int): User?

    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun byEmail(email: String): User?

    /** Returns the generated rowId, which is the new primary key. */
    @Insert
    suspend fun insert(user: User): Long

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteById(id: Int): Int

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int
}
