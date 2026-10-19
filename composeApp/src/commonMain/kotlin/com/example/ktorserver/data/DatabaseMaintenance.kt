package com.example.ktorserver.data

import androidx.room.useWriterConnection
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the write-ahead log from growing without bound.
 *
 * SQLite appends every change to a `-wal` file and folds it back into the database at a
 * checkpoint, which can only complete when no reader is mid-transaction. Under sustained
 * writes there is almost always one, so the automatic checkpoint never finishes its job
 * and the file simply grows.
 *
 * Measured on a terminal taking 50,000 records: the database held steady at 35 MB while
 * the WAL reached **736 MB**, and write throughput fell by a third as every read had more
 * log to walk. Left alone it would fill the storage of a busy terminal within a few weeks.
 *
 * This forces a checkpoint on a timer and caps the file size afterwards. TRUNCATE is used
 * rather than PASSIVE so the file is actually returned to the filesystem rather than
 * merely reused.
 */
class DatabaseMaintenance(
    private val database: AppDatabase,
    private val intervalMillis: Long = 60_000,
) {

    private val logger = KtorSimpleLogger("Maintenance")

    fun start(scope: CoroutineScope) {
        scope.launch {
            // Cap the file so a checkpoint hands space back instead of keeping it.
            runCatching {
                database.useWriterConnection { connection ->
                    // A PRAGMA returns a row, so it has to be stepped rather than just executed.
                    connection.usePrepared("PRAGMA journal_size_limit = $SIZE_LIMIT") { it.step() }
                }
            }.onFailure { logger.warn("Could not set the journal size limit: ${it.message}") }

            while (isActive) {
                delay(intervalMillis)
                checkpoint()
            }
        }
    }

    /**
     * Folds the log back into the database.
     *
     * Failure is not worth escalating: a checkpoint that cannot run because something is
     * reading will simply succeed on the next pass, and the data is safe either way.
     */
    suspend fun checkpoint() {
        runCatching {
            database.useWriterConnection { connection ->
                connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() }
            }
        }.onFailure { logger.debug("Checkpoint skipped: ${it.message}") }
    }

    private companion object {
        /** 8 MB is comfortably more than a burst of sales and far less than a gigabyte. */
        const val SIZE_LIMIT = 8 * 1024 * 1024
    }
}
