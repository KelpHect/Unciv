package com.unciv.app.server.authoritative

import com.unciv.Constants
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerTradeParityTests {
    @Test(timeout = 300_000)
    fun bilateralTradeLifecycleIsStableAcrossFreshWorkers() {
        val fixture = PackagedWorkerScenarioFixture(
            actorId = firstAccountId,
            gameId = "00000000-0000-4000-8000-000000000601",
            serverSeed = 912_647_305L,
        )
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()
            var response = send(fixture.createGameRequest(1_700_600_000_000L))
            results += response

            val firstCivilizationId = requireNotNull(response.actorCivilizationId)
            val game = fixture.decode(response.snapshot)
            val firstCivilization = game.getCivilization(firstCivilizationId)
            val secondCivilization = game.civilizations.single {
                it.isMajorCiv() && it.civID != firstCivilizationId
            }
            secondCivilization.playerId = secondAccountId
            firstCivilization.diplomacyFunctions.makeCivilizationsMeet(secondCivilization)
            firstCivilization.addGold(100)
            secondCivilization.addGold(100)
            val initialFirstGold = firstCivilization.gold
            val initialSecondGold = secondCivilization.gold
            var snapshot = fixture.encode(game)

            fun selectTurn(civilizationId: String) {
                val current = fixture.decode(snapshot)
                current.currentPlayer = civilizationId
                snapshot = fixture.encode(current)
            }

            fun project(
                civilizationId: String,
                accountId: String,
                serverTime: Long,
            ): PlayerProjection {
                val projectionResponse = send(
                    fixture.request(
                        WorkerOperation.ProjectState(snapshot, civilizationId),
                        serverTime,
                        accountId,
                    ),
                )
                results += projectionResponse
                return requireNotNull(projectionResponse.playerProjection)
            }

            fun goldOffer(projection: PlayerProjection, otherCivilizationId: String, amount: Int): ProjectedTrade {
                val gold = projection.tradePartners
                    .single { it.civilizationId == otherCivilizationId }
                    .ourAvailableOffers
                    .single { it.name == Constants.flatGold && it.type == "Gold" }
                return ProjectedTrade(
                    ourOffers = listOf(gold.copy(amount = amount)),
                    theirOffers = emptyList(),
                )
            }

            fun offerFromFirst(amount: Int, serverTime: Long) {
                selectTurn(firstCivilizationId)
                val projection = project(firstCivilizationId, firstAccountId, serverTime)
                response = send(
                    fixture.request(
                        WorkerOperation.OfferTrade(
                            snapshot = snapshot,
                            actorCivilizationId = firstCivilizationId,
                            otherCivilizationId = secondCivilization.civID,
                            trade = goldOffer(projection, secondCivilization.civID, amount),
                        ),
                        serverTime + 1,
                    ),
                )
                results += response
                snapshot = requireNotNull(response.snapshot)
            }

            offerFromFirst(amount = 20, serverTime = 1_700_600_010_000L)
            selectTurn(secondCivilization.civID)
            var secondProjection = project(
                secondCivilization.civID,
                secondAccountId,
                1_700_600_020_000L,
            )
            val firstRequest = secondProjection.pendingTradeRequests.single()
            val counterGold = secondProjection.tradePartners
                .single { it.civilizationId == firstCivilizationId }
                .theirAvailableOffers
                .single { it.name == Constants.flatGold && it.type == "Gold" }
            response = send(
                fixture.request(
                    WorkerOperation.CounterTrade(
                        snapshot = snapshot,
                        actorCivilizationId = secondCivilization.civID,
                        requestId = firstRequest.requestId,
                        trade = ProjectedTrade(
                            ourOffers = emptyList(),
                            theirOffers = listOf(counterGold.copy(amount = 15)),
                        ),
                    ),
                    1_700_600_020_001L,
                    secondAccountId,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            selectTurn(firstCivilizationId)
            var firstProjection = project(
                firstCivilizationId,
                firstAccountId,
                1_700_600_030_000L,
            )
            response = send(
                fixture.request(
                    WorkerOperation.DeclineTrade(
                        snapshot = snapshot,
                        actorCivilizationId = firstCivilizationId,
                        requestId = firstProjection.pendingTradeRequests.single().requestId,
                    ),
                    1_700_600_030_001L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            offerFromFirst(amount = 25, serverTime = 1_700_600_040_000L)
            response = send(
                fixture.request(
                    WorkerOperation.RetractTradeOffer(
                        snapshot = snapshot,
                        actorCivilizationId = firstCivilizationId,
                        otherCivilizationId = secondCivilization.civID,
                    ),
                    1_700_600_040_002L,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            offerFromFirst(amount = 10, serverTime = 1_700_600_050_000L)
            selectTurn(secondCivilization.civID)
            secondProjection = project(
                secondCivilization.civID,
                secondAccountId,
                1_700_600_060_000L,
            )
            response = send(
                fixture.request(
                    WorkerOperation.AcceptTrade(
                        snapshot = snapshot,
                        actorCivilizationId = secondCivilization.civID,
                        requestId = secondProjection.pendingTradeRequests.single().requestId,
                    ),
                    1_700_600_060_001L,
                    secondAccountId,
                ),
            )
            results += response
            snapshot = requireNotNull(response.snapshot)

            selectTurn(firstCivilizationId)
            firstProjection = project(
                firstCivilizationId,
                firstAccountId,
                1_700_600_070_000L,
            )
            val finalGame = fixture.decode(snapshot)
            val finalFirst = finalGame.getCivilization(firstCivilizationId)
            val finalSecond = finalGame.getCivilization(secondCivilization.civID)
            assertTrue(firstProjection.pendingTradeRequests.isEmpty())
            assertEquals(initialFirstGold - 10, finalFirst.gold)
            assertEquals(initialSecondGold + 10, finalSecond.gold)
            assertEquals(1, finalFirst.getDiplomacyManager(finalSecond)!!.trades.size)
            assertEquals(1, finalSecond.getDiplomacyManager(finalFirst)!!.trades.size)
            results
        }

        assertEquals(15, responses.size)
    }

    companion object {
        private const val firstAccountId = "account-trade-parity-one"
        private const val secondAccountId = "account-trade-parity-two"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
