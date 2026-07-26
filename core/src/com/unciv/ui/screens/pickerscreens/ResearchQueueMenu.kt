package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.multiplayer.authoritative.ResearchQueueAction
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.popups.AnimatedMenuPopup

internal class ResearchQueueMenu(
    stage: Stage,
    actor: Actor,
    private val queueActions: List<ResearchQueueAction>,
    private val onAction: (ResearchQueueAction) -> Unit,
) : AnimatedMenuPopup(stage, actor) {
    override fun createContentTable(): Table? {
        if (queueActions.isEmpty()) return null
        return super.createContentTable()!!.apply {
            for (action in queueActions) {
                val label = when (action) {
                    ResearchQueueAction.MoveToTop -> "Move to top"
                    ResearchQueueAction.MoveUp -> "Move up"
                    ResearchQueueAction.MoveDown -> "Move down"
                    ResearchQueueAction.MoveToEnd -> "Move to end"
                    ResearchQueueAction.Remove -> "Remove from queue"
                }
                add(getButton(label, KeyboardBinding.None) { onAction(action) }).row()
            }
        }
    }
}
