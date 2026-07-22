package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedAttackTarget
import com.unciv.logic.multiplayer.authoritative.ProjectedBombardTarget
import com.unciv.logic.multiplayer.authoritative.ProjectedCombatPreview
import com.unciv.logic.multiplayer.authoritative.ProjectedAirSweepTarget
import com.unciv.logic.multiplayer.authoritative.ProjectedNuclearTarget
import com.unciv.ui.screens.worldscreen.WorldScreen

internal enum class AuthoritativeCombatAction { Attack, NuclearStrike, AirSweep }

/** Read-only adapter from synchronized combat choices to map presentation. */
internal object AuthoritativeCombatUi {
    fun isOpen(worldScreen: WorldScreen) = AuthoritativeMovementUi.isOpen(worldScreen)

    fun projection(worldScreen: WorldScreen): PlayerProjection? =
        AuthoritativeMovementUi.projection(worldScreen)

    fun attackTarget(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): ProjectedAttackTarget? =
        projection(worldScreen)?.ownUnits
            ?.singleOrNull { it.id == unit.id }
            ?.attackTargets
            ?.singleOrNull { it.x == tile.position.x && it.y == tile.position.y }

    fun bombardTarget(worldScreen: WorldScreen, city: City, tile: Tile): ProjectedBombardTarget? =
        projection(worldScreen)?.ownCities
            ?.singleOrNull { it.id == city.id }
            ?.bombardTargets
            ?.singleOrNull { it.x == tile.position.x && it.y == tile.position.y }

    fun canBombard(worldScreen: WorldScreen, city: City, tile: Tile): Boolean =
        bombardTarget(worldScreen, city, tile) != null

    fun unitPreview(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): ProjectedCombatPreview? =
        attackTarget(worldScreen, unit, tile)?.preview

    fun bombardPreview(worldScreen: WorldScreen, city: City, tile: Tile): ProjectedCombatPreview? =
        bombardTarget(worldScreen, city, tile)?.preview

    fun canNuclearStrike(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): Boolean =
        nuclearTarget(worldScreen, unit, tile) != null

    fun nuclearTarget(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): ProjectedNuclearTarget? =
        projection(worldScreen)?.ownUnits
            ?.singleOrNull { it.id == unit.id }
            ?.nuclearTargetCandidates
            ?.singleOrNull { it.x == tile.position.x && it.y == tile.position.y }

    fun canAirSweep(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): Boolean =
        airSweepTarget(worldScreen, unit, tile) != null

    fun airSweepTarget(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): ProjectedAirSweepTarget? =
        projection(worldScreen)?.ownUnits
            ?.singleOrNull { it.id == unit.id }
            ?.airSweepTargets
            ?.singleOrNull { it.x == tile.position.x && it.y == tile.position.y }

    fun unitAction(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): AuthoritativeCombatAction? {
        val projected = projection(worldScreen)?.ownUnits?.singleOrNull { it.id == unit.id }
            ?: return null
        val x = tile.position.x
        val y = tile.position.y
        return when {
            projected.nuclearTargetCandidates.any { it.x == x && it.y == y } ->
                AuthoritativeCombatAction.NuclearStrike
            unit.isPreparingAirSweep() && projected.airSweepTargets.any { it.x == x && it.y == y } ->
                AuthoritativeCombatAction.AirSweep
            projected.attackTargets.any { it.x == x && it.y == y } ->
                AuthoritativeCombatAction.Attack
            else -> null
        }
    }
}
