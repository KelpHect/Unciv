package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.civilization.Civilization

/** Canonical bounded research-queue mutations shared by projection and worker execution. */
internal object ResearchQueueOperations {
    fun availableActions(
        civilization: Civilization,
        queueIndex: Int,
    ): List<ResearchQueueAction> = ResearchQueueAction.entries.filter { action ->
        proposal(civilization, queueIndex, action) != null
    }

    fun apply(
        civilization: Civilization,
        technologyName: String,
        queueIndex: Int,
        action: ResearchQueueAction,
    ) {
        require(civilization.tech.techsToResearch.getOrNull(queueIndex) == technologyName) {
            "Research queue entry no longer matches the canonical queue"
        }
        val proposal = proposal(civilization, queueIndex, action)
            ?: error("Research queue action is not legal in the canonical state")
        civilization.tech.techsToResearch = proposal
        civilization.tech.updateResearchProgress()
    }

    private fun proposal(
        civilization: Civilization,
        queueIndex: Int,
        action: ResearchQueueAction,
    ): ArrayList<String>? {
        val current = civilization.tech.techsToResearch
        if (queueIndex !in current.indices) return null
        val targetIndex = when (action) {
            ResearchQueueAction.MoveToTop -> 0
            ResearchQueueAction.MoveUp -> queueIndex - 1
            ResearchQueueAction.MoveDown -> queueIndex + 1
            ResearchQueueAction.MoveToEnd -> current.lastIndex
            ResearchQueueAction.Remove -> null
        }
        if (targetIndex != null && targetIndex !in current.indices) return null
        if (targetIndex == queueIndex) return null
        val proposal = ArrayList(current)
        val technologyName = proposal.removeAt(queueIndex)
        if (targetIndex != null) proposal.add(targetIndex, technologyName)
        return proposal.takeIf { isValid(civilization, it) }
    }

    private fun isValid(civilization: Civilization, queue: List<String>): Boolean {
        val available = civilization.tech.techsResearched.toMutableSet()
        for (technologyName in queue) {
            val technology = civilization.gameInfo.ruleset.technologies[technologyName]
                ?: return false
            if (technology.prerequisites.any { it !in available }) return false
            available.add(technologyName)
        }
        return true
    }
}
