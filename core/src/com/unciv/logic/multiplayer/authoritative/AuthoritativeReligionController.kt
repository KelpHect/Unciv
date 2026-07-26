package com.unciv.logic.multiplayer.authoritative

/** Projection-only boundary for pending belief and religion identity choices. */
class AuthoritativeReligionController internal constructor(
    private val projection: () -> PlayerProjection,
    private val submit: suspend (
        operation: suspend () -> AuthoritativeCommandOutcome?,
    ) -> Unit,
    private val action: suspend (
        beliefNames: List<String>,
        religionIconName: String?,
        religionDisplayName: String?,
    ) -> AuthoritativeCommandOutcome?,
) {
    suspend fun choose(
        beliefNames: List<String>,
        religionIconName: String?,
        religionDisplayName: String?,
    ) {
        val choice = projection().religionChoice
            ?: error("Religious choice is absent from the current server projection")
        ReligionChoiceValidation.requireValid(
            choice,
            beliefNames,
            religionIconName,
            religionDisplayName,
        )
        submit { action(beliefNames.toList(), religionIconName, religionDisplayName) }
    }
}
