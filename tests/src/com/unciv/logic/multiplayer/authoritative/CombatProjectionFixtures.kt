package com.unciv.logic.multiplayer.authoritative

internal fun testCombatPreview() = ProjectedCombatPreview(
    attackerBaseStrength = 10,
    defenderBaseStrength = 8,
    attackerEffectiveStrength = 11,
    defenderEffectiveStrength = 9,
    attackerModifiers = emptyList(),
    defenderModifiers = emptyList(),
    attackerHealth = 100,
    attackerMaxHealth = 100,
    defenderHealth = 100,
    defenderMaxHealth = 100,
    attackerMinRemainingHealth = 80,
    attackerMaxRemainingHealth = 85,
    defenderMinRemainingHealth = 70,
    defenderMaxRemainingHealth = 75,
)
