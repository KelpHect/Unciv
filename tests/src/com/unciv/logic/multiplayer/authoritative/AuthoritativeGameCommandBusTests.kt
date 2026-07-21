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

        assertTrue(bus.setUnitPosture(42, UnitPosture.Fortify) is AuthoritativeCommandOutcome.Accepted)
        val request = transport.unitPostureRequests.single()
        assertEquals("posture-command", request.commandId)
        assertEquals(42, request.unitId)
        assertEquals(UnitPosture.Fortify, request.posture)
        val encoded = Json.encodeToString(ApiV3SetUnitPostureRequest.serializer(), request)
        assertTrue(encoded.contains("\"posture\":\"fortify\""))
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
        availableConstructions: List<String> = listOf("Monument"),
        exploredTiles: List<ProjectedTileVisibility> = emptyList(),
        assignableTiles: List<ProjectedCityTile> = emptyList(),
        specialists: List<ProjectedSpecialist> = emptyList(),
        avoidGrowth: Boolean = false,
        citizenFocus: CitizenFocus = CitizenFocus.NoFocus,
        selectableCitizenFocuses: List<CitizenFocus> = emptyList(),
        ownUnits: List<ProjectedUnit> = emptyList(),
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
                assignableTiles = assignableTiles,
                specialists = specialists,
                avoidGrowth = avoidGrowth,
                citizenFocus = citizenFocus,
                selectableCitizenFocuses = selectableCitizenFocuses,
            )),
            ownUnits = ownUnits,
            exploredTiles = exploredTiles,
            visibleForeignUnits = emptyList(),
        ),
    )

    private fun movementProjection(revision: Long, hash: String, unitId: Int) = projection(
        revision = revision,
        hash = hash,
        exploredTiles = listOf(ProjectedTileVisibility(2, 3, visible = true)),
        ownUnits = listOf(ProjectedUnit(unitId, "Rome", "Warrior", 0, 0, 100, 2f)),
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
        val moveTowardRequests = mutableListOf<ApiV3MoveUnitTowardRequest>()
        val cancelMovementOrderRequests = mutableListOf<ApiV3CancelUnitMovementOrderRequest>()
        val unitExplorationRequests = mutableListOf<ApiV3SetUnitExplorationRequest>()
        val unitAutomationRequests = mutableListOf<ApiV3SetUnitAutomationRequest>()
        val unitPostureRequests = mutableListOf<ApiV3SetUnitPostureRequest>()
        val disbandUnitRequests = mutableListOf<ApiV3DisbandUnitRequest>()
        val upgradeUnitRequests = mutableListOf<ApiV3UpgradeUnitsRequest>()
        val swapRequests = mutableListOf<ApiV3SwapUnitsRequest>()
        val queueRequests = mutableListOf<ApiV3QueueConstructionRequest>()
        val tileQueueRequests = mutableListOf<ApiV3QueueConstructionAtTileRequest>()
        val perpetualRequests = mutableListOf<ApiV3SetPerpetualConstructionRequest>()
        val removeConstructionRequests = mutableListOf<ApiV3RemoveConstructionRequest>()
        val moveConstructionRequests = mutableListOf<ApiV3MoveConstructionRequest>()
        val purchaseConstructionRequests = mutableListOf<ApiV3PurchaseConstructionRequest>()
        val tilePurchaseConstructionRequests = mutableListOf<ApiV3PurchaseConstructionAtTileRequest>()
        val buyCityTileRequests = mutableListOf<ApiV3BuyCityTileRequest>()
        val cityTileAssignmentRequests = mutableListOf<ApiV3SetCityTileAssignmentRequest>()
        val specialistCountRequests = mutableListOf<ApiV3SetSpecialistCountRequest>()
        val manualSpecialistRequests = mutableListOf<ApiV3SetManualSpecialistsRequest>()
        val resetCitizenRequests = mutableListOf<ApiV3ResetCitizensRequest>()
        val avoidGrowthRequests = mutableListOf<ApiV3SetAvoidGrowthRequest>()
        val citizenFocusRequests = mutableListOf<ApiV3SetCitizenFocusRequest>()
        val researchRequests = mutableListOf<ApiV3SetResearchPathRequest>()
        val policyRequests = mutableListOf<ApiV3AdoptPolicyRequest>()
        val freeTechnologyRequests = mutableListOf<ApiV3ChooseFreeTechnologyRequest>()
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
        var onUpgradeUnits: suspend (ApiV3UpgradeUnitsRequest) -> ApiV3CommandAccepted = {
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
        override suspend fun upgradeUnits(
            gameId: String,
            request: ApiV3UpgradeUnitsRequest,
        ): ApiV3CommandAccepted {
            upgradeUnitRequests += request
            return onUpgradeUnits(request)
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
        override suspend fun endTurn(gameId: String, request: ApiV3EndTurnRequest) =
            accepted(request.commandId, request.expectedRevision, request.expectedRevision + 1, "unused")
        override fun notifications(): Flow<ApiV3RevisionNotification> = emptyFlow()
    }
}
