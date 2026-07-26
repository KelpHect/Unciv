package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectedUnit(
    val id: Int,
    val civilizationId: String,
    val name: String,
    val x: Int,
    val y: Int,
    val health: Int,
    val currentMovement: Float,
    val movementDestinationX: Int? = null,
    val movementDestinationY: Int? = null,
    val movementEscortUnitId: Int? = null,
    val automated: Boolean = false,
    val exploring: Boolean = false,
    val posture: UnitPosture? = null,
    val promotions: List<String> = emptyList(),
    val promotionXp: Int? = null,
    val nextPromotionXp: Int? = null,
    val availablePromotions: List<String> = emptyList(),
    val instanceName: String? = null,
    val availablePostures: List<UnitPosture> = emptyList(),
    val canDisband: Boolean = false,
    val canPillage: Boolean = false,
    val canFoundCity: Boolean = false,
    val canRename: Boolean = false,
    val paradropDestinations: List<ProjectedMovementDestination> = emptyList(),
    val availableUpgradeTargets: List<ProjectedUnitUpgradeTarget> = emptyList(),
    val moveTowardDestinations: List<ProjectedMovementDestination> = emptyList(),
    val availableImprovementOrders: List<ProjectedImprovementOrderChoice> = emptyList(),
    val availableRoadDestinations: List<ProjectedMovementDestination> = emptyList(),
    val improvementOrder: List<ProjectedImprovementOrderEntry> = emptyList(),
    val roadConnectionDestinationX: Int? = null,
    val roadConnectionDestinationY: Int? = null,
    val roadConnectionPath: List<ProjectedRoadPathTile> = emptyList(),
    val availableReligiousActions: List<ReligiousUnitAction> = emptyList(),
    val availableGreatPersonActions: List<GreatPersonUnitAction> = emptyList(),
    val canGift: Boolean = false,
    val capitalProjectName: String? = null,
    val availableInstantImprovementActions: List<ProjectedInstantImprovementAction> = emptyList(),
    val availableTransformActions: List<ProjectedUnitTransformAction> = emptyList(),
    val availableTriggerActions: List<ProjectedUnitTriggerAction> = emptyList(),
    val moveDestinations: List<ProjectedMovementDestination> = emptyList(),
    val swapDestinations: List<ProjectedMovementDestination> = emptyList(),
    val attackTargets: List<ProjectedAttackTarget> = emptyList(),
    val nuclearTargetCandidates: List<ProjectedNuclearTarget> = emptyList(),
    val airSweepTargets: List<ProjectedAirSweepTarget> = emptyList(),
)

@Serializable
data class ProjectedMovementDestination(val x: Int, val y: Int)

@Serializable
data class ProjectedUnitUpgradeTarget(
    val targetUnitName: String,
    val goldCost: Int,
)

@Serializable
data class ProjectedImprovementOrderChoice(
    val improvementName: String?,
    val queuedImprovementName: String?,
)

@Serializable
data class ProjectedTargetCoordinate(val x: Int, val y: Int)

@Serializable
data class ProjectedNuclearTarget(
    val x: Int,
    val y: Int,
    val blastRadius: Int,
    val effectDisclosure: ProjectedNuclearEffectDisclosure,
)

@Serializable
enum class ProjectedNuclearEffectDisclosure {
    @SerialName("hidden_until_commit") HiddenUntilCommit,
}

@Serializable
data class ProjectedAirSweepTarget(
    val x: Int,
    val y: Int,
    val attackerBaseStrength: Int,
    val attackerModifiers: List<ProjectedCombatModifier>,
    val attackerHealth: Int,
    val attackerMaxHealth: Int,
    val interceptorDisclosure: ProjectedAirSweepInterceptorDisclosure,
)

@Serializable
enum class ProjectedAirSweepInterceptorDisclosure {
    @SerialName("hidden_until_commit") HiddenUntilCommit,
}

@Serializable
data class ProjectedAttackTarget(
    val x: Int,
    val y: Int,
    val attackFromX: Int,
    val attackFromY: Int,
    val preview: ProjectedCombatPreview,
)

@Serializable
data class ProjectedBombardTarget(
    val x: Int,
    val y: Int,
    val preview: ProjectedCombatPreview,
)

@Serializable
data class ProjectedCombatPreview(
    val attackerBaseStrength: Int,
    val defenderBaseStrength: Int,
    val attackerEffectiveStrength: Int,
    val defenderEffectiveStrength: Int,
    val attackerModifiers: List<ProjectedCombatModifier>,
    val defenderModifiers: List<ProjectedCombatModifier>,
    val attackerHealth: Int,
    val attackerMaxHealth: Int,
    val defenderHealth: Int,
    val defenderMaxHealth: Int,
    val attackerMinRemainingHealth: Int? = null,
    val attackerMaxRemainingHealth: Int? = null,
    val defenderMinRemainingHealth: Int? = null,
    val defenderMaxRemainingHealth: Int? = null,
    val outcome: ProjectedCombatOutcome? = null,
)

@Serializable
data class ProjectedCombatModifier(val label: String, val percent: Int)

@Serializable
enum class ProjectedCombatOutcome {
    @SerialName("captured") Captured,
    @SerialName("occupied") Occupied,
    @SerialName("no_estimate") NoEstimate,
}

@Serializable
data class ProjectedUnitTransformAction(val actionId: String, val targetUnitName: String)

@Serializable
data class ProjectedUnitTriggerAction(val actionId: String, val title: String)

@Serializable
data class ProjectedInstantImprovementAction(val actionId: String, val title: String)

@Serializable
data class ProjectedImprovementOrderEntry(
    val improvementName: String,
    val turnsRemaining: Int,
)

@Serializable
data class ProjectedRoadPathTile(val x: Int, val y: Int)
