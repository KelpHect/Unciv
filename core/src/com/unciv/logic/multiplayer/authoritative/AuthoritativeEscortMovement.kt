package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/** Canonical validation and lifecycle for API-v3 escort movement metadata. */
internal object AuthoritativeEscortMovement {
    fun resolve(
        actor: Civilization,
        unit: MapUnit,
        escortUnitId: Int?,
        destination: Tile,
    ): MapUnit? {
        val escort = escortUnitId?.let { id ->
            require(id != unit.id) { "Escort unit must differ from the moving unit" }
            actor.units.getUnitById(id)
                ?: error("Escort unit is not controlled by the authenticated actor")
        } ?: return null
        require(escort.getTile() == unit.getTile()) { "Escort units are not co-located" }
        require(!unit.baseUnit.movesLikeAirUnits && !escort.baseUnit.movesLikeAirUnits) {
            "Air units cannot form an escort pair"
        }
        require(unit.isCivilian() != escort.isCivilian()) {
            "Escort pair must contain one civilian and one military unit"
        }
        require(escort.movement.canReach(destination)) {
            "Destination is not reachable by the escort unit"
        }
        return escort
    }

    fun requireExactDestination(escort: MapUnit, destination: Tile) {
        require(escort.movement.canReachInCurrentTurn(destination)) {
            "Destination is not reachable by the escort unit this turn"
        }
        require(escort.movement.canMoveTo(destination)) {
            "Escort unit cannot enter the destination"
        }
    }

    fun clearOrderReferences(actor: Civilization, unit: MapUnit) {
        unit.movementEscortUnitId = null
        actor.units.getCivUnits()
            .filter { it.movementEscortUnitId == unit.id }
            .forEach {
                it.action = null
                it.movementEscortUnitId = null
            }
    }

    fun moveToward(unit: MapUnit, escort: MapUnit?, destination: Tile): Tile {
        val origin = unit.getTile()
        if (escort != null) unit.startEscorting()
        val reached = unit.movement.headTowards(destination)
        if (escort != null) {
            check(escort.getTile() == reached) { "Escort movement diverged from its paired unit" }
            unit.stopEscorting()
        }
        check(reached != origin) { "Movement order made no canonical progress" }
        unit.action = if (reached == destination) null
            else "moveTo ${destination.position.x},${destination.position.y}"
        unit.movementEscortUnitId = if (unit.action == null) null else escort?.id
        return reached
    }
}
