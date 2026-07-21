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

    @Test
    fun policyRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(
                projection = current.projection.copy(
                    policies = current.projection.policies.copy(
                        selectablePolicies = listOf("Tradition"),
                    ),
                ),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.adoptPolicyIfOpen(GAME_ID, "Tradition"))
        session.openGame(GAME_ID)
        val outcome = session.adoptPolicyIfOpen(GAME_ID, "Tradition")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf("Tradition"), transport.policyNames)
        assertEquals(8, transport.current.committedRevision)
        session.close()
    }

    @Test
    fun freeTechnologyRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(
                projection = current.projection.copy(
                    research = current.projection.research.copy(
                        freeTechnologyChoices = listOf("Writing"),
                    ),
                ),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.chooseFreeTechnologyIfOpen(GAME_ID, "Writing"))
        session.openGame(GAME_ID)
        val outcome = session.chooseFreeTechnologyIfOpen(GAME_ID, "Writing")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf("Writing"), transport.freeTechnologyNames)
        assertEquals(8, transport.current.committedRevision)
        session.close()
    }

    @Test
    fun constructionRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame() = runBlocking {
        val projectedCity = ProjectedCity(
            id = "city-1",
            name = "Rome",
            x = 0,
            y = 0,
            population = 1,
            health = 200,
            constructionQueue = emptyList(),
            availableConstructions = listOf("Monument"),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(
                projection = current.projection.copy(ownCities = listOf(projectedCity)),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.queueConstructionIfOpen(GAME_ID, "city-1", "Monument"))
        session.openGame(GAME_ID)
        val outcome = session.queueConstructionIfOpen(GAME_ID, "city-1", "Monument")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf("city-1" to "Monument"), transport.queuedConstructions)
        assertEquals(8, transport.current.committedRevision)
        session.close()
    }

    @Test
    fun authoritativeConstructionRetryRetainsItsCommandId() = runBlocking {
        val projectedCity = ProjectedCity(
            id = "city-1",
            name = "Rome",
            x = 0,
            y = 0,
            population = 1,
            health = 200,
            constructionQueue = emptyList(),
            availableConstructions = listOf("Monument"),
        )
        val transport = FakeTransport().apply {
            restored = true
            queueFailuresRemaining = 1
            current = current.copy(
                projection = current.projection.copy(ownCities = listOf(projectedCity)),
            )
        }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        assertTrue(session.queueConstructionIfOpen(GAME_ID, "city-1", "Monument") is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.queueConstructionIfOpen(GAME_ID, "city-1", "Monument") is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.queueCommandIds.size)
        assertEquals(transport.queueCommandIds[0], transport.queueCommandIds[1])
        session.close()
    }

    @Test
    fun authoritativeConstructionRemovalAndMovementRouteFromAnOpenedGame() = runBlocking {
        val projectedCity = ProjectedCity(
            "city-1", "Rome", 0, 0, 1, 200,
            listOf("Monument", "Warrior"), emptyList(),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownCities = listOf(projectedCity)))
        }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        assertTrue(session.moveConstructionIfOpen(
            GAME_ID, "city-1", 1, 0, "Warrior",
        ) is AuthoritativeCommandOutcome.Accepted)
        assertTrue(session.removeConstructionIfOpen(
            GAME_ID, "city-1", 0, "Warrior",
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(listOf("Monument"),
            transport.current.projection.ownCities.single().constructionQueue)
        session.close()
    }

    @Test
    fun authoritativePurchaseRetryRetainsCommandAndNeverSendsPrice() = runBlocking {
        val projectedCity = ProjectedCity(
            "city-1", "Rome", 0, 0, 1, 200,
            listOf("Monument"), listOf("Monument"),
        )
        val transport = FakeTransport().apply {
            restored = true
            purchaseFailuresRemaining = 1
            current = current.copy(projection = current.projection.copy(ownCities = listOf(projectedCity)))
        }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        assertTrue(session.purchaseConstructionIfOpen(
            GAME_ID, "city-1", "Monument", "Gold", 0,
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.purchaseConstructionIfOpen(
            GAME_ID, "city-1", "Monument", "Gold", 0,
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.purchaseCommandIds.size)
        assertEquals(transport.purchaseCommandIds[0], transport.purchaseCommandIds[1])
        assertEquals(emptyList<String>(),
            transport.current.projection.ownCities.single().constructionQueue)
        session.close()
    }

    @Test
    fun authoritativePerpetualConstructionRoutesOnlyForAnOpenedGame() = runBlocking {
        val projectedCity = ProjectedCity(
            "city-1", "Rome", 0, 0, 1, 200,
            listOf("Monument"), listOf("Nothing"),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownCities = listOf(projectedCity)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.setPerpetualConstructionIfOpen(
            GAME_ID, "city-1", "Nothing",
        ))
        session.openGame(GAME_ID)
        assertTrue(session.setPerpetualConstructionIfOpen(
            GAME_ID, "city-1", "Nothing",
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(listOf("city-1" to "Nothing"), transport.perpetualConstructions)
        assertEquals(listOf("Nothing"),
            transport.current.projection.ownCities.single().constructionQueue)
        session.close()
    }

    @Test
    fun authoritativeTilePurchaseRetryRetainsCommandId() = runBlocking {
        val projectedCity = ProjectedCity(
            "city-1", "Rome", 0, 0, 1, 200, emptyList(), emptyList(),
        )
        val transport = FakeTransport().apply {
            restored = true
            tilePurchaseFailuresRemaining = 1
            current = current.copy(projection = current.projection.copy(
                ownCities = listOf(projectedCity),
                exploredTiles = listOf(ProjectedTileVisibility(2, 0, true)),
            ))
        }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        assertTrue(session.buyCityTileIfOpen(
            GAME_ID, "city-1", 2, 0,
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.buyCityTileIfOpen(
            GAME_ID, "city-1", 2, 0,
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.tilePurchaseCommandIds.size)
        assertEquals(transport.tilePurchaseCommandIds[0], transport.tilePurchaseCommandIds[1])
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
            policies = ProjectedPolicies(0, 25, 0, emptyList(), emptyList()),
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
        val policyNames = mutableListOf<String>()
        val freeTechnologyNames = mutableListOf<String>()
        val queuedConstructions = mutableListOf<Pair<String, String>>()
        val perpetualConstructions = mutableListOf<Pair<String, String>>()
        val queueCommandIds = mutableListOf<String>()
        val removedConstructions = mutableListOf<ApiV3RemoveConstructionRequest>()
        val movedConstructions = mutableListOf<ApiV3MoveConstructionRequest>()
        val purchaseCommandIds = mutableListOf<String>()
        val tilePurchaseCommandIds = mutableListOf<String>()
        var purchaseFailuresRemaining = 0
        var tilePurchaseFailuresRemaining = 0
        var queueFailuresRemaining = 0
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
        override suspend fun setCityTileAssignment(
            gameId: String,
            request: ApiV3SetCityTileAssignmentRequest,
        ) = unsupported()
        override suspend fun setSpecialistCount(
            gameId: String,
            request: ApiV3SetSpecialistCountRequest,
        ) = unsupported()
        override suspend fun setManualSpecialists(
            gameId: String,
            request: ApiV3SetManualSpecialistsRequest,
        ) = unsupported()
        override suspend fun queueConstruction(
            gameId: String,
            request: ApiV3QueueConstructionRequest,
        ): ApiV3CommandAccepted {
            queuedConstructions += request.cityId to request.constructionName
            queueCommandIds += request.commandId
            if (queueFailuresRemaining > 0) {
                queueFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    ownCities = current.projection.ownCities.map { city ->
                        if (city.id == request.cityId)
                            city.copy(constructionQueue = city.constructionQueue + request.constructionName)
                        else city
                    },
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
        override suspend fun queueConstructionAtTile(
            gameId: String,
            request: ApiV3QueueConstructionAtTileRequest,
        ): ApiV3CommandAccepted = unsupported()
        override suspend fun setPerpetualConstruction(
            gameId: String,
            request: ApiV3SetPerpetualConstructionRequest,
        ): ApiV3CommandAccepted {
            perpetualConstructions += request.cityId to request.constructionName
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    ownCities = current.projection.ownCities.map { city ->
                        if (city.id == request.cityId)
                            city.copy(constructionQueue = city.constructionQueue.dropLast(1) + request.constructionName)
                        else city
                    },
                ),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                request.expectedRevision + 1, "hash-8",
            )
        }
        override suspend fun removeConstruction(
            gameId: String,
            request: ApiV3RemoveConstructionRequest,
        ): ApiV3CommandAccepted {
            removedConstructions += request
            val city = current.projection.ownCities.single { it.id == request.cityId }
            val queue = city.constructionQueue.toMutableList()
            check(queue[request.queueIndex] == request.expectedConstructionName)
            queue.removeAt(request.queueIndex)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
                projection = current.projection.copy(ownCities = listOf(city.copy(constructionQueue = queue))),
            )
            return ApiV3CommandAccepted(gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash)
        }
        override suspend fun moveConstruction(
            gameId: String,
            request: ApiV3MoveConstructionRequest,
        ): ApiV3CommandAccepted {
            movedConstructions += request
            val city = current.projection.ownCities.single { it.id == request.cityId }
            val queue = city.constructionQueue.toMutableList()
            check(queue[request.fromIndex] == request.expectedConstructionName)
            queue.add(request.toIndex, queue.removeAt(request.fromIndex))
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
                projection = current.projection.copy(ownCities = listOf(city.copy(constructionQueue = queue))),
            )
            return ApiV3CommandAccepted(gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash)
        }
        override suspend fun purchaseConstruction(
            gameId: String,
            request: ApiV3PurchaseConstructionRequest,
        ): ApiV3CommandAccepted {
            purchaseCommandIds += request.commandId
            if (purchaseFailuresRemaining > 0) {
                purchaseFailuresRemaining--
                throw IOException("lost response")
            }
            val city = current.projection.ownCities.single { it.id == request.cityId }
            val queue = city.constructionQueue.toMutableList()
            request.queueIndex?.let {
                check(queue[it] == request.constructionName)
                queue.removeAt(it)
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
                projection = current.projection.copy(
                    gold = current.projection.gold - 10,
                    ownCities = listOf(city.copy(constructionQueue = queue)),
                ),
            )
            return ApiV3CommandAccepted(gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash)
        }
        override suspend fun purchaseConstructionAtTile(
            gameId: String,
            request: ApiV3PurchaseConstructionAtTileRequest,
        ): ApiV3CommandAccepted = unsupported()
        override suspend fun buyCityTile(
            gameId: String,
            request: ApiV3BuyCityTileRequest,
        ): ApiV3CommandAccepted {
            tilePurchaseCommandIds += request.commandId
            if (tilePurchaseFailuresRemaining > 0) {
                tilePurchaseFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                request.expectedRevision + 1, "hash-8",
            )
        }
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
        override suspend fun adoptPolicy(
            gameId: String,
            request: ApiV3AdoptPolicyRequest,
        ): ApiV3CommandAccepted {
            policyNames += request.policyName
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    policies = current.projection.policies.copy(
                        adoptedPolicies = listOf(request.policyName),
                        selectablePolicies = emptyList(),
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
        override suspend fun chooseFreeTechnology(
            gameId: String,
            request: ApiV3ChooseFreeTechnologyRequest,
        ): ApiV3CommandAccepted {
            freeTechnologyNames += request.technologyName
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    research = current.projection.research.copy(
                        freeTechnologyChoices = emptyList(),
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
