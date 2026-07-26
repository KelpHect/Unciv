package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativeCombatControllerTests {
    @Test
    fun exactProjectedCombatTargetsRouteToTypedActions() = runBlocking {
        val initial = gameProjection()
        val calls = mutableListOf<String>()
        val actions = AuthoritativeCombatActions(
            attack = { unit, x, y ->
                calls += "attack:$unit:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
            launchNuclearStrike = { unit, x, y ->
                calls += "nuclear:$unit:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
            airSweep = { unit, x, y ->
                calls += "sweep:$unit:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
            bombard = { city, x, y ->
                calls += "bombard:$city:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val combat = controller(initial, actions).combat

        combat.attack(17, 2, -1)
        combat.launchNuclearStrike(17, 2, -1)
        combat.airSweep(17, 2, -1)
        combat.bombard("city-rome", 2, -1)

        assertEquals(listOf(
            "attack:17:2:-1",
            "nuclear:17:2:-1",
            "sweep:17:2:-1",
            "bombard:city-rome:2:-1",
        ), calls)
    }

    @Test
    fun inventedCombatTargetsNeverInvokeTransport() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val actions = AuthoritativeCombatActions.Unavailable.copy(
            attack = { _, _, _ ->
                calls++
                AuthoritativeCommandOutcome.RetryRequired
            },
            bombard = { _, _, _ ->
                calls++
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val combat = controller(initial, actions).combat

        assertThrows<IllegalStateException> { combat.attack(17, 99, 99) }
        assertThrows<IllegalStateException> { combat.attack(999, 2, -1) }
        assertThrows<IllegalStateException> { combat.launchNuclearStrike(17, 3, -1) }
        assertThrows<IllegalStateException> { combat.airSweep(17, 2, 0) }
        assertThrows<IllegalStateException> { combat.bombard("invented", 2, -1) }
        assertThrows<IllegalStateException> { combat.bombard("city-rome", 2, 0) }
        assertEquals(0, calls)
    }

    @Test
    fun uncertainCombatRetryKeepsProjectionAndCanReuseSessionOperation() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val world = controller(
            initial,
            AuthoritativeCombatActions.Unavailable.copy(
                attack = { _, _, _ ->
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        )

        world.combat.attack(17, 2, -1)
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
        world.combat.attack(17, 2, -1)
        assertEquals(2, calls)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        actions: AuthoritativeCombatActions,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        combatActions = actions,
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
