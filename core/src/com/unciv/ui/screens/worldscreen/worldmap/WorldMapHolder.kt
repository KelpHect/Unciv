package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.*
import com.unciv.UncivGame
import com.unciv.GUI
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.*
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.logic.multiplayer.authoritative.ReligiousUnitAction
import com.unciv.logic.multiplayer.authoritative.GreatPersonUnitAction
import com.unciv.logic.multiplayer.authoritative.UnitPosture
import com.unciv.models.Spy
import com.unciv.models.UnitActionType
import com.unciv.models.UncivSound
import com.unciv.models.UpgradeUnitAction
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.MapArrowType
import com.unciv.ui.components.MiscArrowTypes
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.isShiftKeyPressed
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.input.*
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.components.tilegroups.citybutton.CityButton
import com.unciv.ui.components.widgets.UnitIconGroup
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.UndoHandler.Companion.recordUndoCheckpoint
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.worldscreen.bottombar.BattleTableHelpers.battleAnimationDeferred
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CancellationException
import yairm210.purity.annotations.Readonly
import java.lang.Float.max


class WorldMapHolder(
    internal val worldScreen: WorldScreen,
    internal val tileMap: TileMap
) : ZoomableScrollPane(20f, 20f) {
    internal var selectedTile: Tile? = null
    val tileGroups = HashMap<Tile, WorldTileGroup>()

    /** Holds buttons created by [OverlayButtonData] implementations */
    internal val unitActionOverlays: ArrayList<Actor> = ArrayList()

    internal val unitMovementPaths: HashMap<MapUnit, ArrayList<Tile>> = HashMap()

    internal val unitConnectRoadPaths: HashMap<MapUnit, List<Tile>> = HashMap()

    private lateinit var tileGroupMap: TileGroupMap<WorldTileGroup>

    lateinit var currentTileSetStrings: TileSetStrings

    init {
        if (Gdx.app.type == Application.ApplicationType.Desktop) this.setFlingTime(0f)
        continuousScrollingX = tileMap.mapParameters.worldWrap
        setupZoomPanListeners()
    }

    /**
     * When scrolling or zooming the world map, there are three unnecessary (at least currently) things happening that take a decent amount of time:
     *
     * 1. Checking which [Actor]'s bounds the pointer (mouse/finger) entered+exited and sending appropriate events to these actors
     * 2. Running all [Actor.act] methods of all child [Actor]s
     * 3. Running all [Actor.hit] methods of all child [Actor]s
     *
     * Disabling them while panning/zooming increases the frame rate by approximately 100%.
     */
    private fun setupZoomPanListeners() {

        fun setActHit() {
            val isEnabled = !isZooming() && !isPanning
            (stage as UncivStage).performPointerEnterExitEvents = isEnabled
            tileGroupMap.shouldAct = isEnabled
            tileGroupMap.shouldHit = isEnabled
        }

        onPanStartListener = { setActHit() }
        onPanStopListener = { setActHit() }
        onZoomStartListener = { setActHit() }
        onZoomStopListener = { setActHit() }
    }


    internal fun addTiles() {
        val tileSetStrings = TileSetStrings(worldScreen.gameInfo.ruleset, worldScreen.game.settings)
        currentTileSetStrings = tileSetStrings
        val tileGroupsNew = tileMap.values.map { WorldTileGroup(it, tileSetStrings) }
        tileGroupMap = TileGroupMap(this, tileGroupsNew, continuousScrollingX)

        for (tileGroup in tileGroupsNew) tileGroups[tileGroup.tile] = tileGroup

        addClickListener()

        actor = tileGroupMap
        setSize(worldScreen.stage.width, worldScreen.stage.height)
        layout() // Fit the scroll pane to the contents - otherwise, setScroll won't work!
    }

    private fun addClickListener() {
        // ActivationListener-like listener to allow us to create only one listener for the entire worldmapholder instead of one per tile
        val listener = object : UncivActorGestureListener() {
            override fun tap(event: InputEvent?, x: Float, y: Float, count: Int, button: Int) {
                val child = tileGroupMap.hit(x, y, true) ?: return

                if (child is CityButton) { // the city button can be below the tilegroup, since it moves down when first clicked
                    onTileClicked(child.city.getCenterTile())
                    return
                }
                if (child is WorldTileGroup) {
                    Concurrency.runOnGLThread("Sound") { SoundPlayer.play(UncivSound.Click) }

                    if (button == 0) onTileClicked(child.tile) // Regular click
                    else if (button == 1) { // Right button click = move unit to tile
                        if (!UncivGame.Current.settings.longTapMove) return
                        val unit = worldScreen.bottomUnitTable.selectedUnit
                            ?: return
                        onTileRightClicked(unit, child.tile)
                    }
                }
            }

            override fun longPress(actor: Actor?, x: Float, y: Float): Boolean {
                if (actor == null) return false
                // See #10050 - when a tap discards its actor or ascendants, Gdx can't cancel the longpress timer
                if (actor.stage == null) return false

                if (!UncivGame.Current.settings.longTapMove) return false
                val unit = worldScreen.bottomUnitTable.selectedUnit
                    ?: return false
                if (Gdx.app.type != Application.ApplicationType.Android) return false

                val child = tileGroupMap.hit(x, y, true) ?: return false
                if (child !is WorldTileGroup) return false

                Concurrency.run("WorldScreenClick") {
                    onTileRightClicked(unit, child.tile)
                }
                return true
            }
        }

        tileGroupMap.addListener(listener)
    }

    fun onTileClicked(tile: Tile) {

        if (!worldScreen.viewingCiv.hasExplored(tile)
                && tile.neighbors.all { worldScreen.viewingCiv.hasExplored(it) })
            return // This tile doesn't exist for you

        removeUnitActionOverlay()
        selectedTile = tile
        unitMovementPaths.clear()
        unitConnectRoadPaths.clear()

        val unitTable = worldScreen.bottomUnitTable
        val previousSelectedUnits = unitTable.selectedUnits.toList() // create copy
        val previousSelectedCity = unitTable.selectedCity
        val previousSelectedUnitIsSwapping = unitTable.selectedUnitIsSwapping
        val previousSelectedUnitIsConnectingRoad = unitTable.selectedUnitIsConnectingRoad
        val movingSpyOnMap = unitTable.selectedSpy != null
        if (!movingSpyOnMap)
            unitTable.tileSelected(tile)
        val newSelectedUnit = unitTable.selectedUnit

        if (previousSelectedCity != null && tile != previousSelectedCity.getCenterTile() && !movingSpyOnMap)
            tileGroups[previousSelectedCity.getCenterTile()]!!.layerCityButton.moveUp()

        if (previousSelectedUnits.isNotEmpty()) {
            val isTileDifferent = previousSelectedUnits.any { it.getTile() != tile }
            val isPlayerTurn = worldScreen.isPlayersTurn
            val existsUnitNotPreparingAirSweep = previousSelectedUnits.any { !it.isPreparingAirSweep() }

            // Todo: valid tiles for actions should be handled internally, not here.
            val canPerformActionsOnTile = if (previousSelectedUnitIsSwapping) {
                AuthoritativeMovementUi.canSwap(worldScreen, previousSelectedUnits.first(), tile)
                    ?: previousSelectedUnits.first().movement.canUnitSwapTo(tile)
            } else if(previousSelectedUnitIsConnectingRoad) {
                true
            } else {
                previousSelectedUnits.any {
                    val authoritativeIntent = if (it.isPreparingParadrop()) null
                        else AuthoritativeMovementUi.movementIntent(worldScreen, it, tile)
                    authoritativeIntent?.let { intent ->
                        intent != AuthoritativeMovementIntent.Unavailable
                    } ?: (it.movement.canMoveTo(tile) ||
                        (it.movement.isUnknownTileWeShouldAssumeToBePassable(tile) && !it.baseUnit.movesLikeAirUnits)
                    )
                }
            }

            if (isTileDifferent && isPlayerTurn && canPerformActionsOnTile && existsUnitNotPreparingAirSweep) {
                when {
                    previousSelectedUnitIsSwapping -> addTileOverlaysWithUnitSwapping(previousSelectedUnits.first(), tile)
                    previousSelectedUnitIsConnectingRoad -> addTileOverlaysWithUnitRoadConnecting(previousSelectedUnits.first(), tile)
                    else -> addTileOverlaysWithUnitMovement(previousSelectedUnits, tile) // Long-running task
                }
            }
        } else if (movingSpyOnMap) {
            addMovingSpyOverlay(unitTable.selectedSpy!!, tile)
        } else {
            addTileOverlays(tile) // no unit movement but display the units in the tile etc.
        }

        if (newSelectedUnit == null || newSelectedUnit.isCivilian()) {
            val unitsInTile = selectedTile!!.getUnits()
            val canBombardSelectedTile = when {
                previousSelectedCity == null -> false
                AuthoritativeCombatUi.isOpen(worldScreen) ->
                    AuthoritativeCombatUi.canBombard(worldScreen, previousSelectedCity, selectedTile!!)
                else -> previousSelectedCity.canBombard() &&
                    selectedTile!!.getTilesInDistance(2).contains(previousSelectedCity.getCenterTile()) &&
                    unitsInTile.any() &&
                    unitsInTile.first().civ.isAtWarWith(worldScreen.viewingCiv)
            }
            if (canBombardSelectedTile) {
                // try to select the closest city to bombard this guy
                unitTable.citySelected(checkNotNull(previousSelectedCity))
            }
        }
        worldScreen.shouldUpdate = true
    }

    private fun onTileRightClicked(unit: MapUnit, tile: Tile) {
        if (unit.currentTile.position == tile.position) return
        removeUnitActionOverlay()
        selectedTile = tile
        unitMovementPaths.clear()
        unitConnectRoadPaths.clear()
        if (!worldScreen.canChangeState) return

        // Concurrency might open up a race condition window - if worldScreen.shouldUpdate is on too
        // early, concurrent code might possibly call worldScreen.render() and then our request will be
        // 'consumed' prematurely, and worse, the update might update and show the BattleTable for our
        // right-click attack, and leave it visible after we have resolved the battle here in code -
        // including its onClick closures which will be outdated if the user clicks Attack -> crash!
        var localShouldUpdate = worldScreen.shouldUpdate
        worldScreen.shouldUpdate = false
        // Below, there's 4 outcomes, one of which will have done nothing and will restore the old
        // shouldUpdate - maybe overkill done in a "better safe than sorry" mindset.

        if (worldScreen.bottomUnitTable.selectedUnitIsSwapping) {
            /** ****** Right-click Swap ****** */
            if (AuthoritativeMovementUi.canSwap(worldScreen, unit, tile)
                    ?: unit.movement.canUnitSwapTo(tile)) {
                swapMoveUnitToTargetTile(unit, tile)
                localShouldUpdate = true
            }
            /** If we are in unit-swapping mode and didn't find a swap partner, we don't want to move or attack */
        } else {
            if (AuthoritativeCombatUi.isOpen(worldScreen)) {
                val combatAction = AuthoritativeCombatUi.unitAction(worldScreen, unit, tile)
                if (combatAction != null) {
                    when (combatAction) {
                        AuthoritativeCombatAction.Attack -> submitAuthoritativeUnitAttackIfOpen(unit, tile)
                        AuthoritativeCombatAction.NuclearStrike ->
                            submitAuthoritativeNuclearStrikeIfOpen(unit, tile)
                        AuthoritativeCombatAction.AirSweep -> submitAuthoritativeAirSweepIfOpen(unit, tile)
                    }
                    localShouldUpdate = true
                } else if (AuthoritativeMovementUi.movementIntent(worldScreen, unit, tile)?.let {
                        it != AuthoritativeMovementIntent.Unavailable
                    } == true) {
                    moveUnitToTargetTile(listOf(unit), tile)
                    localShouldUpdate = true
                }
                worldScreen.shouldUpdate = localShouldUpdate
                return
            }
            // This seems inefficient as the tileToAttack is already known - but the method also calculates tileToAttackFrom
            val attackableTile = TargetHelper
                    .getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
                    .firstOrNull { it.tileToAttack == tile }
            if (unit.canAttack() && attackableTile != null) {
                /** ****** Right-click Attack ****** */
                if (submitAuthoritativeUnitAttackIfOpen(unit, tile)) {
                    localShouldUpdate = true
                    worldScreen.shouldUpdate = localShouldUpdate
                    return
                }
                val attacker = MapUnitCombatant(unit)
                if (!Battle.movePreparingAttack(attacker, attackableTile)) return
                if (!SoundPlayer.play(UncivSound(attacker.getName())))
                    SoundPlayer.play(attacker.getAttackSound())
                val (damageToDefender, damageToAttacker) = Battle.attackOrNuke(attacker, attackableTile)
                if (attackableTile.combatant != null)
                    worldScreen.battleAnimationDeferred(attacker, damageToAttacker, attackableTile.combatant, damageToDefender)
                localShouldUpdate = true
            } else if (AuthoritativeMovementUi.movementIntent(worldScreen, unit, tile)?.let {
                    it != AuthoritativeMovementIntent.Unavailable
                } ?: unit.movement.canReach(tile)) {
                /** ****** Right-click Move ****** */
                moveUnitToTargetTile(listOf(unit), tile)
                localShouldUpdate = true
            }
        }
        worldScreen.shouldUpdate = localShouldUpdate
    }

    private fun markUnitMoveTutorialComplete(unit: MapUnit) {
        val key = if (unit.baseUnit.movesLikeAirUnits) "Move an air unit" else "Move unit"
        UncivGame.Current.settings.addCompletedTutorialTask(key)
    }

    internal fun moveUnitToTargetTile(selectedUnits: List<MapUnit>, targetTile: Tile) {
        // this can take a long time, because of the unit-to-tile calculation needed, so we put it in a different thread
        // THIS PART IS REALLY ANNOYING
        // So lets say you have 2 units you want to move in the same direction, right
        // But if the first one gets there, and the second one was PLANNING on going there, then now it can't and has to rethink
        // So basically, THE UNIT MOVES HAVE TO BE SEQUENTIAL and not concurrent which is a BITCH
        // So we do this one at a time by getting the list of units to move, MOVING ONE OF THEM with all the yukky threading,
        // and then calling the function again but without the unit that moved.

        val selectedUnit = selectedUnits.first()
        markUnitMoveTutorialComplete(selectedUnit) // not too expensive to have it repeat too often

        if (isAuthoritativeGame() && !selectedUnit.isPreparingParadrop()) {
            submitAuthoritativeUnitMove(selectedUnits, targetTile, destinationThisTurn = null)
            return
        }

        Concurrency.run("TileToMoveTo") {
            // these are the heavy parts, finding where we want to go
            // Since this runs in a different thread, even if we check movement.canReach()
            // then it might change until we get to the getTileToMoveTo, so we just try/catch it
            val tileToMoveTo: Tile
            var pathToTile: List<Tile>? = null
            try {
                tileToMoveTo = selectedUnit.movement.getTileToMoveToThisTurn(targetTile)
                if (!selectedUnit.type.isAirUnit() && !selectedUnit.isPreparingParadrop())
                    pathToTile = selectedUnit.movement.getDistanceToTiles().getPathToTile(tileToMoveTo)
            } catch (ex: Exception) {
                when (ex) {
                    is UnitMovement.UnreachableDestinationException -> {
                        // This is normal e.g. when selecting an air unit then right-clicking on an empty tile
                        // Or telling a ship to run onto a coastal land tile.
                        // Do nothing
                    }
                    else -> Log.error("Exception in getTileToMoveToThisTurn", ex)
                }
                return@run // can't move here
            }

            if (isAuthoritativeGame()) {
                submitAuthoritativeUnitMove(selectedUnits, targetTile, tileToMoveTo)
                return@run
            }

            worldScreen.recordUndoCheckpoint()

            launchOnGLThread {
                try {
                    // Because this is darned concurrent (as it MUST be to avoid ANRs),
                    // there are edge cases where the canReach is true,
                    // but until it reaches the headTowards the board has changed and so the headTowards fails.
                    // I can't think of any way to avoid this,
                    // but it's so rare and edge-case-y that ignoring its failure is actually acceptable, hence the empty catch
                    val previousTile = selectedUnit.currentTile
                    selectedUnit.movement.moveToTile(tileToMoveTo)
                    
                    // If you try to send a unit to a tile that it can't even get nearer to, then this is actualy a dud
                    if (previousTile == selectedUnit.currentTile){
                        removeUnitActionOverlay() // so the user knows the action 'has been performed'
                        return@launchOnGLThread
                    }
                    
                    if (selectedUnit.isExploring() || selectedUnit.isMoving())
                        selectedUnit.action = null // remove explore on manual move
                    SoundPlayer.play(UncivSound.Whoosh)
                    if (selectedUnit.currentTile != targetTile)
                        selectedUnit.action =
                                "moveTo ${targetTile.position.x},${targetTile.position.y}"
                    if (selectedUnit.hasMovement()) worldScreen.bottomUnitTable.selectUnit(selectedUnit)

                    worldScreen.shouldUpdate = true

                    if (pathToTile != null) {
                        animateMovement(previousTile, selectedUnit, tileToMoveTo, pathToTile)
                        if (selectedUnit.isEscorting()) {
                            animateMovement(previousTile, selectedUnit.getOtherEscortUnit()!!, tileToMoveTo, pathToTile)
                        }
                    }

                    if (selectedUnits.size > 1) { // We have more tiles to move
                        moveUnitToTargetTile(selectedUnits.subList(1, selectedUnits.size), targetTile)
                    } else removeUnitActionOverlay() //we're done here

                    if (UncivGame.Current.settings.autoUnitCycle && !selectedUnit.hasMovement())
                        worldScreen.switchToNextUnit()

                } catch (ex: Exception) {
                    Log.error("Exception in moveUnitToTargetTile", ex)
                }
            }
        }
    }

    @Readonly
    private fun isAuthoritativeGame() = worldScreen.gameInfo.gameParameters.isOnlineMultiplayer &&
        worldScreen.game.onlineMultiplayer.authoritativeSession
            ?.isGameOpen(worldScreen.gameInfo.gameId) == true

    @Readonly
    fun usesAuthoritativeCommands(): Boolean = isAuthoritativeGame()

    internal fun submitAuthoritativeUnitAttackIfOpen(unit: MapUnit, target: Tile): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("unit attack", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession?.attackWithUnitIfOpen(
                worldScreen.gameInfo.gameId,
                unit.id,
                target.position.x,
                target.position.y,
            )
        }) { removeUnitActionOverlay() }
        return true
    }

    internal fun submitAuthoritativeCityBombardIfOpen(cityId: String, target: Tile): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("city bombardment", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession?.bombardWithCityIfOpen(
                worldScreen.gameInfo.gameId,
                cityId,
                target.position.x,
                target.position.y,
            )
        }) { removeUnitActionOverlay() }
        return true
    }

    internal fun submitAuthoritativeNuclearStrikeIfOpen(unit: MapUnit, target: Tile): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("nuclear strike", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession?.launchNuclearStrikeIfOpen(
                worldScreen.gameInfo.gameId,
                unit.id,
                target.position.x,
                target.position.y,
            )
        }) { removeUnitActionOverlay() }
        return true
    }

    internal fun submitAuthoritativeAirSweepIfOpen(unit: MapUnit, target: Tile): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("air sweep", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession?.airSweepIfOpen(
                worldScreen.gameInfo.gameId,
                unit.id,
                target.position.x,
                target.position.y,
            )
        }) { removeUnitActionOverlay() }
        return true
    }

    private fun submitAuthoritativeUnitMove(
        selectedUnits: List<MapUnit>,
        requestedTarget: Tile,
        destinationThisTurn: Tile?,
    ) {
        val selectedUnit = selectedUnits.first()
        val isParadrop = selectedUnit.isPreparingParadrop()
        val movementIntent = AuthoritativeMovementUi.movementIntent(
            worldScreen, selectedUnit, requestedTarget,
        )
        val isMultiTurnOrder = !isParadrop && movementIntent == AuthoritativeMovementIntent.MoveToward
        val description = when {
            isParadrop -> "unit paradrop"
            isMultiTurnOrder -> "unit movement order"
            else -> "unit move"
        }
        submitAuthoritativeUnitCommand(description, submit = {
            if (isParadrop) {
                val paradropDestination = checkNotNull(destinationThisTurn)
                worldScreen.game.onlineMultiplayer.authoritativeSession?.paradropUnitIfOpen(
                    worldScreen.gameInfo.gameId,
                    selectedUnit.id,
                    paradropDestination.position.x,
                    paradropDestination.position.y,
                )
            } else if (isMultiTurnOrder)
                worldScreen.game.onlineMultiplayer.authoritativeSession?.moveUnitTowardIfOpen(
                    worldScreen.gameInfo.gameId,
                    selectedUnit.id,
                    requestedTarget.position.x,
                    requestedTarget.position.y,
                )
            else worldScreen.game.onlineMultiplayer.authoritativeSession?.moveUnitIfOpen(
                    worldScreen.gameInfo.gameId,
                    selectedUnit.id,
                    requestedTarget.position.x,
                    requestedTarget.position.y,
                )
        }) {
            if (selectedUnits.size > 1)
                moveUnitToTargetTile(selectedUnits.drop(1), requestedTarget)
            else removeUnitActionOverlay()
        }
    }

    private fun submitAuthoritativeUnitCommand(
        description: String,
        submit: suspend () -> AuthoritativeCommandOutcome?,
        onAccepted: () -> Unit,
    ) {
        Concurrency.runOnNonDaemonThreadPool("Submit authoritative $description") {
            val outcome = try { submit() }
            catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                launchOnGLThread {
                    removeUnitActionOverlay()
                    ToastPopup("Could not submit authoritative $description: [${ex.message ?: "Unknown"}]", worldScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            launchOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        worldScreen.gameInfo.isUpToDate = false
                        onAccepted()
                        ToastPopup("${description.replaceFirstChar { it.uppercase() }} committed by the authoritative server", worldScreen)
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        worldScreen.gameInfo.isUpToDate = false
                        removeUnitActionOverlay()
                        ToastPopup("Game changed on the server - $description was not committed", worldScreen)
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        removeUnitActionOverlay()
                        ToastPopup("Server rejected $description: [${outcome.code}]", worldScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired ->
                        ToastPopup("Server response was lost - retry will use the same command", worldScreen)
                    null -> {
                        removeUnitActionOverlay()
                        ToastPopup("Authoritative game was closed before $description", worldScreen)
                    }
                }
                worldScreen.shouldUpdate = true
            }
        }
    }

    private fun animateMovement(
        previousTile: Tile,
        selectedUnit: MapUnit,
        targetTile: Tile,
        pathToTile: List<Tile>
    ) {
        val tileGroup = tileGroups[previousTile]!!

        // Steal the current sprites to our new group
        val unitSpriteAndIcon = Group().apply { setPosition(tileGroup.x, tileGroup.y) }
        val unitSpriteSlot = tileGroup.layerUnitArt.getSpriteSlot(selectedUnit) ?: return
        
        for (spriteImage in unitSpriteSlot.spriteGroup.children.toList()) // toList because actors added remove themselves from previous parent  
            unitSpriteAndIcon.addActor(spriteImage)
        tileGroupMap.addActor(unitSpriteAndIcon)

        

        unitSpriteAndIcon.addAction(
            Actions.sequence(
                Actions.run {
                    // Disable the final tile, so we won't have one image "merging into" the other
                    // Can only be done after the new group has been updated, to get the spriteGroup
                    val targetTileSpriteSlot = tileGroups[targetTile]!!.layerUnitArt.getSpriteSlot(selectedUnit)
                    targetTileSpriteSlot?.spriteGroup?.isVisible = false
                },
                *pathToTile.map { tile ->
                    Actions.moveTo(
                        tileGroups[tile]!!.x,
                        tileGroups[tile]!!.y,
                        0.5f / pathToTile.size
                    )
                }.toTypedArray(),
                Actions.run {
                    // Re-enable the final tile
                    val targetTileSpriteSlot = tileGroups[targetTile]!!.layerUnitArt.getSpriteSlot(selectedUnit)
                    targetTileSpriteSlot?.spriteGroup?.isVisible = true
                    worldScreen.shouldUpdate = true
                },
                Actions.removeActor(),
            )
        )
    }

    internal fun swapMoveUnitToTargetTile(selectedUnit: MapUnit, targetTile: Tile) {
        if (isAuthoritativeGame()) {
            submitAuthoritativeUnitCommand("unit swap", submit = {
                worldScreen.game.onlineMultiplayer.authoritativeSession?.swapUnitsIfOpen(
                    worldScreen.gameInfo.gameId,
                    selectedUnit.id,
                    targetTile.position.x,
                    targetTile.position.y,
                )
            }) { removeUnitActionOverlay() }
            return
        }
        markUnitMoveTutorialComplete(selectedUnit)
        selectedUnit.movement.swapMoveToTile(targetTile, keepEscorting = true)

        if (selectedUnit.isExploring() || selectedUnit.isMoving())
            selectedUnit.action = null // remove explore on manual swap-move

        // Play something like a swish-swoosh
        SoundPlayer.play(UncivSound.Swap)

        if (selectedUnit.hasMovement()) worldScreen.bottomUnitTable.selectUnit(selectedUnit)

        worldScreen.shouldUpdate = true
        removeUnitActionOverlay()
    }

    internal fun cancelUnitMovementOrder(unit: MapUnit) {
        if (!isAuthoritativeGame()) {
            unit.action = null
            worldScreen.shouldUpdate = true
            return
        }
        submitAuthoritativeUnitCommand("movement-order cancellation", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.cancelUnitMovementOrderIfOpen(worldScreen.gameInfo.gameId, unit.id)
        }) {
            removeUnitActionOverlay()
        }
    }

    internal fun setUnitExploration(unit: MapUnit, enabled: Boolean) {
        if (!isAuthoritativeGame()) {
            if (enabled) {
                unit.action = com.unciv.models.UnitActionType.Explore.value
                if (unit.hasMovement())
                    com.unciv.logic.automation.unit.UnitAutomation.automatedExplore(unit)
            } else unit.action = null
            worldScreen.shouldUpdate = true
            return
        }
        submitAuthoritativeUnitCommand("exploration order", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.setUnitExplorationIfOpen(worldScreen.gameInfo.gameId, unit.id, enabled)
        }) {
            removeUnitActionOverlay()
        }
    }

    internal fun setUnitAutomation(unit: MapUnit, enabled: Boolean) {
        if (!isAuthoritativeGame()) {
            if (enabled) {
                unit.automated = true
                com.unciv.logic.automation.unit.UnitAutomation.automateUnitMoves(unit)
            } else {
                unit.action = null
                unit.automated = false
            }
            worldScreen.shouldUpdate = true
            return
        }
        if (!enabled && unit.isAutomatingRoadConnection()) {
            submitAuthoritativeUnitCommand("road connection cancellation", submit = {
                worldScreen.game.onlineMultiplayer.authoritativeSession
                    ?.setRoadConnectionOrderIfOpen(worldScreen.gameInfo.gameId, unit.id, null, null)
            }) { removeUnitActionOverlay() }
            return
        }
        submitAuthoritativeUnitCommand("unit automation", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.setUnitAutomationIfOpen(worldScreen.gameInfo.gameId, unit.id, enabled)
        }) {
            removeUnitActionOverlay()
        }
    }

    internal fun setUnitPosture(unit: MapUnit, posture: UnitPosture) {
        if (!isAuthoritativeGame()) {
            when (posture) {
                UnitPosture.Sleep -> unit.action = UnitActionType.Sleep.value
                UnitPosture.SleepUntilHealed -> unit.action = UnitActionType.SleepUntilHealed.value
                UnitPosture.Fortify -> unit.fortify()
                UnitPosture.FortifyUntilHealed -> unit.fortifyUntilHealed()
                UnitPosture.Guard -> unit.action = UnitActionType.Guard.value
            }
            worldScreen.shouldUpdate = true
            return
        }
        submitAuthoritativeUnitCommand("unit posture", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.setUnitPostureIfOpen(worldScreen.gameInfo.gameId, unit.id, posture)
        }) {
            removeUnitActionOverlay()
        }
    }

    internal fun disbandUnit(unit: MapUnit) {
        if (!isAuthoritativeGame()) {
            unit.disband()
            unit.civ.updateStatsForNextTurn()
            GUI.setUpdateWorldOnNextRender()
            if (GUI.getSettings().autoUnitCycle)
                worldScreen.switchToNextUnit()
            return
        }
        submitAuthoritativeUnitCommand("unit disband", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.disbandUnitIfOpen(worldScreen.gameInfo.gameId, unit.id)
        }) {
            removeUnitActionOverlay()
        }
    }

    /** Returns true when pillaging was submitted to the authoritative server. */
    fun pillageTile(unit: MapUnit): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("tile pillage", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.pillageTileIfOpen(worldScreen.gameInfo.gameId, unit.id)
        }) { removeUnitActionOverlay() }
        return true
    }

    /** Returns true when a closed religious action was submitted to API v3. */
    fun useReligiousUnit(unit: MapUnit, action: ReligiousUnitAction): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("religious unit action", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.useReligiousUnitIfOpen(worldScreen.gameInfo.gameId, unit.id, action)
        }) { removeUnitActionOverlay() }
        return true
    }

    /** Returns true when a closed direct great-person action was submitted to API v3. */
    fun useGreatPersonUnit(unit: MapUnit, action: GreatPersonUnitAction): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("great-person unit action", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.useGreatPersonUnitIfOpen(worldScreen.gameInfo.gameId, unit.id, action)
        }) { removeUnitActionOverlay() }
        return true
    }

    /** Returns true when gifting was submitted to the authoritative server. */
    fun giftUnit(unit: MapUnit): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("unit gift", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.giftUnitIfOpen(worldScreen.gameInfo.gameId, unit.id)
        }) { removeUnitActionOverlay() }
        return true
    }

    /** Returns true when a mod-defined transformation was submitted to the authoritative server. */
    fun transformUnit(unit: MapUnit, actionId: String): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("unit transformation", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.transformUnitIfOpen(worldScreen.gameInfo.gameId, unit.id, actionId)
        }) { removeUnitActionOverlay() }
        return true
    }

    /** Returns true when a mod-defined trigger action was submitted to the authoritative server. */
    fun triggerUnitUnique(unit: MapUnit, actionId: String): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("unit trigger", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.triggerUnitUniqueIfOpen(worldScreen.gameInfo.gameId, unit.id, actionId)
        }) { removeUnitActionOverlay() }
        return true
    }

    /** Returns true when founding was submitted to the authoritative server. */
    fun foundCity(unit: MapUnit): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("found city", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.foundCityIfOpen(worldScreen.gameInfo.gameId, unit.id)
        }) { removeUnitActionOverlay() }
        return true
    }

    fun upgradeUnits(units: List<MapUnit>, targetUnitName: String) {
        if (units.isEmpty()) return
        if (!isAuthoritativeGame()) {
            for (unit in units) {
                val action = UnitActionsUpgrade.getUpgradeActions(unit)
                    .filterIsInstance<UpgradeUnitAction>()
                    .firstOrNull { it.unitToUpgradeTo.name == targetUnitName && it.action != null }
                action?.action?.invoke()
            }
            worldScreen.shouldUpdate = true
            return
        }
        submitAuthoritativeUnitCommand("unit upgrade", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.upgradeUnitsIfOpen(
                    worldScreen.gameInfo.gameId,
                    units.map { it.id },
                    targetUnitName,
                )
        }) {
            removeUnitActionOverlay()
        }
    }

    /** Returns true when promotion was submitted to an authoritative game;
     * local modes apply the same selected path synchronously. */
    fun promoteUnit(
        unit: MapUnit,
        promotionNames: List<String>,
        saveAsCityDefault: Boolean,
    ): Boolean {
        if (!isAuthoritativeGame()) {
            for (promotionName in promotionNames)
                unit.promotions.addPromotion(promotionName)
            worldScreen.shouldUpdate = true
            return false
        }
        submitAuthoritativeUnitCommand("unit promotion", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.promoteUnitIfOpen(
                    worldScreen.gameInfo.gameId, unit.id, promotionNames, saveAsCityDefault,
                )
        }) {
            removeUnitActionOverlay()
        }
        return true
    }

    /** Returns true when the rename was submitted to the authoritative server. */
    fun renameUnit(unit: MapUnit, instanceName: String?): Boolean {
        if (!isAuthoritativeGame()) {
            unit.instanceName = instanceName
            worldScreen.shouldUpdate = true
            return false
        }
        submitAuthoritativeUnitCommand("unit rename", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.renameUnitIfOpen(worldScreen.gameInfo.gameId, unit.id, instanceName)
        }) {
            removeUnitActionOverlay()
        }
        return true
    }

    /** Returns true when the tile order was submitted to the authoritative server. */
    fun setTileImprovementOrder(
        unit: MapUnit,
        improvementName: String?,
        queuedImprovementName: String? = null,
    ): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("tile improvement order", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.setTileImprovementOrderIfOpen(
                    worldScreen.gameInfo.gameId,
                    unit.id,
                    improvementName,
                    queuedImprovementName,
                )
        }) {
            removeUnitActionOverlay()
        }
        return true
    }

    /** Returns true when the destination was submitted to an authoritative server. */
    fun setRoadConnectionOrder(unit: MapUnit, destination: Tile): Boolean {
        if (!isAuthoritativeGame()) return false
        submitAuthoritativeUnitCommand("road connection order", submit = {
            worldScreen.game.onlineMultiplayer.authoritativeSession
                ?.setRoadConnectionOrderIfOpen(
                    worldScreen.gameInfo.gameId,
                    unit.id,
                    destination.position.x,
                    destination.position.y,
                )
        }) { removeUnitActionOverlay() }
        return true
    }

    private fun addTileOverlaysWithUnitMovement(selectedUnits: List<MapUnit>, tile: Tile) {
        val authoritativeUnits = selectedUnits.filter {
            !it.isPreparingParadrop() &&
                AuthoritativeMovementUi.movementIntent(worldScreen, it, tile)?.let { intent ->
                    intent != AuthoritativeMovementIntent.Unavailable
                } == true
        }
        if (AuthoritativeMovementUi.isOpen(worldScreen) &&
            selectedUnits.none { it.isPreparingParadrop() }) {
            if (authoritativeUnits.isEmpty()) {
                addTileOverlays(tile)
                worldScreen.shouldUpdate = true
                return
            }
            val exactMove = authoritativeUnits.all {
                AuthoritativeMovementUi.movementIntent(worldScreen, it, tile) ==
                    AuthoritativeMovementIntent.ExactMove
            }
            if (UncivGame.Current.settings.singleTapMove && exactMove)
                moveUnitToTargetTile(authoritativeUnits, tile)
            else addTileOverlays(
                tile,
                MoveHereOverlayButtonData(
                    HashMap(authoritativeUnits.associateWith { 1 }),
                    tile,
                    showTurns = false,
                ),
            )
            worldScreen.shouldUpdate = true
            return
        }
        Concurrency.run("TurnsToGetThere") {
            /** LibGdx sometimes has these weird errors when you try to edit the UI layout from 2 separate threads.
             * And so, all UI editing will be done on the main thread.
             * The only "heavy lifting" that needs to be done is getting the turns to get there,
             * so that and that alone will be relegated to the concurrent thread.
             */

            val unitToTurnsToTile = HashMap<MapUnit, Int>()
            for (unit in selectedUnits) {
                val shortestPath = ArrayList<Tile>()
                val turnsToGetThere = if (unit.baseUnit.movesLikeAirUnits) {
                    if (unit.movement.canReach(tile)) 1
                    else 0
                } else if (unit.isPreparingParadrop()) {
                    if (unit.movement.canReach(tile)) 1
                    else 0
                } else {
                    // this is the most time-consuming call
                    shortestPath.addAll(unit.movement.getShortestPath(tile))
                    shortestPath.size
                }
                unitMovementPaths[unit] = shortestPath
                unitToTurnsToTile[unit] = turnsToGetThere
            }

            launchOnGLThread {
                val unitsWhoCanMoveThere = HashMap(unitToTurnsToTile.filter { it.value != 0 })
                if (unitsWhoCanMoveThere.isEmpty()) { // give the regular tile overlays with no unit movement
                    addTileOverlays(tile)
                    worldScreen.shouldUpdate = true
                    return@launchOnGLThread
                }

                val turnsToGetThere = unitsWhoCanMoveThere.values.maxOrNull()!!

                if (UncivGame.Current.settings.singleTapMove && turnsToGetThere == 1) {
                    // single turn instant move
                    val selectedUnit = unitsWhoCanMoveThere.keys.first()
                    if (isAuthoritativeGame())
                        moveUnitToTargetTile(unitsWhoCanMoveThere.keys.toList(), tile)
                    else {
                        for (unit in unitsWhoCanMoveThere.keys) {
                            unit.movement.headTowards(tile)
                        }
                        worldScreen.bottomUnitTable.selectUnit(selectedUnit) // keep moved unit selected
                    }
                } else {
                    // add "move to" button if there is a path to tileInfo
                    val moveHereButtonDto = MoveHereOverlayButtonData(unitsWhoCanMoveThere, tile)
                    addTileOverlays(tile, moveHereButtonDto)
                }
                worldScreen.shouldUpdate = true
            }
        }
    }

    private fun addTileOverlaysWithUnitSwapping(selectedUnit: MapUnit, tile: Tile) {
        if (!(AuthoritativeMovementUi.canSwap(worldScreen, selectedUnit, tile)
                ?: selectedUnit.movement.canUnitSwapTo(tile))) { // give the regular tile overlays with no unit swapping
            addTileOverlays(tile)
            worldScreen.shouldUpdate = true
            return
        }
        if (UncivGame.Current.settings.singleTapMove) {
            swapMoveUnitToTargetTile(selectedUnit, tile)
        }
        else {
            // Add "swap with" button
            val swapWithButtonDto = SwapWithOverlayButtonData(selectedUnit, tile)
            addTileOverlays(tile, swapWithButtonDto)
        }
        worldScreen.shouldUpdate = true
    }

    private fun addTileOverlaysWithUnitRoadConnecting(selectedUnit: MapUnit, tile: Tile){
        Concurrency.run("ConnectRoad") {
           val validTile = tile.isLand &&
               !tile.isImpassible() &&
                selectedUnit.civ.hasExplored(tile)

            if (validTile) {
                val roadPath: List<Tile>? =
                    if (UncivGame.Current.settings.useAStarPathfinding) selectedUnit.movement.getRoadPath(tile)
                    else MapPathing.getRoadPath(selectedUnit.civ, selectedUnit.getTile(), tile)
                launchOnGLThread {
                    if (roadPath == null) { // give the regular tile overlays with no road connection
                        addTileOverlays(tile)
                        worldScreen.shouldUpdate = true
                        return@launchOnGLThread
                    }
                    unitConnectRoadPaths[selectedUnit] = roadPath
                    val connectRoadButtonDto = ConnectRoadOverlayButtonData(selectedUnit, tile)
                    addTileOverlays(tile, connectRoadButtonDto)
                    worldScreen.shouldUpdate = true
                }
            }
        }
    }

    private fun addMovingSpyOverlay(spy: Spy, tile: Tile) {
        val city: City? = if (tile.isCityCenter() && spy.canMoveTo(tile.getCity()!!)) tile.getCity() else null
        addTileOverlays(tile, MoveSpyOverlayButtonData(spy, city))
        worldScreen.shouldUpdate = true
    }

    private fun addTileOverlays(tile: Tile, buttonDto: OverlayButtonData? = null) {
        val table = Table().apply { defaults().pad(10f) }
        if (buttonDto != null && worldScreen.canChangeState)
            table.add(buttonDto.createButton(this))

        val unitList = ArrayList<MapUnit>()
        if (tile.isCityCenter()
                && (tile.getOwner() == worldScreen.viewingCiv || worldScreen.viewingCiv.isSpectator())) {
            unitList.addAll(tile.getCity()!!.getCenterTile().getUnits())
        } else if (tile.airUnits.isNotEmpty()
                && (tile.airUnits.first().civ == worldScreen.viewingCiv || worldScreen.viewingCiv.isSpectator())) {
            unitList.addAll(tile.getUnits())
        }

        for (unit in unitList) {
            val unitIconGroup = UnitIconGroup(unit, 48f).surroundWithCircle(68f, resizeActor = false)
            unitIconGroup.circle.color = Color.GRAY.cpy().apply { a = 0.5f }
            if (!unit.hasMovement()) unitIconGroup.color.a = 0.66f
            val clickableCircle = ClickableCircle(68f)
            clickableCircle.onClickSuppressive {
                worldScreen.bottomUnitTable.selectUnit(unit, Gdx.input.isShiftKeyPressed())
                worldScreen.shouldUpdate = true
                removeUnitActionOverlay()
            }
            unitIconGroup.addActor(clickableCircle)
            table.add(unitIconGroup)
        }

        addOverlayOnTileGroup(tileGroups[tile]!!, table)
        if (UncivGame.Current.settings.unitMovementButtonAnimation) {
            table.color.a = 0f
            table.addAction(Actions.moveBy(0f, 48f, 0.15f, Interpolation.smooth))
            table.addAction(Actions.alpha(1f, 0.15f, Interpolation.smooth))
        }
        else
            table.moveBy(0f, 48f)
    }

    /** Adds [actor] as a direct child of the TileGroupMap, rendered above all layer groups. */
    fun addActorToTileGroupMap(actor: Actor) = tileGroupMap.addActor(actor)

    fun addOverlayOnTileGroup(group: TileGroup, actor: Actor) {

        actor.center(group)
        actor.x += group.x
        actor.y += group.y
        tileGroupMap.addActor(actor) // Add directly to TileGroupMap so toFront() places it above all layer groups
        actor.toFront()

        actor.y += actor.height
        actor.setOrigin(Align.bottom)
        unitActionOverlays.add(actor)
    }

    /** Returns true when the civ is a human player defeated in singleplayer game */
    @Readonly
    fun isMapRevealEnabled(viewingCiv: Civilization) = !viewingCiv.gameInfo.gameParameters.isOnlineMultiplayer
            && viewingCiv.isCurrentPlayer()
            && viewingCiv.isDefeated()

    /** Clear all arrows to be drawn on the next update. */
    fun resetArrows() {
        for (tile in tileGroups.asSequence())
            tile.value.layerMisc.resetArrows()
    }

    /** Add an arrow to draw on the next update. */
    fun addArrow(fromTile: Tile, toTile: Tile, arrowType: MapArrowType) {
        tileGroups[fromTile]?.layerMisc?.addArrow(toTile, arrowType)
    }

    /**
     * Add arrows to show all past and planned movements and attacks, if the options setting to do so is enabled.
     *
     * @param pastVisibleUnits Sequence of [MapUnit]s for which the last turn's movement history can be displayed.
     * @param targetVisibleUnits Sequence of [MapUnit]s for which the active movement target can be displayed.
     * @param visibleAttacks Sequence of pairs of [Vector2] positions of the sources and the targets of all attacks that can be displayed.
     * */
    internal fun updateMovementOverlay(pastVisibleUnits: Sequence<MapUnit>, targetVisibleUnits: Sequence<MapUnit>, visibleAttacks: Sequence<Pair<HexCoord, HexCoord>>) {
        val selectedUnit = worldScreen.bottomUnitTable.selectedUnit
        for (unit in pastVisibleUnits) {
            if (unit.movementMemories.isEmpty()) continue
            if (selectedUnit != null && selectedUnit != unit) continue // When selecting a unit, show only arrows of that unit
            val stepIter = unit.movementMemories.iterator()
            var previous = stepIter.next()
            while (stepIter.hasNext()) {
                val next = stepIter.next()
                addArrow(tileMap[previous.position], tileMap[next.position], next.type)
                previous = next
            }
            addArrow(tileMap[previous.position], unit.getTile(),  unit.mostRecentMoveType)
        }
        for (unit in targetVisibleUnits) {
            if (!unit.isMoving())
                continue
            val toTile = unit.getMovementDestination()
            addArrow(unit.getTile(), toTile, MiscArrowTypes.UnitMoving)
        }
        for ((from, to) in visibleAttacks) {
            if (selectedUnit != null
                && selectedUnit.currentTile.position != from
                && selectedUnit.currentTile.position != to) continue
            addArrow(tileMap[from], tileMap[to], MiscArrowTypes.UnitHasAttacked)
        }
    }


    var blinkAction: Action? = null

    /** Scrolls the world map to specified coordinates.
     * @param vector Position to center on
     * @param immediately Do so without animation
     * @param selectUnit Select a unit at the destination
     * @return `true` if scroll position was changed, `false` otherwise
     */
    fun setCenterPosition(vector: HexCoord, immediately: Boolean = false, selectUnit: Boolean = true, forceSelectUnit: MapUnit? = null): Boolean {
        val tileGroup = tileGroups.values.firstOrNull { it.tile.position == vector } ?: return false
        selectedTile = tileGroup.tile
        if (selectUnit || forceSelectUnit != null)
            worldScreen.bottomUnitTable.tileSelected(selectedTile!!, forceSelectUnit)

        // The Y axis of [scrollY] is inverted - when at 0 we're at the top, not bottom - so we invert it back.
        if (!scrollTo(tileGroup.x + tileGroup.width / 2, maxY - (tileGroup.y + tileGroup.width / 2), immediately))
            return false

        removeAction(blinkAction) // so we don't have multiple blinks at once
        blinkAction = Actions.repeat(3, Actions.sequence(
                Actions.run { tileGroup.layerOverlay.hideHighlight()},
                Actions.delay(.3f),
                Actions.run { tileGroup.layerOverlay.showHighlight()},
                Actions.delay(.3f)
        ))
        addAction(blinkAction) // Don't set it on the group because it's an actionless group

        worldScreen.shouldUpdate = true
        return true
    }

    override fun zoom(zoomScale: Float) {
        super.zoom(zoomScale)
        clampCityButtonSize()
    }

    /** We don't want the city buttons becoming too large when zooming out */
    private fun clampCityButtonSize() {
        // use scaleX instead of zoomScale itself, because zoomScale might have been outside minZoom..maxZoom and thus not applied
        val clampedCityButtonZoom = 1 / scaleX
        if (clampedCityButtonZoom >= 1) {
            for (tileGroup in tileGroups.values) {
                tileGroup.layerCityButton.setButtonTransform(false) // save rendering time at normal zoom
            }
        } else if (clampedCityButtonZoom >= minZoom) {
            for (tileGroup in tileGroups.values) {
                // ONLY set those groups that have active city buttons as transformable!
                // This is massively framerate-improving!
                if (tileGroup.layerCityButton.hasButton())
                    tileGroup.layerCityButton.setButtonTransform(true)
                tileGroup.layerCityButton.setButtonScale(clampedCityButtonZoom)
            }
        }
    }

    fun removeUnitActionOverlay() {
        for (overlay in unitActionOverlays)
            overlay.remove()
        unitActionOverlays.clear()
    }

    override fun reloadMaxZoom() {
        val maxWorldZoomOut = UncivGame.Current.settings.maxWorldZoomOut
        val mapRadius = tileMap.mapParameters.mapSize.radius

        // Limit max zoom out by the map width
        val enableZoomLimit = (mapRadius < 21 && maxWorldZoomOut < 3f) || (mapRadius > 20 && maxWorldZoomOut < 4f)

        if (enableZoomLimit) {
            // For world-wrap we limit minimal possible zoom to content width + some extra offset
            // to hide one column of tiles so that the player doesn't see it teleporting from side to side
            val pad = if (continuousScrollingX) width / mapRadius * 0.7f else 0f
            minZoom = max(
                (width + pad) * scaleX / maxX,
                1f / maxWorldZoomOut
            )// add some extra padding offset

            // If the window becomes too wide and minZoom > maxZoom, we cannot zoom
            maxZoom = max(2f * minZoom, maxWorldZoomOut)
        }
        else
            super.reloadMaxZoom()
    }

    override fun restrictX(deltaX: Float): Float {
        var result = scrollX - deltaX
        if (worldScreen.viewingCiv.isSpectator()) return result

        val exploredRegion = worldScreen.viewingCiv.exploredRegion
        if (exploredRegion.shouldRecalculateCoords()) exploredRegion.calculateStageCoords(maxX, maxY)
        if (!exploredRegion.shouldRestrictX()) return result

        val leftX = exploredRegion.getLeftX()
        val rightX = exploredRegion.getRightX()

        if (deltaX < 0 && scrollX <= rightX && result > rightX)
            result = rightX
        else if (deltaX > 0 && scrollX >= leftX && result < leftX)
            result = leftX

        return result
    }

    override fun restrictY(deltaY: Float): Float {
        var result = scrollY + deltaY
        if (worldScreen.viewingCiv.isSpectator()) return result

        val exploredRegion = worldScreen.viewingCiv.exploredRegion
        if (exploredRegion.shouldRecalculateCoords()) exploredRegion.calculateStageCoords(maxX, maxY)

        val topY = exploredRegion.getTopY()
        val bottomY = exploredRegion.getBottomY()

        if (result < topY) result = topY
        else if (result > bottomY) result = bottomY

        return result
    }

    // For debugging purposes
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun act(delta: Float) = super.act(delta)
    override fun clear() = super.clear()
}
