package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthoritativeCityControlControllerTests {
    @Test
    fun projectedTileBuildingGovernanceAndDispositionInputsRouteExactly() = runBlocking {
        val initial = gameProjection()
        val calls = mutableListOf<String>()
        val actions = AuthoritativeCityControlActions.Unavailable.copy(
            buyTile = { city, x, y ->
                calls += "tile:$city:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
            buyTileBatch = { city, ring ->
                calls += "ring:$city:$ring"
                AuthoritativeCommandOutcome.RetryRequired
            },
            sellBuilding = { city, building ->
                calls += "sell:$city:$building"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setGovernance = { city, action ->
                calls += "governance:$city:$action"
                AuthoritativeCommandOutcome.RetryRequired
            },
            resolveDisposition = { city, action ->
                calls += "disposition:$city:$action"
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val controls = controller(initial, actions).cityControls

        controls.buyTile("city-rome", 3, -1)
        controls.buyTileBatch("city-rome", 2)
        controls.sellBuilding("city-rome", "Monument")
        controls.setGovernance("city-rome", CityGovernanceAction.StartRazing)
        controls.resolveDisposition("captured-city", CityDispositionAction.Annex)

        assertEquals(listOf(
            "tile:city-rome:3:-1",
            "ring:city-rome:2",
            "sell:city-rome:Monument",
            "governance:city-rome:${CityGovernanceAction.StartRazing}",
            "disposition:captured-city:${CityDispositionAction.Annex}",
        ), calls)
    }

    @Test
    fun projectedCitizenInputsUseOnlyBoundedCityChoices() = runBlocking {
        val initial = gameProjection()
        val calls = mutableListOf<String>()
        val actions = AuthoritativeCityControlActions.Unavailable.copy(
            setTileAssignment = { city, x, y, assignment ->
                calls += "assignment:$city:$x:$y:$assignment"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setSpecialistCount = { city, specialist, count ->
                calls += "specialist:$city:$specialist:$count"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setManualSpecialists = { city, enabled ->
                calls += "manual:$city:$enabled"
                AuthoritativeCommandOutcome.RetryRequired
            },
            resetCitizens = { city ->
                calls += "reset:$city"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setAvoidGrowth = { city, enabled ->
                calls += "growth:$city:$enabled"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setCitizenFocus = { city, focus ->
                calls += "focus:$city:$focus"
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val controls = controller(initial, actions).cityControls

        controls.setTileAssignment("city-rome", 3, -1, CityTileAssignment.Worked)
        controls.setSpecialistCount("city-rome", "Scientist", 2)
        controls.setManualSpecialists("city-rome", false)
        controls.resetCitizens("city-rome")
        controls.setAvoidGrowth("city-rome", false)
        controls.setCitizenFocus("city-rome", CitizenFocus.ScienceFocus)

        assertEquals(6, calls.size)
        assertTrue(calls.contains("specialist:city-rome:Scientist:2"))
        assertTrue(calls.contains("focus:city-rome:${CitizenFocus.ScienceFocus}"))
    }

    @Test
    fun inventedCityControlsNeverInvokeTransportAndRetryKeepsProjection() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val world = controller(
            initial,
            AuthoritativeCityControlActions.Unavailable.copy(
                sellBuilding = { _, _ ->
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        )

        assertThrows<IllegalArgumentException> {
            world.cityControls.sellBuilding("city-rome", "Secret Building")
        }
        assertThrows<IllegalArgumentException> {
            world.cityControls.setSpecialistCount("city-rome", "Scientist", 99)
        }
        assertThrows<IllegalArgumentException> {
            world.cityControls.setGovernance("city-rome", CityGovernanceAction.Annex)
        }
        assertEquals(0, calls)

        world.cityControls.sellBuilding("city-rome", "Monument")
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
        world.cityControls.sellBuilding("city-rome", "Monument")
        assertEquals(2, calls)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        actions: AuthoritativeCityControlActions,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        cityControlActions = actions,
    )

    private fun gameProjection(): ApiV3GameProjection {
        val projection = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }.decodeFromString(
            PlayerProjection.serializer(),
            projectionFixture().readText(),
        )
        return ApiV3GameProjection(
            gameId = "game-a",
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = 7,
            canonicalStateHash = "hash-7",
            projectionHash = "projection-7",
            projection = projection,
        )
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v58.fixture.json") }
        .first { it.isFile }

    private suspend inline fun <reified T : Throwable> assertThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.simpleName}, got $throwable", throwable)
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
