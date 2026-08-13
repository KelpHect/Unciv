package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexCoord
import com.unciv.models.Religion
import com.unciv.models.Spy
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ProjectionLeakSentinelTests {
    private val json = Json { encodeDefaults = true }

    @Test
    fun fogMatrixDisclosesOnlyFieldsPermittedAtEachVisibilityLevel() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val actor = testGame.addCiv(isPlayer = true)
        val neverExplored = testGame.getTile(HexCoord(-3, 0))
        val exploredNotVisible = testGame.getTile(HexCoord(0, 0))
        val currentlyVisible = testGame.getTile(HexCoord(3, 0))
        val hiddenResource = testGame.createResource().also {
            it.revealedBy = "__UNRESEARCHED_TECH_SENTINEL__"
        }
        val staleImprovement = testGame.createTileImprovement()
        val visibleImprovement = testGame.createTileImprovement()
        neverExplored.setTileResource(hiddenResource)
        exploredNotVisible.setTileResource(hiddenResource)
        exploredNotVisible.setImprovementBasic(staleImprovement)
        currentlyVisible.setImprovementBasic(visibleImprovement)
        exploredNotVisible.setExplored(actor, true)
        currentlyVisible.setExplored(actor, true)
        actor.viewableTiles = setOf(currentlyVisible)

        val projection = PlayerProjectionBuilder.build(testGame.gameInfo, actor)
        val encoded = encode(projection)

        assertTrue(projection.exploredTiles.none {
            it.x == neverExplored.position.x && it.y == neverExplored.position.y
        })
        assertFalse(encoded.contains(hiddenResource.name))
        val staleTile = projection.exploredTiles.single {
            it.x == exploredNotVisible.position.x && it.y == exploredNotVisible.position.y
        }
        assertNull(staleTile.resourceName)
        assertNull(staleTile.improvementName)
        assertFalse(encoded.contains(staleImprovement.name))
        assertEquals(visibleImprovement.name, projection.exploredTiles.single {
            it.x == currentlyVisible.position.x && it.y == currentlyVisible.position.y
        }.improvementName)
    }

    @Test
    fun visibleForeignUnitNeverDisclosesPrivateOrdersOrTacticalState() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val actor = testGame.addCiv(isPlayer = true)
        val foreign = testGame.addCiv()
        testGame.addUnit("Warrior", actor, testGame.getTile(HexCoord.Zero))
        val foreignUnit =
            testGame.addUnit("Warrior", foreign, testGame.getTile(HexCoord(1, 0)))
        foreignUnit.instanceName = FOREIGN_UNIT_NAME_SENTINEL
        foreignUnit.currentMovement = 1.875f
        foreignUnit.action = "moveTo 2,0"
        foreignUnit.automated = true
        foreignUnit.movementEscortUnitId = 912_345
        actor.cache.updateViewableTiles()

        val projected = PlayerProjectionBuilder.build(testGame.gameInfo, actor)
            .visibleForeignUnits.single()
        val encoded = encode(PlayerProjectionBuilder.build(testGame.gameInfo, actor))

        assertFalse(encoded.contains(FOREIGN_UNIT_NAME_SENTINEL))
        assertFalse(encoded.contains("1.875"))
        assertNull(projected.currentMovement)
        assertNull(projected.instanceName)
        assertNull(projected.movementDestinationX)
        assertNull(projected.movementDestinationY)
        assertNull(projected.movementEscortUnitId)
        assertFalse(projected.automated)
    }

    @Test
    fun playerProjectionExcludesCanonicalSecretsAcrossSubsystemsAndRelationships() {
        for (status in DiplomaticStatus.entries) {
            val fixture = privateSubsystemFixture()
            val actorDiplomacy = fixture.actor.getDiplomacyManager(fixture.foreign)!!
            val foreignDiplomacy = fixture.foreign.getDiplomacyManager(fixture.actor)!!
            actorDiplomacy.diplomaticStatus = status
            foreignDiplomacy.diplomaticStatus = status
            actorDiplomacy.diplomaticModifiers[DIPLOMACY_SENTINEL] = 37f

            val encoded = encode(PlayerProjectionBuilder.build(
                fixture.testGame.gameInfo,
                fixture.actor,
            ))

            assertTrue(encoded.contains(OWN_CITY_PUBLIC_SENTINEL))
            assertNone(encoded, PRIVATE_SENTINELS)
        }
    }

    @Test
    fun spectatorProjectionContainsOnlyItsPublicSummaryForTheSameCanonicalGame() {
        val fixture = privateSubsystemFixture()

        val projection = SpectatorProjectionBuilder.build(fixture.testGame.gameInfo)
        val encoded = json.encodeToString(SpectatorProjection.serializer(), projection)

        assertEquals(
            fixture.testGame.gameInfo.civilizations.count { it.isMajorCiv() },
            projection.majorCivilizations.size,
        )
        assertNone(encoded, PRIVATE_SENTINELS + OWN_CITY_PUBLIC_SENTINEL)
        assertFalse(encoded.contains("\"cities\""))
        assertFalse(encoded.contains("\"units\""))
        assertFalse(encoded.contains("\"spies\""))
        assertFalse(encoded.contains("\"notifications\""))
        assertFalse(encoded.contains("\"religion\""))
        assertFalse(encoded.contains("\"diplomacy\""))
    }

    private fun privateSubsystemFixture(): PrivateSubsystemFixture {
        val testGame = TestGame()
        testGame.makeHexagonalMap(6)
        testGame.gameInfo.gameParameters.espionageEnabled = true
        val actor = testGame.addCiv(isPlayer = true)
        val foreign = testGame.addCiv()
        val unknown = testGame.addCiv()
        val cityState = testGame.addCiv(cityStateType = "Cultured")
        val barbarian = testGame.addBarbarianCiv()
        actor.diplomacyFunctions.makeCivilizationsMeet(foreign)
        actor.diplomacyFunctions.makeCivilizationsMeet(cityState)

        testGame.addCity(actor, testGame.getTile(HexCoord(-4, 0))).name =
            OWN_CITY_PUBLIC_SENTINEL
        testGame.addCity(foreign, testGame.getTile(HexCoord(0, 0))).name =
            FOREIGN_CITY_SENTINEL
        testGame.addCity(unknown, testGame.getTile(HexCoord(4, 0))).name =
            UNKNOWN_CITY_SENTINEL
        testGame.addCity(cityState, testGame.getTile(HexCoord(0, -4))).name =
            CITY_STATE_CITY_SENTINEL
        val barbarianUnit =
            testGame.addUnit("Warrior", barbarian, testGame.getTile(HexCoord(0, 4)))
        barbarianUnit.instanceName = BARBARIAN_UNIT_SENTINEL

        actor.addNotification(NOTIFICATION_SENTINEL, NotificationCategory.General)
        foreign.espionageManager.spyList.add(Spy(FOREIGN_SPY_SENTINEL, 3))
        val privateReligion = Religion(RELIGION_SENTINEL, testGame.gameInfo, foreign)
        testGame.gameInfo.religions[privateReligion.name] = privateReligion
        foreign.religionManager.religion = privateReligion
        foreign.religionManager.freeBeliefs[RELIGION_BELIEF_SENTINEL] = 1

        return PrivateSubsystemFixture(testGame, actor, foreign)
    }

    @Test
    fun cityStatePartnerExposesInfluenceAndRelationshipLevelWithoutLeakingActions() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val actor = testGame.addCiv(isPlayer = true)
        val cityState = testGame.addCiv(cityStateType = "Cultured")
        testGame.addCity(actor, testGame.getTile(HexCoord(0, 0)))
        testGame.addCity(cityState, testGame.getTile(HexCoord(0, -1)))
        actor.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val diplomacy = cityState.getDiplomacyManager(actor)!!

        var partner = CityStateCommandExecutor.partners(actor).single()
        assertEquals(0, partner.influence)
        assertEquals(ProjectedCityStateInfluenceLevel.Neutral, partner.influenceLevel)
        assertTrue(partner.quests.isEmpty())

        diplomacy.addInfluence(30f)
        partner = CityStateCommandExecutor.partners(actor).single()
        assertEquals(30, partner.influence)
        assertEquals(ProjectedCityStateInfluenceLevel.Friend, partner.influenceLevel)

        diplomacy.addInfluence(35f)
        cityState.cityStateFunctions.updateAllyCivForCityState()
        partner = CityStateCommandExecutor.partners(actor).single()
        assertEquals(65, partner.influence)
        assertEquals(ProjectedCityStateInfluenceLevel.Ally, partner.influenceLevel)
    }

    private fun encode(projection: PlayerProjection): String =
        json.encodeToString(PlayerProjection.serializer(), projection)

    private fun assertNone(encoded: String, sentinels: Collection<String>) {
        for (sentinel in sentinels)
            assertFalse("Projection leaked $sentinel", encoded.contains(sentinel))
    }

    private data class PrivateSubsystemFixture(
        val testGame: TestGame,
        val actor: com.unciv.logic.civilization.Civilization,
        val foreign: com.unciv.logic.civilization.Civilization,
    )

    private companion object {
        const val OWN_CITY_PUBLIC_SENTINEL = "__OWN_CITY_PUBLIC_SENTINEL__"
        const val FOREIGN_CITY_SENTINEL = "__FOREIGN_CITY_PRIVATE_SENTINEL__"
        const val UNKNOWN_CITY_SENTINEL = "__UNKNOWN_CITY_PRIVATE_SENTINEL__"
        const val CITY_STATE_CITY_SENTINEL = "__CITY_STATE_CITY_PRIVATE_SENTINEL__"
        const val BARBARIAN_UNIT_SENTINEL = "__BARBARIAN_UNIT_PRIVATE_SENTINEL__"
        const val FOREIGN_UNIT_NAME_SENTINEL = "__FOREIGN_UNIT_NAME_SENTINEL__"
        const val NOTIFICATION_SENTINEL = "__NOTIFICATION_PRIVATE_SENTINEL__"
        const val DIPLOMACY_SENTINEL = "__DIPLOMACY_MODIFIER_PRIVATE_SENTINEL__"
        const val FOREIGN_SPY_SENTINEL = "__FOREIGN_SPY_PRIVATE_SENTINEL__"
        const val RELIGION_SENTINEL = "__FOREIGN_RELIGION_PRIVATE_SENTINEL__"
        const val RELIGION_BELIEF_SENTINEL = "__FOREIGN_RELIGION_BELIEF_SENTINEL__"
        val PRIVATE_SENTINELS = listOf(
            FOREIGN_CITY_SENTINEL,
            UNKNOWN_CITY_SENTINEL,
            CITY_STATE_CITY_SENTINEL,
            BARBARIAN_UNIT_SENTINEL,
            NOTIFICATION_SENTINEL,
            DIPLOMACY_SENTINEL,
            FOREIGN_SPY_SENTINEL,
            RELIGION_SENTINEL,
            RELIGION_BELIEF_SENTINEL,
        )
    }
}
