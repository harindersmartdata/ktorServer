package com.example.ktorserver.server

import android.content.Context
import com.example.ktorserver.service.KtorServerService
import kotlinx.coroutines.flow.StateFlow

/**
 * Routes start/stop through the foreground service. The service owns the engine, so
 * the socket outlives the activity.
 */
class AndroidServerController(private val context: Context) : ServerController {
    override val isRunning: StateFlow<Boolean> = KtorServerService.isRunning
    override val lastError: StateFlow<String?> = KtorServerService.lastError
    override fun start() = KtorServerService.start(context)
    override fun stop() = KtorServerService.stop(context)
}
