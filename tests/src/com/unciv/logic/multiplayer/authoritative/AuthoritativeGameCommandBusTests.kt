package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AuthoritativeGameCommandBusTests {
    private val gameId = "00000000-0000-0000-0000-000000000001"

    @Test
    fun moveRequestWireShapeContainsNoActorOrStatePayload() {
        val encoded = Json.encodeToString(
            ApiV3MoveUnitRequest.serializer(),
            ApiV3MoveUnitRequest("command", 7, "hash", 42, -3, 8),
        )

        assertTrue(encoded.contains("\"command_id\":\"command\""))
        assertTrue(encoded.contains("\"expected_revision\":7"))
        assertTrue(encoded.contains("\"unit_id\":42"))
        assertTrue(encoded.contains("\"destination_x\":-3"))
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("civilization"))
        assertTrue(!encoded.contains("GameInfo"))
    }

    @Test
    fun queueConstructionUsesOnlyProjectedCityAndConstructionIds() = runBlocking {
        val initial = projection(0, "hash-0", cityQueue = emptyList())
        val committed = projection(1, "hash-1", cityQueue = listOf("Monument"))
        val transport = FakeTransport(initial).apply {
            onQueueConstruction = { request ->
                current = committed
                accepted(request.commandId, 0, 1, "hash-1")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "queue-command" }
        bus.refresh()

        val outcome = bus.queueConstruction("city-1", "Monument")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals("city-1", transport.queueRequests.single().cityId)
        assertEquals("Monument", transport.queueRequests.single().constructionName)
        assertEquals(listOf("Monument"),
            (bus.state as AuthoritativeSyncState.Synchronized).current.projection.ownCities.single().constructionQueue)
    }

    @Test
    fun researchPathUsesOnlyAProjectedDestination() = runBlocking {
        val initial = projection(0, "hash-0")
        val committed = projection(1, "hash-1")
        val transport = FakeTransport(initial).apply {
            onSetResearchPath = { request ->
                current = committed
                accepted(request.commandId, 0, 1, "hash-1")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "research-command" }
        bus.refresh()

        val outcome = bus.setResearchPath("Writing")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals("Writing", transport.researchRequests.single().technologyName)
    }

    @Test
    fun freshClientReconstructsFromServerProjection() = runBlocking {
        val transport = FakeTransport(projection(4, "hash-4"))
        val bus = AuthoritativeGameCommandBus(gameId, transport)

        val reconstructed = bus.refresh()

        assertEquals(4, reconstructed.committedRevision)
        assertEquals(reconstructed, (bus.state as AuthoritativeSyncState.Synchronized).current)
    }

    @Test
    fun staleCommandRefreshesWithoutMergingOrReplaying() = runBlocking {
        val old = projection(3, "hash-3")
        val canonical = projection(4, "hash-4")
        val transport = FakeTransport(old).apply {
            onMove = {
                current = canonical
                throw ApiV3Exception(409, ApiV3ErrorResponse("stale_revision", 4))
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "stale-command" }
        bus.refresh()

        val outcome = bus.moveUnit(1, 2, 3)

        assertEquals(canonical, (outcome as AuthoritativeCommandOutcome.StaleRefreshed).current)
        assertEquals(1, transport.moveRequests.size)
        assertEquals(canonical, (bus.state as AuthoritativeSyncState.Synchronized).current)
    }

    @Test
    fun lostResponseRetriesTheExactIdempotencyKey() = runBlocking {
        val initial = projection(0, "hash-0")
        val committed = projection(1, "hash-1")
        val transport = FakeTransport(initial)
        var first = true
        transport.onMove = { request ->
            if (first) {
                first = false
                transport.current = committed
                throw IOException("response lost after commit")
            }
            accepted(request.commandId, 0, 1, "hash-1")
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "stable-command-id" }
        bus.refresh()

        assertTrue(bus.moveUnit(1, 2, 3) is AuthoritativeCommandOutcome.RetryRequired)
        val outcome = bus.retryPending()

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf("stable-command-id", "stable-command-id"),
            transport.moveRequests.map { it.commandId })
        assertEquals(committed, (bus.state as AuthoritativeSyncState.Synchronized).current)
    }

    @Test
    fun rejectedCommandLeavesCachedProjectionUntouched() = runBlocking {
        val initial = projection(2, "hash-2")
        val transport = FakeTransport(initial).apply {
            onMove = { throw ApiV3Exception(422, ApiV3ErrorResponse("invalid_command")) }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "illegal-command" }
        val cached = bus.refresh()

        val outcome = bus.moveUnit(99, 2, 3)

        assertEquals("invalid_command", (outcome as AuthoritativeCommandOutcome.Rejected).code)
        assertSame(cached, (bus.state as AuthoritativeSyncState.Rejected).current)
    }

    @Test
    fun duplicateLostAndReorderedNotificationsConvergeThroughHttp() = runBlocking {
        val revision5 = projection(5, "hash-5")
        val revision6 = projection(6, "hash-6")
        val transport = FakeTransport(revision5)
        val bus = AuthoritativeGameCommandBus(gameId, transport)
        bus.refresh()

        assertEquals(null, bus.reconcile(notification(5, "hash-5")))
        assertEquals(null, bus.reconcile(notification(4, "hash-4")))
        transport.current = revision6
        assertEquals(revision6, bus.reconcile(notification(6, "hash-6")))
        // A delayed duplicate of revision 5 cannot roll the projection back.
        assertEquals(null, bus.reconcile(notification(5, "hash-5")))
        assertEquals(revision6, (bus.state as AuthoritativeSyncState.Synchronized).current)
    }

    private fun projection(revision: Long, hash: String, cityQueue: List<String>? = null) = ApiV3GameProjection(
        gameId = gameId,
        projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
        committedRevision = revision,
        canonicalStateHash = hash,
        projectionHash = "projection-$revision",
        projection = PlayerProjection(
            civilizationId = "Rome",
            turn = 0,
            currentPlayerCivilizationId = "Rome",
            isCurrentTurn = true,
            pendingTurnActions = emptyList(),
            research = ProjectedResearch(null, emptyList(), listOf("Writing"), emptyList()),
            gold = 0,
            knownCivilizations = emptyList(),
            ownCities = if (cityQueue == null) emptyList() else listOf(ProjectedCity(
                id = "city-1",
                name = "Rome",
                x = 0,
                y = 0,
                population = 1,
                health = 200,
                constructionQueue = cityQueue,
                availableConstructions = listOf("Monument"),
            )),
            ownUnits = emptyList(),
            exploredTiles = emptyList(),
            visibleForeignUnits = emptyList(),
        ),
    )

    private fun accepted(commandId: String, previous: Long, committed: Long, hash: String) =
        ApiV3CommandAccepted(gameId, commandId, previous, committed, hash)

    private fun notification(revision: Long, hash: String) = ApiV3RevisionNotification(
        type = "revision_committed",
        protocolVersion = 3,
        gameId = gameId,
        committedRevision = revision,
        canonicalStateHash = hash,
    )

    private inner class FakeTransport(var current: ApiV3GameProjection) : ApiV3Transport {
        val moveRequests = mutableListOf<ApiV3MoveUnitRequest>()
        val queueRequests = mutableListOf<ApiV3QueueConstructionRequest>()
        val researchRequests = mutableListOf<ApiV3SetResearchPathRequest>()
        var onMove: suspend (ApiV3MoveUnitRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onQueueConstruction: suspend (ApiV3QueueConstructionRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetResearchPath: suspend (ApiV3SetResearchPathRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }

        override suspend fun restoreSession() = true
        override suspend fun capabilities() = ApiV3Capabilities(
            3,
            PlayerProjection.CURRENT_PROJECTION_VERSION,
            emptyList(),
            false,
            false,
        )
        override suspend fun register(username: String, password: String) = ApiV3Account("account", username)
        override suspend fun login(username: String, password: String) = ApiV3Account("account", username)
        override suspend fun refreshSession() = Unit
        override suspend fun logout() = Unit
        override suspend fun changePassword(currentPassword: String, newPassword: String) = Unit
        override suspend fun disableAccount(password: String) = Unit
        override suspend fun deleteAccount(password: String) = Unit
        override suspend fun listGames(after: String?, limit: Int) = ApiV3GamePage(emptyList())
        override suspend fun createGame(rulesetManifestHash: String) =
            ApiV3GameMetadata(gameId, 0, "hash-0", "owner", "Rome")
        override suspend fun joinGame(gameId: String, request: ApiV3JoinGameRequest) =
            accepted(request.commandId, request.expectedRevision, request.expectedRevision + 1, "unused")
        override suspend fun projection(gameId: String) = current
        override suspend fun moveUnit(gameId: String, request: ApiV3MoveUnitRequest): ApiV3CommandAccepted {
            moveRequests += request
            return onMove(request)
        }
        override suspend fun queueConstruction(
            gameId: String,
            request: ApiV3QueueConstructionRequest,
        ): ApiV3CommandAccepted {
            queueRequests += request
            return onQueueConstruction(request)
        }
        override suspend fun setResearchPath(
            gameId: String,
            request: ApiV3SetResearchPathRequest,
        ): ApiV3CommandAccepted {
            researchRequests += request
            return onSetResearchPath(request)
        }
        override suspend fun endTurn(gameId: String, request: ApiV3EndTurnRequest) =
            accepted(request.commandId, request.expectedRevision, request.expectedRevision + 1, "unused")
        override fun notifications(): Flow<ApiV3RevisionNotification> = emptyFlow()
    }
}
