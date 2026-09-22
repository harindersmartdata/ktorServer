package com.example.ktorserver.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Row in the `users` SQLite table and the JSON shape returned by the API.
 *
 * Entity and DTO are the same class here to keep the sample small. In a larger app
 * you would keep them apart so a schema change can't silently alter the wire format.
 */
@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)],
)
@Serializable
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "email")
    val email: String,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)

/** Body accepted by `POST /users`. */
@Serializable
data class CreateUserRequest(
    val name: String,
    val email: String,
)

@Serializable
data class UsersResponse(
    val count: Int,
    val users: List<User>,
)

@Serializable
data class HealthResponse(
    val status: String,
    val uptimeMillis: Long,
    val userCount: Int,
    val serverTime: String,
    val storage: String = "sqlite",
)

@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null,
)
