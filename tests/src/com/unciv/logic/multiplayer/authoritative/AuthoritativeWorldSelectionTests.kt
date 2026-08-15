package com.unciv.logic.multiplayer.authoritative

import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The world screen's tap and city-selection decisions, tested without GL.
 *
 * This logic used to live inside `AuthoritativeWorldScreen`, which extends
 * `BaseScreen` and therefore cannot be constructed headlessly - so its defects
 * were only ever found by reading. Everything asserted here is what that screen
 * now delegates to.
 */
class AuthoritativeWorldSelectionTests {
    private fun city(id: String, x: Int, y: Int) = ProjectedCity(
        id = id,
        name = id,
        x = x,
        y = y,
        population = 1,
        health = 200,
        constructionQueue = emptyList(),
        availableConstructions = emptyList(),
    )

    private fun unit(id: Int, x: Int, y: Int) = ProjectedUnit(
        id = id,
        name = "Warrior",
        civilizationId = "Rome",
        x = x,
        y = y,
        health = 100,
        currentMovement = 2f,
    )

    private fun projection(
        cities: List<ProjectedCity> = emptyList(),
        units: List<ProjectedUnit> = emptyList(),
    ) = PlayerProjection(
        civilizationId = "Rome",
        turn = 1,
        currentPlayerCivilizationId = "Rome",
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
        exploredTiles = emptyList(),
        visibleForeignUnits = emptyList(),
    )

    private fun decide(
        selection: AuthoritativeWorldSelection,
        projection: PlayerProjection,
        x: Int,
        y: Int,
        targetMode: Boolean = false,
        canSubmit: Boolean = false,
        canMove: Boolean = false,
    ) = selection.decide(projection, x, y, targetMode, canSubmit, canMove)

    @Test
    fun aPendingOrderConsumesTheTapEntirely() {
        val selection = AuthoritativeWorldSelection()
        // A city and a unit both stand here, and the unit could move here, yet
        // target mode must still win - otherwise starting an order and tapping
        // would silently re-select instead of submitting.
        val projection = projection(listOf(city("c1", 1, 1)), listOf(unit(7, 1, 1)))

        assertEquals(
            ProjectedTap.SubmitUnitTarget(1, 1),
            decide(selection, projection, 1, 1, targetMode = true, canSubmit = true, canMove = true),
        )
        assertEquals(
            ProjectedTap.RejectedUnitTarget,
            decide(selection, projection, 1, 1, targetMode = true, canSubmit = false, canMove = true),
        )
        assertNull("a rejected target must not select anything", selection.selectedCityId)
    }

    @Test
    fun aCityIsPreferredOverAUnitOnTheSameTile() {
        val selection = AuthoritativeWorldSelection()
        val projection = projection(listOf(city("c1", 2, 3)), listOf(unit(7, 2, 3)))

        assertEquals(ProjectedTap.SelectCity("c1"), decide(selection, projection, 2, 3))
    }

    @Test
    fun aUnitTileSelectsAUnitAndAnEmptyTileMovesOrInspects() {
        val selection = AuthoritativeWorldSelection()
        val projection = projection(units = listOf(unit(7, 0, 0)))

        assertEquals(ProjectedTap.SelectUnit, decide(selection, projection, 0, 0))
        assertEquals(
            ProjectedTap.MoveSelectedUnit(4, 4),
            decide(selection, projection, 4, 4, canMove = true),
        )
        assertEquals(ProjectedTap.InspectOnly, decide(selection, projection, 4, 4))
    }

    @Test
    fun theOpenCityIsResolvedAgainstTheCurrentProjection() {
        val selection = AuthoritativeWorldSelection()
        val projection = projection(listOf(city("c1", 1, 1)))
        selection.selectCity("c1")

        assertEquals("c1", selection.selectedCity(projection)?.id)

        // The city grew between revisions: the panel must read the new values,
        // not the ones captured when it was opened.
        val grown = projection(listOf(city("c1", 1, 1).copy(population = 6)))
        assertEquals(6, selection.selectedCity(grown)?.population)
    }

    @Test
    fun aCityTheProjectionNoLongerListsIsClosed() {
        val selection = AuthoritativeWorldSelection()
        selection.selectCity("c1")

        // Razed, captured, or simply absent - either way its panel must go.
        selection.onProjectionReplaced(projection(listOf(city("c2", 5, 5))))

        assertNull(selection.selectedCityId)
        assertNull(selection.selectedCity(projection(listOf(city("c2", 5, 5)))))
    }

    /**
     * A conquest prompt is not a control of a city the player owns: the
     * projection builds pendingCityDispositions from popup alerts against every
     * city in the game. Tapping can only ever open an own city, so a disposition
     * must never be reachable only through a tap - it would be undecidable.
     */
    @Test
    fun aConquestPromptIsNotReachableByTappingAnOwnCity() {
        val selection = AuthoritativeWorldSelection()
        val conquered = ProjectedCityDisposition(
            cityId = "captured",
            cityName = "Athens",
            availableActions = listOf(CityDispositionAction.Annex, CityDispositionAction.Raze),
        )
        val projection = projection(listOf(city("mine", 1, 1)))
            .copy(pendingCityDispositions = listOf(conquered))

        // The conquered city is not among the player's own cities...
        Assert.assertTrue(projection.ownCities.none { it.id == conquered.cityId })
        // ...so no tap anywhere can select it.
        for (x in 0..3) for (y in 0..3) {
            val tap = decide(selection, projection, x, y)
            Assert.assertNotEquals(
                ProjectedTap.SelectCity(conquered.cityId), tap,
            )
        }
        // The prompt therefore has to live outside the per-city panel.
        Assert.assertEquals(1, projection.pendingCityDispositions.size)
    }

    @Test
    fun aSurvivingCityStaysOpenAcrossRevisions() {
        val selection = AuthoritativeWorldSelection()
        selection.selectCity("c1")

        selection.onProjectionReplaced(projection(listOf(city("c1", 1, 1), city("c2", 5, 5))))

        assertEquals("c1", selection.selectedCityId)
    }
}
