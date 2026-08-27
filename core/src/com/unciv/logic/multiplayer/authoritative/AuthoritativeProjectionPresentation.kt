package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.city.CityFlags
import com.unciv.models.stats.Stats

/**
 * Shapes a [SpectatorProjection]'s revealed world into the feed
 * `AuthoritativeProjectionWorldMap` renders, so spectators watch the match on
 * the game's real hex renderer with a minimap - same as players, minus every
 * private family (the adapter leaves them empty).
 */
fun SpectatorProjection.toPlayerShapedProjection(): PlayerProjection = PlayerProjection(
    civilizationId = Constants.spectator,
    turn = turn,
    currentPlayerCivilizationId = currentPlayerCivilizationId,
    isCurrentTurn = false,
    victory = victory,
    pendingTurnActions = emptyList(),
    research = ProjectedResearch(
        currentTechnology = null,
        researchedTechnologies = emptyList(),
        queue = emptyList(),
        queueEntries = emptyList(),
        overflowScience = 0,
        selectableTargets = emptyList(),
        appendableTargets = emptyList(),
        freeTechnologyChoices = emptyList(),
        completionPrompts = emptyList(),
    ),
    policies = ProjectedPolicies(
        storedCulture = 0,
        cultureNeededForNextPolicy = 0,
        freePolicies = 0,
        adoptedPolicies = emptyList(),
        selectablePolicies = emptyList(),
    ),
    gold = 0,
    knownCivilizations = majorCivilizations.map { it.civilizationId }.sorted(),
    ownCities = emptyList(),
    ownUnits = emptyList(),
    exploredTiles = mapTiles,
    visibleForeignUnits = mapUnits.map {
        ProjectedUnit(
            id = it.id,
            civilizationId = it.civilizationId,
            name = it.name,
            x = it.x,
            y = it.y,
            health = 100,
            currentMovement = null,
        )
    },
    visibleForeignCities = mapCities,
)

/**
 * Fills a civilization that deliberately exists outside a [GameInfo] with the
 * server-projected research and policy state, so the classic tech-tree and
 * policy screens render exactly what the worker owns.
 *
 * This is a presentation cache and nothing else: it is rebuilt from each
 * accepted projection before a picker opens, it is never uploaded, and every
 * mutation still travels through typed commands. Single-player never calls
 * this - its civilizations hold their own canonical state.
 */
fun Civilization.applyProjectionPresentation(projection: PlayerProjection) {
    val research = projection.research
    tech.techsResearched.clear()
    tech.techsResearched.addAll(research.researchedTechnologies)
    tech.techsToResearch.clear()
    tech.techsToResearch.addAll(research.queue)
    tech.techsInProgress.clear()
    val current = research.currentTechnology
    if (current != null) {
        tech.techsInProgress[current] = research.queueEntries
            .firstOrNull { it.technologyName == current }?.storedScience ?: 0
    }
    // The count is only ever rendered as "a free tech is available"; the
    // authoritative choices are the projected freeTechnologyChoices.
    tech.freeTechs = research.freeTechnologyChoices.size
    // Rebuilds researchedTechnologies, the era, and the transient uniques.
    tech.setTransients(this)

    val policiesState = projection.policies
    val adopted = policiesState.adoptedPolicies.toSet()
    // Branch-completion policies are adopted without counting toward culture
    // cost; derive that static ruleset fact locally.
    val completionsAdopted = ruleset.policyBranches.values
        .count { branch -> branch.policies.last().name in adopted }
    policies.setPresentationState(
        adopted,
        storedCulture = policiesState.storedCulture,
        freePolicies = policiesState.freePolicies,
        numberOfAdoptedPolicies = (adopted.size - completionsAdopted).coerceAtLeast(0),
    )
    policies.setTransients(this)

    // Per-city yields and growth: the classic city screen renders these
    // figures from the projection instead of recomputing them without a game.
    for (projectedCity in projection.ownCities) {
        val city = cities.firstOrNull { it.id == projectedCity.id } ?: continue
        projectedCity.stats?.let { stats ->
            city.cityStats.currentCityStats = Stats(
                food = stats.food.toFloat(),
                production = stats.production.toFloat(),
                gold = stats.gold.toFloat(),
                science = stats.science.toFloat(),
                culture = stats.culture.toFloat(),
                faith = stats.faith.toFloat(),
                happiness = stats.happiness.toFloat(),
            )
        }
        projectedCity.growth?.let { growth ->
            city.population.foodStored = growth.foodStored
        }
        city.expansion.cultureStored = projectedCity.cultureStored
        if (projectedCity.resistanceTurns > 0)
            city.flagsCountdown[CityFlags.Resistance.name] = projectedCity.resistanceTurns
        if (projectedCity.wltkTurns > 0)
            city.flagsCountdown[CityFlags.WeLoveTheKing.name] = projectedCity.wltkTurns
        city.demandedResource = projectedCity.demandedResource.orEmpty()
        // Built buildings drive the buildings lists and great-person points;
        // adding them through the canonical path keeps transients consistent.
        val builtNow = projectedCity.builtBuildings.toSet()
        val missing = builtNow - city.cityConstructions.builtBuildings
        if (missing.isNotEmpty()) {
            city.cityConstructions.builtBuildings.addAll(missing)
            city.cityConstructions.setTransients()
        }
    }
}

/**
 * Populates the detached civilizations' [com.unciv.logic.civilization.diplomacy.DiplomacyManager]s
 * from the projected relationship facts, so the literal classic
 * DiplomacyScreen renders exactly what the server disclosed: war/peace
 * status, the partner's labelled modifier breakdown (which also reproduces
 * opinion and relationship band through the same canonical math), demand
 * promises with remaining turns, and friendship/denunciation state
 * reconstructed from the projected capability flags.
 *
 * Presentation-only; never call on civilizations inside a real game.
 */
fun Civilization.populateDiplomacyPresentation(
    projection: PlayerProjection,
    findCivilization: (String) -> Civilization?,
) {
    fun ensurePair(other: Civilization): Pair<DiplomacyManager, DiplomacyManager> {
        val ours = diplomacy.getOrPut(other.civID) { DiplomacyManager(this, other) }
        val theirs = other.diplomacy.getOrPut(civID) { DiplomacyManager(other, this) }
        return ours to theirs
    }

    for (partner in projection.diplomacyPartners) {
        val other = findCivilization(partner.civilizationId) ?: continue
        val (ours, theirs) = ensurePair(other)
        val atWar = partner.peaceTreatyCooldownTurns != null
        ours.diplomaticStatus =
            if (atWar) DiplomaticStatus.War else DiplomaticStatus.Peace
        theirs.diplomaticStatus = ours.diplomaticStatus

        theirs.diplomaticModifiers.clear()
        for (modifier in partner.modifiersTowardUs)
            theirs.diplomaticModifiers[modifier.label] = modifier.amount.toFloat()

        // Reconstruct the display flags the classic screen gates on. With no
        // cross-player popup alerts in a projection, "cannot offer" means the
        // friendship/denunciation flag is already set on the wire.
        if (!atWar && !partner.canOfferFriendship && !partner.canDenounce) {
            ours.flagsCountdown[DiplomacyFlags.DeclarationOfFriendship.name] = 30
            theirs.flagsCountdown[DiplomacyFlags.DeclarationOfFriendship.name] = 30
        }
        if (!atWar && !partner.canDenounce && partner.canOfferFriendship)
            theirs.flagsCountdown[DiplomacyFlags.Denunciation.name] = 30

        for (promise in partner.promisesTheyMadeUs)
            theirs.flagsCountdown[Demand.valueOf(promise.name).agreedToDemand.name] =
                requireNotNull(partner.theyPromiseTurns[promise])
        for (promise in partner.promisesWeMadeThem)
            ours.flagsCountdown[Demand.valueOf(promise.name).agreedToDemand.name] =
                requireNotNull(partner.wePromiseTurns[promise])
    }
}
