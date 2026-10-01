package com.example.ktorserver.server

import com.example.ktorserver.data.ChangeLogDao
import com.example.ktorserver.data.ConfigureRoleRequest
import com.example.ktorserver.data.HeartbeatRequest
import com.example.ktorserver.data.NodeRole
import com.example.ktorserver.data.NotPrimaryResponse
import com.example.ktorserver.data.ReplicationState
import com.example.ktorserver.data.ReplicationStatus
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
            module = { apiModule(startedAt, repository, store, changeLog) },
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
                call.respond(
                    ReplicationStatus(
                        nodeId = state.nodeId,
                        role = state.role.name,
                        epoch = state.epoch,
                        peerAddress = state.peerAddress,
                        peerNodeId = state.peerNodeId,
                        localSeq = changeLog.latestSeq(state.nodeId),
                        lastAppliedSeq = state.lastAppliedSeq,
                    )
                )
            }

            // Operator setup: declare this node primary or secondary.
            post("/role") {
                val body = call.receive<ConfigureRoleRequest>()
                val role = runCatching { NodeRole.valueOf(body.role.uppercase()) }.getOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("role must be PRIMARY, SECONDARY or UNCONFIGURED"),
                    )

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
                val state = store.observeEpoch(body.epoch, body.nodeId)

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

            // Failover: claim a new epoch. The operator decides when, not the software.
            post("/promote") {
                val state = store.promote()
                call.application.log.warn(
                    "PROMOTED to primary at epoch ${state.epoch} (node ${state.nodeId})"
                )
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
                call.requirePrimary(store) ?: return@post

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

                call.respond(HttpStatusCode.Created, created)
            }

            // DELETE /users/{id}
            delete("/{id}") {
                call.requirePrimary(store) ?: return@delete

                val id = call.parameters["id"]?.takeIf { it.isNotBlank() }
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("id must not be blank"),
                    )

                if (repository.delete(id)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No user with id $id"))
                }
            }
        }
    }
}

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
): ReplicationState? {
    val state = store.current()
    if (state.role == NodeRole.PRIMARY) return state

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
