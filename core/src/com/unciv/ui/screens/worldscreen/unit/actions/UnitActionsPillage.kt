package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.GUI
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.ui.popups.ConfirmPopup
import yairm210.purity.annotations.Readonly

object UnitActionsPillage {

    internal fun getPillageActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        val pillageAction = getPillageAction(unit, tile)
            ?: return emptySequence()
        if (pillageAction.action == null || unit.civ.isAIOrAutoPlaying())
            return sequenceOf(pillageAction)
        else return sequenceOf(UnitAction(UnitActionType.Pillage, 65f, pillageAction.title) {
            val pillageText = "Are you sure you want to pillage this [${tile.getImprovementToPillageName()!!}]?"
            ConfirmPopup(
                GUI.getWorldScreen(),
                pillageText,
                "Pillage",
                true
            ) {
                (pillageAction.action)()
                GUI.setUpdateWorldOnNextRender()
            }.open()
        })
    }

    internal fun getPillageAction(unit: MapUnit, tile: Tile): UnitAction? {
        val improvementName = unit.currentTile.getImprovementToPillageName()
        if (unit.isCivilian() || improvementName == null || tile.getOwner() == unit.civ) return null
        return UnitAction(
            UnitActionType.Pillage, 65f,
            title = "${UnitActionType.Pillage} [$improvementName]",
            action = {
                if (!GUI.isWorldLoaded() || !GUI.getMap().pillageTile(unit)) {
                    check(UnitPillage.pillage(unit)) { "Pillage action became invalid before execution" }
                }
            }.takeIf { unit.hasMovement() && canPillage(unit, tile) }
        )
    }

    // Public - used in UnitAutomation
    @Readonly
    fun canPillage(unit: MapUnit, tile: Tile): Boolean {
        return UnitPillage.canPillage(unit, tile)
    }
}
