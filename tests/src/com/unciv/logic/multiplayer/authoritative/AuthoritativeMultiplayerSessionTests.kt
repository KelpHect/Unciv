package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthoritativeMultiplayerSessionTests {
    @Test
    fun incompatibleCapabilitiesFailBeforeSessionRestore() = runBlocking {
        val transport = FakeTransport().apply {
            capabilities = capabilities.copy(wholeStateUpload = true)
        }
        val session = session(transport)

        assertThrows<IllegalStateException> { session.restore() }
        assertEquals(0, transport.restoreCalls)
        session.close()
    }

    @Test
    fun openingAGameRequiresAuthenticationAndBootstrapsWithHttp() = runBlocking {
        val transport = FakeTransport()
        val session = session(transport)

        assertThrows<IllegalStateException> {
            session.openGame(GAME_ID)
        }
        assertFalse(session.restore())
        transport.restored = true
        assertTrue(session.restore())

        val bus = session.openGame(GAME_ID)

        assertEquals(1, transport.projectionCalls)
        assertEquals(7, (bus.state as AuthoritativeSyncState.Synchronized).current.committedRevision)
        session.close()
    }

    @Test
    fun notificationsAreHintsAndReconcileThroughHttpDespiteDuplicatesAndReordering() = runBlocking {
        val transport = FakeTransport().apply { restored = true }
        val session = session(transport)
        assertTrue(session.restore())
        val bus = session.openGame(GAME_ID)
        assertEquals(1, transport.projectionCalls)
        eventually { transport.notifications.subscriptionCount.value == 1 }

        transport.current = projection(8, "hash-8")
        transport.notifications.emit(notification(8, "hash-8"))
        eventually { transport.projectionCalls == 2 }
        assertEquals(8, (bus.state as AuthoritativeSyncState.Synchronized).current.committedRevision)

        transport.notifications.emit(notification(8, "hash-8"))
        transport.notifications.emit(notification(7, "hash-7"))
        delay(50)
        assertEquals(2, transport.projectionCalls)

        transport.current = projection(9, "hash-9")
        transport.notifications.emit(
            ApiV3RevisionNotification("resync_required", 3),
        )
        eventually { transport.projectionCalls == 3 }
        assertEquals(9, (bus.state as AuthoritativeSyncState.Synchronized).current.committedRevision)
        session.close()
    }

    @Test
    fun logoutDropsOpenedGamesAndStopsAuthenticatedUse() = runBlocking {
        val transport = FakeTransport().apply { restored = true }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        session.logout()

        assertEquals(1, transport.logoutCalls)
        assertThrows<IllegalStateException> {
            session.openGame(GAME_ID)
        }
        session.close()
    }

    private fun session(transport: FakeTransport) = AuthoritativeMultiplayerSession.create(
        transport,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private suspend fun eventually(predicate: () -> Boolean) = withTimeout(2_000) {
        while (!predicate()) delay(10)
    }

    private suspend inline fun <reified T : Throwable> assertThrows(
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (throwable: Throwable) {
            if (throwable !is T) throw throwable
        }
    }

    private fun notification(revision: Long, hash: String) = ApiV3RevisionNotification(
        type = "revision_committed",
        protocolVersion = 3,
        gameId = GAME_ID,
        committedRevision = revision,
        canonicalStateHash = hash,
    )

    private fun projection(revision: Long, hash: String) = ApiV3GameProjection(
        gameId = GAME_ID,
        projectionVersion = 2,
        committedRevision = revision,
        canonicalStateHash = hash,
        projectionHash = "projection-$revision",
        projection = PlayerProjection(
            civilizationId = "Rome",
            turn = revision.toInt(),
            currentPlayerCivilizationId = "Rome",
            isCurrentTurn = true,
            gold = 0,
            knownCivilizations = emptyList(),
            ownCities = emptyList(),
            ownUnits = emptyList(),
            exploredTiles = emptyList(),
            visibleForeignUnits = emptyList(),
        ),
    )

    private inner class FakeTransport : ApiV3Transport {
        var capabilities = ApiV3Capabilities(3, 2, listOf("end_turn"), false, true)
        var restored = false
        var restoreCalls = 0
        @Volatile
        var projectionCalls = 0
        var logoutCalls = 0
        @Volatile
        var current = projection(7, "hash-7")
        val notifications = MutableSharedFlow<ApiV3RevisionNotification>(extraBufferCapacity = 8)

        override suspend fun restoreSession(): Boolean {
            restoreCalls++
            return restored
        }
        override suspend fun capabilities() = capabilities
        override suspend fun register(username: String, password: String) = ApiV3Account("account", username)
        override suspend fun login(username: String, password: String) = ApiV3Account("account", username)
        override suspend fun refreshSession() = Unit
        override suspend fun logout() { logoutCalls++ }
        override suspend fun createGame(rulesetManifestHash: String) =
            ApiV3GameMetadata(GAME_ID, 0, "hash-0", "owner", "Rome")
        override suspend fun joinGame(gameId: String, request: ApiV3JoinGameRequest) = unsupported()
        override suspend fun projection(gameId: String): ApiV3GameProjection {
            projectionCalls++
            return current
        }
        override suspend fun moveUnit(gameId: String, request: ApiV3MoveUnitRequest) = unsupported()
        override suspend fun queueConstruction(
            gameId: String,
            request: ApiV3QueueConstructionRequest,
        ) = unsupported()
        override suspend fun endTurn(gameId: String, request: ApiV3EndTurnRequest) = unsupported()
        override fun notifications(): Flow<ApiV3RevisionNotification> = notifications

        private fun unsupported(): Nothing = error("not used by this lifecycle test")
    }

    companion object {
        private const val GAME_ID = "00000000-0000-0000-0000-000000000001"
    }
}
