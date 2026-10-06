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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.User
import com.example.ktorserver.security.SecretsStore
import com.example.ktorserver.server.DiscoveredPeer
import com.example.ktorserver.server.KtorServer
import com.example.ktorserver.server.PairingCoordinator
import com.example.ktorserver.server.PromotionCoordinator
import com.example.ktorserver.server.ServerController
import com.example.ktorserver.server.ServiceAdvertiser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The whole screen, written for whoever is standing at the till rather than for whoever
 * wrote the replication.
 *
 * Everything a shop needs is above the fold and in plain words: whether this till is
 * working, which of the two it is, and what to do when it is not. The protocol detail —
 * roles, epochs, sequence numbers, endpoints — is still here, under Technical details,
 * because it is exactly what is needed when something is genuinely broken.
 */
@Composable
fun App(
    controller: ServerController,
    usersFlow: Flow<List<User>>,
    databasePath: String,
    replication: ReplicationUi? = null,
    onAddTestRecord: (suspend () -> String)? = null,
    onOpenBatterySettings: (() -> Unit)? = null,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ServerScreen(
                controller, usersFlow, databasePath, replication,
                onAddTestRecord, onOpenBatterySettings,
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
    onAddTestRecord: (suspend () -> String)?,
    onOpenBatterySettings: (() -> Unit)?,
) {
    val running by controller.isRunning.collectAsState()
    val error by controller.lastError.collectAsState()
    val users by usersFlow.collectAsState(initial = emptyList())

    val state by (replication?.state ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(null)
    val role = state?.role ?: NodeRole.UNCONFIGURED

    val peerUp by (replication?.peerReachable ?: constFlow(false)).collectAsState()
    val noPrimary by (replication?.noPrimaryAnywhere ?: constFlow(false)).collectAsState()
    val primaryGone by (replication?.primaryUnavailable ?: constFlow(false)).collectAsState()
    val lag by (replication?.unreplicated ?: constFlow(0L)).collectAsState()

    val scope = rememberCoroutineScope()
    var refusal by remember { mutableStateOf<PromotionCoordinator.Outcome.Refused?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var testOutcome by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Till server") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    running = running,
                    role = role,
                    noPrimary = noPrimary,
                    primaryGone = primaryGone,
                    peerUp = peerUp,
                    peerPaired = state?.peerNodeId != null,
                    lag = lag,
                    error = error,
                )
            }

            if (onAddTestRecord != null) {
                item {
                    TestRecordCard(
                        isMain = role == NodeRole.PRIMARY,
                        recordCount = users.size,
                        outcome = testOutcome,
                        onAdd = {
                            testOutcome = "Adding…"
                            scope.launch { testOutcome = onAddTestRecord() }
                        },
                    )
                }
            }

            if (replication != null) {
                item {
                    ActionCard(
                        role = role,
                        noPrimary = noPrimary,
                        primaryGone = primaryGone,
                        onBecomeMain = {
                            scope.launch {
                                if (role == NodeRole.UNCONFIGURED) {
                                    replication.makePrimary()
                                } else {
                                    val outcome = replication.promote(
                                        false,
                                        "Promoted from the till screen",
                                    )
                                    if (outcome is PromotionCoordinator.Outcome.Refused) {
                                        refusal = outcome
                                    }
                                }
                            }
                        },
                    )
                }

                item {
                    ConnectCard(
                        code = state?.nodeToken,
                        peerPaired = state?.peerNodeId != null,
                        discovered = replication.discovered,
                        onPairWith = { peer, entered -> replication.pairAsSecondary(peer, entered) },
                    )
                }
            }

            item { PosAppCard() }

            item {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide technical details" else "Technical details")
                }
            }

            if (showDetails) {
                item {
                    TechnicalCard(
                        running = running,
                        role = role,
                        epoch = state?.epoch ?: 0,
                        nodeId = state?.nodeId,
                        code = state?.nodeToken,
                        peerAddress = state?.peerAddress,
                        lag = lag,
                        databasePath = databasePath,
                        requireAuth = state?.requireClientAuth == true,
                        onToggleAuth = { want ->
                            replication?.let { r -> scope.launch { r.setRequireAuth(want) } }
                        },
                        onStart = controller::start,
                        onStop = controller::stop,
                        onBecomeMain = {
                            scope.launch {
                                val outcome = replication?.promote(false, "Changeover from Technical details")
                                if (outcome is PromotionCoordinator.Outcome.Refused) refusal = outcome
                            }
                        },
                        onOpenBatterySettings = onOpenBatterySettings,
                    )
                }
                item {
                    Text("Records (${users.size})", style = MaterialTheme.typography.titleSmall)
                }
                items(users, key = { it.id }) { user -> UserRow(user) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (replication != null) {
        PromotionRefusalDialog(
            refusal = refusal,
            onDismiss = { refusal = null },
            onForce = {
                refusal = null
                scope.launch {
                    replication.promote(true, "Forced from the till screen despite the other answering")
                }
            },
        )
    }
}

private fun <T> constFlow(value: T): StateFlow<T> =
    kotlinx.coroutines.flow.MutableStateFlow(value)

/**
 * Adds a throwaway record and shows the count, so the two tills can be compared by eye.
 *
 * The count is shown on both: on the backup there is nothing to press, but watching the
 * number follow the main till is the whole point of the check.
 */
@Composable
private fun TestRecordCard(
    isMain: Boolean,
    recordCount: Int,
    outcome: String?,
    onAdd: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Records on this till",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    recordCount.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (isMain) {
                Button(onClick = onAdd) { Text("Add a test record") }
                Text(
                    "The number on the backup till should match within a second.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "This number is copied from the main till. It should match the " +
                        "number shown there.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            outcome?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The one thing somebody glances at: is this till doing its job? */
@Composable
private fun StatusCard(
    running: Boolean,
    role: NodeRole,
    noPrimary: Boolean,
    primaryGone: Boolean,
    peerUp: Boolean,
    peerPaired: Boolean,
    lag: Long,
    error: String?,
) {
    val problem = Wording.isProblem(running, noPrimary, primaryGone)
    val headline = Wording.headline(running, role, noPrimary, primaryGone)
    val guidance = Wording.guidance(running, role, noPrimary, primaryGone, peerUp, lag)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (problem) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(14.dp).clip(CircleShape),
                    color = if (problem) Color(0xFFC62828) else Color(0xFF1B9C5B),
                ) {}
                Spacer(Modifier.size(10.dp))
                Text(
                    headline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                "This is the ${Wording.role(role).lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                Wording.peerSummary(role, peerUp, peerPaired, lag),
                style = MaterialTheme.typography.bodyMedium,
            )

            guidance?.let {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            error?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Offered only when there is actually something to decide. */
@Composable
private fun ActionCard(
    role: NodeRole,
    noPrimary: Boolean,
    primaryGone: Boolean,
    onBecomeMain: () -> Unit,
) {
    // Only offered when there is something to fix. A healthy backup showing a
    // "make this the main till" button invites somebody to press it, and pressing it
    // while the main till is working is how a shop ends up with two sets of takings —
    // which is exactly what happened during testing. Deliberate changeovers go through
    // Technical details.
    val somethingToFix = noPrimary || primaryGone || role == NodeRole.UNCONFIGURED
    if (!somethingToFix) return

    var confirming by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    noPrimary -> "Nothing is taking sales"
                    primaryGone -> "The main till has stopped answering"
                    else -> "Set this till up"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                onClick = { confirming = true },
                colors = if (noPrimary || primaryGone) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text("Make this the main till") }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Make this the main till?") },
            text = {
                Text(
                    "This till will start taking and storing sales. Only do this if the " +
                        "other till is really off or broken — if it is still working, both " +
                        "will take sales and the two sets of takings will have to be sorted " +
                        "out by hand afterwards."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onBecomeMain() }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/** Joining the two tills together. Hidden once they are joined. */
@Composable
private fun ConnectCard(
    code: String?,
    peerPaired: Boolean,
    discovered: StateFlow<List<DiscoveredPeer>>,
    onPairWith: suspend (DiscoveredPeer, String) -> PairingCoordinator.Result,
) {
    val peers by discovered.collectAsState()
    val scope = rememberCoroutineScope()

    var pairingWith by remember { mutableStateOf<DiscoveredPeer?>(null) }
    var entered by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    // Setup is a once-per-shop job. Once the two are joined this only gets in the way,
    // and on the main till it was offering to turn itself into the backup.
    if (peerPaired) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect the two tills", style = MaterialTheme.typography.titleMedium)

            code?.let {
                Text("This till's code", style = MaterialTheme.typography.bodySmall)
                Text(
                    SecretsStore.format(it),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Read this out when joining the other till to this one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            if (peers.isEmpty()) {
                Text(
                    "No other till found yet. Both must be on the same Wi-Fi with the " +
                        "app open.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Other tills on this Wi-Fi", style = MaterialTheme.typography.bodySmall)
                peers.forEach { peer ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(peer.address, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        OutlinedButton(onClick = {
                            entered = ""; problem = null; pairingWith = peer
                        }) { Text("Use as main till") }
                    }
                }
            }
        }
    }

    pairingWith?.let { peer ->
        AlertDialog(
            onDismissRequest = { pairingWith = null },
            title = { Text("Connect to the till at ${peer.address}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This till will become the backup. Type the code shown on the other one.")
                    OutlinedTextField(
                        value = entered,
                        onValueChange = { entered = it; problem = null },
                        singleLine = true,
                        label = { Text("Code") },
                    )
                    problem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (val r = onPairWith(peer, entered)) {
                            is PairingCoordinator.Result.Paired -> pairingWith = null
                            is PairingCoordinator.Result.Rejected -> problem = r.reason
                        }
                    }
                }) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { pairingWith = null }) { Text("Cancel") } },
        )
    }
}

/** The single address the POS app is configured with. */
@Composable
private fun PosAppCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Point the till app at", style = MaterialTheme.typography.bodySmall)
            Text(
                ServiceAdvertiser.url(KtorServer.DEFAULT_PORT),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This address never changes, whichever till is the main one.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Everything an engineer wants and nobody else does. */
@Composable
private fun TechnicalCard(
    running: Boolean,
    role: NodeRole,
    epoch: Long,
    nodeId: String?,
    code: String?,
    peerAddress: String?,
    lag: Long,
    databasePath: String,
    requireAuth: Boolean,
    onToggleAuth: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBecomeMain: () -> Unit,
    onOpenBatterySettings: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val mono = MaterialTheme.typography.bodySmall
            Text("role=${role.name} epoch=$epoch unreplicated=$lag",
                fontFamily = FontFamily.Monospace, style = mono)
            nodeId?.let { Text("node=$it", fontFamily = FontFamily.Monospace, style = mono) }
            code?.let {
                Text("pairing code=${SecretsStore.format(it)}",
                    fontFamily = FontFamily.Monospace, style = mono)
            }
            peerAddress?.let { Text("peer=$it", fontFamily = FontFamily.Monospace, style = mono) }
            Text(databasePath, fontFamily = FontFamily.Monospace, style = mono)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            listOf(
                "GET    /health",
                "GET    /users",
                "GET    /users/{id}",
                "POST   /users",
                "DELETE /users/{id}",
            ).forEach { Text(it, fontFamily = FontFamily.Monospace, style = mono) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Require a code for the API", style = mono)
                    Text(
                        if (requireAuth) "On" else "Off — anything on this network can read and write",
                        style = mono,
                    )
                }
                Switch(checked = requireAuth, onCheckedChange = onToggleAuth)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart, enabled = !running) { Text("Start") }
                OutlinedButton(onClick = onStop, enabled = running) { Text("Stop") }
            }

            // A planned changeover, as opposed to a failover. Deliberately tucked away.
            if (role != NodeRole.PRIMARY) {
                OutlinedButton(onClick = onBecomeMain) { Text("Make this the main till") }
            }

            onOpenBatterySettings?.let {
                OutlinedButton(onClick = it) { Text("Battery settings (keep alive)") }
            }
        }
    }
}

/** Shown only when a promotion is refused because the other till is still answering. */
@Composable
private fun PromotionRefusalDialog(
    refusal: PromotionCoordinator.Outcome.Refused?,
    onDismiss: () -> Unit,
    onForce: () -> Unit,
) {
    refusal ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(
                "The other till answered just now and says it is the main one. " +
                    "This is not a changeover.\n\n" +
                    "Carrying on gives you two tills taking sales at the same time, and " +
                    "the takings will have to be sorted out by hand. Only continue if you " +
                    "know the other till is unusable despite answering."
            )
        },
        title = { Text("The other till is still working") },
        confirmButton = { TextButton(onClick = onForce) { Text("Continue anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UserRow(user: User) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(user.name, fontWeight = FontWeight.Medium)
            Text(user.email, style = MaterialTheme.typography.bodySmall)
        }
    }
}
