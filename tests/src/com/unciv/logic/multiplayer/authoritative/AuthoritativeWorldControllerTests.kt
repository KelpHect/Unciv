package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthoritativeWorldControllerTests {
    @Test
    fun projectedUnitMovesOnlyToServerAdvertisedDestination() = runBlocking {
        val initial = gameProjection(7)
        val moved = gameProjection(8).copy(
            projection = initial.projection.copy(
                ownUnits = initial.projection.ownUnits.map {
                    it.copy(x = 2, y = -1, moveDestinations = emptyList())
                },
            ),
        )
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val controller = controller(initial, move = { unitId, x, y ->
            calls += Triple(unitId, x, y)
            AuthoritativeCommandOutcome.Accepted(
                ApiV3CommandAccepted(initial.gameId, "command", 7, 8, "hash-8"),
                moved,
            )
        })
        val unit = initial.projection.ownUnits.single()
        val destination = unit.moveDestinations.single()

        controller.selectUnit(unit.id)
        assertTrue(controller.canMoveSelectedTo(destination.x, destination.y))
        controller.moveSelectedTo(destination.x, destination.y)

        assertEquals(listOf(Triple(unit.id, destination.x, destination.y)), calls)
        assertEquals(8, controller.current.committedRevision)
        assertEquals(destination.x, controller.selectedUnit()!!.x)
    }

    @Test
    fun unadvertisedDestinationNeverCallsTransport() = runBlocking {
        var calls = 0
        val controller = controller(gameProjection(7), move = { _, _, _ ->
            calls++
            AuthoritativeCommandOutcome.Rejected("unexpected")
        })
        controller.selectUnit(controller.projection.ownUnits.single().id)

        assertThrows<IllegalArgumentException> { controller.moveSelectedTo(99, 99) }
        assertEquals(0, calls)
    }

    @Test
    fun ambiguousMoveRemainsRetryableWithoutLocalPrediction() = runBlocking {
        var calls = 0
        val initial = gameProjection(7)
        val controller = controller(initial, move = { _, _, _ ->
            calls++
            AuthoritativeCommandOutcome.RetryRequired
        })
        val unit = controller.projection.ownUnits.single()
        val destination = unit.moveDestinations.single()
        controller.selectUnit(unit.id)

        controller.moveSelectedTo(destination.x, destination.y)
        assertEquals(AuthoritativeWorldStatus.RetryRequired, controller.status)
        assertEquals(7, controller.current.committedRevision)
        controller.moveSelectedTo(destination.x, destination.y)

        assertEquals(2, calls)
        assertEquals(7, controller.current.committedRevision)
    }

    @Test
    fun endTurnRequiresServerProjectedReadiness() = runBlocking {
        var calls = 0
        val blocked = gameProjection(7)
        val controller = controller(blocked, endTurn = {
            calls++
            AuthoritativeCommandOutcome.Rejected("unexpected")
        })

        assertFalse(controller.canEndTurn())
        assertThrows<IllegalArgumentException> { controller.submitEndTurn() }
        assertEquals(0, calls)
    }

    @Test
    fun refreshRejectsBackwardRevision() = runBlocking {
        val initial = gameProjection(7)
        val controller = controller(initial, refresh = { gameProjection(6) })

        assertThrows<IllegalArgumentException> { controller.refresh() }
        assertEquals(7, controller.current.committedRevision)
        assertEquals(AuthoritativeWorldStatus.Rejected("refresh_failed"), controller.status)
    }

    @Test
    fun projectionWorldHasNoCanonicalOrLegacySaveDependency() {
        val sources = listOf(
            sourceFile(
                "core/src/com/unciv/logic/multiplayer/authoritative/" +
                    "AuthoritativeWorldController.kt",
            ),
            sourceFile(
                "core/src/com/unciv/ui/screens/multiplayerscreens/" +
                    "AuthoritativeWorldScreen.kt",
            ),
        ).joinToString("\n") { it.readText() }

        for (forbidden in listOf(
            "import com.unciv.logic.GameInfo",
            "import com.unciv.ui.screens.worldscreen.WorldScreen",
            "multiplayerFiles",
            "GameStarter",
        )) {
            assertFalse("Projection world must not reference $forbidden", sources.contains(forbidden))
        }
    }

    private fun controller(
        initial: ApiV3GameProjection,
        refresh: suspend () -> ApiV3GameProjection = { initial },
        move: suspend (Int, Int, Int) -> AuthoritativeCommandOutcome? =
            { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn: suspend () -> AuthoritativeCommandOutcome? =
            { AuthoritativeCommandOutcome.Rejected("test") },
    ) = AuthoritativeWorldController(initial, refresh, move, endTurn)

    private fun gameProjection(revision: Long): ApiV3GameProjection {
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
            committedRevision = revision,
            canonicalStateHash = "hash-$revision",
            projectionHash = "projection-$revision",
            projection = projection,
        )
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v54.fixture.json") }
        .first { it.isFile }

    private fun sourceFile(path: String): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, path) }.first { it.isFile }

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
