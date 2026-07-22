package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.unique.GameContext
import java.security.MessageDigest

/** Resolves pending mod-defined event choices from canonical alerts and ruleset state. */
object EventChoiceCommandExecutor {
    fun prompts(actor: Civilization): List<ProjectedEventPrompt> = actor.popupAlerts.withIndex().mapNotNull { indexed ->
        if (indexed.value.type != AlertType.Event) return@mapNotNull null
        project(actor, indexed.value, indexed.index)
    }.sortedBy { it.promptId }

    fun resolve(game: GameInfo, actor: Civilization, promptId: String, choiceId: String) {
        require(game.currentPlayer == actor.civID) { "Authenticated actor cannot resolve events outside their turn" }
        require(promptId.length == 64 && choiceId.length == 64) { "Invalid event choice identity" }
        val projected = prompts(actor).singleOrNull { it.promptId == promptId }
            ?: error("Event prompt is not pending for the authenticated civilization")
        require(projected.choices.any { it.choiceId == choiceId }) { "Event choice is not available in canonical state" }

        val indexed = actor.popupAlerts.withIndex().single { entry ->
            entry.value.type == AlertType.Event && promptId(actor, entry.value, entry.index) == promptId
        }
        val parsed = parse(actor, indexed.value) ?: error("Pending event context is invalid")
        val choices = parsed.event.getMatchingChoices(GameContext(actor, unit = parsed.unit))?.toList()
            ?: error("Pending event is no longer available")
        val choiceIndex = choices.indices.single { choiceId(promptId, it) == choiceId }
        choices[choiceIndex].triggerChoice(actor, parsed.unit)
        actor.popupAlerts.remove(indexed.value)
    }

    private fun project(actor: Civilization, alert: PopupAlert, index: Int): ProjectedEventPrompt? {
        val parsed = parse(actor, alert) ?: return null
        val choices = parsed.event.getMatchingChoices(GameContext(actor, unit = parsed.unit))?.toList() ?: return null
        val promptId = promptId(actor, alert, index)
        return ProjectedEventPrompt(
            promptId = promptId,
            eventName = parsed.event.name,
            unitId = parsed.unit?.id,
            text = parsed.event.text,
            choices = choices.mapIndexed { choiceIndex, choice ->
                ProjectedEventChoice(choiceId(promptId, choiceIndex), choice.text)
            },
        )
    }

    private data class ParsedEvent(val event: Event, val unit: MapUnit?)

    private fun parse(actor: Civilization, alert: PopupAlert): ParsedEvent? {
        val parts = alert.value.split(Constants.stringSplitCharacter)
        val event = actor.gameInfo.ruleset.events[parts.firstOrNull()] ?: return null
        var unit: MapUnit? = null
        for (part in parts.drop(1)) {
            if (!part.startsWith("unitId=")) return null
            val id = part.substringAfter("unitId=").toIntOrNull() ?: return null
            unit = actor.units.getUnitById(id) ?: return null
        }
        return ParsedEvent(event, unit)
    }

    private fun promptId(actor: Civilization, alert: PopupAlert, index: Int) =
        digest("${actor.civID}\u0000${alert.type}\u0000${alert.value}\u0000$index")

    private fun choiceId(promptId: String, index: Int) = digest("$promptId\u0000$index")

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
