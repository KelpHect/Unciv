package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativeTradeControllerTests {
    @Test
    fun exactOfferRetractionDecisionsAndCounterRoute() = runBlocking {
        val initial = gameProjection()
        val calls = mutableListOf<String>()
        val actions = AuthoritativeTradeActions(
            offer = { civ, trade -> call(calls, "offer:$civ:${trade.ourOffers.single().amount}") },
            retract = { civ -> call(calls, "retract:$civ") },
            accept = { request -> call(calls, "accept:$request") },
            decline = { request -> call(calls, "decline:$request") },
            counter = { request, trade ->
                call(calls, "counter:$request:${trade.theirOffers.single().amount}")
            },
        )
        val trade = controller(initial, actions).trade
        val requestId = initial.projection.pendingTradeRequests.single().requestId

        trade.offer("Greece", ProjectedTrade(
            ourOffers = listOf(ProjectedTradeOffer("Gold", "Gold", 100, 0)),
            theirOffers = emptyList(),
        ))
        trade.accept(requestId)
        trade.decline(requestId)
        trade.counter(requestId, ProjectedTrade(
            ourOffers = emptyList(),
            theirOffers = listOf(ProjectedTradeOffer("Gold", "Gold", 50, 0)),
        ))

        val pending = initial.copy(
            projection = initial.projection.copy(
                tradePartners = listOf(
                    initial.projection.tradePartners.single().copy(
                        hasPendingOutgoingOffer = true,
                    ),
                ),
            ),
        )
        controller(pending, actions).trade.retract("Greece")

        assertEquals(5, calls.size)
        assertEquals("offer:Greece:100", calls.first())
        assertEquals("retract:Greece", calls.last())
    }

    @Test
    fun emptyForgedDuplicateAndOutOfTurnTradesNeverInvokeTransport() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val actions = AuthoritativeTradeActions.Unavailable.copy(
            offer = { _, _ ->
                calls++
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val trade = controller(initial, actions).trade

        assertThrows<IllegalArgumentException> {
            trade.offer("Greece", ProjectedTrade(emptyList(), emptyList()))
        }
        assertThrows<IllegalArgumentException> {
            trade.offer("Greece", ProjectedTrade(
                listOf(ProjectedTradeOffer("Gold", "Gold", 322, 0)),
                emptyList(),
            ))
        }
        assertThrows<IllegalArgumentException> {
            trade.offer("Greece", ProjectedTrade(
                listOf(ProjectedTradeOffer("Gold", "Gold", 1, 30)),
                emptyList(),
            ))
        }
        assertThrows<IllegalArgumentException> {
            trade.offer("Greece", ProjectedTrade(
                listOf(
                    ProjectedTradeOffer("Gold", "Gold", 1, 0),
                    ProjectedTradeOffer("Gold", "Gold", 2, 0),
                ),
                emptyList(),
            ))
        }
        val outOfTurn = initial.copy(
            projection = initial.projection.copy(isCurrentTurn = false),
        )
        assertThrows<IllegalArgumentException> {
            controller(outOfTurn, actions).trade.accept(
                initial.projection.pendingTradeRequests.single().requestId,
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun uncertainTradeRetryKeepsProjection() = runBlocking {
        val initial = gameProjection()
        var calls = 0
        val world = controller(
            initial,
            AuthoritativeTradeActions.Unavailable.copy(
                accept = {
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        )
        val requestId = initial.projection.pendingTradeRequests.single().requestId

        repeat(2) { world.trade.accept(requestId) }

        assertEquals(2, calls)
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        actions: AuthoritativeTradeActions,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        tradeActions = actions,
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

    private suspend fun call(
        calls: MutableList<String>,
        value: String,
    ): AuthoritativeCommandOutcome {
        calls += value
        return AuthoritativeCommandOutcome.RetryRequired
    }

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
