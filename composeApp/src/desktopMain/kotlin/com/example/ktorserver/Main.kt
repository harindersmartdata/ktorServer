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
    val controller = remember {
        DesktopServerController(repository, store, database.changeLogDao())
    }

    // A till should be serving the moment the machine finishes booting — nobody is
    // going to click Start every morning.
    LaunchedEffect(Unit) { controller.start() }

    Window(
        onCloseRequest = {
            controller.stop()
            exitApplication()
        },
        title = "TuCount local server",
        state = rememberWindowState(width = 560.dp, height = 820.dp),
    ) {
        App(
            controller = controller,
            usersFlow = repository.users,
            databasePath = databaseFile().absolutePath,
        )
    }
}
