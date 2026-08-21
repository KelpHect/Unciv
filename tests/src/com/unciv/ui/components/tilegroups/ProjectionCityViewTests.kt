package com.unciv.ui.components.tilegroups

import com.unciv.dev.FontDesktop
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedCity
import com.unciv.logic.multiplayer.authoritative.ProjectedCityTileState
import com.unciv.logic.multiplayer.authoritative.ProjectedPolicies
import com.unciv.logic.multiplayer.authoritative.ProjectedResearch
import com.unciv.logic.multiplayer.authoritative.ProjectedTileVisibility
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.view.GameView
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data the real city screen reads, over a projection city.
 *
 * `CityScreen` itself extends `BaseScreen` and needs a live GL context, so it
 * cannot be constructed here. What *can* be driven is every `CityView` and
 * `CityConstructionsView` call its widgets make - which is where a missing
 * `gameInfo` fallback would surface, exactly as the tile-yield crash did.
 */
@RunWith(GdxTestRunner::class)
class ProjectionCityViewTests {
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

    private fun projection() = PlayerProjection(
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
        gold = 250,
        knownCivilizations = emptyList(),
        ownCities = listOf(
            ProjectedCity(
                id = "city-1",
                name = "Roma",
                x = 1,
                y = 1,
                population = 4,
                health = 200,
                constructionQueue = emptyList(),
                availableConstructions = emptyList(),
                tileStates = listOf(
                    ProjectedCityTileState(1, 1, true, "city-1", "city-1", true, false),
                    ProjectedCityTileState(0, 1, true, "city-1", "city-1", true, false),
                ),
            ),
        ),
        ownUnits = emptyList(),
        exploredTiles = (0..2).flatMap { x -> (0..2).map { y -> x to y } }
            .map {
                ProjectedTileVisibility(
                    x = it.first, y = it.second, visible = true,
                    baseTerrain = "Grassland", terrainFeatures = emptyList(),
                    naturalWonderName = null, resourceName = null, resourceAmount = null,
                )
            },
        visibleForeignUnits = emptyList(),
    )

    private fun buildWorldMap(): Triple<TileMap, GameView, Civilization> {
        val type = Class.forName(
            "com.unciv.ui.screens.multiplayerscreens.AuthoritativeProjectionWorldMap"
        )
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val world = constructor.newInstance(projection(), ruleset)
        fun read(name: String): Any = type.methods
            .single { it.name == name && it.parameterCount == 0 }
            .apply { isAccessible = true }
            .invoke(world)
        return Triple(
            read("getTileMap") as TileMap,
            read("getGameView") as GameView,
            read("getViewer") as Civilization,
        )
    }

    @Test
    fun theCityViewTheCityScreenReadsWorksOverAProjection() {
        val (tileMap, gameView, viewer) = buildWorldMap()
        val city = viewer.cities.single()
        val cityView = gameView.getCityView(city)

        // Header and identity, as CityScreen and its picker table read them.
        Assert.assertEquals("Roma", cityView.name)
        Assert.assertEquals(HexCoord(1, 1), cityView.location)
        Assert.assertEquals(200, cityView.getHealth())
        Assert.assertTrue(cityView.getMaxHealth() >= 200)
        Assert.assertTrue(cityView.isOwnedByViewer())

        // Espionage is a game setting: with no game there is no spying, which is
        // what used to crash before the gameInfoOrNull fallbacks.
        Assert.assertFalse(cityView.isEspionageEnabled())
        Assert.assertEquals(listOf("Roma"), cityView.getViewableCities().map { it.name })

        // The city map: centre tile, worked tiles and work range.
        Assert.assertEquals(tileMap[HexCoord(1, 1)], cityView.centerTile().getTile())
        Assert.assertTrue(cityView.getWorkRange() > 0)
        Assert.assertEquals(2, cityView.getTiles().count())
        Assert.assertTrue(cityView.isWorked(cityView.tileView(tileMap[HexCoord(0, 1)])))

        // Population and specialists, as the citizen management table reads them.
        Assert.assertEquals(4, city.population.population)
        Assert.assertTrue(cityView.getFreePopulation() >= 0)

        // Construction identity reads fine.
        val constructions = cityView.constructions
        Assert.assertNotNull(constructions.currentConstructionName())
        Assert.assertNotNull(constructions.getCurrentConstruction())
        val monument = constructions.getConstruction("Monument")
        Assert.assertFalse(constructions.isBuilt("Monument"))
        Assert.assertFalse(constructions.isQueueFull())
    }

    /**
     * The boundary that stops the real construction panels working here.
     *
     * Production cost is scaled by game difficulty - `Building.getProductionCost`
     * calls `civ.getDifficulty()`, which reads `gameInfo`. That is a game
     * setting, not a ruleset fact, so there is no fallback to add and a client
     * must not compute it. The server already sends the finished numbers on the
     * projection, so these panels need a view model over those, not local
     * computation.
     */
    @Test
    fun constructionCostsCannotBeComputedClientSideAndComeFromTheProjection() {
        val (_, gameView, viewer) = buildWorldMap()
        val constructions = gameView.getCityView(viewer.cities.single()).constructions
        val monument = constructions.getConstruction("Monument")

        Assert.assertThrows(Exception::class.java) {
            constructions.getTurnsToConstructionString(monument)
        }

        // What the client is meant to use instead: the server's own figures.
        val projected = projection().ownCities.single()
        Assert.assertEquals("city-1", projected.id)
        Assert.assertNotNull(projected.constructionQueueEntries)
        Assert.assertNotNull(projected.constructionOptions)
    }

    @Test
    fun aProjectionCityScreenIsReadOnly() {
        // GUI answers false without a WorldScreen, which is what forces every
        // state-changing control in a projection-hosted screen off.
        Assert.assertNull(com.unciv.UncivGame.Current.worldScreen)
        Assert.assertNull(com.unciv.GUI.hudHost)
        Assert.assertFalse(com.unciv.GUI.isAllowedChangeState())
    }

    /**
     * Game settings are facts about a game this client does not have. Every
     * view-layer reader must answer "off" instead of crashing - these are the
     * exact reads the religion and sell/specialist panels make.
     */
    @Test
    fun gameSettingsReadToOffWithoutAGameBehindTheCity() {
        val (_, gameView, viewer) = buildWorldMap()
        val cityView = gameView.getCityView(viewer.cities.single())

        Assert.assertFalse(cityView.isGodModeEnabled())
        Assert.assertFalse(cityView.viewingCiv().isReligionEnabled())
        Assert.assertFalse((cityView as com.unciv.view.ForeignCityView).isReligionEnabled())
        Assert.assertFalse(cityView.isEspionageEnabled())
    }
}
