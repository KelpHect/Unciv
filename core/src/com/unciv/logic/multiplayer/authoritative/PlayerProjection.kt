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
    val pendingCityDispositions: List<ProjectedCityDisposition> = emptyList(),
    val diplomaticVoteCandidates: List<String> = emptyList(),
    val selectableGreatPeople: List<String> = emptyList(),
    val religionChoice: ProjectedReligionChoice? = null,
    val tradePartners: List<ProjectedTradePartner> = emptyList(),
    val pendingTradeRequests: List<ProjectedTradeRequest> = emptyList(),
    val diplomacyPartners: List<ProjectedDiplomacyPartner> = emptyList(),
    val diplomacyPrompts: List<ProjectedDiplomacyPrompt> = emptyList(),
    val cityStatePartners: List<ProjectedCityStatePartner> = emptyList(),
    val spies: List<ProjectedSpy> = emptyList(),
    val eventPrompts: List<ProjectedEventPrompt> = emptyList(),
) {
    companion object {
        const val CURRENT_PROJECTION_VERSION = 43
    }
}

@Serializable
data class ProjectedEventPrompt(
    val promptId: String,
    val eventName: String,
    val unitId: Int?,
    val text: String,
    val choices: List<ProjectedEventChoice>,
)

@Serializable
data class ProjectedEventChoice(val choiceId: String, val text: String)

@Serializable
data class ProjectedSpy(
    val name: String,
    val rank: Int,
    val cityId: String?,
    val civilizationId: String?,
    val action: ProjectedSpyAction,
    val turnsRemaining: Int,
    val availableCityIds: List<String>,
    val canMoveToHideout: Boolean,
    val canStageCoup: Boolean,
    val canCancelCoup: Boolean,
)

@Serializable
enum class ProjectedSpyAction {
    @SerialName("none") None,
    @SerialName("moving") Moving,
    @SerialName("establish_network") EstablishNetwork,
    @SerialName("surveillance") Surveillance,
    @SerialName("stealing_tech") StealingTech,
    @SerialName("rigging_elections") RiggingElections,
    @SerialName("coup") Coup,
    @SerialName("counter_intelligence") CounterIntelligence,
    @SerialName("dead") Dead,
}

@Serializable
data class ProjectedCityStatePartner(
    val civilizationId: String,
    val availableGoldGifts: List<Int>,
    val canPledgeProtection: Boolean,
    val canRevokeProtection: Boolean,
    val tributeGoldAmount: Int?,
    val canDemandWorker: Boolean,
    val improvementGifts: List<ProjectedCityStateImprovementGift>,
    val canNegotiatePeace: Boolean,
    val canDeclareWar: Boolean,
    val diplomaticMarriageCost: Int?,
)

@Serializable
data class ProjectedCityStateImprovementGift(
    val x: Int,
    val y: Int,
    val improvementName: String,
    val goldCost: Int,
)

@Serializable
data class ProjectedDiplomacyPartner(
    val civilizationId: String,
    val canDeclareWar: Boolean,
    val canDenounce: Boolean,
    val canOfferFriendship: Boolean,
    val availableDemands: List<DiplomaticDemand>,
)

@Serializable
data class ProjectedDiplomacyPrompt(
    val promptId: String,
    val requestingCivilizationId: String,
    val type: DiplomacyPromptType,
    val demand: DiplomaticDemand?,
    val cityStateCivilizationId: String? = null,
    val availableCityStateResponses: List<CityStateProtectionResponse> = emptyList(),
)

@Serializable
enum class DiplomacyPromptType {
    @SerialName("friendship") Friendship,
    @SerialName("demand") Demand,
    @SerialName("bullied_protected_minor") BulliedProtectedMinor,
    @SerialName("attacked_protected_minor") AttackedProtectedMinor,
    @SerialName("attacked_ally_minor") AttackedAllyMinor,
}

@Serializable
enum class CityStateProtectionResponse {
    @SerialName("declare_war") DeclareWar,
    @SerialName("condemn") Condemn,
    @SerialName("withdraw_protection") WithdrawProtection,
}

@Serializable
enum class DiplomaticDemand {
    @SerialName("dont_spy_on_us") DontSpyOnUs,
    @SerialName("do_not_spread_religion") DoNotSpreadReligion,
    @SerialName("do_not_settle_near_us") DoNotSettleNearUs,
    @SerialName("do_not_attack_us") DoNotAttackUs,
}

@Serializable
data class ProjectedTradePartner(
    val civilizationId: String,
    val ourAvailableOffers: List<ProjectedTradeOffer>,
    val theirAvailableOffers: List<ProjectedTradeOffer>,
    val hasPendingOutgoingOffer: Boolean,
)

@Serializable
data class ProjectedTradeRequest(val requestId: String, val requestingCivilizationId: String, val trade: ProjectedTrade)

@Serializable
data class ProjectedTrade(val ourOffers: List<ProjectedTradeOffer>, val theirOffers: List<ProjectedTradeOffer>)

@Serializable
data class ProjectedTradeOffer(val name: String, val type: String, val amount: Int, val duration: Int)

@Serializable
data class ProjectedReligionChoice(
    val requiredBeliefTypes: List<ReligiousBeliefType>,
    val availableBeliefs: List<ProjectedReligiousBelief>,
    val availableReligionIcons: List<String>,
    val requiresReligionIdentity: Boolean,
)

@Serializable
data class ProjectedReligiousBelief(val name: String, val type: ReligiousBeliefType)

@Serializable
enum class ReligiousBeliefType {
    @SerialName("pantheon") Pantheon,
    @SerialName("founder") Founder,
    @SerialName("follower") Follower,
    @SerialName("enhancer") Enhancer,
    @SerialName("any") Any,
}

@Serializable
data class ProjectedCityDisposition(
    val cityId: String,
    val cityName: String,
    val availableActions: List<CityDispositionAction>,
)

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
    val isPuppet: Boolean = false,
    val isBeingRazed: Boolean = false,
    val availableGovernanceActions: List<CityGovernanceAction> = emptyList(),
    val bombardTargets: List<ProjectedTargetCoordinate> = emptyList(),
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
    val researchedTechnologies: List<String>,
    val queue: List<String>,
    val queueEntries: List<ProjectedResearchQueueEntry>,
    val overflowScience: Int,
    val selectableTargets: List<String>,
    val appendableTargets: List<String>,
    val freeTechnologyChoices: List<String>,
    val completionPrompts: List<ProjectedResearchCompletion>,
)

@Serializable
data class ProjectedResearchCompletion(
    val promptId: String,
    val technologyName: String,
)

@Serializable
data class ProjectedResearchQueueEntry(
    val technologyName: String,
    val storedScience: Int,
    val cost: Int,
    val estimatedTurns: Int?,
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
    val movementEscortUnitId: Int? = null,
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
    val availableReligiousActions: List<ReligiousUnitAction> = emptyList(),
    val availableGreatPersonActions: List<GreatPersonUnitAction> = emptyList(),
    val canGift: Boolean = false,
    val availableTransformActions: List<ProjectedUnitTransformAction> = emptyList(),
    val availableTriggerActions: List<ProjectedUnitTriggerAction> = emptyList(),
    val moveDestinations: List<ProjectedMovementDestination> = emptyList(),
    val swapDestinations: List<ProjectedMovementDestination> = emptyList(),
    val attackTargets: List<ProjectedAttackTarget> = emptyList(),
    val nuclearTargetCandidates: List<ProjectedTargetCoordinate> = emptyList(),
    val airSweepTargets: List<ProjectedTargetCoordinate> = emptyList(),
)

@Serializable
data class ProjectedMovementDestination(val x: Int, val y: Int)

@Serializable
data class ProjectedTargetCoordinate(val x: Int, val y: Int)

@Serializable
data class ProjectedAttackTarget(
    val x: Int,
    val y: Int,
    val attackFromX: Int,
    val attackFromY: Int,
)

@Serializable
data class ProjectedUnitTransformAction(val actionId: String, val targetUnitName: String)

@Serializable
data class ProjectedUnitTriggerAction(val actionId: String, val title: String)

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
    val improvementName: String? = null,
    val improvementPillaged: Boolean? = null,
    val roadStatus: String? = null,
    val roadPillaged: Boolean? = null,
)

object PlayerProjectionBuilder {
    fun build(game: GameInfo, actor: Civilization): PlayerProjection {
        val canIssueTurnCommands = game.currentPlayer == actor.civID
        val ownUnits = actor.units.getCivUnits()
            .map { unitProjection(it, includePrivateOrders = true, canIssueTurnCommands) }
            .sortedBy { it.id }
            .toList()
        val visibleForeignUnits = game.tileMap.tileList.asSequence()
            .filter { it in actor.viewableTiles }
            .flatMap { it.getUnits() }
            .filter { it.civ != actor }
            .filter { !it.isInvisible(actor) || it.getTile() in actor.viewableInvisibleUnitsTiles }
            .map { unitProjection(it, includePrivateOrders = false, canIssueTurnCommands = false) }
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
            tradePartners = TradeCommandExecutor.availablePartners(actor),
            pendingTradeRequests = TradeCommandExecutor.pendingRequests(actor),
            diplomacyPartners = DiplomacyCommandExecutor.partners(actor),
            diplomacyPrompts = DiplomacyCommandExecutor.prompts(actor),
            cityStatePartners = CityStateCommandExecutor.partners(actor),
            spies = EspionageCommandExecutor.spies(actor),
            eventPrompts = EventChoiceCommandExecutor.prompts(actor),
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
                    isPuppet = it.isPuppet,
                    isBeingRazed = it.isBeingRazed,
                    availableGovernanceActions = CityGovernanceExecutor.availableActions(it),
                    bombardTargets = CombatTargetProjection.bombardTargets(it, canIssueTurnCommands),
                )
            }.sortedBy { it.id },
            ownUnits = ownUnits,
            exploredTiles = game.tileMap.tileList.asSequence()
                .filter { it.isExplored(actor) }
                .map { tile ->
                    val visible = tile in actor.viewableTiles
                    ProjectedTileVisibility(
                        tile.position.x,
                        tile.position.y,
                        visible,
                        improvementName = tile.improvement.takeIf { visible },
                        improvementPillaged = tile.improvementIsPillaged.takeIf { visible },
                        roadStatus = tile.roadStatus.name.takeIf { visible },
                        roadPillaged = tile.roadIsPillaged.takeIf { visible },
                    )
                }
                .sortedWith(compareBy<ProjectedTileVisibility> { it.x }.thenBy { it.y })
                .toList(),
            visibleForeignUnits = visibleForeignUnits,
            pendingCityDispositions = actor.popupAlerts.asSequence()
                .filter { it.type == com.unciv.logic.civilization.AlertType.CityConquered }
                .mapNotNull { alert -> game.getCities().singleOrNull { it.id == alert.value } }
                .map { city -> ProjectedCityDisposition(
                    city.id,
                    city.name,
                    CityDispositionExecutor.availableActions(city, actor),
                ) }
                .sortedBy { it.cityId }
                .toList(),
            diplomaticVoteCandidates = diplomaticVoteCandidates(actor),
            selectableGreatPeople = GreatPersonChoiceExecutor.availableChoices(actor),
            religionChoice = ReligionChoiceExecutor.projection(actor),
        )
    }

    internal fun diplomaticVoteCandidates(actor: Civilization): List<String> =
        if (!actor.mayVoteForDiplomaticVictory()) emptyList()
        else actor.diplomacyFunctions.getKnownCivsSorted(includeCityStates = false)
            .map { it.civID }
            .toList()

    private fun unitProjection(
        unit: MapUnit,
        includePrivateOrders: Boolean,
        canIssueTurnCommands: Boolean,
    ): ProjectedUnit {
        val destination = if (includePrivateOrders && unit.isMoving())
            unit.getMovementDestination().position else null
        val movementEnabled = includePrivateOrders && canIssueTurnCommands
        val moveDestinations = MovementTargetProjection.exactDestinations(unit, movementEnabled)
        val swapDestinations = MovementTargetProjection.swapDestinations(unit, movementEnabled)
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
        movementEscortUnitId = if (destination == null) null else unit.movementEscortUnitId,
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
        availableReligiousActions = if (includePrivateOrders)
            ReligiousUnitActionExecutor.availableActions(unit) else emptyList(),
        availableGreatPersonActions = if (includePrivateOrders)
            GreatPersonUnitActionExecutor.availableActions(unit) else emptyList(),
        canGift = includePrivateOrders && UnitGiftCommandExecutor.canGift(unit),
        availableTransformActions = if (includePrivateOrders)
            UnitTransformCommandExecutor.projectedActions(unit) else emptyList(),
        availableTriggerActions = if (includePrivateOrders)
            UnitTriggerCommandExecutor.projectedActions(unit) else emptyList(),
        moveDestinations = moveDestinations,
        swapDestinations = swapDestinations,
        attackTargets = if (includePrivateOrders)
            CombatTargetProjection.attackTargets(unit, canIssueTurnCommands) else emptyList(),
        nuclearTargetCandidates = if (includePrivateOrders)
            CombatTargetProjection.nuclearTargetCandidates(unit, canIssueTurnCommands) else emptyList(),
        airSweepTargets = if (includePrivateOrders)
            CombatTargetProjection.airSweepTargets(unit, canIssueTurnCommands) else emptyList(),
    )
    }

    private fun unitPosture(unit: MapUnit): UnitPosture? = when {
        unit.isSleepingUntilHealed() -> UnitPosture.SleepUntilHealed
        unit.isSleeping() -> UnitPosture.Sleep
        unit.isFortifyingUntilHealed() -> UnitPosture.FortifyUntilHealed
        unit.isFortified() -> UnitPosture.Fortify
        unit.isGuarding() -> UnitPosture.Guard
        unit.isSetUpForSiege() -> UnitPosture.Setup
        else -> null
    }

    private fun researchProjection(civilization: Civilization): ProjectedResearch {
        val technologies = civilization.gameInfo.ruleset.technologies.values
        val targetPaths = technologies.map { technology ->
            technology to civilization.tech.getRequiredTechsToDestination(technology)
        }.filter { (_, path) -> path.isNotEmpty() }
        val queuedTechnologies = civilization.tech.techsToResearch.toSet()
        return ProjectedResearch(
            currentTechnology = civilization.tech.currentTechnologyName(),
            researchedTechnologies = civilization.tech.techsResearched.sorted(),
            queue = civilization.tech.techsToResearch.toList(),
            queueEntries = civilization.tech.techsToResearch.map { technologyName ->
                ProjectedResearchQueueEntry(
                    technologyName = technologyName,
                    storedScience = civilization.tech.researchOfTech(technologyName),
                    cost = civilization.tech.costOfTech(technologyName),
                    estimatedTurns = civilization.tech.estimatedTurnsToTech(technologyName),
                )
            },
            overflowScience = civilization.tech.getOverflowScience(),
            selectableTargets = targetPaths.asSequence()
                .map { (technology) -> technology.name }
                .sorted()
                .toList(),
            appendableTargets = targetPaths.asSequence()
                .filter { (_, path) -> path.any { it.name !in queuedTechnologies } }
                .map { (technology) -> technology.name }
                .sorted()
                .toList(),
            freeTechnologyChoices = if (civilization.tech.freeTechs == 0) emptyList() else
                technologies.asSequence()
                    .filter { civilization.tech.canBeResearched(it.name) }
                    .map { it.name }
                    .sorted()
                    .toList(),
            completionPrompts = ResearchCompletionCommandExecutor.prompts(civilization),
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
        if (GreatPersonChoiceExecutor.availableChoices(civilization).isNotEmpty()) {
            add(PendingEndTurnAction.PickGreatPerson)
        }
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
    @SerialName("pick_great_person") PickGreatPerson("pick_great_person"),
}
