package com.example.ktorserver.data

import android.content.Context
import androidx.room.Room

/** Opens the database in the app's private files dir, where Android backs it up. */
fun createDatabase(context: Context): AppDatabase =
    Room.databaseBuilder<AppDatabase>(
        context = context.applicationContext,
        name = context.getDatabasePath(DATABASE_NAME).absolutePath,
    ).configureAndBuild()

fun databasePath(context: Context): String =
    context.getDatabasePath(DATABASE_NAME).absolutePath
