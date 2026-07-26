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

        assertEquals(null, session.cachedProjectionIfOpen(GAME_ID))
        val bus = session.openGame(GAME_ID)

        assertEquals(1, transport.projectionCalls)
        assertEquals(7, (bus.state as AuthoritativeSyncState.Synchronized).current.committedRevision)
        assertEquals(7, session.cachedProjectionIfOpen(GAME_ID)?.turn)
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
    fun rulesetManifestResolutionPagesAndRequiresOneExactInstalledBundle() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            manifestPages[null] = ApiV3RulesetManifestPage(
                listOf(manifest("a", "Civ V - Vanilla", emptyList())),
                "b".repeat(64),
            )
            manifestPages["b".repeat(64)] = ApiV3RulesetManifestPage(
                listOf(manifest("c", "Civ V - Gods & Kings", listOf("Server Mod"))),
            )
        }
        val session = session(transport)
        assertThrows<IllegalStateException> {
            session.resolveRulesetManifest("Civ V - Gods & Kings", setOf("Server Mod"))
        }
        session.restore()

        val resolved = session.resolveRulesetManifest(
            "Civ V - Gods & Kings",
            setOf("Server Mod"),
        )

        assertEquals("c".repeat(64), resolved.manifestHash)
        assertEquals(listOf(null, "b".repeat(64)), transport.manifestListCalls)
        transport.manifestPages[null] = ApiV3RulesetManifestPage(
            listOf(
                manifest("d", "Civ V - Gods & Kings", listOf("Server Mod")),
                manifest("e", "Civ V - Gods & Kings", listOf("Server Mod")),
            ),
        )
        assertThrows<IllegalStateException> {
            session.resolveRulesetManifest("Civ V - Gods & Kings", setOf("Server Mod"))
        }
        session.close()
    }

    @Test
    fun authoritativeCreationResolvesContentSubmitsBoundedSetupAndOpensRevisionZero() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            current = projection(0, "hash-0")
            manifestPages[null] = ApiV3RulesetManifestPage(
                listOf(manifest("a", "Civ V - Vanilla", emptyList())),
            )
        }
        val session = session(transport)
        val setup = gameSetup()
        assertThrows<IllegalStateException> {
            session.createAuthoritativeGame("Civ V - Vanilla", emptySet(), setup)
        }
        session.restore()

        val created = session.createAuthoritativeGame(
            "Civ V - Vanilla",
            emptySet(),
            setup,
        )

        assertEquals(listOf("a".repeat(64) to setup), transport.createdGames)
        assertEquals(0, created.metadata.committedRevision)
        assertTrue(session.isGameOpen(created.metadata.gameId))
        assertEquals(
            0,
            (created.commandBus.state as AuthoritativeSyncState.Synchronized)
                .current.committedRevision,
        )
        assertEquals(1, transport.projectionCalls)
        session.close()
    }

    @Test
    fun playerInvitationsPreserveServerRevisionAndCallerStableIds() = runBlocking {
        val transport = FakeTransport().apply { restored = true }
        val session = session(transport)
        session.restore()
        val invitation = ApiV3PlayerInvitation(
            GAME_ID,
            "invitation-id",
            "owner",
            7,
            "hash-7",
        )
        transport.playerInvitations += invitation

        session.invitePlayer(GAME_ID, "Invited_Player", "invitation-operation")
        val discovered = session.listPlayerInvitations().single()
        val accepted = session.acceptPlayerInvitation(discovered, "join-command")

        assertEquals(
            listOf(Triple(GAME_ID, "invitation-operation", "Invited_Player")),
            transport.playerInvitationRequests,
        )
        assertEquals(listOf(Triple(GAME_ID, "join-command", 7L)), transport.joinRequests)
        assertEquals("hash-7", transport.joinObservedHashes.single())
        assertEquals(8, accepted.committedRevision)
        session.close()
    }

    @Test
    fun spectatorLifecycleUsesOnlyThePublicProjectionEndpoint() = runBlocking {
        val transport = FakeTransport().apply { restored = true }
        val session = session(transport)
        assertTrue(session.restore())

        session.addSpectator(GAME_ID, "Spectator_Name")
        val projection = session.spectatorProjection(GAME_ID)
        session.leaveSpectator(GAME_ID)

        assertEquals(listOf(GAME_ID to "Spectator_Name"), transport.addedSpectators)
        assertEquals(listOf(GAME_ID), transport.spectatorProjectionCalls)
        assertEquals(listOf(GAME_ID), transport.leftSpectatorGames)
        assertEquals(0, transport.projectionCalls)
        assertEquals(7, projection.committedRevision)
        session.close()
    }

    @Test
    fun administrationUsesCallerStableOperationIdsAndArchiveClosesLocalState() = runBlocking {
        val transport = FakeTransport().apply { restored = true }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        session.transferOwnership(GAME_ID, "Successor", "transfer-operation")
        session.closeAuthoritativeGame(GAME_ID, "close-operation")
        session.archiveAuthoritativeGame(GAME_ID, "archive-operation")

        assertEquals(
            listOf(Triple(GAME_ID, "transfer-operation", "Successor")),
            transport.ownershipTransfers,
        )
        assertEquals(listOf(GAME_ID to "close-operation"), transport.closedGames)
        assertEquals(listOf(GAME_ID to "archive-operation"), transport.archivedGames)
        assertFalse(session.isGameOpen(GAME_ID))
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
    fun unitMovementRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame() = runBlocking {
        val unit = ProjectedUnit(
            42, "Rome", "Warrior", 0, 0, 100, 2f,
            moveDestinations = listOf(ProjectedMovementDestination(1, 0)),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(
                ownUnits = listOf(unit),
                exploredTiles = listOf(ProjectedTileVisibility(1, 0, visible = true)),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.moveUnitIfOpen(GAME_ID, 42, 1, 0))
        session.openGame(GAME_ID)
        val outcome = session.moveUnitIfOpen(GAME_ID, 42, 1, 0)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple(42, 1, 0)), transport.unitMoves)
        assertEquals(8, transport.current.committedRevision)
        session.close()
    }

    @Test
    fun escortedExactMovementPreservesThePairAcrossSessionSubmission() = runBlocking {
        val destination = ProjectedMovementDestination(1, 0)
        val units = listOf(
            ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f,
                moveDestinations = listOf(destination)),
            ProjectedUnit(43, "Rome", "Settler", 0, 0, 100, 2f,
                moveDestinations = listOf(destination)),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(
                ownUnits = units,
                exploredTiles = listOf(ProjectedTileVisibility(1, 0, visible = true)),
            ))
        }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        val outcome = session.moveUnitIfOpen(GAME_ID, 42, 1, 0, escortUnitId = 43)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(43, transport.moveRequests.single().escortUnitId)
        assertTrue(transport.current.projection.ownUnits.all { it.x == 1 && it.y == 0 })
        session.close()
    }

    @Test
    fun multiTurnMovementOrderRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame() = runBlocking {
        val units = listOf(
            ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f),
            ProjectedUnit(43, "Rome", "Settler", 0, 0, 100, 2f),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(
                ownUnits = units,
                exploredTiles = listOf(ProjectedTileVisibility(7, 2, visible = false)),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.moveUnitTowardIfOpen(GAME_ID, 42, 7, 2, escortUnitId = 43))
        session.openGame(GAME_ID)
        val outcome = session.moveUnitTowardIfOpen(GAME_ID, 42, 7, 2, escortUnitId = 43)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple(42, 7, 2)), transport.movementOrders)
        assertEquals(43, transport.moveTowardRequests.single().escortUnitId)
        session.close()
    }

    @Test
    fun movementOrderCancellationRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val ordered = ProjectedUnit(
            42, "Rome", "Warrior", 1, 0, 100, 0f,
            movementDestinationX = 7, movementDestinationY = 2,
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(ordered)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.cancelUnitMovementOrderIfOpen(GAME_ID, 42))
        session.openGame(GAME_ID)
        val outcome = session.cancelUnitMovementOrderIfOpen(GAME_ID, 42)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42), transport.cancelledMovementOrders)
        session.close()
    }

    @Test
    fun unitExplorationRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Scout", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(unit)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.setUnitExplorationIfOpen(GAME_ID, 42, true))
        session.openGame(GAME_ID)
        val outcome = session.setUnitExplorationIfOpen(GAME_ID, 42, true)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42 to true), transport.explorationOrders)
        session.close()
    }

    @Test
    fun unitAutomationRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(unit)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.setUnitAutomationIfOpen(GAME_ID, 42, true))
        session.openGame(GAME_ID)
        val outcome = session.setUnitAutomationIfOpen(GAME_ID, 42, true)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42 to true), transport.automationOrders)
        session.close()
    }

    @Test
    fun unitPostureRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(unit)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.setUnitPostureIfOpen(GAME_ID, 42, UnitPosture.Fortify))
        session.openGame(GAME_ID)
        val outcome = session.setUnitPostureIfOpen(GAME_ID, 42, UnitPosture.Fortify)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42 to UnitPosture.Fortify), transport.postureOrders)
        session.close()
    }

    @Test
    fun unitDisbandRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(unit)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.disbandUnitIfOpen(GAME_ID, 42))
        session.openGame(GAME_ID)
        val outcome = session.disbandUnitIfOpen(GAME_ID, 42)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42), transport.disbandedUnits)
        session.close()
    }

    @Test
    fun tilePillageRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(unit)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.pillageTileIfOpen(GAME_ID, 42))
        session.openGame(GAME_ID)
        val outcome = session.pillageTileIfOpen(GAME_ID, 42)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42), transport.pillagedByUnits)
        session.close()
    }

    @Test
    fun cityFoundingRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val settler = ProjectedUnit(42, "Rome", "Settler", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(settler)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.foundCityIfOpen(GAME_ID, 42))
        session.openGame(GAME_ID)
        val outcome = session.foundCityIfOpen(GAME_ID, 42)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42), transport.foundingUnits)
        session.close()
    }

    @Test
    fun unitAttackRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val attacker = ProjectedUnit(
            42, "Rome", "Warrior", 0, 0, 100, 2f,
            attackTargets = listOf(ProjectedAttackTarget(1, 0, 0, 0, testCombatPreview())),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(
                ownUnits = listOf(attacker),
                exploredTiles = listOf(ProjectedTileVisibility(1, 0, true)),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.attackWithUnitIfOpen(GAME_ID, 42, 1, 0))
        session.openGame(GAME_ID)
        val outcome = session.attackWithUnitIfOpen(GAME_ID, 42, 1, 0)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple(42, 1, 0)), transport.unitAttacks)
        session.close()
    }

    @Test
    fun cityBombardRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val city = ProjectedCity(
            "city-1", "Rome", 0, 0, 5, 200, emptyList(), emptyList(),
            bombardTargets = listOf(ProjectedBombardTarget(2, 0, testCombatPreview())),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(
                ownCities = listOf(city),
                exploredTiles = listOf(ProjectedTileVisibility(2, 0, true)),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.bombardWithCityIfOpen(GAME_ID, "city-1", 2, 0))
        session.openGame(GAME_ID)
        val outcome = session.bombardWithCityIfOpen(GAME_ID, "city-1", 2, 0)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple("city-1", 2, 0)), transport.cityBombardments)
        session.close()
    }

    @Test
    fun nuclearStrikeRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val nuke = ProjectedUnit(
            42, "Rome", "Nuclear Missile", 0, 0, 100, 2f,
            nuclearTargetCandidates = listOf(testNuclearTarget(4, -1)),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(
                ownUnits = listOf(nuke),
                exploredTiles = listOf(ProjectedTileVisibility(4, -1, false)),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.launchNuclearStrikeIfOpen(GAME_ID, 42, 4, -1))
        session.openGame(GAME_ID)
        val outcome = session.launchNuclearStrikeIfOpen(GAME_ID, 42, 4, -1)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple(42, 4, -1)), transport.nuclearStrikes)
        session.close()
    }

    @Test
    fun airSweepRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val fighter = ProjectedUnit(
            42, "Rome", "Fighter", 0, 0, 100, 1f,
            airSweepTargets = listOf(testAirSweepTarget(4, -1)),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(fighter)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.airSweepIfOpen(GAME_ID, 42, 4, -1))
        session.openGame(GAME_ID)
        val outcome = session.airSweepIfOpen(GAME_ID, 42, 4, -1)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple(42, 4, -1)), transport.airSweeps)
        session.close()
    }

    @Test
    fun unitUpgradeBatchRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val units = listOf(
            ProjectedUnit(42, "Rome", "Archer", 1, 0, 100, 2f),
            ProjectedUnit(43, "Rome", "Archer", 2, 0, 100, 2f),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = units))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.upgradeUnitsIfOpen(GAME_ID, listOf(42, 43), "Crossbowman"))
        session.openGame(GAME_ID)
        val outcome = session.upgradeUnitsIfOpen(GAME_ID, listOf(42, 43), "Crossbowman")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(listOf(42, 43) to "Crossbowman"), transport.upgradedUnits)
        session.close()
    }

    @Test
    fun unitPromotionRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(unit)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.promoteUnitIfOpen(GAME_ID, 42, listOf("Drill I")))
        session.openGame(GAME_ID)
        val outcome = session.promoteUnitIfOpen(GAME_ID, 42, listOf("Drill I"))

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42 to listOf("Drill I")), transport.promotedUnits)
        session.close()
    }

    @Test
    fun cityPromotionPreferenceRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val city = ProjectedCity("city-1", "Rome", 0, 0, 5, 200, emptyList(), emptyList())
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownCities = listOf(city)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.setCityUnitPromotionPreferenceIfOpen(
            GAME_ID, "city-1", "Warrior", false,
        ))
        session.openGame(GAME_ID)
        val outcome = session.setCityUnitPromotionPreferenceIfOpen(
            GAME_ID, "city-1", "Warrior", false,
        )

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple("city-1", "Warrior", false)), transport.unitPromotionPreferences)
        session.close()
    }

    @Test
    fun unitRenameRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(ownUnits = listOf(unit)))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.renameUnitIfOpen(GAME_ID, 42, "First Legion"))
        session.openGame(GAME_ID)
        val outcome = session.renameUnitIfOpen(GAME_ID, 42, "First Legion")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(42 to "First Legion"), transport.renamedUnits)
        session.close()
    }

    @Test
    fun roadConnectionOrderRoutesOnlyForAnExplicitlyOpenedGame() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Worker", 1, 0, 100, 2f)
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(
                ownUnits = listOf(unit),
                exploredTiles = listOf(ProjectedTileVisibility(4, -1, visible = true)),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.setRoadConnectionOrderIfOpen(GAME_ID, 42, 4, -1))
        session.openGame(GAME_ID)
        val outcome = session.setRoadConnectionOrderIfOpen(GAME_ID, 42, 4, -1)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple(42, 4, -1)), transport.roadConnectionOrders)
        session.close()
    }

    @Test
    fun unitSwapRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame() = runBlocking {
        val unit = ProjectedUnit(
            42, "Rome", "Warrior", 0, 0, 100, 2f,
            swapDestinations = listOf(ProjectedMovementDestination(1, 0)),
        )
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(projection = current.projection.copy(
                ownUnits = listOf(unit),
                exploredTiles = listOf(ProjectedTileVisibility(1, 0, visible = true)),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.swapUnitsIfOpen(GAME_ID, 42, 1, 0))
        session.openGame(GAME_ID)
        val outcome = session.swapUnitsIfOpen(GAME_ID, 42, 1, 0)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(Triple(42, 1, 0)), transport.unitSwaps)
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
    fun researchCompletionAcknowledgmentRoutesOnlyForAnOpenedAuthoritativeGame() = runBlocking {
        val prompt = ProjectedResearchCompletion("a".repeat(64), "Writing")
        val transport = FakeTransport().apply {
            restored = true
            current = current.copy(
                projection = current.projection.copy(
                    research = current.projection.research.copy(completionPrompts = listOf(prompt)),
                ),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.acknowledgeResearchCompletionIfOpen(GAME_ID, prompt.promptId))
        session.openGame(GAME_ID)
        val outcome = session.acknowledgeResearchCompletionIfOpen(GAME_ID, prompt.promptId)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(listOf(prompt.promptId), transport.researchCompletionPromptIds)
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
            constructionOptions = listOf(projectedConstructionOption("Monument")),
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
            constructionOptions = listOf(projectedConstructionOption("Monument")),
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
    fun authoritativeQueueContextRetryRetainsItsClosedActionAndCommandId() = runBlocking {
        val projectedCity = ProjectedCity(
            id = "city-1", name = "Rome", x = 0, y = 0, population = 1, health = 200,
            constructionQueue = emptyList(), availableConstructions = listOf("Monument"),
            constructionOptions = listOf(ProjectedConstructionOption(
                "Monument", true, 0, 40, 4, emptyList(), emptyList(),
                listOf(ConstructionQueueAction.AddToTop),
            )),
        )
        val transport = FakeTransport().apply {
            restored = true
            manageQueueFailuresRemaining = 1
            current = current.copy(
                projection = current.projection.copy(ownCities = listOf(projectedCity)),
            )
        }
        val session = session(transport)
        session.restore()
        session.openGame(GAME_ID)

        assertTrue(session.manageConstructionQueuesIfOpen(
            GAME_ID, "city-1", "Monument", null, ConstructionQueueAction.AddToTop,
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.manageConstructionQueuesIfOpen(
            GAME_ID, "city-1", "Monument", null, ConstructionQueueAction.AddToTop,
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.managedConstructionQueues.size)
        assertEquals(
            transport.managedConstructionQueues[0].commandId,
            transport.managedConstructionQueues[1].commandId,
        )
        assertEquals(
            ConstructionQueueAction.AddToTop,
            transport.managedConstructionQueues.first().action,
        )
        session.close()
    }

    @Test
    fun authoritativeCityGovernanceRoutesOnlyWhenOpenedAndRetriesTheSameCommand() = runBlocking {
        val projectedCity = ProjectedCity(
            id = "city-1", name = "Rome", x = 0, y = 0, population = 1, health = 200,
            constructionQueue = emptyList(), availableConstructions = emptyList(),
            availableGovernanceActions = listOf(CityGovernanceAction.StartRazing),
        )
        val transport = FakeTransport().apply {
            restored = true
            governanceFailuresRemaining = 1
            current = current.copy(
                projection = current.projection.copy(ownCities = listOf(projectedCity)),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.setCityGovernanceIfOpen(
            GAME_ID, "city-1", CityGovernanceAction.StartRazing,
        ))
        session.openGame(GAME_ID)
        assertTrue(session.setCityGovernanceIfOpen(
            GAME_ID, "city-1", CityGovernanceAction.StartRazing,
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.setCityGovernanceIfOpen(
            GAME_ID, "city-1", CityGovernanceAction.StartRazing,
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.governanceCommandIds.size)
        assertEquals(transport.governanceCommandIds[0], transport.governanceCommandIds[1])
        session.close()
    }

    @Test
    fun authoritativeCityDispositionRoutesOnlyWhenOpenedAndRetriesTheSameCommand() = runBlocking {
        val decision = ProjectedCityDisposition(
            "city-1", "Athens", listOf(CityDispositionAction.Annex, CityDispositionAction.Puppet),
        )
        val transport = FakeTransport().apply {
            restored = true
            dispositionFailuresRemaining = 1
            current = current.copy(
                projection = current.projection.copy(pendingCityDispositions = listOf(decision)),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.resolveCityDispositionIfOpen(
            GAME_ID, "city-1", CityDispositionAction.Puppet,
        ))
        session.openGame(GAME_ID)
        assertTrue(session.resolveCityDispositionIfOpen(
            GAME_ID, "city-1", CityDispositionAction.Puppet,
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.resolveCityDispositionIfOpen(
            GAME_ID, "city-1", CityDispositionAction.Puppet,
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.dispositionCommandIds.size)
        assertEquals(transport.dispositionCommandIds[0], transport.dispositionCommandIds[1])
        session.close()
    }

    @Test
    fun authoritativeDiplomaticVoteRoutesOnlyWhenOpenedAndRetriesTheSameCommand() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            voteFailuresRemaining = 1
            current = current.copy(
                projection = current.projection.copy(
                    pendingTurnActions = listOf(PendingEndTurnAction.CastDiplomaticVote),
                    diplomaticVoteCandidates = listOf("Greece"),
                ),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.castDiplomaticVoteIfOpen(GAME_ID, "Greece"))
        session.openGame(GAME_ID)
        assertTrue(session.castDiplomaticVoteIfOpen(
            GAME_ID, "Greece",
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.castDiplomaticVoteIfOpen(
            GAME_ID, "Greece",
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.voteCommandIds.size)
        assertEquals(transport.voteCommandIds[0], transport.voteCommandIds[1])
        session.close()
    }

    @Test
    fun authoritativeGreatPersonChoiceRoutesOnlyWhenOpenedAndRetriesTheSameCommand() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            greatPersonFailuresRemaining = 1
            current = current.copy(
                projection = current.projection.copy(
                    pendingTurnActions = listOf(PendingEndTurnAction.PickGreatPerson),
                    selectableGreatPeople = listOf("Great Scientist"),
                ),
            )
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.chooseGreatPersonIfOpen(GAME_ID, "Great Scientist"))
        session.openGame(GAME_ID)
        assertTrue(session.chooseGreatPersonIfOpen(
            GAME_ID, "Great Scientist",
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.chooseGreatPersonIfOpen(
            GAME_ID, "Great Scientist",
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.greatPersonCommandIds.size)
        assertEquals(transport.greatPersonCommandIds[0], transport.greatPersonCommandIds[1])
        session.close()
    }

    @Test
    fun authoritativeReligiousUnitActionRoutesOnlyWhenOpenedAndRetriesTheSameCommand() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            religiousUnitFailuresRemaining = 1
            current = current.copy(projection = current.projection.copy(
                ownUnits = listOf(ProjectedUnit(
                    17, "Rome", "Missionary", 0, 0, 100, 2f,
                    availableReligiousActions = listOf(ReligiousUnitAction.SpreadReligion),
                )),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.useReligiousUnitIfOpen(
            GAME_ID, 17, ReligiousUnitAction.SpreadReligion,
        ))
        session.openGame(GAME_ID)
        assertTrue(session.useReligiousUnitIfOpen(
            GAME_ID, 17, ReligiousUnitAction.SpreadReligion,
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.useReligiousUnitIfOpen(
            GAME_ID, 17, ReligiousUnitAction.SpreadReligion,
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.religiousUnitCommandIds.size)
        assertEquals(transport.religiousUnitCommandIds[0], transport.religiousUnitCommandIds[1])
        session.close()
    }

    @Test
    fun authoritativeReligiousBeliefsRouteOnlyWhenOpenedAndRetryTheSameCommand() = runBlocking {
        val transport = FakeTransport().apply {
            restored = true
            religiousBeliefFailuresRemaining = 1
            current = current.copy(projection = current.projection.copy(
                religionChoice = ProjectedReligionChoice(
                    listOf(ReligiousBeliefType.Pantheon),
                    listOf(ProjectedReligiousBelief("God-King", ReligiousBeliefType.Pantheon)),
                    emptyList(),
                    requiresReligionIdentity = false,
                ),
            ))
        }
        val session = session(transport)
        session.restore()

        assertEquals(null, session.chooseReligiousBeliefsIfOpen(
            GAME_ID, listOf("God-King"), null, null,
        ))
        session.openGame(GAME_ID)
        assertTrue(session.chooseReligiousBeliefsIfOpen(
            GAME_ID, listOf("God-King"), null, null,
        ) is AuthoritativeCommandOutcome.RetryRequired)
        assertTrue(session.chooseReligiousBeliefsIfOpen(
            GAME_ID, listOf("God-King"), null, null,
        ) is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.religiousBeliefCommandIds.size)
        assertEquals(transport.religiousBeliefCommandIds[0], transport.religiousBeliefCommandIds[1])
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
            constructionQueueEntries = listOf(projectedQueueEntry("Monument")),
            constructionOptions = listOf(projectedConstructionOption("Monument")),
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
            constructionQueueEntries = listOf(projectedQueueEntry("Monument")),
            constructionOptions = listOf(ProjectedConstructionOption(
                "Nothing", true, 0, null, null, emptyList(), emptyList(),
            )),
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
            tileStates = listOf(ProjectedCityTileState(2, 0, false, null, null, false, false)),
            tilePurchases = listOf(ProjectedCityTilePurchase(2, 0, 50, true)),
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
            research = ProjectedResearch(
                currentTechnology = null,
                researchedTechnologies = emptyList(),
                queue = emptyList(),
                queueEntries = emptyList(),
                overflowScience = 0,
                selectableTargets = emptyList(),
                appendableTargets = emptyList(),
                freeTechnologyChoices = emptyList(),
                completionPrompts = emptyList(),
            ),
            policies = ProjectedPolicies(0, 25, 0, emptyList(), emptyList()),
            gold = 0,
            knownCivilizations = emptyList(),
            ownCities = emptyList(),
            ownUnits = emptyList(),
            exploredTiles = emptyList(),
            visibleForeignUnits = emptyList(),
        ),
    )

    private fun projectedConstructionOption(name: String) = ProjectedConstructionOption(
        name, true, 0, 40, 4, emptyList(),
        listOf(ProjectedConstructionPurchase(
            "Gold", 100, 1_000, true, false, emptyList(),
        )),
    )

    private fun projectedQueueEntry(name: String) = ProjectedConstructionQueueEntry(
        name, 0, 40, 4,
        listOf(ProjectedConstructionPurchase(
            "Gold", 100, 1_000, true, false, emptyList(),
        )),
    )

    private fun manifest(hash: String, base: String, mods: List<String>) =
        ApiV3RulesetManifestSummary(
            hash.repeat(64),
            "engine-1",
            ApiV3PublicRulesetIdentity(base, "f".repeat(64)),
            mods.map { ApiV3PublicRulesetIdentity(it, "1".repeat(64)) },
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
        val unitMoves = mutableListOf<Triple<Int, Int, Int>>()
        val moveRequests = mutableListOf<ApiV3MoveUnitRequest>()
        val movementOrders = mutableListOf<Triple<Int, Int, Int>>()
        val moveTowardRequests = mutableListOf<ApiV3MoveUnitTowardRequest>()
        val cancelledMovementOrders = mutableListOf<Int>()
        val explorationOrders = mutableListOf<Pair<Int, Boolean>>()
        val automationOrders = mutableListOf<Pair<Int, Boolean>>()
        val postureOrders = mutableListOf<Pair<Int, UnitPosture>>()
        val disbandedUnits = mutableListOf<Int>()
        val pillagedByUnits = mutableListOf<Int>()
        val foundingUnits = mutableListOf<Int>()
        val unitAttacks = mutableListOf<Triple<Int, Int, Int>>()
        val cityBombardments = mutableListOf<Triple<String, Int, Int>>()
        val nuclearStrikes = mutableListOf<Triple<Int, Int, Int>>()
        val airSweeps = mutableListOf<Triple<Int, Int, Int>>()
        val upgradedUnits = mutableListOf<Pair<List<Int>, String>>()
        val promotedUnits = mutableListOf<Pair<Int, List<String>>>()
        val unitPromotionPreferences = mutableListOf<Triple<String, String, Boolean>>()
        val renamedUnits = mutableListOf<Pair<Int, String?>>()
        val improvementOrders = mutableListOf<Triple<Int, String?, String?>>()
        val roadConnectionOrders = mutableListOf<Triple<Int, Int?, Int?>>()
        val unitSwaps = mutableListOf<Triple<Int, Int, Int>>()
        val researchTargets = mutableListOf<String>()
        val policyNames = mutableListOf<String>()
        val freeTechnologyNames = mutableListOf<String>()
        val researchCompletionPromptIds = mutableListOf<String>()
        val queuedConstructions = mutableListOf<Pair<String, String>>()
        val perpetualConstructions = mutableListOf<Pair<String, String>>()
        val queueCommandIds = mutableListOf<String>()
        val removedConstructions = mutableListOf<ApiV3RemoveConstructionRequest>()
        val movedConstructions = mutableListOf<ApiV3MoveConstructionRequest>()
        val managedConstructionQueues = mutableListOf<ApiV3ManageConstructionQueuesRequest>()
        val purchaseCommandIds = mutableListOf<String>()
        val tilePurchaseCommandIds = mutableListOf<String>()
        val governanceCommandIds = mutableListOf<String>()
        val dispositionCommandIds = mutableListOf<String>()
        val voteCommandIds = mutableListOf<String>()
        val greatPersonCommandIds = mutableListOf<String>()
        val religiousUnitCommandIds = mutableListOf<String>()
        val religiousBeliefCommandIds = mutableListOf<String>()
        var purchaseFailuresRemaining = 0
        var tilePurchaseFailuresRemaining = 0
        var governanceFailuresRemaining = 0
        var dispositionFailuresRemaining = 0
        var voteFailuresRemaining = 0
        var greatPersonFailuresRemaining = 0
        var religiousUnitFailuresRemaining = 0
        var religiousBeliefFailuresRemaining = 0
        var queueFailuresRemaining = 0
        var manageQueueFailuresRemaining = 0
        var logoutCalls = 0
        val listCalls = mutableListOf<Pair<String?, Int>>()
        val manifestListCalls = mutableListOf<String?>()
        val manifestPages = mutableMapOf<String?, ApiV3RulesetManifestPage>()
        val createdGames = mutableListOf<Pair<String, ApiV3GameSetup>>()
        val passwordChanges = mutableListOf<Pair<String, String>>()
        val disableRequests = mutableListOf<String>()
        val deleteRequests = mutableListOf<String>()
        val addedSpectators = mutableListOf<Pair<String, String>>()
        val spectatorProjectionCalls = mutableListOf<String>()
        val leftSpectatorGames = mutableListOf<String>()
        val ownershipTransfers = mutableListOf<Triple<String, String, String>>()
        val playerInvitations = mutableListOf<ApiV3PlayerInvitation>()
        val playerInvitationRequests = mutableListOf<Triple<String, String, String>>()
        val joinRequests = mutableListOf<Triple<String, String, Long>>()
        val joinObservedHashes = mutableListOf<String>()
        val closedGames = mutableListOf<Pair<String, String>>()
        val archivedGames = mutableListOf<Pair<String, String>>()
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
        override suspend fun listRulesetManifests(
            after: String?,
            limit: Int,
        ): ApiV3RulesetManifestPage {
            manifestListCalls += after
            return manifestPages[after] ?: ApiV3RulesetManifestPage(emptyList())
        }
        override suspend fun listPlayerInvitations() = playerInvitations.toList()
        override suspend fun invitePlayer(gameId: String, request: ApiV3InvitePlayerRequest) {
            playerInvitationRequests += Triple(gameId, request.invitationId, request.username)
        }
        override suspend fun createGame(
            rulesetManifestHash: String,
            setup: ApiV3GameSetup,
        ): ApiV3GameMetadata {
            createdGames += rulesetManifestHash to setup
            return ApiV3GameMetadata(GAME_ID, 0, "hash-0", "owner", "Rome")
        }
        override suspend fun joinGame(gameId: String, request: ApiV3JoinGameRequest): ApiV3CommandAccepted {
            joinRequests += Triple(gameId, request.commandId, request.expectedRevision)
            joinObservedHashes += request.clientObservedStateHash
            return ApiV3CommandAccepted(
                gameId,
                request.commandId,
                request.expectedRevision,
                request.expectedRevision + 1,
                "joined-hash",
            )
        }
        override suspend fun projection(gameId: String): ApiV3GameProjection {
            projectionCalls++
            return current
        }
        override suspend fun spectatorProjection(gameId: String): ApiV3SpectatorGameProjection {
            spectatorProjectionCalls += gameId
            return ApiV3SpectatorGameProjection(
            gameId = gameId,
            projectionVersion = SpectatorProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = current.committedRevision,
            canonicalStateHash = current.canonicalStateHash,
            projectionHash = "spectator-hash",
            projection = SpectatorProjection(
                turn = current.projection.turn,
                currentPlayerCivilizationId = current.projection.currentPlayerCivilizationId,
                majorCivilizations = emptyList(),
            ),
            )
        }
        override suspend fun addSpectator(gameId: String, username: String) {
            addedSpectators += gameId to username
        }
        override suspend fun leaveSpectator(gameId: String) {
            leftSpectatorGames += gameId
        }
        override suspend fun transferOwnership(gameId: String, request: ApiV3TransferOwnershipRequest) {
            ownershipTransfers += Triple(gameId, request.operationId, request.username)
        }
        override suspend fun closeGameAdmin(gameId: String, request: ApiV3GameAdminOperationRequest) {
            closedGames += gameId to request.operationId
        }
        override suspend fun archiveGame(gameId: String, request: ApiV3GameAdminOperationRequest) {
            archivedGames += gameId to request.operationId
        }
        override suspend fun moveUnit(
            gameId: String,
            request: ApiV3MoveUnitRequest,
        ): ApiV3CommandAccepted {
            moveRequests += request
            unitMoves += Triple(request.unitId, request.destinationX, request.destinationY)
            val movedUnits = current.projection.ownUnits.map { unit ->
                if (unit.id == request.unitId || unit.id == request.escortUnitId)
                    unit.copy(x = request.destinationX, y = request.destinationY)
                else unit
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(ownUnits = movedUnits),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun moveUnitToward(
            gameId: String,
            request: ApiV3MoveUnitTowardRequest,
        ): ApiV3CommandAccepted {
            moveTowardRequests += request
            movementOrders += Triple(request.unitId, request.destinationX, request.destinationY)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    ownUnits = current.projection.ownUnits.map { unit ->
                        if (unit.id == request.unitId) unit.copy(
                            movementDestinationX = request.destinationX,
                            movementDestinationY = request.destinationY,
                        ) else unit
                    },
                ),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun cancelUnitMovementOrder(
            gameId: String,
            request: ApiV3CancelUnitMovementOrderRequest,
        ): ApiV3CommandAccepted {
            cancelledMovementOrders += request.unitId
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    ownUnits = current.projection.ownUnits.map { unit ->
                        if (unit.id == request.unitId) unit.copy(
                            movementDestinationX = null,
                            movementDestinationY = null,
                        ) else unit
                    },
                ),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun setUnitExploration(
            gameId: String,
            request: ApiV3SetUnitExplorationRequest,
        ): ApiV3CommandAccepted {
            explorationOrders += request.unitId to request.enabled
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun setUnitAutomation(
            gameId: String,
            request: ApiV3SetUnitAutomationRequest,
        ): ApiV3CommandAccepted {
            automationOrders += request.unitId to request.enabled
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    ownUnits = current.projection.ownUnits.map { unit ->
                        if (unit.id == request.unitId) unit.copy(automated = request.enabled) else unit
                    },
                ),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun setUnitPosture(
            gameId: String,
            request: ApiV3SetUnitPostureRequest,
        ): ApiV3CommandAccepted {
            postureOrders += request.unitId to request.posture
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    ownUnits = current.projection.ownUnits.map { unit ->
                        if (unit.id == request.unitId) unit.copy(posture = request.posture) else unit
                    },
                ),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun disbandUnit(
            gameId: String,
            request: ApiV3DisbandUnitRequest,
        ): ApiV3CommandAccepted {
            disbandedUnits += request.unitId
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    ownUnits = current.projection.ownUnits.filterNot { it.id == request.unitId },
                ),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun pillageTile(
            gameId: String,
            request: ApiV3PillageTileRequest,
        ): ApiV3CommandAccepted {
            pillagedByUnits += request.unitId
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-pillage",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun foundCity(
            gameId: String,
            request: ApiV3FoundCityRequest,
        ): ApiV3CommandAccepted {
            foundingUnits += request.unitId
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-found-city",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun paradropUnit(
            gameId: String,
            request: ApiV3ParadropUnitRequest,
        ): ApiV3CommandAccepted = error("Paradrop is not used by this session fixture")
        override suspend fun attackWithUnit(
            gameId: String,
            request: ApiV3AttackWithUnitRequest,
        ): ApiV3CommandAccepted {
            unitAttacks += Triple(request.unitId, request.targetX, request.targetY)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-attack",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun bombardWithCity(
            gameId: String,
            request: ApiV3BombardWithCityRequest,
        ): ApiV3CommandAccepted {
            cityBombardments += Triple(request.cityId, request.targetX, request.targetY)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-bombard",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun launchNuclearStrike(
            gameId: String,
            request: ApiV3LaunchNuclearStrikeRequest,
        ): ApiV3CommandAccepted {
            nuclearStrikes += Triple(request.unitId, request.targetX, request.targetY)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-nuke",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun airSweep(
            gameId: String,
            request: ApiV3AirSweepRequest,
        ): ApiV3CommandAccepted {
            airSweeps += Triple(request.unitId, request.targetX, request.targetY)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-air-sweep",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun upgradeUnits(
            gameId: String,
            request: ApiV3UpgradeUnitsRequest,
        ): ApiV3CommandAccepted {
            upgradedUnits += request.unitIds to request.targetUnitName
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun promoteUnit(
            gameId: String,
            request: ApiV3PromoteUnitRequest,
        ): ApiV3CommandAccepted {
            promotedUnits += request.unitId to request.promotionNames
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun setCityUnitPromotionPreference(
            gameId: String,
            request: ApiV3SetCityUnitPromotionPreferenceRequest,
        ): ApiV3CommandAccepted {
            unitPromotionPreferences += Triple(request.cityId, request.baseUnitName, request.enabled)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun renameUnit(
            gameId: String,
            request: ApiV3RenameUnitRequest,
        ): ApiV3CommandAccepted {
            renamedUnits += request.unitId to request.instanceName
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun setTileImprovementOrder(
            gameId: String,
            request: ApiV3SetTileImprovementOrderRequest,
        ): ApiV3CommandAccepted {
            improvementOrders += Triple(
                request.unitId, request.improvementName, request.queuedImprovementName,
            )
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun setRoadConnectionOrder(
            gameId: String,
            request: ApiV3SetRoadConnectionOrderRequest,
        ): ApiV3CommandAccepted {
            roadConnectionOrders += Triple(request.unitId, request.destinationX, request.destinationY)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun swapUnits(
            gameId: String,
            request: ApiV3SwapUnitsRequest,
        ): ApiV3CommandAccepted {
            unitSwaps += Triple(request.unitId, request.destinationX, request.destinationY)
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
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
        override suspend fun resetCitizens(
            gameId: String,
            request: ApiV3ResetCitizensRequest,
        ) = unsupported()
        override suspend fun setAvoidGrowth(
            gameId: String,
            request: ApiV3SetAvoidGrowthRequest,
        ) = unsupported()
        override suspend fun setCitizenFocus(
            gameId: String,
            request: ApiV3SetCitizenFocusRequest,
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
        override suspend fun manageConstructionQueues(
            gameId: String,
            request: ApiV3ManageConstructionQueuesRequest,
        ): ApiV3CommandAccepted {
            managedConstructionQueues += request
            if (manageQueueFailuresRemaining > 0) {
                manageQueueFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
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
        override suspend fun sellBuilding(
            gameId: String,
            request: ApiV3SellBuildingRequest,
        ): ApiV3CommandAccepted = unsupported()
        override suspend fun setCityGovernance(
            gameId: String,
            request: ApiV3SetCityGovernanceRequest,
        ): ApiV3CommandAccepted {
            governanceCommandIds += request.commandId
            if (governanceFailuresRemaining > 0) {
                governanceFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun resolveCityDisposition(
            gameId: String,
            request: ApiV3ResolveCityDispositionRequest,
        ): ApiV3CommandAccepted {
            dispositionCommandIds += request.commandId
            if (dispositionFailuresRemaining > 0) {
                dispositionFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
                projection = current.projection.copy(pendingCityDispositions = emptyList()),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun castDiplomaticVote(
            gameId: String,
            request: ApiV3CastDiplomaticVoteRequest,
        ): ApiV3CommandAccepted {
            voteCommandIds += request.commandId
            if (voteFailuresRemaining > 0) {
                voteFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
                projection = current.projection.copy(
                    pendingTurnActions = emptyList(),
                    diplomaticVoteCandidates = emptyList(),
                ),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun chooseGreatPerson(
            gameId: String,
            request: ApiV3ChooseGreatPersonRequest,
        ): ApiV3CommandAccepted {
            greatPersonCommandIds += request.commandId
            if (greatPersonFailuresRemaining > 0) {
                greatPersonFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
                projection = current.projection.copy(
                    pendingTurnActions = emptyList(),
                    selectableGreatPeople = emptyList(),
                ),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun useReligiousUnit(
            gameId: String,
            request: ApiV3UseReligiousUnitRequest,
        ): ApiV3CommandAccepted {
            religiousUnitCommandIds += request.commandId
            if (religiousUnitFailuresRemaining > 0) {
                religiousUnitFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun chooseReligiousBeliefs(
            gameId: String,
            request: ApiV3ChooseReligiousBeliefsRequest,
        ): ApiV3CommandAccepted {
            religiousBeliefCommandIds += request.commandId
            if (religiousBeliefFailuresRemaining > 0) {
                religiousBeliefFailuresRemaining--
                throw IOException("lost response")
            }
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-${current.committedRevision + 1}",
                projection = current.projection.copy(religionChoice = null),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                current.committedRevision, current.canonicalStateHash,
            )
        }
        override suspend fun offerTrade(gameId: String, request: ApiV3OfferTradeRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun retractTradeOffer(gameId: String, request: ApiV3RetractTradeOfferRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun acceptTrade(gameId: String, request: ApiV3TradeRequestDecisionRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun declineTrade(gameId: String, request: ApiV3TradeRequestDecisionRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun counterTrade(gameId: String, request: ApiV3CounterTradeRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun declareWar(gameId: String, request: ApiV3DiplomacyPartnerRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun denounceCivilization(gameId: String, request: ApiV3DiplomacyPartnerRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun offerFriendship(gameId: String, request: ApiV3DiplomacyPartnerRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun makeDiplomaticDemand(gameId: String, request: ApiV3DiplomaticDemandRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun respondToDiplomaticPrompt(gameId: String, request: ApiV3DiplomaticPromptResponseRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun respondToCityStateProtectionPrompt(gameId: String, request: ApiV3CityStateProtectionPromptResponseRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun giftCityStateGold(gameId: String, request: ApiV3CityStateGoldGiftRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun setCityStateProtection(gameId: String, request: ApiV3CityStateProtectionRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun demandCityStateTribute(gameId: String, request: ApiV3CityStateTributeRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun giftCityStateImprovement(gameId: String, request: ApiV3CityStateImprovementGiftRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun negotiateCityStatePeace(gameId: String, request: ApiV3CityStatePeaceRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun marryCityState(gameId: String, request: ApiV3CityStateMarriageRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun moveSpy(gameId: String, request: ApiV3MoveSpyRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun setSpyCoup(gameId: String, request: ApiV3SetSpyCoupRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun useGreatPersonUnit(gameId: String, request: ApiV3UseGreatPersonUnitRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun giftUnit(gameId: String, request: ApiV3GiftUnitRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun addUnitToCapitalProject(gameId: String, request: ApiV3AddUnitToCapitalProjectRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun transformUnit(gameId: String, request: ApiV3TransformUnitRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun triggerUnitUnique(gameId: String, request: ApiV3TriggerUnitUniqueRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun createInstantImprovement(gameId: String, request: ApiV3CreateInstantImprovementRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun resolveEventChoice(gameId: String, request: ApiV3ResolveEventChoiceRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)

        private fun acceptTradeTestCommand(gameId: String, commandId: String, revision: Long): ApiV3CommandAccepted {
            current = current.copy(committedRevision = revision + 1, canonicalStateHash = "hash-${revision + 1}")
            return ApiV3CommandAccepted(gameId, commandId, revision, revision + 1, current.canonicalStateHash)
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
        override suspend fun acknowledgeResearchCompletion(
            gameId: String,
            request: ApiV3AcknowledgeResearchCompletionRequest,
        ): ApiV3CommandAccepted {
            researchCompletionPromptIds += request.promptId
            current = current.copy(
                committedRevision = current.committedRevision + 1,
                canonicalStateHash = "hash-8",
                projectionHash = "projection-hash-8",
                projection = current.projection.copy(
                    research = current.projection.research.copy(completionPrompts = emptyList()),
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
        override suspend fun resign(gameId: String, request: ApiV3ResignRequest) =
            ApiV3CommandAccepted(
                gameId,
                request.commandId,
                request.expectedRevision,
                request.expectedRevision + 1,
                "resigned",
            )
        override suspend fun forceResign(gameId: String, request: ApiV3ForceResignRequest) =
            ApiV3CommandAccepted(
                gameId,
                request.commandId,
                request.expectedRevision,
                request.expectedRevision + 1,
                "force-resigned",
            )
        override suspend fun kickMember(gameId: String, request: ApiV3KickMemberRequest) =
            ApiV3CommandAccepted(
                gameId,
                request.commandId,
                request.expectedRevision,
                request.expectedRevision + 1,
                "kicked",
            )
        override fun notifications(): Flow<ApiV3RevisionNotification> = notifications

        private fun unsupported(): Nothing = error("not used by this lifecycle test")
    }

    companion object {
        private const val GAME_ID = "00000000-0000-0000-0000-000000000001"
        private const val NEXT_GAME_ID = "00000000-0000-0000-0000-000000000002"

        private fun gameSetup() = ApiV3GameSetup(
            difficulty = "Prince",
            speed = "Standard",
            startingEra = "Ancient era",
            victoryTypes = listOf("Domination", "Scientific"),
            majorCivilizations = 4,
            cityStates = 6,
            maxTurns = 500,
            mapType = ApiV3GeneratedMapType.Pangaea,
            mapShape = ApiV3GeneratedMapShape.Hexagonal,
            mapSize = ApiV3GeneratedMapSize.Medium,
            mapResources = ApiV3MapResourceDensity.Default,
            barbarians = ApiV3BarbarianMode.Normal,
            oneCityChallenge = false,
            nuclearWeaponsEnabled = true,
            espionageEnabled = true,
            noStartBias = false,
            shufflePlayerOrder = false,
            noCityRazing = false,
            worldWrap = false,
            strategicBalance = false,
            legendaryStart = false,
            noRuins = false,
            noNaturalWonders = false,
            minutesUntilSkipTurn = 1_440,
            minutesUntilForceResign = 4_320,
            minutesRecoveredPerTurn = 1_440,
        )
    }
}
