package com.unciv.models.metadata

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.nation.Nation

class Player(
    var chosenCiv: String = Constants.random,
    var playerType: PlayerType = PlayerType.AI,
    var playerId: String = "",
    /** Per-player AI difficulty override. Empty uses the match's AI difficulty. */
    var aiDifficulty: String = "",
    /** Per-player AI personality override. Empty uses the nation's own personality. */
    var personality: String = ""
) : IsPartOfGameInfoSerialization {
    constructor() : this(Constants.random, PlayerType.AI, "")
    constructor(
        chosenNation: Nation,
        playerType: PlayerType = PlayerType.AI,
        playerId: String = "",
        aiDifficulty: String = "",
        personality: String = ""
    ): this(chosenNation.name, playerType, playerId, aiDifficulty, personality) {
            this.chosenNation = chosenNation
        }
    @Transient
    lateinit var chosenNation: Nation
    fun setNationTransient(ruleset: Ruleset) {
        chosenNation = ruleset.nations[chosenCiv]!!
    }
}
