package com.unciv.app.server.authoritative

import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.multiplayer.authoritative.DiplomaticDemand
import com.unciv.logic.multiplayer.authoritative.DiplomacyPromptType
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PackagedWorkerMajorDiplomacyParityTests {
    @Test(timeout = 300_000)
    fun majorCivilizationDiplomacyIsStableAcrossFreshWorkers() {
        val friendshipFixture = fixture("00000000-0000-4000-8000-000000000701", 387_140_296L)
        val demandFixture = fixture("00000000-0000-4000-8000-000000000702", 387_140_297L)
        val warFixture = fixture("00000000-0000-4000-8000-000000000703", 387_140_298L)
        val responses = PackagedWorkerParityHarness.assertStableScenario { send ->
            val results = mutableListOf<WorkerResponse>()

            fun prepare(fixture: PackagedWorkerScenarioFixture, serverTime: Long): PreparedGame {
                val creation = send(fixture.createGameRequest(serverTime))
                results += creation
                val firstCivilizationId = requireNotNull(creation.actorCivilizationId)
                val game = fixture.decode(creation.snapshot)
                val first = game.getCivilization(firstCivilizationId)
                val second = game.civilizations.single {
                    it.isMajorCiv() && it.civID != firstCivilizationId
                }
                second.playerId = secondAccountId
                first.diplomacyFunctions.makeCivilizationsMeet(second)
                return PreparedGame(fixture.encode(game), firstCivilizationId, second.civID)
            }

            fun project(
                fixture: PackagedWorkerScenarioFixture,
                snapshot: String,
                civilizationId: String,
                accountId: String,
                serverTime: Long,
            ): PlayerProjection {
                val response = send(
                    fixture.request(
                        WorkerOperation.ProjectState(snapshot, civilizationId),
                        serverTime,
                        accountId,
                    ),
                )
                results += response
                return requireNotNull(response.playerProjection)
            }

            fun selectTurn(
                fixture: PackagedWorkerScenarioFixture,
                snapshot: String,
                civilizationId: String,
            ): String {
                val game = fixture.decode(snapshot)
                game.currentPlayer = civilizationId
                return fixture.encode(game)
            }

            var friendship = prepare(friendshipFixture, 1_700_700_000_000L)
            var projection = project(
                friendshipFixture,
                friendship.snapshot,
                friendship.firstCivilizationId,
                firstAccountId,
                1_700_700_010_000L,
            )
            val partner = projection.diplomacyPartners.single {
                it.civilizationId == friendship.secondCivilizationId
            }
            assertTrue(partner.canOfferFriendship)
            var response = send(
                friendshipFixture.request(
                    WorkerOperation.OfferFriendship(
                        snapshot = friendship.snapshot,
                        actorCivilizationId = friendship.firstCivilizationId,
                        otherCivilizationId = friendship.secondCivilizationId,
                    ),
                    1_700_700_010_001L,
                ),
            )
            results += response
            friendship = friendship.copy(snapshot = requireNotNull(response.snapshot))
            friendship = friendship.copy(
                snapshot = selectTurn(
                    friendshipFixture,
                    friendship.snapshot,
                    friendship.secondCivilizationId,
                ),
            )
            projection = project(
                friendshipFixture,
                friendship.snapshot,
                friendship.secondCivilizationId,
                secondAccountId,
                1_700_700_020_000L,
            )
            val friendshipPrompt = projection.diplomacyPrompts.single()
            assertEquals(DiplomacyPromptType.Friendship, friendshipPrompt.type)
            response = send(
                friendshipFixture.request(
                    WorkerOperation.RespondToDiplomaticPrompt(
                        snapshot = friendship.snapshot,
                        actorCivilizationId = friendship.secondCivilizationId,
                        promptId = friendshipPrompt.promptId,
                        accept = true,
                    ),
                    1_700_700_020_001L,
                    secondAccountId,
                ),
            )
            results += response
            friendship = friendship.copy(snapshot = requireNotNull(response.snapshot))
            val friendshipGame = friendshipFixture.decode(friendship.snapshot)
            assertTrue(
                friendshipGame.getCivilization(friendship.firstCivilizationId)
                    .getDiplomacyManager(friendshipGame.getCivilization(friendship.secondCivilizationId))!!
                    .hasFlag(DiplomacyFlags.DeclarationOfFriendship),
            )

            var demand = prepare(demandFixture, 1_700_700_100_000L)
            projection = project(
                demandFixture,
                demand.snapshot,
                demand.firstCivilizationId,
                firstAccountId,
                1_700_700_110_000L,
            )
            val demandPartner = projection.diplomacyPartners.single {
                it.civilizationId == demand.secondCivilizationId
            }
            val demandType = demandPartner.availableDemands
                .single { it == DiplomaticDemand.DoNotSettleNearUs }
            response = send(
                demandFixture.request(
                    WorkerOperation.MakeDiplomaticDemand(
                        snapshot = demand.snapshot,
                        actorCivilizationId = demand.firstCivilizationId,
                        otherCivilizationId = demand.secondCivilizationId,
                        demand = demandType,
                    ),
                    1_700_700_110_001L,
                ),
            )
            results += response
            demand = demand.copy(snapshot = requireNotNull(response.snapshot))
            demand = demand.copy(
                snapshot = selectTurn(
                    demandFixture,
                    demand.snapshot,
                    demand.secondCivilizationId,
                ),
            )
            projection = project(
                demandFixture,
                demand.snapshot,
                demand.secondCivilizationId,
                secondAccountId,
                1_700_700_120_000L,
            )
            val demandPrompt = projection.diplomacyPrompts.single()
            assertEquals(DiplomacyPromptType.Demand, demandPrompt.type)
            assertEquals(demandType, demandPrompt.demand)
            response = send(
                demandFixture.request(
                    WorkerOperation.RespondToDiplomaticPrompt(
                        snapshot = demand.snapshot,
                        actorCivilizationId = demand.secondCivilizationId,
                        promptId = demandPrompt.promptId,
                        accept = true,
                    ),
                    1_700_700_120_001L,
                    secondAccountId,
                ),
            )
            results += response
            demand = demand.copy(snapshot = requireNotNull(response.snapshot))
            val demandGame = demandFixture.decode(demand.snapshot)
            val demandingCivilization = demandGame.getCivilization(demand.firstCivilizationId)
            val respondingCivilization = demandGame.getCivilization(demand.secondCivilizationId)
            assertTrue(
                respondingCivilization.getDiplomacyManager(demandingCivilization)!!
                    .otherCivDiplomacy().hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs),
            )

            var war = prepare(warFixture, 1_700_700_200_000L)
            projection = project(
                warFixture,
                war.snapshot,
                war.firstCivilizationId,
                firstAccountId,
                1_700_700_210_000L,
            )
            val warPartner = projection.diplomacyPartners.single {
                it.civilizationId == war.secondCivilizationId
            }
            assertTrue(warPartner.canDenounce)
            assertTrue(warPartner.canDeclareWar)
            response = send(
                warFixture.request(
                    WorkerOperation.DenounceCivilization(
                        snapshot = war.snapshot,
                        actorCivilizationId = war.firstCivilizationId,
                        otherCivilizationId = war.secondCivilizationId,
                    ),
                    1_700_700_210_001L,
                ),
            )
            results += response
            war = war.copy(snapshot = requireNotNull(response.snapshot))
            response = send(
                warFixture.request(
                    WorkerOperation.DeclareWar(
                        snapshot = war.snapshot,
                        actorCivilizationId = war.firstCivilizationId,
                        otherCivilizationId = war.secondCivilizationId,
                    ),
                    1_700_700_210_002L,
                ),
            )
            results += response
            war = war.copy(snapshot = requireNotNull(response.snapshot))
            val warGame = warFixture.decode(war.snapshot)
            assertTrue(
                warGame.getCivilization(war.firstCivilizationId)
                    .isAtWarWith(warGame.getCivilization(war.secondCivilizationId)),
            )
            results
        }

        assertEquals(14, responses.size)
    }

    private data class PreparedGame(
        val snapshot: String,
        val firstCivilizationId: String,
        val secondCivilizationId: String,
    )

    private fun fixture(gameId: String, serverSeed: Long) = PackagedWorkerScenarioFixture(
        actorId = firstAccountId,
        gameId = gameId,
        serverSeed = serverSeed,
    )

    companion object {
        private const val firstAccountId = "account-major-diplomacy-one"
        private const val secondAccountId = "account-major-diplomacy-two"

        @JvmStatic
        @BeforeClass
        fun initializeRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
