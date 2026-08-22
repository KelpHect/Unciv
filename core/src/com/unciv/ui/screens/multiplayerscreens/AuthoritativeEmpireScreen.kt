package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.stats.Stat
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize

/**
 * The classic empire overview, projection-fed: Cities, Units, Resources,
 * Wonders, Trades and Politics tabs over exactly what the worker discloses.
 * No canonical state is read and nothing here can mutate.
 */
class AuthoritativeEmpireScreen(
    private val projectionFeed: () -> PlayerProjection,
    private val ruleset: Ruleset,
) : BaseScreen(), RecreateOnResize {

    private val content = TabbedPager(
        minimumWidth = stage.width * 0.9f,
        maximumWidth = stage.width * 0.9f,
        minimumHeight = stage.height * 0.85f,
        maximumHeight = stage.height * 0.85f,
        separatorColor = LobbyChrome.accent,
        shortcutScreen = this,
        capacity = 7,
    )

    init {
        // Nation portraits/stat icons resolve through the pinned ruleset.
        ImageGetter.setNewRuleset(ruleset, ignoreIfModsAreEqual = true)

        val closeButton = "Close".toTextButton()
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)
        closeButton.onClick { game.popScreen() }

        val layout = Table(BaseScreen.skin)
        layout.defaults().pad(6f)
        layout.add(LobbyChrome.title("Empire")).left()
        layout.add(closeButton).right().row()
        layout.add(content).grow().row()
        stage.addActor(layout)
        layout.setFillParent(true)

        fillTabs(projectionFeed())
        content.selectPage(0)
    }

    override fun getCivilopediaRuleset() = ruleset

    /** Fresh figures on resize-recreation; the world refreshes its own feed. */
    private fun fillTabs(projection: PlayerProjection) {
        content.addPage("Cities", citiesTab(projection))
        content.addPage("Units", unitsTab(projection))
        content.addPage("Resources", resourcesTab(projection))
        content.addPage("Wonders", wondersTab(projection))
        content.addPage("Trades", tradesTab(projection))
        content.addPage("Politics", politicsTab(projection))
    }

    private fun header(caption: String): Table = Table(BaseScreen.skin).apply {
        defaults().pad(4f)
        add(LobbyChrome.caption(caption)).growX().left().row()
    }

    private fun citiesTab(projection: PlayerProjection): Table {
        val table = header("Cities")
        for (column in listOf("Name", "Pop", "Health", "Yields", "Growth", "Buildings"))
            table.add(LobbyChrome.caption(column)).left().padBottom(3f)
        table.row()

        for (city in projection.ownCities.sortedBy { it.name }) {
            table.add(city.name.toLabel(hideIcons = true)).left()
            table.add("${city.population}".toLabel()).left()
            table.add("${city.health}".toLabel()).left()
            table.add(cityYields(city.stats)).left()
            val growth = city.growth
            table.add(
                when {
                    growth == null -> "-"
                    growth.turnsToStarvation != null ->
                        "[${growth.turnsToStarvation}] to starve"
                    growth.turnsToNewPopulation != null ->
                        "[${growth.turnsToNewPopulation}] to grow"
                    else -> "Stopped"
                }.toLabel(),
            ).left()
            table.add("${city.builtBuildings.size}".toLabel()).left().row()
        }
        return scrollable(table)
    }

    private fun unitsTab(projection: PlayerProjection): Table {
        val table = header("Units")
        for (column in listOf("Unit", "Health", "Position"))
            table.add(LobbyChrome.caption(column)).left().padBottom(3f)
        table.row()
        for (unit in projection.ownUnits.sortedWith(
            compareBy({ it.name }, { it.id }),
        )) {
            table.add(unit.name.toLabel(hideIcons = true)).left()
            table.add("${unit.health}".toLabel()).left()
            table.add("(${unit.x}, ${unit.y})".toLabel()).left().row()
        }
        if (projection.ownUnits.isEmpty())
            table.add(LobbyChrome.hint("No units")).colspan(3).left().row()
        return scrollable(table)
    }

    private fun resourcesTab(projection: PlayerProjection): Table {
        val table = header("Resources")
        for (resource in projection.resources.sortedBy { it.name }) {
            table.add(resource.name.toLabel(hideIcons = true)).left()
            table.add("${resource.amount}".toLabel()).right().row()
        }
        if (projection.resources.isEmpty())
            table.add(LobbyChrome.hint("No resources")).left().row()
        return scrollable(table)
    }

    private fun wondersTab(projection: PlayerProjection): Table {
        val table = header("Wonders")
        // Wonders are the wonder-class buildings among the projected built
        // lists; completion events add the public history on top.
        val wonders = projection.ownCities
            .asSequence()
            .flatMap { city ->
                city.builtBuildings.asSequence()
                    .mapNotNull { building -> ruleset.buildings[building]?.let { city to it } }
            }
            .filter { (_, building) -> building.isWonder }
            .sortedBy { (_, building) -> building.name }
            .toList()
        if (wonders.isEmpty()) {
            table.add(LobbyChrome.hint("No wonders built yet")).left().row()
            return scrollable(table)
        }
        for ((city, building) in wonders) {
            table.add(building.name.toLabel(hideIcons = true)).left()
            table.add(LobbyChrome.hint("in ${city.name}")).left().row()
        }
        return scrollable(table)
    }

    private fun tradesTab(projection: PlayerProjection): Table {
        val table = header("Trades")
        for (partner in projection.tradePartners) {
            table.add(partner.civilizationId.toLabel(hideIcons = true)).left()
            table.add(
                if (partner.hasPendingOutgoingOffer) "Offer pending".toLabel(LobbyChrome.accent)
                else LobbyChrome.hint("No pending offer"),
            ).left().row()
        }
        for (request in projection.pendingTradeRequests) {
            table.add(request.requestingCivilizationId.toLabel(hideIcons = true)).left()
            table.add("Proposes a trade".toLabel(LobbyChrome.ready)).left().row()
        }
        if (projection.tradePartners.isEmpty() && projection.pendingTradeRequests.isEmpty())
            table.add(LobbyChrome.hint("No trading partners known")).left().row()
        return scrollable(table)
    }

    private fun politicsTab(projection: PlayerProjection): Table {
        val table = header("Global politics")
        for (partner in projection.diplomacyPartners.sortedBy { it.civilizationId }) {
            val nation = ruleset.nations[partner.civilizationId]
            table.add(LobbyChrome.nationBadge(ruleset, partner.civilizationId, 30f)).left()
            table.add(
                (
                    nation?.getLeaderDisplayName() ?: partner.civilizationId
                    ).toLabel(hideIcons = true),
            ).growX().left()
            table.add(
                partner.relationshipLevel.name.replaceFirstChar(Char::lowercase)
                    .toLabel(relationshipColor(partner.relationshipLevel.name)),
            ).right().row()
        }
        if (projection.diplomacyPartners.isEmpty())
            table.add(LobbyChrome.hint("No civilizations met")).left().row()
        return scrollable(table)
    }

    private fun relationshipColor(levelName: String) = when (levelName) {
        "Ally", "Friend" -> LobbyChrome.ready
        "Enemy", "Unforgivable" -> LobbyChrome.danger
        else -> LobbyChrome.muted
    }

    private fun cityYields(stats: com.unciv.logic.multiplayer.authoritative.ProjectedCityStats?) =
        stats?.let {
            (
                "${it.food}${Stat.Food.character} ${it.production}${Stat.Production.character} " +
                    "${it.gold}${Stat.Gold.character} ${it.science}${Stat.Science.character} " +
                    "${it.culture}${Stat.Culture.character}"
                ).toLabel()
        } ?: "-".toLabel()

    private fun scrollable(table: Table): Table = Table(BaseScreen.skin).apply {
        add(com.unciv.ui.components.widgets.AutoScrollPane(table).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }).grow().row()
    }

    override fun recreate(): BaseScreen =
        AuthoritativeEmpireScreen(projectionFeed, ruleset)
}
