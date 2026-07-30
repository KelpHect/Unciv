package com.unciv.logic.multiplayer.authoritative

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ApiV3SetupDefaultsTests {
    @Before
    fun loadRulesets() {
        if (RulesetCache.isEmpty()) RulesetCache.loadRulesets(noMods = true)
    }

    @Test
    fun defaultLobbySubmitsOnlyPlayerSelectableVictoryConditions() {
        val ruleset = RulesetCache[BaseRuleset.Civ_V_Vanilla.fullName]!!
        val setup = createDefaultApiV3GameSetup(ruleset, "America", 2)
        val selectableVictories = ruleset.victories.values
            .filterNot { it.hiddenInVictoryScreen }
            .mapTo(sortedSetOf()) { it.name }

        assertEquals(selectableVictories, setup.victoryTypes.toSortedSet())
        assertFalse("Time" in setup.victoryTypes)
        assertTrue(setup.victoryTypes.isNotEmpty())
        assertEquals("America", setup.ownerCivilizationId)
        assertTrue(setup.majorCivilizations >= 2)
    }
}
