package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedWonderEvent
import com.unciv.ui.components.extensions.toLabel

/** Read-only history derived exclusively from the current server projection. */
internal class AuthoritativeHistoryPanel(
    private val projection: PlayerProjection,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        val rows = AuthoritativeHistoryPresentation.rows(projection)
        if (rows.isEmpty()) return@apply
        add("History and public events".toLabel()).left().row()
        for (row in rows) add(row.toLabel()).left().row()
    }
}

/** Deterministic text rows for projection-only history presentation. */
object AuthoritativeHistoryPresentation {
    fun rows(projection: PlayerProjection): List<String> = buildList {
        if (projection.research.researchedTechnologies.isNotEmpty()) {
            add(
                "Researched technologies: " +
                    projection.research.researchedTechnologies.joinToString(),
            )
        }
        addAll(projection.wonderEvents.map(::wonderRow))
    }

    private fun wonderRow(event: ProjectedWonderEvent): String = buildString {
        append("Turn ${event.completionTurn}: ${event.wonderName}")
        append(" — ${event.effectSummary}")
        event.builderCivilizationId?.let { append(" — built by $it") }
        event.cityName?.let { append(" in $it") }
        if (event.x != null && event.y != null) append(" (${event.x},${event.y})")
    }
}
