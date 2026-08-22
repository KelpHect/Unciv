package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.multiplayer.authoritative.ProjectedNotification
import com.unciv.logic.multiplayer.authoritative.ProjectedNotificationAction
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * The classic world screen's notification cards, driven by the projection:
 * icon row, translated text, and round-robin tap through the click-through
 * actions the server projected - restricted to what a projection client can
 * faithfully do (look at places, open own cities, open the tech/policy
 * pickers, follow civilopedia links, select units).
 */
internal class AuthoritativeNotificationsFeed(
    private val ruleset: com.unciv.models.ruleset.Ruleset,
    private val handler: Handler,
) {
    interface Handler {
        fun centerOn(x: Int, y: Int)
        fun openTechTree(centerOnTech: String?)
        fun openCityScreenAt(x: Int, y: Int)
        fun focusDiplomacyPartner(civilizationId: String)
        fun selectUnitAt(x: Int, y: Int, unitId: Int?)
        fun openCivilopedia(link: String)
        fun openPolicyPicker(select: String?)
        fun openUrl(url: String)
    }

    /** Round-robin state per card, like [Notification.execute]. */
    private val actionIndexByIdentity = HashMap<Int, Int>()
    private var identitySeed = 0

    fun rebuild(notifications: List<ProjectedNotification>): Table = Table().apply {
        top()
        defaults().pad(3f)
        identitySeed = 0
        for (notification in notifications) {
            val identity = identitySeed++
            add(cardFor(notification, identity)).growX().row()
        }
    }

    private fun cardFor(notification: ProjectedNotification, identity: Int): Table {
        val card = Table()
        card.background = BaseScreen.skinStrings.getUiBackground(
            "MultiplayerScreen/NotificationCard",
            BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            BaseScreen.skinStrings.skinConfig.baseColor.darken(0.45f),
        )
        card.defaults().pad(4f)

        if (notification.icons.isNotEmpty()) {
            val iconsRow = Table()
            for (icon in notification.icons.reversed()) {
                iconsRow.add(NotificationIcon.getImage(icon, ruleset, 24f)).size(24f).padRight(3f)
            }
            card.add(iconsRow).left().top().row()
        }

        val label = notification.text.tr(hideIcons = false)
        card.add(label.toLabel()).left().row()

        if (notification.actions.isEmpty()) return card

        card.onClick {
            val actions = notification.actions
            if (actions.isEmpty()) return@onClick
            val index = actionIndexByIdentity.getOrPut(identity) { 0 }
            dispatch(actions[index % actions.size])
            actionIndexByIdentity[identity] = index + 1
        }
        return card
    }

    private fun dispatch(action: ProjectedNotificationAction) = when (action) {
        is ProjectedNotificationAction.Location -> handler.centerOn(action.x, action.y)
        is ProjectedNotificationAction.Tech -> handler.openTechTree(action.technologyName)
        is ProjectedNotificationAction.City -> handler.openCityScreenAt(action.x, action.y)
        is ProjectedNotificationAction.Diplomacy ->
            handler.focusDiplomacyPartner(action.civilizationId)
        is ProjectedNotificationAction.MapUnit ->
            handler.selectUnitAt(action.x, action.y, action.unitId)
        is ProjectedNotificationAction.Civilopedia -> handler.openCivilopedia(action.link)
        is ProjectedNotificationAction.Policy -> handler.openPolicyPicker(action.select)
        is ProjectedNotificationAction.Link -> handler.openUrl(action.url)
    }
}
