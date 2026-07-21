package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
import com.unciv.logic.multiplayer.authoritative.PendingEndTurnAction
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.Json

@RunWith(GdxTestRunner::class)
class AuthoritativeGameExecutionContextTests {
    private val serverTime = 1_700_000_000_000L

    @Before
    fun setUpHeadlessGame() {
        RulesetCache.loadRulesets(noMods = true)
        UncivGame.Current = UncivGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        UncivGame.Current.settings = GameSettings()
    }

    @Test
    fun serverCreationDoesNotPersistAClientSetup() {
        val game = GameStarter.startNewGame(
            testSetup(),
            GameExecutionContext.authoritative(
                actorId = "account-1",
                rulesetManifest = vanillaManifest(),
                clockMillis = { serverTime },
            ),
        )

        Assert.assertNull(UncivGame.Current.settings.lastGameSetup)
        Assert.assertEquals(serverTime, game.currentTurnStartTime)
    }

    @Test
    fun serverTurnUsesTheSuppliedClock() {
        val engine = HeadlessGameEngine(serverContext { serverTime + 5_000L })
        val game = engine.createGame(testSetup()).game

        engine.endTurn(game, "Rome")

        Assert.assertEquals(serverTime + 5_000L, game.currentTurnStartTime)
    }

    @Test
    fun aSnapshotReloadsToTheSameCanonicalHash() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val created = engine.createGame(testSetup()).game

        val reloaded = engine.loadSnapshot(engine.serializeSnapshot(created))

        Assert.assertEquals(engine.stateHash(created), engine.stateHash(reloaded))
    }

    @Test
    fun aReloadedCanonicalSnapshotCanRunTheServerTurnEngine() {
        val engine = HeadlessGameEngine(serverContext { serverTime + 5_000L })
        val created = engine.createGame(testSetup()).game
        val reloaded = engine.loadSnapshot(engine.serializeSnapshot(created))

        engine.endTurn(reloaded, "Rome")

        Assert.assertEquals(serverTime + 5_000L, reloaded.currentTurnStartTime)
    }

    @Test
    fun serverRejectsEndTurnWhileCanonicalConstructionChoiceIsPending() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        rome.addCity(rome.units.getCivUnits().first().getTile().position)
        val hashBefore = engine.stateHash(game)
        val projection = engine.playerProjection(game, "Rome")

        val rejection = Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.endTurn(game, "Rome")
        }
        Assert.assertEquals(
            listOf(
                PendingEndTurnAction.PickConstruction,
                PendingEndTurnAction.PickTechnology,
            ),
            projection.pendingTurnActions,
        )
        Assert.assertTrue(rejection.message!!.contains("pick_construction"))
        Assert.assertEquals(hashBefore, engine.stateHash(game))
        Assert.assertEquals("Rome", game.currentPlayer)
    }

    @Test
    fun serverDerivesResearchQueueFromProjectedDestination() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().getTile().position)
        val construction = city.getRuleset().buildings.values
            .first { city.cityConstructions.canAddToQueue(it) }.name
        engine.queueConstruction(game, "Rome", city.id, construction)
        val before = engine.playerProjection(game, "Rome")
        val targetName = before.research.selectableTargets.last()
        val target = game.ruleset.technologies[targetName]!!
        val expectedQueue = rome.tech.getRequiredTechsToDestination(target).map { it.name }

        val result = engine.setResearchPath(game, "Rome", targetName)
        val after = engine.playerProjection(result.game, "Rome")

        Assert.assertEquals(expectedQueue, after.research.queue)
        Assert.assertEquals(expectedQueue.firstOrNull(), after.research.currentTechnology)
        Assert.assertFalse(PendingEndTurnAction.PickTechnology in after.pendingTurnActions)
        Assert.assertFalse(PendingEndTurnAction.PickConstruction in after.pendingTurnActions)
    }

    @Test
    fun serverConsumesOnlyACanonicalFreeTechnologyGrant() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        rome.tech.freeTechs = 1
        val before = engine.playerProjection(game, "Rome")
        val technologyName = before.research.freeTechnologyChoices.first()

        val result = engine.chooseFreeTechnology(game, "Rome", technologyName)
        val after = engine.playerProjection(result.game, "Rome")

        Assert.assertTrue(rome.tech.isResearched(technologyName))
        Assert.assertEquals(0, rome.tech.freeTechs)
        Assert.assertTrue(after.research.freeTechnologyChoices.isEmpty())
        Assert.assertFalse(PendingEndTurnAction.PickTechnology in after.pendingTurnActions)

        val hashAfter = engine.stateHash(result.game)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.chooseFreeTechnology(result.game, "Rome", technologyName)
        }
        Assert.assertEquals(hashAfter, engine.stateHash(result.game))
    }

    @Test
    fun serverAdoptsOnlyAProjectedCanonicalPolicy() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        rome.policies.freePolicies = 1
        rome.policies.shouldOpenPolicyPicker = true
        val before = engine.playerProjection(game, "Rome")
        val policyName = before.policies.selectablePolicies.first()

        val result = engine.adoptPolicy(game, "Rome", policyName)
        val after = engine.playerProjection(result.game, "Rome")

        Assert.assertTrue(policyName in after.policies.adoptedPolicies)
        Assert.assertFalse(policyName in after.policies.selectablePolicies)
        Assert.assertEquals(0, after.policies.freePolicies)
        Assert.assertFalse(PendingEndTurnAction.PickPolicy in after.pendingTurnActions)

        val hashAfter = engine.stateHash(result.game)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.adoptPolicy(result.game, "Rome", policyName)
        }
        Assert.assertEquals(hashAfter, engine.stateHash(result.game))
    }

    @Test(expected = IllegalStateException::class)
    fun actorCannotEndAnotherCivilizationsTurn() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game

        engine.endTurn(game, "Greece")
    }

    @Test(expected = IllegalArgumentException::class)
    fun assignedActorCannotEndOutsideTheirTurn() {
        val creator = HeadlessGameEngine(serverContext { serverTime })
        val game = creator.createGame(testSetup()).game
        val secondPlayerEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        secondPlayerEngine.endTurn(game, "Greece")
    }

    @Test
    fun joiningActorIsAssignedAnUnclaimedCivilization() {
        val creator = HeadlessGameEngine(serverContext { serverTime })
        val game = creator.createGame(joinableSetup()).game
        val joiningEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        val assignment = joiningEngine.assignPlayer(game)
        val civilization = game.civilizations.single { it.civID == assignment.civilizationId }

        Assert.assertEquals("Greece", assignment.civilizationId)
        Assert.assertEquals(PlayerType.Human, civilization.playerType)
        Assert.assertEquals("account-2", civilization.playerId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anAssignedActorCannotJoinTwice() {
        val creator = HeadlessGameEngine(serverContext { serverTime })
        val game = creator.createGame(joinableSetup()).game
        val joiningEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        joiningEngine.assignPlayer(game)
        joiningEngine.assignPlayer(game)
    }

    @Test
    fun moveUnitUsesCanonicalOwnershipAndMovementRules() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val unit = game.getCivilization("Rome").units.getCivUnits().first()
        val destination = unit.movement.getDistanceToTiles().keys.first {
            it != unit.getTile() && unit.movement.canMoveTo(it)
        }

        engine.moveUnit(game, "Rome", unit.id, destination.position)

        Assert.assertEquals(destination.position, unit.getTile().position)
    }

    @Test
    fun theSameMoveFromTheSameSnapshotHasTheSameCanonicalHash() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val created = engine.createGame(testSetup()).game
        val unit = created.getCivilization("Rome").units.getCivUnits().first()
        val destination = unit.movement.getDistanceToTiles().keys.first {
            it != unit.getTile() && unit.movement.canMoveTo(it)
        }.position
        val snapshot = engine.serializeSnapshot(created)

        val first = engine.moveUnit(engine.loadSnapshot(snapshot), "Rome", unit.id, destination)
        val second = engine.moveUnit(engine.loadSnapshot(snapshot), "Rome", unit.id, destination)

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
    }

    @Test
    fun queueConstructionUsesCanonicalCityOwnershipAndBuildability() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().getTile().position)
        val construction = city.getRuleset().buildings.values
            .first { city.cityConstructions.canAddToQueue(it) }.name

        val result = engine.queueConstruction(game, "Rome", city.id, construction)
        val projectedCity = engine.playerProjection(result.game, "Rome").ownCities.single { it.id == city.id }

        Assert.assertEquals(listOf(construction), city.cityConstructions.constructionQueue)
        Assert.assertEquals(listOf(construction), projectedCity.constructionQueue)
        Assert.assertTrue(construction in projectedCity.availableConstructions)
    }

    @Test
    fun tileConstructionRevalidatesPlacementAndCommitsTheCanonicalMarker() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = civilization.civName
        val farm = testGame.ruleset.tileImprovements["Farm"]!!
        civilization.tech.techsResearched.add(farm.techRequired!!)
        val building = testGame.createBuilding("Creates a [Farm] improvement on a specific tile")
        val target = testGame.setTileTerrain(HexCoord(1, 0), Constants.grassland)
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.queueConstructionAtTile(
            testGame.gameInfo, civilization.civName, city.id, building.name, target.position,
        )

        Assert.assertEquals(listOf(building.name), city.cityConstructions.constructionQueue)
        Assert.assertTrue(target.isMarkedForCreatesOneImprovement("Farm"))
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.queueConstructionAtTile(
                testGame.gameInfo, civilization.civName, city.id, building.name, HexCoord(4, 0),
            )
        }
    }

    @Test(expected = IllegalStateException::class)
    fun actorCannotQueueConstructionInAnotherCivilizationsCity() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val greece = game.getCivilization("Greece")
        val city = greece.addCity(greece.units.getCivUnits().first().getTile().position)
        val construction = city.getRuleset().buildings.values
            .first { city.cityConstructions.canAddToQueue(it) }.name

        engine.queueConstruction(game, "Rome", city.id, construction)
    }

    @Test
    fun authoritativeQueueRemovalAndMovementRequireTheProjectedEntry() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().getTile().position)
        val constructions = city.getRuleset().units.values
            .filter { city.cityConstructions.canAddToQueue(it) }
            .take(2)
            .map { it.name }
        Assert.assertEquals(2, constructions.size)
        constructions.forEach { engine.queueConstruction(game, "Rome", city.id, it) }

        engine.moveConstruction(game, "Rome", city.id, 1, 0, constructions[1])
        Assert.assertEquals(listOf(constructions[1], constructions[0]),
            city.cityConstructions.constructionQueue)

        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.removeConstruction(game, "Rome", city.id, 0, constructions[0])
        }
        engine.removeConstruction(game, "Rome", city.id, 0, constructions[1])
        Assert.assertEquals(listOf(constructions[0]), city.cityConstructions.constructionQueue)
    }

    @Test
    fun authoritativePerpetualConstructionUsesTheSharedReplacementRules() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().getTile().position)

        engine.setPerpetualConstruction(
            game, "Rome", city.id, PerpetualConstruction.Idle.name,
        )

        Assert.assertEquals(
            listOf(PerpetualConstruction.Idle.name),
            city.cityConstructions.constructionQueue,
        )
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.setPerpetualConstruction(game, "Rome", city.id, "Monument")
        }
    }

    @Test
    fun authoritativePurchaseRecomputesCanonicalPriceAndConsumesTheQueueEntry() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().getTile().position)
        rome.addGold(100_000)
        val construction = city.getRuleset().buildings.values.first {
            !it.hasUnique(UniqueType.CreatesOneImprovement) &&
                it.getStatBuyCost(city, Stat.Gold)?.let { cost ->
                    city.cityConstructions.isConstructionPurchaseAllowed(it, Stat.Gold, cost)
                } == true && city.cityConstructions.canAddToQueue(it)
        }
        engine.queueConstruction(game, "Rome", city.id, construction.name)
        val expectedCost = construction.getStatBuyCost(city, Stat.Gold)!!
        val previousGold = rome.gold

        engine.purchaseConstruction(game, "Rome", city.id, construction.name, Stat.Gold.name, 0)

        Assert.assertTrue(city.cityConstructions.isBuilt(construction.name))
        Assert.assertEquals(previousGold - expectedCost, rome.gold)
        Assert.assertFalse(construction.name in city.cityConstructions.constructionQueue)
    }

    @Test
    fun authoritativePurchaseRejectsClientCurrencyThatRulesDoNotSupport() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().getTile().position)
        val construction = city.getRuleset().buildings.values
            .first { !city.cityConstructions.isBuilt(it.name) }.name

        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.purchaseConstruction(game, "Rome", city.id, construction, "Production", null)
        }
        Assert.assertFalse(city.cityConstructions.isBuilt(construction))
    }

    @Test
    fun authoritativeTilePurchaseRecomputesCanonicalCostAndOwnership() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().getTile().position)
        rome.addGold(100_000)
        val tile = city.tilesInRange.first { city.expansion.canBuyTile(it) }
        val expectedCost = city.expansion.getGoldCostOfTile(tile)
        val previousGold = rome.gold

        engine.buyCityTile(game, "Rome", city.id, tile.position)

        Assert.assertEquals(city, tile.owningCity)
        Assert.assertEquals(previousGold - expectedCost, rome.gold)
    }

    @Test(expected = IllegalStateException::class)
    fun actorCannotMoveAnotherCivilizationsUnit() {
        val creator = HeadlessGameEngine(serverContext { serverTime })
        val game = creator.createGame(testSetup()).game
        val romanUnit = game.getCivilization("Rome").units.getCivUnits().first()
        val destination = romanUnit.movement.getDistanceToTiles().keys.first {
            it != romanUnit.getTile() && romanUnit.movement.canMoveTo(it)
        }
        val otherPlayer = HeadlessGameEngine(serverContext("account-2") { serverTime })
        creator.endTurn(game, "Rome")

        otherPlayer.moveUnit(game, "Greece", romanUnit.id, destination.position)
    }

    @Test
    fun playerProjectionStructurallyExcludesForeignSecrets() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val otherCivilization = game.getCivilization("Greece")
        otherCivilization.playerId = "SENTINEL_OTHER_ACCOUNT"
        otherCivilization.flagsCountdown["SENTINEL_SECRET_PLAN"] = 999
        otherCivilization.units.getCivUnits().first().instanceName = "SENTINEL_HIDDEN_UNIT_NAME"

        val projection = engine.playerProjection(game, "Rome")
        val serialized = Json.encodeToString(PlayerProjection.serializer(), projection)

        Assert.assertEquals("Rome", projection.civilizationId)
        Assert.assertTrue(projection.ownUnits.isNotEmpty())
        Assert.assertTrue(projection.exploredTiles.isNotEmpty())
        Assert.assertFalse(serialized.contains("SENTINEL_OTHER_ACCOUNT"))
        Assert.assertFalse(serialized.contains("SENTINEL_SECRET_PLAN"))
        Assert.assertFalse(serialized.contains("SENTINEL_HIDDEN_UNIT_NAME"))
    }

    private fun testSetup(): GameSetupInfo {
        val parameters = GameParameters().apply {
            numberOfCityStates = 0
            players.clear()
            players.add(Player("Rome", PlayerType.Human, "account-1"))
            players.add(Player("Greece", PlayerType.Human, "account-2"))
        }
        val mapParameters = MapParameters().apply {
            mapSize = MapSize.Tiny
            seed = 42L
        }
        return GameSetupInfo(parameters, mapParameters)
    }

    private fun joinableSetup(): GameSetupInfo {
        val setup = testSetup()
        setup.gameParameters.players[1].playerType = PlayerType.AI
        setup.gameParameters.players[1].playerId = ""
        return setup
    }

    private fun vanillaManifest() = RulesetManifest(
        engineBuild = "test-engine-build",
        baseRuleset = ContentAddressedRuleset("Civ V - Vanilla", "0".repeat(64)),
    )

    private fun serverContext(
        actorId: String = "account-1",
        clock: () -> Long,
    ) = GameExecutionContext.authoritative(
        actorId = actorId,
        rulesetManifest = vanillaManifest(),
        clockMillis = clock,
    )
}
