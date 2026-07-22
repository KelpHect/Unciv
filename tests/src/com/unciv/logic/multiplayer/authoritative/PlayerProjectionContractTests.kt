package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.unciv.logic.city.CityFocus as DomainCityFocus
import java.io.File

class PlayerProjectionContractTests {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Test
    fun sharedRustKotlinProjectionFixtureRoundTripsSemantically() {
        val fixture = projectionFixture().readText()
        val projection = json.decodeFromString(PlayerProjection.serializer(), fixture)

        assertEquals(3, projection.protocolVersion)
        assertEquals(
            listOf(
                PendingEndTurnAction.PickPolicy,
                PendingEndTurnAction.CastDiplomaticVote,
                PendingEndTurnAction.PickGreatPerson,
            ),
            projection.pendingTurnActions,
        )
        assertEquals(listOf("Greece"), projection.diplomaticVoteCandidates)
        assertEquals("Greece", projection.diplomacyPartners.single().civilizationId)
        assertEquals(listOf(DiplomaticDemand.DoNotSettleNearUs),
            projection.diplomacyPartners.single().availableDemands)
        assertEquals(DiplomacyPromptType.Friendship, projection.diplomacyPrompts.single().type)
        assertEquals("Geneva", projection.cityStatePartners.single().civilizationId)
        assertEquals(listOf(250), projection.cityStatePartners.single().availableGoldGifts)
        assertEquals(listOf("Great Engineer", "Great Scientist"), projection.selectableGreatPeople)
        assertEquals(listOf(ReligiousBeliefType.Founder, ReligiousBeliefType.Follower),
            projection.religionChoice!!.requiredBeliefTypes)
        assertEquals(listOf("Buddhism", "Christianity"),
            projection.religionChoice!!.availableReligionIcons)
        assertEquals("Pottery", projection.research.currentTechnology)
        assertEquals(listOf("Archery"), projection.research.appendableTargets)
        assertEquals(listOf("Tradition"), projection.policies.selectablePolicies)
        assertEquals(listOf("Monument"), projection.ownCities.single().constructionQueue)
        assertEquals(CitizenFocus.GoldFocus, projection.ownCities.single().citizenFocus)
        assertEquals("Warrior", projection.ownCities.single().unitPromotionPreferences.single().baseUnitName)
        assertTrue(projection.ownCities.single().unitPromotionPreferences.single().enabled)
        assertEquals(7, projection.ownUnits.single().movementDestinationX)
        assertTrue(projection.ownUnits.single().automated)
        assertTrue(!projection.ownUnits.single().exploring)
        assertEquals(UnitPosture.Fortify, projection.ownUnits.single().posture)
        assertEquals(listOf("Drill I"), projection.ownUnits.single().promotions)
        assertEquals(12, projection.ownUnits.single().promotionXp)
        assertEquals(30, projection.ownUnits.single().nextPromotionXp)
        assertEquals(listOf("Drill II", "Shock I"), projection.ownUnits.single().availablePromotions)
        assertEquals("First Mission", projection.ownUnits.single().instanceName)
        assertEquals(listOf(ReligiousUnitAction.SpreadReligion),
            projection.ownUnits.single().availableReligiousActions)
        assertEquals(listOf("Remove Forest", "Farm"),
            projection.ownUnits.single().improvementOrder.map { it.improvementName })
        assertEquals(8, projection.ownUnits.single().roadConnectionDestinationX)
        assertEquals(4, projection.ownUnits.single().roadConnectionPath.size)
        assertEquals(null, projection.visibleForeignUnits.single().movementDestinationX)
        assertTrue(!projection.visibleForeignUnits.single().automated)
        assertTrue(!projection.visibleForeignUnits.single().exploring)
        assertEquals(null, projection.visibleForeignUnits.single().posture)
        assertEquals(emptyList<String>(), projection.visibleForeignUnits.single().promotions)
        assertEquals(null, projection.visibleForeignUnits.single().promotionXp)
        assertEquals(null, projection.visibleForeignUnits.single().nextPromotionXp)
        assertEquals(emptyList<String>(), projection.visibleForeignUnits.single().availablePromotions)
        assertEquals(null, projection.visibleForeignUnits.single().instanceName)
        assertEquals(emptyList<ProjectedImprovementOrderEntry>(),
            projection.visibleForeignUnits.single().improvementOrder)
        assertEquals(null, projection.visibleForeignUnits.single().roadConnectionDestinationX)
        assertEquals(emptyList<ProjectedRoadPathTile>(),
            projection.visibleForeignUnits.single().roadConnectionPath)
        assertEquals(emptyList<ReligiousUnitAction>(),
            projection.visibleForeignUnits.single().availableReligiousActions)
        assertEquals(
            json.parseToJsonElement(fixture),
            json.parseToJsonElement(json.encodeToString(PlayerProjection.serializer(), projection)),
        )
    }

    @Test
    fun citizenFocusWireVocabularyCoversTheDomainEnum() {
        assertEquals(DomainCityFocus.entries.map { it.name }, CitizenFocus.entries.map { it.name })
    }

    @Test
    fun unknownCanonicalFieldsCannotCrossTheProjectionContract() {
        val fixture = projectionFixture().readText().replaceFirst(
            "{",
            "{\"canonicalGameInfo\":{\"secret\":true},",
        )
        assertTrue(runCatching {
            json.decodeFromString(PlayerProjection.serializer(), fixture)
        }.isFailure)
    }

    @Test
    fun unknownPendingTurnActionsCannotCrossTheProjectionContract() {
        val fixture = projectionFixture().readText().replace(
            "pick_policy",
            "replace_canonical_state",
        )
        assertTrue(runCatching {
            json.decodeFromString(PlayerProjection.serializer(), fixture)
        }.isFailure)
    }

    private fun projectionFixture(): File = generateSequence(
        File(System.getProperty("user.dir")).absoluteFile,
        File::getParentFile,
    ).map { File(it, "protocol/player-projection-v36.fixture.json") }
        .first { it.isFile }
}
