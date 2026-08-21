package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.models.stats.Stat
import com.unciv.ui.components.extensions.toLabel

/**
 * A compact empire overview built entirely from the player projection:
 * treasury, city roster with their server-computed yields, unit count,
 * research standing and policy progress.
 *
 * The classic empire-overview tabs read canonical GameInfo families the
 * projection deliberately does not carry; this panel is the V3 equivalent for
 * what IS projected, and grows only when the disclosure policy grows.
 */
internal class AuthoritativeEmpirePanel(
    private val projection: PlayerProjection,
) {
    fun build(): Table = Table().apply {
        defaults().pad(3f)

        add(
            (
                "Gold: [${projection.gold}]  •  Cities: ${projection.ownCities.size}  •  " +
                    "Units: ${projection.ownUnits.size}"
                ).toLabel(),
        ).left().row()

        val research = projection.research
        add(
            (
                "Research: ${research.currentTechnology ?: "none"}  •  " +
                    "${research.researchedTechnologies.size} technologies"
                ).toLabel(),
        ).left().row()

        val policies = projection.policies
        add(
            (
                "Policies: ${policies.adoptedPolicies.size} adopted  •  " +
                    "${policies.storedCulture}/${policies.cultureNeededForNextPolicy} culture" +
                    if (policies.freePolicies > 0) "  •  ${policies.freePolicies} free!" else ""
                ).toLabel(),
        ).left().row()

        if (projection.ownCities.isEmpty()) return@apply

        add(LobbyChrome.caption("Cities")).colspan(4).growX().left().padTop(6f).row()
        add(LobbyChrome.caption("Name")).left().padBottom(2f)
        add(LobbyChrome.caption("Pop")).left().padBottom(2f)
        add(LobbyChrome.caption("Yields")).growX().left().padBottom(2f)
        add(LobbyChrome.caption("Health")).left().padBottom(2f).row()
        for (city in projection.ownCities.sortedBy { it.name }) {
            add(city.name.toLabel(hideIcons = true)).left()
            add("${city.population}".toLabel()).left()
            add(cityYields(city)).growX().left()
            add("${city.health}".toLabel()).left().row()
        }
    }

    /** Server-computed headline yields with the game's own stat glyphs. */
    private fun cityYields(city: com.unciv.logic.multiplayer.authoritative.ProjectedCity) =
        city.stats?.let { stats ->
            (
                "${stats.food}${Stat.Food.character} " +
                    "${stats.production}${Stat.Production.character} " +
                    "${stats.gold}${Stat.Gold.character} " +
                    "${stats.science}${Stat.Science.character} " +
                    "${stats.culture}${Stat.Culture.character}"
                ).toLabel()
        } ?: "-".toLabel()
}
