package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A projection-only client renders its cities without any [com.unciv.logic.GameInfo].
 * If city, tile or population code reaches back through `civ.gameInfo` again, the
 * online map silently loses every city - so this pins the gameInfo-free path.
 */
@RunWith(GdxTestRunner::class)
class ProjectionCityRenderingTests {
    private lateinit var ruleset: Ruleset

    @Before
    fun loadRuleset() {
        RulesetCache.loadRulesets(noMods = true)
        ruleset = RulesetCache.getVanillaRuleset()
    }

    @Test
    fun cityRendersFromProjectionWithoutGameInfo() {
        val map = TileMap(4)
        map.startingLocations.clear()
        for (x in 0..2) for (y in 0..2) {
            map.tileList.add(Tile().apply {
                position = HexCoord(x, y)
                baseTerrain = Constants.grassland
            })
        }
        map.setTransients(ruleset, setUnitCivTransients = false)

        // Aliased because inside `apply` a bare `ruleset` would resolve to
        // Civilization.ruleset, which reads gameInfo until attachRuleset ran.
        val serverRuleset = ruleset
        val civ = Civilization("Rome").apply {
            attachRuleset(serverRuleset)
            nation = serverRuleset.nations.values.first()
        }

        val city = City()
        city.id = "city-1"
        city.name = "Roma"
        city.location = HexCoord(1, 1)
        city.health = 200
        city.population.setProjectedPopulation(5)
        city.tiles = hashSetOf(HexCoord(1, 1), HexCoord(1, 2))
        city.setTransients(civ, map)
        civ.cities = listOf(city)
        city.expansion.setTransients()

        Assert.assertTrue("City centre must render", map[HexCoord(1, 1)].isCityCenter())
        Assert.assertSame(city, map[HexCoord(1, 1)].getCity())
        Assert.assertSame("Owned tile must draw the border", city, map[HexCoord(1, 2)].getCity())
        Assert.assertNull("Unowned tile must stay neutral", map[HexCoord(0, 0)].getCity())
        Assert.assertEquals(5, city.population.population)
        Assert.assertEquals(ruleset, city.getRuleset())
    }

    /**
     * The projected map only holds tiles the server actually sent, and
     * `City.getTiles()` indexes the map with `tileMap[...]!!`. A city owning a
     * tile outside that set must not take the whole world screen down.
     */
    @Test
    fun cityOwningUnsentTilesStillRenders() {
        val map = TileMap(4)
        map.startingLocations.clear()
        // Deliberately sparse: (1,0) is a hole inside the bounding box and
        // (5,5) is outside it entirely.
        for (position in listOf(HexCoord(0, 0), HexCoord(1, 1), HexCoord(2, 2))) {
            map.tileList.add(Tile().apply {
                this.position = position
                baseTerrain = Constants.grassland
            })
        }
        map.setTransients(ruleset, setUnitCivTransients = false)

        val serverRuleset = ruleset
        val civ = Civilization("Rome").apply {
            attachRuleset(serverRuleset)
            nation = serverRuleset.nations.values.first()
        }

        // The hazard the projection map guards against: an unsent coordinate is
        // not a null tile, it throws - so an unfiltered city.tiles kills the screen.
        Assert.assertNull(map.getIfTileExistsOrNull(1, 0))
        Assert.assertNull(map.getIfTileExistsOrNull(5, 5))
        Assert.assertThrows(Exception::class.java) { map[HexCoord(1, 0)] }
        Assert.assertThrows(Exception::class.java) { map[HexCoord(5, 5)] }

        // What the projection map builds: only coordinates it actually holds.
        val city = City()
        city.id = "city-1"
        city.name = "Roma"
        city.location = HexCoord(1, 1)
        city.tiles = hashSetOf(HexCoord(1, 1), HexCoord(1, 0), HexCoord(5, 5))
            .mapNotNullTo(HashSet()) { map.getIfTileExistsOrNull(it.x, it.y)?.position }
        city.setTransients(civ, map)
        civ.cities = listOf(city)
        city.expansion.setTransients()

        Assert.assertTrue(map[HexCoord(1, 1)].isCityCenter())
        Assert.assertEquals(1, city.getTiles().count())
    }
}
