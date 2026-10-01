package com.example.ktorserver

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ktorserver.server.AndroidServerController
import com.example.ktorserver.ui.App

/** Thin host for the shared [App] composable. */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as KtorServerApp

        setContent {
            App(
                controller = AndroidServerController(this),
                usersFlow = app.userRepository.users,
                databasePath = app.databasePath,
                onOpenBatterySettings = {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                },
            )
        }
    }
}
