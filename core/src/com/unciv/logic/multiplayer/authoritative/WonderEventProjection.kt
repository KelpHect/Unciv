package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import kotlinx.serialization.Serializable

@Serializable
data class ProjectedWonderEvent(
    val completionTurn: Int,
    val wonderName: String,
    val effectSummary: String,
    val builderCivilizationId: String? = null,
    val cityId: String? = null,
    val cityName: String? = null,
    val x: Int? = null,
    val y: Int? = null,
)

/** Applies the information boundary to canonical world-wonder events. */
internal object WonderEventProjection {
    fun build(game: GameInfo, actor: Civilization): List<ProjectedWonderEvent> =
        game.wonderCompletionEvents.asSequence()
            .mapNotNull { event ->
                val wonder = game.ruleset.buildings[event.wonderName]
                    ?.takeIf { it.isWonder }
                    ?: return@mapNotNull null
                val builder = game.civilizations.singleOrNull {
                    it.civID == event.builderCivilizationId
                }
                val builderIsKnown = builder != null && (builder == actor || actor.knows(builder))
                val locationIsKnown = game.tileMap.getIfTileExistsOrNull(event.x, event.y)
                    ?.isExplored(actor) == true
                ProjectedWonderEvent(
                    completionTurn = event.turn,
                    wonderName = wonder.name,
                    effectSummary = wonder.getShortDescription(),
                    builderCivilizationId = event.builderCivilizationId.takeIf { builderIsKnown },
                    cityId = event.cityId.takeIf { locationIsKnown },
                    cityName = event.cityName.takeIf { locationIsKnown },
                    x = event.x.takeIf { locationIsKnown },
                    y = event.y.takeIf { locationIsKnown },
                )
            }
            .sortedWith(compareBy<ProjectedWonderEvent> { it.completionTurn }
                .thenBy { it.wonderName })
            .toList()
}
