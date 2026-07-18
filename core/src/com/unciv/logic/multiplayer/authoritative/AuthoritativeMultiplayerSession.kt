package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one authenticated API-v3 client lifecycle. UI code opens a game here
 * instead of constructing transports or command buses ad hoc. The initial HTTP
 * projection is always authoritative; WebSocket messages are only hints that
 * cause another authenticated HTTP reconciliation.
 */
class AuthoritativeMultiplayerSession(
    private val transport: ApiV3Transport,
    parentScope: CoroutineScope,
    private val closeTransport: Boolean = false,
) : AutoCloseable {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val mutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val games = mutableMapOf<String, AuthoritativeGameCommandBus>()
    private var notificationJob: Job? = null
    @Volatile
    private var negotiated = false
    @Volatile
    private var authenticated = false

    suspend fun restore(): Boolean {
        negotiate()
        authenticated = transport.restoreSession()
        if (authenticated) {
            startNotifications()
        } else {
            notificationJob?.cancel()
            notificationJob = null
            mutex.withLock { games.clear() }
        }
        return authenticated
    }

    suspend fun login(username: String, password: String): ApiV3Account {
        negotiate()
        val account = transport.login(username, password)
        authenticated = true
        startNotifications()
        return account
    }

    suspend fun register(username: String, password: String): ApiV3Account {
        negotiate()
        return transport.register(username, password)
    }

    suspend fun refreshSession() {
        requireAuthenticated()
        transport.refreshSession()
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        requireAuthenticated()
        transport.changePassword(currentPassword, newPassword)
    }

    suspend fun disableAccount(password: String) {
        requireAuthenticated()
        try {
            transport.disableAccount(password)
        } finally {
            clearAuthenticatedState()
        }
    }

    suspend fun deleteAccount(password: String) {
        requireAuthenticated()
        try {
            transport.deleteAccount(password)
        } finally {
            clearAuthenticatedState()
        }
    }

    suspend fun listGames(after: String? = null, limit: Int = 50): ApiV3GamePage {
        requireAuthenticated()
        return transport.listGames(after, limit)
    }

    suspend fun openGame(gameId: String): AuthoritativeGameCommandBus {
        requireAuthenticated()
        require(gameId.isNotBlank()) { "gameId must not be blank" }
        val bus = mutex.withLock {
            games.getOrPut(gameId) { AuthoritativeGameCommandBus(gameId, transport) }
        }
        bus.refresh()
        return bus
    }

    suspend fun closeGame(gameId: String) {
        mutex.withLock { games.remove(gameId) }
    }

    suspend fun logout() {
        if (authenticated) transport.logout()
        clearAuthenticatedState()
    }

    private suspend fun clearAuthenticatedState() {
        authenticated = false
        notificationJob?.cancel()
        notificationJob = null
        mutex.withLock { games.clear() }
    }

    private suspend fun negotiate() = lifecycleMutex.withLock {
        if (!negotiated) {
            val capabilities = transport.capabilities()
            check(capabilities.protocolVersion == CommandEnvelope.CURRENT_PROTOCOL_VERSION) {
                "Server uses an incompatible authoritative protocol"
            }
            check(capabilities.projectionVersion == PlayerProjection.CURRENT_PROJECTION_VERSION) {
                "Server uses an incompatible player projection"
            }
            check(!capabilities.wholeStateUpload) {
                "Authoritative API unexpectedly permits whole-state uploads"
            }
            negotiated = true
        }
    }

    private fun requireAuthenticated() {
        check(authenticated) { "Authenticate before opening an authoritative game" }
    }

    private fun startNotifications() {
        if (notificationJob?.isActive == true) return
        notificationJob = scope.launch {
            transport.notifications().collect { notification ->
                val targets = mutex.withLock {
                    if (notification.type == "resync_required") games.values.toList()
                    else listOfNotNull(notification.gameId?.let(games::get))
                }
                targets.forEach { bus ->
                    // A failed HTTP refresh must not permanently stop later
                    // notification hints. The bus retains its retry state.
                    runCatching { bus.reconcile(notification) }
                }
            }
        }
    }

    override fun close() {
        notificationJob?.cancel()
        sessionJob.cancel()
        if (closeTransport) (transport as? AutoCloseable)?.close()
    }

    companion object {
        fun create(
            transport: ApiV3Transport,
            scope: CoroutineScope = CoroutineScope(SupervisorJob()),
            closeTransport: Boolean = false,
        ) = AuthoritativeMultiplayerSession(transport, scope, closeTransport)
    }
}
