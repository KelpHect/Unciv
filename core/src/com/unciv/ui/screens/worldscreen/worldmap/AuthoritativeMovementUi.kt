package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.ui.screens.worldscreen.WorldScreen

internal enum class AuthoritativeMovementIntent { ExactMove, MoveToward, Unavailable }

/** Read-only adapter from the synchronized API-v3 projection to map input/rendering. */
internal object AuthoritativeMovementUi {
    fun isOpen(worldScreen: WorldScreen): Boolean {
        if (!worldScreen.gameInfo.gameParameters.isOnlineMultiplayer) return false
        return worldScreen.game.onlineMultiplayer.authoritativeSession
            ?.isGameOpen(worldScreen.gameInfo.gameId) == true
    }

    fun projection(worldScreen: WorldScreen): PlayerProjection? {
        if (!isOpen(worldScreen)) return null
        return worldScreen.game.onlineMultiplayer.authoritativeSession
            ?.cachedProjectionIfOpen(worldScreen.gameInfo.gameId)
    }

    fun movementIntent(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): AuthoritativeMovementIntent? {
        if (!isOpen(worldScreen)) return null
        val projection = projection(worldScreen) ?: return AuthoritativeMovementIntent.Unavailable
        val projectedUnit = projection.ownUnits.singleOrNull { it.id == unit.id }
            ?: return AuthoritativeMovementIntent.Unavailable
        if (!projection.isCurrentTurn || projectedUnit.currentMovement <= 0f)
            return AuthoritativeMovementIntent.Unavailable
        val x = tile.position.x
        val y = tile.position.y
        return when {
            projectedUnit.moveDestinations.any { it.x == x && it.y == y } ->
                AuthoritativeMovementIntent.ExactMove
            projection.exploredTiles.any { it.x == x && it.y == y } ->
                AuthoritativeMovementIntent.MoveToward
            else -> AuthoritativeMovementIntent.Unavailable
        }
    }

    fun canSwap(worldScreen: WorldScreen, unit: MapUnit, tile: Tile): Boolean? {
        if (!isOpen(worldScreen)) return null
        val projection = projection(worldScreen) ?: return false
        val projectedUnit = projection.ownUnits.singleOrNull { it.id == unit.id } ?: return false
        return projectedUnit.swapDestinations.any {
            it.x == tile.position.x && it.y == tile.position.y
        }
    }
}
