package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.MapSaver
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCreationMeaning
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCreationRetryState
import com.unciv.logic.multiplayer.authoritative.AuthoritativeLobbyConfiguration
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSetup
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.multiplayerscreens.AuthoritativeLobbyScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.coroutineScope
import kotlin.math.floor
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

class NewGameScreen(
    defaultGameSetupInfo: GameSetupInfo? = null,
    private val authoritativeCreationRetryState: AuthoritativeCreationRetryState =
        AuthoritativeCreationRetryState(),
    private val lobbyConfiguration: AuthoritativeLobbyConfiguration? = null,
): IPreviousScreen, PickerScreen(), RecreateOnResize {
    internal val isAuthoritativeLobbySetup get() = lobbyConfiguration != null

    override val gameSetupInfo = defaultGameSetupInfo ?: GameSetupInfo.fromSettings()
    override val ruleset = Ruleset()  // updateRuleset will clear and add
    private val newGameOptionsTable: GameOptionsTable
    internal val playerPickerTable: PlayerPickerTable
    private val mapOptionsTable: MapOptionsTable
    private var mapOptionsTableInitialized = false

    init {
        if (lobbyConfiguration != null)
            gameSetupInfo.gameParameters.apply {
                isOnlineMultiplayer = true
                baseRuleset = lobbyConfiguration.rulesetManifest.baseRuleset.name
                mods = lobbyConfiguration.rulesetManifest.mods
                    .mapTo(linkedSetOf()) { it.name }
            }
        else gameSetupInfo.gameParameters.isOnlineMultiplayer = false
        val isPortrait = isNarrowerThan4to3()

        tryUpdateRuleset(updateUI = false)  // must come before playerPickerTable so mod nations from fromSettings

        // remove the victory types which are not in the rule set (e.g. were in the recently disabled mod)
        gameSetupInfo.gameParameters.victoryTypes.removeAll { it !in ruleset.victories.keys }

        if (gameSetupInfo.gameParameters.victoryTypes.isEmpty())
            gameSetupInfo.gameParameters.victoryTypes.addAll(ruleset.victories.keys)

        rightSideButton.enable()  // now because PlayerPickerTable init might disable it again
        playerPickerTable = PlayerPickerTable(
            this, gameSetupInfo.gameParameters,
            if (isPortrait) stage.width - 20f else 0f
        )
        newGameOptionsTable = GameOptionsTable(
            this, isPortrait,
            updatePlayerPickerTable = { desiredCiv -> playerPickerTable.update(desiredCiv) },
            updatePlayerPickerRandomLabel = { playerPickerTable.updateRandomNumberLabel() }
        )
        mapOptionsTable = MapOptionsTable(this)
        mapOptionsTableInitialized = true
        closeButton.onActivation {
            mapOptionsTable.cancelBackgroundJobs()
            game.popScreen()
        }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)

        if (isPortrait) initPortrait()
        else initLandscape()
        bottomTable.background = skinStrings.getUiBackground("NewGameScreen/BottomTable", tintColor = skinStrings.skinConfig.clearColor)
        topTable.background = skinStrings.getUiBackground("NewGameScreen/TopTable", tintColor = skinStrings.skinConfig.clearColor)

        val horizontalGroup = HorizontalGroup().padBottom(5f).space(10f)
        rightSideGroup.addActorAt(0, horizontalGroup)

        if (UncivGame.Current.settings.lastGameSetup != null) {
            val resetToDefaultsButton = "Reset to defaults".toTextButton()
            resetToDefaultsButton.onClick {
                ConfirmPopup(
                    this,
                    "Are you sure you want to reset all game options to defaults?",
                    "Reset to defaults",
                ) {
                    val gameSetupInfo = GameSetupInfo().apply {
                        gameParameters.espionageEnabled = true
                    }
                    game.replaceCurrentScreen(
                        NewGameScreen(gameSetupInfo, lobbyConfiguration = lobbyConfiguration),
                    )
                }.open(true)
            }
            horizontalGroup.addActor(resetToDefaultsButton)
        }

        val startGameButton =
            (if (isAuthoritativeLobbySetup) "Create lobby" else "Start game!")
                .toTextButton().apply { color = Color.GREEN }
        startGameButton.onClick(this::startGameAvoidANRs)
        horizontalGroup.addActor(startGameButton)
        pickerPane.rightSideButton.remove()
    }

    private fun startGameAvoidANRs(){
        // Don't allow players to click the game while we're checking if it's ok
        Gdx.input.inputProcessor = null
        mapOptionsTable.cancelBackgroundJobs()
        Concurrency.run {  // even just *checking* can take time
            val errorMessage = getErrorMessage()
            if (errorMessage != null){
                Concurrency.runOnGLThread {
                    val errorPopup = Popup(this@NewGameScreen)
                    errorPopup.addGoodSizedLabel(errorMessage).row()
                    errorPopup.addCloseButton()
                    errorPopup.open()
                    Gdx.input.inputProcessor = stage
                }
                return@run
            }

            // Requires a custom popup so can't be folded into getErrorMessage
            val modCheckResult = newGameOptionsTable.modCheckboxes.savedModcheckResult
            newGameOptionsTable.modCheckboxes.savedModcheckResult = null
            if (modCheckResult != null) {
                Concurrency.runOnGLThread {
                    AcceptModErrorsPopup(
                        this@NewGameScreen, modCheckResult,
                        action = {
                            gameSetupInfo.gameParameters.acceptedModCheckErrors = modCheckResult
                            startGameAvoidANRs()
                        }
                    )
                    Gdx.input.inputProcessor = stage
                }
                return@run
            }
            startGame()
        }
    }
    
    // Should be run NOT on main thread because it contacts MP server and loads maps etc
    fun getErrorMessage(): String? {
        if (isAuthoritativeLobbySetup) {
            if (game.onlineMultiplayer.authoritativeSession == null)
                return authoritativeUnavailableMessage()
            if (lobbyConfiguration!!.humanSlots > gameSetupInfo.gameParameters.players.size)
                return "Human slots cannot exceed the number of major civilizations."
            val humanPlayers = gameSetupInfo.gameParameters.players.count {
                it.playerType == PlayerType.Human && it.chosenCiv != Constants.spectator
            }
            if (humanPlayers != 1) {
                return "API v3 creates the authenticated owner first; other players join the lobby."
            }
            runCatching { ApiV3GameSetup.from(gameSetupInfo) }
                .exceptionOrNull()
                ?.let { return it.message ?: "This setup is not supported by API v3." }
        }

        if (gameSetupInfo.gameParameters.players.none {
                it.playerType == PlayerType.Human &&
                        // do not allow multiplayer with only spectator(s) and AI(s) - non-MP that works
                        !(it.chosenCiv == Constants.spectator && gameSetupInfo.gameParameters.isOnlineMultiplayer)
            }) return "No human players selected!"

        if (gameSetupInfo.gameParameters.victoryTypes.isEmpty()) return "No victory conditions were selected!"
        
        if (mapOptionsTable.mapTypeSelectBox.selected.value == MapGeneratedMainType.custom) {
            val map = try {
                MapSaver.loadMap(gameSetupInfo.mapFile!!)
            } catch (ex: Throwable) {
                return "Could not load map"
            }

            val rulesetIncompatibilities = map.getRulesetIncompatibility(ruleset)
            if (rulesetIncompatibilities.isNotEmpty())
                return "Map is incompatible with the chosen ruleset!".tr() + "\n" + rulesetIncompatibilities.joinToString("\n"){it.tr()}
        } else {
            // Generated map - check for sensible dimensions and if exceeded correct them and notify user
            val mapSize = gameSetupInfo.mapParameters.mapSize
            val message = mapSize.fixUndesiredSizes(gameSetupInfo.mapParameters.worldWrap)
            if (message != null) {
                with (mapOptionsTable.generatedMapOptionsTable) {
                    customMapSizeRadius.intValue = mapSize.radius
                    customMapWidth.intValue = mapSize.width
                    customMapHeight.intValue = mapSize.height
                }
                return message
            }
        }
        return null
    }
    
    private fun startGame() {

        Concurrency.runOnGLThread {
            rightSideButton.disable()
            rightSideButton.setText(Constants.working.tr())
            setSkin()
            
            // Creating a new game can take a while and we don't want ANRs
            Concurrency.runOnNonDaemonThreadPool("NewGame") {
                startNewGame()
            }
        }
    }

    /** Subtables may need an upper limit to their width - they can ask this function. */
    // In sync with isPortrait in init, here so UI details need not know about 3-column vs 1-column layout
    internal fun getColumnWidth() = floor(stage.width / (if (isNarrowerThan4to3()) 1 else 3))

    internal fun refreshExampleMap() {
        if (mapOptionsTableInitialized)
            mapOptionsTable.refreshExampleMap()
    }

    private fun initLandscape() {
        scrollPane.setScrollingDisabled(true,true)

        if (isAuthoritativeLobbySetup)
            topTable.add("MULTIPLAYER SETUP  •  STEP 1 OF 2".toLabel(Color.GOLD))
                .colspan(5).growX().left().pad(12f).row()
        topTable.add(
            (if (isAuthoritativeLobbySetup) "Match Rules" else "Game Options")
                .toLabel(fontSize = Constants.headingFontSize),
        ).pad(20f, 0f)
        topTable.addSeparatorVertical(ImageGetter.CHARCOAL, 1f)
        topTable.add("Map Options".toLabel(fontSize = Constants.headingFontSize)).pad(20f,0f)
        topTable.addSeparatorVertical(ImageGetter.CHARCOAL, 1f)
        topTable.add("Civilizations".toLabel(fontSize = Constants.headingFontSize)).pad(20f,0f)
        topTable.addSeparator(Color.CLEAR, height = 1f)

        topTable.add(ScrollPane(newGameOptionsTable)
                .apply { setOverscroll(false, false) })
                .width(stage.width / 3).top()
        topTable.addSeparatorVertical(Color.CLEAR, 1f)
        topTable.add(ScrollPane(mapOptionsTable)
                .apply { setOverscroll(false, false) })
                .width(stage.width / 3).top()
        topTable.addSeparatorVertical(Color.CLEAR, 1f)
        topTable.add(playerPickerTable)  // No ScrollPane, PlayerPickerTable has its own
                .width(stage.width / 3).top()
    }

    private fun initPortrait() {
        scrollPane.setScrollingDisabled(false,false)

        if (isAuthoritativeLobbySetup)
            topTable.add("MULTIPLAYER SETUP  •  STEP 1 OF 2".toLabel(Color.GOLD))
                .expandX().fillX().left().pad(12f).row()
        topTable.add(ExpanderTab(if (isAuthoritativeLobbySetup) "Match Rules" else "Game Options") {
            it.add(newGameOptionsTable).row()
        }).expandX().fillX().row()
        topTable.addSeparator(Color.DARK_GRAY, height = 1f)

        if (!isAuthoritativeLobbySetup) {
            topTable.add(newGameOptionsTable.modCheckboxes).expandX().fillX().row()
            topTable.addSeparator(Color.DARK_GRAY, height = 1f)
        }

        topTable.add(ExpanderTab("Map Options") {
            it.add(mapOptionsTable).row()
        }).expandX().fillX().row()
        topTable.addSeparator(Color.DARK_GRAY, height = 1f)

        (playerPickerTable.playerListTable.parent as ScrollPane).setScrollingDisabled(true,true)
        topTable.add(ExpanderTab("Civilizations") {
            it.add(playerPickerTable).row()
        }).expandX().fillX().row()
    }

    private suspend fun startNewGame() = coroutineScope {
        val popup = Popup(this@NewGameScreen)
        launchOnGLThread {
            popup.addGoodSizedLabel(Constants.working).row()
            popup.open()
            ImageGetter.setNewRuleset(ruleset) // To build the temp atlases
        }

        if (isAuthoritativeLobbySetup) {
            startAuthoritativeGame(popup)
            return@coroutineScope
        }

        val newGame:GameInfo
        try {
            val selectedScenario = mapOptionsTable.getSelectedScenario()
            newGame = if (selectedScenario == null)
                GameStarter.startNewGame(gameSetupInfo)
            else {
                val gameInfo = game.files.loadGameFromFile(selectedScenario.file)
                // Remove the Spectator - it was recommended by the wiki as Scenario builder
                gameInfo.civilizations.removeIf { it.civID == Constants.spectator }
                for (civ in gameInfo.civilizations) {
                    civ.playerType = PlayerType.AI
                    civ.diplomacy.remove(Constants.spectator)
                    civ.popupAlerts.removeIf { it.type == AlertType.FirstContact && it.value == Constants.spectator }
                }
                // Ergo the Spectator can't be chosen from NewGameScreen - make sure
                gameSetupInfo.gameParameters.players.removeIf { it.chosenCiv == Constants.spectator }
                // Now assign player types to explicit player Nation choices that exist in the game,
                // remembering which are already "used".
                // (at the moment NewGameScreen forbids such choices for scenarios, but let's support it here in case someone goes and does) 
                val randomPool = gameInfo.civilizations.filter { it.isMajorCiv() }.map { it.civID }.toMutableSet()
                fun Civilization.assign(playerInfo: Player) {
                    playerType = playerInfo.playerType
                    randomPool.remove(civID)
                }
                for (playerInfo in gameSetupInfo.gameParameters.players) {
                    if (playerInfo.chosenCiv == Constants.random) continue
                    gameInfo.getCivilizationOrNull(playerInfo.chosenCiv)?.assign(playerInfo)
                }
                // Now assign player types for "Random" entries
                for (playerInfo in gameSetupInfo.gameParameters.players) {
                    if (playerInfo.chosenCiv != Constants.random) continue
                    val civID = randomPool.randomOrNull() ?: continue
                    gameInfo.getCivilizationOrNull(civID)?.assign(playerInfo)
                }
                // If the Spectator was active when saved, skip it
                if (gameInfo.currentPlayer == Constants.spectator) {
                    gameInfo.currentPlayer = ""
                    gameInfo.nextTurn() // TODO Risky - triggers?
                    gameInfo.turns--
                }
                gameInfo
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            launchOnGLThread {
                popup.apply {
                    reuseWith("It looks like we can't make a map with the parameters you requested!")
                    row()
                    addGoodSizedLabel("Maybe you put too many players into too small a map?").row()
                    addButton("Copy to clipboard"){
                        Gdx.app.clipboard.contents = exception.stackTraceToString()
                    }
                    addCloseButton()
                }
                Gdx.input.inputProcessor = stage
                rightSideButton.enable()
                rightSideButton.setText("Start game!".tr())
            }
            return@coroutineScope
        }

        val worldScreen = game.loadGame(newGame)
        
        worldScreen.autoSave()

    }

    private fun authoritativeUnavailableMessage() =
        when (game.onlineMultiplayer.authoritativeStatus) {
            com.unciv.logic.multiplayer.authoritative.AuthoritativeSessionStatus.NotStarted,
            com.unciv.logic.multiplayer.authoritative.AuthoritativeSessionStatus.Detecting,
            -> "Checking authoritative multiplayer server capabilities. Please wait."
            com.unciv.logic.multiplayer.authoritative.AuthoritativeSessionStatus.SecureStoreUnavailable ->
                "This platform has no secure credential store for authoritative multiplayer."
            else -> "Could not establish the authoritative multiplayer session."
        }


    private suspend fun startAuthoritativeGame(popup: Popup) {
        try {
            val lobby = requireNotNull(lobbyConfiguration) {
                "Create authoritative multiplayer matches from the multiplayer lobby browser."
            }
            val session = requireNotNull(game.onlineMultiplayer.authoritativeSession) {
                "API v3 session is not installed"
            }
            val setup = ApiV3GameSetup.from(gameSetupInfo)
            val meaning = AuthoritativeCreationMeaning(
                lobby.rulesetManifest.baseRuleset.name,
                lobby.rulesetManifest.mods.mapTo(linkedSetOf()) { it.name },
                setup,
            )
            val creation = session.createAuthoritativeGame(
                meaning.baseRulesetName,
                meaning.modNames,
                meaning.setup,
                authoritativeCreationRetryState.operationIdFor(meaning),
                lobby.displayName,
                lobby.humanSlots,
                lobby.password,
                ruleset.nations.values.filter { it.isMajorCiv }.map { it.name }.sorted(),
            )
            val createdLobby = session.lobby(creation.metadata.gameId)
            Concurrency.runOnGLThread {
                popup.close()
                game.replaceCurrentScreen(AuthoritativeLobbyScreen(createdLobby, session))
            }
        } catch (exception: Exception) {
            Log.error("Error while creating authoritative game", exception)
            Concurrency.runOnGLThread {
                popup.reuseWith(
                    exception.message ?: "Could not create game on the authoritative server.",
                    true,
                )
                rightSideButton.enable()
                rightSideButton.setText("Start game!".tr())
                Gdx.input.inputProcessor = stage
            }
        }
    }

    /** Updates our local [ruleset] from [gameSetupInfo], guarding against exceptions.
     *
     *  Note: The options reset on failure is not propagated automatically to the Widgets -
     *  the caller must ensure that.
     *
     *  @return Success - failure means gameSetupInfo was reset to defaults and the Ruleset was reverted to G&K
     */
    fun tryUpdateRuleset(updateUI: Boolean): Boolean {
        var success = true
        fun handleFailure(message: String): Ruleset {
            success = false
            ToastPopup(message, this, 5000)
            gameSetupInfo.gameParameters.mods.clear()
            gameSetupInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_GnK.fullName
            return RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        }

        val newRuleset = try {
            // this can throw with non-default gameSetupInfo, e.g. when Mods change or we change the impact of Mod errors
            RulesetCache.getComplexRuleset(gameSetupInfo.gameParameters)
        } catch (ex: UncivShowableException) {
            handleFailure("«YELLOW»{Your previous options needed to be reset to defaults.}«»\n\n${ex.localizedMessage}")
        } catch (ex: Throwable) {
            Log.debug("updateRuleset failed", ex)
            handleFailure("«RED»{Your previous options needed to be reset to defaults.}«»")
        }

        ruleset.clear()
        ruleset.add(newRuleset)
        ImageGetter.setNewRuleset(ruleset)
        game.musicController.setModList(gameSetupInfo.gameParameters.getModsAndBaseRuleset())

        if (updateUI) newGameOptionsTable.updateRuleset(ruleset)
        return success
    }

    fun lockTables() {
        playerPickerTable.locked = true
        newGameOptionsTable.locked = true
    }

    fun unlockTables() {
        playerPickerTable.locked = false
        newGameOptionsTable.locked = false
    }

    fun updateTables() {
        playerPickerTable.gameParameters = gameSetupInfo.gameParameters
        playerPickerTable.update()
        newGameOptionsTable.changeGameParameters(gameSetupInfo.gameParameters)
        newGameOptionsTable.update()
    }

    override fun recreate(): BaseScreen =
        NewGameScreen(gameSetupInfo, authoritativeCreationRetryState, lobbyConfiguration)
}
