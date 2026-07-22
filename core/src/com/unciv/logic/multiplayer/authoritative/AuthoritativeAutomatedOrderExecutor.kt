package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.civilization.Civilization

/** Executes player-owned persistent orders inside the authoritative worker.
 * The ordinary client forces this phase before ending a turn; v3 performs the
 * same shared Kotlin actions immediately before the canonical turn commit. */
internal object AuthoritativeAutomatedOrderExecutor {
    fun executePending(actor: Civilization) {
        if (actor.hasMovedAutomatedUnits) return
        actor.hasMovedAutomatedUnits = true
        actor.units.getCivUnits().forEach { unit -> unit.doAction() }
    }
}
