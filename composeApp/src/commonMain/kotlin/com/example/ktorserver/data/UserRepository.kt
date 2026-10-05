package com.example.ktorserver.data

import androidx.sqlite.SQLiteException
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * SQLite-backed user store, shared by the HTTP routes and the UI.
 *
 * Every mutation goes through [WriteDao] so the row and its change_log entry land in the
 * same transaction. Nothing here writes to `users` directly.
 */
@OptIn(ExperimentalUuidApi::class)
class UserRepository(
    private val dao: UserDao,
    private val writeDao: WriteDao,
    private val store: ReplicationStore,
    private val json: Json = Json { encodeDefaults = true },
) {

    /** Cold flow straight from Room; re-emits on every table change. */
    val users: Flow<List<User>> = dao.observeAll()

    suspend fun all(): List<User> = dao.all()

    suspend fun byId(id: String): User? = dao.byId(id)

    suspend fun existsByEmail(email: String): Boolean = dao.byEmail(email) != null

    suspend fun count(): Int = dao.count()

    /**
     * Inserts a user. Returns null when the email is already taken — the unique index
     * on `email` is the real guard, so a concurrent duplicate loses here rather than
     * slipping past the pre-check in the route.
     */
    suspend fun add(name: String, email: String): User? {
        val state = store.current()
        val now = Clock.System.now().toEpochMilliseconds()
        val user = User(
            id = Uuid.random().toString(),
            name = name.trim(),
            email = email.trim(),
            createdAt = now,
        )
        return try {
            writeDao.insertUserLogged(
                user = user,
                nodeId = state.nodeId,
                epoch = state.epoch,
                txId = Uuid.random().toString(),
                payload = json.encodeToString(user),
                now = now,
            )
            user
        } catch (_: SQLiteException) {
            null
        }
    }

    /**
     * Gives pre-replication rows a change_log entry so they can reach a secondary.
     *
     * Run only when this node is made primary: if both terminals did it independently
     * they would each author entries for the same logical rows under different node
     * ids, and the unique email index would then fight over them on first sync.
     *
     * Returns how many rows were backfilled.
     */
    suspend fun backfillUnloggedRows(): Int {
        val state = store.current()
        val rows = dao.withoutLogEntries()
        if (rows.isEmpty()) return 0

        val now = Clock.System.now().toEpochMilliseconds()
        val txId = Uuid.random().toString()
        for (row in rows) {
            writeDao.logExistingRow(
                rowId = row.id,
                payload = json.encodeToString(row),
                nodeId = state.nodeId,
                epoch = state.epoch,
                txId = txId,
                now = now,
            )
        }
        return rows.size
    }

    suspend fun delete(id: String): Boolean {
        val state = store.current()
        // -1 means the row was not there, so nothing was logged either.
        return writeDao.deleteUserLogged(
            id = id,
            nodeId = state.nodeId,
            epoch = state.epoch,
            txId = Uuid.random().toString(),
            now = Clock.System.now().toEpochMilliseconds(),
        ) != -1L
    }
}
