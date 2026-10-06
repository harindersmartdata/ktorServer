package com.example.ktorserver.server

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Adds the multicast lock Android needs.
 *
 * Without it the responder starts and simply never answers: the OS drops multicast
 * frames before they reach the app, so both announcing and discovery fail silently.
 * The lock costs battery, so it is released as soon as mDNS is shut down.
 */
class AndroidServiceAdvertiser(context: Context) : JmdnsServiceAdvertiser() {

    private val appContext = context.applicationContext
    private var lock: WifiManager.MulticastLock? = null

    override fun onNetworkStart() {
        if (lock != null) return
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("trucount-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onNetworkStop() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }
}
