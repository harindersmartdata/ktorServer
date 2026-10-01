package com.example.ktorserver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ktorserver.data.User
import com.example.ktorserver.server.KtorServer
import com.example.ktorserver.server.ServerController
import com.example.ktorserver.util.localIpv4Address
import kotlinx.coroutines.flow.Flow

/**
 * Whole UI, shared by the APK and the Windows build.
 *
 * [onOpenBatterySettings] is null on desktop — battery optimisation is an Android
 * concern, so the button only renders where it can do something.
 */
@Composable
fun App(
    controller: ServerController,
    usersFlow: Flow<List<User>>,
    databasePath: String,
    onOpenBatterySettings: (() -> Unit)? = null,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ServerScreen(
                controller = controller,
                usersFlow = usersFlow,
                databasePath = databasePath,
                onOpenBatterySettings = onOpenBatterySettings,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    controller: ServerController,
    usersFlow: Flow<List<User>>,
    databasePath: String,
    onOpenBatterySettings: (() -> Unit)?,
) {
    val running by controller.isRunning.collectAsState()
    val error by controller.lastError.collectAsState()
    val users by usersFlow.collectAsState(initial = emptyList())

    val host = localIpv4Address() ?: "127.0.0.1"
    val baseUrl = "http://$host:${KtorServer.DEFAULT_PORT}"

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ktor local server") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(running = running, baseUrl = baseUrl, error = error)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = controller::start, enabled = !running) { Text("Start server") }
                OutlinedButton(onClick = controller::stop, enabled = running) { Text("Stop") }
            }

            EndpointsCard(baseUrl = baseUrl)

            StorageCard(databasePath = databasePath)

            Text(
                "Users (${users.size}) — live from SQLite",
                style = MaterialTheme.typography.titleMedium,
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(users, key = { it.id }) { user -> UserRow(user) }
            }

            if (onOpenBatterySettings != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenBatterySettings) {
                    Text("Battery settings (keep alive)")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, baseUrl: String, error: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(10.dp).clip(CircleShape),
                    color = if (running) Color(0xFF1B9C5B) else Color(0xFF9E9E9E),
                ) {}
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (running) "Running" else "Stopped",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(baseUrl, fontFamily = FontFamily.Monospace)
            if (error != null) {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EndpointsCard(baseUrl: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Endpoints", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            listOf(
                "GET    /health",
                "GET    /users",
                "GET    /users/{id}",
                "POST   /users",
                "DELETE /users/{id}",
            ).forEach {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StorageCard(databasePath: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Storage", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Room / SQLite — survives restarts", style = MaterialTheme.typography.bodySmall)
            Text(
                databasePath,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UserRow(user: User) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("#${user.id}  ${user.name}", fontWeight = FontWeight.Medium)
            Text(user.email, style = MaterialTheme.typography.bodySmall)
        }
    }
}
