package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
import com.unciv.logic.multiplayer.authoritative.CityTileAssignment
import com.unciv.logic.multiplayer.authoritative.CitizenFocus
import com.unciv.logic.multiplayer.authoritative.PendingEndTurnAction
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.UnitPosture
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapPathing
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.GameContext
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
        unit.action = "Explore"

        engine.moveUnit(game, "Rome", unit.id, destination.position)

        Assert.assertEquals(destination.position, unit.getTile().position)
        Assert.assertEquals(null, unit.action)
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
    fun moveTowardPersistsACanonicalServerOwnedMultiTurnOrder() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val unit = civilization.units.getCivUnits().first()
        val destination = game.tileMap.tileList.first { tile ->
            tile != unit.currentTile &&
                unit.movement.canReach(tile) &&
                unit.movement.getTileToMoveToThisTurn(tile) != tile
        }
        destination.setExplored(civilization, true)

        engine.moveUnitToward(game, "Rome", unit.id, destination.position)
        val projectedUnit = engine.playerProjection(game, "Rome").ownUnits.single { it.id == unit.id }

        Assert.assertNotEquals(destination, unit.currentTile)
        Assert.assertEquals(
            "moveTo ${destination.position.x},${destination.position.y}",
            unit.action,
        )
        Assert.assertEquals(destination.position.x, projectedUnit.movementDestinationX)
        Assert.assertEquals(destination.position.y, projectedUnit.movementDestinationY)

        engine.cancelUnitMovementOrder(game, "Rome", unit.id)

        val cancelled = engine.playerProjection(game, "Rome").ownUnits.single { it.id == unit.id }
        Assert.assertEquals(null, unit.action)
        Assert.assertEquals(null, cancelled.movementDestinationX)
        Assert.assertEquals(null, cancelled.movementDestinationY)
    }

    @Test
    fun explorationIsStartedAndStoppedOnlyByTheAuthoritativeEngine() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val unit = game.getCivilization("Rome").units.getCivUnits().first {
            !it.baseUnit.movesLikeAirUnits
        }

        engine.setUnitExploration(game, "Rome", unit.id, enabled = true)
        Assert.assertTrue(unit.isExploring())
        Assert.assertTrue(engine.playerProjection(game, "Rome").ownUnits
            .single { it.id == unit.id }.exploring)

        engine.setUnitExploration(game, "Rome", unit.id, enabled = false)
        Assert.assertFalse(unit.isExploring())
        Assert.assertEquals(null, unit.action)
    }

    @Test
    fun unitAutomationIsCanonicalDeterministicAndProjectedOnlyToItsOwner() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val created = engine.createGame(testSetup()).game
        val unitId = created.getCivilization("Rome").units.getCivUnits().first { it.isMilitary() }.id
        val snapshot = engine.serializeSnapshot(created)

        val first = engine.setUnitAutomation(
            engine.loadSnapshot(snapshot), "Rome", unitId, enabled = true,
        )
        val second = engine.setUnitAutomation(
            engine.loadSnapshot(snapshot), "Rome", unitId, enabled = true,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        Assert.assertTrue(first.game.getCivilization("Rome").units.getUnitById(unitId)!!.isAutomated())
        Assert.assertTrue(engine.playerProjection(first.game, "Rome").ownUnits
            .single { it.id == unitId }.automated)

        engine.setUnitAutomation(first.game, "Rome", unitId, enabled = false)
        Assert.assertFalse(first.game.getCivilization("Rome").units.getUnitById(unitId)!!.isAutomated())
    }

    @Test
    fun unitAutomationRejectsForeignActorsAndOutOfTurnOwners() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val unitId = game.getCivilization("Rome").units.getCivUnits().first { it.isMilitary() }.id
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.setUnitAutomation(game, "Rome", unitId, enabled = true)
        }
        ownerEngine.endTurn(game, "Rome")
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.setUnitAutomation(game, "Rome", unitId, enabled = true)
        }
    }

    @Test
    fun unitPostureIsCanonicalDeterministicAndProjectedOnlyToItsOwner() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val created = engine.createGame(testSetup()).game
        val unitId = created.getCivilization("Rome").units.getCivUnits().first { it.canFortify() }.id
        val snapshot = engine.serializeSnapshot(created)

        val first = engine.setUnitPosture(
            engine.loadSnapshot(snapshot), "Rome", unitId, UnitPosture.Fortify,
        )
        val second = engine.setUnitPosture(
            engine.loadSnapshot(snapshot), "Rome", unitId, UnitPosture.Fortify,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        Assert.assertTrue(first.game.getCivilization("Rome").units.getUnitById(unitId)!!.isFortified())
        Assert.assertEquals(
            UnitPosture.Fortify,
            engine.playerProjection(first.game, "Rome").ownUnits.single { it.id == unitId }.posture,
        )
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        Assert.assertTrue(foreignEngine.playerProjection(first.game, "Greece").visibleForeignUnits.all {
            it.posture == null
        })
    }

    @Test
    fun unitPostureRejectsForeignActorsAndOutOfTurnOwners() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val unitId = game.getCivilization("Rome").units.getCivUnits().first { it.canFortify() }.id
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.setUnitPosture(game, "Rome", unitId, UnitPosture.Fortify)
        }
        ownerEngine.endTurn(game, "Rome")
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.setUnitPosture(game, "Rome", unitId, UnitPosture.Fortify)
        }
    }

    @Test
    fun unitDisbandIsCanonicalDeterministicAndDerivesGoldOnTheServer() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val unit = civilization.units.getCivUnits().first { it.isMilitary() }
        civilization.addCity(unit.getTile().position)
        val unitId = unit.id
        val expectedGold = unit.baseUnit.getDisbandGold(civilization)
        val initialGold = civilization.gold
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.disbandUnit(
            engine.loadSnapshot(snapshot), civilization.civName, unitId,
        )
        val second = engine.disbandUnit(
            engine.loadSnapshot(snapshot), civilization.civName, unitId,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        Assert.assertEquals(
            initialGold + expectedGold,
            first.game.getCivilization(civilization.civName).gold,
        )
        Assert.assertEquals(
            null,
            first.game.getCivilization(civilization.civName).units.getUnitById(unitId),
        )
        Assert.assertTrue(engine.playerProjection(first.game, civilization.civName).ownUnits
            .none { it.id == unitId })
    }

    @Test
    fun unitDisbandRejectsForeignActorsAndOutOfTurnOwners() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val unitId = game.getCivilization("Rome").units.getCivUnits().first().id
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.disbandUnit(game, "Rome", unitId)
        }
        ownerEngine.endTurn(game, "Rome")
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.disbandUnit(game, "Rome", unitId)
        }
    }

    @Test
    fun unitUpgradeBatchIsCanonicalDeterministicAndDerivesCostsOnTheServer() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val city = civilization.addCity(civilization.units.getCivUnits().first().getTile().position)
        civilization.addGold(10_000)
        val units = listOf(
            civilization.units.addUnit("Archer", city)!!,
            civilization.units.addUnit("Archer", city)!!,
        )
        val unitIds = units.map { it.id }
        val target = civilization.getEquivalentUnit(
            units.first().baseUnit.getUpgradeUnits(units.first().cache.state).first(),
        )
        target.requiredTech?.let { civilization.tech.addTechnology(it) }
        val totalCost = units.sumOf { it.upgrade.getCostOfUpgrade(target) }
        val initialGold = civilization.gold
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.upgradeUnits(
            engine.loadSnapshot(snapshot), "Rome", unitIds, target.name,
        )
        val second = engine.upgradeUnits(
            engine.loadSnapshot(snapshot), "Rome", unitIds, target.name,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val resultCivilization = first.game.getCivilization("Rome")
        Assert.assertEquals(initialGold - totalCost, resultCivilization.gold)
        Assert.assertTrue(unitIds.all { unitId ->
            resultCivilization.units.getUnitById(unitId)?.baseUnit?.name == target.name
        })
        Assert.assertTrue(unitIds.all { unitId ->
            engine.playerProjection(first.game, "Rome").ownUnits
                .single { it.id == unitId }.name == target.name
        })
    }

    @Test
    fun unitUpgradeRejectsForeignActorsAndOutOfTurnOwners() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val city = civilization.addCity(civilization.units.getCivUnits().first().getTile().position)
        civilization.addGold(10_000)
        val unit = civilization.units.addUnit("Archer", city)!!
        val unitId = unit.id
        val target = civilization.getEquivalentUnit(
            unit.baseUnit.getUpgradeUnits(unit.cache.state).first(),
        )
        target.requiredTech?.let { civilization.tech.addTechnology(it) }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.upgradeUnits(game, "Rome", listOf(unitId), target.name)
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.upgradeUnits(game, "Rome", listOf(unitId), target.name)
        }
    }

    @Test
    fun unitPromotionIsCanonicalDeterministicAndSpendsServerDerivedXp() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val unit = civilization.units.getCivUnits()
            .first { it.promotions.getAvailablePromotions().any() }
        unit.promotions.XP = 1_000
        val promotion = unit.promotions.getAvailablePromotions().sortedBy { it.name }.first()
        val xpCost = unit.promotions.xpForNextPromotion()
        val initialXp = unit.promotions.XP
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.promoteUnit(
            engine.loadSnapshot(snapshot), "Rome", unit.id, listOf(promotion.name),
        )
        val second = engine.promoteUnit(
            engine.loadSnapshot(snapshot), "Rome", unit.id, listOf(promotion.name),
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val promoted = first.game.getCivilization("Rome").units.getUnitById(unit.id)!!
        Assert.assertTrue(promotion.name in promoted.promotions.promotions)
        Assert.assertEquals(initialXp - xpCost, promoted.promotions.XP)
        val projected = engine.playerProjection(first.game, "Rome").ownUnits.single { it.id == unit.id }
        Assert.assertTrue(promotion.name in projected.promotions)
        Assert.assertEquals(initialXp - xpCost, projected.promotionXp)
        Assert.assertEquals(promoted.promotions.xpForNextPromotion(), projected.nextPromotionXp)
    }

    @Test
    fun unitPromotionRejectsForeignActorsOutOfTurnOwnersAndUnavailableChoices() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val unit = civilization.units.getCivUnits()
            .first { it.promotions.getAvailablePromotions().any() }
        unit.promotions.XP = 1_000
        val promotion = unit.promotions.getAvailablePromotions().sortedBy { it.name }.first().name
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.promoteUnit(game, "Rome", unit.id, listOf(promotion))
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.promoteUnit(game, "Rome", unit.id, listOf("Not a promotion"))
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.promoteUnit(game, "Rome", unit.id, listOf(promotion))
        }
    }

    @Test
    fun unitRenameIsCanonicalDeterministicProjectedAndClearable() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val unit = game.getCivilization("Rome").units.getCivUnits().first()
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.renameUnit(engine.loadSnapshot(snapshot), "Rome", unit.id, "First Legion")
        val second = engine.renameUnit(engine.loadSnapshot(snapshot), "Rome", unit.id, "First Legion")

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        Assert.assertEquals("First Legion", first.game.getCivilization("Rome").units.getUnitById(unit.id)!!.instanceName)
        Assert.assertEquals(
            "First Legion",
            engine.playerProjection(first.game, "Rome").ownUnits.single { it.id == unit.id }.instanceName,
        )
        val cleared = engine.renameUnit(first.game, "Rome", unit.id, null)
        Assert.assertEquals(null, cleared.game.getCivilization("Rome").units.getUnitById(unit.id)!!.instanceName)
    }

    @Test
    fun unitRenameRejectsForeignActorsOutOfTurnOwnersAndInvalidNames() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val unit = game.getCivilization("Rome").units.getCivUnits().first()
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.renameUnit(game, "Rome", unit.id, "Stolen")
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.renameUnit(game, "Rome", unit.id, "line\nbreak")
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.renameUnit(game, "Rome", unit.id, "Too late")
        }
    }

    @Test
    fun tileImprovementOrderIsCanonicalDeterministicProjectedAndCancelable() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val city = civilization.addCity(civilization.units.getCivUnits().first().currentTile.position)
        civilization.tech.techsResearched.addAll(game.ruleset.technologies.keys)
        val worker = civilization.units.addUnit("Worker", city)!!
        val tile = worker.currentTile.neighbors.first { !it.isCityCenter() && it.isLand }
        worker.removeFromTile()
        worker.putInTile(tile)
        val context = GameContext(civilization, unit = worker, tile = tile)
        val improvement = game.ruleset.tileImprovements.values.first {
            it.turnsToBuild != -1 && worker.canBuildImprovement(it) &&
                tile.improvementFunctions.canBuildImprovement(it, context)
        }
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.setTileImprovementOrder(
            engine.loadSnapshot(snapshot), "Rome", worker.id, improvement.name, null,
        )
        val second = engine.setTileImprovementOrder(
            engine.loadSnapshot(snapshot), "Rome", worker.id, improvement.name, null,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val projectedOrder = engine.playerProjection(first.game, "Rome").ownUnits
            .single { it.id == worker.id }.improvementOrder.single()
        Assert.assertEquals(improvement.name, projectedOrder.improvementName)
        Assert.assertTrue(projectedOrder.turnsRemaining > 0)
        val cancelled = engine.setTileImprovementOrder(
            first.game, "Rome", worker.id, null, null,
        )
        Assert.assertTrue(cancelled.game.getCivilization("Rome").units.getUnitById(worker.id)!!
            .currentTile.getImprovementQueueSnapshot().isEmpty())
    }

    @Test
    fun tileImprovementOrderRejectsForeignActorsAndOutOfTurnOwners() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val city = civilization.addCity(civilization.units.getCivUnits().first().currentTile.position)
        civilization.tech.techsResearched.addAll(game.ruleset.technologies.keys)
        val worker = civilization.units.addUnit("Worker", city)!!
        val tile = worker.currentTile.neighbors.first { !it.isCityCenter() && it.isLand }
        worker.removeFromTile()
        worker.putInTile(tile)
        val context = GameContext(civilization, unit = worker, tile = tile)
        val improvement = game.ruleset.tileImprovements.values.first {
            it.turnsToBuild != -1 && worker.canBuildImprovement(it) &&
                tile.improvementFunctions.canBuildImprovement(it, context)
        }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.setTileImprovementOrder(
                game, "Rome", worker.id, improvement.name, null,
            )
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.setTileImprovementOrder(
                game, "Rome", worker.id, improvement.name, null,
            )
        }
    }

    @Test
    fun roadConnectionOrderIsCanonicalDeterministicPrivateAndCancelable() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val city = civilization.addCity(civilization.units.getCivUnits().first().currentTile.position)
        val roadImprovement = game.ruleset.roadImprovement!!
        roadImprovement.techRequired?.let { civilization.tech.techsResearched.add(it) }
        val worker = civilization.units.addUnit("Worker", city)!!
        val destination = game.tileMap.tileList.first { tile ->
            tile != worker.currentTile && civilization.hasExplored(tile) &&
                (MapPathing.getRoadPath(civilization, worker.currentTile, tile)?.size ?: 0) > 2
        }
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.setRoadConnectionOrder(
            engine.loadSnapshot(snapshot), "Rome", worker.id, destination.position,
        )
        val second = engine.setRoadConnectionOrder(
            engine.loadSnapshot(snapshot), "Rome", worker.id, destination.position,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val projected = engine.playerProjection(first.game, "Rome").ownUnits.single { it.id == worker.id }
        Assert.assertEquals(destination.position.x, projected.roadConnectionDestinationX)
        Assert.assertTrue(projected.roadConnectionPath.size > 2)
        val foreign = first.game.getCivilization("Greece")
        foreign.viewableTiles = foreign.viewableTiles +
            projected.let { first.game.tileMap[HexCoord(it.x, it.y)] }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        val foreignProjection = foreignEngine.playerProjection(first.game, "Greece").visibleForeignUnits
            .single { it.id == worker.id }
        Assert.assertEquals(null, foreignProjection.roadConnectionDestinationX)
        Assert.assertTrue(foreignProjection.roadConnectionPath.isEmpty())

        val cancelled = engine.setRoadConnectionOrder(first.game, "Rome", worker.id, null)
        val cancelledUnit = cancelled.game.getCivilization("Rome").units.getUnitById(worker.id)!!
        Assert.assertEquals(null, cancelledUnit.automatedRoadConnectionDestination)
        Assert.assertEquals(null, cancelledUnit.automatedRoadConnectionPath)
        Assert.assertTrue(!cancelledUnit.isAutomated())
    }

    @Test
    fun roadConnectionOrderRejectsForeignActorsAndOutOfTurnOwners() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val city = civilization.addCity(civilization.units.getCivUnits().first().currentTile.position)
        game.ruleset.roadImprovement!!.techRequired?.let { civilization.tech.techsResearched.add(it) }
        val worker = civilization.units.addUnit("Worker", city)!!
        val destination = game.tileMap.tileList.first { tile ->
            tile != worker.currentTile && civilization.hasExplored(tile) &&
                MapPathing.getRoadPath(civilization, worker.currentTile, tile) != null
        }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.setRoadConnectionOrder(game, "Rome", worker.id, destination.position)
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.setRoadConnectionOrder(game, "Rome", worker.id, destination.position)
        }
    }

    @Test
    fun pillageTileDerivesTargetLootHealingAndMovementDeterministically() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val unit = civilization.units.getCivUnits().first { !it.isCivilian() }
        val tile = unit.currentTile.neighbors.first { it.isLand && !it.isCityCenter() }
        unit.removeFromTile()
        unit.putInTile(tile)
        tile.setImprovement("Farm")
        unit.health = 50
        val movementBefore = unit.currentMovement
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.pillageTile(engine.loadSnapshot(snapshot), "Rome", unit.id)
        val second = engine.pillageTile(engine.loadSnapshot(snapshot), "Rome", unit.id)

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val resultUnit = first.game.getCivilization("Rome").units.getUnitById(unit.id)!!
        Assert.assertTrue(resultUnit.currentTile.improvementIsPillaged)
        Assert.assertEquals(75, resultUnit.health)
        Assert.assertEquals(movementBefore - 1f, resultUnit.currentMovement)
        val projectedTile = engine.playerProjection(first.game, "Rome").exploredTiles
            .single { it.x == tile.position.x && it.y == tile.position.y }
        Assert.assertTrue(projectedTile.visible)
        Assert.assertEquals("Farm", projectedTile.improvementName)
        Assert.assertEquals(true, projectedTile.improvementPillaged)
        val resultCivilization = first.game.getCivilization("Rome")
        val hiddenTile = first.game.tileMap.tileList.first {
            resultCivilization.hasExplored(it) && it != resultUnit.currentTile
        }
        hiddenTile.setImprovement("Farm")
        hiddenTile.improvementIsPillaged = true
        resultCivilization.viewableTiles = resultCivilization.viewableTiles - hiddenTile
        val hiddenProjection = engine.playerProjection(first.game, "Rome").exploredTiles
            .single { it.x == hiddenTile.position.x && it.y == hiddenTile.position.y }
        Assert.assertFalse(hiddenProjection.visible)
        Assert.assertEquals(null, hiddenProjection.improvementName)
        Assert.assertEquals(null, hiddenProjection.improvementPillaged)
        Assert.assertEquals(null, hiddenProjection.roadStatus)
        Assert.assertEquals(null, hiddenProjection.roadPillaged)
    }

    @Test
    fun pillageTileRejectsForeignActorsOutOfTurnOwnersAndInvalidTargets() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val unit = game.getCivilization("Rome").units.getCivUnits().first { !it.isCivilian() }
        val tile = unit.currentTile.neighbors.first { it.isLand && !it.isCityCenter() }
        unit.removeFromTile()
        unit.putInTile(tile)
        tile.setImprovement("Farm")
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.pillageTile(game, "Rome", unit.id)
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.pillageTile(game, "Rome", unit.id)
        }
        game.currentPlayer = "Rome"
        tile.removeImprovement()
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.pillageTile(game, "Rome", unit.id)
        }
    }

    @Test
    fun foundCityIsCanonicalDeterministicAndProjectedWithStableIdentity() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val settler = civilization.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
        val location = settler.currentTile.position
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.foundCity(engine.loadSnapshot(snapshot), "Rome", settler.id)
        val second = engine.foundCity(engine.loadSnapshot(snapshot), "Rome", settler.id)

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val city = first.game.getCivilization("Rome").cities.single()
        val repeatedCity = second.game.getCivilization("Rome").cities.single()
        Assert.assertEquals(city.id, repeatedCity.id)
        Assert.assertEquals(location, city.location)
        Assert.assertTrue(city.isOriginalCapital)
        Assert.assertEquals(null, first.game.getCivilization("Rome").units.getUnitById(settler.id))
        val projection = engine.playerProjection(first.game, "Rome")
        Assert.assertEquals(city.id, projection.ownCities.single().id)
        Assert.assertEquals(city.name, projection.ownCities.single().name)
        Assert.assertTrue(projection.ownUnits.none { it.id == settler.id })
    }

    @Test
    fun foundCityRejectsForeignActorsOutOfTurnOwnersAndNonFounders() {
        val ownerEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = ownerEngine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val settler = civilization.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
        val nonFounder = civilization.units.getCivUnits().first { !it.hasUnique(UniqueType.FoundCity) }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.foundCity(game, "Rome", settler.id)
        }
        Assert.assertThrows(IllegalStateException::class.java) {
            ownerEngine.foundCity(game, "Rome", nonFounder.id)
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            ownerEngine.foundCity(game, "Rome", settler.id)
        }
    }

    @Test
    fun paradropIsCanonicalDeterministicAndConsumesServerDerivedActionState() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val origin = civilization.units.getCivUnits().first { it.isCivilian() }.currentTile
        val city = civilization.addCity(origin.position)
        val unit = civilization.units.addUnit("Paratrooper", city)!!
        civilization.cache.updateViewableTiles()
        val destination = origin.getTilesAtDistance(2).first { it.isLand && it.militaryUnit == null }
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.paradropUnit(
            engine.loadSnapshot(snapshot), civilization.civName, unit.id,
            destination.position.x, destination.position.y,
        )
        val second = engine.paradropUnit(
            engine.loadSnapshot(snapshot), civilization.civName, unit.id,
            destination.position.x, destination.position.y,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val dropped = first.game.getCivilization(civilization.civName).units.getUnitById(unit.id)!!
        Assert.assertEquals(destination.position, dropped.currentTile.position)
        Assert.assertEquals(1, dropped.attacksThisTurn)
        Assert.assertTrue(dropped.currentMovement < dropped.getMaxMovement().toFloat())
    }

    @Test
    fun paradropRejectsForeignActorsOutOfTurnUnitsAndIllegalDestinations() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val origin = civilization.units.getCivUnits().first { it.isCivilian() }.currentTile
        val city = civilization.addCity(origin.position)
        val unit = civilization.units.addUnit("Paratrooper", city)!!
        civilization.cache.updateViewableTiles()
        val destination = origin.getTilesAtDistance(6).first()
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.paradropUnit(
                game, civilization.civName, unit.id,
                destination.position.x, destination.position.y,
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.paradropUnit(
                game, civilization.civName, unit.id,
                destination.position.x, destination.position.y,
            )
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.paradropUnit(
                game, civilization.civName, unit.id,
                origin.position.x, origin.position.y,
            )
        }
    }

    @Test
    fun cityDefaultPromotionsAreSavedFromCanonicalUnitAndToggleDeterministically() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val civilization = game.getCivilization("Rome")
        val unit = civilization.units.getCivUnits()
            .first { it.promotions.getAvailablePromotions().any() }
        val city = civilization.addCity(unit.currentTile.position)
        unit.promotions.XP = 1_000
        val promotion = unit.promotions.getAvailablePromotions().sortedBy { it.name }.first().name
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.promoteUnit(
            engine.loadSnapshot(snapshot), "Rome", unit.id, listOf(promotion), true,
        )
        val second = engine.promoteUnit(
            engine.loadSnapshot(snapshot), "Rome", unit.id, listOf(promotion), true,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val resultCity = first.game.getCivilization("Rome").cities.single { it.id == city.id }
        Assert.assertTrue(resultCity.unitShouldUseSavedPromotion[unit.baseUnit.name] == true)
        Assert.assertTrue(promotion in resultCity.unitToPromotions[unit.baseUnit.name]!!.promotions)
        val preference = engine.playerProjection(first.game, "Rome").ownCities
            .single { it.id == city.id }.unitPromotionPreferences.single()
        Assert.assertEquals(unit.baseUnit.name, preference.baseUnitName)
        Assert.assertTrue(preference.enabled)
        Assert.assertTrue(promotion in preference.savedPromotions)

        val disabled = engine.setCityUnitPromotionPreference(
            first.game, "Rome", city.id, unit.baseUnit.name, false,
        )
        Assert.assertTrue(disabled.game.getCivilization("Rome").cities
            .single { it.id == city.id }.unitShouldUseSavedPromotion[unit.baseUnit.name] == false)

        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.setCityUnitPromotionPreference(
                disabled.game, "Rome", city.id, unit.baseUnit.name, true,
            )
        }
        disabled.game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.setCityUnitPromotionPreference(
                disabled.game, "Rome", city.id, unit.baseUnit.name, true,
            )
        }
    }

    @Test
    fun swapUnitsUsesCanonicalFriendlyOccupancyAndMovementRules() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        testGame.gameInfo.currentPlayer = civilization.civName
        val origin = testGame.getTile(HexCoord.Zero)
        val destination = testGame.getTile(HexCoord(1, 0))
        val moving = testGame.addUnit("Warrior", civilization, origin)
        val displaced = testGame.addUnit("Warrior", civilization, destination)
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.swapUnits(
            testGame.gameInfo,
            civilization.civName,
            moving.id,
            destination.position,
        )

        Assert.assertEquals(destination, moving.currentTile)
        Assert.assertEquals(origin, displaced.currentTile)
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

    @Test
    fun tilePurchaseRecomputesCanonicalCostAndCommitsTheImprovement() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        testGame.gameInfo.gameParameters.godMode = true
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = civilization.civName
        civilization.addGold(100_000)
        val building = testGame.createBuilding("Creates a [Farm] improvement on a specific tile")
        val target = testGame.setTileTerrain(HexCoord(1, 0), Constants.grassland)
        val expectedCost = building.getStatBuyCost(city, Stat.Gold)!!
        val previousGold = civilization.gold
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.purchaseConstructionAtTile(
            testGame.gameInfo, civilization.civName, city.id, building.name,
            Stat.Gold.name, target.position, null,
        )

        Assert.assertTrue(city.cityConstructions.isBuilt(building.name))
        Assert.assertEquals("Farm", target.improvement)
        Assert.assertEquals(previousGold - expectedCost, civilization.gold)
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

    @Test
    fun cityTileAssignmentEnforcesPopulationAndCanonicalLockState() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = civilization.civName
        val target = testGame.setTileTerrain(HexCoord(1, 0), Constants.grassland)
        city.workedTiles.clear()
        city.lockedTiles.clear()
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.setCityTileAssignment(
            testGame.gameInfo, civilization.civName, city.id, target.position,
            CityTileAssignment.Locked,
        )
        Assert.assertTrue(city.isWorked(target))
        Assert.assertTrue(target.isLocked())

        engine.setCityTileAssignment(
            testGame.gameInfo, civilization.civName, city.id, target.position,
            CityTileAssignment.Unworked,
        )
        Assert.assertFalse(city.isWorked(target))
        Assert.assertFalse(target.isLocked())
    }

    @Test
    fun specialistAssignmentUsesCanonicalCapacityAndPopulation() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = civilization.civName
        val specialistBuilding = testGame.createBuilding()
        specialistBuilding.specialistSlots.add("Merchant", 1)
        city.cityConstructions.addBuilding(specialistBuilding)
        city.workedTiles.clear()
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.setSpecialistCount(
            testGame.gameInfo, civilization.civName, city.id, "Merchant", 1,
        )
        Assert.assertEquals(1, city.population.specialistAllocations["Merchant"])
        Assert.assertTrue(city.manualSpecialists)

        engine.setSpecialistCount(
            testGame.gameInfo, civilization.civName, city.id, "Merchant", 0,
        )
        Assert.assertEquals(0, city.population.specialistAllocations["Merchant"])
    }

    @Test
    fun disablingManualSpecialistsReassignsPopulationCanonically() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = civilization.civName
        val specialistBuilding = testGame.createBuilding()
        specialistBuilding.specialistSlots.add("Merchant", 1)
        city.cityConstructions.addBuilding(specialistBuilding)
        city.workedTiles.clear()
        city.manualSpecialists = true
        city.population.specialistAllocations["Merchant"] = 1
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.setManualSpecialists(
            testGame.gameInfo, civilization.civName, city.id, false,
        )

        Assert.assertFalse(city.manualSpecialists)
        Assert.assertEquals(0, city.population.getFreePopulation())
    }

    @Test
    fun citizenResetClearsLocksAndReassignsCanonicalPopulation() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = civilization.civName
        val target = testGame.setTileTerrain(HexCoord(1, 0), Constants.grassland)
        city.workedTiles.clear()
        city.workedTiles.add(target.position)
        city.lockedTiles.add(target.position)
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.resetCitizens(testGame.gameInfo, civilization.civName, city.id)

        Assert.assertTrue(city.lockedTiles.isEmpty())
        Assert.assertEquals(0, city.population.getFreePopulation())
    }

    @Test
    fun citizenPoliciesAreAppliedAndReassignedCanonically() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = civilization.civName
        testGame.setTileTerrain(HexCoord(1, 0), Constants.grassland)
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.setAvoidGrowth(testGame.gameInfo, civilization.civName, city.id, true)
        engine.setCitizenFocus(
            testGame.gameInfo, civilization.civName, city.id, CitizenFocus.GoldFocus,
        )

        Assert.assertTrue(city.avoidGrowth)
        Assert.assertEquals(com.unciv.logic.city.CityFocus.GoldFocus, city.getCityFocus())
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
        otherCivilization.units.getCivUnits().first().action = "moveTo 999,999"
        otherCivilization.units.getCivUnits().first().automated = true

        val projection = engine.playerProjection(game, "Rome")
        val serialized = Json.encodeToString(PlayerProjection.serializer(), projection)

        Assert.assertEquals("Rome", projection.civilizationId)
        Assert.assertTrue(projection.ownUnits.isNotEmpty())
        Assert.assertTrue(projection.exploredTiles.isNotEmpty())
        Assert.assertFalse(serialized.contains("SENTINEL_OTHER_ACCOUNT"))
        Assert.assertFalse(serialized.contains("SENTINEL_SECRET_PLAN"))
        Assert.assertFalse(serialized.contains("SENTINEL_HIDDEN_UNIT_NAME"))
        Assert.assertTrue(projection.visibleForeignUnits.all {
            it.movementDestinationX == null && it.movementDestinationY == null &&
                !it.automated && !it.exploring && it.posture == null &&
                it.promotions.isEmpty() && it.promotionXp == null &&
                it.nextPromotionXp == null && it.availablePromotions.isEmpty() &&
                it.instanceName == null
        })
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
