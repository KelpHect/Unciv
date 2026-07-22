package com.unciv.logic.multiplayer.authoritative

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.Counter
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType

/** Derives and executes every pending player belief choice from canonical state. */
internal object ReligionChoiceExecutor {
    fun projection(civilization: Civilization): ProjectedReligionChoice? {
        val required = requiredBeliefs(civilization) ?: return null
        val manager = civilization.religionManager
        val currentReligion = manager.religion
        val availableBeliefs = civilization.gameInfo.ruleset.beliefs.values.asSequence()
            .filter { belief -> currentReligion?.hasBelief(belief.name) != true }
            .filter { belief -> manager.getReligionWithBelief(belief) in listOf(null, currentReligion) }
            .filter { belief -> beliefIsAllowed(belief, civilization) }
            .map { ProjectedReligiousBelief(it.name, it.type.projected()) }
            .sortedWith(compareBy<ProjectedReligiousBelief> { it.type.name }.thenBy { belief -> belief.name })
            .toList()
        val founding = manager.religionState == ReligionState.FoundingReligion
        val usedReligions = civilization.gameInfo.religions.values.mapTo(hashSetOf()) { it.name }
        return ProjectedReligionChoice(
            requiredBeliefTypes = BeliefType.entries.flatMap { type ->
                List(required[type]) { type.projected() }
            },
            availableBeliefs = availableBeliefs,
            availableReligionIcons = if (founding)
                civilization.gameInfo.ruleset.religions.filterNot { it in usedReligions }.sorted()
            else emptyList(),
            requiresReligionIdentity = founding,
        )
    }

    fun execute(
        civilization: Civilization,
        beliefNames: List<String>,
        religionIconName: String?,
        religionDisplayName: String?,
    ) {
        val choice = projection(civilization)
            ?: error("Civilization does not have a pending religious choice")
        require(beliefNames.size == choice.requiredBeliefTypes.size && beliefNames.distinct().size == beliefNames.size) {
            "Religious belief selection does not fill each canonical slot exactly once"
        }
        val allowedByName = choice.availableBeliefs.associateBy { it.name }
        val selected = beliefNames.map { name ->
            requireNotNull(allowedByName[name]) { "Religious belief is unavailable in canonical state" }
        }
        validateSlotTypes(selected.map { it.type }, choice.requiredBeliefTypes)

        if (choice.requiresReligionIdentity) {
            require(religionIconName in choice.availableReligionIcons) {
                "Religion icon is unavailable in canonical state"
            }
            requireValidDisplayName(civilization, religionIconName!!, religionDisplayName)
            civilization.religionManager.foundReligion(religionDisplayName!!, religionIconName)
        } else {
            require(religionIconName == null && religionDisplayName == null) {
                "Religion identity is accepted only while founding a religion"
            }
        }

        val beliefs = beliefNames.map { civilization.gameInfo.ruleset.beliefs.getValue(it) }
        civilization.religionManager.chooseBeliefs(
            beliefs,
            useFreeBeliefs = civilization.religionManager.usingFreeBeliefs(),
        )
    }

    private fun requiredBeliefs(civilization: Civilization): Counter<BeliefType>? =
        civilization.religionManager.run {
            when {
                religionState == ReligionState.FoundingReligion -> getBeliefsToChooseAtFounding()
                religionState == ReligionState.EnhancingReligion -> getBeliefsToChooseAtEnhancing()
                hasFreeBeliefs() -> freeBeliefsAsEnums()
                canFoundOrExpandPantheon() -> Counter<BeliefType>().apply { add(BeliefType.Pantheon, 1) }
                else -> null
            }
        }

    private fun validateSlotTypes(selected: List<ReligiousBeliefType>, required: List<ReligiousBeliefType>) {
        val anySlots = required.count { it == ReligiousBeliefType.Any }
        for (type in ReligiousBeliefType.entries.filter { it != ReligiousBeliefType.Any }) {
            require(selected.count { it == type } >= required.count { it == type }) {
                "Religious belief selection does not satisfy canonical slot types"
            }
        }
        require(selected.size - required.count { it != ReligiousBeliefType.Any } == anySlots) {
            "Religious belief selection does not satisfy canonical wildcard slots"
        }
    }

    private fun BeliefType.projected(): ReligiousBeliefType = when (this) {
        BeliefType.Pantheon -> ReligiousBeliefType.Pantheon
        BeliefType.Founder -> ReligiousBeliefType.Founder
        BeliefType.Follower -> ReligiousBeliefType.Follower
        BeliefType.Enhancer -> ReligiousBeliefType.Enhancer
        BeliefType.Any -> ReligiousBeliefType.Any
        BeliefType.None -> error("None is not a selectable religious belief type")
    }

    private fun beliefIsAllowed(belief: Belief, civilization: Civilization): Boolean =
        belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
            .none { !it.conditionalsApply(civilization.state) } &&
            belief.getMatchingUniques(UniqueType.Unavailable, civilization.state).none()

    private fun requireValidDisplayName(
        civilization: Civilization,
        religionIconName: String,
        displayName: String?,
    ) {
        require(!displayName.isNullOrBlank() && displayName.length <= 128) {
            "Religion display name is invalid"
        }
        require(displayName != Constants.noReligionName) { "Religion display name is reserved" }
        require(displayName == religionIconName ||
            (displayName !in civilization.gameInfo.ruleset.religions &&
                civilization.gameInfo.religions.values.none { it.name == displayName || it.displayName == displayName })) {
            "Religion display name is unavailable in canonical state"
        }
    }
}
