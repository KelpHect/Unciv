package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.screens.victoryscreen.RankingType
import kotlinx.serialization.Serializable

/**
 * Full no-fog-of-war projection for match replay viewing.
 * All civilizations' data is visible — no fog-of-war filtering.
 */
@Serializable
data class ReplayProjection(
    val protocolVersion: Int = 2,
    val turn: Int,
    val currentPlayerCivilizationId: String,
    val victory: ProjectedVictory? = null,
    val majorCivilizations: List<ReplayCivilization>,
    val map: ReplayMap,
)

@Serializable
data class ReplayCivilization(
    val civilizationId: String,
    val displayName: String,
    val humanControlled: Boolean,
    val defeated: Boolean,
    val gold: Int,
    val cityCount: Int,
    val unitCount: Int,
    val population: Int,
    val technologiesResearched: Int,
    val policiesAdopted: Int,
    val statsHistory: List<ReplayStatsEntry>,
)

@Serializable
data class ReplayStatsEntry(
    val turn: Int,
    val score: Int,
    val population: Int,
    val growth: Int,
    val production: Int,
    val gold: Int,
    val territory: Int,
    val force: Int,
    val happiness: Int,
    val technologies: Int,
    val culture: Int,
)

@Serializable
data class ReplayMap(
    val worldWrap: Boolean,
    val tiles: List<ReplayTile>,
)

@Serializable
data class ReplayTile(
    val x: Int,
    val y: Int,
    val baseTerrain: String,
    val terrainFeatures: List<String>,
    val naturalWonderName: String? = null,
    val ownerCivilizationId: String? = null,
)

object ReplayProjectionBuilder {
    fun build(game: GameInfo): ReplayProjection {
        val civs = game.civilizations.asSequence()
            .filter { it.isMajorCiv() }
            .sortedBy { it.civID }
            .map { civ -> buildCivilization(civ) }
            .toList()

        return ReplayProjection(
            turn = game.turns,
            currentPlayerCivilizationId = game.currentPlayer,
            victory = game.victoryData?.let {
                ProjectedVictory(it.winningCiv, it.victoryType, it.victoryTurn)
            },
            majorCivilizations = civs,
            map = buildMap(game),
        )
    }

    private fun buildCivilization(civ: Civilization): ReplayCivilization {
        val statsEntries = civ.statsHistory.entries.sortedBy { it.key }.map { (turn, statMap) ->
            ReplayStatsEntry(
                turn = turn,
                score = statMap[RankingType.Score] ?: 0,
                population = statMap[RankingType.Population] ?: 0,
                growth = statMap[RankingType.Growth] ?: 0,
                production = statMap[RankingType.Production] ?: 0,
                gold = statMap[RankingType.Gold] ?: 0,
                territory = statMap[RankingType.Territory] ?: 0,
                force = statMap[RankingType.Force] ?: 0,
                happiness = statMap[RankingType.Happiness] ?: 0,
                technologies = statMap[RankingType.Technologies] ?: 0,
                culture = statMap[RankingType.Culture] ?: 0,
            )
        }
        return ReplayCivilization(
            civilizationId = civ.civID,
            displayName = civ.civName,
            humanControlled = civ.isHuman(),
            defeated = civ.isDefeated(),
            gold = civ.gold,
            cityCount = civ.cities.size,
            unitCount = civ.units.getCivUnitsSize(),
            population = civ.cities.sumOf { it.population.population },
            technologiesResearched = civ.tech.researchedTechnologies.size,
            policiesAdopted = civ.policies.adoptedPolicies.size,
            statsHistory = statsEntries,
        )
    }

    private fun buildMap(game: GameInfo): ReplayMap {
        val tileMap = game.tileMap
        val tiles = tileMap.tileList.map { tile ->
            ReplayTile(
                x = tile.position.x,
                y = tile.position.y,
                baseTerrain = tile.baseTerrain,
                terrainFeatures = tile.terrainFeatures,
                naturalWonderName = tile.naturalWonder,
                ownerCivilizationId = tile.getOwner()?.civID,
            )
        }
        return ReplayMap(
            worldWrap = tileMap.mapParameters.worldWrap,
            tiles = tiles,
        )
    }
}
