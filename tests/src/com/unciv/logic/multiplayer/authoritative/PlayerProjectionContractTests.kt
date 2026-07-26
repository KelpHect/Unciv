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
        assertEquals(listOf("Tradition"),
            projection.diplomacyPartners.single().adoptedPolicyBranches)
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
        assertEquals(14, projection.research.queueEntries.first().storedScience)
        assertEquals(4, projection.research.queueEntries.first().estimatedTurns)
        assertEquals(emptyList<ResearchQueueAction>(),
            projection.research.queueEntries.first().availableActions)
        assertEquals(listOf(ResearchQueueAction.Remove),
            projection.research.queueEntries.last().availableActions)
        assertEquals(3, projection.research.overflowScience)
        assertEquals(listOf("Agriculture", "Mining"), projection.research.researchedTechnologies)
        assertEquals("Mining", projection.research.completionPrompts.single().technologyName)
        assertEquals(listOf("Tradition"), projection.policies.selectablePolicies)
        assertEquals("Grassland", projection.exploredTiles.first().baseTerrain)
        assertEquals(listOf("Forest"), projection.exploredTiles.first().terrainFeatures)
        assertEquals("Wheat", projection.exploredTiles.first().resourceName)
        assertEquals(null, projection.exploredTiles.last().resourceName)
        assertEquals(listOf("Great Library", "Stonehenge"),
            projection.wonderEvents.map { it.wonderName })
        assertEquals("Rome", projection.wonderEvents.first().builderCivilizationId)
        assertEquals(null, projection.wonderEvents.last().cityId)
        assertEquals(listOf("Monument"), projection.ownCities.single().constructionQueue)
        assertEquals(12, projection.ownCities.single().constructionQueueEntries.single().storedProduction)
        assertEquals(160, projection.ownCities.single().constructionQueueEntries.single()
            .purchases.single().cost)
        assertEquals(listOf("Archer", "Granary", "Nothing"), projection.ownCities.single()
            .constructionOptions.map { it.name })
        assertEquals(ProjectedConstructionKind.Perpetual, projection.ownCities.single()
            .constructionOptions.last().kind)
        assertEquals(listOf(ConstructionQueueAction.AddToTop), projection.ownCities.single()
            .constructionOptions.first().availableActions)
        assertEquals(75, projection.ownCities.single().tilePurchases.single().goldCost)
        assertEquals(3, projection.ownCities.single().tileBatchPurchases.single().tileCount)
        assertEquals("city-rome", projection.ownCities.single().tileStates.first().workingCityId)
        assertEquals(CitizenFocus.GoldFocus, projection.ownCities.single().citizenFocus)
        assertEquals(listOf("Monument"), projection.ownCities.single().sellableBuildings)
        assertEquals("Warrior", projection.ownCities.single().unitPromotionPreferences.single().baseUnitName)
        assertTrue(projection.ownCities.single().unitPromotionPreferences.single().enabled)
        assertEquals(7, projection.ownUnits.single().movementDestinationX)
        assertEquals(null, projection.ownUnits.single().capitalProjectName)
        assertEquals(listOf(ProjectedMovementDestination(2, -1)),
            projection.ownUnits.single().moveDestinations)
        assertEquals(listOf(ProjectedMovementDestination(2, -1)),
            projection.ownUnits.single().swapDestinations)
        val attackTarget = projection.ownUnits.single().attackTargets.single()
        assertEquals(ProjectedTargetCoordinate(2, -1),
            ProjectedTargetCoordinate(attackTarget.x, attackTarget.y))
        assertEquals(11, attackTarget.preview.attackerEffectiveStrength)
        assertEquals(listOf(ProjectedCombatModifier("Flanking", 10)),
            attackTarget.preview.attackerModifiers)
        val nuclearTarget = projection.ownUnits.single().nuclearTargetCandidates.single()
        assertEquals(ProjectedTargetCoordinate(2, -1),
            ProjectedTargetCoordinate(nuclearTarget.x, nuclearTarget.y))
        assertEquals(2, nuclearTarget.blastRadius)
        assertEquals(ProjectedNuclearEffectDisclosure.HiddenUntilCommit,
            nuclearTarget.effectDisclosure)
        val airSweepTarget = projection.ownUnits.single().airSweepTargets.single()
        assertEquals(ProjectedTargetCoordinate(2, -1),
            ProjectedTargetCoordinate(airSweepTarget.x, airSweepTarget.y))
        assertEquals(10, airSweepTarget.attackerBaseStrength)
        assertEquals(listOf(ProjectedCombatModifier("Sweep bonus", 25)),
            airSweepTarget.attackerModifiers)
        assertEquals(ProjectedAirSweepInterceptorDisclosure.HiddenUntilCommit,
            airSweepTarget.interceptorDisclosure)
        assertEquals(ProjectedTargetCoordinate(2, -1), projection.ownCities.single()
            .bombardTargets.single().let { ProjectedTargetCoordinate(it.x, it.y) })
        assertEquals(70, projection.ownCities.single().bombardTargets.single()
            .preview.defenderMinRemainingHealth)
        assertTrue(projection.ownUnits.single().automated)
        assertTrue(!projection.ownUnits.single().exploring)
        assertEquals(UnitPosture.Fortify, projection.ownUnits.single().posture)
        assertEquals(listOf("Drill I"), projection.ownUnits.single().promotions)
        assertEquals(12, projection.ownUnits.single().promotionXp)
        assertEquals(30, projection.ownUnits.single().nextPromotionXp)
        assertEquals(listOf("Drill II", "Shock I"), projection.ownUnits.single().availablePromotions)
        assertEquals("First Mission", projection.ownUnits.single().instanceName)
        assertEquals(listOf(UnitPosture.Sleep, UnitPosture.Guard),
            projection.ownUnits.single().availablePostures)
        assertTrue(projection.ownUnits.single().canDisband)
        assertTrue(projection.ownUnits.single().canPillage)
        assertTrue(!projection.ownUnits.single().canFoundCity)
        assertTrue(projection.ownUnits.single().canRename)
        assertEquals(listOf(ProjectedMovementDestination(2, -1)),
            projection.ownUnits.single().paradropDestinations)
        assertEquals(listOf(ProjectedUnitUpgradeTarget("Rifleman", 120)),
            projection.ownUnits.single().availableUpgradeTargets)
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
        assertEquals(emptyList<UnitPosture>(),
            projection.visibleForeignUnits.single().availablePostures)
        assertTrue(!projection.visibleForeignUnits.single().canDisband)
        assertTrue(!projection.visibleForeignUnits.single().canPillage)
        assertTrue(!projection.visibleForeignUnits.single().canFoundCity)
        assertTrue(!projection.visibleForeignUnits.single().canRename)
        assertEquals(emptyList<ProjectedMovementDestination>(),
            projection.visibleForeignUnits.single().paradropDestinations)
        assertEquals(emptyList<ProjectedUnitUpgradeTarget>(),
            projection.visibleForeignUnits.single().availableUpgradeTargets)
        assertEquals(emptyList<ProjectedImprovementOrderEntry>(),
            projection.visibleForeignUnits.single().improvementOrder)
        assertEquals(null, projection.visibleForeignUnits.single().roadConnectionDestinationX)
        assertEquals(emptyList<ProjectedRoadPathTile>(),
            projection.visibleForeignUnits.single().roadConnectionPath)
        assertEquals(emptyList<ReligiousUnitAction>(),
            projection.visibleForeignUnits.single().availableReligiousActions)
        assertEquals(emptyList<ProjectedMovementDestination>(),
            projection.visibleForeignUnits.single().moveDestinations)
        assertEquals(emptyList<ProjectedMovementDestination>(),
            projection.visibleForeignUnits.single().swapDestinations)
        assertEquals(emptyList<ProjectedAttackTarget>(),
            projection.visibleForeignUnits.single().attackTargets)
        assertEquals(emptyList<ProjectedNuclearTarget>(),
            projection.visibleForeignUnits.single().nuclearTargetCandidates)
        assertEquals(emptyList<ProjectedAirSweepTarget>(),
            projection.visibleForeignUnits.single().airSweepTargets)
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
    ).map { File(it, "protocol/player-projection-v57.fixture.json") }
        .first { it.isFile }
}
