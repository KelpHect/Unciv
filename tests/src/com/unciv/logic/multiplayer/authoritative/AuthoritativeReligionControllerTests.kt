package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativeReligionControllerTests {
    @Test
    fun exactProjectedBeliefsAndIdentityRoute() = runBlocking {
        val initial = gameProjection()
        val calls = mutableListOf<String>()
        val world = controller(initial) { beliefs, icon, name ->
            calls += "${beliefs.joinToString("|")}:$icon:$name"
            AuthoritativeCommandOutcome.RetryRequired
        }

        world.religion.choose(
            listOf("Church Property", "Pagodas"),
            "Buddhism",
            "The Middle Way",
        )

        assertEquals(
            listOf("Church Property|Pagodas:Buddhism:The Middle Way"),
            calls,
        )
    }

    @Test
    fun invalidSlotsBeliefsAndIdentityNeverInvokeTransport() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val world = controller(initial) { _, _, _ ->
            calls++
            AuthoritativeCommandOutcome.RetryRequired
        }
        val choice = requireNotNull(initial.projection.religionChoice)
        val withExtraFounder = initial.copy(
            projection = initial.projection.copy(
                religionChoice = choice.copy(
                    availableBeliefs = choice.availableBeliefs +
                        ProjectedReligiousBelief(
                            "Tithe",
                            ReligiousBeliefType.Founder,
                        ),
                ),
            ),
        )

        assertThrows<IllegalArgumentException> {
            controller(withExtraFounder) { _, _, _ -> null }.religion.choose(
                listOf("Church Property", "Tithe"),
                "Buddhism",
                "Name",
            )
        }
        assertThrows<IllegalArgumentException> {
            world.religion.choose(
                listOf("Church Property", "Invented"),
                "Buddhism",
                "Name",
            )
        }
        assertThrows<IllegalArgumentException> {
            world.religion.choose(
                listOf("Church Property", "Pagodas"),
                "Invented",
                "Name",
            )
        }
        assertThrows<IllegalArgumentException> {
            world.religion.choose(
                listOf("Church Property", "Pagodas"),
                "Buddhism",
                "bad\nname",
            )
        }
        assertThrows<IllegalStateException> {
            controller(
                initial.copy(
                    projection = initial.projection.copy(religionChoice = null),
                ),
            ) { _, _, _ -> null }.religion.choose(
                listOf("Church Property", "Pagodas"),
                "Buddhism",
                "Name",
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun uncertainReligionRetryKeepsProjection() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val world = controller(initial) { _, _, _ ->
            calls++
            AuthoritativeCommandOutcome.RetryRequired
        }

        repeat(2) {
            world.religion.choose(
                listOf("Church Property", "Pagodas"),
                "Buddhism",
                "The Middle Way",
            )
        }

        assertEquals(2, calls)
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        action: suspend (List<String>, String?, String?) -> AuthoritativeCommandOutcome?,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        religionAction = action,
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
    ).map { File(it, "protocol/player-projection-v59.fixture.json") }
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
