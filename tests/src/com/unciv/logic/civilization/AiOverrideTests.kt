package com.unciv.logic.civilization

import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-civilization AI overrides authored by a multiplayer lobby host. They are
 * canonical state, so single-player and hotseat read them through exactly the
 * same accessors.
 */
@RunWith(GdxTestRunner::class)
class AiOverrideTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame()
    }

    @Test
    fun pinnedAiDifficultyOverridesTheMatchWideAiDifficulty() {
        val plain = game.addCiv()
        val pinned = game.addCiv()
        val matchDefault = plain.getDifficulty().name

        pinned.aiDifficultyOverride = "Deity"

        assertEquals("Deity", pinned.getDifficulty().name)
        assertNotEquals("Deity", matchDefault)
        // Only the pinned seat moves.
        assertEquals(matchDefault, plain.getDifficulty().name)
    }

    @Test
    fun anUnknownOrBlankOverrideFallsBackToTheMatchDifficulty() {
        val civilization = game.addCiv()
        val matchDefault = civilization.getDifficulty().name

        civilization.aiDifficultyOverride = "Not A Difficulty"
        assertEquals(matchDefault, civilization.getDifficulty().name)

        civilization.aiDifficultyOverride = ""
        assertEquals(matchDefault, civilization.getDifficulty().name)
    }

    @Test
    fun aHumanPlayerIsNeverAffectedByAnAiOverride() {
        val human = game.addCiv(isPlayer = true)
        val expected = game.gameInfo.getDifficulty().name

        human.aiDifficultyOverride = "Deity"

        assertEquals(expected, human.getDifficulty().name)
    }

    @Test
    fun overridesSurviveTheCanonicalCloneUsedForSnapshots() {
        val civilization = game.addCiv()
        civilization.aiDifficultyOverride = "Deity"
        civilization.personalityOverride = "Alexander"

        val clone = civilization.clone()

        assertEquals("Deity", clone.aiDifficultyOverride)
        assertEquals("Alexander", clone.personalityOverride)
    }
}
