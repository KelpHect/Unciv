package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.roundToInt

/** Builds bounded display-only combat estimates from canonical worker state. */
internal object CombatPreviewProjection {
    private const val MAX_MODIFIERS_PER_COMBATANT = 64
    private const val MAX_MODIFIER_LABEL_LENGTH = 200

    fun build(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile): ProjectedCombatPreview {
        val outcome = captureOutcome(attacker, defender)
        val attackerHealth = attacker.getHealth()
        val defenderHealth = defender.getHealth()
        val remaining = if (outcome == null) remainingHealth(attacker, defender, tileToAttackFrom)
            else null
        return ProjectedCombatPreview(
            attackerBaseStrength = attacker.getAttackingStrength(defender),
            defenderBaseStrength = defender.getDefendingStrength(attacker),
            attackerEffectiveStrength = BattleDamage
                .getAttackingStrength(attacker, defender, tileToAttackFrom).roundToInt(),
            defenderEffectiveStrength = BattleDamage
                .getDefendingStrength(attacker, defender, tileToAttackFrom).roundToInt(),
            attackerModifiers = modifiers(
                BattleDamage.getAttackModifiers(attacker, defender, tileToAttackFrom),
            ),
            defenderModifiers = if (defender is MapUnitCombatant) modifiers(
                BattleDamage.getDefenceModifiers(attacker, defender, tileToAttackFrom),
            ) else emptyList(),
            attackerHealth = attackerHealth,
            attackerMaxHealth = attacker.getMaxHealth(),
            defenderHealth = defenderHealth,
            defenderMaxHealth = defender.getMaxHealth(),
            attackerMinRemainingHealth = remaining?.attackerMin,
            attackerMaxRemainingHealth = remaining?.attackerMax,
            defenderMinRemainingHealth = remaining?.defenderMin,
            defenderMaxRemainingHealth = remaining?.defenderMax,
            outcome = outcome,
        )
    }

    private fun captureOutcome(attacker: ICombatant, defender: ICombatant): ProjectedCombatOutcome? {
        if (!attacker.isMelee() || !(defender.isCivilian() || defender is CityCombatant && defender.isDefeated()))
            return null
        if (defender is CityCombatant) return ProjectedCombatOutcome.Occupied
        val unit = (defender as MapUnitCombatant).unit
        return if (unit.hasUnique(UniqueType.Uncapturable)) ProjectedCombatOutcome.NoEstimate
            else ProjectedCombatOutcome.Captured
    }

    private fun remainingHealth(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile,
    ): RemainingHealth {
        val attackerHealth = attacker.getHealth()
        val defenderHealth = defender.getHealth()
        return RemainingHealth(
            attackerMin = max(attackerHealth - BattleDamage.calculateDamageToAttacker(
                attacker, defender, tileToAttackFrom, 1f,
            ), 0),
            attackerMax = max(attackerHealth - BattleDamage.calculateDamageToAttacker(
                attacker, defender, tileToAttackFrom, 0f,
            ), 0),
            defenderMin = max(defenderHealth - BattleDamage.calculateDamageToDefender(
                attacker, defender, tileToAttackFrom, 1f,
            ), 0),
            defenderMax = max(defenderHealth - BattleDamage.calculateDamageToDefender(
                attacker, defender, tileToAttackFrom, 0f,
            ), 0),
        )
    }

    private fun modifiers(values: Map<String, Int>) = values.entries.asSequence()
        .groupBy { it.key.take(MAX_MODIFIER_LABEL_LENGTH) }
        .map { (label, matching) ->
            val combined = matching.sumOf { it.value.toLong() }
                .coerceIn(Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            ProjectedCombatModifier(label, combined.toInt())
        }
        .sortedWith(compareBy<ProjectedCombatModifier> { it.label }.thenBy { it.percent })
        .take(MAX_MODIFIERS_PER_COMBATANT)
        .toList()

    private data class RemainingHealth(
        val attackerMin: Int,
        val attackerMax: Int,
        val defenderMin: Int,
        val defenderMax: Int,
    )
}
