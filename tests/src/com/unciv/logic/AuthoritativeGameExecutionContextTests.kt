package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
import com.unciv.logic.multiplayer.authoritative.CityTileAssignment
import com.unciv.logic.multiplayer.authoritative.CityGovernanceAction
import com.unciv.logic.multiplayer.authoritative.CityDispositionAction
import com.unciv.logic.multiplayer.authoritative.CityStateProtectionResponse
import com.unciv.logic.multiplayer.authoritative.CitizenFocus
import com.unciv.logic.multiplayer.authoritative.PendingEndTurnAction
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.SpectatorProjection
import com.unciv.logic.multiplayer.authoritative.UnitPosture
import com.unciv.logic.multiplayer.authoritative.ReligiousUnitAction
import com.unciv.logic.multiplayer.authoritative.ProjectedTrade
import com.unciv.logic.multiplayer.authoritative.ProjectedTradeOffer
import com.unciv.logic.multiplayer.authoritative.ProjectedMovementDestination
import com.unciv.logic.multiplayer.authoritative.DiplomaticDemand
import com.unciv.logic.multiplayer.authoritative.DiplomacyPromptType
import com.unciv.logic.multiplayer.authoritative.GreatPersonUnitAction
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapPathing
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
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
    fun serverOwnsTheAutomatedOrderPhaseBeforeEndingTheTurn() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        game.getCivilization("Rome").hasMovedAutomatedUnits = false
        val snapshot = engine.serializeSnapshot(game)
        val first = engine.loadSnapshot(snapshot)
        val second = engine.loadSnapshot(snapshot)

        val firstResult = engine.endTurn(first, "Rome")
        val secondResult = engine.endTurn(second, "Rome")

        Assert.assertTrue(first.getCivilization("Rome").hasMovedAutomatedUnits)
        Assert.assertEquals(firstResult.canonicalStateHash, secondResult.canonicalStateHash)
    }

    @Test
    fun transientMoveSpyReminderDoesNotBlockCanonicalEndTurn() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val setup = testSetup().apply { gameParameters.espionageEnabled = true }
        val game = engine.createGame(setup).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().getTile().position)
        val construction = city.getRuleset().buildings.values
            .first { city.cityConstructions.canAddToQueue(it) }.name
        engine.queueConstruction(game, "Rome", city.id, construction)
        val technology = engine.playerProjection(game, "Rome").research.selectableTargets.first()
        engine.setResearchPath(game, "Rome", technology)
        rome.espionageManager.addSpy()
        rome.espionageManager.dismissedShouldMoveSpies = false

        val projection = engine.playerProjection(game, "Rome")
        Assert.assertTrue(rome.espionageManager.shouldShowMoveSpies())
        Assert.assertFalse(PendingEndTurnAction.MoveSpies in projection.pendingTurnActions)

        engine.endTurn(game, "Rome")
        Assert.assertEquals("Greece", game.currentPlayer)
    }

    @Test
    fun resignationTransfersTheAuthenticatedCivilizationToServerAI() {
        val engine = HeadlessGameEngine(serverContext { serverTime + 5_000L })
        val game = engine.createGame(testSetup()).game

        engine.resign(game, "Rome")

        val rome = game.getCivilization("Rome")
        Assert.assertEquals(PlayerType.AI, rome.playerType)
        Assert.assertEquals("", rome.playerId)
        Assert.assertEquals("Greece", game.currentPlayer)
        Assert.assertTrue(game.getCivilization("Greece").notifications.any {
            it.text.contains("resigned and is now controlled by AI")
        })
    }

    @Test
    fun forceResignationUsesOnlyCanonicalTurnTimeAndAllowance() {
        val now = serverTime + 60_000L
        val engine = HeadlessGameEngine(serverContext { now })
        val game = engine.createGame(testSetup()).game
        val greece = game.getCivilization("Greece")
        game.currentPlayer = greece.civID
        game.currentTurnStartTime = serverTime
        greece.playerMinutesBeforeForceResign = 1

        val forced = engine.forceResign(game, "Rome")

        Assert.assertEquals("Greece", forced.civilizationId)
        Assert.assertEquals(PlayerType.AI, greece.playerType)
        Assert.assertEquals("", greece.playerId)
        Assert.assertEquals("Rome", game.currentPlayer)
    }

    @Test
    fun forceResignationRejectsBeforeCanonicalAllowanceWithoutMutation() {
        val engine = HeadlessGameEngine(serverContext { serverTime + 59_999L })
        val game = engine.createGame(testSetup()).game
        val greece = game.getCivilization("Greece")
        game.currentPlayer = greece.civID
        game.currentTurnStartTime = serverTime
        greece.playerMinutesBeforeForceResign = 1
        val hashBefore = engine.stateHash(game)

        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.forceResign(game, "Rome")
        }
        Assert.assertEquals(hashBefore, engine.stateHash(game))
        Assert.assertEquals(PlayerType.Human, greece.playerType)
        Assert.assertEquals("account-2", greece.playerId)
    }

    @Test
    fun ownerKickTransfersTheSelectedPlayerToServerAIAndAdvancesTheirTurn() {
        val engine = HeadlessGameEngine(serverContext { serverTime + 5_000L })
        val game = engine.createGame(testSetup()).game
        val greece = game.getCivilization("Greece")
        game.currentPlayer = greece.civID

        engine.kickPlayer(game, "Rome", "Greece")

        Assert.assertEquals(PlayerType.AI, greece.playerType)
        Assert.assertEquals("", greece.playerId)
        Assert.assertEquals("Rome", game.currentPlayer)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.kickPlayer(game, "Rome", "Greece")
        }
    }

    @Test
    fun spectatorProjectionContainsOnlyTheExplicitPublicSummary() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        game.getCivilization("Rome").addNotification(
            "SPECTATOR_PRIVATE_SENTINEL",
            NotificationCategory.General,
        )

        val encoded = Json.encodeToString(
            SpectatorProjection.serializer(),
            engine.spectatorProjection(game),
        )

        Assert.assertFalse(encoded.contains("SPECTATOR_PRIVATE_SENTINEL"))
        for (forbidden in listOf("gold", "unit", "city", "tile", "research", "policy", "diplomacy", "notification", "queue", "rng"))
            Assert.assertFalse("spectator projection leaked $forbidden", encoded.contains(forbidden, ignoreCase = true))
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
        rome.tech.techsInProgress[expectedQueue.first()] = 7
        val after = engine.playerProjection(result.game, "Rome")

        Assert.assertEquals(expectedQueue, after.research.queue)
        Assert.assertEquals(expectedQueue.firstOrNull(), after.research.currentTechnology)
        Assert.assertEquals(rome.tech.techsResearched.sorted(), after.research.researchedTechnologies)
        Assert.assertEquals(expectedQueue, after.research.queueEntries.map { it.technologyName })
        Assert.assertEquals(7, after.research.queueEntries.first().storedScience)
        Assert.assertEquals(rome.tech.costOfTech(expectedQueue.first()), after.research.queueEntries.first().cost)
        Assert.assertEquals(
            rome.tech.estimatedTurnsToTech(expectedQueue.first()),
            after.research.queueEntries.first().estimatedTurns,
        )
        Assert.assertEquals(0, after.research.overflowScience)
        Assert.assertFalse(PendingEndTurnAction.PickTechnology in after.pendingTurnActions)
        Assert.assertFalse(PendingEndTurnAction.PickConstruction in after.pendingTurnActions)
    }

    @Test
    fun serverAppendsOnlyMissingResearchPrerequisites() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val firstTargetName = engine.playerProjection(game, "Rome").research.selectableTargets.first()
        engine.setResearchPath(game, "Rome", firstTargetName)
        val beforeAppend = engine.playerProjection(game, "Rome")
        val appendTargetName = beforeAppend.research.appendableTargets.first()
        val appendTarget = game.ruleset.technologies[appendTargetName]!!
        val expectedQueue = beforeAppend.research.queue + rome.tech
            .getRequiredTechsToDestination(appendTarget)
            .map { it.name }
            .filterNot { it in beforeAppend.research.queue }

        val result = engine.setResearchPath(game, "Rome", appendTargetName, append = true)
        val after = engine.playerProjection(result.game, "Rome")

        Assert.assertEquals(expectedQueue, after.research.queue)
        Assert.assertEquals(beforeAppend.research.currentTechnology, after.research.currentTechnology)
        val hashAfter = engine.stateHash(result.game)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.setResearchPath(result.game, "Rome", appendTargetName, append = true)
        }
        Assert.assertEquals(hashAfter, engine.stateHash(result.game))
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
    fun serverProjectsAndAcknowledgesOnlyTheAuthenticatedResearchCompletion() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val technologyName = game.ruleset.technologies.keys.first()
        val initialAlertCount = rome.popupAlerts.size
        val unrelated = PopupAlert(AlertType.GoldenAge, "unrelated")
        rome.popupAlerts += unrelated
        rome.popupAlerts += PopupAlert(AlertType.TechResearched, technologyName)

        val prompt = engine.playerProjection(game, "Rome").research.completionPrompts.single()
        Assert.assertEquals(technologyName, prompt.technologyName)
        Assert.assertTrue(prompt.promptId.matches(Regex("[0-9a-f]{64}")))

        val initialHash = engine.stateHash(game)
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.acknowledgeResearchCompletion(game, "Greece", prompt.promptId)
        }
        Assert.assertEquals(initialHash, engine.stateHash(game))

        val result = engine.acknowledgeResearchCompletion(game, "Rome", prompt.promptId)
        val remainingAlerts = result.game.getCivilization("Rome").popupAlerts
        Assert.assertEquals(initialAlertCount + 1, remainingAlerts.size)
        Assert.assertTrue(unrelated in remainingAlerts)
        Assert.assertFalse(remainingAlerts.any {
            it.type == AlertType.TechResearched && it.value == technologyName
        })
        Assert.assertTrue(engine.playerProjection(result.game, "Rome").research.completionPrompts.isEmpty())

        val committedHash = engine.stateHash(result.game)
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.acknowledgeResearchCompletion(result.game, "Rome", prompt.promptId)
        }
        Assert.assertEquals(committedHash, engine.stateHash(result.game))
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
    fun escortedExactMoveIsAtomicDeterministicAndServerValidated() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val created = engine.createGame(testSetup()).game
        val rome = created.getCivilization("Rome")
        val military = rome.units.getCivUnits().first { it.isMilitary() }
        val civilian = rome.units.getCivUnits().first { it.isCivilian() }
        civilian.removeFromTile()
        civilian.putInTile(military.getTile())
        val destination = military.movement.getDistanceToTiles().keys.first {
            it != military.getTile() &&
                military.movement.canMoveTo(it) &&
                civilian.movement.canReachInCurrentTurn(it) &&
                civilian.movement.canMoveTo(it)
        }.position
        val snapshot = engine.serializeSnapshot(created)

        val first = engine.moveUnit(
            engine.loadSnapshot(snapshot), "Rome", military.id, destination, civilian.id,
        )
        val second = engine.moveUnit(
            engine.loadSnapshot(snapshot), "Rome", military.id, destination, civilian.id,
        )
        val movedRome = first.game.getCivilization("Rome")

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        Assert.assertEquals(destination, movedRome.units.getUnitById(military.id)!!.getTile().position)
        Assert.assertEquals(destination, movedRome.units.getUnitById(civilian.id)!!.getTile().position)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.moveUnit(
                engine.loadSnapshot(snapshot), "Rome", military.id, destination, military.id,
            )
        }
        val foreignUnitId = created.getCivilization("Greece").units.getCivUnits().first().id
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.moveUnit(
                engine.loadSnapshot(snapshot), "Rome", military.id, destination, foreignUnitId,
            )
        }
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
    fun siegeSetupIsCanonicalDeterministicAndRejectsIneligibleUnits() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val created = engine.createGame(testSetup()).game
        val rome = created.getCivilization("Rome")
        val siege = rome.units.placeUnitNearTile(
            rome.units.getCivUnits().first().currentTile.position,
            "Catapult",
        )!!
        val initialMovement = siege.currentMovement
        val snapshot = engine.serializeSnapshot(created)

        val first = engine.setUnitPosture(
            engine.loadSnapshot(snapshot), "Rome", siege.id, UnitPosture.Setup,
        )
        val second = engine.setUnitPosture(
            engine.loadSnapshot(snapshot), "Rome", siege.id, UnitPosture.Setup,
        )
        val canonicalSiege = first.game.getCivilization("Rome").units.getUnitById(siege.id)!!

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        Assert.assertTrue(canonicalSiege.isSetUpForSiege())
        Assert.assertEquals(initialMovement - 1f, canonicalSiege.currentMovement)
        Assert.assertEquals(
            UnitPosture.Setup,
            engine.playerProjection(first.game, "Rome").ownUnits
                .single { it.id == siege.id }.posture,
        )

        val ordinaryUnit = first.game.getCivilization("Rome").units.getCivUnits()
            .first { it.id != siege.id && !it.hasUnique(UniqueType.MustSetUp) }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.setUnitPosture(first.game, "Rome", ordinaryUnit.id, UnitPosture.Setup)
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
    fun unitAttackIsCanonicalDeterministicAndProjected() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.getDiplomacyManager(greece)!!.declareWar()
        val attacker = rome.units.getCivUnits().first { !it.isCivilian() }
        val defender = greece.units.getCivUnits().first { !it.isCivilian() }
        val target = attacker.currentTile.neighbors.first { it.isLand && it.militaryUnit == null }
        defender.removeFromTile()
        defender.putInTile(target)
        rome.cache.updateViewableTiles()
        val projectedTarget = engine.playerProjection(game, "Rome").ownUnits
            .single { it.id == attacker.id }.attackTargets
            .single { it.x == target.position.x && it.y == target.position.y }
        Assert.assertTrue(projectedTarget.attackFromX != target.position.x ||
            projectedTarget.attackFromY != target.position.y)
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.attackWithUnit(
            engine.loadSnapshot(snapshot), "Rome", attacker.id,
            target.position.x, target.position.y,
        )
        val second = engine.attackWithUnit(
            engine.loadSnapshot(snapshot), "Rome", attacker.id,
            target.position.x, target.position.y,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val resultAttacker = first.game.getCivilization("Rome").units.getUnitById(attacker.id)!!
        Assert.assertEquals(1, resultAttacker.attacksThisTurn)
        val projected = engine.playerProjection(first.game, "Rome")
        Assert.assertEquals(resultAttacker.health, projected.ownUnits.single { it.id == attacker.id }.health)
    }

    @Test
    fun unitAttackRejectsForeignOutOfTurnAndNonEnemyTargets() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val attacker = rome.units.getCivUnits().first { !it.isCivilian() }
        val friendly = rome.units.getCivUnits().first { it.isCivilian() }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.attackWithUnit(
                game, "Rome", attacker.id,
                friendly.currentTile.position.x, friendly.currentTile.position.y,
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.attackWithUnit(
                game, "Rome", attacker.id,
                friendly.currentTile.position.x, friendly.currentTile.position.y,
            )
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.attackWithUnit(
                game, "Rome", attacker.id,
                friendly.currentTile.position.x, friendly.currentTile.position.y,
            )
        }
    }

    @Test
    fun cityBombardmentIsCanonicalDeterministicAndProjected() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.getDiplomacyManager(greece)!!.declareWar()
        val founder = rome.units.getCivUnits().first { it.isCivilian() }
        val city = rome.addCity(founder.currentTile.position)
        val defender = greece.units.getCivUnits().first { !it.isCivilian() }
        val target = city.getCenterTile().getTilesAtDistance(2)
            .first { it.isLand && it.militaryUnit == null }
        defender.removeFromTile()
        defender.putInTile(target)
        rome.cache.updateViewableTiles()
        Assert.assertTrue(engine.playerProjection(game, "Rome").ownCities
            .single { it.id == city.id }.bombardTargets
            .any { it.x == target.position.x && it.y == target.position.y })
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.bombardWithCity(
            engine.loadSnapshot(snapshot), "Rome", city.id,
            target.position.x, target.position.y,
        )
        val second = engine.bombardWithCity(
            engine.loadSnapshot(snapshot), "Rome", city.id,
            target.position.x, target.position.y,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        Assert.assertTrue(first.game.getCivilization("Rome").attacksSinceTurnStart.any {
            it.target == target.position
        })
        val projectedDefender = engine.playerProjection(first.game, "Rome")
            .visibleForeignUnits.single { it.id == defender.id }
        Assert.assertTrue(projectedDefender.health < defender.health)
    }

    @Test
    fun cityBombardmentRejectsForeignOutOfTurnAndInvalidTargets() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val founder = rome.units.getCivUnits().first { it.isCivilian() }
        val city = rome.addCity(founder.currentTile.position)
        val invalidTarget = city.getCenterTile().neighbors.first()
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.bombardWithCity(
                game, "Rome", city.id, invalidTarget.position.x, invalidTarget.position.y,
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.bombardWithCity(
                game, "Rome", city.id, invalidTarget.position.x, invalidTarget.position.y,
            )
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.bombardWithCity(
                game, "Rome", city.id, invalidTarget.position.x, invalidTarget.position.y,
            )
        }
    }

    @Test
    fun nuclearStrikeOnEmptyTileIsCanonicalDeterministicAndProjected() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        val city = rome.addCity(rome.units.getCivUnits().first().currentTile.position)
        val nuke = rome.units.placeUnitNearTile(city.location, "Nuclear Missile")!!
        val target = city.getCenterTile().getTilesAtDistance(3)
            .first { !it.isCityCenter() && it.getUnits().none() }
        target.setExplored(rome, true)
        val candidatesBeforeHiddenVictim = engine.playerProjection(game, "Rome").ownUnits
            .single { it.id == nuke.id }.nuclearTargetCandidates
        val victimTile = target.neighbors.first { it.isLand && it.militaryUnit == null }
        val defender = greece.units.getCivUnits().first { !it.isCivilian() }
        defender.removeFromTile()
        defender.putInTile(victimTile)
        val candidatesAfterHiddenVictim = engine.playerProjection(game, "Rome").ownUnits
            .single { it.id == nuke.id }.nuclearTargetCandidates
        Assert.assertEquals(candidatesBeforeHiddenVictim, candidatesAfterHiddenVictim)
        Assert.assertTrue(candidatesAfterHiddenVictim
            .any { it.x == target.position.x && it.y == target.position.y })
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.launchNuclearStrike(
            engine.loadSnapshot(snapshot), "Rome", nuke.id,
            target.position.x, target.position.y,
        )
        val second = engine.launchNuclearStrike(
            engine.loadSnapshot(snapshot), "Rome", nuke.id,
            target.position.x, target.position.y,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        Assert.assertNull(first.game.getCivilization("Rome").units.getUnitById(nuke.id))
        Assert.assertTrue(first.game.getCivilization("Greece").units.getUnitById(defender.id)!!.health < 100)
        Assert.assertTrue(engine.playerProjection(first.game, "Rome").ownUnits.none { it.id == nuke.id })
    }

    @Test
    fun nuclearStrikeRejectsForeignOutOfTurnNonNuclearAndUnexploredTargets() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().currentTile.position)
        val nuke = rome.units.placeUnitNearTile(city.location, "Nuclear Missile")!!
        val unexploredTarget = game.tileMap.tileList.first {
            !it.isExplored(rome) && nuke.currentTile.aerialDistanceTo(it) <= nuke.getRange()
        }
        val ordinaryUnit = rome.units.getCivUnits().first { !it.isCivilian() && !it.isNuclearWeapon() }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.launchNuclearStrike(
                game, "Rome", nuke.id, unexploredTarget.position.x, unexploredTarget.position.y,
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.launchNuclearStrike(
                game, "Rome", nuke.id, unexploredTarget.position.x, unexploredTarget.position.y,
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.launchNuclearStrike(
                game, "Rome", ordinaryUnit.id, city.location.x, city.location.y,
            )
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.launchNuclearStrike(
                game, "Rome", nuke.id, unexploredTarget.position.x, unexploredTarget.position.y,
            )
        }
    }

    @Test
    fun airSweepIsCanonicalDeterministicAndProjected() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.getDiplomacyManager(greece)!!.declareWar()
        val romanCity = rome.addCity(rome.units.getCivUnits().first().currentTile.position)
        val target = romanCity.getCenterTile().getTilesAtDistance(4)
            .first { it.isLand && !it.isCityCenter() && it.getUnits().none() }
        val greekCity = greece.addCity(target.position)
        val attacker = rome.units.placeUnitNearTile(romanCity.location, "Fighter")!!
        val interceptors = listOf(
            greece.units.placeUnitNearTile(greekCity.location, "Fighter")!!,
            greece.units.placeUnitNearTile(greekCity.location, "Fighter")!!,
        )
        Assert.assertTrue(engine.playerProjection(game, "Rome").ownUnits
            .single { it.id == attacker.id }.airSweepTargets
            .any { it.x == target.position.x && it.y == target.position.y })
        val snapshot = engine.serializeSnapshot(game)

        val first = engine.airSweep(
            engine.loadSnapshot(snapshot), "Rome", attacker.id,
            target.position.x, target.position.y,
        )
        val second = engine.airSweep(
            engine.loadSnapshot(snapshot), "Rome", attacker.id,
            target.position.x, target.position.y,
        )

        Assert.assertEquals(first.canonicalStateHash, second.canonicalStateHash)
        val resultAttacker = first.game.getCivilization("Rome").units.getUnitById(attacker.id)!!
        val resultInterceptors = interceptors.map {
            first.game.getCivilization("Greece").units.getUnitById(it.id)!!
        }
        Assert.assertEquals(1, resultAttacker.attacksThisTurn)
        Assert.assertEquals(1, resultInterceptors.sumOf { it.attacksThisTurn })
        Assert.assertNull(resultAttacker.action)
        Assert.assertTrue(resultAttacker.health < 100 || resultInterceptors.any { it.health < 100 })
        Assert.assertEquals(
            resultAttacker.health,
            engine.playerProjection(first.game, "Rome").ownUnits.single { it.id == attacker.id }.health,
        )
    }

    @Test
    fun airSweepRejectsForeignOutOfTurnOrdinaryUnitAndInvalidTargets() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val city = rome.addCity(rome.units.getCivUnits().first().currentTile.position)
        val fighter = rome.units.placeUnitNearTile(city.location, "Fighter")!!
        val ordinaryUnit = rome.units.getCivUnits().first { !it.isCivilian() && it.id != fighter.id }
        val outOfRange = game.tileMap.tileList.maxBy { fighter.currentTile.aerialDistanceTo(it) }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.airSweep(game, "Rome", fighter.id, city.location.x, city.location.y)
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.airSweep(game, "Rome", fighter.id, fighter.currentTile.position.x, fighter.currentTile.position.y)
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.airSweep(game, "Rome", fighter.id, outOfRange.position.x, outOfRange.position.y)
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.airSweep(game, "Rome", ordinaryUnit.id, city.location.x, city.location.y)
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.airSweep(game, "Rome", fighter.id, city.location.x, city.location.y)
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

    @Test
    fun buildingSaleDerivesCanonicalRefundAndEnforcesServerRules() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        testGame.gameInfo.currentPlayer = civilization.civName
        val building = testGame.createBuilding()
        building.cost = 170
        city.cityConstructions.addBuilding(building)
        val previousGold = civilization.gold
        val engine = HeadlessGameEngine(serverContext { serverTime })

        engine.sellBuilding(testGame.gameInfo, civilization.civName, city.id, building.name)

        Assert.assertFalse(city.cityConstructions.isBuilt(building.name))
        Assert.assertEquals(previousGold + 17, civilization.gold)
        Assert.assertTrue(city.hasSoldBuildingThisTurn)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.sellBuilding(testGame.gameInfo, civilization.civName, city.id, building.name)
        }
    }

    @Test
    fun buildingSaleRejectsAnotherCivilizationsCityAndPuppets() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val actor = testGame.addCiv()
        actor.playerId = "account-1"
        val other = testGame.addCiv()
        other.playerId = "account-2"
        val city = testGame.addCity(other, testGame.getTile(HexCoord.Zero))
        val building = testGame.createBuilding()
        city.cityConstructions.addBuilding(building)
        testGame.gameInfo.currentPlayer = actor.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })

        Assert.assertThrows(IllegalStateException::class.java) {
            engine.sellBuilding(testGame.gameInfo, actor.civName, city.id, building.name)
        }

        testGame.gameInfo.currentPlayer = other.civName
        city.isPuppet = true
        val otherEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        Assert.assertThrows(IllegalArgumentException::class.java) {
            otherEngine.sellBuilding(testGame.gameInfo, other.civName, city.id, building.name)
        }
    }

    @Test
    fun cityGovernanceIsCanonicalAndProjectedAsClosedActions() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civilization = testGame.addCiv()
        civilization.playerId = "account-1"
        testGame.addCity(civilization, testGame.getTile(HexCoord.Zero))
        val city = testGame.addCity(civilization, testGame.getTile(HexCoord(2, 0)))
        city.isPuppet = true
        testGame.gameInfo.currentPlayer = civilization.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })

        var projected = engine.playerProjection(testGame.gameInfo, civilization.civName)
            .ownCities.single { it.id == city.id }
        Assert.assertTrue(projected.isPuppet)
        Assert.assertEquals(listOf(CityGovernanceAction.Annex), projected.availableGovernanceActions)

        engine.setCityGovernance(
            testGame.gameInfo, civilization.civName, city.id, CityGovernanceAction.Annex,
        )
        Assert.assertFalse(city.isPuppet)
        projected = engine.playerProjection(testGame.gameInfo, civilization.civName)
            .ownCities.single { it.id == city.id }
        Assert.assertEquals(listOf(CityGovernanceAction.StartRazing), projected.availableGovernanceActions)

        engine.setCityGovernance(
            testGame.gameInfo, civilization.civName, city.id, CityGovernanceAction.StartRazing,
        )
        Assert.assertTrue(city.isBeingRazed)
        engine.setCityGovernance(
            testGame.gameInfo, civilization.civName, city.id, CityGovernanceAction.StopRazing,
        )
        Assert.assertFalse(city.isBeingRazed)
    }

    @Test
    fun cityGovernanceRejectsInvalidStateForeignOwnershipAndOutOfTurnActors() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val actor = testGame.addCiv()
        actor.playerId = "account-1"
        testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val city = testGame.addCity(actor, testGame.getTile(HexCoord(2, 0)))
        val other = testGame.addCiv()
        other.playerId = "account-2"
        testGame.gameInfo.currentPlayer = actor.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })

        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.setCityGovernance(
                testGame.gameInfo, actor.civName, city.id, CityGovernanceAction.Annex,
            )
        }
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.setCityGovernance(
                testGame.gameInfo, other.civName, city.id, CityGovernanceAction.StartRazing,
            )
        }
        testGame.gameInfo.currentPlayer = other.civName
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.setCityGovernance(
                testGame.gameInfo, actor.civName, city.id, CityGovernanceAction.StartRazing,
            )
        }
    }

    @Test
    fun cityDispositionIsProjectedAndResolvedOnlyFromTheCanonicalPendingDecision() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val actor = testGame.addCiv()
        actor.playerId = "account-1"
        testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val captured = testGame.addCity(actor, testGame.getTile(HexCoord(2, 0)))
        captured.isPuppet = true
        actor.popupAlerts += PopupAlert(AlertType.CityConquered, captured.id)
        testGame.gameInfo.currentPlayer = actor.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })

        val decision = engine.playerProjection(testGame.gameInfo, actor.civName)
            .pendingCityDispositions.single()
        Assert.assertEquals(captured.id, decision.cityId)
        Assert.assertEquals(captured.name, decision.cityName)
        Assert.assertTrue(CityDispositionAction.Annex in decision.availableActions)
        Assert.assertTrue(CityDispositionAction.Puppet in decision.availableActions)

        engine.resolveCityDisposition(
            testGame.gameInfo, actor.civName, captured.id, CityDispositionAction.Annex,
        )
        Assert.assertFalse(captured.isPuppet)
        Assert.assertTrue(actor.popupAlerts.none {
            it.type == AlertType.CityConquered && it.value == captured.id
        })
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.resolveCityDisposition(
                testGame.gameInfo, actor.civName, captured.id, CityDispositionAction.Annex,
            )
        }
    }

    @Test
    fun diplomaticVoteCandidatesAndMutationAreCanonical() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)

        val projection = engine.playerProjection(game, rome.civName)
        Assert.assertTrue(PendingEndTurnAction.CastDiplomaticVote in projection.pendingTurnActions)
        Assert.assertEquals(listOf(greece.civName), projection.diplomaticVoteCandidates)

        engine.castDiplomaticVote(game, rome.civName, greece.civName)

        Assert.assertEquals(greece.civName, game.diplomaticVictoryVotesCast[rome.civName])
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.castDiplomaticVote(game, rome.civName, greece.civName)
        }
    }

    @Test
    fun diplomaticVoteRejectsForeignUnknownAndOutOfTurnChoices() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)

        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.castDiplomaticVote(game, rome.civName, "Unknown")
        }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.castDiplomaticVote(game, rome.civName, greece.civName)
        }
        game.currentPlayer = greece.civName
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.castDiplomaticVote(game, rome.civName, null)
        }
    }

    @Test
    fun bilateralTradeIsProjectedAndCommittedByTheCanonicalWorker() {
        val romeEngine = HeadlessGameEngine(serverContext { serverTime })
        val game = romeEngine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        val initialRomeGold = rome.gold
        val initialGreeceGold = greece.gold
        rome.addGold(100)
        val offer = ProjectedTrade(
            ourOffers = listOf(ProjectedTradeOffer(Constants.flatGold, "Gold", 50, 0)),
            theirOffers = emptyList(),
        )

        romeEngine.offerTrade(game, rome.civName, greece.civName, offer)

        Assert.assertEquals(1, greece.tradeRequests.size)
        val greeceEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        val request = greeceEngine.playerProjection(game, greece.civName).pendingTradeRequests.single()
        Assert.assertEquals(64, request.requestId.length)
        Assert.assertEquals(rome.civName, request.requestingCivilizationId)

        game.currentPlayer = greece.civName
        greeceEngine.acceptTrade(game, greece.civName, request.requestId)

        Assert.assertEquals(initialRomeGold + 50, rome.gold)
        Assert.assertEquals(initialGreeceGold + 50, greece.gold)
        Assert.assertTrue(greece.tradeRequests.isEmpty())
        Assert.assertEquals(1, rome.getDiplomacyManager(greece)!!.trades.size)
        Assert.assertEquals(1, greece.getDiplomacyManager(rome)!!.trades.size)
    }

    @Test
    fun tradeRejectsForgedAvailabilityForeignActorsAndReplayedRequestIds() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        val availableGold = rome.gold
        val forged = ProjectedTrade(
            listOf(ProjectedTradeOffer(Constants.flatGold, "Gold", availableGold + 1, 0)), emptyList(),
        )
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.offerTrade(game, rome.civName, greece.civName, forged)
        }
        val foreign = HeadlessGameEngine(serverContext("account-2") { serverTime })
        Assert.assertThrows(IllegalStateException::class.java) {
            foreign.offerTrade(game, rome.civName, greece.civName, ProjectedTrade(emptyList(), emptyList()))
        }

        rome.addGold(10)
        engine.offerTrade(game, rome.civName, greece.civName, ProjectedTrade(
            listOf(ProjectedTradeOffer(Constants.flatGold, "Gold", 5, 0)), emptyList(),
        ))
        game.currentPlayer = greece.civName
        val requestId = foreign.playerProjection(game, greece.civName).pendingTradeRequests.single().requestId
        foreign.declineTrade(game, greece.civName, requestId)
        Assert.assertThrows(IllegalStateException::class.java) {
            foreign.declineTrade(game, greece.civName, requestId)
        }
    }

    @Test
    fun counterTradeAtomicallyReplacesTheIncomingRequest() {
        val romeEngine = HeadlessGameEngine(serverContext { serverTime })
        val greeceEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        val game = romeEngine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.addGold(100)
        romeEngine.offerTrade(game, rome.civName, greece.civName, ProjectedTrade(
            listOf(ProjectedTradeOffer(Constants.flatGold, "Gold", 50, 0)), emptyList(),
        ))
        game.currentPlayer = greece.civName
        val incoming = greeceEngine.playerProjection(game, greece.civName).pendingTradeRequests.single()

        greeceEngine.counterTrade(game, greece.civName, incoming.requestId, ProjectedTrade(
            emptyList(), listOf(ProjectedTradeOffer(Constants.flatGold, "Gold", 25, 0)),
        ))

        Assert.assertTrue(greece.tradeRequests.isEmpty())
        Assert.assertEquals(1, rome.tradeRequests.size)
        Assert.assertEquals(greece.civName, rome.tradeRequests.single().requestingCiv)
    }

    @Test
    fun majorDiplomacyActionsAndFriendshipPromptAreCanonical() {
        val romeEngine = HeadlessGameEngine(serverContext { serverTime })
        val greeceEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        val game = romeEngine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)

        val partner = romeEngine.playerProjection(game, rome.civName).diplomacyPartners.single()
        Assert.assertTrue(partner.canDeclareWar)
        Assert.assertTrue(partner.canDenounce)
        Assert.assertTrue(partner.canOfferFriendship)

        romeEngine.offerFriendship(game, rome.civName, greece.civName)
        game.currentPlayer = greece.civName
        val prompt = greeceEngine.playerProjection(game, greece.civName).diplomacyPrompts.single()
        Assert.assertEquals(DiplomacyPromptType.Friendship, prompt.type)
        greeceEngine.respondToDiplomaticPrompt(game, greece.civName, prompt.promptId, true)
        Assert.assertTrue(rome.getDiplomacyManager(greece)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
        Assert.assertThrows(IllegalStateException::class.java) {
            greeceEngine.respondToDiplomaticPrompt(game, greece.civName, prompt.promptId, true)
        }
    }

    @Test
    fun diplomaticDemandResponseAndWarDeclarationRejectForgeryAndWrongActor() {
        val romeEngine = HeadlessGameEngine(serverContext { serverTime })
        val greeceEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        val game = romeEngine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)

        romeEngine.makeDiplomaticDemand(game, rome.civName, greece.civName, DiplomaticDemand.DoNotSettleNearUs)
        game.currentPlayer = greece.civName
        val prompt = greeceEngine.playerProjection(game, greece.civName).diplomacyPrompts.single()
        Assert.assertEquals(DiplomaticDemand.DoNotSettleNearUs, prompt.demand)
        greeceEngine.respondToDiplomaticPrompt(game, greece.civName, prompt.promptId, true)
        Assert.assertTrue(greece.getDiplomacyManager(rome)!!.otherCivDiplomacy().hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))

        Assert.assertThrows(IllegalStateException::class.java) {
            greeceEngine.declareWar(game, rome.civName, greece.civName)
        }
        game.currentPlayer = rome.civName
        romeEngine.declareWar(game, rome.civName, greece.civName)
        Assert.assertTrue(rome.isAtWarWith(greece))
        Assert.assertThrows(IllegalArgumentException::class.java) {
            romeEngine.denounceCivilization(game, rome.civName, greece.civName)
        }
    }

    @Test
    fun greatPersonChoiceIsProjectedPlacedAndConsumedCanonically() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(5)
        val actor = testGame.addCiv()
        actor.playerId = "account-1"
        testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        actor.greatPeople.freeGreatPeople = 1
        testGame.gameInfo.currentPlayer = actor.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val projection = engine.playerProjection(testGame.gameInfo, actor.civName)
        val choice = projection.selectableGreatPeople.first {
            !testGame.gameInfo.ruleset.units.getValue(it).isWaterUnit
        }
        val previousUnits = actor.units.getCivUnits().count()

        Assert.assertTrue(PendingEndTurnAction.PickGreatPerson in projection.pendingTurnActions)
        projection.selectableGreatPeople.firstOrNull {
            testGame.gameInfo.ruleset.units.getValue(it).isWaterUnit
        }?.let { unplaceableNavalChoice ->
            Assert.assertThrows(IllegalStateException::class.java) {
                engine.chooseGreatPerson(testGame.gameInfo, actor.civName, unplaceableNavalChoice)
            }
            Assert.assertEquals(1, actor.greatPeople.freeGreatPeople)
        }
        engine.chooseGreatPerson(testGame.gameInfo, actor.civName, choice)

        Assert.assertEquals(previousUnits + 1, actor.units.getCivUnits().count())
        Assert.assertTrue(actor.units.getCivUnits().any { it.name == choice })
        Assert.assertEquals(0, actor.greatPeople.freeGreatPeople)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.chooseGreatPerson(testGame.gameInfo, actor.civName, choice)
        }
    }

    @Test
    fun greatPersonChoiceEnforcesMayaPoolOwnershipAndTurn() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(5)
        val actor = testGame.addCiv()
        actor.playerId = "account-1"
        testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val other = testGame.addCiv()
        other.playerId = "account-2"
        actor.greatPeople.freeGreatPeople = 1
        actor.greatPeople.mayaLimitedFreeGP = 1
        val allowed = actor.greatPeople.getGreatPeople().first().name
        actor.greatPeople.longCountGPPool = hashSetOf(allowed)
        testGame.gameInfo.currentPlayer = actor.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })

        Assert.assertEquals(
            listOf(allowed),
            engine.playerProjection(testGame.gameInfo, actor.civName).selectableGreatPeople,
        )
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.chooseGreatPerson(testGame.gameInfo, actor.civName, "Warrior")
        }
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.chooseGreatPerson(testGame.gameInfo, actor.civName, allowed)
        }
        testGame.gameInfo.currentPlayer = other.civName
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.chooseGreatPerson(testGame.gameInfo, actor.civName, allowed)
        }
    }

    @Test
    fun pantheonChoiceIsProjectedValidatedAndCommittedCanonically() {
        val testGame = TestGame()
        val actor = testGame.addCiv()
        actor.playerId = "account-1"
        actor.religionManager.storedFaith = 10_000
        testGame.gameInfo.currentPlayer = actor.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val choice = engine.playerProjection(testGame.gameInfo, actor.civName).religionChoice!!
        val pantheon = choice.availableBeliefs.first { it.type.name == "Pantheon" }

        Assert.assertEquals(1, choice.requiredBeliefTypes.size)
        Assert.assertTrue(choice.availableReligionIcons.isEmpty())
        engine.chooseReligiousBeliefs(
            testGame.gameInfo, actor.civName, listOf(pantheon.name), null, null,
        )

        Assert.assertTrue(actor.religionManager.religion!!.hasBelief(pantheon.name))
        Assert.assertNull(engine.playerProjection(testGame.gameInfo, actor.civName).religionChoice)
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.chooseReligiousBeliefs(
                testGame.gameInfo, actor.civName, listOf(pantheon.name), null, null,
            )
        }
    }

    @Test
    fun religiousChoiceRejectsUnavailableDuplicateForeignAndOutOfTurnClaims() {
        val testGame = TestGame()
        val actor = testGame.addCiv().apply { playerId = "account-1" }
        val other = testGame.addCiv().apply { playerId = "account-2" }
        actor.religionManager.storedFaith = 10_000
        testGame.gameInfo.currentPlayer = actor.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })

        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.chooseReligiousBeliefs(
                testGame.gameInfo, actor.civName, listOf("Not a belief"), null, null,
            )
        }
        val belief = engine.playerProjection(testGame.gameInfo, actor.civName)
            .religionChoice!!.availableBeliefs.first().name
        val foreignEngine = HeadlessGameEngine(serverContext("account-2") { serverTime })
        Assert.assertThrows(IllegalStateException::class.java) {
            foreignEngine.chooseReligiousBeliefs(
                testGame.gameInfo, actor.civName, listOf(belief), null, null,
            )
        }
        testGame.gameInfo.currentPlayer = other.civName
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.chooseReligiousBeliefs(
                testGame.gameInfo, actor.civName, listOf(belief), null, null,
            )
        }
    }

    @Test
    fun foundingReligionDerivesIdentityBeliefSlotsHolyCityAndProphetConsumption() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val actor = testGame.addCiv().apply { playerId = "account-1" }
        val city = testGame.addCity(actor, testGame.getTile(HexCoord.Zero))
        val pantheon = testGame.gameInfo.ruleset.beliefs.values.first {
            it.type == com.unciv.models.ruleset.BeliefType.Pantheon
        }
        actor.religionManager.storedFaith = 10_000
        actor.religionManager.chooseBeliefs(listOf(pantheon))
        val prophet = testGame.addUnit("Great Prophet", actor, city.getCenterTile())
        prophet.religion = actor.religionManager.religion!!.name
        testGame.gameInfo.currentPlayer = actor.civName
        val engine = HeadlessGameEngine(serverContext { serverTime })
        Assert.assertTrue(ReligiousUnitAction.FoundReligion in
            engine.playerProjection(testGame.gameInfo, actor.civName)
                .ownUnits.single { it.id == prophet.id }.availableReligiousActions)
        engine.useReligiousUnit(
            testGame.gameInfo, actor.civName, prophet.id, ReligiousUnitAction.FoundReligion,
        )
        Assert.assertNull(actor.units.getUnitById(prophet.id))
        val choice = engine.playerProjection(testGame.gameInfo, actor.civName).religionChoice!!
        val chosenBeliefs = choice.requiredBeliefTypes.map { requiredType ->
            choice.availableBeliefs.first { it.type == requiredType }
        }.map { it.name }
        val icon = choice.availableReligionIcons.first()

        Assert.assertTrue(choice.requiresReligionIdentity)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.chooseReligiousBeliefs(
                testGame.gameInfo, actor.civName,
                List(choice.requiredBeliefTypes.size) { chosenBeliefs.first() }, icon, "Duplicate",
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.chooseReligiousBeliefs(
                testGame.gameInfo, actor.civName, chosenBeliefs, icon, "",
            )
        }
        engine.chooseReligiousBeliefs(
            testGame.gameInfo, actor.civName, chosenBeliefs, icon, "Server Faith",
        )

        Assert.assertEquals(icon, actor.religionManager.religion!!.name)
        Assert.assertEquals("Server Faith", actor.religionManager.religion!!.displayName)
        Assert.assertEquals(icon, city.religion.religionThisIsTheHolyCityOf)
        Assert.assertTrue(chosenBeliefs.all(actor.religionManager.religion!!::hasBelief))
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

    @Test
    fun cityStateGoldAndProtectionAreCanonicalAndProjectionBound() {
        val setup = testSetup().apply { gameParameters.numberOfCityStates = 1 }
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(setup).game
        val rome = game.getCivilization("Rome")
        val cityState = game.getAliveCityStates().single()
        rome.diplomacyFunctions.makeCivilizationsMeet(cityState)
        rome.addGold(500 - rome.gold)

        val before = engine.playerProjection(game, "Rome").cityStatePartners.single()
        Assert.assertEquals(cityState.civID, before.civilizationId)
        Assert.assertEquals(listOf(250, 500), before.availableGoldGifts)
        Assert.assertTrue(before.canPledgeProtection)

        engine.giftCityStateGold(game, "Rome", cityState.civID, 250)
        Assert.assertEquals(250, rome.gold)
        Assert.assertTrue(cityState.getDiplomacyManager(rome)!!.getInfluence() > 0)
        engine.setCityStateProtection(game, "Rome", cityState.civID, true)
        Assert.assertFalse(engine.playerProjection(game, "Rome").cityStatePartners.single().canPledgeProtection)

        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.giftCityStateGold(game, "Rome", cityState.civID, 251)
        }
        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.setCityStateProtection(game, "Rome", cityState.civID, false)
        }
    }

    @Test
    fun cityStateWarAndPeaceAreCanonical() {
        val setup = testSetup().apply { gameParameters.numberOfCityStates = 1 }
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(setup).game
        val rome = game.getCivilization("Rome")
        val cityState = game.getAliveCityStates().single()
        rome.diplomacyFunctions.makeCivilizationsMeet(cityState)

        Assert.assertTrue(engine.playerProjection(game, "Rome").cityStatePartners.single().canDeclareWar)
        engine.declareWar(game, "Rome", cityState.civID)
        Assert.assertTrue(rome.isAtWarWith(cityState))
        rome.getDiplomacyManager(cityState)!!.removeFlag(DiplomacyFlags.DeclaredWar)
        cityState.getDiplomacyManager(rome)!!.removeFlag(DiplomacyFlags.DeclaredWar)
        Assert.assertTrue(engine.playerProjection(game, "Rome").cityStatePartners.single().canNegotiatePeace)
        engine.negotiateCityStatePeace(game, "Rome", cityState.civID)
        Assert.assertFalse(rome.isAtWarWith(cityState))
    }

    @Test
    fun diplomaticMarriageDerivesCostAndCapturedCitiesFromCanonicalState() {
        val setup = testSetup().apply {
            gameParameters.numberOfCityStates = 1
            gameParameters.players[0].chosenCiv = "Austria"
        }
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(setup).game
        val austria = game.getCivilization("Austria")
        val cityState = game.getAliveCityStates().single()
        if (cityState.cities.isEmpty()) {
            val settler = cityState.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
            cityState.addCity(settler.currentTile.position, settler)
        }
        austria.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.getDiplomacyManager(austria)!!.addInfluence(100f)
        austria.getDiplomacyManager(cityState)!!.removeFlag(DiplomacyFlags.MarriageCooldown)
        austria.addGold(10_000 - austria.gold)
        val cityIds = cityState.cities.map { it.id }
        Assert.assertTrue("Austria marriage unique missing", austria.hasUnique(UniqueType.CityStateCanBeBoughtForGold))
        Assert.assertTrue("City-state alliance missing", cityState.getDiplomacyManager(austria)!!.isRelationshipLevelEQ(com.unciv.logic.civilization.diplomacy.RelationshipLevel.Ally))
        Assert.assertTrue("Marriage cost exceeds fixture gold", austria.gold >= cityState.cityStateFunctions.getDiplomaticMarriageCost())
        Assert.assertFalse("Marriage cooldown still present", austria.getDiplomacyManager(cityState)!!.hasFlag(DiplomacyFlags.MarriageCooldown))
        Assert.assertTrue("Canonical marriage unexpectedly unavailable", cityState.cityStateFunctions.canBeMarriedBy(austria))
        val projectedCost = engine.playerProjection(game, "Austria").cityStatePartners.single().diplomaticMarriageCost

        Assert.assertNotNull(projectedCost)
        val goldBefore = austria.gold
        engine.marryCityState(game, "Austria", cityState.civID)

        Assert.assertEquals(goldBefore - projectedCost!!, austria.gold)
        Assert.assertTrue(cityState.isDefeated())
        Assert.assertTrue(cityIds.all { id -> austria.cities.any { it.id == id } })
        Assert.assertEquals(cityIds.toSet(), austria.popupAlerts.filter { it.type == AlertType.DiplomaticMarriage }.map { it.value }.toSet())
    }

    @Test
    fun cityStateProtectionPromptResponsesAreCanonicalAndProjectionBound() {
        val setup = testSetup().apply { gameParameters.numberOfCityStates = 1 }
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(setup).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        val cityState = game.getAliveCityStates().single()
        rome.diplomacyFunctions.makeCivilizationsMeet(cityState)
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)

        rome.popupAlerts.add(PopupAlert(AlertType.BulliedProtectedMinor, "${greece.civID}@${cityState.civID}"))
        var prompt = engine.playerProjection(game, "Rome").diplomacyPrompts.single()
        Assert.assertEquals(DiplomacyPromptType.BulliedProtectedMinor, prompt.type)
        Assert.assertTrue(CityStateProtectionResponse.Condemn in prompt.availableCityStateResponses)
        engine.respondToCityStateProtectionPrompt(game, "Rome", prompt.promptId, CityStateProtectionResponse.Condemn)
        Assert.assertTrue(greece.getDiplomacyManager(rome)!!.hasModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.SidedWithProtectedMinor))
        Assert.assertTrue(rome.popupAlerts.none { it.type == AlertType.BulliedProtectedMinor })

        cityState.cityStateFunctions.addProtectorCiv(rome)
        rome.popupAlerts.add(PopupAlert(AlertType.AttackedProtectedMinor, "${greece.civID}@${cityState.civID}"))
        prompt = engine.playerProjection(game, "Rome").diplomacyPrompts.single()
        engine.respondToCityStateProtectionPrompt(game, "Rome", prompt.promptId, CityStateProtectionResponse.WithdrawProtection)
        Assert.assertFalse(cityState.cityStateFunctions.getProtectorCivs().contains(rome))

        rome.popupAlerts.add(PopupAlert(AlertType.AttackedAllyMinor, "${greece.civID}@${cityState.civID}"))
        prompt = engine.playerProjection(game, "Rome").diplomacyPrompts.single()
        val influenceBefore = cityState.getDiplomacyManager(rome)!!.getInfluence()
        engine.respondToCityStateProtectionPrompt(game, "Rome", prompt.promptId, CityStateProtectionResponse.DeclareWar)
        Assert.assertTrue(rome.isAtWarWith(greece))
        Assert.assertEquals(influenceBefore + 20f, cityState.getDiplomacyManager(rome)!!.getInfluence())
        Assert.assertTrue(rome.popupAlerts.none { it.type == AlertType.AttackedAllyMinor })
    }

    @Test
    fun espionageMovementAndCoupIntentAreCanonicalAndProjectionBound() {
        val setup = testSetup().apply { gameParameters.numberOfCityStates = 1 }
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(setup).game
        val rome = game.getCivilization("Rome")
        val greece = game.getCivilization("Greece")
        val cityState = game.getAliveCityStates().single()
        if (greece.cities.isEmpty()) {
            val settler = greece.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
            greece.addCity(settler.currentTile.position, settler)
        }
        val greekCity = greece.getCapital()!!
        greekCity.getCenterTile().setExplored(rome, true)
        val spy = rome.espionageManager.addSpy()

        var projected = engine.playerProjection(game, "Rome").spies.single()
        Assert.assertTrue(greekCity.id in projected.availableCityIds)
        engine.moveSpy(game, "Rome", spy.name, greekCity.id)
        Assert.assertEquals(greekCity, spy.getCity())
        Assert.assertEquals(SpyAction.Moving, spy.action)
        Assert.assertTrue(engine.playerProjection(game, "Rome").spies.single().canMoveToHideout)
        engine.moveSpy(game, "Rome", spy.name, null)
        Assert.assertTrue(spy.isIdle())

        if (cityState.cities.isEmpty()) {
            val settler = cityState.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
            cityState.addCity(settler.currentTile.position, settler)
        }
        rome.diplomacyFunctions.makeCivilizationsMeet(cityState)
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        greece.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.getDiplomacyManager(greece)!!.addInfluence(100f)
        val cityStateCity = cityState.getCapital()!!
        cityStateCity.getCenterTile().setExplored(rome, true)
        spy.moveTo(cityStateCity)
        spy.setAction(SpyAction.RiggingElections, 10)
        projected = engine.playerProjection(game, "Rome").spies.single()
        Assert.assertTrue(projected.canStageCoup)
        engine.setSpyCoup(game, "Rome", spy.name, true)
        Assert.assertEquals(SpyAction.Coup, spy.action)
        Assert.assertEquals(1, spy.turnsRemainingForAction)
        Assert.assertTrue(engine.playerProjection(game, "Rome").spies.single().canCancelCoup)
        engine.setSpyCoup(game, "Rome", spy.name, false)
        Assert.assertEquals(SpyAction.CounterIntelligence, spy.action)
        Assert.assertEquals(10, spy.turnsRemainingForAction)

        game.currentPlayer = "Greece"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.moveSpy(game, "Rome", spy.name, null)
        }
    }

    @Test
    fun eventChoiceIsPendingOpaqueAndExecutedOnlyByCanonicalWorker() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val event = Event().apply {
            name = "ServerEvent"
            text = "Choose a server-owned outcome"
            choices.add(EventChoice().apply {
                name = "golden-age"
                text = "Begin a golden age"
                uniques.add("Empire enters a [5]-turn Golden Age")
            })
            choices.add(EventChoice().apply {
                name = "free-policy"
                text = "Gain a policy"
                uniques.add("Free Social Policy")
            })
        }
        game.ruleset.events[event.name] = event
        rome.popupAlerts.add(PopupAlert(AlertType.Event, event.name))

        val prompt = engine.playerProjection(game, "Rome").eventPrompts.single()
        Assert.assertEquals(event.name, prompt.eventName)
        Assert.assertEquals(listOf("Begin a golden age", "Gain a policy"), prompt.choices.map { it.text })
        Assert.assertTrue(prompt.promptId.matches(Regex("[0-9a-f]{64}")))
        Assert.assertTrue(prompt.choices.all { it.choiceId.matches(Regex("[0-9a-f]{64}")) })
        Assert.assertFalse(rome.goldenAges.isGoldenAge())

        engine.resolveEventChoice(game, "Rome", prompt.promptId, prompt.choices.first().choiceId)

        Assert.assertTrue(rome.goldenAges.isGoldenAge())
        Assert.assertTrue(rome.popupAlerts.none { it.type == AlertType.Event })
        Assert.assertTrue(engine.playerProjection(game, "Rome").eventPrompts.isEmpty())
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.resolveEventChoice(game, "Rome", prompt.promptId, prompt.choices.last().choiceId)
        }
    }

    @Test
    fun greatPersonActionIsProjectedAndExecutedByCanonicalWorker() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        if (rome.cities.isEmpty()) {
            val settler = rome.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
            rome.addCity(settler.currentTile.position, settler)
        }
        val scientistType = game.ruleset.units.values.single { it.hasUnique(UniqueType.CanHurryResearch) }
        val scientist = rome.units.addUnit(scientistType, rome.getCapital())!!
        rome.tech.techsToResearch.add("Pottery")
        rome.tech.scienceOfLast8Turns.fill(100)

        val projected = engine.playerProjection(game, "Rome").ownUnits.single { it.id == scientist.id }
        Assert.assertEquals(listOf(GreatPersonUnitAction.HurryResearch), projected.availableGreatPersonActions)

        engine.useGreatPersonUnit(game, "Rome", scientist.id, GreatPersonUnitAction.HurryResearch)

        Assert.assertTrue(rome.tech.isResearched("Pottery") || rome.tech.researchOfTech("Pottery") > 0)
        Assert.assertTrue(scientist.isDestroyed)
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.useGreatPersonUnit(game, "Rome", scientist.id, GreatPersonUnitAction.HurryResearch)
        }
    }

    @Test
    fun unitGiftDerivesRecipientInfluenceAndOwnershipFromCanonicalState() {
        val setup = testSetup().apply { gameParameters.numberOfCityStates = 1 }
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(setup).game
        val rome = game.getCivilization("Rome")
        val cityState = game.getAliveCityStates().single()
        if (cityState.cities.isEmpty()) {
            val settler = cityState.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
            cityState.addCity(settler.currentTile.position, settler)
        }
        rome.diplomacyFunctions.makeCivilizationsMeet(cityState)
        if (rome.cities.isEmpty()) {
            val settler = rome.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
            rome.addCity(settler.currentTile.position, settler)
        }
        val recipientTile = game.tileMap.values.first {
            it.getOwner() == cityState && it.militaryUnit == null
        }
        val warriorType = game.ruleset.units.values.first { it.isMilitary && !it.isWaterUnit }
        val warrior = rome.units.addUnit(warriorType, rome.getCapital())!!
        warrior.putInTile(recipientTile)

        val projected = engine.playerProjection(game, "Rome").ownUnits.single { it.id == warrior.id }
        Assert.assertTrue(projected.canGift)
        Assert.assertEquals(0f, cityState.getDiplomacyManager(rome)!!.getInfluence(), 0.001f)

        engine.giftUnit(game, "Rome", warrior.id)

        Assert.assertEquals(cityState, warrior.civ)
        Assert.assertEquals(5f, cityState.getDiplomacyManager(rome)!!.getInfluence(), 0.001f)
        Assert.assertNull(rome.units.getUnitById(warrior.id))
        Assert.assertEquals(warrior, cityState.units.getUnitById(warrior.id))
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.giftUnit(game, "Rome", warrior.id)
        }
    }

    @Test
    fun unitTransformationIsProjectedAndExecutedByCanonicalWorker() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        if (rome.cities.isEmpty()) {
            val settler = rome.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
            rome.addCity(settler.currentTile.position, settler)
        }
        val transformable = com.unciv.models.ruleset.unit.BaseUnit().apply {
            name = "Server Transformer"
            cost = 40
            movement = 2
            strength = 8
            unitType = "Melee"
            uniques.add("Can transform to [Scout]")
            uniques.add("Can transform to [Scout]")
            setRuleset(game.ruleset)
        }
        game.ruleset.units[transformable.name] = transformable
        val warrior = rome.units.addUnit(transformable, rome.getCapital())!!
        val originalId = warrior.id

        val projected = engine.playerProjection(game, "Rome").ownUnits.single { it.id == originalId }
        Assert.assertEquals(listOf("Scout", "Scout"), projected.availableTransformActions.map { it.targetUnitName })
        Assert.assertEquals(2, projected.availableTransformActions.map { it.actionId }.distinct().size)
        val actionId = projected.availableTransformActions.last().actionId
        Assert.assertTrue(actionId.matches(Regex("[0-9a-f]{64}")))

        engine.transformUnit(game, "Rome", originalId, actionId)

        val transformed = rome.units.getUnitById(originalId)!!
        Assert.assertEquals("Scout", transformed.name)
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.transformUnit(game, "Rome", originalId, actionId)
        }
    }

    @Test
    fun genericUnitTriggerIsOpaqueProjectedAndExecutedByCanonicalWorker() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        if (rome.cities.isEmpty()) {
            val settler = rome.units.getCivUnits().first { it.hasUnique(UniqueType.FoundCity) }
            rome.addCity(settler.currentTile.position, settler)
        }
        val triggerUnit = com.unciv.models.ruleset.unit.BaseUnit().apply {
            name = "Server Trigger"
            cost = 40
            movement = 2
            strength = 8
            unitType = "Melee"
            uniques.add("Gain [100] [Gold] <for all movement>")
            uniques.add("Gain [100] [Gold] <for all movement>")
            setRuleset(game.ruleset)
        }
        game.ruleset.units[triggerUnit.name] = triggerUnit
        val unit = rome.units.addUnit(triggerUnit, rome.getCapital())!!
        val beforeGold = rome.gold

        val actions = engine.playerProjection(game, "Rome").ownUnits
            .single { it.id == unit.id }.availableTriggerActions
        Assert.assertEquals(2, actions.size)
        Assert.assertEquals(2, actions.map { it.actionId }.distinct().size)
        Assert.assertTrue(actions.all { it.actionId.matches(Regex("[0-9a-f]{64}")) })

        engine.triggerUnitUnique(game, "Rome", unit.id, actions.last().actionId)

        Assert.assertEquals(beforeGold + 100, rome.gold)
        Assert.assertEquals(0f, unit.currentMovement, 0.001f)
        Assert.assertThrows(IllegalStateException::class.java) {
            engine.triggerUnitUnique(game, "Rome", unit.id, actions.last().actionId)
        }
    }

    @Test
    fun movementLegalityProjectionUsesCanonicalRulesWithoutLeakingFogOrForeignOptions() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val game = engine.createGame(testSetup()).game
        val rome = game.getCivilization("Rome")
        val projection = engine.playerProjection(game, "Rome")
        val visibleCoordinates = projection.exploredTiles.asSequence()
            .filter { it.visible }
            .map { it.x to it.y }
            .toSet()

        for (projected in projection.ownUnits) {
            val unit = rome.units.getUnitById(projected.id)!!
            val expectedMoves = unit.movement.getDistanceToTiles().keys.asSequence()
                .filter { tile ->
                    tile != unit.getTile() && when {
                        tile in rome.viewableTiles -> unit.movement.canMoveTo(tile)
                        else -> unit.movement.isUnknownTileWeShouldAssumeToBePassable(tile) &&
                            !unit.baseUnit.movesLikeAirUnits
                    }
                }
                .map { ProjectedMovementDestination(it.position.x, it.position.y) }
                .distinct()
                .sortedWith(compareBy<ProjectedMovementDestination> { it.x }.thenBy { it.y })
                .toList()
            val expectedSwaps = unit.movement.getUnitSwappableTiles()
                .filter { it in rome.viewableTiles }
                .map { ProjectedMovementDestination(it.position.x, it.position.y) }
                .distinct()
                .sortedWith(compareBy<ProjectedMovementDestination> { it.x }.thenBy { it.y })
                .toList()
            Assert.assertEquals(expectedMoves, projected.moveDestinations)
            Assert.assertEquals(expectedSwaps, projected.swapDestinations)
            val exploredCoordinates = projection.exploredTiles
                .map { it.x to it.y }
                .toSet()
            Assert.assertTrue(projected.moveDestinations.all {
                (it.x to it.y) in visibleCoordinates || (it.x to it.y) !in exploredCoordinates
            })
            Assert.assertTrue(projected.swapDestinations
                .all { (it.x to it.y) in visibleCoordinates })
        }
        Assert.assertTrue(projection.visibleForeignUnits
            .all {
                it.moveDestinations.isEmpty() && it.swapDestinations.isEmpty() &&
                    it.attackTargets.isEmpty() && it.nuclearTargetCandidates.isEmpty() &&
                    it.airSweepTargets.isEmpty()
            })

        game.currentPlayer = "Greece"
        val outOfTurn = engine.playerProjection(game, "Rome")
        Assert.assertTrue(outOfTurn.ownUnits
            .all {
                it.moveDestinations.isEmpty() && it.swapDestinations.isEmpty() &&
                    it.attackTargets.isEmpty() && it.nuclearTargetCandidates.isEmpty() &&
                    it.airSweepTargets.isEmpty()
            })
        Assert.assertTrue(outOfTurn.ownCities.all { it.bombardTargets.isEmpty() })
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
