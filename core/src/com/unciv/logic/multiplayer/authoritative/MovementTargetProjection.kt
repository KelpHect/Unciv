package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.map.mapunit.MapUnit

/** Derives owner-only movement choices, including durable escort constraints. */
internal object MovementTargetProjection {
    fun exactDestinations(unit: MapUnit, enabled: Boolean): List<ProjectedMovementDestination> {
        if (!enabled || !unit.hasMovement()) return emptyList()
        val primary = ordinaryExactDestinations(unit)
        val escortId = unit.movementEscortUnitId ?: return primary
        val escort = unit.civ.units.getUnitById(escortId) ?: return emptyList()
        if (escort.getTile() != unit.getTile() || !escort.hasMovement()) return emptyList()
        val escortDestinations = ordinaryExactDestinations(escort).toHashSet()
        return primary.filter { it in escortDestinations }
    }

    fun swapDestinations(unit: MapUnit, enabled: Boolean): List<ProjectedMovementDestination> {
        if (!enabled || !unit.hasMovement() || unit.movementEscortUnitId != null) return emptyList()
        return unit.movement.getUnitSwappableTiles()
            .filter { it in unit.civ.viewableTiles }
            .map { ProjectedMovementDestination(it.position.x, it.position.y) }
            .distinct()
            .sortedWith(coordinateOrder)
            .toList()
    }

    fun towardDestinations(unit: MapUnit, enabled: Boolean): List<ProjectedMovementDestination> {
        if (!enabled || !unit.hasMovement()) return emptyList()
        return unit.civ.gameInfo.tileMap.values.asSequence()
            .filter { tile ->
                tile != unit.getTile() && unit.civ.hasExplored(tile) &&
                    unit.movement.canReach(tile)
            }
            .map { ProjectedMovementDestination(it.position.x, it.position.y) }
            .distinct()
            .sortedWith(coordinateOrder)
            .take(MAX_TOWARD_DESTINATIONS)
            .toList()
    }

    private fun ordinaryExactDestinations(unit: MapUnit) =
        unit.movement.getDistanceToTiles().keys.asSequence()
            .filter { tile ->
                tile != unit.getTile() && when {
                    tile in unit.civ.viewableTiles -> unit.movement.canMoveTo(tile)
                    else -> unit.movement.isUnknownTileWeShouldAssumeToBePassable(tile) &&
                        !unit.baseUnit.movesLikeAirUnits
                }
            }
            .map { ProjectedMovementDestination(it.position.x, it.position.y) }
            .distinct()
            .sortedWith(coordinateOrder)
            .toList()

    private val coordinateOrder =
        compareBy<ProjectedMovementDestination> { it.x }.thenBy { it.y }

    private const val MAX_TOWARD_DESTINATIONS = 10_000
}
