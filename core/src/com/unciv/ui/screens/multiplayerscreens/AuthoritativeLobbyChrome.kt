package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * Shared visual language for the API-v3 multiplayer front end: a dark carded
 * layout with gold rules and section captions, so the browser, the create
 * screen, and the staging room read as one product on desktop and on touch.
 */
internal object LobbyChrome {
    val accent: Color = Color.GOLD
    val muted: Color = Color(0.72f, 0.74f, 0.80f, 1f)
    val ready: Color = Color(0.36f, 0.85f, 0.42f, 1f)
    val waiting: Color = Color(0.62f, 0.64f, 0.70f, 1f)
    val danger: Color = Color(0.94f, 0.38f, 0.34f, 1f)

    private val skinStrings get() = BaseScreen.skinStrings

    /** A titled card. Pass a blank [title] for an untitled surface. */
    fun card(title: String = "", columns: Int = 2): Table = Table(BaseScreen.skin).apply {
        background = skinStrings.getUiBackground(
            "MultiplayerScreen/Section",
            skinStrings.roundedEdgeRectangleMidShape,
            skinStrings.skinConfig.baseColor.darken(0.35f),
        )
        defaults().pad(7f)
        top()
        header(title, columns)
    }

    /**
     * Empties a card and restores its header. Live panels re-render in place, so
     * the header has to be rebuilt with them or the rule vanishes on refresh.
     */
    fun resetCard(card: Table, title: String = "", columns: Int = 2) {
        card.clearChildren()
        card.header(title, columns)
    }

    private fun Table.header(title: String, columns: Int) {
        if (title.isBlank()) return
        add(caption(title)).colspan(columns).growX().left().padBottom(2f).row()
        addSeparator(accent.cpy().apply { a = 0.45f }, colSpan = columns, height = 1f)
            .padTop(0f).padBottom(6f)
    }

    /** A recessed surface used for rows inside a [card]. */
    fun row(highlighted: Boolean = false): Table = Table(BaseScreen.skin).apply {
        background = skinStrings.getUiBackground(
            "MultiplayerScreen/Row",
            skinStrings.roundedEdgeRectangleSmallShape,
            skinStrings.skinConfig.baseColor.let {
                if (highlighted) it.brighten(0.18f) else it.darken(0.6f)
            },
        )
        defaults().pad(6f)
    }

    fun caption(text: String) = text.uppercase().tr().toLabel(accent, hideIcons = true)

    fun title(text: String) = text.tr().toLabel(Color.WHITE, 28, hideIcons = true)

    fun hint(text: String) = text.tr().toLabel(muted, hideIcons = true)

    /** `label: value` pair rendered into a two-column [card]. */
    fun Table.field(label: String, value: String) {
        add(hint(label)).left().top()
        add(value.tr().toLabel(hideIcons = true)).growX().left().row()
    }

    fun Table.control(label: String, control: Actor, minWidth: Float = 210f) {
        add(hint(label)).left()
        add(control).minWidth(minWidth).growX().left().row()
    }

    fun readyBadge(isReady: Boolean, waitingText: String = "Not ready") =
        (if (isReady) "Ready" else waitingText).tr()
            .toLabel(if (isReady) ready else waiting, hideIcons = true)

    /**
     * Nation portrait with the nation's own ring colour, or a neutral marker for
     * a slot whose occupant has not chosen yet. Falls back to a plain circle for
     * a civilization this client's ruleset cannot resolve.
     */
    fun nationBadge(ruleset: Ruleset, civilizationId: String, size: Float): Actor {
        val nation: Nation? = ruleset.nations[civilizationId]
        if (nation == null)
            return "?".toLabel(muted, (size * 0.6f).toInt(), Align.center)
                .surroundWithCircle(size, color = Color.DARK_GRAY)
        return ImageGetter.getNationPortrait(nation, size)
    }

    fun nationLabel(ruleset: Ruleset, civilizationId: String): Actor {
        val nation = ruleset.nations[civilizationId]
        val text = when {
            civilizationId.isBlank() -> "Choosing a civilization"
            nation == null -> civilizationId
            else -> nation.getLeaderDisplayName()
        }
        return text.tr().toLabel(
            if (nation == null) muted else nation.getInnerColor().cpy(),
            hideIcons = true,
        )
    }
}
