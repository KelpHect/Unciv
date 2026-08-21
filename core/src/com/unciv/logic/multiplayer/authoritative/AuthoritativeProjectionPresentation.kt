package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.civilization.Civilization

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
            city.cityStats.currentCityStats = com.unciv.models.stats.Stats(
                food = stats.food.toFloat(),
                production = stats.production.toFloat(),
                gold = stats.gold.toFloat(),
                science = stats.science.toFloat(),
                culture = stats.culture.toFloat(),
                faith = stats.faith.toFloat(),
                happiness = stats.happiness.toFloat(),
            )
        }
    }
}
