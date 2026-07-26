package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthoritativeWorldControllerTests {
    @Test
    fun projectedUnitMovesOnlyToServerAdvertisedDestination() = runBlocking {
        val initial = gameProjection(7)
        val moved = gameProjection(8).copy(
            projection = initial.projection.copy(
                ownUnits = initial.projection.ownUnits.map {
                    it.copy(x = 2, y = -1, moveDestinations = emptyList())
                },
            ),
        )
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val controller = controller(initial, move = { unitId, x, y ->
            calls += Triple(unitId, x, y)
            AuthoritativeCommandOutcome.Accepted(
                ApiV3CommandAccepted(initial.gameId, "command", 7, 8, "hash-8"),
                moved,
            )
        })
        val unit = initial.projection.ownUnits.single()
        val destination = unit.moveDestinations.single()

        controller.selectUnit(unit.id)
        assertTrue(controller.canMoveSelectedTo(destination.x, destination.y))
        controller.moveSelectedTo(destination.x, destination.y)

        assertEquals(listOf(Triple(unit.id, destination.x, destination.y)), calls)
        assertEquals(8, controller.current.committedRevision)
        assertEquals(destination.x, controller.selectedUnit()!!.x)
    }

    @Test
    fun unadvertisedDestinationNeverCallsTransport() = runBlocking {
        var calls = 0
        val controller = controller(gameProjection(7), move = { _, _, _ ->
            calls++
            AuthoritativeCommandOutcome.Rejected("unexpected")
        })
        controller.selectUnit(controller.projection.ownUnits.single().id)

        assertThrows<IllegalArgumentException> { controller.moveSelectedTo(99, 99) }
        assertEquals(0, calls)
    }

    @Test
    fun ambiguousMoveRemainsRetryableWithoutLocalPrediction() = runBlocking {
        var calls = 0
        val initial = gameProjection(7)
        val controller = controller(initial, move = { _, _, _ ->
            calls++
            AuthoritativeCommandOutcome.RetryRequired
        })
        val unit = controller.projection.ownUnits.single()
        val destination = unit.moveDestinations.single()
        controller.selectUnit(unit.id)

        controller.moveSelectedTo(destination.x, destination.y)
        assertEquals(AuthoritativeWorldStatus.RetryRequired, controller.status)
        assertEquals(7, controller.current.committedRevision)
        controller.moveSelectedTo(destination.x, destination.y)

        assertEquals(2, calls)
        assertEquals(7, controller.current.committedRevision)
    }

    @Test
    fun endTurnRequiresServerProjectedReadiness() = runBlocking {
        var calls = 0
        val blocked = gameProjection(7)
        val controller = controller(blocked, endTurn = {
            calls++
            AuthoritativeCommandOutcome.Rejected("unexpected")
        })

        assertFalse(controller.canEndTurn())
        assertThrows<IllegalArgumentException> { controller.submitEndTurn() }
        assertEquals(0, calls)
    }

    @Test
    fun refreshRejectsBackwardRevision() = runBlocking {
        val initial = gameProjection(7)
        val controller = controller(initial, refresh = { gameProjection(6) })

        assertThrows<IllegalArgumentException> { controller.refresh() }
        assertEquals(7, controller.current.committedRevision)
        assertEquals(AuthoritativeWorldStatus.Rejected("refresh_failed"), controller.status)
    }

    @Test
    fun projectedResearchAndPolicyChoicesAreSubmittedWithoutLocalMutation() = runBlocking {
        val initial = gameProjection(7)
        val calls = mutableListOf<String>()
        val controller = controller(
            initial,
            setResearch = { technology, append ->
                calls += "research:$technology:$append"
                accepted(initial, 8)
            },
            adoptPolicy = { policy ->
                calls += "policy:$policy"
                accepted(initial, 8)
            },
            acknowledgeResearch = { prompt ->
                calls += "ack:$prompt"
                accepted(initial, 8)
            },
        )

        controller.selectResearch("Archery", append = false)
        assertEquals(listOf("research:Archery:false"), calls)
        assertEquals(8, controller.current.committedRevision)

        val policyController = controller(
            initial,
            adoptPolicy = { policy ->
                calls += "policy:$policy"
                accepted(initial, 8)
            },
        )
        policyController.adoptProjectedPolicy("Tradition")
        assertEquals("policy:Tradition", calls.last())

        val prompt = initial.projection.research.completionPrompts.single().promptId
        val completionController = controller(
            initial,
            acknowledgeResearch = { promptId ->
                calls += "ack:$promptId"
                accepted(initial, 8)
            },
        )
        completionController.acknowledgeProjectedResearchCompletion(prompt)
        assertEquals("ack:$prompt", calls.last())
    }

    @Test
    fun unadvertisedWorldDecisionNeverCallsTransport() = runBlocking {
        var calls = 0
        val controller = controller(
            gameProjection(7),
            setResearch = { _, _ ->
                calls++
                AuthoritativeCommandOutcome.Rejected("unexpected")
            },
            adoptPolicy = {
                calls++
                AuthoritativeCommandOutcome.Rejected("unexpected")
            },
        )

        assertThrows<IllegalArgumentException> {
            controller.selectResearch("Secret Future Tech", append = false)
        }
        assertThrows<IllegalArgumentException> {
            controller.adoptProjectedPolicy("Secret Policy")
        }
        assertEquals(0, calls)
    }

    @Test
    fun projectedQueueAndFreeTechnologyActionsUseExactAdvertisedIdentity() = runBlocking {
        val base = gameProjection(7)
        val initial = base.copy(
            projection = base.projection.copy(
                research = base.projection.research.copy(
                    freeTechnologyChoices = listOf("Archery"),
                ),
            ),
        )
        val calls = mutableListOf<String>()
        val queueController = AuthoritativeWorldController(
            initial = initial,
            refreshProjection = { initial },
            moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
            endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
            manageResearch = { technology, index, action ->
                calls += "queue:$technology:$index:$action"
                accepted(initial, 8)
            },
            chooseFreeTechnology = { technology ->
                calls += "free:$technology"
                accepted(initial, 8)
            },
        )
        val queueIndex = initial.projection.research.queueEntries.indexOfFirst {
            ResearchQueueAction.Remove in it.availableActions
        }
        val queueEntry = initial.projection.research.queueEntries[queueIndex]

        queueController.manageResearchQueue(
            queueEntry.technologyName,
            queueIndex,
            ResearchQueueAction.Remove,
        )
        assertEquals(
            "queue:${queueEntry.technologyName}:$queueIndex:${ResearchQueueAction.Remove}",
            calls.single(),
        )

        val freeController = AuthoritativeWorldController(
            initial = initial,
            refreshProjection = { initial },
            moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
            endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
            chooseFreeTechnology = { technology ->
                calls += "free:$technology"
                accepted(initial, 8)
            },
        )
        freeController.chooseProjectedFreeTechnology("Archery")
        assertEquals("free:Archery", calls.last())
    }

    @Test
    fun projectedConstructionKindRoutesOrdinaryPerpetualAndPlacedCommands() = runBlocking {
        val initial = gameProjection(7)
        val calls = mutableListOf<String>()
        val ordinaryController = controller(
            initial,
            queueConstruction = { cityId, construction ->
                calls += "queue:$cityId:$construction"
                accepted(initial, 8)
            },
        )

        ordinaryController.cityEconomy.selectConstruction("city-rome", "Archer")
        assertEquals("queue:city-rome:Archer", calls.single())

        val perpetualController = controller(
            initial,
            perpetualConstruction = { cityId, construction ->
                calls += "perpetual:$cityId:$construction"
                accepted(initial, 8)
            },
        )
        perpetualController.cityEconomy.selectConstruction("city-rome", "Nothing")
        assertEquals("perpetual:city-rome:Nothing", calls.last())

        val target = ProjectedTargetCoordinate(3, -1)
        val city = initial.projection.ownCities.single()
        val placed = initial.copy(
            projection = initial.projection.copy(
                ownCities = listOf(city.copy(
                    constructionOptions = city.constructionOptions.map { option ->
                        if (option.name == "Archer") option.copy(
                            placementTargets = listOf(target),
                        ) else option
                    },
                )),
            ),
        )
        val placedController = controller(
            placed,
            queueConstructionAtTile = { cityId, construction, x, y ->
                calls += "placed:$cityId:$construction:$x:$y"
                accepted(placed, 8)
            },
        )
        placedController.cityEconomy.selectConstruction("city-rome", "Archer", target)
        assertEquals("placed:city-rome:Archer:3:-1", calls.last())
    }

    @Test
    fun inventedConstructionOrPlacementNeverCallsTransport() = runBlocking {
        var calls = 0
        val controller = controller(
            gameProjection(7),
            queueConstruction = { _, _ ->
                calls++
                AuthoritativeCommandOutcome.Rejected("unexpected")
            },
            queueConstructionAtTile = { _, _, _, _ ->
                calls++
                AuthoritativeCommandOutcome.Rejected("unexpected")
            },
            perpetualConstruction = { _, _ ->
                calls++
                AuthoritativeCommandOutcome.Rejected("unexpected")
            },
        )

        assertThrows<IllegalStateException> {
            controller.cityEconomy.selectConstruction("city-rome", "Secret Wonder")
        }
        assertThrows<IllegalArgumentException> {
            controller.cityEconomy.selectConstruction(
                "city-rome",
                "Archer",
                ProjectedTargetCoordinate(99, 99),
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun projectedCityQueueManagementAndPurchaseUseExactIdentities() = runBlocking {
        val initial = gameProjection(7)
        val calls = mutableListOf<String>()
        val controller = controller(
            initial,
            removeConstruction = { cityId, index, name ->
                calls += "remove:$cityId:$index:$name"
                accepted(initial, 8)
            },
            manageConstruction = { cityId, name, index, action ->
                calls += "manage:$cityId:$name:$index:$action"
                accepted(initial, 8)
            },
            purchaseConstruction = { cityId, name, currency, index ->
                calls += "purchase:$cityId:$name:$currency:$index"
                accepted(initial, 8)
            },
        )

        controller.cityEconomy.removeConstruction("city-rome", 0, "Monument")
        assertEquals("remove:city-rome:0:Monument", calls.single())

        val manageController = controller(
            initial,
            manageConstruction = { cityId, name, index, action ->
                calls += "manage:$cityId:$name:$index:$action"
                accepted(initial, 8)
            },
        )
        manageController.cityEconomy.manageQueues(
            "city-rome",
            "Archer",
            null,
            ConstructionQueueAction.AddToTop,
        )
        assertEquals(
            "manage:city-rome:Archer:null:${ConstructionQueueAction.AddToTop}",
            calls.last(),
        )

        val purchaseController = controller(
            initial,
            purchaseConstruction = { cityId, name, currency, index ->
                calls += "purchase:$cityId:$name:$currency:$index"
                accepted(initial, 8)
            },
        )
        purchaseController.cityEconomy.purchase(
            "city-rome", "Archer", "Gold", queueIndex = null,
        )
        assertEquals("purchase:city-rome:Archer:Gold:null", calls.last())
    }

    @Test
    fun projectedAdjacentQueueMoveAndTilePurchaseAreBounded() = runBlocking {
        val base = gameProjection(7)
        val city = base.projection.ownCities.single()
        val target = ProjectedTargetCoordinate(3, -1)
        val tilePurchase = city.constructionOptions.first().purchases.single().copy(
            requiresTile = true,
            legalTargets = listOf(target),
        )
        val projected = base.copy(
            projection = base.projection.copy(
                ownCities = listOf(city.copy(
                    constructionQueueEntries = city.constructionQueueEntries +
                        city.constructionQueueEntries.single().copy(name = "Granary"),
                    constructionQueue = listOf("Monument", "Granary"),
                    constructionOptions = city.constructionOptions.map { option ->
                        if (option.name == "Archer") {
                            option.copy(purchases = listOf(tilePurchase))
                        } else option
                    },
                )),
            ),
        )
        val calls = mutableListOf<String>()
        val moveController = controller(
            projected,
            moveConstruction = { cityId, from, to, name ->
                calls += "move:$cityId:$from:$to:$name"
                accepted(projected, 8)
            },
        )
        moveController.cityEconomy.moveConstruction("city-rome", 1, 0, "Granary")
        assertEquals("move:city-rome:1:0:Granary", calls.single())

        val purchaseController = controller(
            projected,
            purchaseConstructionAtTile = { cityId, name, currency, x, y, index ->
                calls += "tile:$cityId:$name:$currency:$x:$y:$index"
                accepted(projected, 8)
            },
        )
        purchaseController.cityEconomy.purchase(
            "city-rome", "Archer", "Gold", null, target,
        )
        assertEquals("tile:city-rome:Archer:Gold:3:-1:null", calls.last())
    }

    @Test
    fun unadvertisedCityEconomyInputsAndAmbiguousRetryNeverMutateProjection() = runBlocking {
        val initial = gameProjection(7)
        var calls = 0
        val controller = controller(
            initial,
            removeConstruction = { _, _, _ ->
                calls++
                AuthoritativeCommandOutcome.RetryRequired
            },
            purchaseConstruction = { _, _, _, _ ->
                calls++
                AuthoritativeCommandOutcome.Rejected("unexpected")
            },
        )

        assertThrows<IllegalStateException> {
            controller.cityEconomy.purchase(
                "city-rome", "Archer", "Faith", queueIndex = null,
            )
        }
        assertThrows<IllegalStateException> {
            controller.cityEconomy.removeConstruction("city-rome", 0, "Granary")
        }
        assertEquals(0, calls)

        controller.cityEconomy.removeConstruction("city-rome", 0, "Monument")
        assertEquals(AuthoritativeWorldStatus.RetryRequired, controller.status)
        assertEquals(7, controller.current.committedRevision)
        controller.cityEconomy.removeConstruction("city-rome", 0, "Monument")
        assertEquals(2, calls)
        assertEquals(7, controller.current.committedRevision)
    }

    @Test
    fun projectionWorldHasNoCanonicalOrLegacySaveDependency() {
        val sources = listOf(
            sourceFile(
                "core/src/com/unciv/logic/multiplayer/authoritative/" +
                    "AuthoritativeWorldController.kt",
            ),
            sourceFile(
                "core/src/com/unciv/logic/multiplayer/authoritative/" +
                    "AuthoritativeCityEconomyController.kt",
            ),
            sourceFile(
                "core/src/com/unciv/logic/multiplayer/authoritative/" +
                    "AuthoritativeCityControlController.kt",
            ),
            sourceFile(
                "core/src/com/unciv/logic/multiplayer/authoritative/" +
                    "AuthoritativeCombatController.kt",
            ),
            sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                    "AuthoritativeWorldScreen.kt",
            ),
            sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                    "AuthoritativeWorldDecisions.kt",
            ),
            sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                    "AuthoritativeCityEconomyPanel.kt",
            ),
            sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                    "AuthoritativeCityControlPanel.kt",
            ),
            sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                    "AuthoritativeCombatPanel.kt",
            ),
            sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                    "AuthoritativeWorldSessionActions.kt",
            ),
        ).joinToString("\n") { it.readText() }

        for (forbidden in listOf(
            "import com.unciv.logic.GameInfo",
            "import com.unciv.ui.screens.worldscreen.WorldScreen",
            "multiplayerFiles",
            "GameStarter",
        )) {
            assertFalse("Projection world must not reference $forbidden", sources.contains(forbidden))
        }
    }

    private fun controller(
        initial: ApiV3GameProjection,
        refresh: suspend () -> ApiV3GameProjection = { initial },
        move: suspend (Int, Int, Int) -> AuthoritativeCommandOutcome? =
            { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn: suspend () -> AuthoritativeCommandOutcome? =
            { AuthoritativeCommandOutcome.Rejected("test") },
        setResearch: suspend (String, Boolean) -> AuthoritativeCommandOutcome? =
            { _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        adoptPolicy: suspend (String) -> AuthoritativeCommandOutcome? =
            { AuthoritativeCommandOutcome.Rejected("test") },
        acknowledgeResearch: suspend (String) -> AuthoritativeCommandOutcome? =
            { AuthoritativeCommandOutcome.Rejected("test") },
        queueConstruction: suspend (String, String) -> AuthoritativeCommandOutcome? =
            { _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        queueConstructionAtTile:
            suspend (String, String, Int, Int) -> AuthoritativeCommandOutcome? =
            { _, _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        perpetualConstruction: suspend (String, String) -> AuthoritativeCommandOutcome? =
            { _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        removeConstruction: suspend (String, Int, String) -> AuthoritativeCommandOutcome? =
            { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        moveConstruction: suspend (String, Int, Int, String) -> AuthoritativeCommandOutcome? =
            { _, _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        manageConstruction:
            suspend (
                String,
                String,
                Int?,
                ConstructionQueueAction,
            ) -> AuthoritativeCommandOutcome? =
            { _, _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        purchaseConstruction:
            suspend (String, String, String, Int?) -> AuthoritativeCommandOutcome? =
            { _, _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        purchaseConstructionAtTile:
            suspend (String, String, String, Int, Int, Int?) -> AuthoritativeCommandOutcome? =
            { _, _, _, _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = refresh,
        moveUnit = move,
        endTurn = endTurn,
        setResearch = setResearch,
        adoptPolicy = adoptPolicy,
        acknowledgeResearchCompletion = acknowledgeResearch,
        cityEconomyActions = AuthoritativeCityEconomyActions(
            queue = queueConstruction,
            queueAtTile = queueConstructionAtTile,
            setPerpetual = perpetualConstruction,
            remove = removeConstruction,
            move = moveConstruction,
            manage = manageConstruction,
            purchase = purchaseConstruction,
            purchaseAtTile = purchaseConstructionAtTile,
        ),
    )

    private fun accepted(
        current: ApiV3GameProjection,
        revision: Long,
    ): AuthoritativeCommandOutcome.Accepted {
        val replacement = current.copy(
            committedRevision = revision,
            canonicalStateHash = "hash-$revision",
            projectionHash = "projection-$revision",
        )
        return AuthoritativeCommandOutcome.Accepted(
            ApiV3CommandAccepted(
                current.gameId,
                "command",
                current.committedRevision,
                revision,
                replacement.canonicalStateHash,
            ),
            replacement,
        )
    }

    private fun gameProjection(revision: Long): ApiV3GameProjection {
        val projection = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }.decodeFromString(
            PlayerProjection.serializer(),
            projectionFixture().readText(),
        )
        return ApiV3GameProjection(
            gameId = "game-a",
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = revision,
            canonicalStateHash = "hash-$revision",
            projectionHash = "projection-$revision",
            projection = projection,
        )
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v56.fixture.json") }
        .first { it.isFile }

    private fun sourceFile(path: String): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, path) }.first { it.isFile }

    private suspend inline fun <reified T : Throwable> assertThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.simpleName}, got $throwable", throwable)
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
