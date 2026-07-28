package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.MultiplayerGamePreview
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.logic.multiplayer.authoritative.AuthoritativeAdministrationCoordinator
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectory
import com.unciv.logic.multiplayer.authoritative.AuthoritativeGameChatCoordinator
import com.unciv.logic.multiplayer.authoritative.AuthoritativeInvitationCoordinator
import com.unciv.logic.multiplayer.authoritative.AuthoritativeInvitationFlow
import com.unciv.logic.multiplayer.authoritative.AuthoritativeResignationCoordinator
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSocialCoordinator
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSummary
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativeGame
import com.unciv.logic.multiplayer.authoritative.OpenedAuthoritativePlayerGame
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSessionStatus
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.AuthPopup
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import java.time.Duration
import java.time.Instant
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

class MultiplayerScreen : PickerScreen() {
    private val initialAuthoritativeStatus = game.onlineMultiplayer.authoritativeStatus
    private var selectedGame: MultiplayerGamePreview? = null
    private var selectedAuthoritativeGame: ApiV3GameSummary? = null
    private val authoritativeDirectory = game.onlineMultiplayer.authoritativeSession
        ?.let(::AuthoritativeGameDirectory)
    private val authoritativeInvitations = game.onlineMultiplayer.authoritativeSession
        ?.let(::AuthoritativeInvitationCoordinator)
    private val authoritativeInvitationFlow =
        if (authoritativeDirectory != null && authoritativeInvitations != null)
            AuthoritativeInvitationFlow(authoritativeInvitations, authoritativeDirectory)
        else null
    private val authoritativeAdministration = game.onlineMultiplayer.authoritativeSession
        ?.let(::AuthoritativeAdministrationCoordinator)
    private val authoritativeResignation = game.onlineMultiplayer.authoritativeSession
        ?.let(::AuthoritativeResignationCoordinator)
    private val authoritativeSocial: AuthoritativeSocialCoordinator? =
        game.onlineMultiplayer.authoritativeSession?.socialCoordinator()
    private val authoritativeChat: AuthoritativeGameChatCoordinator? =
        game.onlineMultiplayer.authoritativeSession?.chatCoordinator()

    private val copyGameIdButton = createCopyGameIdButton()
    private val resignButton = createResignButton()
    private val authoritativeResignButton = createAuthoritativeResignButton()
    private val forceResignButton = createForceResignButton()
    private val skipTurnButton = createSkipTurnButton()
    private val deleteButton = createDeleteButton()
    private val renameButton = createRenameButton()
    private val invitePlayerButton = createInvitePlayerButton()
    private val administrationButton = createAdministrationButton()
    private val chatButton = createChatButton()

    private val gameSpecificButtons =
        listOf(
            copyGameIdButton,
            resignButton,
            authoritativeResignButton,
            deleteButton,
            renameButton,
            invitePlayerButton,
            administrationButton,
            chatButton,
        )

    private val addGameButton = createAddGameButton()
    private val copyUserIdButton = createCopyUserIdButton()
    private val friendsListButton = createFriendsListButton()
    private val invitationsButton = createInvitationsButton()
    private val refreshButton = createRefreshButton()
    private val authoritativeAccountButton = createAuthoritativeAccountButton()
    private val authoritativeAccountManagementButton = createAuthoritativeAccountManagementButton()

    val gameList = GameList(::selectGame)
    val authoritativeGameList = AuthoritativeGameList(::selectAuthoritativeGame)

    init {
        setDefaultCloseAction()

        scrollPane.setScrollingDisabled(false, true)

        topTable.add(createMainContent()).row()

        setupHelpButton()
        setupRightSideButton()
        
        refreshGameLists()

        pickerPane.bottomTable.background = skinStrings.getUiBackground("MultiplayerScreen/BottomTable", tintColor = skinStrings.skinConfig.clearColor)
        pickerPane.topTable.background = skinStrings.getUiBackground("MultiplayerScreen/TopTable", tintColor = skinStrings.skinConfig.clearColor)
    }

    private fun onGameDeleted(gameName:String){
        if (selectedGame?.name == gameName) unselectGame()
        gameList.update()
    }

    private fun setupRightSideButton() {
        rightSideButton.setText("Join game".tr())
        rightSideButton.onClick {
            selectedAuthoritativeGame?.let {
                openAuthoritativeGame(it)
                return@onClick
            }
            val missingMods = selectedGame!!.preview!!.gameParameters.getModsAndBaseRuleset()
                .filter { !RulesetCache.containsKey(it) }
            if (missingMods.isEmpty()) return@onClick MultiplayerHelpers.loadMultiplayerGame(this, selectedGame!!)

            // Download missing mods
            Concurrency.runOnNonDaemonThreadPool(LoadGameScreen.downloadMissingMods) {
                try {
                    LoadGameScreen.loadMissingMods(missingMods, onModDownloaded = {
                        Concurrency.runOnGLThread { ToastPopup("[$it] Downloaded!", this@MultiplayerScreen) }
                    },
                    onCompleted = {
                        RulesetCache.loadRulesets()
                        Concurrency.runOnGLThread { MultiplayerHelpers.loadMultiplayerGame(this@MultiplayerScreen, selectedGame!!) }
                    })
                } catch (ex: Exception) {
                    val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                    launchOnGLThread { ToastPopup(message, this@MultiplayerScreen) }
                }
            }
        }
    }

    private fun getGeneralActionsTable(): Table {
        val generalActions = Table().apply { defaults().pad(10f) }
        generalActions.add(copyUserIdButton).row()
        if (game.onlineMultiplayer.authoritativeStatus == AuthoritativeSessionStatus.LoginRequired)
            generalActions.add(authoritativeAccountButton).row()
        if (game.onlineMultiplayer.authoritativeStatus == AuthoritativeSessionStatus.Authenticated)
            generalActions.add(authoritativeAccountManagementButton).row()
        generalActions.add(addGameButton).row()
        if (authoritativeInvitationFlow != null) generalActions.add(invitationsButton).row()
        generalActions.add(friendsListButton).row()
        generalActions.add(refreshButton).row()
        return generalActions
    }

    private fun getGameSpecificActionsTable(): Table {
        val gameSpecificActions = Table().apply { defaults().pad(10f) }
        gameSpecificActions.add(copyGameIdButton).row()
        gameSpecificActions.add(invitePlayerButton).row()
        gameSpecificActions.add(administrationButton).row()
        gameSpecificActions.add(chatButton).row()
        gameSpecificActions.add(renameButton).row()
        gameSpecificActions.add(skipTurnButton).row()
        gameSpecificActions.add(resignButton).row()
        gameSpecificActions.add(authoritativeResignButton).row()
        gameSpecificActions.add(forceResignButton).row()
        gameSpecificActions.add(deleteButton).row()
        return gameSpecificActions
    }

    private fun createRefreshButton(): TextButton {
        val btn = "Refresh list".toTextButton()
        btn.onClick {
            if (game.onlineMultiplayer.authoritativeStatus != initialAuthoritativeStatus) {
                game.replaceCurrentScreen(MultiplayerScreen())
                return@onClick
            }
            when (game.onlineMultiplayer.authoritativeStatus) {
                AuthoritativeSessionStatus.NotStarted,
                AuthoritativeSessionStatus.Failed,
                -> retryAuthoritativeSession()
                AuthoritativeSessionStatus.Detecting ->
                    ToastPopup("Still checking the multiplayer server.", this)
                AuthoritativeSessionStatus.SecureStoreUnavailable ->
                    ToastPopup("No secure credential store is available on this platform.", this)
                else -> refreshGameLists()
            }
        }
        return btn
    }

    private fun retryAuthoritativeSession() {
        Concurrency.runOnNonDaemonThreadPool("Retry authoritative session restoration") {
            game.onlineMultiplayer.restoreConfiguredAuthoritativeSession(
                game.settings.multiplayer.getServer(),
                game::createApiV3SessionTokenStore,
            )
            launchOnGLThread {
                game.replaceCurrentScreen(MultiplayerScreen())
            }
        }
    }

    private fun createAuthoritativeAccountButton(): TextButton =
        "Log in to server account".toTextButton().apply {
            onClick {
                AuthoritativeAccountPopup(this@MultiplayerScreen) {
                    game.replaceCurrentScreen(MultiplayerScreen())
                }.open()
            }
        }

    private fun createAuthoritativeAccountManagementButton(): TextButton =
        "Manage server account".toTextButton().apply {
            onClick {
                AuthoritativeAccountManagementPopup(this@MultiplayerScreen) {
                    game.replaceCurrentScreen(MultiplayerScreen())
                }.open()
            }
        }

    private fun createInvitationsButton(): TextButton {
        val button = "Server invitations".toTextButton()
        button.onClick {
            val flow = authoritativeInvitationFlow ?: return@onClick
            AuthoritativeInvitationInboxPopup(this, flow, ::openAcceptedInvitation)
                .openAndRefresh()
        }
        return button
    }

    private fun openAcceptedInvitation(accepted: OpenedAuthoritativePlayerGame) {
        val directory = authoritativeDirectory ?: return
        val session = game.onlineMultiplayer.authoritativeSession ?: return
        game.pushScreen(
            AuthoritativeWorldScreen(
                accepted.summary,
                directory,
                accepted.projection,
                session,
            ),
        )
    }

    private fun createInvitePlayerButton(): TextButton {
        val button = "Invite player account".toTextButton().apply { disable() }
        button.onClick {
            val selected = selectedAuthoritativeGame ?: return@onClick
            val coordinator = authoritativeInvitations ?: return@onClick
            AuthoritativeInvitePlayerPopup(this, coordinator, selected.gameId).open()
        }
        return button
    }

    private fun createAdministrationButton(): TextButton {
        val button = "Administer server game".toTextButton().apply { disable() }
        button.onClick {
            val selected = selectedAuthoritativeGame ?: return@onClick
            val coordinator = authoritativeAdministration ?: return@onClick
            AuthoritativeAdministrationPopup(
                this,
                selected,
                coordinator,
                ::refreshGameLists,
            ).open()
        }
        return button
    }

    private fun refreshGameLists() {
        if (game.onlineMultiplayer.authoritativeStatus == AuthoritativeSessionStatus.LegacyServer) {
            Concurrency.run("Update all multiplayer games") {
                game.onlineMultiplayer.legacy.requestUpdate()
            }
        }
        val directory = authoritativeDirectory
            ?.takeIf {
                game.onlineMultiplayer.authoritativeStatus ==
                    AuthoritativeSessionStatus.Authenticated
            }
            ?: return
        Concurrency.runOnNonDaemonThreadPool("Update authoritative multiplayer games") {
            try {
                val games = directory.refresh()
                launchOnGLThread {
                    authoritativeGameList.update(games)
                    val selected = selectedAuthoritativeGame
                    if (selected != null) {
                        games.firstOrNull { it.gameId == selected.gameId }
                            ?.let(::selectAuthoritativeGame)
                            ?: unselectGame()
                    }
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    authoritativeGameList.update(emptyList())
                    ToastPopup("Could not refresh server games: [$message]", this@MultiplayerScreen)
                }
            }
        }
    }

    private fun openAuthoritativeGame(summary: ApiV3GameSummary) {
        val directory = authoritativeDirectory ?: return
        val popup = Popup(this)
        popup.addGoodSizedLabel(Constants.working).row()
        popup.open()
        Concurrency.runOnNonDaemonThreadPool("Open authoritative multiplayer game") {
            try {
                val opened = directory.open(summary)
                launchOnGLThread {
                    popup.clear()
                    when (opened) {
                        is OpenedAuthoritativeGame.Player -> {
                            val projection = opened.projection
                            popup.close()
                            val session = requireNotNull(
                                game.onlineMultiplayer.authoritativeSession,
                            )
                            game.pushScreen(
                                AuthoritativeWorldScreen(
                                    summary,
                                    directory,
                                    projection,
                                    session,
                                ),
                            )
                            return@launchOnGLThread
                        }
                        is OpenedAuthoritativeGame.Spectator -> {
                            val projection = opened.projection
                            popup.addGoodSizedLabel("Server game [${projection.gameId}]").row()
                            popup.addGoodSizedLabel(
                                "Revision [${projection.committedRevision}] - Turn [${projection.projection.turn}]",
                            ).row()
                            popup.addGoodSizedLabel("Public spectator projection").row()
                        }
                    }
                    popup.addCloseButton()
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread { popup.reuseWith(message, true) }
            }
        }
    }

    private fun createAddGameButton(): TextButton {
        val label = if (authoritativeDirectory == null)
            "Add multiplayer game"
        else "Add legacy saved game"
        val btn = label.toTextButton()
        btn.onClick {
            game.pushScreen(AddMultiplayerGameScreen(this))
        }
        return btn
    }

    private fun createResignButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val resignButton = "Resign".toTextButton(negativeButtonStyle).apply { disable() }
        resignButton.onClick {
            val civName = selectedGame!!.preview!!.currentPlayer
            val askPopup = ConfirmPopup(
                    this,
                    "Are you sure you ([$civName]) want to resign?",
                    "Resign",
            ) {
                resignPlayer(selectedGame!!, civName, civName)
            }
            askPopup.open()
        }
        return resignButton
    }

    private fun createAuthoritativeResignButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val button = "Resign from server game".toTextButton(negativeButtonStyle).apply {
            disable()
        }
        button.onClick {
            val summary = selectedAuthoritativeGame ?: return@onClick
            ConfirmPopup(
                this,
                "Resign from authoritative game [${summary.gameId}]? " +
                    "Your civilization will become server-controlled AI.",
                "Resign",
            ) {
                resignAuthoritativeGame(summary)
            }.open()
        }
        return button
    }

    private fun resignAuthoritativeGame(summary: ApiV3GameSummary) {
        val coordinator = authoritativeResignation ?: return
        val popup = Popup(this)
        popup.addGoodSizedLabel(Constants.working).row()
        popup.open()
        Concurrency.runOnNonDaemonThreadPool("Resign authoritative game") {
            try {
                val outcome = coordinator.resign(summary.gameId)
                val message = when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> null
                    AuthoritativeCommandOutcome.RetryRequired ->
                        "Resignation status is uncertain - retry to confirm"
                    is AuthoritativeCommandOutcome.StaleRefreshed ->
                        "Game was out of sync with server - refreshed"
                    is AuthoritativeCommandOutcome.Rejected -> outcome.code
                }
                launchOnGLThread {
                    if (message == null) {
                        popup.close()
                        unselectGame()
                        refreshGameLists()
                    } else {
                        popup.reuseWith(message, true)
                    }
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread { popup.reuseWith(message, true) }
            }
        }
    }
    
    private fun getOurCivNameOrPlayerId(): String {
        val ourId = game.settings.multiplayer.getUserId()
        val ourCiv = selectedGame!!.preview!!.getPlayerCiv(ourId)
        // if we are a non-spectator player, use our civ name, otherwise use player id
        return if (ourCiv != null && ourCiv.civName != Constants.spectator) ourCiv.civName else ourId
    }

    private fun createForceResignButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val resignButton = "Force current player to resign".toTextButton(negativeButtonStyle).apply { isVisible = false }
        resignButton.onClick {
            val currentPlayer = selectedGame!!.preview!!.currentPlayer
            val askPopup = ConfirmPopup(
                this,
                "Are you sure you want to force the current player ([$currentPlayer]) to resign?",
                "Yes",
            ) {
                resignPlayer(selectedGame!!, currentPlayer, getOurCivNameOrPlayerId())
            }
            askPopup.open()
        }
        return resignButton
    }

    private fun createSkipTurnButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val skipTurnButton = "Skip turn of current player".toTextButton(negativeButtonStyle).apply { isVisible = false }
        skipTurnButton.onClick {
            val civName = selectedGame!!.preview!!.currentPlayer
            val askPopup = ConfirmPopup(
                this,
                "Are you sure you want to skip the turn of [$civName]?",
                "Yes",
            ) {
                skipCurrentPlayerTurn(selectedGame!!, civName, getOurCivNameOrPlayerId())
            }
            askPopup.open()
        }
        return skipTurnButton
    }

    /**
     * Permanently turns the current playerCiv into an AI civ and uploads the game afterwards.
     * 
     * @param responsibleCivNameOrPlayerId Who caused the player to resign? Can be the name of a civ, or for example a player id
     */
    private fun resignPlayer(multiplayerGamePreview: MultiplayerGamePreview, playerCiv: String, responsibleCivNameOrPlayerId: String) {
        //Create a popup
        val popup = Popup(this)
        popup.addGoodSizedLabel(Constants.working).row()
        popup.open()

        Concurrency.runOnNonDaemonThreadPool("Resign") {
            try {
                val errorMessage = game.onlineMultiplayer.legacy.resignPlayer(
                    multiplayerGamePreview,
                    playerCiv,
                    responsibleCivNameOrPlayerId
                )

                launchOnGLThread {
                    if (errorMessage.isEmpty()) {
                        popup.close()
                    } else {
                        popup.reuseWith(errorMessage, true)
                    }
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)

                if (ex is MultiplayerAuthException) {
                    launchOnGLThread {
                        AuthPopup(this@MultiplayerScreen) { success ->
                            if (success) resignPlayer(multiplayerGamePreview, playerCiv, responsibleCivNameOrPlayerId)
                        }.open(true)
                    }
                    return@runOnNonDaemonThreadPool
                }

                launchOnGLThread {
                    popup.reuseWith(message, true)
                }
            }
        }
    }

    /**
     * Temporarily turns the current playerCiv into an AI civ and uploads the game afterwards.
     *
     * @param responsibleCivNameOrPlayerId Who skipped the player's turn? Can be the name of a civ, or for example a player id
     */
    private fun skipCurrentPlayerTurn(multiplayerGamePreview: MultiplayerGamePreview, playerToSkip: String, responsibleCivNameOrPlayerId: String) {
        //Create a popup
        val popup = Popup(this)
        popup.addGoodSizedLabel(Constants.working).row()
        popup.open()

        Concurrency.runOnNonDaemonThreadPool("Skip turn") {
            try {
                val skipTurnErrorMessage = game.onlineMultiplayer.legacy.skipCurrentPlayerTurn(
                    multiplayerGamePreview,
                    playerToSkip,
                    responsibleCivNameOrPlayerId
                )

                launchOnGLThread {
                    if (skipTurnErrorMessage == null) {
                        popup.close()
                    } else {
                        popup.reuseWith(skipTurnErrorMessage, true)
                    }
                    gameList.update()
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)

                if (ex is MultiplayerAuthException) {
                    launchOnGLThread {
                        AuthPopup(this@MultiplayerScreen) { success ->
                            if (success) skipCurrentPlayerTurn(multiplayerGamePreview, playerToSkip, responsibleCivNameOrPlayerId)
                        }.open(true)
                    }
                    return@runOnNonDaemonThreadPool
                }

                launchOnGLThread {
                    popup.reuseWith(message, true)
                }
            }
        }
    }

    private fun createDeleteButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val deleteButton = "Delete save".toTextButton(negativeButtonStyle).apply { disable() }
        deleteButton.onClick {
            val askPopup = ConfirmPopup(
                    this,
                    "Are you sure you want to delete this save?",
                    "Delete save",
            ) {
                try {
                    game.onlineMultiplayer.legacy.multiplayerFiles.deleteGame(selectedGame!!)
                    onGameDeleted(selectedGame!!.name)
                } catch (ex: Exception) {
                    Log.error("Could not delete game!", ex)
                    ToastPopup("Could not delete game!", this)
                }
            }
            askPopup.open()
        }
        return deleteButton
    }

    private fun createRenameButton(): TextButton {
        val btn = "Rename".toTextButton().apply { disable() }
        btn.onClick {
            Popup(this).apply {
                val textField = UncivTextField("Game name", selectedGame!!.name)
                // slashes in mp names are interpreted as directory separators, so we don't allow them
                textField.textFieldFilter = UncivFiles.fileNameTextFieldFilter()
                add(textField).width(stageToShowOn.width / 2).row()
                val saveButton = "Save".toTextButton()

                val saveNewNameFunction = {
                    val newName = textField.text.trim()
                    game.onlineMultiplayer.legacy.multiplayerFiles.changeGameName(selectedGame!!, newName) {
                        if (it != null) reuseWith("Could not save game!", true)
                    }
                    gameList.update()
                    selectGame(newName)
                    close()
                }

                saveButton.onActivation(saveNewNameFunction)
                saveButton.keyShortcuts.add(KeyCharAndCode.RETURN)
                textField.cursorPosition = textField.text.length
                this@MultiplayerScreen.stage.keyboardFocus = textField
                add(saveButton)
                open()
            }
        }
        return btn
    }

    private fun createCopyGameIdButton(): TextButton {
        val btn = "Copy game ID".toTextButton().apply { disable() }
        btn.onClick {
            val gameInfo = selectedGame?.preview
            if (gameInfo != null) {
                Gdx.app.clipboard.contents = gameInfo.gameId
                ToastPopup("Game ID copied to clipboard!", this)
            }
        }
        return btn
    }

    private fun createFriendsListButton(): TextButton {
        val btn = "Friends list".toTextButton()
        btn.onClick {
            val social = authoritativeSocial
            if (social == null) game.pushScreen(ViewFriendsListScreen())
            else AuthoritativeFriendsPopup(this, social).openAndRefresh()
        }
        return btn
    }

    private fun createChatButton(): TextButton {
        val button = "Game chat".toTextButton().apply { disable() }
        button.onClick {
            val gameId = selectedAuthoritativeGame?.gameId ?: return@onClick
            val chat = authoritativeChat ?: return@onClick
            AuthoritativeGameChatPopup(this, chat, gameId).openAndRefresh()
        }
        return button
    }

    private fun createCopyUserIdButton(): TextButton {
        val btn = "Copy user ID".toTextButton()
        btn.onClick {
            Gdx.app.clipboard.contents = game.settings.multiplayer.getUserId()
            ToastPopup("UserID copied to clipboard", this)
        }
        return btn
    }

    private fun createMainContent(): Table {
        val mainTable = Table()
        val lists = Table()
        if (authoritativeDirectory != null) {
            lists.add("Server games".tr().toLabel()).left().row()
            lists.add(
                ScrollPane(authoritativeGameList).apply { setScrollingDisabled(true, false) },
            ).growX().row()
            lists.add("Legacy saved games".tr().toLabel()).left().row()
        }
        lists.add(ScrollPane(gameList).apply { setScrollingDisabled(true, false) }).growX()
        mainTable.add(lists).center()
        mainTable.add(getGameSpecificActionsTable())
        mainTable.add(getGeneralActionsTable())
        return mainTable
    }

    private fun setupHelpButton() {
        val tab = Table()
        val helpButton = "Help".toTextButton()
        helpButton.onClick {
            val helpPopup = Popup(this)
            helpPopup.addGoodSizedLabel("To create a multiplayer game, check the 'multiplayer' toggle in the New Game screen, and for each human player insert that player's user ID.").row()
            helpPopup.addGoodSizedLabel("You can assign your own user ID there easily, and other players can copy their user IDs here and send them to you for you to include them in the game.").row()
            helpPopup.row()

            helpPopup.addGoodSizedLabel("Once you've created your game, the Game ID gets automatically copied to your clipboard so you can send it to the other players.").row()
            helpPopup.addGoodSizedLabel("Players can enter your game by copying the game ID to the clipboard, and clicking on the 'Add multiplayer game' button").row()
            helpPopup.row()

            helpPopup.addGoodSizedLabel("The symbol of your nation will appear next to the game when it's your turn").row()

            helpPopup.addCloseButton()
            helpPopup.open()
        }
        tab.add(helpButton)
        tab.x = (stage.width - helpButton.width)
        tab.y = (stage.height - helpButton.height)

        stage.addActor(tab)
    }

    private fun unselectGame() {
        selectedGame = null
        selectedAuthoritativeGame = null
        rightSideButton.setText("Join game".tr())
        rightSideButton.disable()
        for (button in gameSpecificButtons)
            button.disable()
        skipTurnButton.isVisible = false
        forceResignButton.isVisible = false

        descriptionLabel.setText("")
    }

    private fun selectGame(name: String) {
        val multiplayerGame = game.onlineMultiplayer.legacy.multiplayerFiles.getGameByName(name)
        if (multiplayerGame == null) {
            // Should never happen
            unselectGame()
            return
        }

        selectedGame = multiplayerGame
        selectedAuthoritativeGame = null
        rightSideButton.setText("Join game".tr())

        for (button in gameSpecificButtons) button.enable()
        invitePlayerButton.disable()
        administrationButton.disable()
        authoritativeResignButton.disable()
        chatButton.disable()

        val preview = multiplayerGame.preview
        
        if (preview != null) {
            copyGameIdButton.enable()
            rightSideButton.enable()
        } else {
            copyGameIdButton.disable()
            rightSideButton.disable()
        }

        // is it our turn?
        resignButton.isEnabled = preview?.getCurrentPlayerCiv()?.playerId == game.settings.multiplayer.getUserId()
        
        if (resignButton.isEnabled || preview == null){
            skipTurnButton.isVisible = false
            forceResignButton.isVisible = false
        } else {
            val durationInactive = Duration.between(Instant.ofEpochMilli(preview.currentTurnStartTime), Instant.now())
            val playerDurationBeforeForceResign = Duration.ofMinutes(preview.getCurrentPlayerCiv().playerMinutesBeforeForceResign.toLong())
            val weAreAPlayer = game.settings.multiplayer.getUserId() in preview.civilizations.map { it.playerId }
            skipTurnButton.isVisible = weAreAPlayer && durationInactive > Duration.ofMinutes(preview.gameParameters.minutesUntilSkipTurn.toLong())
            forceResignButton.isVisible = weAreAPlayer && (durationInactive > playerDurationBeforeForceResign)
                                                
        }
        
        descriptionLabel.setText(MultiplayerHelpers.buildDescriptionText(multiplayerGame))
    }

    private fun selectAuthoritativeGame(summary: ApiV3GameSummary) {
        selectedGame = null
        selectedAuthoritativeGame = summary
        for (button in gameSpecificButtons) button.disable()
        if (authoritativeChat != null) chatButton.enable()
        if (summary.role == "owner" && summary.lifecycleStatus == "active" && summary.available) {
            invitePlayerButton.enable()
        }
        if (
            summary.role == "owner" &&
            summary.lifecycleStatus in setOf("active", "closed") &&
            summary.available
        ) {
            administrationButton.enable()
        }
        if (
            summary.role in setOf("owner", "player") &&
            summary.lifecycleStatus == "active" &&
            summary.available
        ) authoritativeResignButton.enable()
        skipTurnButton.isVisible = false
        forceResignButton.isVisible = false
        rightSideButton.setText("Open server projection".tr())
        if (authoritativeDirectory?.canOpen(summary) == true)
            rightSideButton.enable()
        else rightSideButton.disable()
        descriptionLabel.setText(
            "API v3 server game [${summary.gameId}]\n" +
                "Role: [${summary.role}] - Revision: [${summary.committedRevision}]\n" +
                "Status: [${summary.lifecycleStatus}]",
        )
    }
}
