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
import java.io.IOException

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

    @Test
    fun gameDiscoveryRequiresAuthenticationAndPreservesServerPagination() = runBlocking {
        val transport = FakeTransport().apply { restored = true }
        val session = session(transport)
        assertThrows<IllegalStateException> { session.listGames() }
        session.restore()

        val page = session.listGames(after = GAME_ID, limit = 25)

        assertEquals(listOf(GAME_ID to 25), transport.listCalls)
        assertEquals(GAME_ID, page.games.single().gameId)
        assertEquals(NEXT_GAME_ID, page.nextCursor)
        session.close()
    }

    @Test
    fun apiClientRejectsMalformedPagingBeforeNetworkAccess() = runBlocking {
        val client = ApiV3Client(
            "http://127.0.0.1:1",
            InMemoryApiV3SessionTokenStore(),
        )
        try {
            assertThrows<IllegalArgumentException> { client.listGames(limit = 0) }
            assertThrows<IllegalArgumentException> { client.listGames(after = "not-a-uuid") }
        } finally {
            client.close()
        }
    }

    @Test
    fun accountLifecycleRotatesCredentialsAndClearsAuthenticatedGameState() = runBlocking {
        val transport = FakeTransport().apply { restored = true }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        session.changePassword("old-password", "new-password")
        assertEquals(listOf("old-password" to "new-password"), transport.passwordChanges)
        session.disableAccount("new-password")
        assertEquals(listOf("new-password"), transport.disableRequests)
        assertThrows<IllegalStateException> { session.openGame(GAME_ID) }
        session.close()

        val deleteTransport = FakeTransport().apply { restored = true }
        val deleteSession = session(deleteTransport)
        deleteSession.restore()
        deleteSession.deleteAccount("delete-password")
        assertEquals(listOf("delete-password"), deleteTransport.deleteRequests)
        assertThrows<IllegalStateException> { deleteSession.listGames() }
        deleteSession.close()

        val ambiguousTransport = FakeTransport().apply {
            restored = true
            disableFailure = IOException("lost response")
        }
        val ambiguousSession = session(ambiguousTransport)
        ambiguousSession.restore()
        assertThrows<IOException> { ambiguousSession.disableAccount("password") }
        assertThrows<IllegalStateException> { ambiguousSession.listGames() }
        ambiguousSession.close()
    }

    @Test
    fun endTurnRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame() = runBlocking {
        val transport = FakeTransport().apply { restored = true }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.endTurnIfOpen(GAME_ID))
        session.openGame(GAME_ID)
        val outcome = session.endTurnIfOpen(GAME_ID)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(1, transport.endTurnCalls)
        assertEquals(8, transport.current.committedRevision)
        session.close()
    }

    @Test
    fun authoritativeEndTurnRetryRetainsItsCommandId() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            endTurnFailuresRemaining = 1
        }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        assertTrue(session.endTurnIfOpen(GAME_ID) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.endTurnIfOpen(GAME_ID) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.endTurnCommandIds.size)
        assertEquals(transport.endTurnCommandIds[0], transport.endTurnCommandIds[1])
        session.close()
    }

    @Test
    fun researchRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(
                projection = current.projection.copy(
                    research = current.projection.research.copy(
                        selectableTargets = listOf("Writing"),
                    ),
                ),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.setResearchPathIfOpen(GAME_ID, "Writing"))
        session.openGame(GAME_ID)
        val outcome = session.setResearchPathIfOpen(GAME_ID, "Writing")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf("Writing"), transport.researchTargets)
        assertEquals(8, transport.current.committedRevision)
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
        projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
        committedRevision = revision,
        canonicalStateHash = hash,
        projectionHash = "projection-$revision",
        projection = PlayerProjection(
            civilizationId = "Rome",
            turn = revision.toInt(),
            currentPlayerCivilizationId = "Rome",
            isCurrentTurn = true,
            pendingTurnActions = emptyList(),
            research = ProjectedResearch(null, emptyList(), emptyList(), emptyList()),
            gold = 0,
            knownCivilizations = emptyList(),
            ownCities = emptyList(),
            ownUnits = emptyList(),
            exploredTiles = emptyList(),
            visibleForeignUnits = emptyList(),
        ),
    )

    private inner class FakeTransport : ApiV3Transport {
        var capabilities = ApiV3Capabilities(
            3,
            PlayerProjection.CURRENT_PROJECTION_VERSION,
            listOf("end_turn"),
            false,
            true,
        )
        var restored = false
        var restoreCalls = 0
        @Volatile
        var projectionCalls = 0
        var endTurnCalls = 0
        var endTurnFailuresRemaining = 0
        val endTurnCommandIds = mutableListOf<String>()
        val researchTargets = mutableListOf<String>()
        var logoutCalls = 0
        val listCalls = mutableListOf<Pair<String?, Int>>()
        val passwordChanges = mutableListOf<Pair<String, String>>()
        val disableRequests = mutableListOf<String>()
        val deleteRequests = mutableListOf<String>()
        var disableFailure: Throwable? = null
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
        override suspend fun changePassword(currentPassword: String, newPassword: String) {
            passwordChanges += currentPassword to newPassword
        }
        override suspend fun disableAccount(password: String) {
            disableRequests += password
            disableFailure?.let { throw it }
        }
        override suspend fun deleteAccount(password: String) {
            deleteRequests += password
        }
        override suspend fun listGames(after: String?, limit: Int): ApiV3GamePage {
            listCalls += after to limit
            return ApiV3GamePage(
                listOf(ApiV3GameSummary(GAME_ID, 7, "hash-7", "owner", "Rome", true)),
                NEXT_GAME_ID,
            )
        }
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
        override suspend fun setResearchPath(
            gameId: String,
            request: ApiV3SetResearchPathRequest,
        ): ApiV3CommandAccepted {
            researchTargets += request.technologyName
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    research = current.projection.research.copy(
                        currentTechnology = request.technologyName,
                        queue = listOf(request.technologyName),
                    ),
                ),
            )
            return ApiV3CommandAccepted(
                gameId,
                request.commandId,
                request.expectedRevision,
                current.committedRevision,
                current.canonicalStateHash,
            )
        }
        override suspend fun endTurn(
            gameId: String,
            request: ApiV3EndTurnRequest,
        ): ApiV3CommandAccepted {
            endTurnCalls++
            endTurnCommandIds += request.commandId
            if (endTurnFailuresRemaining > 0) {
                endTurnFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    turn = current.projection.turn + 1,
                    isCurrentTurn = false,
                ),
            )
            return ApiV3CommandAccepted(
                gameId,
                request.commandId,
                request.expectedRevision,
                current.committedRevision,
                current.canonicalStateHash,
            )
        }
        override fun notifications(): Flow<ApiV3RevisionNotification> = notifications

        private fun unsupported(): Nothing = error("not used by this lifecycle test")
    }

    companion object {
        private const val GAME_ID = "00000000-0000-0000-0000-000000000001"
        private const val NEXT_GAME_ID = "00000000-0000-0000-0000-000000000002"
    }
}
