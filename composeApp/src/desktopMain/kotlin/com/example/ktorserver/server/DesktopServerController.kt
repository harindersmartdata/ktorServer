package com.example.ktorserver.server

import com.example.ktorserver.data.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop has no service layer to hide behind: the engine lives in this process and
 * dies with it, so start/stop are direct calls and the state flows are owned here.
 */
class DesktopServerController(
    repository: UserRepository,
    port: Int = KtorServer.DEFAULT_PORT,
) : ServerController {

    private val server = KtorServer(repository, port)

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override fun start() {
        if (_isRunning.value) return
        runCatching { server.start() }
            .onSuccess {
                _isRunning.value = true
                _lastError.value = null
            }
            .onFailure { error ->
                // Port already taken is the common one — surface it instead of dying.
                _lastError.value = error.message ?: error::class.simpleName
                _isRunning.value = false
            }
    }

    override fun stop() {
        runCatching { server.stop() }
        _isRunning.value = false
    }
}
