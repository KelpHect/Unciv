package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import kotlinx.serialization.Serializable

/** Public-summary spectator view. It deliberately contains no map, units,
 * cities, yields, queues, diplomacy, notifications, or RNG state. */
@Serializable
data class SpectatorProjection(
    val protocolVersion: Int = CommandEnvelope.CURRENT_PROTOCOL_VERSION,
    val turn: Int,
    val currentPlayerCivilizationId: String,
    val activePlayerCivilizationIds: List<String> = emptyList(),
    val victory: ProjectedVictory? = null,
    val majorCivilizations: List<SpectatorCivilization>,
) {
    companion object {
        const val CURRENT_PROJECTION_VERSION = 3
    }
}

@Serializable
data class SpectatorCivilization(
    val civilizationId: String,
    val displayName: String,
    val humanControlled: Boolean,
    val defeated: Boolean,
)

object SpectatorProjectionBuilder {
    fun build(game: GameInfo) = SpectatorProjection(
        turn = game.turns,
        currentPlayerCivilizationId = game.currentPlayer,
        activePlayerCivilizationIds = if (game.gameParameters.simultaneousHumanTurns) {
            game.civilizations.asSequence()
                .filter { it.isHuman() && it.isAlive() && !it.isSpectator() }
                .filter { it.civID !in game.playersWhoEndedTurn }
                .map { it.civID }
                .sorted()
                .toList()
        } else listOf(game.currentPlayer),
        victory = game.victoryData?.let {
            ProjectedVictory(it.winningCiv, it.victoryType, it.victoryTurn)
        },
        majorCivilizations = game.civilizations.asSequence()
            .filter { it.isMajorCiv() }
            .sortedBy { it.civID }
            .map {
                SpectatorCivilization(
                    civilizationId = it.civID,
                    displayName = it.civName,
                    humanControlled = it.isHuman(),
                    defeated = it.isDefeated(),
                )
            }
            .toList(),
    )
}
