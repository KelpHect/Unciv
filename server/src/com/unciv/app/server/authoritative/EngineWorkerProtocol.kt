package com.unciv.app.server.authoritative

import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.logic.ContentAddressedRuleset
import com.unciv.logic.GameExecutionContext
import com.unciv.logic.RulesetManifest
import com.unciv.logic.multiplayer.authoritative.CityTileAssignment
import com.unciv.logic.multiplayer.authoritative.CityGovernanceAction
import com.unciv.logic.multiplayer.authoritative.CityDispositionAction
import com.unciv.logic.multiplayer.authoritative.CitizenFocus
import com.unciv.logic.multiplayer.authoritative.CityStateProtectionResponse
import com.unciv.logic.multiplayer.authoritative.ConstructionQueueAction
import com.unciv.logic.multiplayer.authoritative.HeadlessGameEngine
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.SpectatorProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedTrade
import com.unciv.logic.multiplayer.authoritative.DiplomaticDemand
import com.unciv.logic.multiplayer.authoritative.ReligiousUnitAction
import com.unciv.logic.multiplayer.authoritative.GreatPersonUnitAction
import com.unciv.logic.multiplayer.authoritative.UnitPosture
import com.unciv.logic.map.HexCoord
import com.unciv.json.json
import com.unciv.models.ruleset.RulesetCache
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Private length-prefixed JSON protocol. Bind only to loopback in development;
 * production launches this process behind a Unix-domain socket. */
object EngineWorkerProtocol {
    const val VERSION = 1
    const val maxFrameBytes = 16 * 1024 * 1024
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
}

@Serializable
data class WorkerRequest(
    val protocolVersion: Int,
    val serverTimeMillis: Long? = null,
    val actorId: String? = null,
    val rulesetManifest: WorkerRulesetManifest? = null,
    val operation: WorkerOperation,
)

@Serializable
data class WorkerRulesetManifest(
    val engineBuild: String,
    val baseRuleset: WorkerRuleset,
    val mods: List<WorkerRuleset> = emptyList(),
)

@Serializable
data class WorkerRuleset(val name: String, val sha256: String)

@Serializable
sealed interface WorkerOperation {
    /** Capability handshake performed before a control plane routes work to
     * this process. It intentionally needs neither actor nor game state. */
    @Serializable @SerialName("handshake")
    data object Handshake : WorkerOperation

    /** A setup intent, never a client-created GameInfo. The worker invokes the
     * shared GameStarter to create canonical revision zero. */
    @Serializable @SerialName("create_game")
    data class CreateGame(
        val gameId: String,
        val serverSeed: Long,
        val setup: WorkerGameSetup,
    ) : WorkerOperation

    @Serializable @SerialName("assign_player")
    data class AssignPlayer(val snapshot: String) : WorkerOperation

    @Serializable @SerialName("end_turn")
    data class EndTurn(
        val snapshot: String,
        val actorCivilizationId: String,
    ) : WorkerOperation

    @Serializable @SerialName("resign")
    data class Resign(val snapshot: String, val actorCivilizationId: String) : WorkerOperation

    @Serializable @SerialName("force_resign")
    data class ForceResign(val snapshot: String, val actorCivilizationId: String) : WorkerOperation

    @Serializable @SerialName("kick_player")
    data class KickPlayer(
        val snapshot: String,
        val actorCivilizationId: String,
        val targetCivilizationId: String,
    ) : WorkerOperation

    @Serializable @SerialName("move_unit")
    data class MoveUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
        val escortUnitId: Int? = null,
    ) : WorkerOperation

    @Serializable @SerialName("move_unit_toward")
    data class MoveUnitToward(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
        val escortUnitId: Int? = null,
    ) : WorkerOperation

    @Serializable @SerialName("cancel_unit_movement_order")
    data class CancelUnitMovementOrder(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
    ) : WorkerOperation

    @Serializable @SerialName("set_unit_exploration")
    data class SetUnitExploration(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val enabled: Boolean,
    ) : WorkerOperation

    @Serializable @SerialName("set_unit_automation")
    data class SetUnitAutomation(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val enabled: Boolean,
    ) : WorkerOperation

    @Serializable @SerialName("set_unit_posture")
    data class SetUnitPosture(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val posture: UnitPosture,
    ) : WorkerOperation

    @Serializable @SerialName("disband_unit")
    data class DisbandUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
    ) : WorkerOperation

    @Serializable @SerialName("pillage_tile")
    data class PillageTile(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
    ) : WorkerOperation

    @Serializable @SerialName("found_city")
    data class FoundCity(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
    ) : WorkerOperation

    @Serializable @SerialName("paradrop_unit")
    data class ParadropUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : WorkerOperation

    @Serializable @SerialName("attack_with_unit")
    data class AttackWithUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : WorkerOperation

    @Serializable @SerialName("bombard_with_city")
    data class BombardWithCity(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val targetX: Int,
        val targetY: Int,
    ) : WorkerOperation

    @Serializable @SerialName("launch_nuclear_strike")
    data class LaunchNuclearStrike(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : WorkerOperation

    @Serializable @SerialName("air_sweep")
    data class AirSweep(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val targetX: Int,
        val targetY: Int,
    ) : WorkerOperation

    @Serializable @SerialName("upgrade_units")
    data class UpgradeUnits(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitIds: List<Int>,
        val targetUnitName: String,
    ) : WorkerOperation

    @Serializable @SerialName("promote_unit")
    data class PromoteUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val promotionNames: List<String>,
        val saveAsCityDefault: Boolean,
    ) : WorkerOperation

    @Serializable @SerialName("set_city_unit_promotion_preference")
    data class SetCityUnitPromotionPreference(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val baseUnitName: String,
        val enabled: Boolean,
    ) : WorkerOperation

    @Serializable @SerialName("rename_unit")
    data class RenameUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val instanceName: String?,
    ) : WorkerOperation

    @Serializable @SerialName("set_tile_improvement_order")
    data class SetTileImprovementOrder(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val improvementName: String?,
        val queuedImprovementName: String?,
    ) : WorkerOperation

    @Serializable @SerialName("set_road_connection_order")
    data class SetRoadConnectionOrder(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val destinationX: Int?,
        val destinationY: Int?,
    ) : WorkerOperation

    @Serializable @SerialName("swap_units")
    data class SwapUnits(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : WorkerOperation

    @Serializable @SerialName("queue_construction")
    data class QueueConstruction(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val constructionName: String,
    ) : WorkerOperation

    @Serializable @SerialName("queue_construction_at_tile")
    data class QueueConstructionAtTile(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val constructionName: String,
        val x: Int,
        val y: Int,
    ) : WorkerOperation

    @Serializable @SerialName("set_perpetual_construction")
    data class SetPerpetualConstruction(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val constructionName: String,
    ) : WorkerOperation

    @Serializable @SerialName("remove_construction")
    data class RemoveConstruction(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val queueIndex: Int,
        val expectedConstructionName: String,
    ) : WorkerOperation

    @Serializable @SerialName("move_construction")
    data class MoveConstruction(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val fromIndex: Int,
        val toIndex: Int,
        val expectedConstructionName: String,
    ) : WorkerOperation

    @Serializable @SerialName("manage_construction_queues")
    data class ManageConstructionQueues(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val constructionName: String,
        val queueIndex: Int? = null,
        val action: ConstructionQueueAction,
    ) : WorkerOperation

    @Serializable @SerialName("purchase_construction")
    data class PurchaseConstruction(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val constructionName: String,
        val currencyName: String,
        val queueIndex: Int? = null,
    ) : WorkerOperation

    @Serializable @SerialName("purchase_construction_at_tile")
    data class PurchaseConstructionAtTile(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val constructionName: String,
        val currencyName: String,
        val x: Int,
        val y: Int,
        val queueIndex: Int? = null,
    ) : WorkerOperation

    @Serializable @SerialName("buy_city_tile")
    data class BuyCityTile(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val x: Int,
        val y: Int,
    ) : WorkerOperation

    @Serializable @SerialName("buy_city_tile_batch")
    data class BuyCityTileBatch(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val ring: Int,
    ) : WorkerOperation

    @Serializable @SerialName("sell_building")
    data class SellBuilding(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val buildingName: String,
    ) : WorkerOperation

    @Serializable @SerialName("set_city_governance")
    data class SetCityGovernance(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val action: CityGovernanceAction,
    ) : WorkerOperation

    @Serializable @SerialName("resolve_city_disposition")
    data class ResolveCityDisposition(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val action: CityDispositionAction,
    ) : WorkerOperation

    @Serializable @SerialName("cast_diplomatic_vote")
    data class CastDiplomaticVote(
        val snapshot: String,
        val actorCivilizationId: String,
        val candidateCivilizationId: String?,
    ) : WorkerOperation

    @Serializable @SerialName("choose_great_person")
    data class ChooseGreatPerson(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitName: String,
    ) : WorkerOperation

    @Serializable @SerialName("use_religious_unit")
    data class UseReligiousUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val action: ReligiousUnitAction,
    ) : WorkerOperation
    @Serializable @SerialName("use_great_person_unit")
    data class UseGreatPersonUnit(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
        val action: GreatPersonUnitAction,
    ) : WorkerOperation

    @Serializable @SerialName("choose_religious_beliefs")
    data class ChooseReligiousBeliefs(
        val snapshot: String,
        val actorCivilizationId: String,
        val beliefNames: List<String>,
        val religionIconName: String? = null,
        val religionDisplayName: String? = null,
    ) : WorkerOperation

    @Serializable @SerialName("offer_trade")
    data class OfferTrade(
        val snapshot: String,
        val actorCivilizationId: String,
        val otherCivilizationId: String,
        val trade: ProjectedTrade,
    ) : WorkerOperation

    @Serializable @SerialName("retract_trade_offer")
    data class RetractTradeOffer(
        val snapshot: String,
        val actorCivilizationId: String,
        val otherCivilizationId: String,
    ) : WorkerOperation

    @Serializable @SerialName("accept_trade")
    data class AcceptTrade(val snapshot: String, val actorCivilizationId: String, val requestId: String) : WorkerOperation

    @Serializable @SerialName("decline_trade")
    data class DeclineTrade(val snapshot: String, val actorCivilizationId: String, val requestId: String) : WorkerOperation

    @Serializable @SerialName("counter_trade")
    data class CounterTrade(val snapshot: String, val actorCivilizationId: String, val requestId: String, val trade: ProjectedTrade) : WorkerOperation

    @Serializable @SerialName("declare_war")
    data class DeclareWar(val snapshot: String, val actorCivilizationId: String, val otherCivilizationId: String) : WorkerOperation
    @Serializable @SerialName("denounce_civilization")
    data class DenounceCivilization(val snapshot: String, val actorCivilizationId: String, val otherCivilizationId: String) : WorkerOperation
    @Serializable @SerialName("offer_friendship")
    data class OfferFriendship(val snapshot: String, val actorCivilizationId: String, val otherCivilizationId: String) : WorkerOperation
    @Serializable @SerialName("make_diplomatic_demand")
    data class MakeDiplomaticDemand(val snapshot: String, val actorCivilizationId: String, val otherCivilizationId: String, val demand: DiplomaticDemand) : WorkerOperation
    @Serializable @SerialName("respond_to_diplomatic_prompt")
    data class RespondToDiplomaticPrompt(val snapshot: String, val actorCivilizationId: String, val promptId: String, val accept: Boolean) : WorkerOperation
    @Serializable @SerialName("respond_to_city_state_protection_prompt")
    data class RespondToCityStateProtectionPrompt(val snapshot: String, val actorCivilizationId: String, val promptId: String, val response: CityStateProtectionResponse) : WorkerOperation

    @Serializable @SerialName("gift_city_state_gold")
    data class GiftCityStateGold(val snapshot: String, val actorCivilizationId: String, val cityStateCivilizationId: String, val amount: Int) : WorkerOperation
    @Serializable @SerialName("set_city_state_protection")
    data class SetCityStateProtection(val snapshot: String, val actorCivilizationId: String, val cityStateCivilizationId: String, val protect: Boolean) : WorkerOperation
    @Serializable @SerialName("demand_city_state_tribute")
    data class DemandCityStateTribute(val snapshot: String, val actorCivilizationId: String, val cityStateCivilizationId: String, val worker: Boolean) : WorkerOperation
    @Serializable @SerialName("gift_city_state_improvement")
    data class GiftCityStateImprovement(val snapshot: String, val actorCivilizationId: String, val cityStateCivilizationId: String, val x: Int, val y: Int, val improvementName: String) : WorkerOperation
    @Serializable @SerialName("negotiate_city_state_peace")
    data class NegotiateCityStatePeace(val snapshot: String, val actorCivilizationId: String, val cityStateCivilizationId: String) : WorkerOperation
    @Serializable @SerialName("marry_city_state")
    data class MarryCityState(val snapshot: String, val actorCivilizationId: String, val cityStateCivilizationId: String) : WorkerOperation
    @Serializable @SerialName("move_spy")
    data class MoveSpy(val snapshot: String, val actorCivilizationId: String, val spyName: String, val cityId: String?) : WorkerOperation
    @Serializable @SerialName("set_spy_coup")
    data class SetSpyCoup(val snapshot: String, val actorCivilizationId: String, val spyName: String, val enabled: Boolean) : WorkerOperation
    @Serializable @SerialName("resolve_event_choice")
    data class ResolveEventChoice(val snapshot: String, val actorCivilizationId: String, val promptId: String, val choiceId: String) : WorkerOperation
    @Serializable @SerialName("gift_unit")
    data class GiftUnit(val snapshot: String, val actorCivilizationId: String, val unitId: Int) : WorkerOperation
    @Serializable @SerialName("add_unit_to_capital_project")
    data class AddUnitToCapitalProject(
        val snapshot: String,
        val actorCivilizationId: String,
        val unitId: Int,
    ) : WorkerOperation
    @Serializable @SerialName("transform_unit")
    data class TransformUnit(val snapshot: String, val actorCivilizationId: String, val unitId: Int, val actionId: String) : WorkerOperation
    @Serializable @SerialName("trigger_unit_unique")
    data class TriggerUnitUnique(val snapshot: String, val actorCivilizationId: String, val unitId: Int, val actionId: String) : WorkerOperation
    @Serializable @SerialName("create_instant_improvement")
    data class CreateInstantImprovement(val snapshot: String, val actorCivilizationId: String, val unitId: Int, val actionId: String) : WorkerOperation

    @Serializable @SerialName("set_city_tile_assignment")
    data class SetCityTileAssignment(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val x: Int,
        val y: Int,
        val assignment: CityTileAssignment,
    ) : WorkerOperation

    @Serializable @SerialName("set_specialist_count")
    data class SetSpecialistCount(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val specialistName: String,
        val count: Int,
    ) : WorkerOperation

    @Serializable @SerialName("set_manual_specialists")
    data class SetManualSpecialists(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val enabled: Boolean,
    ) : WorkerOperation

    @Serializable @SerialName("reset_citizens")
    data class ResetCitizens(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
    ) : WorkerOperation

    @Serializable @SerialName("set_avoid_growth")
    data class SetAvoidGrowth(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val enabled: Boolean,
    ) : WorkerOperation

    @Serializable @SerialName("set_citizen_focus")
    data class SetCitizenFocus(
        val snapshot: String,
        val actorCivilizationId: String,
        val cityId: String,
        val focus: CitizenFocus,
    ) : WorkerOperation

    @Serializable @SerialName("set_research_path")
    data class SetResearchPath(
        val snapshot: String,
        val actorCivilizationId: String,
        val technologyName: String,
        val append: Boolean,
    ) : WorkerOperation

    @Serializable @SerialName("manage_research_queue")
    data class ManageResearchQueue(
        val snapshot: String,
        val actorCivilizationId: String,
        val technologyName: String,
        val queueIndex: Int,
        val action: com.unciv.logic.multiplayer.authoritative.ResearchQueueAction,
    ) : WorkerOperation

    @Serializable @SerialName("adopt_policy")
    data class AdoptPolicy(
        val snapshot: String,
        val actorCivilizationId: String,
        val policyName: String,
    ) : WorkerOperation

    @Serializable @SerialName("choose_free_technology")
    data class ChooseFreeTechnology(
        val snapshot: String,
        val actorCivilizationId: String,
        val technologyName: String,
    ) : WorkerOperation

    @Serializable @SerialName("acknowledge_research_completion")
    data class AcknowledgeResearchCompletion(
        val snapshot: String,
        val actorCivilizationId: String,
        val promptId: String,
    ) : WorkerOperation

    @Serializable @SerialName("project_state")
    data class ProjectState(
        val snapshot: String,
        val actorCivilizationId: String,
    ) : WorkerOperation

    @Serializable @SerialName("project_spectator_state")
    data class ProjectSpectatorState(val snapshot: String) : WorkerOperation
}

@Serializable
data class WorkerResponse(
    val protocolVersion: Int = EngineWorkerProtocol.VERSION,
    val serverTimeMillis: Long? = null,
    val engineBuild: String? = null,
    val installedRulesets: List<WorkerRuleset>? = null,
    val snapshot: String? = null,
    val canonicalStateHash: String? = null,
    val actorCivilizationId: String? = null,
    val playerProjection: PlayerProjection? = null,
    val spectatorProjection: SpectatorProjection? = null,
    val error: WorkerError? = null,
)

@Serializable
data class WorkerError(val code: String, val message: String)

class AuthoritativeEngineWorker {
    fun execute(request: WorkerRequest): WorkerResponse = try {
        require(request.protocolVersion == EngineWorkerProtocol.VERSION) { "Unsupported protocol version" }
        if (request.operation is WorkerOperation.Handshake) return WorkerResponse(
            engineBuild = InstalledRulesetCatalog.engineBuild,
            installedRulesets = InstalledRulesetCatalog.all(),
        )
        val actorId = requireNotNull(request.actorId) { "Execution requires an authenticated actor" }
        val serverTimeMillis = requireNotNull(request.serverTimeMillis) {
            "Execution requires a server-controlled timestamp"
        }
        val manifest = requireNotNull(request.rulesetManifest) { "Execution requires a ruleset manifest" }
        InstalledRulesetCatalog.requireAvailable(manifest)
        val engine = HeadlessGameEngine(GameExecutionContext.authoritative(
            actorId = actorId,
            rulesetManifest = manifest.toCore(),
            canonicalGameId = (request.operation as? WorkerOperation.CreateGame)?.gameId,
            clockMillis = { serverTimeMillis },
        ))
        when (val operation = request.operation) {
            WorkerOperation.Handshake -> error("Handshake was not handled")
            is WorkerOperation.CreateGame -> {
                require(UUID.fromString(operation.gameId).toString() == operation.gameId.lowercase()) {
                    "Canonical game ID must be a normalized UUID"
                }
                val setup = operation.setup.materialize(manifest, actorId, operation.serverSeed)
                val owner = setup.gameParameters.players.firstOrNull()
                    ?: error("Game setup requires at least one player")
                require(setup.gameParameters.baseRuleset == manifest.baseRuleset.name) {
                    "Setup base ruleset does not match the pinned manifest"
                }
                require(setup.gameParameters.mods == manifest.mods.map { it.name }.toSet()) {
                    "Setup mods do not match the pinned manifest"
                }
                val result = engine.createGame(setup)
                val ownerCivilization = result.game.civilizations.singleOrNull {
                    it.playerId == actorId
                } ?: error("GameStarter did not assign the authenticated owner")
                responseForGame(engine, result.game, ownerCivilization.civID)
            }
            is WorkerOperation.AssignPlayer -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val assignment = engine.assignPlayer(game)
                responseForGame(engine, assignment.result.game, assignment.civilizationId)
            }
            is WorkerOperation.EndTurn -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.endTurn(game, operation.actorCivilizationId)
                responseForGame(engine, result.game)
            }
            is WorkerOperation.Resign -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.resign(game, operation.actorCivilizationId)
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ForceResign -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val forced = engine.forceResign(game, operation.actorCivilizationId)
                responseForGame(engine, forced.result.game, forced.civilizationId)
            }
            is WorkerOperation.KickPlayer -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.kickPlayer(
                    game,
                    operation.actorCivilizationId,
                    operation.targetCivilizationId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.MoveUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.moveUnit(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    HexCoord(operation.destinationX, operation.destinationY),
                    operation.escortUnitId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.MoveUnitToward -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.moveUnitToward(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    HexCoord(operation.destinationX, operation.destinationY),
                    operation.escortUnitId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.CancelUnitMovementOrder -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.cancelUnitMovementOrder(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetUnitExploration -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setUnitExploration(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.enabled,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetUnitAutomation -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setUnitAutomation(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.enabled,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetUnitPosture -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setUnitPosture(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.posture,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.DisbandUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.disbandUnit(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.PillageTile -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.pillageTile(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.FoundCity -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.foundCity(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ParadropUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.paradropUnit(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.destinationX,
                    operation.destinationY,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.AttackWithUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.attackWithUnit(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.targetX,
                    operation.targetY,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.BombardWithCity -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.bombardWithCity(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.targetX,
                    operation.targetY,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.LaunchNuclearStrike -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.launchNuclearStrike(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.targetX,
                    operation.targetY,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.AirSweep -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.airSweep(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.targetX,
                    operation.targetY,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.UpgradeUnits -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.upgradeUnits(
                    game,
                    operation.actorCivilizationId,
                    operation.unitIds,
                    operation.targetUnitName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.PromoteUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.promoteUnit(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.promotionNames,
                    operation.saveAsCityDefault,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetCityUnitPromotionPreference -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setCityUnitPromotionPreference(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.baseUnitName,
                    operation.enabled,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.RenameUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.renameUnit(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.instanceName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetTileImprovementOrder -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setTileImprovementOrder(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.improvementName,
                    operation.queuedImprovementName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetRoadConnectionOrder -> {
                val game = engine.loadSnapshot(operation.snapshot)
                require((operation.destinationX == null) == (operation.destinationY == null)) {
                    "Road destination coordinates must both be present or absent"
                }
                val destination = operation.destinationX?.let {
                    com.unciv.logic.map.HexCoord(it, operation.destinationY!!)
                }
                val result = engine.setRoadConnectionOrder(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    destination,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SwapUnits -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.swapUnits(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    HexCoord(operation.destinationX, operation.destinationY),
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.QueueConstruction -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.queueConstruction(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.constructionName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.QueueConstructionAtTile -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.queueConstructionAtTile(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.constructionName,
                    HexCoord(operation.x, operation.y),
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetPerpetualConstruction -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setPerpetualConstruction(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.constructionName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.RemoveConstruction -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.removeConstruction(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.queueIndex,
                    operation.expectedConstructionName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.MoveConstruction -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.moveConstruction(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.fromIndex,
                    operation.toIndex,
                    operation.expectedConstructionName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ManageConstructionQueues -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.manageConstructionQueues(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.constructionName,
                    operation.queueIndex,
                    operation.action,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.PurchaseConstruction -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.purchaseConstruction(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.constructionName,
                    operation.currencyName,
                    operation.queueIndex,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.PurchaseConstructionAtTile -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.purchaseConstructionAtTile(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.constructionName,
                    operation.currencyName,
                    HexCoord(operation.x, operation.y),
                    operation.queueIndex,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.BuyCityTile -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.buyCityTile(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    HexCoord(operation.x, operation.y),
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.BuyCityTileBatch -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.buyCityTileBatch(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.ring,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SellBuilding -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.sellBuilding(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.buildingName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetCityGovernance -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setCityGovernance(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.action,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ResolveCityDisposition -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.resolveCityDisposition(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.action,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.CastDiplomaticVote -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.castDiplomaticVote(
                    game,
                    operation.actorCivilizationId,
                    operation.candidateCivilizationId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ChooseGreatPerson -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.chooseGreatPerson(
                    game,
                    operation.actorCivilizationId,
                    operation.unitName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.UseReligiousUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.useReligiousUnit(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                    operation.action,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.UseGreatPersonUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.useGreatPersonUnit(
                    game, operation.actorCivilizationId, operation.unitId, operation.action,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.GiftUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.giftUnit(game, operation.actorCivilizationId, operation.unitId)
                responseForGame(engine, result.game)
            }
            is WorkerOperation.AddUnitToCapitalProject -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.addUnitToCapitalProject(
                    game,
                    operation.actorCivilizationId,
                    operation.unitId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.TransformUnit -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.transformUnit(
                    game, operation.actorCivilizationId, operation.unitId, operation.actionId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.TriggerUnitUnique -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.triggerUnitUnique(
                    game, operation.actorCivilizationId, operation.unitId, operation.actionId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.CreateInstantImprovement -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.createInstantImprovement(
                    game, operation.actorCivilizationId, operation.unitId, operation.actionId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ChooseReligiousBeliefs -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.chooseReligiousBeliefs(
                    game,
                    operation.actorCivilizationId,
                    operation.beliefNames,
                    operation.religionIconName,
                    operation.religionDisplayName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.OfferTrade -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.offerTrade(game, operation.actorCivilizationId, operation.otherCivilizationId, operation.trade).game)
            }
            is WorkerOperation.RetractTradeOffer -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.retractTradeOffer(game, operation.actorCivilizationId, operation.otherCivilizationId).game)
            }
            is WorkerOperation.AcceptTrade -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.acceptTrade(game, operation.actorCivilizationId, operation.requestId).game)
            }
            is WorkerOperation.DeclineTrade -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.declineTrade(game, operation.actorCivilizationId, operation.requestId).game)
            }
            is WorkerOperation.CounterTrade -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.counterTrade(game, operation.actorCivilizationId, operation.requestId, operation.trade).game)
            }
            is WorkerOperation.DeclareWar -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.declareWar(game, operation.actorCivilizationId, operation.otherCivilizationId).game)
            }
            is WorkerOperation.DenounceCivilization -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.denounceCivilization(game, operation.actorCivilizationId, operation.otherCivilizationId).game)
            }
            is WorkerOperation.OfferFriendship -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.offerFriendship(game, operation.actorCivilizationId, operation.otherCivilizationId).game)
            }
            is WorkerOperation.MakeDiplomaticDemand -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.makeDiplomaticDemand(game, operation.actorCivilizationId, operation.otherCivilizationId, operation.demand).game)
            }
            is WorkerOperation.RespondToDiplomaticPrompt -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.respondToDiplomaticPrompt(game, operation.actorCivilizationId, operation.promptId, operation.accept).game)
            }
            is WorkerOperation.RespondToCityStateProtectionPrompt -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.respondToCityStateProtectionPrompt(game, operation.actorCivilizationId, operation.promptId, operation.response).game)
            }
            is WorkerOperation.GiftCityStateGold -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.giftCityStateGold(game, operation.actorCivilizationId, operation.cityStateCivilizationId, operation.amount).game)
            }
            is WorkerOperation.SetCityStateProtection -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.setCityStateProtection(game, operation.actorCivilizationId, operation.cityStateCivilizationId, operation.protect).game)
            }
            is WorkerOperation.DemandCityStateTribute -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.demandCityStateTribute(game, operation.actorCivilizationId, operation.cityStateCivilizationId, operation.worker).game)
            }
            is WorkerOperation.GiftCityStateImprovement -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.giftCityStateImprovement(game, operation.actorCivilizationId, operation.cityStateCivilizationId, operation.x, operation.y, operation.improvementName).game)
            }
            is WorkerOperation.NegotiateCityStatePeace -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.negotiateCityStatePeace(game, operation.actorCivilizationId, operation.cityStateCivilizationId).game)
            }
            is WorkerOperation.MarryCityState -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.marryCityState(game, operation.actorCivilizationId, operation.cityStateCivilizationId).game)
            }
            is WorkerOperation.MoveSpy -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.moveSpy(game, operation.actorCivilizationId, operation.spyName, operation.cityId).game)
            }
            is WorkerOperation.SetSpyCoup -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.setSpyCoup(game, operation.actorCivilizationId, operation.spyName, operation.enabled).game)
            }
            is WorkerOperation.ResolveEventChoice -> {
                val game = engine.loadSnapshot(operation.snapshot)
                responseForGame(engine, engine.resolveEventChoice(game, operation.actorCivilizationId, operation.promptId, operation.choiceId).game)
            }
            is WorkerOperation.SetCityTileAssignment -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setCityTileAssignment(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    HexCoord(operation.x, operation.y),
                    operation.assignment,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetSpecialistCount -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setSpecialistCount(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.specialistName,
                    operation.count,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetManualSpecialists -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setManualSpecialists(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.enabled,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ResetCitizens -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.resetCitizens(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetAvoidGrowth -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setAvoidGrowth(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.enabled,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetCitizenFocus -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setCitizenFocus(
                    game,
                    operation.actorCivilizationId,
                    operation.cityId,
                    operation.focus,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.SetResearchPath -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.setResearchPath(
                    game,
                    operation.actorCivilizationId,
                    operation.technologyName,
                    operation.append,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ManageResearchQueue -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.manageResearchQueue(
                    game,
                    operation.actorCivilizationId,
                    operation.technologyName,
                    operation.queueIndex,
                    operation.action,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.AdoptPolicy -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.adoptPolicy(
                    game,
                    operation.actorCivilizationId,
                    operation.policyName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ChooseFreeTechnology -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.chooseFreeTechnology(
                    game,
                    operation.actorCivilizationId,
                    operation.technologyName,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.AcknowledgeResearchCompletion -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val result = engine.acknowledgeResearchCompletion(
                    game,
                    operation.actorCivilizationId,
                    operation.promptId,
                )
                responseForGame(engine, result.game)
            }
            is WorkerOperation.ProjectState -> {
                val game = engine.loadSnapshot(operation.snapshot)
                val projection = engine.playerProjection(game, operation.actorCivilizationId)
                WorkerResponse(playerProjection = projection)
            }
            is WorkerOperation.ProjectSpectatorState -> {
                val game = engine.loadSnapshot(operation.snapshot)
                WorkerResponse(spectatorProjection = engine.spectatorProjection(game))
            }
        }.copy(serverTimeMillis = serverTimeMillis)
    } catch (exception: Exception) {
        WorkerResponse(error = WorkerError("engine_rejected", exception.message ?: "Engine execution failed"))
    }

    private fun WorkerRulesetManifest.toCore() = RulesetManifest(
        engineBuild,
        ContentAddressedRuleset(baseRuleset.name, baseRuleset.sha256),
        mods.map { ContentAddressedRuleset(it.name, it.sha256) },
    )

    /** Hash exactly the bytes returned over the worker protocol. Serializing a
     * mutable game twice is not a valid canonical-hash operation. */
    private fun responseForGame(
        engine: HeadlessGameEngine,
        game: com.unciv.logic.GameInfo,
        actorCivilizationId: String? = null,
    ): WorkerResponse {
        val snapshot = engine.serializeSnapshot(game)
        val hash = sha256(snapshot.toByteArray(Charsets.UTF_8))
        return WorkerResponse(
            snapshot = snapshot,
            canonicalStateHash = hash,
            actorCivilizationId = actorCivilizationId,
        )
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

/** Computes content identities over the exact ruleset JSON bytes visible to
 * this worker. Paths are sorted and length-framed so concatenation cannot be
 * ambiguous. Media is intentionally excluded from gameplay identity. */
object InstalledRulesetCatalog {
    val engineBuild: String get() = UncivGame.VERSION.toSerializeString()

    fun all(): List<WorkerRuleset> = RulesetCache.keys.sorted().map { named(it) }

    fun requireAvailable(manifest: WorkerRulesetManifest) {
        require(manifest.engineBuild == engineBuild) {
            "Pinned engine build is unavailable"
        }
        val requested = listOf(manifest.baseRuleset) + manifest.mods
        require(requested.map { it.name }.distinct().size == requested.size) {
            "Ruleset manifest contains duplicate names"
        }
        requested.forEach { expected ->
            val installed = named(expected.name)
            require(installed.sha256.equals(expected.sha256, ignoreCase = true)) {
                "Pinned ruleset content is unavailable: ${expected.name}"
            }
        }
    }

    fun named(name: String): WorkerRuleset {
        val ruleset = RulesetCache[name] ?: error("Pinned ruleset is unavailable: $name")
        val jsonFolder = ruleset.folderLocation?.child("jsons") ?: FileHandle("jsons/$name")
        require(jsonFolder.exists() && jsonFolder.isDirectory) { "Ruleset JSON is unavailable: $name" }
        return WorkerRuleset(name, hashDirectory(jsonFolder))
    }

    internal fun hashDirectory(root: FileHandle): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = collectFiles(root).sortedBy { it.first }
        require(files.isNotEmpty()) { "Ruleset JSON directory is empty" }
        files.forEach { (relativePath, file) ->
            val path = relativePath.replace('\\', '/').toByteArray(Charsets.UTF_8)
            val bytes = file.readBytes()
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun collectFiles(root: FileHandle, prefix: String = ""): List<Pair<String, FileHandle>> =
        root.list().flatMap { child ->
            val relative = if (prefix.isEmpty()) child.name() else "$prefix/${child.name()}"
            if (child.isDirectory) collectFiles(child, relative) else listOf(relative to child)
        }
}

class LoopbackEngineWorkerServer(private val worker: AuthoritativeEngineWorker = AuthoritativeEngineWorker()) {
    fun serve(port: Int) = ServerSocket(port, 50, java.net.InetAddress.getLoopbackAddress()).use { server ->
        while (true) {
            server.accept().use { socket ->
                // A readiness probe or a malformed local peer must not kill
                // the long-lived worker process. Valid requests receive their
                // structured engine result; invalid frames are simply dropped.
                runCatching { serveConnection(socket) }
            }
        }
    }

    private fun serveConnection(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val frameSize = input.readInt()
        require(frameSize in 1..EngineWorkerProtocol.maxFrameBytes) { "Invalid frame length" }
        val request = EngineWorkerProtocol.json.decodeFromString<WorkerRequest>(input.readNBytes(frameSize).decodeToString())
        val response = EngineWorkerProtocol.json.encodeToString(WorkerResponse.serializer(), worker.execute(request)).encodeToByteArray()
        output.writeInt(response.size)
        output.write(response)
        output.flush()
    }
}
