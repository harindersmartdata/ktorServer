package com.example.ktorserver

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.example.ktorserver.ui.App

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
    val replicator = remember {
        HttpChangeReplicator(store, database.changeLogDao())
    }
    val controller = remember {
        DesktopServerController(
            repository = repository,
            store = store,
            changeLog = database.changeLogDao(),
            writeDao = database.writeDao(),
            replicator = replicator,
            port = port,
        )
    }

    // A till should be serving the moment the machine finishes booting — nobody is
    // going to click Start every morning.
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
        )
    }
}
