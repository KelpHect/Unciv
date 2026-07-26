package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.managers.ImprovementFunctions
import com.unciv.logic.map.MapPathing
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import java.util.ArrayDeque

/** Bounded exact worker and route choices derived from canonical unit state. */
internal object UnitOrderProjection {
    private const val MAX_IMPROVEMENT_CHOICES = 256
    private const val MAX_ROAD_DESTINATIONS = 10_000

    fun improvementChoices(unit: MapUnit): List<ProjectedImprovementOrderChoice> {
        val tile = unit.currentTile
        if (tile.isMarkedForCreatesOneImprovement()) return emptyList()
        val choices = mutableListOf<ProjectedImprovementOrderChoice>()
        if (tile.improvementInProgress != null)
            choices += ProjectedImprovementOrderChoice(null, null)
        if (!unit.hasMovement() || tile.isCityCenter()) return choices

        val improvements = unit.civ.gameInfo.ruleset.tileImprovements.values
            .asSequence()
            .filter { improvement ->
                improvement.name != Constants.cancelImprovementOrder &&
                    improvement.name != tile.improvementInProgress &&
                    canStart(unit, improvement.name)
            }
            .sortedBy { it.name }
            .toList()
        for (improvement in improvements) {
            choices += ProjectedImprovementOrderChoice(improvement.name, null)
            if (!improvement.name.startsWith(Constants.remove)) continue
            val removedFeature = improvement.name.removePrefix(Constants.remove)
            if (removedFeature !in tile.terrainFeatures) continue
            val futureTile = tile.clone(addUnits = false)
            futureTile.setTerrainFeatures(tile.terrainFeatures - removedFeature)
            val futureContext = GameContext(unit.civ, unit = unit, tile = futureTile)
            for (queued in improvements) {
                if (queued.name.startsWith(Constants.remove) ||
                    !unit.canBuildImprovement(queued) ||
                    !futureTile.improvementFunctions.canBuildImprovement(queued, futureContext)
                ) continue
                choices += ProjectedImprovementOrderChoice(improvement.name, queued.name)
            }
        }
        return choices.distinct().sortedWith(
            compareBy<ProjectedImprovementOrderChoice> { it.improvementName.orEmpty() }
                .thenBy { it.queuedImprovementName.orEmpty() },
        ).take(MAX_IMPROVEMENT_CHOICES)
    }

    fun roadDestinations(unit: MapUnit): List<ProjectedMovementDestination> {
        if (!canBuildRoad(unit)) return emptyList()
        val start = unit.currentTile
        val visited = linkedSetOf(start)
        val pending = ArrayDeque<Tile>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            val tile = pending.removeFirst()
            for (neighbor in tile.neighbors) {
                if (neighbor in visited || !MapPathing.isValidRoadPathTile(unit.civ, neighbor))
                    continue
                visited += neighbor
                pending += neighbor
            }
        }
        return visited.asSequence()
            .filter { it != start }
            .map { ProjectedMovementDestination(it.position.x, it.position.y) }
            .sortedWith(compareBy<ProjectedMovementDestination> { it.x }.thenBy { it.y })
            .take(MAX_ROAD_DESTINATIONS)
            .toList()
    }

    fun canBuildRoad(unit: MapUnit): Boolean {
        if (unit.isEmbarked()) return false
        val bestRoad = unit.civ.tech.getBestRoadAvailable()
        if (bestRoad == RoadStatus.None) return false
        var allowed = false
        unit.forEachMatchingUnique(UniqueType.BuildImprovements) { unique ->
            if (unique.params[0] == "Land" || unique.params[0] in Constants.all ||
                (unique.params[0] == "Road" &&
                    bestRoad in setOf(RoadStatus.Road, RoadStatus.Railroad)) ||
                (unique.params[0] == "Railroad" && bestRoad == RoadStatus.Railroad)
            ) allowed = true
        }
        return allowed
    }

    private fun canStart(unit: MapUnit, improvementName: String): Boolean {
        val tile = unit.currentTile
        val improvement = unit.civ.gameInfo.ruleset.tileImprovements[improvementName]
            ?: return false
        val context = GameContext(unit.civ, unit = unit, tile = tile)
        if (improvement.name == Constants.repair) {
            if (!unit.cache.hasUniqueToBuildImprovements || unit.isEmbarked() ||
                !tile.isPillaged() || tile.isEnemyTerritory(unit.civ)
            ) return false
            val repair = tile.getImprovementToRepair() ?: return false
            return ImprovementFunctions.getImprovementBuildingProblems(repair, context)
                .none { it == ImprovementBuildingProblem.OutsideBorders }
        }
        return improvement.turnsToBuild != -1 && unit.canBuildImprovement(improvement) &&
            tile.improvementFunctions.canBuildImprovement(improvement, context)
    }
}
