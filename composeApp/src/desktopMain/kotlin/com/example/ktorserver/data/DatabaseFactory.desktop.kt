package com.example.ktorserver.data

import androidx.room.Room
import java.io.File

/**
 * Where the .db file lives on a desktop install.
 *
 * On Windows that is `%LOCALAPPDATA%\TuCountServer` — per-user, writable without
 * admin rights, and outside Program Files, which is read-only for standard accounts.
 * Falls back to a dotfolder in the home directory on macOS/Linux.
 */
fun databaseFile(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?.let { File(it, "TuCountServer") }
        ?: File(System.getProperty("user.home"), ".tucount-server")

    base.mkdirs()
    return File(base, DATABASE_NAME)
}

fun createDatabase(): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = databaseFile().absolutePath)
        .configureAndBuild()
