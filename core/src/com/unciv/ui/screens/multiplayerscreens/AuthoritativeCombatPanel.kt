package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCombatController
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedCombatPreview
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

/** Renders only combat targets and previews supplied by the player projection. */
internal class AuthoritativeCombatPanel(
    private val projection: PlayerProjection,
    private val selectedUnitId: Int?,
    private val controller: AuthoritativeCombatController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)
        val unit = projection.ownUnits.singleOrNull { it.id == selectedUnitId }
        if (unit != null) {
            for (target in unit.attackTargets) {
                add(actionButton(
                    "Attack ${target.x},${target.y} ${target.preview.summary()}",
                ) { controller.attack(unit.id, target.x, target.y) }).left().row()
            }
            for (target in unit.nuclearTargetCandidates) {
                add(actionButton(
                    "Nuclear strike ${target.x},${target.y} (radius ${target.blastRadius})",
                ) {
                    controller.launchNuclearStrike(unit.id, target.x, target.y)
                }).left().row()
            }
            for (target in unit.airSweepTargets) {
                add(actionButton(
                    "Air sweep ${target.x},${target.y} " +
                        "(strength ${target.attackerBaseStrength})",
                ) { controller.airSweep(unit.id, target.x, target.y) }).left().row()
            }
        }
        for (city in projection.ownCities) {
            for (target in city.bombardTargets) {
                add(actionButton(
                    "${city.name} bombard ${target.x},${target.y} ${target.preview.summary()}",
                ) { controller.bombard(city.id, target.x, target.y) }).left().row()
            }
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative combat", operation) }
    }

    private fun ProjectedCombatPreview.summary(): String =
        "(strength $attackerEffectiveStrength vs $defenderEffectiveStrength, " +
            "health $attackerHealth/$attackerMaxHealth vs " +
            "$defenderHealth/$defenderMaxHealth)"
}
