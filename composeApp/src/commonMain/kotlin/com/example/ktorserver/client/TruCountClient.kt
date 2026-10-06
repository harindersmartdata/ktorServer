package com.example.ktorserver.client

import com.example.ktorserver.data.CreateUserRequest
import com.example.ktorserver.data.HealthResponse
import com.example.ktorserver.data.NotPrimaryResponse
import com.example.ktorserver.data.PrimaryLocation
import com.example.ktorserver.data.User
import com.example.ktorserver.data.UsersResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** No candidate could tell us where the primary is. */
class NoPrimaryAvailable(candidates: List<String>) :
    Exception("No primary found among: ${candidates.joinToString()}")

/**
 * Talks to whichever terminal is currently primary.
 *
 * A terminal is configured with the addresses of both server terminals and this works out
 * which one to use, re-resolving whenever that changes. Failover is therefore invisible
 * to calling code: a sale rung during a promotion is retried against the new primary
 * rather than failing.
 *
 * Deliberately address-based rather than relying on a fixed IP moving between machines.
 * IP takeover needs root on Android, and the server terminals may be Android tablets.
 *
 * Writes are only ever sent to the primary. Reads go there too by default, because a
 * secondary can be behind; [allowStaleReads] relaxes that when stale data is acceptable
 * and availability matters more.
 */
class TruCountClient(
    private val candidates: List<String>,
    /** Client token issued by the server pair. Null only for an unsecured install. */
    private val token: String? = null,
    private val http: HttpClient = defaultClient(),
    private val maxAttempts: Int = 4,
    private val allowStaleReads: Boolean = false,
) {

    private val logger = KtorSimpleLogger("TruCountClient")

    private val _primary = MutableStateFlow<String?>(null)

    /** The address currently believed to be primary, for display. */
    val primary: StateFlow<String?> = _primary.asStateFlow()

    // ---- API ---------------------------------------------------------------

    suspend fun health(): HealthResponse =
        request { base -> http.get("http://$base/health") { auth() } }.body()

    suspend fun users(): UsersResponse =
        request { base -> http.get("http://$base/users") { auth() } }.body()

    suspend fun createUser(name: String, email: String): User =
        request { base ->
            http.post("http://$base/users") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(CreateUserRequest(name, email))
            }
        }.body()

    suspend fun deleteUser(id: String): Boolean =
        request { base -> http.delete("http://$base/users/$id") { auth() } }
            .status == HttpStatusCode.NoContent

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    // ---- resolution --------------------------------------------------------

    /**
     * Finds the primary by asking any candidate that answers.
     *
     * Every node answers `/replication/primary`, including secondaries, so one reachable
     * terminal is enough — there is no need for the primary itself to be the first one
     * tried.
     */
    suspend fun resolvePrimary(): String {
        val ordered = buildList {
            _primary.value?.let(::add)
            addAll(candidates.filterNot { it == _primary.value })
        }

        for (candidate in ordered) {
            val location = runCatching {
                http.get("http://$candidate/replication/primary") { auth() }
                    .body<PrimaryLocation>()
            }.getOrNull() ?: continue

            val resolved = when (location.source) {
                "self" -> candidate
                "peer" -> location.primaryAddress
                else -> null
            } ?: continue

            if (resolved != _primary.value) {
                logger.info("Primary is $resolved (via $candidate, ${location.source})")
            }
            _primary.value = resolved
            return resolved
        }

        _primary.value = null
        throw NoPrimaryAvailable(candidates)
    }

    /**
     * Sends a request to the primary, re-resolving and retrying when it moves.
     *
     * Handles the three ways the server says "not here, or not yet":
     *  - **421** the node is not primary; the body names who is
     *  - **503** a restarted primary is still confirming its epoch — wait and retry here
     *  - a connection failure; the terminal is gone, so look elsewhere
     */
    private suspend fun request(call: suspend (String) -> HttpResponse): HttpResponse {
        var lastFailure: Throwable? = null

        repeat(maxAttempts) { attempt ->
            val base = runCatching { _primary.value ?: resolvePrimary() }
                .getOrElse { failure -> lastFailure = failure; null }
                ?: run { delay(backoff(attempt)); return@repeat }

            val response = runCatching { call(base) }.getOrElse { failure ->
                // The terminal we had is unreachable: forget it and look again.
                logger.debug("$base unreachable (${failure::class.simpleName}); re-resolving")
                lastFailure = failure
                _primary.value = null
                delay(backoff(attempt))
                return@repeat
            }

            when (response.status.value) {
                421 -> {
                    val redirect = runCatching { response.body<NotPrimaryResponse>() }.getOrNull()
                    val target = redirect?.primaryAddress
                    logger.info("$base is ${redirect?.role}; primary is ${target ?: "unknown"}")
                    // Trust the redirect when given; otherwise fall back to a full probe.
                    _primary.value = target
                    if (target == null) runCatching { resolvePrimary() }
                }

                503 -> {
                    val wait = response.headers["Retry-After"]?.toLongOrNull()?.times(1000)
                        ?: backoff(attempt)
                    logger.debug("$base still confirming its role; retrying in ${wait}ms")
                    delay(wait)
                }

                else -> return response
            }
        }

        throw lastFailure ?: NoPrimaryAvailable(candidates)
    }

    private fun backoff(attempt: Int): Long = (200L shl attempt).coerceAtMost(2_000L)

    fun close() = http.close()

    companion object {
        fun defaultClient(timeoutMillis: Long = 3_000): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
            // 421 and 503 are routing information, not failures to throw on.
            expectSuccess = false
        }
    }
}
