package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
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

        engine.endTurn(game)

        Assert.assertEquals(serverTime + 5_000L, game.currentTurnStartTime)
    }

    @Test
    fun aSnapshotReloadsToTheSameCanonicalHash() {
        val engine = HeadlessGameEngine(serverContext { serverTime })
        val created = engine.createGame(testSetup()).game

        val reloaded = engine.loadSnapshot(engine.serializeSnapshot(created))

        Assert.assertEquals(engine.stateHash(created), engine.stateHash(reloaded))
    }

    private fun testSetup(): GameSetupInfo {
        val parameters = GameParameters().apply {
            numberOfCityStates = 0
            players.clear()
            players.add(Player("Rome", PlayerType.Human))
            players.add(Player("Greece"))
        }
        val mapParameters = MapParameters().apply {
            mapSize = MapSize.Tiny
            seed = 42L
        }
        return GameSetupInfo(parameters, mapParameters)
    }

    private fun vanillaManifest() = RulesetManifest(
        engineBuild = "test-engine-build",
        baseRuleset = ContentAddressedRuleset("Civ V - Vanilla", "0".repeat(64)),
    )

    private fun serverContext(clock: () -> Long) = GameExecutionContext.authoritative(
        actorId = "account-1",
        rulesetManifest = vanillaManifest(),
        clockMillis = clock,
    )
}
