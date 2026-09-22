package com.example.ktorserver.server

import android.util.Log
import com.example.ktorserver.data.CreateUserRequest
import com.example.ktorserver.data.ErrorResponse
import com.example.ktorserver.data.HealthResponse
import com.example.ktorserver.data.UserRepository
import com.example.ktorserver.data.UsersResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wraps the embedded Ktor engine so the service only has to call [start] / [stop].
 */
class KtorServer(
    private val repository: UserRepository,
    private val port: Int = DEFAULT_PORT,
) {

    private var engine: EmbeddedServer<*, *>? = null
    private var startedAt: Long = 0L

    val isRunning: Boolean get() = engine != null

    fun start() {
        if (engine != null) return
        startedAt = System.currentTimeMillis()
        engine = embeddedServer(
            factory = CIO,
            port = port,
            // 0.0.0.0 makes the server reachable from other devices on the same
            // network. Use 127.0.0.1 to keep it on-device only.
            host = "0.0.0.0",
            module = { apiModule(startedAt, repository) },
        ).also {
            // wait = false: never block the caller's thread.
            it.start(wait = false)
            Log.i(TAG, "Ktor server started on port $port")
        }
    }

    fun stop() {
        engine?.let {
            Log.i(TAG, "Stopping Ktor server")
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
fun Application.apiModule(startedAt: Long, repository: UserRepository) {

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
            Log.e(KtorServer.TAG, "Unhandled request failure", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error", cause.message),
            )
        }
    }

    routing {

        // GET /health -> liveness probe
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "UP",
                    uptimeMillis = System.currentTimeMillis() - startedAt,
                    userCount = repository.count(),
                    serverTime = isoNow(),
                )
            )
        }

        route("/users") {

            // GET /users -> list every user
            get {
                val users = repository.all()
                call.respond(UsersResponse(count = users.size, users = users))
            }

            // GET /users/{id} -> one user
            get("/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("id must be an integer"),
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
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("id must be an integer"),
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

private fun isoNow(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
