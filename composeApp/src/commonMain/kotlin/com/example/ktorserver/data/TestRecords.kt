package com.example.ktorserver.data

import com.example.ktorserver.server.ChangeReplicator
import kotlinx.datetime.Clock

/**
 * Inserts a throwaway record so somebody can watch it appear on the other terminal.
 *
 * Goes through exactly the same path as a real sale — logged write, then a push to the
 * peer — because a test that took a shortcut would prove nothing about whether real
 * sales replicate.
 */
class TestRecordInserter(
    private val repository: UserRepository,
    private val store: ReplicationStore,
    private val replicator: ChangeReplicator,
) {

    /** Returns a sentence to show the operator. */
    suspend fun insert(): String {
        if (store.current().role != NodeRole.PRIMARY) {
            return "Only the main terminal can add records. This one is the backup."
        }

        val stamp = Clock.System.now().toEpochMilliseconds()
        val created = repository.add(
            name = "Test record ${stamp % 100_000}",
            email = "test-$stamp@example.com",
        ) ?: return "Could not add the record."

        // Same synchronous push a real write performs.
        val delivered = replicator.push()

        return if (delivered) {
            "Added \"${created.name}\" and copied it to the backup."
        } else {
            "Added \"${created.name}\". The backup did not confirm it yet."
        }
    }
}
