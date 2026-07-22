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

@Serializable
enum class CityGovernanceAction {
    @SerialName("annex") Annex,
    @SerialName("start_razing") StartRazing,
    @SerialName("stop_razing") StopRazing,
}

@Serializable
enum class CityDispositionAction {
    @SerialName("liberate") Liberate,
    @SerialName("annex") Annex,
    @SerialName("puppet") Puppet,
    @SerialName("raze") Raze,
    @SerialName("destroy") Destroy,
}

@Serializable
enum class UnitPosture {
    @SerialName("sleep") Sleep,
    @SerialName("sleep_until_healed") SleepUntilHealed,
    @SerialName("fortify") Fortify,
    @SerialName("fortify_until_healed") FortifyUntilHealed,
    @SerialName("guard") Guard,
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

    data class MoveUnitToward(
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : GameCommand

    data class CancelUnitMovementOrder(val unitId: Int) : GameCommand

    data class SetUnitExploration(val unitId: Int, val enabled: Boolean) : GameCommand

    data class SetUnitAutomation(val unitId: Int, val enabled: Boolean) : GameCommand

    data class SetUnitPosture(val unitId: Int, val posture: UnitPosture) : GameCommand

    data class DisbandUnit(val unitId: Int) : GameCommand

    data class PillageTile(val unitId: Int) : GameCommand

    data class FoundCity(val unitId: Int) : GameCommand

    data class ParadropUnit(
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : GameCommand

    data class AttackWithUnit(
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : GameCommand

    data class BombardWithCity(
        val cityId: String,
        val targetX: Int,
        val targetY: Int,
    ) : GameCommand

    data class LaunchNuclearStrike(
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : GameCommand

    data class AirSweep(
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : GameCommand

    data class UpgradeUnits(
        val unitIds: List<Int>,
        val targetUnitName: String,
    ) : GameCommand

    data class PromoteUnit(
        val unitId: Int,
        val promotionNames: List<String>,
        val saveAsCityDefault: Boolean,
    ) : GameCommand

    data class RenameUnit(
        val unitId: Int,
        val instanceName: String?,
    ) : GameCommand

    data class SetTileImprovementOrder(
        val unitId: Int,
        val improvementName: String?,
        val queuedImprovementName: String?,
    ) : GameCommand

    data class SetRoadConnectionOrder(
        val unitId: Int,
        val destinationX: Int?,
        val destinationY: Int?,
    ) : GameCommand

    data class SetCityUnitPromotionPreference(
        val cityId: String,
        val baseUnitName: String,
        val enabled: Boolean,
    ) : GameCommand

    data class SwapUnits(
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

    data class SellBuilding(
        val cityId: String,
        val buildingName: String,
    ) : GameCommand

    data class SetCityGovernance(
        val cityId: String,
        val action: CityGovernanceAction,
    ) : GameCommand

    data class ResolveCityDisposition(
        val cityId: String,
        val action: CityDispositionAction,
    ) : GameCommand

    data class CastDiplomaticVote(
        val candidateCivilizationId: String?,
    ) : GameCommand

    data class ChooseGreatPerson(
        val unitName: String,
    ) : GameCommand

    data class UseReligiousUnit(
        val unitId: Int,
        val action: ReligiousUnitAction,
    ) : GameCommand

    data class ChooseReligiousBeliefs(
        val beliefNames: List<String>,
        val religionIconName: String? = null,
        val religionDisplayName: String? = null,
    ) : GameCommand

    data class OfferTrade(
        val otherCivilizationId: String,
        val trade: ProjectedTrade,
    ) : GameCommand

    data class RetractTradeOffer(val otherCivilizationId: String) : GameCommand

    data class AcceptTrade(val requestId: String) : GameCommand

    data class DeclineTrade(val requestId: String) : GameCommand

    data class CounterTrade(val requestId: String, val trade: ProjectedTrade) : GameCommand

    data class DeclareWar(val otherCivilizationId: String) : GameCommand
    data class DenounceCivilization(val otherCivilizationId: String) : GameCommand
    data class OfferFriendship(val otherCivilizationId: String) : GameCommand
    data class MakeDiplomaticDemand(
        val otherCivilizationId: String,
        val demand: DiplomaticDemand,
    ) : GameCommand
    data class RespondToDiplomaticPrompt(
        val promptId: String,
        val accept: Boolean,
    ) : GameCommand
    data class RespondToCityStateProtectionPrompt(
        val promptId: String,
        val response: CityStateProtectionResponse,
    ) : GameCommand

    data class GiftCityStateGold(val cityStateCivilizationId: String, val amount: Int) : GameCommand
    data class SetCityStateProtection(val cityStateCivilizationId: String, val protect: Boolean) : GameCommand
    data class DemandCityStateTribute(val cityStateCivilizationId: String, val worker: Boolean) : GameCommand
    data class GiftCityStateImprovement(val cityStateCivilizationId: String, val x: Int, val y: Int, val improvementName: String) : GameCommand
    data class NegotiateCityStatePeace(val cityStateCivilizationId: String) : GameCommand
    data class MarryCityState(val cityStateCivilizationId: String) : GameCommand
    data class MoveSpy(val spyName: String, val cityId: String?) : GameCommand
    data class SetSpyCoup(val spyName: String, val enabled: Boolean) : GameCommand
    data class ResolveEventChoice(val promptId: String, val choiceId: String) : GameCommand
    data class UseGreatPersonUnit(val unitId: Int, val action: GreatPersonUnitAction) : GameCommand
    data class GiftUnit(val unitId: Int) : GameCommand
    data class TransformUnit(val unitId: Int, val actionId: String) : GameCommand
    data class TriggerUnitUnique(val unitId: Int, val actionId: String) : GameCommand

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

@Serializable
enum class ReligiousUnitAction {
    @SerialName("found_religion") FoundReligion,
    @SerialName("enhance_religion") EnhanceReligion,
    @SerialName("spread_religion") SpreadReligion,
    @SerialName("remove_heresy") RemoveHeresy,
}

@Serializable
enum class GreatPersonUnitAction {
    @SerialName("hurry_research") HurryResearch,
    @SerialName("hurry_policy") HurryPolicy,
    @SerialName("hurry_wonder") HurryWonder,
    @SerialName("hurry_building") HurryBuilding,
    @SerialName("conduct_trade_mission") ConductTradeMission,
}

sealed interface CommandEnvelopeValidation {
    data object Valid : CommandEnvelopeValidation
    data class UnsupportedProtocol(val receivedVersion: Int) : CommandEnvelopeValidation
    data class Invalid(val reason: String) : CommandEnvelopeValidation
}
