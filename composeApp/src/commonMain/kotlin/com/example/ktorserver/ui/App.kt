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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.User
import com.example.ktorserver.server.KtorServer
import com.example.ktorserver.server.PromotionCoordinator
import com.example.ktorserver.server.ServerController
import kotlinx.coroutines.launch
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
    replication: ReplicationUi? = null,
    onOpenBatterySettings: (() -> Unit)? = null,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ServerScreen(
                controller = controller,
                usersFlow = usersFlow,
                databasePath = databasePath,
                replication = replication,
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
    replication: ReplicationUi?,
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

            if (replication != null) ReplicationCard(replication)

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

/**
 * Shows the node's role and whether the peer is answering, and surfaces the promotion
 * button when the primary has gone silent.
 *
 * The button is the whole point of the failure detector: the software reports what it
 * can see and a person decides. A silent primary and a cut network look identical from
 * here, and promoting automatically on that evidence is how both terminals end up
 * taking sales at the same time.
 */
@Composable
private fun ReplicationCard(replication: ReplicationUi) {
    val state by replication.state.collectAsState(initial = null)
    val peerUp by replication.peerReachable.collectAsState()
    val primaryGone by replication.primaryUnavailable.collectAsState()
    val confirmed by replication.startupConfirmed.collectAsState()

    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<PromotionCoordinator.Outcome.Refused?>(null) }

    val role = state?.role ?: NodeRole.UNCONFIGURED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (primaryGone) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Replication", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(8.dp))
                Text(
                    "${'$'}{role.name} · epoch ${'$'}{state?.epoch ?: 0}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(8.dp).clip(CircleShape),
                    color = if (peerUp) Color(0xFF1B9C5B) else Color(0xFFC62828),
                ) {}
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (peerUp) "Peer responding" else "Peer not responding",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state?.peerAddress?.let {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }

            // Pairing: both terminals must hold the same node token or they cannot
            // replicate. Shown here because this screen is the operator's own.
            state?.nodeToken?.let { token ->
                Spacer(Modifier.size(4.dp))
                Text("Pairing token", style = MaterialTheme.typography.titleSmall)
                Text(
                    token,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "The other terminal must be set to this same value.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (role == NodeRole.PRIMARY && !confirmed) {
                Text(
                    "Checking with the peer before accepting writes…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (primaryGone) {
                Spacer(Modifier.size(4.dp))
                Text(
                    "Primary server is not responding.",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Promote this terminal only if you are sure the primary is really " +
                        "down. If it is still running elsewhere, both will take sales.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { confirming = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Make this terminal primary") }
            }
        }
    }

    PromotionDialogs(
        confirming = confirming,
        refusal = refusal,
        onDismissConfirm = { confirming = false },
        onConfirm = {
            confirming = false
            scope.launch {
                val outcome = replication.promote(false)
                if (outcome is PromotionCoordinator.Outcome.Refused) refusal = outcome
            }
        },
        onDismissRefusal = { refusal = null },
        onForce = {
            refusal = null
            scope.launch { replication.promote(true) }
        },
    )
}

/** Plain-language confirmation. Failover is not an action to take by accident. */
@Composable
private fun PromotionDialogs(
    confirming: Boolean,
    refusal: PromotionCoordinator.Outcome.Refused?,
    onDismissConfirm: () -> Unit,
    onConfirm: () -> Unit,
    onDismissRefusal: () -> Unit,
    onForce: () -> Unit,
) {
    if (confirming) {
        AlertDialog(
            onDismissRequest = onDismissConfirm,
            title = { Text("Make this terminal the primary?") },
            text = {
                Text(
                    "This terminal will start accepting sales and storing them. Do this " +
                        "only if the current primary is really down — if it is still " +
                        "running somewhere, both will take sales and the two sets of " +
                        "data will have to be sorted out by hand afterwards."
                )
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Promote") } },
            dismissButton = { TextButton(onClick = onDismissConfirm) { Text("Cancel") } },
        )
    }

    refusal?.let { refused ->
        AlertDialog(
            onDismissRequest = onDismissRefusal,
            title = { Text("The other terminal is still running") },
            text = {
                Text(
                    "It answered just now and reports itself as ${'$'}{refused.peerRole} at " +
                        "epoch ${'$'}{refused.peerEpoch}. This is not a failover.\n\n" +
                        "Promoting anyway gives you two primaries taking sales at the " +
                        "same time. Only continue if you know the other terminal is " +
                        "unusable despite answering."
                )
            },
            confirmButton = { TextButton(onClick = onForce) { Text("Promote anyway") } },
            dismissButton = { TextButton(onClick = onDismissRefusal) { Text("Cancel") } },
        )
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
