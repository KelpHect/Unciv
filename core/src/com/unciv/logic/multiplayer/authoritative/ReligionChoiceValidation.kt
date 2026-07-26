package com.unciv.logic.multiplayer.authoritative

/** Validates only the closed choices and slot semantics present in a projection. */
internal object ReligionChoiceValidation {
    fun requireValid(
        choice: ProjectedReligionChoice,
        beliefNames: List<String>,
        religionIconName: String?,
        religionDisplayName: String?,
    ) {
        require(
            beliefNames.size == choice.requiredBeliefTypes.size &&
                beliefNames.distinct().size == beliefNames.size,
        ) {
            "Religious belief selection must fill each projected slot exactly once"
        }
        val available = choice.availableBeliefs.associateBy { it.name }
        val selectedTypes = beliefNames.map { name ->
            available[name]?.type
                ?: throw IllegalArgumentException(
                    "Religious belief is absent from the current server projection",
                )
        }
        requireSlotTypes(selectedTypes, choice.requiredBeliefTypes)

        if (choice.requiresReligionIdentity) {
            require(religionIconName in choice.availableReligionIcons) {
                "Religion icon is absent from the current server projection"
            }
            require(
                !religionDisplayName.isNullOrBlank() &&
                    religionDisplayName.length <= MAX_DISPLAY_NAME_LENGTH &&
                    religionDisplayName.none { it.isISOControl() },
            ) {
                "Religion display name must be 1-128 printable characters"
            }
        } else {
            require(religionIconName == null && religionDisplayName == null) {
                "Religion identity is not accepted for this projected choice"
            }
        }
    }

    private fun requireSlotTypes(
        selected: List<ReligiousBeliefType>,
        required: List<ReligiousBeliefType>,
    ) {
        for (type in ReligiousBeliefType.entries.filter {
            it != ReligiousBeliefType.Any
        }) {
            require(selected.count { it == type } >= required.count { it == type }) {
                "Religious beliefs do not satisfy the projected slot types"
            }
        }
        require(
            selected.size - required.count { it != ReligiousBeliefType.Any } ==
                required.count { it == ReligiousBeliefType.Any },
        ) {
            "Religious beliefs do not satisfy the projected wildcard slots"
        }
    }

    const val MAX_DISPLAY_NAME_LENGTH = 128
}
