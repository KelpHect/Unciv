package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AuthoritativeDiplomacyControllerTests {
    @Test
    fun exactMajorAndCityStateCapabilitiesRoute() = runBlocking {
        val initial = gameProjectionWithAllCapabilities()
        val calls = mutableListOf<String>()
        val actions = AuthoritativeDiplomacyActions(
            declareWar = { civ -> call(calls, "war:$civ") },
            denounce = { civ -> call(calls, "denounce:$civ") },
            offerFriendship = { civ -> call(calls, "friend:$civ") },
            makeDemand = { civ, demand -> call(calls, "demand:$civ:$demand") },
            giftGold = { civ, amount -> call(calls, "gold:$civ:$amount") },
            setProtection = { civ, protect -> call(calls, "protect:$civ:$protect") },
            demandTribute = { civ, worker -> call(calls, "tribute:$civ:$worker") },
            giftImprovement = { civ, x, y, improvement ->
                call(calls, "improvement:$civ:$x:$y:$improvement")
            },
            negotiatePeace = { civ -> call(calls, "peace:$civ") },
            marry = { civ -> call(calls, "marry:$civ") },
        )
        val diplomacy = controller(initial, actions).diplomacy

        diplomacy.declareWar("Greece")
        diplomacy.denounce("Greece")
        diplomacy.offerFriendship("Greece")
        diplomacy.makeDemand("Greece", DiplomaticDemand.DoNotSettleNearUs)
        diplomacy.declareWar("Geneva")
        diplomacy.giftGold("Geneva", 250)
        diplomacy.setProtection("Geneva", true)
        diplomacy.demandTribute("Geneva", false)
        diplomacy.demandTribute("Geneva", true)
        diplomacy.giftImprovement("Geneva", 4, -2, "Trading post")
        diplomacy.negotiatePeace("Geneva")
        diplomacy.marry("Geneva")

        assertEquals(12, calls.size)
        assertEquals("war:Greece", calls.first())
        assertEquals("marry:Geneva", calls.last())
    }

    @Test
    fun inventedUnavailableAndOutOfTurnCapabilitiesNeverInvokeTransport() = runBlocking {
        val initial = gameProjectionWithAllCapabilities()
        var calls = 0
        val actions = AuthoritativeDiplomacyActions.Unavailable.copy(
            declareWar = {
                calls++
                AuthoritativeCommandOutcome.RetryRequired
            },
        )
        val diplomacy = controller(initial, actions).diplomacy

        assertThrows<IllegalArgumentException> { diplomacy.declareWar("Unknown") }
        assertThrows<IllegalArgumentException> {
            diplomacy.giftGold("Geneva", 999)
        }
        assertThrows<IllegalArgumentException> {
            diplomacy.setProtection("Geneva", false)
        }
        val outOfTurn = initial.copy(
            projection = initial.projection.copy(isCurrentTurn = false),
        )
        assertThrows<IllegalArgumentException> {
            controller(outOfTurn, actions).diplomacy.declareWar("Greece")
        }
        assertEquals(0, calls)
    }

    @Test
    fun uncertainDiplomacyRetryKeepsProjection() = runBlocking {
        val initial = gameProjectionWithAllCapabilities()
        var calls = 0
        val world = controller(
            initial,
            AuthoritativeDiplomacyActions.Unavailable.copy(
                declareWar = {
                    calls++
                    AuthoritativeCommandOutcome.RetryRequired
                },
            ),
        )

        repeat(2) { world.diplomacy.declareWar("Greece") }

        assertEquals(2, calls)
        assertEquals(AuthoritativeWorldStatus.RetryRequired, world.status)
        assertEquals(7, world.current.committedRevision)
    }

    private fun controller(
        initial: ApiV3GameProjection,
        actions: AuthoritativeDiplomacyActions,
    ) = AuthoritativeWorldController(
        initial = initial,
        refreshProjection = { initial },
        moveUnit = { _, _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        endTurn = { AuthoritativeCommandOutcome.Rejected("test") },
        diplomacyActions = actions,
    )

    private fun gameProjectionWithAllCapabilities(): ApiV3GameProjection {
        val projection = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }.decodeFromString(
            PlayerProjection.serializer(),
            projectionFixture().readText(),
        )
        val cityState = projection.cityStatePartners.single().copy(
            canDemandWorker = true,
            improvementGifts = listOf(ProjectedCityStateImprovementGift(
                x = 4,
                y = -2,
                improvementName = "Trading post",
                goldCost = 200,
            )),
            canNegotiatePeace = true,
        )
        return ApiV3GameProjection(
            gameId = "game-a",
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            committedRevision = 7,
            canonicalStateHash = "hash-7",
            projectionHash = "projection-7",
            projection = projection.copy(cityStatePartners = listOf(cityState)),
        )
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v57.fixture.json") }
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
