package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.PlayerType.AI
import com.unciv.logic.civilization.PlayerType.Human
import com.unciv.logic.multiplayer.FriendList
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * This [Table] is used to pick or edit players information for new game creation.
 * Could be inserted to [NewGameScreen], or any other [BaseScreen]
 * which provides [GameSetupInfo] and [Ruleset].
 * Upon player changes updates property [gameParameters]. Also updates available nations when mod changes.
 * @param previousScreen A [Screen][BaseScreen] where the player table is inserted, should provide [GameSetupInfo] as property, updated when a player is added/deleted/changed
 * @param gameParameters contains info about number of players.
 * @param blockWidth sets a width for the Civ "blocks". If too small a third of the stage is used.
 */
class PlayerPickerTable(
    val previousScreen: IPreviousScreen,
    var gameParameters: GameParameters,
    blockWidth: Float = 0f
): Table() {
    val playerListTable = Table()
    private val friendList = FriendList()
    val civBlocksWidth = if (blockWidth <= 10f) previousScreen.stage.width / 3 - 5f else blockWidth
    private var randomNumberLabel: WrappableLabel? = null

    /** Locks player table for editing, currently unused, was previously used for scenarios and could be useful in the future. */
    var locked = false

    /** No random civilization is available, potentially used in the future during map editing. */
    var noRandom = false


    init {
        for (player in gameParameters.players)
            player.playerId = "" // This is to stop people from getting other users' IDs and cheating with them in multiplayer games

        top()
        gameParameters.shufflePlayerOrder = false
        add("Shuffle Civ Order at Start".toCheckBox(false) { gameParameters.shufflePlayerOrder = it }).padTop(5f).padBottom(5f).row()
        add(ScrollPane(playerListTable).apply { setOverscroll(false, false) }).width(civBlocksWidth)
        update()
        background = BaseScreen.skinStrings.getUiBackground("NewGameScreen/PlayerPickerTable", tintColor = BaseScreen.skinStrings.skinConfig.clearColor)
    }

    /**
     * Updates view of main player table. Used when mod picked or player changed.
     * Also sets desired civilization, that is preferable for human players.
     * @param desiredCiv desired civilization name
     */
    fun update(desiredCiv: String = "") {
        playerListTable.clear()
        normalizeAuthoritativePlayers()
        val gameBasics = previousScreen.ruleset // the mod picking changes this ruleset

        reassignRemovedModReferences()
        val newRulesetPlayableCivs = previousScreen.ruleset.nations
            .count { it.key != Constants.barbarians && !it.value.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
        if (gameParameters.players.size > newRulesetPlayableCivs)
            gameParameters.players = ArrayList(gameParameters.players.subList(0, newRulesetPlayableCivs))
        if (desiredCiv.isNotEmpty()) assignDesiredCiv(desiredCiv)

        for (player in gameParameters.players) {
            playerListTable.add(getPlayerTable(player)).width(civBlocksWidth).padBottom(20f).row()
        }

        val isRandomNumberOfPlayers = gameParameters.randomNumberOfPlayers
        if (isRandomNumberOfPlayers) {
            randomNumberLabel = WrappableLabel("", civBlocksWidth - 20f, Color.GOLD)
            playerListTable.add(randomNumberLabel).fillX().pad(0f, 10f, 20f, 10f).row()
            updateRandomNumberLabel()
        }

        if (!locked && gameParameters.players.size < gameBasics.nations.values.count { it.isMajorCiv }) {
            val addPlayerButton = "+".toLabel(ImageGetter.CHARCOAL, 30)
                .apply { this.setAlignment(Align.center) }
                .surroundWithCircle(50f)
                .onClick {
                    // no random mode - add first not spectator civ if still available
                    val player = if (noRandom || isRandomNumberOfPlayers) {
                        val availableCiv = getAvailablePlayerCivs().firstOrNull()
                        if (availableCiv != null) Player(availableCiv)
                        // Spectators can only be Humans
                        else Player(Constants.spectator, PlayerType.Human).apply { setNationTransient(gameBasics) }
                    } else Player()  // normal: add random AI
                    gameParameters.players.add(player)
                    update()
                }
            playerListTable.add(addPlayerButton).pad(10f)
        }

        // enable start game when at least one human player and they're not alone
        val humanPlayerCount = gameParameters.players.count { it.playerType == PlayerType.Human }
        val isValid = humanPlayerCount >= 1 && (isRandomNumberOfPlayers || gameParameters.players.size >= 2)
        (previousScreen as? PickerScreen)?.setRightSideButtonEnabled(isValid)
    }

    fun updateRandomNumberLabel() {
        randomNumberLabel?.run {
            val playerRange = if (gameParameters.minNumberOfPlayers == gameParameters.maxNumberOfPlayers) {
                gameParameters.minNumberOfPlayers.tr()
            } else {
                "${gameParameters.minNumberOfPlayers} - ${gameParameters.maxNumberOfPlayers}"
            }
            val numberOfExplicitPlayersText = if (gameParameters.players.size == 1) {
                "The number of players will be adjusted"
            } else {
                "These [${gameParameters.players.size}] players will be adjusted"
            }
            val text = "[$numberOfExplicitPlayersText] to [$playerRange] actual players by adding random AI's or by randomly omitting AI's."
            wrap = false
            align(Align.center)
            setText(text.tr())
            wrap = true
        }
    }

    /**
     * Reassigns removed mod references to random civilization
     */
    private fun reassignRemovedModReferences() {
        for (player in gameParameters.players) {
            if (!previousScreen.ruleset.nations.containsKey(player.chosenCiv)
                || previousScreen.ruleset.nations[player.chosenCiv]!!.isCityState
                || previousScreen.ruleset.nations[player.chosenCiv]!!.hasUnique(UniqueType.WillNotBeChosenForNewGames))
                player.chosenCiv = Constants.random
        }
    }

    /**
     * Assigns desired civilization for human players with 'random' choice
     * @param desiredCiv string containing desired civilization name
     */
    private fun assignDesiredCiv(desiredCiv: String) {
        // No auto-select if desiredCiv already used
        if (gameParameters.players.any { it.chosenCiv == desiredCiv }) return
        // Do auto-select, silently no-op if no suitable slot (human with 'random' choice)
        val player = gameParameters.players.firstOrNull { it.chosenCiv == Constants.random && it.playerType == Human }
        player?.chosenCiv = desiredCiv
        player?.setNationTransient(previousScreen.ruleset)
    }

    /**
     * Creates [Table] for single player containing clickable
     * player type button ("AI" or "Human"), nation [Table]
     * and "-" remove player button.*
     * @param player for which [Table] is generated
     * @return [Table] containing the all the elements
     */
    private fun getPlayerTable(player: Player): Table {
        val playerTable = Table()
        playerTable.pad(5f)
        playerTable.background = BaseScreen.skinStrings.getUiBackground(
            "NewGameScreen/PlayerPickerTable/PlayerTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.8f)
        )

        val nationTable = getNationTable(player)
        playerTable.add(nationTable).left()

        val playerTypeTextButton = player.playerType.name.toTextButton()
        playerTable.add(playerTypeTextButton).width(100f).pad(5f).right()
        fun updatePlayerTypeButtonEnabled() {
            // This could be written much shorter with logical operators - I think this is readable
            playerTypeTextButton.isEnabled = when {
                usesAuthoritativeCreation() -> false
                // Can always change AI to Human
                player.playerType == PlayerType.AI -> true
                // we cannot change Spectator player to AI type, robots not allowed to spectate :(
                player.chosenCiv == Constants.spectator -> false
                // In randomNumberOfPlayers mode, don't let the user choose random AI's
                gameParameters.randomNumberOfPlayers && player.chosenCiv == Constants.random -> false
                else -> true
            }
        }
        updatePlayerTypeButtonEnabled()

        nationTable.onClick {
            if (locked || usesAuthoritativeCreation() && player.playerType != Human) return@onClick
            val noRandom = noRandom ||
                    gameParameters.randomNumberOfPlayers && player.playerType == PlayerType.AI
            popupNationPicker(player, noRandom)
            updatePlayerTypeButtonEnabled()
        }
        playerTypeTextButton.onClick {
            player.playerType = if (player.playerType == AI) Human else AI
            update()
        }

        if (!locked && !(usesAuthoritativeCreation() && player.playerType == Human)) {
            playerTable.add("-".toLabel(ImageGetter.CHARCOAL, 30, Align.center)
                .surroundWithCircle(40f)
                .onClick {
                    gameParameters.players.remove(player)
                    update()
                }
            ).pad(5f).right()
        }

        return playerTable
    }

    private fun usesAuthoritativeCreation() =
        (previousScreen as? NewGameScreen)?.isAuthoritativeLobbySetup == true

    private fun normalizeAuthoritativePlayers() {
        if (!usesAuthoritativeCreation()) return
        gameParameters.players.removeAll { it.chosenCiv == Constants.spectator }
        if (gameParameters.players.isEmpty())
            gameParameters.players += Player(Constants.random, PlayerType.Human)
        val owner = gameParameters.players.firstOrNull { it.playerType == PlayerType.Human }
            ?: gameParameters.players.first()
        for (player in gameParameters.players) {
            player.playerId = ""
            if (player === owner) {
                player.playerType = PlayerType.Human
            } else {
                player.playerType = PlayerType.AI
                player.chosenCiv = Constants.random
            }
        }
    }

    /**
     * Creates clickable icon and nation name for some [Player].
     * @param player [Player] for which generated
     * @return [Table] containing nation icon and name
     */
    private fun getNationTable(player: Player): Table {
        val nationTable = Table()
        val nationImageName = previousScreen.ruleset.nations[player.chosenCiv]
        val nationImage =
            if (nationImageName == null)
                ImageGetter.getRandomNationPortrait(40f)
            else ImageGetter.getNationPortrait(nationImageName, 40f)
        nationTable.add(nationImage).pad(5f)
        nationTable.add(player.chosenCiv.toLabel(hideIcons = true)).pad(5f)
        nationTable.touchable = Touchable.enabled
        return nationTable
    }

    /**
     * Opens Nation picking popup with all nations,
     * currently available for [player] to choose, depending on current
     * ruleset and other players nation choice.
     * @param player current player
     */
    private fun popupNationPicker(player: Player, noRandom: Boolean) {
        NationPickerPopup(this, player, noRandom).open()
        update()
    }

    /**
     * Returns a list of available civilization for all players, according
     * to current ruleset, with exception of city states nations, spectator and barbarians.
     *
     * Skips nations already chosen by a player, unless parameter [dontSkipNation] says to keep a
     * specific one. That is used so the picker can be used to inspect and confirm the current selection.
     *
     * @return [Sequence] of available [Nation]s
     */
    internal fun getAvailablePlayerCivs(dontSkipNation: String? = null) =
        previousScreen.ruleset.nations.values.asSequence()
            .filter { it.isMajorCiv }
            .filterNot { it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
            .filter { it.name == dontSkipNation || gameParameters.players.none { player -> player.chosenCiv == it.name } }

    /**
     * Legacy friend-list filtering remains available to the standalone picker,
     * but new online games no longer expose player-ID assignment controls.
     */
    internal fun getAvailableFriends(): Sequence<FriendList.Friend> {
        val available = friendList.listOfFriends.toMutableList()
        for (player in gameParameters.players)
            available.remove(friendList.getFriendById(player.playerId))
        return available.asSequence()
    }

}
