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
    fun resignationIsTerminalAndDoesNotFetchAnUnauthorizedProjection() = runBlocking {
        val transport = FakeTransport(projection(7, "hash-7"))
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "resign-command" }
        bus.refresh()

        val outcome = bus.resign()

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(1, transport.projectionCalls)
        assertEquals("resign-command", transport.resignRequests.single().commandId)
    }

    @Test
    fun forceResignationContainsNoTargetOrTimingClaimAndReconciles() = runBlocking {
        val transport = FakeTransport(projection(7, "hash-7"))
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "force-resign-command" }
        bus.refresh()

        val outcome = bus.forceResign()

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        val request = transport.forceResignRequests.single()
        val encoded = Json.encodeToString(ApiV3ForceResignRequest.serializer(), request)
        assertTrue(!encoded.contains("civilization"))
        assertTrue(!encoded.contains("inactive"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun ownerKickCarriesOnlyTheAccountNameAndRetriesWithTheSameCommandId() = runBlocking {
        val transport = FakeTransport(projection(7, "hash-7")).apply {
            kickFailuresRemaining = 1
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "kick-command" }
        bus.refresh()

        assertSame(AuthoritativeCommandOutcome.RetryRequired, bus.kickMember("PlayerTwo"))
        assertTrue(bus.retryPending() is AuthoritativeCommandOutcome.Accepted)

        assertEquals(2, transport.kickRequests.size)
        assertEquals(listOf("kick-command", "kick-command"), transport.kickRequests.map { it.commandId })
        val encoded = Json.encodeToString(ApiV3KickMemberRequest.serializer(), transport.kickRequests.last())
        assertTrue(encoded.contains("\"username\":\"PlayerTwo\""))
        assertTrue(!encoded.contains("civilization"))
        assertTrue(!encoded.contains("actor"))
    }

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
    fun movementOrderRequestContainsNoClientPathOrActionEncoding() {
        val encoded = Json.encodeToString(
            ApiV3MoveUnitTowardRequest.serializer(),
            ApiV3MoveUnitTowardRequest("command", 7, "hash", 42, 7, 2),
        )
        assertTrue(encoded.contains("\"destination_x\":7"))
        assertTrue(!encoded.contains("path"))
        assertTrue(!encoded.contains("action"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun movementOrderCancellationContainsOnlyTheStableUnitId() {
        val encoded = Json.encodeToString(
            ApiV3CancelUnitMovementOrderRequest.serializer(),
            ApiV3CancelUnitMovementOrderRequest("command", 7, "hash", 42),
        )
        assertTrue(encoded.contains("\"unit_id\":42"))
        assertTrue(!encoded.contains("action"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun movementOrderCancellationRequiresAProjectedCanonicalOrder() = runBlocking {
        val ordered = ProjectedUnit(
            42, "Rome", "Warrior", 1, 0, 100, 0f,
            movementDestinationX = 7, movementDestinationY = 2,
        )
        val initial = projection(7, "hash-7", ownUnits = listOf(ordered))
        val committed = projection(8, "hash-8", ownUnits = listOf(
            ordered.copy(movementDestinationX = null, movementDestinationY = null),
        ))
        val transport = FakeTransport(initial).apply {
            onCancelMovementOrder = { request ->
                current = committed
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "cancel-command" }
        bus.refresh()

        assertTrue(bus.cancelUnitMovementOrder(42) is AuthoritativeCommandOutcome.Accepted)
        assertEquals("cancel-command", transport.cancelMovementOrderRequests.single().commandId)
    }

    @Test
    fun explorationRequestContainsOnlyStableUnitAndDesiredState() = runBlocking {
        val initial = projection(
            7, "hash-7",
            ownUnits = listOf(ProjectedUnit(42, "Rome", "Scout", 0, 0, 100, 2f)),
        )
        val transport = FakeTransport(initial).apply {
            onSetUnitExploration = { request ->
                current = current.copy(committedRevision = 8, canonicalStateHash = "hash-8")
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "explore-command" }
        bus.refresh()

        assertTrue(bus.setUnitExploration(42, true) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.unitExplorationRequests.single()
        assertEquals("explore-command", request.commandId)
        assertEquals(42, request.unitId)
        assertTrue(request.enabled)
    }

    @Test
    fun automationRequestIsBoundToProjectedOwnUnitState() = runBlocking {
        val initial = projection(
            7, "hash-7",
            ownUnits = listOf(ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f)),
        )
        val transport = FakeTransport(initial).apply {
            onSetUnitAutomation = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(
                        ownUnits = current.projection.ownUnits.map { it.copy(automated = true) },
                    ),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "automation-command" }
        bus.refresh()

        assertTrue(bus.setUnitAutomation(42, true) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.unitAutomationRequests.single()
        assertEquals("automation-command", request.commandId)
        assertEquals(42, request.unitId)
        assertTrue(request.enabled)
    }

    @Test
    fun postureRequestUsesAClosedIntentAndProjectedOwnUnitState() = runBlocking {
        val initial = projection(
            7, "hash-7",
            ownUnits = listOf(ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f)),
        )
        val transport = FakeTransport(initial).apply {
            onSetUnitPosture = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(
                        ownUnits = current.projection.ownUnits.map {
                            it.copy(posture = UnitPosture.Fortify)
                        },
                    ),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "posture-command" }
        bus.refresh()

        assertTrue(bus.setUnitPosture(42, UnitPosture.Setup) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.unitPostureRequests.single()
        assertEquals("posture-command", request.commandId)
        assertEquals(42, request.unitId)
        assertEquals(UnitPosture.Setup, request.posture)
        val encoded = Json.encodeToString(ApiV3SetUnitPostureRequest.serializer(), request)
        assertTrue(encoded.contains("\"posture\":\"setup\""))
        assertTrue(!encoded.contains("action"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun disbandRequestContainsOnlyTheProjectedStableUnitId() = runBlocking {
        val initial = projection(
            7, "hash-7",
            ownUnits = listOf(ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f)),
        )
        val transport = FakeTransport(initial).apply {
            onDisbandUnit = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(ownUnits = emptyList()),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "disband-command" }
        bus.refresh()

        assertTrue(bus.disbandUnit(42) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.disbandUnitRequests.single()
        assertEquals("disband-command", request.commandId)
        assertEquals(42, request.unitId)
        val encoded = Json.encodeToString(ApiV3DisbandUnitRequest.serializer(), request)
        assertTrue(!encoded.contains("gold"))
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("civilization"))
    }

    @Test
    fun pillageRequestContainsOnlyTheProjectedStableUnitIdAndReconcilesTile() = runBlocking {
        val visibleTile = ProjectedTileVisibility(
            0, 0, true, "Farm", false, "None", false,
        )
        val initial = projection(
            7, "hash-7",
            ownUnits = listOf(ProjectedUnit(42, "Rome", "Warrior", 0, 0, 50, 2f)),
            exploredTiles = listOf(visibleTile),
        )
        val transport = FakeTransport(initial).apply {
            onPillageTile = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(
                        ownUnits = listOf(ProjectedUnit(42, "Rome", "Warrior", 0, 0, 75, 1f)),
                        exploredTiles = listOf(visibleTile.copy(improvementPillaged = true)),
                    ),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "pillage-command" }
        bus.refresh()

        assertTrue(bus.pillageTile(42) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.pillageTileRequests.single()
        assertEquals("pillage-command", request.commandId)
        assertEquals(42, request.unitId)
        val encoded = Json.encodeToString(ApiV3PillageTileRequest.serializer(), request)
        assertTrue(!encoded.contains("improvement"))
        assertTrue(!encoded.contains("loot"))
        assertTrue(!encoded.contains("actor"))
        val synchronized = bus.state as AuthoritativeSyncState.Synchronized
        assertEquals(true, synchronized.current.projection.exploredTiles.single().improvementPillaged)
    }

    @Test
    fun foundCityRequestContainsOnlyUnitIntentAndReconcilesCityIdentity() = runBlocking {
        val settler = ProjectedUnit(42, "Rome", "Settler", 0, 0, 100, 2f)
        val initial = projection(7, "hash-7", ownUnits = listOf(settler))
        val foundedCity = ProjectedCity(
            "city-1", "Rome", 0, 0, 1, 200, emptyList(), emptyList(),
        )
        val transport = FakeTransport(initial).apply {
            onFoundCity = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(
                        ownUnits = emptyList(),
                        ownCities = listOf(foundedCity),
                    ),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "command-42" }
        bus.refresh()

        assertTrue(bus.foundCity(42) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.foundCityRequests.single()
        assertEquals("command-42", request.commandId)
        assertEquals(42, request.unitId)
        val encoded = Json.encodeToString(ApiV3FoundCityRequest.serializer(), request)
        assertTrue(!encoded.contains("city_name"))
        assertTrue(!encoded.contains("destination"))
        assertTrue(!encoded.contains("actor"))
        val synchronized = bus.state as AuthoritativeSyncState.Synchronized
        assertEquals("city-1", synchronized.current.projection.ownCities.single().id)
        assertTrue(synchronized.current.projection.ownUnits.isEmpty())
    }

    @Test
    fun paradropRequestContainsOnlyUnitAndVisibleDestinationIntent() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Paratrooper", 0, 0, 100, 2f)
        val initial = projection(7, "hash-7", ownUnits = listOf(unit)).copy(
            projection = projection(7, "hash-7", ownUnits = listOf(unit)).projection.copy(
                exploredTiles = listOf(ProjectedTileVisibility(2, -1, true)),
            ),
        )
        val transport = FakeTransport(initial).apply {
            onParadropUnit = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(
                        ownUnits = listOf(unit.copy(x = 2, y = -1, currentMovement = 1f)),
                    ),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "command-42" }
        bus.refresh()

        assertTrue(bus.paradropUnit(42, 2, -1) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.paradropUnitRequests.single()
        assertEquals(42, request.unitId)
        assertEquals(2, request.destinationX)
        assertEquals(-1, request.destinationY)
        val encoded = Json.encodeToString(ApiV3ParadropUnitRequest.serializer(), request)
        assertTrue(!encoded.contains("range"))
        assertTrue(!encoded.contains("movement_cost"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun unitAttackRequestContainsOnlyAttackerAndVisibleTargetIntent() = runBlocking {
        val attacker = ProjectedUnit(
            42, "Rome", "Warrior", 0, 0, 100, 2f,
            attackTargets = listOf(ProjectedAttackTarget(1, 0, 0, 0)),
        )
        val defender = ProjectedUnit(84, "Greece", "Warrior", 1, 0, 100, 2f)
        val base = projection(7, "hash-7", ownUnits = listOf(attacker))
        val initial = base.copy(projection = base.projection.copy(
            exploredTiles = listOf(ProjectedTileVisibility(1, 0, true)),
            visibleForeignUnits = listOf(defender),
        ))
        val transport = FakeTransport(initial).apply {
            onAttackWithUnit = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(
                        ownUnits = listOf(attacker.copy(health = 88, currentMovement = 0f)),
                        visibleForeignUnits = listOf(defender.copy(health = 70)),
                    ),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "command-attack" }
        bus.refresh()

        assertTrue(bus.attackWithUnit(42, 1, 0) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.attackWithUnitRequests.single()
        assertEquals(42, request.unitId)
        assertEquals(1, request.targetX)
        assertEquals(0, request.targetY)
        val encoded = Json.encodeToString(ApiV3AttackWithUnitRequest.serializer(), request)
        assertTrue(!encoded.contains("damage"))
        assertTrue(!encoded.contains("random"))
        assertTrue(!encoded.contains("path"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun cityBombardRequestContainsOnlyOwnedCityAndVisibleTargetIntent() = runBlocking {
        val city = ProjectedCity(
            "city-1", "Rome", 0, 0, 5, 200, emptyList(), emptyList(),
            bombardTargets = listOf(ProjectedTargetCoordinate(2, 0)),
        )
        val defender = ProjectedUnit(84, "Greece", "Warrior", 2, 0, 100, 2f)
        val base = projection(7, "hash-7")
        val initial = base.copy(projection = base.projection.copy(
            ownCities = listOf(city),
            exploredTiles = listOf(ProjectedTileVisibility(2, 0, true)),
            visibleForeignUnits = listOf(defender),
        ))
        val transport = FakeTransport(initial).apply {
            onBombardWithCity = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(
                        visibleForeignUnits = listOf(defender.copy(health = 65)),
                    ),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "command-bombard" }
        bus.refresh()

        assertTrue(bus.bombardWithCity("city-1", 2, 0) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.bombardWithCityRequests.single()
        assertEquals("city-1", request.cityId)
        assertEquals(2, request.targetX)
        assertEquals(0, request.targetY)
        val encoded = Json.encodeToString(ApiV3BombardWithCityRequest.serializer(), request)
        assertTrue(!encoded.contains("damage"))
        assertTrue(!encoded.contains("random"))
        assertTrue(!encoded.contains("range"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun nuclearStrikeRequestContainsOnlyOwnedUnitAndExploredTargetIntent() = runBlocking {
        val nuke = ProjectedUnit(
            42, "Rome", "Nuclear Missile", 0, 0, 100, 2f,
            nuclearTargetCandidates = listOf(ProjectedTargetCoordinate(4, -1)),
        )
        val initial = projection(
            7, "hash-7",
            ownUnits = listOf(nuke),
            exploredTiles = listOf(ProjectedTileVisibility(4, -1, false)),
        )
        val transport = FakeTransport(initial).apply {
            onLaunchNuclearStrike = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(ownUnits = emptyList()),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "command-nuke" }
        bus.refresh()

        assertTrue(bus.launchNuclearStrike(42, 4, -1) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.launchNuclearStrikeRequests.single()
        assertEquals(42, request.unitId)
        assertEquals(4, request.targetX)
        assertEquals(-1, request.targetY)
        val encoded = Json.encodeToString(ApiV3LaunchNuclearStrikeRequest.serializer(), request)
        assertTrue(!encoded.contains("blast"))
        assertTrue(!encoded.contains("victim"))
        assertTrue(!encoded.contains("random"))
        assertTrue(!encoded.contains("range"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun airSweepRequestContainsOnlyOwnedUnitAndTargetIntent() = runBlocking {
        val fighter = ProjectedUnit(
            42, "Rome", "Fighter", 0, 0, 100, 1f,
            airSweepTargets = listOf(ProjectedTargetCoordinate(4, -1)),
        )
        val transport = FakeTransport(projection(7, "hash-7", ownUnits = listOf(fighter))).apply {
            onAirSweep = { request ->
                current = current.copy(
                    committedRevision = 8,
                    canonicalStateHash = "hash-8",
                    projection = current.projection.copy(
                        ownUnits = listOf(fighter.copy(health = 72, currentMovement = 0f)),
                    ),
                )
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "command-air-sweep" }
        bus.refresh()

        assertTrue(bus.airSweep(42, 4, -1) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.airSweepRequests.single()
        assertEquals(42, request.unitId)
        assertEquals(4, request.targetX)
        assertEquals(-1, request.targetY)
        val encoded = Json.encodeToString(ApiV3AirSweepRequest.serializer(), request)
        assertTrue(!encoded.contains("interceptor"))
        assertTrue(!encoded.contains("damage"))
        assertTrue(!encoded.contains("random"))
        assertTrue(!encoded.contains("range"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun combatCommandsRejectTargetsAbsentFromTheirProjectedAllowlist() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f)
        val city = ProjectedCity("city-1", "Rome", 0, 0, 5, 200, emptyList(), emptyList())
        val bus = AuthoritativeGameCommandBus(
            gameId,
            FakeTransport(projection(
                7, "hash-7", ownUnits = listOf(unit), projectedCities = listOf(city),
            )),
        ) { "rejected-combat" }
        bus.refresh()

        assertTrue(runCatching { bus.attackWithUnit(42, 1, 0) }.exceptionOrNull()
            is IllegalArgumentException)
        assertTrue(runCatching { bus.bombardWithCity("city-1", 1, 0) }.exceptionOrNull()
            is IllegalArgumentException)
        assertTrue(runCatching { bus.launchNuclearStrike(42, 1, 0) }.exceptionOrNull()
            is IllegalArgumentException)
        assertTrue(runCatching { bus.airSweep(42, 1, 0) }.exceptionOrNull()
            is IllegalArgumentException)
    }

    @Test
    fun upgradeBatchContainsOnlyProjectedIdsAndTargetIntent() = runBlocking {
        val units = listOf(
            ProjectedUnit(42, "Rome", "Archer", 0, 0, 100, 2f),
            ProjectedUnit(43, "Rome", "Archer", 1, 0, 100, 2f),
        )
        val transport = FakeTransport(projection(7, "hash-7", ownUnits = units)).apply {
            onUpgradeUnits = { request ->
                current = current.copy(committedRevision = 8, canonicalStateHash = "hash-8")
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "upgrade-command" }
        bus.refresh()

        assertTrue(bus.upgradeUnits(listOf(42, 43), "Crossbowman") is AuthoritativeCommandOutcome.Accepted)
        val request = transport.upgradeUnitRequests.single()
        assertEquals(listOf(42, 43), request.unitIds)
        assertEquals("Crossbowman", request.targetUnitName)
        val encoded = Json.encodeToString(ApiV3UpgradeUnitsRequest.serializer(), request)
        assertTrue(!encoded.contains("gold"))
        assertTrue(!encoded.contains("resources"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun promotionContainsOnlyProjectedUnitAndSelectedPath() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f)
        val transport = FakeTransport(projection(7, "hash-7", ownUnits = listOf(unit))).apply {
            onPromoteUnit = { request ->
                current = current.copy(committedRevision = 8, canonicalStateHash = "hash-8")
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "promotion-command" }
        bus.refresh()

        assertTrue(bus.promoteUnit(42, listOf("Drill I", "Drill II")) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.promoteUnitRequests.single()
        assertEquals(42, request.unitId)
        assertEquals(listOf("Drill I", "Drill II"), request.promotionNames)
        val encoded = Json.encodeToString(ApiV3PromoteUnitRequest.serializer(), request)
        assertTrue(!encoded.contains("xp_cost"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun cityPromotionPreferenceContainsNoClientAuthoredPromotionState() = runBlocking {
        val city = ProjectedCity("city-1", "Rome", 0, 0, 5, 200, emptyList(), emptyList())
        val transport = FakeTransport(projection(7, "hash-7").copy(
            projection = projection(7, "hash-7").projection.copy(ownCities = listOf(city)),
        )).apply {
            onUnitPromotionPreference = { request ->
                current = current.copy(committedRevision = 8, canonicalStateHash = "hash-8")
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "preference-command" }
        bus.refresh()

        assertTrue(bus.setCityUnitPromotionPreference("city-1", "Warrior", false)
            is AuthoritativeCommandOutcome.Accepted)
        val request = transport.unitPromotionPreferenceRequests.single()
        val encoded = Json.encodeToString(ApiV3SetCityUnitPromotionPreferenceRequest.serializer(), request)
        assertTrue(!encoded.contains("saved_promotions"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun renameContainsOnlyProjectedUnitAndOptionalName() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f)
        val transport = FakeTransport(projection(7, "hash-7", ownUnits = listOf(unit))).apply {
            onRenameUnit = { request ->
                current = current.copy(committedRevision = 8, canonicalStateHash = "hash-8")
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "rename-command" }
        bus.refresh()

        assertTrue(bus.renameUnit(42, "First Legion") is AuthoritativeCommandOutcome.Accepted)
        val request = transport.renameUnitRequests.single()
        assertEquals(42, request.unitId)
        assertEquals("First Legion", request.instanceName)
        val encoded = Json.encodeToString(ApiV3RenameUnitRequest.serializer(), request)
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("civilization"))
    }

    @Test
    fun tileImprovementOrderContainsNoClientTileCostTurnsOrActor() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Worker", 2, -1, 100, 2f)
        val transport = FakeTransport(projection(7, "hash-7", ownUnits = listOf(unit))).apply {
            onImprovementOrder = { request ->
                current = current.copy(committedRevision = 8, canonicalStateHash = "hash-8")
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "improvement-command" }
        bus.refresh()

        assertTrue(bus.setTileImprovementOrder(42, "Remove Forest", "Farm")
            is AuthoritativeCommandOutcome.Accepted)
        val request = transport.improvementOrderRequests.single()
        assertEquals(42, request.unitId)
        assertEquals("Remove Forest", request.improvementName)
        assertEquals("Farm", request.queuedImprovementName)
        val encoded = Json.encodeToString(ApiV3SetTileImprovementOrderRequest.serializer(), request)
        for (forbidden in listOf("actor", "civilization", "turns", "tile_x", "tile_y", "cost"))
            assertTrue(!encoded.contains(forbidden))
    }

    @Test
    fun roadConnectionOrderContainsNoClientPathTierMovementOrActor() = runBlocking {
        val unit = ProjectedUnit(42, "Rome", "Worker", 2, -1, 100, 2f)
        val initial = projection(
            7, "hash-7",
            ownUnits = listOf(unit),
            exploredTiles = listOf(ProjectedTileVisibility(7, -2, true)),
        )
        val transport = FakeTransport(initial).apply {
            onRoadConnectionOrder = { request ->
                current = current.copy(committedRevision = 8, canonicalStateHash = "hash-8")
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "road-command" }
        bus.refresh()

        assertTrue(bus.setRoadConnectionOrder(42, 7, -2)
            is AuthoritativeCommandOutcome.Accepted)
        val request = transport.roadConnectionOrderRequests.single()
        assertEquals(Triple(42, 7, -2), Triple(
            request.unitId, request.destinationX, request.destinationY,
        ))
        val encoded = Json.encodeToString(ApiV3SetRoadConnectionOrderRequest.serializer(), request)
        for (forbidden in listOf("actor", "civilization", "path", "road_tier", "movement", "cost"))
            assertTrue(!encoded.contains(forbidden))
    }

    @Test
    fun movementOrderIsBoundToProjectedUnitAndExploredDestination() = runBlocking {
        val initial = projection(
            7, "hash-7",
            exploredTiles = listOf(ProjectedTileVisibility(7, 2, visible = false)),
            ownUnits = listOf(ProjectedUnit(42, "Rome", "Warrior", 0, 0, 100, 2f)),
        )
        val committed = initial.copy(committedRevision = 8, canonicalStateHash = "hash-8")
        val transport = FakeTransport(initial).apply {
            onMoveToward = { request ->
                current = committed
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "order-command" }
        bus.refresh()

        assertTrue(bus.moveUnitToward(42, 7, 2) is AuthoritativeCommandOutcome.Accepted)
        assertEquals("order-command", transport.moveTowardRequests.single().commandId)
    }

    @Test
    fun swapRequestContainsOnlyStableUnitAndDestinationIntent() {
        val encoded = Json.encodeToString(
            ApiV3SwapUnitsRequest.serializer(),
            ApiV3SwapUnitsRequest("command", 7, "hash", 42, -3, 8),
        )
        assertTrue(encoded.contains("\"unit_id\":42"))
        assertTrue(encoded.contains("\"destination_x\":-3"))
        assertTrue(!encoded.contains("target_unit"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun swapIsBoundToProjectedUnitAndExploredDestination() = runBlocking {
        val initial = movementProjection(7, "hash-7", 42)
        val committed = movementProjection(8, "hash-8", 42)
        val transport = FakeTransport(initial).apply {
            onSwapUnits = { request ->
                current = committed
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "swap-command" }
        bus.refresh()

        val outcome = bus.swapUnits(42, 2, 3)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals("swap-command", transport.swapRequests.single().commandId)
    }

    @Test
    fun exactMoveAndSwapRejectDestinationsNotPublishedForTheUnit() = runBlocking {
        val initial = movementProjection(7, "hash-7", 42)
        val transport = FakeTransport(initial)
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "rejected-command" }
        bus.refresh()

        val moveFailure = runCatching { bus.moveUnit(42, 9, 9) }.exceptionOrNull()
        val swapFailure = runCatching { bus.swapUnits(42, 9, 9) }.exceptionOrNull()
        assertTrue(moveFailure is IllegalArgumentException)
        assertTrue(swapFailure is IllegalArgumentException)
        assertTrue(transport.moveRequests.isEmpty())
        assertTrue(transport.swapRequests.isEmpty())
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
    fun specialistRequestContainsNoCapacityPopulationOrActorClaims() {
        val encoded = Json.encodeToString(
            ApiV3SetSpecialistCountRequest.serializer(),
            ApiV3SetSpecialistCountRequest("command", 7, "hash", "city-1", "Scientist", 2),
        )
        assertTrue(encoded.contains("\"specialist_name\":\"Scientist\""))
        assertTrue(encoded.contains("\"count\":2"))
        assertTrue(!encoded.contains("capacity"))
        assertTrue(!encoded.contains("population"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun manualSpecialistModeRequestIsClosedAndActorless() {
        val encoded = Json.encodeToString(
            ApiV3SetManualSpecialistsRequest.serializer(),
            ApiV3SetManualSpecialistsRequest("command", 7, "hash", "city-1", false),
        )
        assertTrue(encoded.contains("\"enabled\":false"))
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("allocations"))
    }

    @Test
    fun citizenResetRequestContainsNoPolicyOrPopulationClaims() {
        val encoded = Json.encodeToString(
            ApiV3ResetCitizensRequest.serializer(),
            ApiV3ResetCitizensRequest("command", 7, "hash", "city-1"),
        )
        assertTrue(encoded.contains("\"city_id\":\"city-1\""))
        assertTrue(!encoded.contains("focus"))
        assertTrue(!encoded.contains("population"))
        assertTrue(!encoded.contains("actor"))
    }

    @Test
    fun citizenPolicyRequestsContainOnlyTypedPolicyInputs() {
        val avoid = Json.encodeToString(
            ApiV3SetAvoidGrowthRequest.serializer(),
            ApiV3SetAvoidGrowthRequest("command", 7, "hash", "city-1", true),
        )
        val focus = Json.encodeToString(
            ApiV3SetCitizenFocusRequest.serializer(),
            ApiV3SetCitizenFocusRequest("command", 7, "hash", "city-1", CitizenFocus.GoldFocus),
        )
        assertTrue(avoid.contains("\"enabled\":true"))
        assertTrue(focus.contains("\"focus\":\"gold_focus\""))
        assertTrue(!avoid.contains("population") && !focus.contains("actor"))
    }

    @Test
    fun citizenPoliciesAreBoundToTheProjectedCityAndFocusAllowlist() = runBlocking {
        val initial = projection(
            7, "hash-7", cityQueue = emptyList(),
            selectableCitizenFocuses = listOf(CitizenFocus.NoFocus, CitizenFocus.GoldFocus),
        )
        val afterGrowth = projection(
            8, "hash-8", cityQueue = emptyList(), avoidGrowth = true,
            selectableCitizenFocuses = initial.projection.ownCities.single().selectableCitizenFocuses,
        )
        val afterFocus = projection(
            9, "hash-9", cityQueue = emptyList(), avoidGrowth = true,
            citizenFocus = CitizenFocus.GoldFocus,
            selectableCitizenFocuses = initial.projection.ownCities.single().selectableCitizenFocuses,
        )
        val transport = FakeTransport(initial).apply {
            onSetAvoidGrowth = { request ->
                current = afterGrowth
                accepted(request.commandId, 7, 8, "hash-8")
            }
            onSetCitizenFocus = { request ->
                current = afterFocus
                accepted(request.commandId, 8, 9, "hash-9")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "policy-command" }
        bus.refresh()

        assertTrue(bus.setAvoidGrowth("city-1", true) is AuthoritativeCommandOutcome.Accepted)
        assertTrue(bus.setCitizenFocus("city-1", CitizenFocus.GoldFocus) is AuthoritativeCommandOutcome.Accepted)
        assertEquals(true, transport.avoidGrowthRequests.single().enabled)
        assertEquals(CitizenFocus.GoldFocus, transport.citizenFocusRequests.single().focus)
    }

    @Test
    fun cityTileAssignmentIsBoundToTheProjectedCityTile() = runBlocking {
        val tile = ProjectedCityTile(2, -1, worked = true, locked = false)
        val initial = projection(7, "hash-7", cityQueue = emptyList(), assignableTiles = listOf(tile))
        val committed = projection(8, "hash-8", cityQueue = emptyList(), assignableTiles = listOf(tile.copy(locked = true)))
        val transport = FakeTransport(initial).apply {
            onSetCityTileAssignment = { request ->
                current = committed
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "assignment-command" }
        bus.refresh()

        val outcome = bus.setCityTileAssignment("city-1", 2, -1, CityTileAssignment.Locked)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(CityTileAssignment.Locked, transport.cityTileAssignmentRequests.single().assignment)
        assertEquals("assignment-command", transport.cityTileAssignmentRequests.single().commandId)
    }

    @Test
    fun specialistCountIsBoundToProjectedNameAndCapacity() = runBlocking {
        val specialist = ProjectedSpecialist("Scientist", assigned = 1, capacity = 2)
        val initial = projection(7, "hash-7", cityQueue = emptyList(), specialists = listOf(specialist))
        val committed = projection(8, "hash-8", cityQueue = emptyList(), specialists = listOf(specialist.copy(assigned = 2)))
        val transport = FakeTransport(initial).apply {
            onSetSpecialistCount = { request ->
                current = committed
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "specialist-command" }
        bus.refresh()

        val outcome = bus.setSpecialistCount("city-1", "Scientist", 2)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(2, transport.specialistCountRequests.single().count)
        assertEquals("specialist-command", transport.specialistCountRequests.single().commandId)
    }

    @Test
    fun manualSpecialistModeRequiresProjectedSlots() = runBlocking {
        val specialist = ProjectedSpecialist("Scientist", assigned = 1, capacity = 2)
        val initial = projection(7, "hash-7", cityQueue = emptyList(), specialists = listOf(specialist))
        val committed = projection(8, "hash-8", cityQueue = emptyList(), specialists = listOf(specialist))
        val transport = FakeTransport(initial).apply {
            onSetManualSpecialists = { request ->
                current = committed
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "mode-command" }
        bus.refresh()

        val outcome = bus.setManualSpecialists("city-1", false)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(false, transport.manualSpecialistRequests.single().enabled)
    }

    @Test
    fun citizenResetIsBoundToProjectedCity() = runBlocking {
        val initial = projection(7, "hash-7", cityQueue = emptyList())
        val committed = projection(8, "hash-8", cityQueue = emptyList())
        val transport = FakeTransport(initial).apply {
            onResetCitizens = { request ->
                current = committed
                accepted(request.commandId, 7, 8, "hash-8")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "reset-command" }
        bus.refresh()

        val outcome = bus.resetCitizens("city-1")

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals("reset-command", transport.resetCitizenRequests.single().commandId)
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

    @Test
    fun buildingSaleSendsOnlyCanonicalIdentityIntent() = runBlocking {
        val initial = projection(3, "hash-3", cityQueue = emptyList())
        val committed = projection(4, "hash-4", cityQueue = emptyList())
        val transport = FakeTransport(initial).apply {
            onSellBuilding = { request ->
                current = committed
                accepted(request.commandId, 3, 4, "hash-4")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "sale-command" }
        bus.refresh()

        assertTrue(bus.sellBuilding("city-1", "Monument") is AuthoritativeCommandOutcome.Accepted)

        val request = transport.sellBuildingRequests.single()
        assertEquals(ApiV3SellBuildingRequest(
            "sale-command", 3, "hash-3", "city-1", "Monument",
        ), request)
        val encoded = Json.encodeToString(ApiV3SellBuildingRequest.serializer(), request)
        assertTrue(!encoded.contains("refund"))
        assertTrue(!encoded.contains("price"))
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("can_sell"))
    }

    @Test
    fun cityGovernanceSendsAClosedActionWithoutRuleClaims() = runBlocking {
        val initial = projection(3, "hash-3", cityQueue = emptyList())
        val committed = projection(4, "hash-4", cityQueue = emptyList())
        val transport = FakeTransport(initial).apply {
            onSetCityGovernance = { request ->
                current = committed
                accepted(request.commandId, 3, 4, "hash-4")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "governance-command" }
        bus.refresh()

        assertTrue(bus.setCityGovernance(
            "city-1", CityGovernanceAction.StartRazing,
        ) is AuthoritativeCommandOutcome.Accepted)

        val request = transport.cityGovernanceRequests.single()
        assertEquals(ApiV3SetCityGovernanceRequest(
            "governance-command", 3, "hash-3", "city-1", CityGovernanceAction.StartRazing,
        ), request)
        val encoded = Json.encodeToString(ApiV3SetCityGovernanceRequest.serializer(), request)
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("can_destroy"))
        assertTrue(!encoded.contains("may_annex"))
        assertTrue(!encoded.contains("is_puppet"))
    }

    @Test
    fun cityDispositionSendsOnlyAProjectedClosedAction() = runBlocking {
        val decision = ProjectedCityDisposition(
            "city-1", "Athens", listOf(CityDispositionAction.Annex, CityDispositionAction.Puppet),
        )
        val initial = projection(3, "hash-3", pendingCityDispositions = listOf(decision))
        val committed = projection(4, "hash-4")
        val transport = FakeTransport(initial).apply {
            onResolveCityDisposition = { request ->
                current = committed
                accepted(request.commandId, 3, 4, "hash-4")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "disposition-command" }
        bus.refresh()

        assertTrue(bus.resolveCityDisposition(
            "city-1", CityDispositionAction.Puppet,
        ) is AuthoritativeCommandOutcome.Accepted)

        val request = transport.cityDispositionRequests.single()
        assertEquals(ApiV3ResolveCityDispositionRequest(
            "disposition-command", 3, "hash-3", "city-1", CityDispositionAction.Puppet,
        ), request)
        val encoded = Json.encodeToString(ApiV3ResolveCityDispositionRequest.serializer(), request)
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("original_owner"))
        assertTrue(!encoded.contains("may_annex"))
        assertTrue(!encoded.contains("can_raze"))
    }

    @Test
    fun diplomaticVoteIsBoundToTheProjectedCandidate() = runBlocking {
        val initial = projection(
            3,
            "hash-3",
            pendingTurnActions = listOf(PendingEndTurnAction.CastDiplomaticVote),
            diplomaticVoteCandidates = listOf("Greece"),
        )
        val committed = projection(4, "hash-4")
        val transport = FakeTransport(initial).apply {
            onCastDiplomaticVote = { request ->
                current = committed
                accepted(request.commandId, 3, 4, "hash-4")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "vote-command" }
        bus.refresh()

        assertTrue(bus.castDiplomaticVote("Greece") is AuthoritativeCommandOutcome.Accepted)

        val request = transport.diplomaticVoteRequests.single()
        assertEquals(ApiV3CastDiplomaticVoteRequest(
            "vote-command", 3, "hash-3", "Greece",
        ), request)
        val encoded = Json.encodeToString(ApiV3CastDiplomaticVoteRequest.serializer(), request)
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("known"))
        assertTrue(!encoded.contains("alive"))
        assertTrue(!encoded.contains("votes"))
    }

    @Test
    fun greatPersonChoiceIsBoundToTheProjectedUnit() = runBlocking {
        val initial = projection(
            3,
            "hash-3",
            pendingTurnActions = listOf(PendingEndTurnAction.PickGreatPerson),
            selectableGreatPeople = listOf("Great Scientist"),
        )
        val committed = projection(4, "hash-4")
        val transport = FakeTransport(initial).apply {
            onChooseGreatPerson = { request ->
                current = committed
                accepted(request.commandId, 3, 4, "hash-4")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "great-person-command" }
        bus.refresh()

        assertTrue(bus.chooseGreatPerson("Great Scientist") is AuthoritativeCommandOutcome.Accepted)

        val request = transport.greatPersonRequests.single()
        assertEquals(ApiV3ChooseGreatPersonRequest(
            "great-person-command", 3, "hash-3", "Great Scientist",
        ), request)
        val encoded = Json.encodeToString(ApiV3ChooseGreatPersonRequest.serializer(), request)
        assertTrue(!encoded.contains("actor"))
        assertTrue(!encoded.contains("capital"))
        assertTrue(!encoded.contains("maya"))
        assertTrue(!encoded.contains("free_great"))
    }

    @Test
    fun religiousUnitActionIsBoundToTheOwnedProjectedUnitAndClosedAction() = runBlocking {
        val initial = projection(
            3,
            "hash-3",
            ownUnits = listOf(ProjectedUnit(
                17, "Rome", "Missionary", 0, 0, 100, 2f,
                availableReligiousActions = listOf(ReligiousUnitAction.SpreadReligion),
            )),
        )
        val transport = FakeTransport(initial)
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "religion-command" }
        bus.refresh()

        assertTrue(bus.useReligiousUnit(17, ReligiousUnitAction.SpreadReligion)
            is AuthoritativeCommandOutcome.Accepted)

        val request = transport.religiousUnitRequests.single()
        assertEquals(ApiV3UseReligiousUnitRequest(
            "religion-command", 3, "hash-3", 17, ReligiousUnitAction.SpreadReligion,
        ), request)
        val encoded = Json.encodeToString(ApiV3UseReligiousUnitRequest.serializer(), request)
        for (forged in listOf("actor", "city", "religion_name", "pressure", "charges"))
            assertTrue(!encoded.contains(forged))
    }

    @Test
    fun religiousBeliefChoiceIsBoundToProjectedSlotsBeliefsAndIdentity() = runBlocking {
        val initial = projection(3, "hash-3").copy(
            projection = projection(3, "hash-3").projection.copy(
                religionChoice = ProjectedReligionChoice(
                    listOf(ReligiousBeliefType.Founder, ReligiousBeliefType.Follower),
                    listOf(
                        ProjectedReligiousBelief("Church Property", ReligiousBeliefType.Founder),
                        ProjectedReligiousBelief("Pagodas", ReligiousBeliefType.Follower),
                    ),
                    listOf("Buddhism"),
                    requiresReligionIdentity = true,
                ),
            ),
        )
        val transport = FakeTransport(initial)
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "belief-command" }
        bus.refresh()

        assertTrue(bus.chooseReligiousBeliefs(
            listOf("Church Property", "Pagodas"), "Buddhism", "The Middle Way",
        ) is AuthoritativeCommandOutcome.Accepted)

        val request = transport.religiousBeliefRequests.single()
        assertEquals(ApiV3ChooseReligiousBeliefsRequest(
            "belief-command", 3, "hash-3", listOf("Church Property", "Pagodas"),
            "Buddhism", "The Middle Way",
        ), request)
        val encoded = Json.encodeToString(ApiV3ChooseReligiousBeliefsRequest.serializer(), request)
        for (forged in listOf("actor", "belief_types", "free_beliefs", "faith_cost", "holy_city"))
            assertTrue(!encoded.contains(forged))
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

        val outcome = bus.setResearchPath("Writing", append = true)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals("Writing", transport.researchRequests.single().technologyName)
        assertTrue(transport.researchRequests.single().append)
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
    fun researchCompletionAcknowledgmentUsesOnlyAnOpaqueProjectedPrompt() = runBlocking {
        val prompt = ProjectedResearchCompletion("a".repeat(64), "Writing")
        val initial = projection(0, "hash-0", completionPrompts = listOf(prompt))
        val committed = projection(1, "hash-1")
        val transport = FakeTransport(initial).apply {
            onAcknowledgeResearchCompletion = { request ->
                current = committed
                accepted(request.commandId, 0, 1, "hash-1")
            }
        }
        val bus = AuthoritativeGameCommandBus(gameId, transport) { "research-ack-command" }
        bus.refresh()

        val outcome = bus.acknowledgeResearchCompletion(prompt.promptId)

        assertTrue(outcome is AuthoritativeCommandOutcome.Accepted)
        assertEquals(prompt.promptId, transport.researchCompletionRequests.single().promptId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun researchCompletionAcknowledgmentRejectsAnUnprojectedPrompt() = runBlocking {
        val bus = AuthoritativeGameCommandBus(gameId, FakeTransport(projection(0, "hash-0")))
        bus.refresh()
        bus.acknowledgeResearchCompletion("b".repeat(64))
        Unit
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
        val old = movementProjection(3, "hash-3", 1)
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
        val initial = movementProjection(0, "hash-0", 1)
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
        val initial = movementProjection(2, "hash-2", 99)
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
        completionPrompts: List<ProjectedResearchCompletion> = emptyList(),
        availableConstructions: List<String> = listOf("Monument"),
        exploredTiles: List<ProjectedTileVisibility> = emptyList(),
        assignableTiles: List<ProjectedCityTile> = emptyList(),
        specialists: List<ProjectedSpecialist> = emptyList(),
        avoidGrowth: Boolean = false,
        citizenFocus: CitizenFocus = CitizenFocus.NoFocus,
        selectableCitizenFocuses: List<CitizenFocus> = emptyList(),
        ownUnits: List<ProjectedUnit> = emptyList(),
        projectedCities: List<ProjectedCity>? = null,
        pendingCityDispositions: List<ProjectedCityDisposition> = emptyList(),
        pendingTurnActions: List<PendingEndTurnAction> = emptyList(),
        diplomaticVoteCandidates: List<String> = emptyList(),
        selectableGreatPeople: List<String> = emptyList(),
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
            pendingTurnActions = pendingTurnActions,
            research = ProjectedResearch(
                currentTechnology = null,
                researchedTechnologies = emptyList(),
                queue = emptyList(),
                queueEntries = emptyList(),
                overflowScience = 0,
                selectableTargets = listOf("Writing"),
                appendableTargets = listOf("Writing"),
                freeTechnologyChoices = freeTechnologyChoices,
                completionPrompts = completionPrompts,
            ),
            policies = ProjectedPolicies(25, 25, 0, emptyList(), listOf("Tradition")),
            gold = 0,
            knownCivilizations = emptyList(),
            ownCities = projectedCities ?: if (cityQueue == null) emptyList() else listOf(ProjectedCity(
                id = "city-1",
                name = "Rome",
                x = 0,
                y = 0,
                population = 1,
                health = 200,
                constructionQueue = cityQueue,
                availableConstructions = availableConstructions,
                assignableTiles = assignableTiles,
                specialists = specialists,
                avoidGrowth = avoidGrowth,
                citizenFocus = citizenFocus,
                selectableCitizenFocuses = selectableCitizenFocuses,
            )),
            ownUnits = ownUnits,
            exploredTiles = exploredTiles,
            visibleForeignUnits = emptyList(),
            pendingCityDispositions = pendingCityDispositions,
            diplomaticVoteCandidates = diplomaticVoteCandidates,
            selectableGreatPeople = selectableGreatPeople,
        ),
    )

    private fun movementProjection(revision: Long, hash: String, unitId: Int) = projection(
        revision = revision,
        hash = hash,
        exploredTiles = listOf(ProjectedTileVisibility(2, 3, visible = true)),
        ownUnits = listOf(ProjectedUnit(
            unitId, "Rome", "Warrior", 0, 0, 100, 2f,
            moveDestinations = listOf(ProjectedMovementDestination(2, 3)),
            swapDestinations = listOf(ProjectedMovementDestination(2, 3)),
        )),
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
        var projectionCalls = 0
        val resignRequests = mutableListOf<ApiV3ResignRequest>()
        val forceResignRequests = mutableListOf<ApiV3ForceResignRequest>()
        val kickRequests = mutableListOf<ApiV3KickMemberRequest>()
        var kickFailuresRemaining = 0
        val moveRequests = mutableListOf<ApiV3MoveUnitRequest>()
        val moveTowardRequests = mutableListOf<ApiV3MoveUnitTowardRequest>()
        val cancelMovementOrderRequests = mutableListOf<ApiV3CancelUnitMovementOrderRequest>()
        val unitExplorationRequests = mutableListOf<ApiV3SetUnitExplorationRequest>()
        val unitAutomationRequests = mutableListOf<ApiV3SetUnitAutomationRequest>()
        val unitPostureRequests = mutableListOf<ApiV3SetUnitPostureRequest>()
        val disbandUnitRequests = mutableListOf<ApiV3DisbandUnitRequest>()
        val pillageTileRequests = mutableListOf<ApiV3PillageTileRequest>()
        val foundCityRequests = mutableListOf<ApiV3FoundCityRequest>()
        val paradropUnitRequests = mutableListOf<ApiV3ParadropUnitRequest>()
        val attackWithUnitRequests = mutableListOf<ApiV3AttackWithUnitRequest>()
        val bombardWithCityRequests = mutableListOf<ApiV3BombardWithCityRequest>()
        val launchNuclearStrikeRequests = mutableListOf<ApiV3LaunchNuclearStrikeRequest>()
        val airSweepRequests = mutableListOf<ApiV3AirSweepRequest>()
        val upgradeUnitRequests = mutableListOf<ApiV3UpgradeUnitsRequest>()
        val promoteUnitRequests = mutableListOf<ApiV3PromoteUnitRequest>()
        val unitPromotionPreferenceRequests = mutableListOf<ApiV3SetCityUnitPromotionPreferenceRequest>()
        val renameUnitRequests = mutableListOf<ApiV3RenameUnitRequest>()
        val improvementOrderRequests = mutableListOf<ApiV3SetTileImprovementOrderRequest>()
        val roadConnectionOrderRequests = mutableListOf<ApiV3SetRoadConnectionOrderRequest>()
        val swapRequests = mutableListOf<ApiV3SwapUnitsRequest>()
        val queueRequests = mutableListOf<ApiV3QueueConstructionRequest>()
        val tileQueueRequests = mutableListOf<ApiV3QueueConstructionAtTileRequest>()
        val perpetualRequests = mutableListOf<ApiV3SetPerpetualConstructionRequest>()
        val removeConstructionRequests = mutableListOf<ApiV3RemoveConstructionRequest>()
        val moveConstructionRequests = mutableListOf<ApiV3MoveConstructionRequest>()
        val purchaseConstructionRequests = mutableListOf<ApiV3PurchaseConstructionRequest>()
        val tilePurchaseConstructionRequests = mutableListOf<ApiV3PurchaseConstructionAtTileRequest>()
        val buyCityTileRequests = mutableListOf<ApiV3BuyCityTileRequest>()
        val sellBuildingRequests = mutableListOf<ApiV3SellBuildingRequest>()
        val cityGovernanceRequests = mutableListOf<ApiV3SetCityGovernanceRequest>()
        val cityDispositionRequests = mutableListOf<ApiV3ResolveCityDispositionRequest>()
        val diplomaticVoteRequests = mutableListOf<ApiV3CastDiplomaticVoteRequest>()
        val greatPersonRequests = mutableListOf<ApiV3ChooseGreatPersonRequest>()
        val religiousUnitRequests = mutableListOf<ApiV3UseReligiousUnitRequest>()
        val religiousBeliefRequests = mutableListOf<ApiV3ChooseReligiousBeliefsRequest>()
        val cityTileAssignmentRequests = mutableListOf<ApiV3SetCityTileAssignmentRequest>()
        val specialistCountRequests = mutableListOf<ApiV3SetSpecialistCountRequest>()
        val manualSpecialistRequests = mutableListOf<ApiV3SetManualSpecialistsRequest>()
        val resetCitizenRequests = mutableListOf<ApiV3ResetCitizensRequest>()
        val avoidGrowthRequests = mutableListOf<ApiV3SetAvoidGrowthRequest>()
        val citizenFocusRequests = mutableListOf<ApiV3SetCitizenFocusRequest>()
        val researchRequests = mutableListOf<ApiV3SetResearchPathRequest>()
        val policyRequests = mutableListOf<ApiV3AdoptPolicyRequest>()
        val freeTechnologyRequests = mutableListOf<ApiV3ChooseFreeTechnologyRequest>()
        val researchCompletionRequests = mutableListOf<ApiV3AcknowledgeResearchCompletionRequest>()
        var onMove: suspend (ApiV3MoveUnitRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onMoveToward: suspend (ApiV3MoveUnitTowardRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onCancelMovementOrder: suspend (ApiV3CancelUnitMovementOrderRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetUnitExploration: suspend (ApiV3SetUnitExplorationRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetUnitAutomation: suspend (ApiV3SetUnitAutomationRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetUnitPosture: suspend (ApiV3SetUnitPostureRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onDisbandUnit: suspend (ApiV3DisbandUnitRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onPillageTile: suspend (ApiV3PillageTileRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onFoundCity: suspend (ApiV3FoundCityRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onParadropUnit: suspend (ApiV3ParadropUnitRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onAttackWithUnit: suspend (ApiV3AttackWithUnitRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onBombardWithCity: suspend (ApiV3BombardWithCityRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onLaunchNuclearStrike: suspend (ApiV3LaunchNuclearStrikeRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onAirSweep: suspend (ApiV3AirSweepRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onUpgradeUnits: suspend (ApiV3UpgradeUnitsRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onPromoteUnit: suspend (ApiV3PromoteUnitRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onUnitPromotionPreference: suspend (ApiV3SetCityUnitPromotionPreferenceRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onRenameUnit: suspend (ApiV3RenameUnitRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onImprovementOrder: suspend (ApiV3SetTileImprovementOrderRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onRoadConnectionOrder: suspend (ApiV3SetRoadConnectionOrderRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSwapUnits: suspend (ApiV3SwapUnitsRequest) -> ApiV3CommandAccepted = {
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
        var onSellBuilding: suspend (ApiV3SellBuildingRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetCityGovernance: suspend (ApiV3SetCityGovernanceRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onResolveCityDisposition: suspend (ApiV3ResolveCityDispositionRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onCastDiplomaticVote: suspend (ApiV3CastDiplomaticVoteRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onChooseGreatPerson: suspend (ApiV3ChooseGreatPersonRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetCityTileAssignment: suspend (ApiV3SetCityTileAssignmentRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetSpecialistCount: suspend (ApiV3SetSpecialistCountRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetManualSpecialists: suspend (ApiV3SetManualSpecialistsRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onResetCitizens: suspend (ApiV3ResetCitizensRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetAvoidGrowth: suspend (ApiV3SetAvoidGrowthRequest) -> ApiV3CommandAccepted = {
            accepted(it.commandId, it.expectedRevision, it.expectedRevision + 1, "unused")
        }
        var onSetCitizenFocus: suspend (ApiV3SetCitizenFocusRequest) -> ApiV3CommandAccepted = {
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
        var onAcknowledgeResearchCompletion: suspend (ApiV3AcknowledgeResearchCompletionRequest) -> ApiV3CommandAccepted = {
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
        override suspend fun listPlayerInvitations() = emptyList<ApiV3PlayerInvitation>()
        override suspend fun invitePlayer(gameId: String, request: ApiV3InvitePlayerRequest) = Unit
        override suspend fun createGame(rulesetManifestHash: String) =
            ApiV3GameMetadata(gameId, 0, "hash-0", "owner", "Rome")
        override suspend fun joinGame(gameId: String, request: ApiV3JoinGameRequest) =
            accepted(request.commandId, request.expectedRevision, request.expectedRevision + 1, "unused")
        override suspend fun projection(gameId: String): ApiV3GameProjection {
            projectionCalls++
            return current
        }
        override suspend fun spectatorProjection(gameId: String): ApiV3SpectatorGameProjection =
            error("not used by command-bus tests")
        override suspend fun addSpectator(gameId: String, username: String) = Unit
        override suspend fun leaveSpectator(gameId: String) = Unit
        override suspend fun transferOwnership(gameId: String, request: ApiV3TransferOwnershipRequest) = Unit
        override suspend fun closeGameAdmin(gameId: String, request: ApiV3GameAdminOperationRequest) = Unit
        override suspend fun archiveGame(gameId: String, request: ApiV3GameAdminOperationRequest) = Unit
        override suspend fun moveUnit(gameId: String, request: ApiV3MoveUnitRequest): ApiV3CommandAccepted {
            moveRequests += request
            return onMove(request)
        }
        override suspend fun moveUnitToward(
            gameId: String,
            request: ApiV3MoveUnitTowardRequest,
        ): ApiV3CommandAccepted {
            moveTowardRequests += request
            return onMoveToward(request)
        }
        override suspend fun cancelUnitMovementOrder(
            gameId: String,
            request: ApiV3CancelUnitMovementOrderRequest,
        ): ApiV3CommandAccepted {
            cancelMovementOrderRequests += request
            return onCancelMovementOrder(request)
        }
        override suspend fun setUnitExploration(
            gameId: String,
            request: ApiV3SetUnitExplorationRequest,
        ): ApiV3CommandAccepted {
            unitExplorationRequests += request
            return onSetUnitExploration(request)
        }
        override suspend fun setUnitAutomation(
            gameId: String,
            request: ApiV3SetUnitAutomationRequest,
        ): ApiV3CommandAccepted {
            unitAutomationRequests += request
            return onSetUnitAutomation(request)
        }
        override suspend fun setUnitPosture(
            gameId: String,
            request: ApiV3SetUnitPostureRequest,
        ): ApiV3CommandAccepted {
            unitPostureRequests += request
            return onSetUnitPosture(request)
        }
        override suspend fun disbandUnit(
            gameId: String,
            request: ApiV3DisbandUnitRequest,
        ): ApiV3CommandAccepted {
            disbandUnitRequests += request
            return onDisbandUnit(request)
        }
        override suspend fun pillageTile(
            gameId: String,
            request: ApiV3PillageTileRequest,
        ): ApiV3CommandAccepted {
            pillageTileRequests += request
            return onPillageTile(request)
        }
        override suspend fun foundCity(
            gameId: String,
            request: ApiV3FoundCityRequest,
        ): ApiV3CommandAccepted {
            foundCityRequests += request
            return onFoundCity(request)
        }
        override suspend fun paradropUnit(
            gameId: String,
            request: ApiV3ParadropUnitRequest,
        ): ApiV3CommandAccepted {
            paradropUnitRequests += request
            return onParadropUnit(request)
        }
        override suspend fun attackWithUnit(
            gameId: String,
            request: ApiV3AttackWithUnitRequest,
        ): ApiV3CommandAccepted {
            attackWithUnitRequests += request
            return onAttackWithUnit(request)
        }
        override suspend fun bombardWithCity(
            gameId: String,
            request: ApiV3BombardWithCityRequest,
        ): ApiV3CommandAccepted {
            bombardWithCityRequests += request
            return onBombardWithCity(request)
        }
        override suspend fun launchNuclearStrike(
            gameId: String,
            request: ApiV3LaunchNuclearStrikeRequest,
        ): ApiV3CommandAccepted {
            launchNuclearStrikeRequests += request
            return onLaunchNuclearStrike(request)
        }
        override suspend fun airSweep(
            gameId: String,
            request: ApiV3AirSweepRequest,
        ): ApiV3CommandAccepted {
            airSweepRequests += request
            return onAirSweep(request)
        }
        override suspend fun upgradeUnits(
            gameId: String,
            request: ApiV3UpgradeUnitsRequest,
        ): ApiV3CommandAccepted {
            upgradeUnitRequests += request
            return onUpgradeUnits(request)
        }
        override suspend fun promoteUnit(
            gameId: String,
            request: ApiV3PromoteUnitRequest,
        ): ApiV3CommandAccepted {
            promoteUnitRequests += request
            return onPromoteUnit(request)
        }
        override suspend fun setCityUnitPromotionPreference(
            gameId: String,
            request: ApiV3SetCityUnitPromotionPreferenceRequest,
        ): ApiV3CommandAccepted {
            unitPromotionPreferenceRequests += request
            return onUnitPromotionPreference(request)
        }
        override suspend fun renameUnit(
            gameId: String,
            request: ApiV3RenameUnitRequest,
        ): ApiV3CommandAccepted {
            renameUnitRequests += request
            return onRenameUnit(request)
        }
        override suspend fun setTileImprovementOrder(
            gameId: String,
            request: ApiV3SetTileImprovementOrderRequest,
        ): ApiV3CommandAccepted {
            improvementOrderRequests += request
            return onImprovementOrder(request)
        }
        override suspend fun setRoadConnectionOrder(
            gameId: String,
            request: ApiV3SetRoadConnectionOrderRequest,
        ): ApiV3CommandAccepted {
            roadConnectionOrderRequests += request
            return onRoadConnectionOrder(request)
        }
        override suspend fun swapUnits(
            gameId: String,
            request: ApiV3SwapUnitsRequest,
        ): ApiV3CommandAccepted {
            swapRequests += request
            return onSwapUnits(request)
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
        override suspend fun sellBuilding(
            gameId: String,
            request: ApiV3SellBuildingRequest,
        ): ApiV3CommandAccepted {
            sellBuildingRequests += request
            return onSellBuilding(request)
        }
        override suspend fun setCityGovernance(
            gameId: String,
            request: ApiV3SetCityGovernanceRequest,
        ): ApiV3CommandAccepted {
            cityGovernanceRequests += request
            return onSetCityGovernance(request)
        }
        override suspend fun resolveCityDisposition(
            gameId: String,
            request: ApiV3ResolveCityDispositionRequest,
        ): ApiV3CommandAccepted {
            cityDispositionRequests += request
            return onResolveCityDisposition(request)
        }
        override suspend fun castDiplomaticVote(
            gameId: String,
            request: ApiV3CastDiplomaticVoteRequest,
        ): ApiV3CommandAccepted {
            diplomaticVoteRequests += request
            return onCastDiplomaticVote(request)
        }
        override suspend fun chooseGreatPerson(
            gameId: String,
            request: ApiV3ChooseGreatPersonRequest,
        ): ApiV3CommandAccepted {
            greatPersonRequests += request
            return onChooseGreatPerson(request)
        }
        override suspend fun useReligiousUnit(
            gameId: String,
            request: ApiV3UseReligiousUnitRequest,
        ): ApiV3CommandAccepted {
            religiousUnitRequests += request
            current = current.copy(
                committedRevision = request.expectedRevision + 1,
                canonicalStateHash = "religious-unit-hash",
                projectionHash = "religious-unit-projection-hash",
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                request.expectedRevision + 1, "religious-unit-hash",
            )
        }
        override suspend fun chooseReligiousBeliefs(
            gameId: String,
            request: ApiV3ChooseReligiousBeliefsRequest,
        ): ApiV3CommandAccepted {
            religiousBeliefRequests += request
            current = current.copy(
                committedRevision = request.expectedRevision + 1,
                canonicalStateHash = "religious-beliefs-hash",
                projectionHash = "religious-beliefs-projection-hash",
                projection = current.projection.copy(religionChoice = null),
            )
            return ApiV3CommandAccepted(
                gameId, request.commandId, request.expectedRevision,
                request.expectedRevision + 1, "religious-beliefs-hash",
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
        override suspend fun transformUnit(gameId: String, request: ApiV3TransformUnitRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun triggerUnitUnique(gameId: String, request: ApiV3TriggerUnitUniqueRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)
        override suspend fun resolveEventChoice(gameId: String, request: ApiV3ResolveEventChoiceRequest) = acceptTradeTestCommand(gameId, request.commandId, request.expectedRevision)

        private fun acceptTradeTestCommand(gameId: String, commandId: String, revision: Long): ApiV3CommandAccepted {
            current = current.copy(committedRevision = revision + 1, canonicalStateHash = "trade-hash", projectionHash = "trade-projection-hash")
            return ApiV3CommandAccepted(gameId, commandId, revision, revision + 1, "trade-hash")
        }
        override suspend fun setCityTileAssignment(
            gameId: String,
            request: ApiV3SetCityTileAssignmentRequest,
        ): ApiV3CommandAccepted {
            cityTileAssignmentRequests += request
            return onSetCityTileAssignment(request)
        }
        override suspend fun setSpecialistCount(
            gameId: String,
            request: ApiV3SetSpecialistCountRequest,
        ): ApiV3CommandAccepted {
            specialistCountRequests += request
            return onSetSpecialistCount(request)
        }
        override suspend fun setManualSpecialists(
            gameId: String,
            request: ApiV3SetManualSpecialistsRequest,
        ): ApiV3CommandAccepted {
            manualSpecialistRequests += request
            return onSetManualSpecialists(request)
        }
        override suspend fun resetCitizens(
            gameId: String,
            request: ApiV3ResetCitizensRequest,
        ): ApiV3CommandAccepted {
            resetCitizenRequests += request
            return onResetCitizens(request)
        }
        override suspend fun setAvoidGrowth(
            gameId: String,
            request: ApiV3SetAvoidGrowthRequest,
        ): ApiV3CommandAccepted {
            avoidGrowthRequests += request
            return onSetAvoidGrowth(request)
        }
        override suspend fun setCitizenFocus(
            gameId: String,
            request: ApiV3SetCitizenFocusRequest,
        ): ApiV3CommandAccepted {
            citizenFocusRequests += request
            return onSetCitizenFocus(request)
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
        override suspend fun acknowledgeResearchCompletion(
            gameId: String,
            request: ApiV3AcknowledgeResearchCompletionRequest,
        ): ApiV3CommandAccepted {
            researchCompletionRequests += request
            return onAcknowledgeResearchCompletion(request)
        }
        override suspend fun endTurn(gameId: String, request: ApiV3EndTurnRequest) =
            accepted(request.commandId, request.expectedRevision, request.expectedRevision + 1, "unused")
        override suspend fun resign(gameId: String, request: ApiV3ResignRequest): ApiV3CommandAccepted {
            resignRequests += request
            return accepted(request.commandId, request.expectedRevision, request.expectedRevision + 1, "resigned")
        }
        override suspend fun forceResign(
            gameId: String,
            request: ApiV3ForceResignRequest,
        ): ApiV3CommandAccepted {
            forceResignRequests += request
            current = current.copy(
                committedRevision = request.expectedRevision + 1,
                canonicalStateHash = "force-resigned",
            )
            return accepted(
                request.commandId,
                request.expectedRevision,
                request.expectedRevision + 1,
                "force-resigned",
            )
        }
        override suspend fun kickMember(
            gameId: String,
            request: ApiV3KickMemberRequest,
        ): ApiV3CommandAccepted {
            kickRequests += request
            if (kickFailuresRemaining-- > 0) throw IOException("lost response")
            current = current.copy(
                committedRevision = request.expectedRevision + 1,
                canonicalStateHash = "kicked",
            )
            return accepted(
                request.commandId,
                request.expectedRevision,
                request.expectedRevision + 1,
                "kicked",
            )
        }
        override fun notifications(): Flow<ApiV3RevisionNotification> = emptyFlow()
    }
}
