package com.example.ktorserver.server

import com.example.ktorserver.data.ApplyChangesRequest
import com.example.ktorserver.data.ApplyChangesResponse
import com.example.ktorserver.data.ChangeLogDao
import com.example.ktorserver.data.ConfigureRoleRequest
import com.example.ktorserver.data.HeartbeatRequest
import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.NotPrimaryResponse
import com.example.ktorserver.data.PairRequest
import com.example.ktorserver.data.OrphanReport
import com.example.ktorserver.data.PrimaryLocation
import com.example.ktorserver.security.Secrets
import com.example.ktorserver.security.SecretsStore
import com.example.ktorserver.security.constantTimeEquals
import com.example.ktorserver.data.ReplicationState
import com.example.ktorserver.data.PreparedChange
import com.example.ktorserver.data.PromoteRequest
import com.example.ktorserver.data.PromotionDao
import com.example.ktorserver.data.PromotionRecord
import com.example.ktorserver.data.PromotionRefused
import com.example.ktorserver.data.ReplicationStatus
import com.example.ktorserver.data.User
import com.example.ktorserver.data.WriteDao
import com.example.ktorserver.data.CreateUserRequest
import com.example.ktorserver.data.ErrorResponse
import com.example.ktorserver.data.HealthResponse
import com.example.ktorserver.data.ReplicationStore
import com.example.ktorserver.data.UserRepository
import com.example.ktorserver.data.UsersResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Wraps the embedded Ktor engine so the service only has to call [start] / [stop].
 */
class KtorServer(
    private val repository: UserRepository,
    private val store: ReplicationStore,
    private val changeLog: ChangeLogDao,
    private val writeDao: WriteDao,
    private val promotions: PromotionDao,
    private val promotionCoordinator: PromotionCoordinator? = null,
    private val secrets: (suspend () -> Secrets)? = null,
    private val secretsStore: SecretsStore? = null,
    private val replicator: ChangeReplicator = NoReplication,
    private val peerMonitor: PeerMonitor? = null,
    private val port: Int = DEFAULT_PORT,
) {

    private val logger = KtorSimpleLogger("KtorServer")

    private var engine: EmbeddedServer<*, *>? = null
    private var startedAt: Long = 0L

    val isRunning: Boolean get() = engine != null

    fun start() {
        if (engine != null) return
        startedAt = Clock.System.now().toEpochMilliseconds()
        engine = embeddedServer(
            factory = CIO,
            port = port,
            // 0.0.0.0 makes the server reachable from other devices on the same
            // network. Use 127.0.0.1 to keep it on-device only.
            host = "0.0.0.0",
            module = {
                apiModule(
                    startedAt, repository, store, changeLog,
                    writeDao, promotions, promotionCoordinator, replicator, peerMonitor,
                    secrets, secretsStore,
                )
            },
        ).also {
            // wait = false: never block the caller's thread.
            it.start(wait = false)
            logger.info("Ktor server started on port $port")
        }
    }

    fun stop() {
        engine?.let {
            logger.info("Stopping Ktor server")
            it.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        }
        engine = null
        startedAt = 0L
    }

    companion object {
        const val TAG = "KtorServer"
        const val DEFAULT_PORT = 8080
    }
}

/** All plugins and routes for the local API. */
fun Application.apiModule(
    startedAt: Long,
    repository: UserRepository,
    store: ReplicationStore,
    changeLog: ChangeLogDao,
    writeDao: WriteDao,
    promotions: PromotionDao,
    promotionCoordinator: PromotionCoordinator?,
    replicator: ChangeReplicator,
    peerMonitor: PeerMonitor?,
    secrets: (suspend () -> Secrets)?,
    secretsStore: SecretsStore?,
) {

    // Every response says which role served it. A client reading from a secondary is
    // reading a replica, and should be able to tell without a second request.
    install(
        createApplicationPlugin("ReplicationRoleHeader") {
            onCall { call ->
                call.response.headers.append("X-Replication-Role", store.current().role.name)
                call.response.headers.append("X-Replication-Epoch", store.current().epoch.toString())
            }
        }
    )

    // Two tiers: the node token reaches /replication/ routes (replication batches, promotion,
    // failover history); the client token only reaches the data routes. A terminal's
    // credentials being lifted off a device must not let someone promote a terminal.
    if (secrets != null) {
        install(Authentication) {
            bearer("node") {
                realm = "TruCount node"
                authenticate { credential ->
                    val expected = secrets().nodeToken
                    if (constantTimeEquals(credential.token, expected)) NodeCaller else null
                }
            }
            bearer("client") {
                realm = "TruCount client"
                authenticate { credential ->
                    val current = secrets()
                    when {
                        constantTimeEquals(credential.token, current.clientToken) -> ClientCaller
                        // A node token is strictly more privileged, so it works here too.
                        constantTimeEquals(credential.token, current.nodeToken) -> NodeCaller
                        else -> null
                    }
                }
            }
        }
    }

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        )
    }

    // Lets a browser page or another app on the network call the API.
    install(CORS) {
        anyHost()
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Malformed request body", cause.message),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled request failure", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error", cause.message),
            )
        }
    }

    routing {

        // GET /health -> liveness probe
        get("/health") {
            val state = store.current()

            // Open so a watchdog can check liveness, but an anonymous caller gets only
            // that. Node identity, role, epoch and row counts describe the shape of the
            // deployment and are not owed to whoever can reach the port.
            val presented = call.request.headers[HttpHeaders.Authorization]
                ?.removePrefix("Bearer ")
                ?.trim()
            val known = secrets?.invoke()
            val authorised = known == null || !state.requireClientAuth || presented != null && (
                constantTimeEquals(presented, known.nodeToken) ||
                    constantTimeEquals(presented, known.clientToken)
                )

            if (!authorised) {
                return@get call.respond(
                    HealthResponse(
                        status = "UP",
                        uptimeMillis = Clock.System.now().toEpochMilliseconds() - startedAt,
                        userCount = 0,
                        serverTime = isoNow(),
                    )
                )
            }

            call.respond(
                HealthResponse(
                    status = "UP",
                    uptimeMillis = Clock.System.now().toEpochMilliseconds() - startedAt,
                    userCount = repository.count(),
                    serverTime = isoNow(),
                    nodeId = state.nodeId,
                    role = state.role.name,
                    epoch = state.epoch,
                    localSeq = changeLog.latestSeq(state.nodeId),
                )
            )
        }

        // Discovery is reachable with EITHER token: a terminal cannot route its traffic
        // without it, and it reveals only which terminal to talk to.
        authenticatedAs(secrets, "client", optional = true) {
            /**
             * Where the primary is, as far as this node knows.
             *
             * Answered by any node, so a client holding both addresses resolves in one
             * call. A secondary only names its peer if that peer actually claimed the
             * primary role when last heard from — guessing would send clients to a
             * terminal that has also stood down.
             */
            get("/replication/primary") {
                val state = store.current()
                val peerIsPrimary = peerMonitor?.peerRole?.value == NodeRole.PRIMARY.name &&
                    peerMonitor.peerReachable.value

                call.respond(
                    when {
                        state.role == NodeRole.PRIMARY -> PrimaryLocation(
                            primaryAddress = null,
                            primaryNodeId = state.nodeId,
                            epoch = state.epoch,
                            source = "self",
                            respondingRole = state.role.name,
                        )

                        peerIsPrimary -> PrimaryLocation(
                            primaryAddress = state.peerAddress,
                            primaryNodeId = state.peerNodeId,
                            epoch = state.epoch,
                            source = "peer",
                            respondingRole = state.role.name,
                        )

                        else -> PrimaryLocation(
                            epoch = state.epoch,
                            source = "unknown",
                            respondingRole = state.role.name,
                        )
                    }
                )
            }

        }

        authenticatedAs(secrets, "node") {
        route("/replication") {

            get("/status") {
                val state = store.current()
                val localSeq = changeLog.latestSeq(state.nodeId)
                call.respond(
                    ReplicationStatus(
                        nodeId = state.nodeId,
                        role = state.role.name,
                        epoch = state.epoch,
                        peerAddress = state.peerAddress,
                        peerNodeId = state.peerNodeId,
                        localSeq = localSeq,
                        lastAppliedSeq = state.lastAppliedSeq,
                        peerAckedSeq = state.peerAckedSeq,
                        unreplicated = (localSeq - state.peerAckedSeq).coerceAtLeast(0),
                        peerReachable = peerMonitor?.peerReachable?.value,
                        primaryUnavailable = peerMonitor?.primaryUnavailable?.value,
                        peerError = peerMonitor?.peerError?.value,
                        noPrimaryAnywhere = peerMonitor?.noPrimaryAnywhere?.value,
                        writesEnabled = peerMonitor?.let {
                            state.role == NodeRole.PRIMARY && it.startupConfirmed.value
                        },
                    )
                )
            }

            // Operator setup: declare this node primary or secondary.
            post("/role") {
                val body = call.receive<ConfigureRoleRequest>()
                val current = store.current()

                // Omitting the role updates only the peer address.
                val role = body.role?.let { raw ->
                    runCatching { NodeRole.valueOf(raw.uppercase()) }.getOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("role must be PRIMARY, SECONDARY or UNCONFIGURED"),
                        )
                } ?: current.role

                // Becoming primary is a promotion, and promotions have a safety check.
                // Letting this endpoint hand out the primary role would route around it,
                // which is how two nodes end up claiming the same epoch with no way back.
                if (role == NodeRole.PRIMARY &&
                    current.role == NodeRole.SECONDARY
                ) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(
                            "Use /replication/promote to make a secondary the primary",
                            "Setting the role directly skips the check that the other " +
                                "terminal has stood down. To correct the peer address, " +
                                "send peerAddress without a role.",
                        ),
                    )
                }

                val state = store.configure(role, body.peerAddress)

                // Becoming primary is the moment to make historical rows replicable.
                if (role == NodeRole.PRIMARY) {
                    val backfilled = repository.backfillUnloggedRows()
                    if (backfilled > 0) {
                        call.application.log.warn(
                            "Backfilled $backfilled pre-replication row(s) into change_log"
                        )
                    }
                }
                call.respond(
                    ReplicationStatus(
                        nodeId = state.nodeId,
                        role = state.role.name,
                        epoch = state.epoch,
                        peerAddress = state.peerAddress,
                        peerNodeId = state.peerNodeId,
                    )
                )
            }

            /**
             * The peer tells us the epoch it is operating under.
             *
             * Two jobs, both essential to fencing: a secondary learns the epoch it must
             * out-rank if it is ever promoted, and a primary that has been superseded
             * discovers it and stands down before taking another sale.
             */
            post("/heartbeat") {
                val body = call.receive<HeartbeatRequest>()
                val before = store.current()
                val state = store.observePeer(body.epoch, body.role, body.nodeId)

                if (before.role == NodeRole.PRIMARY && state.role != NodeRole.PRIMARY) {
                    call.application.log.warn(
                        "DEMOTED: peer ${body.nodeId} is at epoch ${body.epoch}, " +
                            "ours was ${before.epoch} - standing down"
                    )
                }

                call.respond(
                    ReplicationStatus(
                        nodeId = state.nodeId,
                        role = state.role.name,
                        epoch = state.epoch,
                        peerAddress = state.peerAddress,
                        peerNodeId = state.peerNodeId,
                        localSeq = changeLog.latestSeq(state.nodeId),
                        // The sender reconciles its ack mark from this. Omitting it made
                        // it believe the peer held nothing, so `unreplicated` reported
                        // sales at risk that were already safely on the other terminal.
                        lastAppliedSeq = state.lastAppliedSeq,
                    )
                )
            }

            /**
             * Receives a batch of changes from the primary.
             *
             * Safe to call twice with the same batch: applyBatch keys off (nodeId, seq)
             * and skips anything already stored, so the sender can retry a request that
             * timed out after it actually succeeded.
             */
            post("/apply") {
                val body = call.receive<ApplyChangesRequest>()

                // Adopt a higher epoch before deciding anything: if the sender has been
                // promoted since we last heard, it is the authority now.
                val state = store.observeEpoch(body.epoch, body.nodeId)

                if (body.epoch < state.epoch) {
                    // A superseded primary is trying to write to us. Refuse, and tell it
                    // the current epoch so it stands down.
                    call.application.log.warn(
                        "Rejected batch from ${body.nodeId}: epoch ${body.epoch} < ours ${state.epoch}"
                    )
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        ApplyChangesResponse(
                            accepted = false,
                            appliedThroughSeq = state.lastAppliedSeq,
                            epoch = state.epoch,
                            reason = "superseded: this node is at epoch ${state.epoch}",
                        ),
                    )
                }

                val prepared = body.entries.map { entry ->
                    PreparedChange(
                        entry = entry,
                        row = entry.payload?.let { replicationJson.decodeFromString<User>(it) },
                    )
                }

                val appliedThrough = writeDao.applyBatch(prepared)
                if (appliedThrough > 0) store.recordApplied(body.nodeId, appliedThrough)

                call.respond(
                    ApplyChangesResponse(
                        accepted = true,
                        appliedThroughSeq = appliedThrough,
                        epoch = state.epoch,
                    )
                )
            }

            /**
             * Failover. The operator decides when, never the software.
             *
             * Refuses by default if the peer is still answering and still claims to be
             * primary: that is not a failover, it is a second primary, and it ends with
             * two divergent sets of takings. force=true overrides, because a primary
             * can answer heartbeats while being useless, and the override is recorded.
             */
            post("/promote") {
                val body = runCatching { call.receive<PromoteRequest>() }
                    .getOrElse { PromoteRequest() }

                val coordinator = promotionCoordinator
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Promotion is not configured on this node"),
                    )

                when (val outcome = coordinator.promote(body.force, body.reason)) {
                    is PromotionCoordinator.Outcome.Refused -> call.respond(
                        HttpStatusCode.Conflict,
                        PromotionRefused(
                            error = "The other terminal is still running as primary",
                            peerRole = outcome.peerRole,
                            peerEpoch = outcome.peerEpoch,
                            ourEpoch = outcome.ourEpoch,
                        ),
                    )

                    is PromotionCoordinator.Outcome.Promoted -> call.respond(
                        ReplicationStatus(
                            nodeId = outcome.state.nodeId,
                            role = outcome.state.role.name,
                            epoch = outcome.state.epoch,
                            peerAddress = outcome.state.peerAddress,
                            peerNodeId = outcome.state.peerNodeId,
                        )
                    )
                }
            }

            /**
             * What this node holds that the peer has never confirmed.
             *
             * On a primary this is the ordinary backlog. On a demoted node these are
             * orphans — sales it took while it thought it was in charge — and they are
             * reported, never merged silently.
             */
            get("/orphans") {
                val state = store.current()
                val entries = changeLog.since(state.nodeId, state.peerAckedSeq)
                val demoted = state.role != NodeRole.PRIMARY

                call.respond(
                    OrphanReport(
                        nodeId = state.nodeId,
                        role = state.role.name,
                        epoch = state.epoch,
                        count = entries.size,
                        entries = entries,
                        note = when {
                            entries.isEmpty() -> "Nothing outstanding."
                            demoted -> "This terminal is no longer primary. These " +
                                "changes were taken while it believed it was, and the " +
                                "current primary has not seen them. Review before " +
                                "handing them over."
                            else -> "Normal replication backlog; the peer is behind."
                        },
                    )
                )
            }

            /**
             * Hands the orphans to the current primary, on the operator's say-so.
             *
             * Deliberately explicit. An orphaned INSERT can resurrect a row the new
             * primary deliberately deleted, and an orphaned sale may duplicate one that
             * was re-rung on the other terminal. Somebody has to look first.
             */
            post("/orphans/handover") {
                val state = store.current()
                val outstanding = changeLog.since(state.nodeId, state.peerAckedSeq)

                if (outstanding.isEmpty()) {
                    return@post call.respond(
                        OrphanReport(
                            nodeId = state.nodeId,
                            role = state.role.name,
                            epoch = state.epoch,
                            count = 0,
                            entries = emptyList(),
                            note = "Nothing to hand over.",
                        )
                    )
                }

                val delivered = replicator.handOver()
                val remaining = changeLog.since(store.current().nodeId, store.current().peerAckedSeq)

                call.application.log.warn(
                    "Orphan handover: ${outstanding.size} offered, ${remaining.size} remaining"
                )

                call.respond(
                    if (delivered) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                    OrphanReport(
                        nodeId = state.nodeId,
                        role = state.role.name,
                        epoch = state.epoch,
                        count = remaining.size,
                        entries = remaining,
                        note = if (delivered) {
                            "Handed ${outstanding.size - remaining.size} change(s) to the peer."
                        } else {
                            "Peer did not accept the handover; nothing was lost here."
                        },
                    ),
                )
            }

            /**
             * Adopts the other terminal's pairing token, so the two can replicate.
             *
             * Authenticated with THIS terminal's current token, which is printed on its
             * own screen — so pairing requires physical access to the terminal being
             * changed, not just knowledge of the one it is being joined to.
             *
             * Replication is rejected as unauthorised until both hold the same value,
             * which is the single most likely thing to be wrong in a fresh install.
             */
            post("/pair") {
                val body = call.receive<PairRequest>()
                val updated = runCatching { secretsStore?.setNodeToken(body.nodeToken.trim()) }
                    .getOrElse { failure ->
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Could not set the pairing token", failure.message),
                        )
                    }
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Pairing is not configured on this node"),
                    )

                call.application.log.warn("Pairing token replaced; peer must hold the same value")
                call.respond(
                    ReplicationStatus(
                        nodeId = store.current().nodeId,
                        role = store.current().role.name,
                        epoch = store.current().epoch,
                        peerAddress = store.current().peerAddress,
                    )
                )
            }

            /** Permanent failover history — who claimed which epoch, and why. */
            get("/promotions") {
                call.respond(promotions.recent())
            }
        }

        }

        authenticatedAs(secrets, "client", optional = true) {
        route("/users") {

            // GET /users -> list every user
            get {
                if (!call.allowedAsClient(store)) return@get
                val users = repository.all()
                call.respond(UsersResponse(count = users.size, users = users))
            }

            // GET /users/{id} -> one user
            get("/{id}") {
                if (!call.allowedAsClient(store)) return@get
                val id = call.parameters["id"]?.takeIf { it.isNotBlank() }
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("id must not be blank"),
                    )

                val user = repository.byId(id)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("No user with id $id"),
                    )

                call.respond(user)
            }

            // POST /users -> create a user
            post {
                if (!call.allowedAsClient(store)) return@post
                call.requirePrimary(store, peerMonitor) ?: return@post

                val body = call.receive<CreateUserRequest>()

                val name = body.name.trim()
                val email = body.email.trim()

                when {
                    name.isEmpty() -> return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("name must not be blank"),
                    )

                    !email.contains("@") || email.length < 3 -> return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("email is not a valid address"),
                    )

                    repository.existsByEmail(email) -> return@post call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("A user with email $email already exists"),
                    )
                }

                // Null means the unique index rejected it (lost a concurrent race).
                val created = repository.add(name, email)
                    ?: return@post call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("A user with email $email already exists"),
                    )

                // Synchronous replication: wait for the secondary to confirm before
                // telling the caller the sale is committed. A false return means the
                // peer was unreachable; by agreed policy we still confirm and let the
                // catch-up loop deliver it.
                replicator.push()

                call.respond(HttpStatusCode.Created, created)
            }

            // DELETE /users/{id}
            delete("/{id}") {
                if (!call.allowedAsClient(store)) return@delete
                call.requirePrimary(store, peerMonitor) ?: return@delete

                val id = call.parameters["id"]?.takeIf { it.isNotBlank() }
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("id must not be blank"),
                    )

                if (repository.delete(id)) {
                    replicator.push()
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No user with id $id"))
                }
            }
        }
        }
    }
}

/**
 * Applies a bearer requirement, or none at all when the server is running unsecured.
 *
 * Keeps the route tree identical in both cases so there is no second, untested shape
 * of the routing to drift out of sync.
 */
private fun io.ktor.server.routing.Route.authenticatedAs(
    secrets: (suspend () -> Secrets)?,
    provider: String,
    optional: Boolean = false,
    build: io.ktor.server.routing.Route.() -> Unit,
) {
    if (secrets == null) build() else authenticate(provider, optional = optional) { build() }
}

/**
 * Enforces the token on the data routes only when this terminal is set to require it.
 *
 * Off by default: during setup and development the API has to be reachable from a
 * browser, and a browser cannot send an Authorization header. The replication and
 * operator routes are deliberately not covered by this — they are where someone on the
 * shop network could write into the database or take over a terminal.
 */
private suspend fun ApplicationCall.allowedAsClient(
    store: com.example.ktorserver.data.ReplicationStore,
): Boolean {
    if (!store.current().requireClientAuth) return true
    if (principal<Any>() != null) return true

    respond(
        HttpStatusCode.Unauthorized,
        ErrorResponse(
            "This terminal requires a code for API access",
            "Send it as 'Authorization: Bearer <code>', or turn the requirement off on " +
                "the terminal's screen",
        ),
    )
    return false
}

/** Marks a caller holding the node token. */
internal object NodeCaller

/** Marks a caller holding only the client token. */
internal object ClientCaller

/** Shared by the apply endpoint; kept lenient so a newer peer can add fields. */
private val replicationJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Rejects a write unless this node is the primary.
 *
 * Reads are deliberately still allowed on a secondary — it is a replica, and letting it
 * serve reads is most of the point. Only writes are fenced.
 *
 * 421 carries the peer's address as the likely primary. With exactly two servers that is
 * a safe inference; it becomes a guess if a third server is ever added.
 */
private suspend fun ApplicationCall.requirePrimary(
    store: com.example.ktorserver.data.ReplicationStore,
    peerMonitor: PeerMonitor?,
): ReplicationState? {
    // Only name the peer if it actually claimed primary when last heard from. Sending a
    // client to a terminal that has also stood down just costs it another round trip.
    val peerLikelyPrimary = peerMonitor == null ||
        (peerMonitor.peerRole.value == NodeRole.PRIMARY.name && peerMonitor.peerReachable.value)
    val state = store.current()

    if (state.role == NodeRole.PRIMARY) {
        // A restarted primary must confirm nobody was promoted while it was gone before
        // it takes a sale. 503 rather than 421: we are probably still the primary, the
        // client should retry here in a moment rather than go elsewhere.
        if (peerMonitor != null && !peerMonitor.startupConfirmed.value) {
            response.headers.append("Retry-After", "1")
            respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "Confirming replication state with peer",
                    "This node restarted and is checking whether it is still the primary",
                ),
            )
            return null
        }
        return state
    }

    respond(
        HttpStatusCode(421, "Misdirected Request"),
        NotPrimaryResponse(
            role = state.role.name,
            epoch = state.epoch,
            primaryAddress = state.peerAddress.takeIf { peerLikelyPrimary },
        ),
    )
    return null
}

/** kotlinx-datetime renders ISO-8601 in UTC on every target. */
private fun isoNow(): String = Clock.System.now().toString()
