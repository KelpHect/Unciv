package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativeUnitOrderControllerTests {
    @Test
    fun exactProjectedOrdersAndPromotionRouteToTypedOperations() = runBlocking {
        val initial = gameProjection()
        val calls = mutableListOf<String>()
        val actions = AuthoritativeUnitOrderActions(
            cancelMovement = { unit ->
                calls += "cancel:$unit"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setExploration = { unit, enabled ->
                calls += "explore:$unit:$enabled"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setAutomation = { unit, enabled ->
                calls += "automate:$unit:$enabled"
                AuthoritativeCommandOutcome.RetryRequired
            },
            promote = { unit, promotion ->
                calls += "promote:$unit:$promotion"
                AuthoritativeCommandOutcome.RetryRequired
            },
            swap = { unit, x, y ->
                calls += "swap:$unit:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setPosture = { unit, posture ->
                calls += "posture:$unit:$posture"
                AuthoritativeCommandOutcome.RetryRequired
            },
            disband = { unit ->
                calls += "disband:$unit"
                AuthoritativeCommandOutcome.RetryRequired
            },
            pillage = { unit ->
                calls += "pillage:$unit"
                AuthoritativeCommandOutcome.RetryRequired
            },
            foundCity = { unit ->
                calls += "found:$unit"
                AuthoritativeCommandOutcome.RetryRequired
            },
            paradrop = { unit, x, y ->
                calls += "paradrop:$unit:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
            upgrade = { units, target ->
                calls += "upgrade:${units.joinToString()}:$target"
                AuthoritativeCommandOutcome.RetryRequired
            },
            rename = { unit, name ->
                calls += "rename:$unit:$name"
                AuthoritativeCommandOutcome.RetryRequired
            },
            moveToward = { unit, x, y ->
                calls += "toward:$unit:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setImprovementOrder = { unit, improvement, queued ->
                calls += "improvement:$unit:$improvement:$queued"
                AuthoritativeCommandOutcome.RetryRequired
            },
            setRoadOrder = { unit, x, y ->
                calls += "road:$unit:$x:$y"
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val orders = controller(initial, actions).unitOrders

        orders.cancelMovement(17)
        orders.setExploration(17, true)
        orders.setAutomation(17, false)
        orders.promote(17, "Drill II")
        orders.swap(17, 2, -1)
        orders.setPosture(17, UnitPosture.Sleep)
        orders.pillage(17)
        orders.paradrop(17, 2, -1)
        orders.upgrade(17, "Rifleman")
        orders.rename(17, "Second Mission")
        orders.moveToward(17, 5, -1)
        orders.setImprovementOrder(
            17, ProjectedImprovementOrderChoice("Remove Forest", "Farm"),
        )
        orders.setRoadOrder(17, 2, -1)
        orders.cancelRoadOrder(17)
        orders.disband(17)

        assertEquals(listOf(
            "cancel:17",
            "explore:17:true",
            "automate:17:false",
            "promote:17:Drill II",
            "swap:17:2:-1",
            "posture:17:Sleep",
            "pillage:17",
            "paradrop:17:2:-1",
            "upgrade:17:Rifleman",
            "rename:17:Second Mission",
            "toward:17:5:-1",
            "improvement:17:Remove Forest:Farm",
            "road:17:2:-1",
            "road:17:null:null",
            "disband:17",
        ), calls)
    }

    @Test
    fun staleInventedAndOutOfTurnOrdersNeverInvokeTransport() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val actions = AuthoritativeUnitOrderActions.Unavailable.copy(
            promote = { _, _ ->
                calls++
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val orders = controller(initial, actions).unitOrders

        assertThrows<IllegalArgumentException> { orders.setExploration(17, false) }
        assertThrows<IllegalArgumentException> { orders.setAutomation(17, true) }
        assertThrows<IllegalArgumentException> { orders.promote(17, "Invented") }
        assertThrows<IllegalArgumentException> { orders.swap(17, 99, 99) }
        assertThrows<IllegalArgumentException> {
            orders.setPosture(17, UnitPosture.Setup)
        }
        assertThrows<IllegalArgumentException> { orders.paradrop(17, 99, 99) }
        assertThrows<IllegalArgumentException> { orders.upgrade(17, "Invented") }
        assertThrows<IllegalArgumentException> { orders.rename(17, "\u0000") }
        assertThrows<IllegalArgumentException> { orders.foundCity(17) }
        assertThrows<IllegalArgumentException> { orders.moveToward(17, 99, 99) }
        assertThrows<IllegalArgumentException> {
            orders.setImprovementOrder(
                17, ProjectedImprovementOrderChoice("Invented", null),
            )
        }
        assertThrows<IllegalArgumentException> { orders.setRoadOrder(17, 99, 99) }
        assertThrows<IllegalStateException> { orders.cancelMovement(999) }

        val outOfTurn = initial.copy(
            projection = initial.projection.copy(isCurrentTurn = false),
        )
        assertThrows<IllegalArgumentException> {
            controller(outOfTurn, actions).unitOrders.promote(17, "Drill II")
        }
        assertEquals(0, calls)
    }

    @Test
    fun uncertainOrderRetryKeepsProjection() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val world = controller(
            initial,
            AuthoritativeUnitOrderActions.Unavailable.copy(
                promote = { _, _ ->
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        )

        world.unitOrders.promote(17, "Drill II")
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
        world.unitOrders.promote(17, "Drill II")
        assertEquals(2, calls)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        actions: AuthoritativeUnitOrderActions,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        unitOrderActions = actions,
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
