package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.PerpetualConstruction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Deliberately constructed player view. This is not a redacted GameInfo: fields
 * absent from these DTOs cannot leak through serialization accidentally.
 */
@Serializable
data class PlayerProjection(
    val protocolVersion: Int = CommandEnvelope.CURRENT_PROTOCOL_VERSION,
    val civilizationId: String,
    val turn: Int,
    val currentPlayerCivilizationId: String,
    val isCurrentTurn: Boolean,
    val pendingTurnActions: List<PendingEndTurnAction>,
    val research: ProjectedResearch,
    val policies: ProjectedPolicies,
    val gold: Int,
    val knownCivilizations: List<String>,
    val ownCities: List<ProjectedCity>,
    val ownUnits: List<ProjectedUnit>,
    val exploredTiles: List<ProjectedTileVisibility>,
    val visibleForeignUnits: List<ProjectedUnit>,
) {
    companion object {
        const val CURRENT_PROJECTION_VERSION = 16
    }
}

@Serializable
data class ProjectedCity(
    val id: String,
    val name: String,
    val x: Int,
    val y: Int,
    val population: Int,
    val health: Int,
    val constructionQueue: List<String>,
    val availableConstructions: List<String>,
    val assignableTiles: List<ProjectedCityTile> = emptyList(),
    val manualSpecialists: Boolean = false,
    val specialists: List<ProjectedSpecialist> = emptyList(),
    val avoidGrowth: Boolean = false,
    val citizenFocus: CitizenFocus = CitizenFocus.NoFocus,
    val selectableCitizenFocuses: List<CitizenFocus> = emptyList(),
    val unitPromotionPreferences: List<ProjectedUnitPromotionPreference> = emptyList(),
)

@Serializable
data class ProjectedUnitPromotionPreference(
    val baseUnitName: String,
    val enabled: Boolean,
    val savedPromotions: List<String>,
)

@Serializable
data class ProjectedCityTile(
    val x: Int,
    val y: Int,
    val worked: Boolean,
    val locked: Boolean,
)

@Serializable
data class ProjectedSpecialist(
    val name: String,
    val assigned: Int,
    val capacity: Int,
)

@Serializable
data class ProjectedResearch(
    val currentTechnology: String?,
    val queue: List<String>,
    val selectableTargets: List<String>,
    val freeTechnologyChoices: List<String>,
)

@Serializable
data class ProjectedPolicies(
    val storedCulture: Int,
    val cultureNeededForNextPolicy: Int,
    val freePolicies: Int,
    val adoptedPolicies: List<String>,
    val selectablePolicies: List<String>,
)

@Serializable
data class ProjectedUnit(
    val id: Int,
    val civilizationId: String,
    val name: String,
    val x: Int,
    val y: Int,
    val health: Int,
    val currentMovement: Float,
    val movementDestinationX: Int? = null,
    val movementDestinationY: Int? = null,
    val automated: Boolean = false,
    val exploring: Boolean = false,
    val posture: UnitPosture? = null,
    val promotions: List<String> = emptyList(),
    val promotionXp: Int? = null,
    val nextPromotionXp: Int? = null,
    val availablePromotions: List<String> = emptyList(),
    val instanceName: String? = null,
    val improvementOrder: List<ProjectedImprovementOrderEntry> = emptyList(),
    val roadConnectionDestinationX: Int? = null,
    val roadConnectionDestinationY: Int? = null,
    val roadConnectionPath: List<ProjectedRoadPathTile> = emptyList(),
)

@Serializable
data class ProjectedImprovementOrderEntry(
    val improvementName: String,
    val turnsRemaining: Int,
)

@Serializable
data class ProjectedRoadPathTile(val x: Int, val y: Int)

@Serializable
data class ProjectedTileVisibility(
    val x: Int,
    val y: Int,
    val visible: Boolean,
)

object PlayerProjectionBuilder {
    fun build(game: GameInfo, actor: Civilization): PlayerProjection {
        val ownUnits = actor.units.getCivUnits()
            .map { unitProjection(it, includePrivateOrders = true) }
            .sortedBy { it.id }
            .toList()
        val visibleForeignUnits = game.tileMap.tileList.asSequence()
            .filter { it in actor.viewableTiles }
            .flatMap { it.getUnits() }
            .filter { it.civ != actor }
            .filter { !it.isInvisible(actor) || it.getTile() in actor.viewableInvisibleUnitsTiles }
            .map { unitProjection(it, includePrivateOrders = false) }
            .sortedWith(compareBy<ProjectedUnit> { it.civilizationId }.thenBy { it.id })
            .toList()
        return PlayerProjection(
            civilizationId = actor.civID,
            turn = game.turns,
            currentPlayerCivilizationId = game.currentPlayer,
            isCurrentTurn = game.currentPlayer == actor.civID,
            pendingTurnActions = AuthoritativeTurnReadiness.pendingActions(actor),
            research = researchProjection(actor),
            policies = policyProjection(actor),
            gold = actor.gold,
            knownCivilizations = actor.getKnownCivs().map { it.civID }.sorted().toList(),
            ownCities = actor.cities.map {
                ProjectedCity(
                    id = it.id,
                    name = it.name,
                    x = it.location.x,
                    y = it.location.y,
                    population = it.population.population,
                    health = it.health,
                    constructionQueue = it.cityConstructions.constructionQueue.toList(),
                    availableConstructions = (
                        it.cityConstructions.getBuildableBuildings().map { construction -> construction.name } +
                            it.cityConstructions.getConstructableUnits().map { construction -> construction.name } +
                            PerpetualConstruction.perpetualConstructionsMap.values
                                .filter { construction -> construction.shouldBeDisplayed(it.cityConstructions) }
                                .map { construction -> construction.name }
                    ).sorted().toList(),
                    assignableTiles = it.tilesInRange.asSequence()
                        .filter { tile -> tile.getOwner() == actor }
                        .filter { tile -> !tile.isCityCenter() }
                        .filter { tile -> !tile.isWorked() || tile.getWorkingCity() == it }
                        .filter { tile -> !tile.stats.getTileStats(it, actor).isEmpty() }
                        .filter { tile -> !tile.isBlockaded() }
                        .map { tile -> ProjectedCityTile(
                            tile.position.x,
                            tile.position.y,
                            tile.isWorked() && tile.getWorkingCity() == it,
                            tile.isLocked() && tile.getWorkingCity() == it,
                        ) }
                        .sortedWith(compareBy<ProjectedCityTile> { tile -> tile.x }.thenBy { tile -> tile.y })
                        .toList(),
                    manualSpecialists = it.manualSpecialists,
                    specialists = it.population.getMaxSpecialists().asSequence()
                        .filter { specialist -> specialist.value > 0 }
                        .filter { specialist -> it.getRuleset().specialists.containsKey(specialist.key) }
                        .map { specialist -> ProjectedSpecialist(
                            specialist.key,
                            it.population.specialistAllocations[specialist.key],
                            specialist.value,
                        ) }
                        .sortedBy { specialist -> specialist.name }
                        .toList(),
                    avoidGrowth = it.avoidGrowth,
                    citizenFocus = CitizenFocus.valueOf(it.getCityFocus().name),
                    selectableCitizenFocuses = com.unciv.logic.city.CityFocus.entries.asSequence()
                        .filter { focus -> focus.tableEnabled }
                        .filter { focus -> focus != com.unciv.logic.city.CityFocus.FaithFocus || game.isReligionEnabled() }
                        .map { focus -> CitizenFocus.valueOf(focus.name) }
                        .toList(),
                    unitPromotionPreferences = it.unitToPromotions.entries.asSequence()
                        .map { preference -> ProjectedUnitPromotionPreference(
                            preference.key,
                            it.unitShouldUseSavedPromotion[preference.key] == true,
                            preference.value.promotions.sorted(),
                        ) }
                        .sortedBy { preference -> preference.baseUnitName }
                        .toList(),
                )
            }.sortedBy { it.id },
            ownUnits = ownUnits,
            exploredTiles = game.tileMap.tileList.asSequence()
                .filter { it.isExplored(actor) }
                .map { ProjectedTileVisibility(it.position.x, it.position.y, it in actor.viewableTiles) }
                .sortedWith(compareBy<ProjectedTileVisibility> { it.x }.thenBy { it.y })
                .toList(),
            visibleForeignUnits = visibleForeignUnits,
        )
    }

    private fun unitProjection(unit: MapUnit, includePrivateOrders: Boolean): ProjectedUnit {
        val destination = if (includePrivateOrders && unit.isMoving())
            unit.getMovementDestination().position else null
        return ProjectedUnit(
        id = unit.id,
        civilizationId = unit.civ.civID,
        name = unit.name,
        x = unit.getTile().position.x,
        y = unit.getTile().position.y,
        health = unit.health,
        currentMovement = unit.currentMovement,
        movementDestinationX = destination?.x,
        movementDestinationY = destination?.y,
        automated = includePrivateOrders && unit.isAutomated(),
        exploring = includePrivateOrders && unit.isExploring(),
        posture = if (includePrivateOrders) unitPosture(unit) else null,
        promotions = if (includePrivateOrders) unit.promotions.promotions.sorted() else emptyList(),
        promotionXp = if (includePrivateOrders) unit.promotions.XP else null,
        nextPromotionXp = if (includePrivateOrders) unit.promotions.xpForNextPromotion() else null,
        availablePromotions = if (includePrivateOrders)
            unit.promotions.getAvailablePromotions().map { it.name }.sorted().toList()
        else emptyList(),
        instanceName = if (includePrivateOrders) unit.instanceName else null,
        improvementOrder = if (includePrivateOrders)
            unit.currentTile.getImprovementQueueSnapshot().map {
                ProjectedImprovementOrderEntry(it.first, it.second)
            }
        else emptyList(),
        roadConnectionDestinationX = if (includePrivateOrders)
            unit.automatedRoadConnectionDestination?.x else null,
        roadConnectionDestinationY = if (includePrivateOrders)
            unit.automatedRoadConnectionDestination?.y else null,
        roadConnectionPath = if (includePrivateOrders)
            unit.automatedRoadConnectionPath.orEmpty().map { ProjectedRoadPathTile(it.x, it.y) }
        else emptyList(),
    )
    }

    private fun unitPosture(unit: MapUnit): UnitPosture? = when {
        unit.isSleepingUntilHealed() -> UnitPosture.SleepUntilHealed
        unit.isSleeping() -> UnitPosture.Sleep
        unit.isFortifyingUntilHealed() -> UnitPosture.FortifyUntilHealed
        unit.isFortified() -> UnitPosture.Fortify
        unit.isGuarding() -> UnitPosture.Guard
        else -> null
    }

    private fun researchProjection(civilization: Civilization): ProjectedResearch {
        val technologies = civilization.gameInfo.ruleset.technologies.values
        return ProjectedResearch(
            currentTechnology = civilization.tech.currentTechnologyName(),
            queue = civilization.tech.techsToResearch.toList(),
            selectableTargets = technologies.asSequence()
                .filter { civilization.tech.getRequiredTechsToDestination(it).isNotEmpty() }
                .map { it.name }
                .sorted()
                .toList(),
            freeTechnologyChoices = if (civilization.tech.freeTechs == 0) emptyList() else
                technologies.asSequence()
                    .filter { civilization.tech.canBeResearched(it.name) }
                    .map { it.name }
                    .sorted()
                    .toList(),
        )
    }

    private fun policyProjection(civilization: Civilization): ProjectedPolicies {
        val manager = civilization.policies
        val canAdopt = manager.canAdoptPolicy()
        return ProjectedPolicies(
            storedCulture = manager.storedCulture,
            cultureNeededForNextPolicy = manager.getCultureNeededForNextPolicy(),
            freePolicies = manager.freePolicies,
            adoptedPolicies = manager.getAdoptedPolicies().sorted(),
            selectablePolicies = if (!canAdopt) emptyList() else
                civilization.gameInfo.ruleset.policies.values.asSequence()
                    .filter(manager::isAdoptable)
                    .map { it.name }
                    .sorted()
                    .toList(),
        )
    }
}

/** Canonical blockers only. Idle-unit and automation reminders are client
 * conveniences; these choices mutate state or consume a one-shot grant. */
internal object AuthoritativeTurnReadiness {
    fun pendingActions(civilization: Civilization) = buildList {
        if (civilization.cities.any {
                !it.isPuppet && it.cityConstructions.currentConstructionName().isEmpty()
            }) add(PendingEndTurnAction.PickConstruction)
        if (civilization.shouldOpenTechPicker()) add(PendingEndTurnAction.PickTechnology)
        if (civilization.policies.shouldShowPolicyPicker()) add(PendingEndTurnAction.PickPolicy)
        if (civilization.gameInfo.isEspionageEnabled()
            && civilization.espionageManager.shouldShowMoveSpies()
        ) add(PendingEndTurnAction.MoveSpies)
        when {
            civilization.religionManager.religionState == ReligionState.FoundingReligion ->
                add(PendingEndTurnAction.FoundReligion)
            civilization.religionManager.religionState == ReligionState.EnhancingReligion ->
                add(PendingEndTurnAction.EnhanceReligion)
            civilization.religionManager.hasFreeBeliefs() ->
                add(PendingEndTurnAction.ReformReligion)
            civilization.religionManager.canFoundOrExpandPantheon() ->
                add(PendingEndTurnAction.FoundOrExpandPantheon)
        }
        if (civilization.mayVoteForDiplomaticVictory()) add(PendingEndTurnAction.CastDiplomaticVote)
    }
}

@Serializable
enum class PendingEndTurnAction(val wireName: String) {
    @SerialName("pick_construction") PickConstruction("pick_construction"),
    @SerialName("pick_technology") PickTechnology("pick_technology"),
    @SerialName("pick_policy") PickPolicy("pick_policy"),
    @SerialName("move_spies") MoveSpies("move_spies"),
    @SerialName("found_or_expand_pantheon") FoundOrExpandPantheon("found_or_expand_pantheon"),
    @SerialName("found_religion") FoundReligion("found_religion"),
    @SerialName("enhance_religion") EnhanceReligion("enhance_religion"),
    @SerialName("reform_religion") ReformReligion("reform_religion"),
    @SerialName("cast_diplomatic_vote") CastDiplomaticVote("cast_diplomatic_vote"),
}
