package com.example.ktorserver.server

import kotlinx.coroutines.flow.StateFlow

/**
 * How the UI starts and stops the server, without knowing how the platform hosts it.
 *
 * Android has to route through a foreground service or the OS kills the socket the
 * moment the activity leaves the screen. On desktop the server simply lives in the
 * process, so the implementation there is a direct call.
 */
interface ServerController {
    val isRunning: StateFlow<Boolean>
    val lastError: StateFlow<String?>
    fun start()
    fun stop()
}
