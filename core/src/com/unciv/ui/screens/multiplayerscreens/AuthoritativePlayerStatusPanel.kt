package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.ui.components.extensions.toLabel

/** Read-only player identity and public status from the server projection. */
internal class AuthoritativePlayerStatusPanel(
    private val projection: PlayerProjection,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        for (row in AuthoritativePlayerStatusPresentation.rows(projection)) {
            add(row.toLabel()).left().row()
        }
    }
}

/** Deterministic text rows for projection-only player status presentation. */
object AuthoritativePlayerStatusPresentation {
    fun rows(projection: PlayerProjection): List<String> = buildList {
        add("Civilization: ${projection.civilizationId}")
        add("Current player: ${projection.currentPlayerCivilizationId}")
        add("Treasury: ${projection.gold} gold")
        if (projection.knownCivilizations.isNotEmpty()) {
            add("Known civilizations: ${projection.knownCivilizations.joinToString()}")
        }
        if (projection.policies.adoptedPolicies.isNotEmpty()) {
            add("Adopted policies: ${projection.policies.adoptedPolicies.joinToString()}")
        }
    }
}
