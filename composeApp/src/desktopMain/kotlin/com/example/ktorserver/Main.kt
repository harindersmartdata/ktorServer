package com.example.ktorserver

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.ktorserver.data.ReplicationStore
import com.example.ktorserver.data.UserRepository
import com.example.ktorserver.data.createDatabase
import com.example.ktorserver.data.databaseFile
import com.example.ktorserver.server.DesktopServerController
import com.example.ktorserver.server.HttpChangeReplicator
import com.example.ktorserver.server.KtorServer
import com.example.ktorserver.server.PeerMonitor
import com.example.ktorserver.server.PromotionCoordinator
import com.example.ktorserver.security.SecretsStore
import com.example.ktorserver.ui.App
import com.example.ktorserver.ui.ReplicationUi
import kotlinx.coroutines.launch

/**
 * Desktop entry point. Referenced by `mainClass` in build.gradle.kts as
 * `com.example.ktorserver.MainKt`.
 */
fun main() = application {
    val database = remember { createDatabase() }
    val store = remember { ReplicationStore(database.replicationStateDao()) }
    val repository = remember {
        UserRepository(database.userDao(), database.writeDao(), store)
    }
    // TUCOUNT_PORT lets a second instance run alongside the first when testing failover.
    val port = remember {
        System.getenv("TUCOUNT_PORT")?.toIntOrNull() ?: KtorServer.DEFAULT_PORT
    }
    // Env overrides let an installer inject the shared secret; otherwise it is
    // generated on first run and shown on screen for pairing.
    val secrets = remember {
        SecretsStore(
            dao = database.replicationStateDao(),
            store = store,
            nodeTokenOverride = System.getenv("TUCOUNT_NODE_TOKEN"),
            clientTokenOverride = System.getenv("TUCOUNT_CLIENT_TOKEN"),
        )
    }
    val replicator = remember {
        HttpChangeReplicator(store, database.changeLogDao(), { secrets.load().nodeToken })
    }
    val monitor = remember { PeerMonitor(store, { secrets.load().nodeToken }) }
    val promotion = remember {
        PromotionCoordinator(store, database.promotionDao(), monitor)
    }
    val controller = remember {
        DesktopServerController(
            repository = repository,
            store = store,
            changeLog = database.changeLogDao(),
            writeDao = database.writeDao(),
            promotions = database.promotionDao(),
            promotionCoordinator = promotion,
            secrets = { secrets.load() },
            replicator = replicator,
            monitor = monitor,
            port = port,
        )
    }

    // A till should be serving the moment the machine finishes booting — nobody is
    // going to click Start every morning.
    val uiScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { controller.start() }

    Window(
        onCloseRequest = {
            controller.stop()
            exitApplication()
        },
        title = "TuCount local server :$port",
        state = rememberWindowState(width = 560.dp, height = 820.dp),
    ) {
        App(
            controller = controller,
            usersFlow = repository.users,
            databasePath = databaseFile().absolutePath,
            replication = ReplicationUi(
                state = store.observe(),
                peerReachable = monitor.peerReachable,
                primaryUnavailable = monitor.primaryUnavailable,
                startupConfirmed = monitor.startupConfirmed,
                promote = { force -> promotion.promote(force) },
            ),
        )
    }
}
