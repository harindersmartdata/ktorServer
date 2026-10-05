package com.example.ktorserver.server

/**
 * Claims a fixed name on the local network for whichever terminal is currently primary.
 *
 * The point is a URL that never changes. A till is configured once with
 * `http://tucount.local:8080` and keeps working after a failover, because the name
 * follows the primary rather than the primary having to take over an address — which
 * stock Android cannot do without root.
 *
 * Only the primary may advertise. Two terminals claiming the same name at once is an
 * mDNS conflict and clients would resolve to whichever answered first.
 */
interface ServiceAdvertiser {

    /** Claims `<hostName>.local` for [ipv4]. Safe to call repeatedly with the same values. */
    suspend fun advertise(hostName: String, ipv4: String, port: Int)

    /** Gives up the name, so a demoted terminal stops answering for it. */
    fun withdraw()

    companion object {
        /** The name tills are configured with. Changing it means reconfiguring them. */
        const val HOST_NAME = "tucount"

        /** What an operator should put in the POS app. */
        fun url(port: Int): String = "http://$HOST_NAME.local:$port"
    }
}

/** Used where mDNS is unavailable; the candidate-list fallback still applies. */
object NoAdvertising : ServiceAdvertiser {
    override suspend fun advertise(hostName: String, ipv4: String, port: Int) = Unit
    override fun withdraw() = Unit
}
