package com.example.ktorserver

import android.app.Application
import com.example.ktorserver.data.UserRepository
import com.example.ktorserver.data.createDatabase
import com.example.ktorserver.data.databasePath

/**
 * Owns the single database instance for the process.
 *
 * The Activity and the Service both run in this process, so they get the same
 * repository — a write over HTTP is visible to the UI immediately.
 */
class KtorServerApp : Application() {

    val database by lazy { createDatabase(this) }

    val userRepository by lazy { UserRepository(database.userDao()) }

    val databasePath: String by lazy { databasePath(this) }
}
