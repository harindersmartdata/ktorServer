package com.example.ktorserver.security

import com.example.ktorserver.data.ReplicationStateDao
import com.example.ktorserver.data.ReplicationStore

import io.ktor.util.logging.KtorSimpleLogger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The shared secrets this terminal uses.
 *
 * Two tiers on purpose. The node token admits a caller to `/replication/` routes — applying
 * change batches, promoting a terminal, reading the failover history. The client token
 * only admits a caller to the data routes. A terminal therefore cannot promote a server or
 * inject replication batches even if its credentials are extracted from the device,
 * which is the realistic threat in a shop.
 *
 * **These protect against casual access on the shop network, not against someone
 * watching the traffic.** The server speaks plain HTTP, so every request carries its
 * token in clear text and anyone able to capture packets on that LAN can take it.
 * Closing that needs TLS, which needs certificates the terminals both trust.
 */
class Secrets(
    val nodeToken: String,
    val clientToken: String,
)

@OptIn(ExperimentalUuidApi::class)
class SecretsStore(
    private val dao: ReplicationStateDao,
    private val store: ReplicationStore,
    /** Overrides, for installs that inject configuration rather than pair by hand. */
    private val nodeTokenOverride: String? = null,
    private val clientTokenOverride: String? = null,
) {

    private val logger = KtorSimpleLogger("Secrets")

    // Read on every authenticated request, so it must not hit the database each time.
    private var cached: Secrets? = null

    /**
     * Returns the tokens, generating and persisting them on first run.
     *
     * A generated node token is logged once, loudly: the operator has to copy it to the
     * other terminal, and the pair cannot replicate until both hold the same one.
     */
    suspend fun load(): Secrets {
        cached?.let { return it }
        val state = store.current()

        val node = nodeTokenOverride ?: state.nodeToken ?: generate("node")
        val client = clientTokenOverride ?: state.clientToken ?: generate("client")

        if (node != state.nodeToken || client != state.clientToken) {
            dao.put(state.copy(nodeToken = node, clientToken = client))
        }

        return Secrets(node, client).also { cached = it }
    }

    /** Replaces the node token — used when pairing this terminal with another. */
    suspend fun setNodeToken(raw: String): Secrets {
        val token = normalise(raw)
        require(token.length >= MIN_LENGTH) { "Code must be at least $MIN_LENGTH characters" }
        val state = store.current()
        dao.put(state.copy(nodeToken = token))
        logger.warn("Node token replaced; this terminal can only pair with one holding the same value")
        return Secrets(token, state.clientToken ?: generate("client")).also { cached = it }
    }

    private fun generate(kind: String): String {
        val token = if (kind == "node") pairingCode() else Uuid.random().toString().replace("-", "")
        logger.warn("Generated a new $kind token.")
        return token
    }

    /**
     * A code a person can read off one screen and type into another.
     *
     * The previous 32-character hex token was unusable in a shop: nobody is going to
     * transcribe that correctly from a tablet to a phone. Eight characters from an
     * alphabet with no 0/O or 1/I is about 40 bits, which is appropriate here — an
     * attacker also has to be on the shop network, and physical access to the terminal
     * is needed to read the code in the first place.
     */
    private fun pairingCode(): String {
        val raw = Uuid.random().toString().filter { it.isLetterOrDigit() }.uppercase()
        return (0 until CODE_LENGTH)
            .map { ALPHABET[(raw[it].code + it) % ALPHABET.length] }
            .joinToString("")
    }

    companion object {
        const val MIN_LENGTH = 8
        const val CODE_LENGTH = 8

        /** No 0/O, 1/I/L — the characters people get wrong reading aloud. */
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** Displayed in groups so it can be read out loud. */
        fun format(code: String): String =
            if (code.length == CODE_LENGTH) "${code.take(4)}-${code.drop(4)}" else code

        /** Accepts what a person typed: any case, with or without the dash. */
        fun normalise(entered: String): String =
            entered.filter { it.isLetterOrDigit() }.uppercase()
    }
}

/**
 * Compares two tokens without leaking their contents through timing.
 *
 * A naive `==` returns as soon as it finds a difference, so the time it takes reveals
 * how much of a guess was right and lets an attacker recover the token one character at
 * a time. This always inspects every character.
 */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
