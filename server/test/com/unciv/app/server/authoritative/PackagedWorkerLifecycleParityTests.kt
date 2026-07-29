package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.PlayerType
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerLifecycleParityTests {
    @Test(timeout = 300_000)
    fun selfResignationIsStableAcrossFreshWorkers() {
        val fixture = fixture("00000000-0000-4000-8000-000000000701", 1_700_700_000_000L)
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            val created = send(fixture.createGameRequest(1_700_700_000_000L))
            results += created
            val assigned = assignSecondPlayer(send, fixture, created, 1_700_700_010_000L)
            results += assigned

            val ownerCivilizationId = requireNotNull(created.actorCivilizationId)
            val resigned = send(
                fixture.request(
                    WorkerOperation.Resign(
                        snapshot = requireNotNull(assigned.snapshot),
                        actorCivilizationId = ownerCivilizationId,
                    ),
                    1_700_700_020_000L,
                ),
            )
            results += resigned

            val game = fixture.decode(resigned.snapshot)
            val owner = game.getCivilization(ownerCivilizationId)
            assertEquals(PlayerType.AI, owner.playerType)
            assertEquals("", owner.playerId)
            assertEquals(assigned.actorCivilizationId, game.currentPlayer)
            results
        }

        assertEquals(3, responses.size)
    }

    @Test(timeout = 300_000)
    fun ownerKickIsStableAcrossFreshWorkers() {
        val fixture = fixture("00000000-0000-4000-8000-000000000702", 1_700_700_100_000L)
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            val created = send(fixture.createGameRequest(1_700_700_100_000L))
            results += created
            val assigned = assignSecondPlayer(send, fixture, created, 1_700_700_110_000L)
            results += assigned

            val ownerCivilizationId = requireNotNull(created.actorCivilizationId)
            val targetCivilizationId = requireNotNull(assigned.actorCivilizationId)
            val kicked = send(
                fixture.request(
                    WorkerOperation.KickPlayer(
                        snapshot = requireNotNull(assigned.snapshot),
                        actorCivilizationId = ownerCivilizationId,
                        targetCivilizationId = targetCivilizationId,
                    ),
                    1_700_700_120_000L,
                ),
            )
            results += kicked

            val target = fixture.decode(kicked.snapshot).getCivilization(targetCivilizationId)
            assertEquals(PlayerType.AI, target.playerType)
            assertEquals("", target.playerId)
            results
        }

        assertEquals(3, responses.size)
    }

    private fun assignSecondPlayer(
        send: (WorkerRequest) -> WorkerResponse,
        fixture: PackagedWorkerScenarioFixture,
        created: WorkerResponse,
        serverTime: Long,
    ): WorkerResponse {
        val available = fixture.decode(created.snapshot).civilizations.first {
            it.isMajorCiv() && it.isAI() && it.playerId.isEmpty()
        }.civID
        return send(
            fixture.request(
                WorkerOperation.AssignPlayer(requireNotNull(created.snapshot), available),
                serverTime,
                secondActorId,
            ),
        )
    }

    private fun fixture(gameId: String, seed: Long) = PackagedWorkerScenarioFixture(
        actorId = ownerActorId,
        gameId = gameId,
        serverSeed = seed,
    )

    companion object {
        private const val ownerActorId = "account-lifecycle-owner"
        private const val secondActorId = "account-lifecycle-player"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
