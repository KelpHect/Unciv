package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativeSpyControllerTests {
    @Test
    fun exactProjectedDestinationsAndCoupActionsRoute() = runBlocking {
        val initial = gameProjectionWithSpies()
        val calls = mutableListOf<String>()
        val actions = AuthoritativeSpyActions(
            move = { spy, city ->
                calls += "move:$spy:${city ?: "hideout"}"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setCoup = { spy, enabled ->
                calls += "coup:$spy:$enabled"
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val spies = controller(initial, actions).spies

        spies.move("Agent A", null)
        spies.move("Agent A", "city-athens")
        spies.setCoup("Agent A", true)
        spies.setCoup("Agent B", false)

        assertEquals(listOf(
            "move:Agent A:hideout",
            "move:Agent A:city-athens",
            "coup:Agent A:true",
            "coup:Agent B:false",
        ), calls)
    }

    @Test
    fun inventedUnavailableAndOutOfTurnSpyInputsNeverInvokeTransport() = runBlocking {
        val initial = gameProjectionWithSpies()
        var calls = 0
        val actions = AuthoritativeSpyActions.Unavailable.copy(
            move = { _, _ ->
                calls++
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val spies = controller(initial, actions).spies

        assertThrows<IllegalArgumentException> {
            spies.move("Agent A", "invented-city")
        }
        assertThrows<IllegalArgumentException> {
            spies.setCoup("Agent A", false)
        }
        assertThrows<IllegalStateException> {
            spies.move("Unknown", null)
        }
        val outOfTurn = initial.copy(
            projection = initial.projection.copy(isCurrentTurn = false),
        )
        assertThrows<IllegalArgumentException> {
            controller(outOfTurn, actions).spies.move("Agent A", null)
        }
        assertEquals(0, calls)
    }

    @Test
    fun uncertainSpyRetryKeepsProjection() = runBlocking {
        val initial = gameProjectionWithSpies()
        var calls = 0
        val world = controller(
            initial,
            AuthoritativeSpyActions.Unavailable.copy(
                setCoup = { _, _ ->
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        )

        world.spies.setCoup("Agent A", true)
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
        world.spies.setCoup("Agent A", true)
        assertEquals(2, calls)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        actions: AuthoritativeSpyActions,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        spyActions = actions,
    )

    private fun gameProjectionWithSpies(): ApiV3GameProjection {
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
            projection = projection.copy(
                pendingTurnActions =
                    projection.pendingTurnActions + PendingEndTurnAction.MoveSpies,
                spies = listOf(
                    ProjectedSpy(
                        name = "Agent A",
                        rank = 2,
                        cityId = "city-rome",
                        civilizationId = "Rome",
                        action = ProjectedSpyAction.Surveillance,
                        turnsRemaining = 3,
                        availableCityIds = listOf("city-athens"),
                        canMoveToHideout = true,
                        canStageCoup = true,
                        canCancelCoup = false,
                    ),
                    ProjectedSpy(
                        name = "Agent B",
                        rank = 1,
                        cityId = "city-geneva",
                        civilizationId = "Geneva",
                        action = ProjectedSpyAction.Coup,
                        turnsRemaining = 1,
                        availableCityIds = emptyList(),
                        canMoveToHideout = true,
                        canStageCoup = false,
                        canCancelCoup = true,
                    ),
                ),
            ),
        )
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v56.fixture.json") }
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
