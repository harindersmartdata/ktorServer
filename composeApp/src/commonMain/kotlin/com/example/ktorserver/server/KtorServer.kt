package com.example.ktorserver.server

import com.example.ktorserver.data.ApplyChangesRequest
import com.example.ktorserver.data.ApplyChangesResponse
import com.example.ktorserver.data.ChangeLogDao
import com.example.ktorserver.data.ConfigureRoleRequest
import com.example.ktorserver.data.HeartbeatRequest
import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.NotPrimaryResponse
import com.example.ktorserver.data.OrphanReport
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
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
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
) {

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
             * was re-rung on the other till. Somebody has to look first.
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

            /** Permanent failover history — who claimed which epoch, and why. */
            get("/promotions") {
                call.respond(promotions.recent())
            }
        }

        route("/users") {

            // GET /users -> list every user
            get {
                val users = repository.all()
                call.respond(UsersResponse(count = users.size, users = users))
            }

            // GET /users/{id} -> one user
            get("/{id}") {
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
            primaryAddress = state.peerAddress,
        ),
    )
    return null
}

/** kotlinx-datetime renders ISO-8601 in UTC on every target. */
private fun isoNow(): String = Clock.System.now().toString()
