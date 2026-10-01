package com.example.ktorserver.data

import androidx.sqlite.SQLiteException
import kotlinx.datetime.Clock
import kotlinx.coroutines.flow.Flow

/**
 * SQLite-backed user store, shared by the HTTP routes and the UI.
 *
 * Every write lands in the `users` table, so data survives process death and reboots.
 */
class UserRepository(private val dao: UserDao) {

    /** Cold flow straight from Room; re-emits on every table change. */
    val users: Flow<List<User>> = dao.observeAll()

    suspend fun all(): List<User> = dao.all()

    suspend fun byId(id: Int): User? = dao.byId(id)

    suspend fun existsByEmail(email: String): Boolean = dao.byEmail(email) != null

    suspend fun count(): Int = dao.count()

    /**
     * Inserts a user. Returns null when the email is already taken — the unique index
     * on `email` is the real guard, so a concurrent duplicate loses here rather than
     * slipping past the pre-check in the route.
     */
    suspend fun add(name: String, email: String): User? {
        val user = User(
            name = name.trim(),
            email = email.trim(),
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
        return try {
            val id = dao.insert(user)
            user.copy(id = id.toInt())
        } catch (_: SQLiteException) {
            null
        }
    }

    suspend fun delete(id: Int): Boolean = dao.deleteById(id) > 0
}
