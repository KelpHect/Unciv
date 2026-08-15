package com.unciv.ui.components.tilegroups

import com.unciv.dev.FontDesktop
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedCity
import com.unciv.logic.multiplayer.authoritative.ProjectedCityTileState
import com.unciv.logic.multiplayer.authoritative.ProjectedForeignCity
import com.unciv.logic.multiplayer.authoritative.ProjectedTargetCoordinate
import com.unciv.logic.multiplayer.authoritative.ProjectedPolicies
import com.unciv.logic.multiplayer.authoritative.ProjectedResearch
import com.unciv.logic.multiplayer.authoritative.ProjectedTileVisibility
import com.unciv.logic.multiplayer.authoritative.ProjectedUnit
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.tilegroups.layers.TileLayerCityButton
import com.unciv.ui.images.ImageGetter
import com.unciv.view.CivView
import com.unciv.view.GameView
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real API-v3 projection world map and the real tile renderer over it.
 *
 * [com.unciv.ui.screens.multiplayerscreens.AuthoritativeProjectionWorldMap] is
 * `internal` to the core module, so it is reached reflectively - the point of this
 * test is to execute the actual production class, not a re-implementation of it.
 */
@RunWith(GdxTestRunner::class)
class ProjectionWorldMapRenderTests {
    private lateinit var testGame: TestGame
    private lateinit var ruleset: Ruleset

    @Before
    fun setUp() {
        testGame = TestGame()
        ruleset = testGame.ruleset
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(ruleset)
        TileSetCache.loadTileSetConfigs()
    }

    private fun tileVisibility(x: Int, y: Int, terrain: String = "Grassland") =
        ProjectedTileVisibility(
            x = x,
            y = y,
            visible = true,
            baseTerrain = terrain,
            terrainFeatures = emptyList(),
            naturalWonderName = null,
            resourceName = null,
            resourceAmount = null,
        )

    /** Explored 3x3 block, minus (2,2) which stays a genuine hole in the map. */
    private fun exploredBlock(): List<ProjectedTileVisibility> =
        (0..2).flatMap { x -> (0..2).map { y -> x to y } }
            .filterNot { it == 2 to 2 }
            .map { tileVisibility(it.first, it.second) }

    private fun projection(
        cities: List<ProjectedCity>,
        units: List<ProjectedUnit> = emptyList(),
        foreignCities: List<ProjectedForeignCity> = emptyList(),
    ) = PlayerProjection(
        civilizationId = ruleset.nations.keys.first(),
        turn = 5,
        currentPlayerCivilizationId = ruleset.nations.keys.first(),
        isCurrentTurn = true,
        pendingTurnActions = emptyList(),
        research = ProjectedResearch(
            currentTechnology = null,
            researchedTechnologies = emptyList(),
            queue = emptyList(),
            queueEntries = emptyList(),
            overflowScience = 0,
            selectableTargets = emptyList(),
            appendableTargets = emptyList(),
            freeTechnologyChoices = emptyList(),
            completionPrompts = emptyList(),
        ),
        policies = ProjectedPolicies(
            storedCulture = 0,
            cultureNeededForNextPolicy = 0,
            freePolicies = 0,
            adoptedPolicies = emptyList(),
            selectablePolicies = emptyList(),
        ),
        gold = 0,
        knownCivilizations = emptyList(),
        ownCities = cities,
        ownUnits = units,
        exploredTiles = exploredBlock(),
        visibleForeignUnits = emptyList(),
        visibleForeignCities = foreignCities,
    )

    /** Builds the real production world map reflectively (it is module-internal). */
    private fun buildWorldMap(projection: PlayerProjection): Triple<TileMap, GameView, Civilization> {
        val type = Class.forName(
            "com.unciv.ui.screens.multiplayerscreens.AuthoritativeProjectionWorldMap"
        )
        val constructor = type.declaredConstructors.single()
        constructor.isAccessible = true
        val world = constructor.newInstance(projection, ruleset)

        fun read(name: String): Any {
            val getter = type.methods.single { it.name == name && it.parameterCount == 0 }
            getter.isAccessible = true
            return getter.invoke(world)
        }
        return Triple(
            read("getTileMap") as TileMap,
            read("getGameView") as GameView,
            read("getViewer") as Civilization,
        )
    }

    @Test
    fun projectedCityIsMaterializedWithBordersAndSurvivesUnsentTiles() {
        val city = ProjectedCity(
            id = "city-1",
            name = "Roma",
            x = 1,
            y = 1,
            population = 4,
            health = 180,
            constructionQueue = emptyList(),
            availableConstructions = emptyList(),
            tileStates = listOf(
                // Owned and worked, inside the projection.
                ProjectedCityTileState(1, 1, true, "city-1", "city-1", true, false),
                ProjectedCityTileState(0, 1, true, "city-1", "city-1", true, false),
                ProjectedCityTileState(1, 2, true, "city-1", null, false, false),
                // Owned per the server but never sent as an explored tile: (2,2) is a
                // hole in the map and (9,9) is outside it. Both are fatal if not filtered.
                ProjectedCityTileState(2, 2, true, "city-1", "city-1", true, false),
                ProjectedCityTileState(9, 9, true, "city-1", "city-1", true, false),
            ),
        )

        val (tileMap, _, viewer) = buildWorldMap(projection(listOf(city)))

        Assert.assertEquals("City must exist on the projected map", 1, viewer.cities.size)
        val materialized = viewer.cities.single()
        Assert.assertEquals("Roma", materialized.name)
        Assert.assertEquals("city-1", materialized.id)
        Assert.assertEquals(4, materialized.population.population)
        Assert.assertEquals(180, materialized.health)

        Assert.assertTrue("Centre tile must render as a city centre",
            tileMap[HexCoord(1, 1)].isCityCenter())
        Assert.assertSame("Owned tile must draw this city's border",
            materialized, tileMap[HexCoord(0, 1)].getCity())
        Assert.assertSame(materialized, tileMap[HexCoord(1, 2)].getCity())
        Assert.assertNull("Unowned tile must stay neutral", tileMap[HexCoord(0, 0)].getCity())

        // The unsent coordinates were dropped, so nothing indexes a missing tile.
        Assert.assertEquals(3, materialized.tiles.size)
        Assert.assertEquals(3, materialized.getTiles().count())
        Assert.assertEquals(2, materialized.getWorkedTiles().count())
        Assert.assertTrue(materialized.isWorked(tileMap[HexCoord(0, 1)]))
        Assert.assertFalse(materialized.isWorked(tileMap[HexCoord(1, 2)]))
    }

    @Test
    fun realTileRendererDrawsProjectedCityWithoutAWorldScreen() {
        val city = ProjectedCity(
            id = "city-1",
            name = "Roma",
            x = 1,
            y = 1,
            population = 4,
            health = 180,
            constructionQueue = emptyList(),
            availableConstructions = emptyList(),
            tileStates = listOf(
                ProjectedCityTileState(1, 1, true, "city-1", "city-1", true, false),
                ProjectedCityTileState(0, 1, true, "city-1", "city-1", true, false),
            ),
        )
        // A unit garrisoned on the city centre and one on an owned border tile:
        // this activates the unit-flag layers and isBlockaded over owned territory.
        val units = listOf(
            ProjectedUnit(
                id = 1,
                name = "Warrior",
                civilizationId = ruleset.nations.keys.first(),
                x = 1,
                y = 1,
                health = 100,
                currentMovement = 2f,
            ),
            ProjectedUnit(
                id = 2,
                name = "Warrior",
                civilizationId = ruleset.nations.keys.first(),
                x = 0,
                y = 1,
                health = 70,
                currentMovement = 1f,
            ),
        )
        val (tileMap, gameView, _) = buildWorldMap(projection(listOf(city), units))

        // There is deliberately no WorldScreen: this is exactly the state a V3
        // client renders in, and every CityButton path dereferences one.
        Assert.assertNull(com.unciv.UncivGame.Current.worldScreen)

        val tileSetStrings = TileSetStrings(ruleset, com.unciv.UncivGame.Current.settings)
        val groups = tileMap.values.map {
            WorldTileGroup(gameView.tileMapView.getTile(it), tileSetStrings)
        }
        // The real render call - this is what crashed before the guard.
        for (group in groups) group.update(gameView.civView)

        val centreGroup = groups.single { it.tile.position == HexCoord(1, 1) }
        Assert.assertTrue("The city centre tile must have rendered",
            centreGroup.tile.isCityCenter())
        val cityButtonLayer: TileLayerCityButton = centreGroup.layerCityButton
        Assert.assertFalse(
            "A projection client has no WorldScreen, so no CityButton may be built",
            cityButtonLayer.hasButton(),
        )

        // Units and owned territory coexist: the garrison renders on the city tile
        // and the owned tile is not reported blockaded by its owner's own unit.
        Assert.assertNotNull("Garrison must render on the city centre",
            tileMap[HexCoord(1, 1)].militaryUnit)
        Assert.assertNotNull(tileMap[HexCoord(0, 1)].militaryUnit)
        Assert.assertFalse("Own unit must not blockade own tile",
            tileMap[HexCoord(0, 1)].isBlockaded())
    }

    @Test
    fun visibleRivalCityRendersWithItsOwnBordersThroughTheSamePath() {
        val rivalNation = ruleset.nations.keys.elementAt(1)
        val rival = ProjectedForeignCity(
            id = "rival-1",
            name = "Athens",
            civilizationId = rivalNation,
            x = 2,
            y = 0,
            ownedTiles = listOf(
                ProjectedTargetCoordinate(2, 0),
                ProjectedTargetCoordinate(2, 1),
                // Never sent as an explored tile - must be dropped, not fatal.
                ProjectedTargetCoordinate(7, 7),
            ),
        )
        val own = ProjectedCity(
            id = "city-1",
            name = "Roma",
            x = 0,
            y = 0,
            population = 3,
            health = 200,
            constructionQueue = emptyList(),
            availableConstructions = emptyList(),
            tileStates = listOf(
                ProjectedCityTileState(0, 0, true, "city-1", "city-1", true, false),
            ),
        )
        val (tileMap, gameView, viewer) = buildWorldMap(
            projection(listOf(own), foreignCities = listOf(rival)),
        )

        val rivalCity = tileMap[HexCoord(2, 0)].getCity()
        Assert.assertNotNull("The rival city must exist on the map", rivalCity)
        Assert.assertEquals("Athens", rivalCity!!.name)
        Assert.assertTrue(tileMap[HexCoord(2, 0)].isCityCenter())
        Assert.assertSame("Its disclosed border tile must be drawn as its territory",
            rivalCity, tileMap[HexCoord(2, 1)].getCity())

        // The rival belongs to a different civilization than the viewer, so the
        // renderer colours it as foreign rather than as the player's own.
        Assert.assertNotSame(viewer, rivalCity.civ)
        Assert.assertEquals(rivalNation, rivalCity.civ.civName)
        Assert.assertEquals("Rival cities must never join the viewer's own cities",
            listOf("Roma"), viewer.cities.map { it.name })
        Assert.assertSame(viewer, tileMap[HexCoord(0, 0)].getCity()!!.civ)

        // And the real renderer draws all of it without a WorldScreen.
        val tileSetStrings = TileSetStrings(ruleset, com.unciv.UncivGame.Current.settings)
        val groups = tileMap.values.map {
            WorldTileGroup(gameView.tileMapView.getTile(it), tileSetStrings)
        }
        for (group in groups) group.update(gameView.civView)
        Assert.assertFalse(
            groups.single { it.tile.position == HexCoord(2, 0) }.layerCityButton.hasButton(),
        )
    }

    /**
     * The Rust boundary rejects a "foreign" city owned by the viewer, but the
     * client must not depend on that: resolving such a city would alias the
     * viewer's own Civilization and wipe the player's cities off their own map.
     */
    @Test
    fun aForeignCityClaimingTheViewersCivDoesNotEraseTheViewersOwnCities() {
        val viewerNation = ruleset.nations.keys.first()
        val own = ProjectedCity(
            id = "city-1",
            name = "Roma",
            x = 0,
            y = 0,
            population = 3,
            health = 200,
            constructionQueue = emptyList(),
            availableConstructions = emptyList(),
            tileStates = listOf(
                ProjectedCityTileState(0, 0, true, "city-1", "city-1", true, false),
            ),
        )
        val impostor = ProjectedForeignCity(
            id = "impostor",
            name = "NotYours",
            civilizationId = viewerNation,
            x = 2,
            y = 0,
            ownedTiles = listOf(ProjectedTargetCoordinate(2, 0)),
        )

        val (tileMap, _, viewer) = buildWorldMap(
            projection(listOf(own), foreignCities = listOf(impostor)),
        )

        Assert.assertEquals(
            "The player's own city must survive a foreign entry claiming their civ",
            listOf("Roma"),
            viewer.cities.map { it.name },
        )
        Assert.assertSame(viewer, tileMap[HexCoord(0, 0)].getCity()!!.civ)
        Assert.assertNull("The impostor city must be dropped, not rendered",
            tileMap[HexCoord(2, 0)].getCity())
    }

    @Test
    fun guardConditionIsFalseForARealGame() {
        // The guard keys on the viewing civ having no GameInfo. A real game always
        // has one, so city buttons keep rendering in single-player and hotseat.
        val realCiv = testGame.addCiv(isPlayer = true)
        Assert.assertNotNull(realCiv.gameInfoOrNull)
        Assert.assertNotNull(CivView(realCiv, realCiv, false, GameView(testGame.gameInfo, realCiv))
            .getCiv().gameInfoOrNull)
    }
}
