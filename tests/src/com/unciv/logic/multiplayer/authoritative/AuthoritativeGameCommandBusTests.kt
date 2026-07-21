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
    fun cityTileAssignmentRequestUsesClosedSnakeCaseState() {
        val encoded = Json.encodeToString(
            ApiV3SetCityTileAssignmentRequest.serializer(),
            ApiV3SetCityTileAssignmentRequest(
                "command", 7, "hash", "city-1", 2, -1, CityTileAssignment.Locked,
            ),
        )
        assertTrue(encoded.contains("\"assignment\":\"locked\""))
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("population"))
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
    fun tileConstructionSendsCoordinatesWithoutClientPlacementClaims() = runBlocking {
        val tile = ProjectedTileVisibility(2, 0, true)
        val initial = projection(
            0, "hash-0", cityQueue = emptyList(),
            availableConstructions = listOf("District"), exploredTiles = listOf(tile),
        )
        val committed = projection(
            1, "hash-1", cityQueue = listOf("District"),
            availableConstructions = listOf("District"), exploredTiles = listOf(tile),
        )
        val transport = FakeTransport(initial).apply {
            onQueueConstructionAtTile = { request ->
                current = committed
                accepted(request.commandId, 0, 1, "hash-1")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "tile-command" }
        bus.refresh()

        val outcome = bus.queueConstructionAtTile("city-1", "District", 2, 0)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(
            ApiV3QueueConstructionAtTileRequest(
                "tile-command", 0, "hash-0", "city-1", "District", 2, 0,
            ),
            transport.tileQueueRequests.single(),
        )
        val encoded = Json.encodeToString(
            ApiV3QueueConstructionAtTileRequest.serializer(),
            transport.tileQueueRequests.single(),
        )
        assertTrue(!encoded.contains("legal"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun tilePurchaseUsesProjectedQueueAndCoordinateWithoutPrice() = runBlocking {
        val tile = ProjectedTileVisibility(2, 0, true)
        val initial = projection(
            0, "hash-0", cityQueue = listOf("District"),
            availableConstructions = listOf("District"), exploredTiles = listOf(tile),
        )
        val committed = projection(
            1, "hash-1", cityQueue = emptyList(),
            availableConstructions = listOf("District"), exploredTiles = listOf(tile),
        )
        val transport = FakeTransport(initial).apply {
            onPurchaseConstructionAtTile = { request ->
                current = committed
                accepted(request.commandId, 0, 1, "hash-1")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "tile-purchase" }
        bus.refresh()

        val outcome = bus.purchaseConstructionAtTile("city-1", "District", "Gold", 2, 0, 0)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        val request = transport.tilePurchaseConstructionRequests.single()
        assertEquals("District", request.constructionName)
        assertEquals(0, request.queueIndex)
        assertEquals(2, request.x)
        val encoded = Json.encodeToString(ApiV3PurchaseConstructionAtTileRequest.serializer(), request)
        assertTrue(!encoded.contains("price"))
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("legal"))
    }

    @Test
    fun perpetualConstructionHasItsOwnClosedIntent() = runBlocking {
        val initial = projection(
            0, "hash-0", cityQueue = listOf("Monument"),
            availableConstructions = listOf("Nothing"),
        )
        val committed = projection(
            1, "hash-1", cityQueue = listOf("Nothing"),
            availableConstructions = listOf("Nothing"),
        )
        val transport = FakeTransport(initial).apply {
            onSetPerpetualConstruction = { request ->
                current = committed
                accepted(request.commandId, 0, 1, "hash-1")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "perpetual-command" }
        bus.refresh()

        assertTrue(bus.setPerpetualConstruction(
            "city-1", "Nothing",
        ) is AuthoritativeCommandOutcome.Accepted)

        val request = transport.perpetualRequests.single()
        assertEquals(ApiV3SetPerpetualConstructionRequest(
            "perpetual-command", 0, "hash-0", "city-1", "Nothing",
        ), request)
        val encoded = Json.encodeToString(ApiV3SetPerpetualConstructionRequest.serializer(), request)
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("queue"))
    }

    @Test
    fun queueRemovalAndMovementBindToTheProjectedEntry() = runBlocking {
        val initial = projection(4, "hash-4", cityQueue = listOf("Monument", "Warrior"))
        val afterMove = projection(5, "hash-5", cityQueue = listOf("Warrior", "Monument"))
        val afterRemove = projection(6, "hash-6", cityQueue = listOf("Monument"))
        val transport = FakeTransport(initial).apply {
            onMoveConstruction = { request ->
                current = afterMove
                accepted(request.commandId, 4, 5, "hash-5")
            }
            onRemoveConstruction = { request ->
                current = afterRemove
                accepted(request.commandId, 5, 6, "hash-6")
            }
        }
        val ids = ArrayDeque(listOf("move-command", "remove-command"))
        val bus = AuthoritativeGameCommandBus(gameId, transport) { ids.removeFirst() }
        bus.refresh()

        assertTrue(bus.moveConstruction("city-1", 1, 0, "Warrior") is AuthoritativeCommandOutcome.Accepted)
        assertTrue(bus.removeConstruction("city-1", 0, "Warrior") is AuthoritativeCommandOutcome.Accepted)

        assertEquals(ApiV3MoveConstructionRequest("move-command", 4, "hash-4", "city-1", 1, 0, "Warrior"),
            transport.moveConstructionRequests.single())
        assertEquals(ApiV3RemoveConstructionRequest("remove-command", 5, "hash-5", "city-1", 0, "Warrior"),
            transport.removeConstructionRequests.single())
    }

    @Test
    fun purchaseIntentContainsNoClientPriceOrActor() = runBlocking {
        val initial = projection(2, "hash-2", cityQueue = listOf("Monument"))
        val committed = projection(3, "hash-3", cityQueue = emptyList())
        val transport = FakeTransport(initial).apply {
            onPurchaseConstruction = { request ->
                current = committed
                accepted(request.commandId, 2, 3, "hash-3")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "purchase-command" }
        bus.refresh()

        assertTrue(bus.purchaseConstruction(
            "city-1", "Monument", "Gold", 0,
        ) is AuthoritativeCommandOutcome.Accepted)

        val request = transport.purchaseConstructionRequests.single()
        assertEquals(ApiV3PurchaseConstructionRequest(
            "purchase-command", 2, "hash-2", "city-1", "Monument", "Gold", 0,
        ), request)
        val encoded = Json.encodeToString(ApiV3PurchaseConstructionRequest.serializer(), request)
        assertTrue(!encoded.contains("cost"))
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("civilization"))
    }

    @Test
    fun tilePurchaseSendsCoordinatesWithoutClientPriceOrActor() = runBlocking {
        val initial = projection(
            3, "hash-3", cityQueue = emptyList(),
            exploredTiles = listOf(ProjectedTileVisibility(2, 0, true)),
        )
        val committed = projection(
            4, "hash-4", cityQueue = emptyList(),
            exploredTiles = listOf(ProjectedTileVisibility(2, 0, true)),
        )
        val transport = FakeTransport(initial).apply {
            onBuyCityTile = { request ->
                current = committed
                accepted(request.commandId, 3, 4, "hash-4")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "tile-command" }
        bus.refresh()

        assertTrue(bus.buyCityTile("city-1", 2, 0) is AuthoritativeCommandOutcome.Accepted)

        val request = transport.buyCityTileRequests.single()
        assertEquals(ApiV3BuyCityTileRequest(
            "tile-command", 3, "hash-3", "city-1", 2, 0,
        ), request)
        val encoded = Json.encodeToString(ApiV3BuyCityTileRequest.serializer(), request)
        assertTrue(!encoded.contains("price"))
        assertTrue(!encoded.contains("cost"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun queueMutationRejectsAnEntryThatDoesNotMatchTheProjection() = runBlocking {
        val bus = AuthoritativeGameCommandBus(
            gameId,
            FakeTransport(projection(4, "hash-4", cityQueue = listOf("Monument"))),
        )
        bus.refresh()
        bus.removeConstruction("city-1", 0, "Warrior")
        Unit
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
    fun policyAdoptionUsesOnlyAProjectedPolicyName() = runBlocking {
        val initial = projection(0, "hash-0")
        val committed = projection(1, "hash-1")
        val transport = FakeTransport(initial).apply {
            onAdoptPolicy = { request ->
                current = committed
                accepted(request.commandId, 0, 1, "hash-1")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "policy-command" }
        bus.refresh()

        val outcome = bus.adoptPolicy("Tradition")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals("Tradition", transport.policyRequests.single().policyName)
    }

    @Test
    fun freeTechnologyUsesOnlyAProjectedGrantChoice() = runBlocking {
        val initial = projection(0, "hash-0", freeTechnologyChoices = listOf("Writing"))
        val committed = projection(1, "hash-1")
        val transport = FakeTransport(initial).apply {
            onChooseFreeTechnology = { request ->
                current = committed
                accepted(request.commandId, 0, 1, "hash-1")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "free-tech-command" }
        bus.refresh()

        val outcome = bus.chooseFreeTechnology("Writing")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals("Writing", transport.freeTechnologyRequests.single().technologyName)
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

    private fun projection(
        revision: Long,
        hash: String,
        cityQueue: List<String>? = null,
        freeTechnologyChoices: List<String> = emptyList(),
        availableConstructions: List<String> = listOf("Monument"),
        exploredTiles: List<ProjectedTileVisibility> = emptyList(),
    ) = ApiV3GameProjection(
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
            research = ProjectedResearch(null, emptyList(), listOf("Writing"), freeTechnologyChoices),
            policies = ProjectedPolicies(25, 25, 0, emptyList(), listOf("Tradition")),
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
                availableConstructions = availableConstructions,
            )),
            ownUnits = emptyList(),
            exploredTiles = exploredTiles,
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
        val tileQueueRequests = mutableListOf<ApiV3QueueConstructionAtTileRequest>()
        val perpetualRequests = mutableListOf<ApiV3SetPerpetualConstructionRequest>()
        val removeConstructionRequests = mutableListOf<ApiV3RemoveConstructionRequest>()
        val moveConstructionRequests = mutableListOf<ApiV3MoveConstructionRequest>()
        val purchaseConstructionRequests = mutableListOf<ApiV3PurchaseConstructionRequest>()
        val tilePurchaseConstructionRequests = mutableListOf<ApiV3PurchaseConstructionAtTileRequest>()
        val buyCityTileRequests = mutableListOf<ApiV3BuyCityTileRequest>()
        val researchRequests = mutableListOf<ApiV3SetResearchPathRequest>()
        val policyRequests = mutableListOf<ApiV3AdoptPolicyRequest>()
        val freeTechnologyRequests = mutableListOf<ApiV3ChooseFreeTechnologyRequest>()
        var onMove: suspend (ApiV3MoveUnitRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onQueueConstruction: suspend (ApiV3QueueConstructionRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onQueueConstructionAtTile: suspend (ApiV3QueueConstructionAtTileRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetPerpetualConstruction: suspend (ApiV3SetPerpetualConstructionRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onRemoveConstruction: suspend (ApiV3RemoveConstructionRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onMoveConstruction: suspend (ApiV3MoveConstructionRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onPurchaseConstruction: suspend (ApiV3PurchaseConstructionRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onPurchaseConstructionAtTile: suspend (ApiV3PurchaseConstructionAtTileRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onBuyCityTile: suspend (ApiV3BuyCityTileRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetResearchPath: suspend (ApiV3SetResearchPathRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onAdoptPolicy: suspend (ApiV3AdoptPolicyRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onChooseFreeTechnology: suspend (ApiV3ChooseFreeTechnologyRequest) -> ApiV3CommandAccepted = {
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
        override suspend fun queueConstructionAtTile(
            gameId: String,
            request: ApiV3QueueConstructionAtTileRequest,
        ): ApiV3CommandAccepted {
            tileQueueRequests += request
            return onQueueConstructionAtTile(request)
        }
        override suspend fun setPerpetualConstruction(
            gameId: String,
            request: ApiV3SetPerpetualConstructionRequest,
        ): ApiV3CommandAccepted {
            perpetualRequests += request
            return onSetPerpetualConstruction(request)
        }
        override suspend fun removeConstruction(
            gameId: String,
            request: ApiV3RemoveConstructionRequest,
        ): ApiV3CommandAccepted {
            removeConstructionRequests += request
            return onRemoveConstruction(request)
        }
        override suspend fun moveConstruction(
            gameId: String,
            request: ApiV3MoveConstructionRequest,
        ): ApiV3CommandAccepted {
            moveConstructionRequests += request
            return onMoveConstruction(request)
        }
        override suspend fun purchaseConstruction(
            gameId: String,
            request: ApiV3PurchaseConstructionRequest,
        ): ApiV3CommandAccepted {
            purchaseConstructionRequests += request
            return onPurchaseConstruction(request)
        }
        override suspend fun purchaseConstructionAtTile(
            gameId: String,
            request: ApiV3PurchaseConstructionAtTileRequest,
        ): ApiV3CommandAccepted {
            tilePurchaseConstructionRequests += request
            return onPurchaseConstructionAtTile(request)
        }
        override suspend fun buyCityTile(
            gameId: String,
            request: ApiV3BuyCityTileRequest,
        ): ApiV3CommandAccepted {
            buyCityTileRequests += request
            return onBuyCityTile(request)
        }
        override suspend fun setResearchPath(
            gameId: String,
            request: ApiV3SetResearchPathRequest,
        ): ApiV3CommandAccepted {
            researchRequests += request
            return onSetResearchPath(request)
        }
        override suspend fun adoptPolicy(
            gameId: String,
            request: ApiV3AdoptPolicyRequest,
        ): ApiV3CommandAccepted {
            policyRequests += request
            return onAdoptPolicy(request)
        }
        override suspend fun chooseFreeTechnology(
            gameId: String,
            request: ApiV3ChooseFreeTechnologyRequest,
        ): ApiV3CommandAccepted {
            freeTechnologyRequests += request
            return onChooseFreeTechnology(request)
        }
        override suspend fun endTurn(gameId: String, request: ApiV3EndTurnRequest) =
            accepted(request.commandId, request.expectedRevision, request.expectedRevision + 1, "unused")
        override fun notifications(): Flow<ApiV3RevisionNotification> = emptyFlow()
    }
}
