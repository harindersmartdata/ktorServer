package com.example.ktorserver

import android.app.Application
import com.example.ktorserver.data.ReplicationStore
import com.example.ktorserver.data.UserRepository
import com.example.ktorserver.data.createDatabase
import com.example.ktorserver.server.HttpChangeReplicator
import com.example.ktorserver.server.PeerMonitor
import com.example.ktorserver.security.SecretsStore
import com.example.ktorserver.server.PromotionCoordinator
import com.example.ktorserver.data.databasePath

/**
 * Owns the single database instance for the process.
 *
 * The Activity and the Service both run in this process, so they get the same
 * repository — a write over HTTP is visible to the UI immediately.
 */
class KtorServerApp : Application() {

    val database by lazy { createDatabase(this) }

    val replicationStore by lazy { ReplicationStore(database.replicationStateDao()) }

    val userRepository by lazy {
        UserRepository(database.userDao(), database.writeDao(), replicationStore)
    }

    val changeLogDao by lazy { database.changeLogDao() }

    val writeDao by lazy { database.writeDao() }

    val promotionDao by lazy { database.promotionDao() }

    val secrets by lazy { SecretsStore(database.replicationStateDao(), replicationStore) }

    val replicator by lazy {
        HttpChangeReplicator(replicationStore, changeLogDao, { secrets.load().nodeToken })
    }

    val peerMonitor by lazy { PeerMonitor(replicationStore, { secrets.load().nodeToken }) }

    val promotionCoordinator by lazy {
        PromotionCoordinator(replicationStore, promotionDao, peerMonitor)
    }

    val databasePath: String by lazy { databasePath(this) }
}
