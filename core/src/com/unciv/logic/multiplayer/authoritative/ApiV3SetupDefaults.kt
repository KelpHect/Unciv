package com.unciv.logic.multiplayer.authoritative

import com.unciv.models.ruleset.Ruleset

/**
 * Builds the server-validated initial setup for a new V3 lobby.
 *
 * Hidden victory definitions are engine implementation details such as the
 * score-at-turn-limit result. They must never be submitted as player-selected
 * victory conditions.
 */
fun createDefaultApiV3GameSetup(
    ruleset: Ruleset,
    ownerCivilizationId: String,
    humanSlots: Int,
): ApiV3GameSetup {
    val playableMajors = ruleset.nations.values.count { it.isMajorCiv }
    require(playableMajors >= maxOf(2, humanSlots)) {
        "The selected ruleset does not contain enough playable major factions."
    }
    val majorCivilizations = maxOf(4, humanSlots).coerceAtMost(playableMajors)
    val selectableVictories = ruleset.victories.values
        .asSequence()
        .filterNot { it.hiddenInVictoryScreen }
        .map { it.name }
        .sorted()
        .toList()
    require(selectableVictories.isNotEmpty()) {
        "The selected ruleset does not contain a selectable victory condition."
    }
    return ApiV3GameSetup(
        ownerCivilizationId = ownerCivilizationId,
        difficulty = ruleset.difficulties.keys.preferred("Prince"),
        speed = ruleset.speeds.keys.preferred("Standard"),
        startingEra = ruleset.eras.keys.preferred("Ancient era"),
        victoryTypes = selectableVictories,
        majorCivilizations = majorCivilizations,
        cityStates = minOf(6, ruleset.nations.values.count { it.isCityState }),
        maxTurns = 500,
        mapType = ApiV3GeneratedMapType.Pangaea,
        mapShape = ApiV3GeneratedMapShape.Hexagonal,
        mapSize = ApiV3GeneratedMapSize.Medium,
        mapResources = ApiV3MapResourceDensity.Default,
        barbarians = ApiV3BarbarianMode.Normal,
        oneCityChallenge = false,
        nuclearWeaponsEnabled = true,
        espionageEnabled = true,
        noStartBias = false,
        shufflePlayerOrder = false,
        noCityRazing = false,
        worldWrap = false,
        strategicBalance = false,
        legendaryStart = false,
        noRuins = false,
        noNaturalWonders = false,
    )
}

private fun Collection<String>.preferred(name: String): String =
    firstOrNull { it == name } ?: firstOrNull()
    ?: error("The selected server ruleset has no $name-compatible setup values.")
