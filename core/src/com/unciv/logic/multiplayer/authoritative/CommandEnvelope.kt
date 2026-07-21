package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CityTileAssignment {
    @SerialName("unworked") Unworked,
    @SerialName("worked") Worked,
    @SerialName("locked") Locked,
}

@Serializable
enum class CitizenFocus {
    @SerialName("no_focus") NoFocus,
    @SerialName("manual") Manual,
    @SerialName("food_focus") FoodFocus,
    @SerialName("production_focus") ProductionFocus,
    @SerialName("gold_focus") GoldFocus,
    @SerialName("science_focus") ScienceFocus,
    @SerialName("culture_focus") CultureFocus,
    @SerialName("happiness_focus") HappinessFocus,
    @SerialName("faith_focus") FaithFocus,
    @SerialName("gold_growth_focus") GoldGrowthFocus,
    @SerialName("production_growth_focus") ProductionGrowthFocus,
}

/**
 * Public API-v3 command contract. This deliberately models an intent, never a
 * serialized [com.unciv.logic.GameInfo] or an arbitrary object patch.
 *
 * The server derives the actor from the authenticated session and game
 * membership; it is not part of this envelope.
 */
data class CommandEnvelope(
    val protocolVersion: Int,
    val gameId: String,
    val commandId: String,
    val expectedRevision: Long,
    val clientObservedStateHash: String? = null,
    val command: GameCommand,
) {
    fun validate(): CommandEnvelopeValidation = when {
        protocolVersion != CURRENT_PROTOCOL_VERSION -> CommandEnvelopeValidation.UnsupportedProtocol(protocolVersion)
        gameId.isBlank() -> CommandEnvelopeValidation.Invalid("gameId must not be blank")
        commandId.isBlank() -> CommandEnvelopeValidation.Invalid("commandId must not be blank")
        expectedRevision < 0 -> CommandEnvelopeValidation.Invalid("expectedRevision must not be negative")
        else -> CommandEnvelopeValidation.Valid
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 3
    }
}

/**
 * Closed by design. Adding a mutation requires an explicit protocol type,
 * server authorization rule, engine handler, projection effect, and tests.
 */
sealed interface GameCommand {
    data object EndTurn : GameCommand

    /** First non-turn vertical-slice command. The authoritative engine still
     * validates ownership, visibility, movement points, and destination. */
    data class MoveUnit(
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : GameCommand

    data class QueueConstruction(
        val cityId: String,
        val constructionName: String,
    ) : GameCommand

    data class QueueConstructionAtTile(
        val cityId: String,
        val constructionName: String,
        val x: Int,
        val y: Int,
    ) : GameCommand

    data class SetPerpetualConstruction(
        val cityId: String,
        val constructionName: String,
    ) : GameCommand

    data class RemoveConstruction(
        val cityId: String,
        val queueIndex: Int,
        val expectedConstructionName: String,
    ) : GameCommand

    data class MoveConstruction(
        val cityId: String,
        val fromIndex: Int,
        val toIndex: Int,
        val expectedConstructionName: String,
    ) : GameCommand

    data class PurchaseConstruction(
        val cityId: String,
        val constructionName: String,
        val currencyName: String,
        val queueIndex: Int? = null,
    ) : GameCommand

    data class PurchaseConstructionAtTile(
        val cityId: String,
        val constructionName: String,
        val currencyName: String,
        val x: Int,
        val y: Int,
        val queueIndex: Int? = null,
    ) : GameCommand

    data class BuyCityTile(
        val cityId: String,
        val x: Int,
        val y: Int,
    ) : GameCommand

    data class SetCityTileAssignment(
        val cityId: String,
        val x: Int,
        val y: Int,
        val assignment: CityTileAssignment,
    ) : GameCommand

    data class SetSpecialistCount(
        val cityId: String,
        val specialistName: String,
        val count: Int,
    ) : GameCommand

    data class SetManualSpecialists(
        val cityId: String,
        val enabled: Boolean,
    ) : GameCommand

    data class ResetCitizens(val cityId: String) : GameCommand

    data class SetAvoidGrowth(
        val cityId: String,
        val enabled: Boolean,
    ) : GameCommand

    data class SetCitizenFocus(
        val cityId: String,
        val focus: CitizenFocus,
    ) : GameCommand

    data class SetResearchPath(val technologyName: String) : GameCommand

    data class AdoptPolicy(val policyName: String) : GameCommand

    data class ChooseFreeTechnology(val technologyName: String) : GameCommand
}

sealed interface CommandEnvelopeValidation {
    data object Valid : CommandEnvelopeValidation
    data class UnsupportedProtocol(val receivedVersion: Int) : CommandEnvelopeValidation
    data class Invalid(val reason: String) : CommandEnvelopeValidation
}
