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
        const val CURRENT_PROJECTION_VERSION = 5
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
)

@Serializable
data class ProjectedTileVisibility(
    val x: Int,
    val y: Int,
    val visible: Boolean,
)

object PlayerProjectionBuilder {
    fun build(game: GameInfo, actor: Civilization): PlayerProjection {
        val ownUnits = actor.units.getCivUnits().map(::unitProjection).sortedBy { it.id }.toList()
        val visibleForeignUnits = game.tileMap.tileList.asSequence()
            .filter { it in actor.viewableTiles }
            .flatMap { it.getUnits() }
            .filter { it.civ != actor }
            .filter { !it.isInvisible(actor) || it.getTile() in actor.viewableInvisibleUnitsTiles }
            .map(::unitProjection)
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

    private fun unitProjection(unit: MapUnit) = ProjectedUnit(
        id = unit.id,
        civilizationId = unit.civ.civID,
        name = unit.name,
        x = unit.getTile().position.x,
        y = unit.getTile().position.y,
        health = unit.health,
        currentMovement = unit.currentMovement,
    )

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
