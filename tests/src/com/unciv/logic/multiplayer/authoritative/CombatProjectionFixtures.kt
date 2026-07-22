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

internal fun testNuclearTarget(x: Int, y: Int) = ProjectedNuclearTarget(
    x,
    y,
    blastRadius = 2,
    effectDisclosure = ProjectedNuclearEffectDisclosure.HiddenUntilCommit,
)

internal fun testAirSweepTarget(x: Int, y: Int) = ProjectedAirSweepTarget(
    x,
    y,
    attackerBaseStrength = 20,
    attackerModifiers = emptyList(),
    attackerHealth = 100,
    attackerMaxHealth = 100,
    interceptorDisclosure = ProjectedAirSweepInterceptorDisclosure.HiddenUntilCommit,
)
