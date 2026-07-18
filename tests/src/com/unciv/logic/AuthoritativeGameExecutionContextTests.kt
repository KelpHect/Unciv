package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
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
