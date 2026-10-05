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
import androidx.lifecycle.lifecycleScope
import com.example.ktorserver.ui.App
import com.example.ktorserver.ui.ReplicationUi
import kotlinx.coroutines.launch

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

        // Generate the pairing tokens up front. They were previously created on the
        // first authenticated request, so a terminal that had not served one yet showed
        // an empty pairing panel and could not be paired.
        lifecycleScope.launch { app.secrets.load() }

        setContent {
            App(
                controller = AndroidServerController(this),
                usersFlow = app.userRepository.users,
                databasePath = app.databasePath,
                replication = ReplicationUi(
                    state = app.replicationStore.observe(),
                    peerReachable = app.peerMonitor.peerReachable,
                    primaryUnavailable = app.peerMonitor.primaryUnavailable,
                    startupConfirmed = app.peerMonitor.startupConfirmed,
                    promote = { force, reason -> app.promotionCoordinator.promote(force, reason) },
                ),
                onOpenBatterySettings = {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                },
            )
        }
    }
}
