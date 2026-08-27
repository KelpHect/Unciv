package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSpyController
import com.unciv.logic.multiplayer.authoritative.PendingEndTurnAction
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedSpy
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter

/** Renders only destinations and coup actions advertised for owned spies. */
internal class AuthoritativeSpyPanel(
    private val projection: PlayerProjection,
    private val controller: AuthoritativeSpyController,
    private val busy: Boolean,
    private val submit: (taskName: String, operation: suspend () -> Unit) -> Unit,
) {
    private var selectedSpyName: String? = projection.spies.firstOrNull()?.name

    fun build(): Table = Table().also(::rebuild)

    private fun rebuild(root: Table): Table = root.apply {
        clear()
        defaults().pad(3f)
        if (!projection.isCurrentTurn || projection.spies.isEmpty()) return@apply
        if (PendingEndTurnAction.MoveSpies in projection.pendingTurnActions) {
            add("Assign every required spy before ending the turn".toLabel())
                .colspan(2).left().row()
        }
        val selectedSpy = projection.spies.firstOrNull { it.name == selectedSpyName }
            ?: projection.spies.first()
        add(spyRoster(root, selectedSpy)).top().growX()
        add(destinationList(selectedSpy)).top().growX()
    }

    private fun spyRoster(root: Table, selectedSpy: ProjectedSpy): Table = Table().apply {
        defaults().pad(5f)
        add("Spy".toLabel()).left()
        add("Rank".toLabel())
        add("Location".toLabel()).left()
        add("Action".toLabel()).left()
        add().row()
        for (spy in projection.spies) {
            add(spy.name.toLabel()).left()
            add(rankBadge(spy.rank))
            add(cityName(spy.cityId).toLabel()).left()
            val action = spy.action.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            add(
                (if (spy.turnsRemaining > 0) "$action (${spy.turnsRemaining} turns)" else action)
                    .toLabel(),
            ).left()
            add((if (spy == selectedSpy) "Selected" else "Move").toTextButton().apply {
                if (spy == selectedSpy || busy) disable()
                onClick {
                    selectedSpyName = spy.name
                    rebuild(root)
                }
            }).row()
        }
    }

    private fun destinationList(spy: ProjectedSpy): Table = Table().apply {
        defaults().pad(5f)
        add().width(30f)
        add("City".toLabel()).left()
        add("Spy present".toLabel())
        add().row()

        if (spy.canMoveToHideout) {
            add(ImageGetter.getImage("OtherIcons/Hideout")).size(30f)
            add("Spy Hideout".toLabel()).left()
            add().width(30f)
            add(actionButton("Move") { controller.move(spy.name, null) }).row()
        }
        for (cityId in spy.availableCityIds) {
            add(ImageGetter.getImage("OtherIcons/City")).size(30f)
            add(cityName(cityId).toLabel()).left()
            add(
                if (projection.spies.any { it.cityId == cityId })
                    ImageGetter.getImage("OtherIcons/Spy_White").apply { color = Color.WHITE }
                else Table(),
            ).size(30f)
            add(actionButton("Move") { controller.move(spy.name, cityId) }).row()
        }
        if (spy.canStageCoup) {
            add().width(30f)
            add("Current city".toLabel()).left()
            add().width(30f)
            add(actionButton("Stage coup") { controller.setCoup(spy.name, true) }).row()
        }
        if (spy.canCancelCoup) {
            add().width(30f)
            add("Current city".toLabel()).left()
            add().width(30f)
            add(actionButton("Cancel coup") { controller.setCoup(spy.name, false) }).row()
        }
    }

    private fun cityName(cityId: String?): String {
        if (cityId == null) return "Spy Hideout"
        return projection.ownCities.firstOrNull { it.id == cityId }?.name
            ?: projection.visibleForeignCities.firstOrNull { it.id == cityId }?.name
            ?: cityId
    }

    private fun rankBadge(rank: Int): Table = Table().apply {
        add(ImageGetter.getImage("OtherIcons/Spy_White").apply { color = Color.WHITE }).size(26f)
        val color = when (rank) {
            1 -> Color.BROWN
            2 -> Color.LIGHT_GRAY
            else -> Color.GOLD
        }
        repeat(rank.coerceAtMost(9)) {
            add(ImageGetter.getImage("OtherIcons/Star").apply { this.color = color })
                .size(8f).pad(1f)
            if (it % 3 == 2) row()
        }
    }

    private fun actionButton(
        title: String,
        operation: suspend () -> Unit,
    ): TextButton = title.toTextButton().apply {
        if (busy) disable()
        onClick { submit("Submit authoritative spy action", operation) }
    }
}
