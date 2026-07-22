package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import java.security.MessageDigest

/** Projects and acknowledges only canonical research-completion alerts. */
object ResearchCompletionCommandExecutor {
    fun prompts(actor: Civilization): List<ProjectedResearchCompletion> =
        actor.popupAlerts.withIndex().mapNotNull { indexed ->
            val alert = indexed.value
            if (alert.type != AlertType.TechResearched ||
                alert.value !in actor.gameInfo.ruleset.technologies
            ) return@mapNotNull null
            ProjectedResearchCompletion(
                promptId = promptId(actor, alert, indexed.index),
                technologyName = alert.value,
            )
        }.sortedBy { it.promptId }

    fun acknowledge(game: GameInfo, actor: Civilization, promptId: String) {
        require(game.currentPlayer == actor.civID) {
            "Authenticated actor cannot acknowledge research outside their turn"
        }
        require(promptId.length == 64 && promptId.all { it.isDigit() || it in 'a'..'f' }) {
            "Invalid research-completion identity"
        }
        val indexed = actor.popupAlerts.withIndex().singleOrNull { entry ->
            entry.value.type == AlertType.TechResearched &&
                entry.value.value in game.ruleset.technologies &&
                promptId(actor, entry.value, entry.index) == promptId
        } ?: error("Research completion is not pending for the authenticated civilization")
        actor.popupAlerts.remove(indexed.value)
    }

    private fun promptId(actor: Civilization, alert: PopupAlert, index: Int): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${actor.civID}\u0000${alert.type}\u0000${alert.value}\u0000$index".toByteArray())
            .joinToString("") { "%02x".format(it) }
}
