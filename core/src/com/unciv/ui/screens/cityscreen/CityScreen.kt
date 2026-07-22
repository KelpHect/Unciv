package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.automation.Automation
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.logic.multiplayer.authoritative.CityTileAssignment
import com.unciv.logic.multiplayer.authoritative.CityGovernanceAction
import com.unciv.logic.multiplayer.authoritative.CitizenFocus
import com.unciv.logic.multiplayer.authoritative.PlayerProjection
import com.unciv.logic.multiplayer.authoritative.ProjectedCity
import com.unciv.logic.multiplayer.authoritative.ProjectedConstructionPurchase
import com.unciv.logic.map.tile.Tile
import com.unciv.models.TutorialTrigger
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.audio.CityAmbiencePlayer
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.ParticleEffectMapFireworks
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyShortcutDispatcherVeto
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.tilegroups.CityTileGroup
import com.unciv.ui.components.tilegroups.CityTileState
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.popups.closeAllPopups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import kotlinx.coroutines.CancellationException
import kotlin.math.max

class CityScreen(
    internal val city: City,
    initSelectedConstruction: IConstruction? = null,
    initSelectedTile: Tile? = null,
    /** City ambience sound player proxies can be passed from one CityScreen instance to the next
     *  to avoid premature stops or rewinds. Only the fresh CityScreen from WorldScreen or Overview
     *  will instantiate a new CityAmbiencePlayer and start playing. */
    ambiencePlayer: CityAmbiencePlayer? = null
): BaseScreen(), RecreateOnResize {
    companion object {
        /** Distance from stage edges to floating widgets */
        const val posFromEdge = 5f

        /** Size of the decoration icons shown besides the raze button */
        const val wltkIconSize = 40f
    }

    private val selectedCiv: Civilization = GUI.getWorldScreen().selectedCiv

    internal val isSpying = selectedCiv.gameInfo.isEspionageEnabled() && selectedCiv != city.civ && !selectedCiv.isSpectator()

    /**
     * This is the regular civ city list if we are not spying, if we are spying then it is every foreign city that our spies are in
     */
    val viewableCities = if (isSpying) selectedCiv.espionageManager.getCitiesWithOurSpies()
        .filter { it.civ !=  GUI.getWorldScreen().selectedCiv }
    else city.civ.cities

    /** Toggles or adds/removes all state changing buttons */
    val canChangeState = GUI.isAllowedChangeState() && !isSpying

    internal fun isAuthoritativeGame() = city.civ.gameInfo.gameParameters.isOnlineMultiplayer &&
        game.onlineMultiplayer.authoritativeSession?.isGameOpen(city.civ.gameInfo.gameId) == true

    internal fun authoritativeProjection(): PlayerProjection? =
        if (!isAuthoritativeGame()) null else game.onlineMultiplayer.authoritativeSession
            ?.cachedProjectionIfOpen(city.civ.gameInfo.gameId)

    internal fun authoritativeProjectedCity(): ProjectedCity? = authoritativeProjection()
        ?.ownCities?.singleOrNull { it.id == city.id }

    internal fun authoritativeProjectedPurchase(
        constructionName: String,
        currencyName: String,
        queueIndex: Int?,
    ): ProjectedConstructionPurchase? {
        val projectedCity = authoritativeProjectedCity() ?: return null
        val purchases = if (queueIndex == null)
            projectedCity.constructionOptions.singleOrNull { it.name == constructionName }?.purchases
        else projectedCity.constructionQueueEntries.getOrNull(queueIndex)
            ?.takeIf { it.name == constructionName }
            ?.purchases
        return purchases?.singleOrNull { it.currency == currencyName }
    }

    // Clockwise from the top-left

    /** Displays current production, production queue and available productions list
     *  Not a widget, but manages two: construction queue + buy buttons
     *  in a Table holder on TOP LEFT, and available constructions in a ScrollPane BOTTOM LEFT.
     */
    private var constructionsTable = CityConstructionsTable(this)
    private var authoritativeTilePurchaseSubmissionInProgress = false
    private var authoritativeTileConstructionSubmissionInProgress = false
    private var authoritativeTileAssignmentSubmissionInProgress = false
    private var authoritativeSpecialistSubmissionInProgress = false
    private var authoritativeCitizenManagementSubmissionInProgress = false

    /** Displays raze city button - sits on TOP CENTER */
    private var razeCityButtonHolder = Table()

    /** Displays city stats, population management, religion, built buildings info - TOP RIGHT */
    private var cityStatsTable = CityStatsTable(this)

    /** Displays tile info, alternate with selectedConstructionTable - sits on BOTTOM RIGHT */
    private var tileTable = CityScreenTileTable(this)

    /** Displays selected construction info, alternate with tileTable - sits on BOTTOM RIGHT */
    private var selectedConstructionTable = ConstructionInfoTable(this)

    /** Displays city name, allows switching between cities - sits on BOTTOM CENTER */
    private var cityPickerTable = CityScreenCityPickerTable(this)

    /** Button for exiting the city - sits on BOTTOM CENTER */
    private val exitCityButton = "Exit city".toTextButton().apply {
        labelCell.pad(10f)
        keyShortcuts.add(KeyCharAndCode.BACK)
        onActivation {
            exit()
        }
    }


    /** Holds City tiles group*/
    private var tileGroups = ArrayList<CityTileGroup>()

    /** The ScrollPane for the background map view of the city surroundings */
    private val mapScrollPane = CityMapHolder()

    /** Support for [UniqueType.CreatesOneImprovement] - need user to pick a tile */
    class PickTileForImprovementData (
        val building: Building,
        val improvement: TileImprovement,
        val isBuying: Boolean,
        val buyStat: Stat
    )

    // The following fields control what the user selects
    var selectedConstruction: IConstruction? = initSelectedConstruction
        private set
    var selectedTile: Tile? = initSelectedTile
        private set
    /** If set, we are waiting for the user to pick a tile for [UniqueType.CreatesOneImprovement] */
    var pickTileData: PickTileForImprovementData? = null
    /** A [Building] with [UniqueType.CreatesOneImprovement] has been selected _in the queue_: show the tile it will place the improvement on */
    private var selectedQueueEntryTargetTile: Tile? = null
    var selectedQueueEntry
        get() = constructionsTable.selectedQueueEntry
        set(value) { constructionsTable.selectedQueueEntry = value }
    /** Cached city.expansion.chooseNewTileToOwn() */
    // val should be OK as buying tiles is what changes this, and that would re-create the whole CityScreen
    private val nextTileToOwn = city.expansion.chooseNewTileToOwn()

    private var cityAmbiencePlayer: CityAmbiencePlayer?  = ambiencePlayer ?: CityAmbiencePlayer(city)

    /** Particle effects for WLTK day decoration */
    private val isWLTKday = city.isWeLoveTheKingDayActive()
    private val fireworks: ParticleEffectMapFireworks?
    internal var pauseFireworks = false

    init {
        if (isWLTKday && UncivGame.Current.settings.citySoundsVolume > 0) {
            SoundPlayer.play(UncivSound("WLTK"))
        }
        fireworks = if (isWLTKday) ParticleEffectMapFireworks.create(game, mapScrollPane) else null

        UncivGame.Current.settings.addCompletedTutorialTask("Enter city screen")

        addTiles()

        // If we are spying then we shoulden't be able to see their construction screen.
        constructionsTable.addActorsToStage()
        stage.addActor(cityStatsTable)
        stage.addActor(selectedConstructionTable)
        stage.addActor(tileTable)
        stage.addActor(cityPickerTable)  // add late so it's top in Z-order and doesn't get covered in cramped portrait
        stage.addActor(exitCityButton)
        update()

        globalShortcuts.add(KeyboardBinding.PreviousCity) { page(-1) }
        globalShortcuts.add(KeyboardBinding.NextCity) { page(1) }

        if (isPortrait()) mapScrollPane.apply {
            // center scrolling so city center sits more to the bottom right
            scrollX = (maxX - constructionsTable.getLowerWidth() - posFromEdge) / 2
            scrollY = (maxY - cityStatsTable.packIfNeeded().height - posFromEdge + cityPickerTable.top) / 2
            updateVisualScroll()
        }
    }

    override fun getCivilopediaRuleset() = selectedCiv.gameInfo.ruleset

    internal fun update() {
        // Recalculate Stats
        city.cityStats.update()

        constructionsTable.isVisible = !isSpying
        constructionsTable.update(selectedConstruction)

        updateWithoutConstructionAndMap()

        // Rest of screen: Map of surroundings
        updateTileGroups()
    }

    internal fun updateWithoutConstructionAndMap() {
        // Bottom right: Tile or selected construction info
        tileTable.update(selectedTile)
        tileTable.setPosition(stage.width - posFromEdge, posFromEdge, Align.bottomRight)
        selectedConstructionTable.update(selectedConstruction)
        selectedConstructionTable.setPosition(stage.width - posFromEdge, posFromEdge, Align.bottomRight)

        // In portrait mode only: calculate already occupied horizontal space
        val rightMargin = when {
            !isPortrait() || isCrampedPortrait() -> 0f
            selectedTile != null -> tileTable.packIfNeeded().width
            selectedConstruction != null -> selectedConstructionTable.packIfNeeded().width
            else -> posFromEdge
        }
        val leftMargin = when {
            !isPortrait() -> 0f
            else -> constructionsTable.getLowerWidth()
        }

        // Bottom center: Name, paging, exit city button
        val centeredX = (stage.width - leftMargin - rightMargin) / 2 + leftMargin
        exitCityButton.setPosition(centeredX, 10f, Align.bottom)
        cityPickerTable.update()
        cityPickerTable.setPosition(centeredX, exitCityButton.top + 10f, Align.bottom)

        // Top right of screen: Stats / Specialists
        updateCityStats()

        // Top center: Annex/Raze button
        updateAnnexAndRazeCityButton()

    }

    private fun updateCityStats() {
        var statsHeight = stage.height - posFromEdge * 2
        if (selectedTile != null)
            statsHeight -= tileTable.top + 10f
        if (selectedConstruction != null)
            statsHeight -= selectedConstructionTable.top + 10f
        cityStatsTable.update(statsHeight)
        cityStatsTable.setPosition(
            stage.width - posFromEdge,
            stage.height - posFromEdge,
            Align.topRight
        )
    }

    fun canCityBeChanged(): Boolean {
        return canChangeState && !city.isPuppet
    }

    private fun updateTileGroups() {
        fun isExistingImprovementValuable(tile: Tile): Boolean {
            val improvement = tile.tileImprovement ?: return false
            val civInfo = city.civ

            val statDiffForNewImprovement = tile.stats.getStatDiffForImprovement(
                improvement,
                civInfo,
                city,
            )

            // If stat diff for new improvement is negative/zero utility, current improvement is valuable
            return Automation.rankStatsValue(statDiffForNewImprovement, civInfo) <= 0
        }

        fun getPickImprovementColor(tile: Tile): Pair<Color, Float> {
            val improvementToPlace = pickTileData!!.improvement
            val projectedLegal = if (!isAuthoritativeGame()) null else {
                val data = pickTileData!!
                val targets = if (data.isBuying) authoritativeProjectedPurchase(
                    data.building.name,
                    data.buyStat.name,
                    selectedQueueEntry.takeIf { it >= 0 },
                )?.legalTargets else authoritativeProjectedCity()?.constructionOptions
                    ?.singleOrNull { it.name == data.building.name }
                    ?.placementTargets
                targets?.any { it.x == tile.position.x && it.y == tile.position.y } == true
            }
            return when {
                tile.isMarkedForCreatesOneImprovement() -> Color.BROWN to 0.7f
                projectedLegal == false -> Color.RED to 0.4f
                projectedLegal == null &&
                    !city.cityConstructions.canPlaceCreateOneImprovementOn(improvementToPlace, tile) ->
                    Color.RED to 0.4f
                isExistingImprovementValuable(tile) -> Color.ORANGE to 0.5f
                tile.improvement != null -> Color.YELLOW to 0.6f
                tile.turnsToImprovement > 0 -> Color.YELLOW to 0.6f
                else -> Color.GREEN to 0.5f
            }
        }

        for (tileGroup in tileGroups) {
            tileGroup.update(selectedCiv)
            tileGroup.layerMisc.removeHexOutline()
            if (isSpying) continue // the rest is only for own cities

            if (tileGroup.tileState == CityTileState.BLOCKADED)
                displayTutorial(TutorialTrigger.CityTileBlockade)

            when {
                tileGroup.tile == nextTileToOwn ->
                    tileGroup.layerMisc.addHexOutline(colorFromRGB(200, 20, 220))
                /** Support for [UniqueType.CreatesOneImprovement] */
                tileGroup.tile == selectedQueueEntryTargetTile ->
                    tileGroup.layerMisc.addHexOutline(Color.BROWN)
                pickTileData != null && tileGroup.tile.getCity() == city && tileGroup.tile in city.tilesInRange ->
                    getPickImprovementColor(tileGroup.tile).run {
                        tileGroup.layerMisc.addHexOutline(first.cpy().apply { this.a = second }) }
            }

            if (fireworks != null && tileGroup.tile.position == city.location)
                fireworks.setActorBounds(tileGroup)
        }
    }

    private fun updateAnnexAndRazeCityButton() {
        razeCityButtonHolder.clear()

        fun addWltkIcon(name: String, apply: Image.()->Unit = {}) =
            razeCityButtonHolder.add(ImageGetter.getImage(name).apply(apply)).size(wltkIconSize)

        if (isWLTKday && fireworks == null) {
            addWltkIcon("OtherIcons/WLTK LR") { color = Color.GOLD }
            addWltkIcon("OtherIcons/WLTK 1") { color = Color.FIREBRICK }.padRight(10f)
        }

        val canAnnex = !city.civ.hasUnique(UniqueType.MayNotAnnexCities)
        if (city.isPuppet && canAnnex) {
            val annexCityButton = "Annex city".toTextButton()
            annexCityButton.labelCell.pad(10f)
            annexCityButton.onClick {
                if (isAuthoritativeGame())
                    submitAuthoritativeCityGovernance(CityGovernanceAction.Annex)
                else {
                    city.annexCity()
                    update()
                }
            }
            if (!canChangeState) annexCityButton.disable()
            razeCityButtonHolder.add(annexCityButton) //.colspan(cityPickerTable.columns)
        } else if (!city.isBeingRazed) {
            val razeCityButton = "Raze city".toTextButton()
            razeCityButton.labelCell.pad(10f)
            razeCityButton.onClick {
                if (isAuthoritativeGame())
                    submitAuthoritativeCityGovernance(CityGovernanceAction.StartRazing)
                else {
                    city.isBeingRazed = true
                    update()
                }
            }
            if (!canChangeState ||
                (!isAuthoritativeGame() && (!city.canBeDestroyed() || !canAnnex))) {
                razeCityButton.disable()
            }

            razeCityButtonHolder.add(razeCityButton) //.colspan(cityPickerTable.columns)
        } else {
            val stopRazingCityButton = "Stop razing city".toTextButton()
            stopRazingCityButton.labelCell.pad(10f)
            stopRazingCityButton.onClick {
                if (isAuthoritativeGame())
                    submitAuthoritativeCityGovernance(CityGovernanceAction.StopRazing)
                else {
                    city.isBeingRazed = false
                    update()
                }
            }
            if (!canChangeState) stopRazingCityButton.disable()
            razeCityButtonHolder.add(stopRazingCityButton) //.colspan(cityPickerTable.columns)
        }

        if (isWLTKday && fireworks == null) {
            addWltkIcon("OtherIcons/WLTK 2") { color = Color.FIREBRICK }.padLeft(10f)
            addWltkIcon("OtherIcons/WLTK LR") {
                color = Color.GOLD
                scaleX = -scaleX
                originX = wltkIconSize * 0.5f
            }
        }

        razeCityButtonHolder.pack()
        if(isCrampedPortrait()) {
            // cramped portrait: move raze button down to city picker
            val centerX = cityPickerTable.x + cityPickerTable.width / 2 - razeCityButtonHolder.width / 2
            razeCityButtonHolder.setPosition(centerX, cityPickerTable.y + cityPickerTable.height + 10)
            // and also re-position the tooltips, which would otherwise be covered
            tileTable.setPosition(stage.width - posFromEdge, razeCityButtonHolder.top + 10f, Align.bottomRight)
            selectedConstructionTable.setPosition(stage.width - posFromEdge, razeCityButtonHolder.top + 10f, Align.bottomRight)
            updateCityStats() // limit city stats height according to the tooltips
        } else {
            val centerX = if (isPortrait())
                constructionsTable.getUpperWidth().let { it + (stage.width - cityStatsTable.width - it) / 2 }
            else
                stage.width / 2
            razeCityButtonHolder.setPosition(centerX, stage.height - 20f, Align.top)
        }
        stage.addActor(razeCityButtonHolder)
    }

    private fun addTiles() {
        val viewRange = max(city.getExpandRange(), city.getWorkRange())
        val tileSetStrings = TileSetStrings(city.civ.gameInfo.ruleset, game.settings)
        city.getCenterTile().forEachTileInDistance(viewRange) { tile ->
            if (!selectedCiv.hasExplored(tile)) return@forEachTileInDistance
            val tileGroup = CityTileGroup(city, tile, tileSetStrings, false, isSpying)
            tileGroup.onClick { tileGroupOnClick(tileGroup, city) }
            tileGroup.layerMisc.onWorkedIconClick = {
                tileWorkedIconOnClick(tileGroup, city)
                tileGroupOnClick(tileGroup, city)
            }
            tileGroup.layerMisc.onWorkedIconDoubleClick = { tileWorkedIconDoubleClick(tileGroup, city) }
            tileGroups.add(tileGroup)
        }

        val tilesToUnwrap = mutableSetOf<CityTileGroup>()
        for (tileGroup in tileGroups) {
            val xDifference = city.getCenterTile().position.x - tileGroup.tile.position.x
            val yDifference = city.getCenterTile().position.y - tileGroup.tile.position.y
            //if difference is bigger than the expansion range the tileGroup we are looking for is on the other side of the map
            if (xDifference > viewRange || xDifference < -viewRange || yDifference > viewRange || yDifference < -viewRange) {
                //so we want to unwrap its position
                tilesToUnwrap.add(tileGroup)
            }
        }

        val tileMapGroup = TileGroupMap(mapScrollPane, tileGroups, tileGroupsToUnwrap = tilesToUnwrap)
        mapScrollPane.actor = tileMapGroup
        mapScrollPane.setSize(stage.width, stage.height)
        stage.addActor(mapScrollPane)

        mapScrollPane.layout() // center scrolling
        mapScrollPane.scrollPercentX = 0.5f
        mapScrollPane.scrollPercentY = 0.5f
        mapScrollPane.updateVisualScroll()
    }

    // We contain a map...
    override fun getShortcutDispatcherVetoer() = KeyShortcutDispatcherVeto.createTileGroupMapDispatcherVetoer()

    private fun tileWorkedIconOnClick(tileGroup: CityTileGroup, city: City) {

        if (!canChangeState || city.isPuppet) return
        val tile = tileGroup.tile

        // Cycling as: Not-worked -> Worked  -> Not-worked
        if (tileGroup.tileState == CityTileState.WORKABLE) {
            if (isAuthoritativeGame()) {
                submitAuthoritativeCityTileAssignment(
                    tile,
                    if (city.isWorked(tile)) CityTileAssignment.Unworked else CityTileAssignment.Worked,
                )
                return
            }
            if (!tile.providesYield() && city.population.getFreePopulation() > 0) {
                city.workedTiles.add(tile.position)
                game.settings.addCompletedTutorialTask("Reassign worked tiles")
            } else {
                city.workedTiles.remove(tile.position)
                city.lockedTiles.remove(tile.position)
            }
            city.cityStats.update()
            update()

        } else if (tileGroup.tileState == CityTileState.PURCHASABLE) {
            askToBuyTile(tile)
        }
    }

    internal fun submitAuthoritativeCityTileAssignment(
        tile: Tile,
        assignment: CityTileAssignment,
    ) {
        if (authoritativeTileAssignmentSubmissionInProgress) return
        authoritativeTileAssignmentSubmissionInProgress = true
        Concurrency.runOnNonDaemonThreadPool("Set authoritative city tile assignment") {
            val outcome = try {
                game.onlineMultiplayer.authoritativeSession?.setCityTileAssignmentIfOpen(
                    city.civ.gameInfo.gameId,
                    city.id,
                    tile.position.x,
                    tile.position.y,
                    assignment,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeTileAssignmentSubmissionInProgress = false
                    ToastPopup("Could not submit tile assignment: [${ex.message ?: "Unknown"}]", this@CityScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("City tile assignment committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - tile assignment was not changed", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeTileAssignmentSubmissionInProgress = false
                        ToastPopup("Server rejected tile assignment: [${outcome.code}]", this@CityScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeTileAssignmentSubmissionInProgress = false
                        ToastPopup("Server response was lost - retry will use the same command", this@CityScreen)
                    }
                    null -> {
                        authoritativeTileAssignmentSubmissionInProgress = false
                        ToastPopup("Authoritative game was closed before tile assignment", this@CityScreen)
                    }
                }
            }
        }
    }

    internal fun submitAuthoritativeSpecialistCount(
        specialistName: String,
        count: Int,
    ) {
        if (authoritativeSpecialistSubmissionInProgress) return
        authoritativeSpecialistSubmissionInProgress = true
        Concurrency.runOnNonDaemonThreadPool("Set authoritative specialist count") {
            val outcome = try {
                game.onlineMultiplayer.authoritativeSession?.setSpecialistCountIfOpen(
                    city.civ.gameInfo.gameId,
                    city.id,
                    specialistName,
                    count,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeSpecialistSubmissionInProgress = false
                    ToastPopup("Could not submit specialist assignment: [${ex.message ?: "Unknown"}]", this@CityScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Specialist assignment committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - specialist assignment was not changed", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeSpecialistSubmissionInProgress = false
                        ToastPopup("Server rejected specialist assignment: [${outcome.code}]", this@CityScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeSpecialistSubmissionInProgress = false
                        ToastPopup("Server response was lost - retry will use the same command", this@CityScreen)
                    }
                    null -> {
                        authoritativeSpecialistSubmissionInProgress = false
                        ToastPopup("Authoritative game was closed before specialist assignment", this@CityScreen)
                    }
                }
            }
        }
    }

    internal fun submitAuthoritativeManualSpecialists(enabled: Boolean) {
        if (authoritativeSpecialistSubmissionInProgress) return
        authoritativeSpecialistSubmissionInProgress = true
        Concurrency.runOnNonDaemonThreadPool("Set authoritative specialist mode") {
            val outcome = try {
                game.onlineMultiplayer.authoritativeSession?.setManualSpecialistsIfOpen(
                    city.civ.gameInfo.gameId,
                    city.id,
                    enabled,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeSpecialistSubmissionInProgress = false
                    ToastPopup("Could not submit specialist mode: [${ex.message ?: "Unknown"}]", this@CityScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Specialist mode committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - specialist mode was not changed", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeSpecialistSubmissionInProgress = false
                        ToastPopup("Server rejected specialist mode: [${outcome.code}]", this@CityScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeSpecialistSubmissionInProgress = false
                        ToastPopup("Server response was lost - retry will use the same command", this@CityScreen)
                    }
                    null -> {
                        authoritativeSpecialistSubmissionInProgress = false
                        ToastPopup("Authoritative game was closed before specialist mode", this@CityScreen)
                    }
                }
            }
        }
    }

    internal fun submitAuthoritativeCitizenReset() {
        if (authoritativeCitizenManagementSubmissionInProgress) return
        authoritativeCitizenManagementSubmissionInProgress = true
        Concurrency.runOnNonDaemonThreadPool("Reset authoritative citizens") {
            val outcome = try {
                game.onlineMultiplayer.authoritativeSession?.resetCitizensIfOpen(
                    city.civ.gameInfo.gameId,
                    city.id,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeCitizenManagementSubmissionInProgress = false
                    ToastPopup("Could not submit citizen reset: [${ex.message ?: "Unknown"}]", this@CityScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Citizen reset committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - citizens were not reset", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeCitizenManagementSubmissionInProgress = false
                        ToastPopup("Server rejected citizen reset: [${outcome.code}]", this@CityScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeCitizenManagementSubmissionInProgress = false
                        ToastPopup("Server response was lost - retry will use the same command", this@CityScreen)
                    }
                    null -> {
                        authoritativeCitizenManagementSubmissionInProgress = false
                        ToastPopup("Authoritative game was closed before citizen reset", this@CityScreen)
                    }
                }
            }
        }
    }

    internal fun submitAuthoritativeAvoidGrowth(enabled: Boolean) =
        submitAuthoritativeCitizenPolicy("growth policy") {
            game.onlineMultiplayer.authoritativeSession?.setAvoidGrowthIfOpen(
                city.civ.gameInfo.gameId,
                city.id,
                enabled,
            )
        }

    internal fun submitAuthoritativeCitizenFocus(focus: CityFocus) =
        submitAuthoritativeCitizenPolicy("citizen focus") {
            game.onlineMultiplayer.authoritativeSession?.setCitizenFocusIfOpen(
                city.civ.gameInfo.gameId,
                city.id,
                CitizenFocus.valueOf(focus.name),
            )
        }

    internal fun submitAuthoritativeUnitPromotionPreference(
        baseUnitName: String,
        enabled: Boolean,
    ) = submitAuthoritativeCitizenPolicy("unit promotion preference") {
        game.onlineMultiplayer.authoritativeSession?.setCityUnitPromotionPreferenceIfOpen(
            city.civ.gameInfo.gameId,
            city.id,
            baseUnitName,
            enabled,
        )
    }

    private fun submitAuthoritativeCitizenPolicy(
        description: String,
        submit: suspend () -> AuthoritativeCommandOutcome?,
    ) {
        if (authoritativeCitizenManagementSubmissionInProgress) return
        authoritativeCitizenManagementSubmissionInProgress = true
        Concurrency.runOnNonDaemonThreadPool("Set authoritative $description") {
            val outcome = try {
                submit()
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeCitizenManagementSubmissionInProgress = false
                    ToastPopup("Could not submit $description: [${ex.message ?: "Unknown"}]", this@CityScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("${description.replaceFirstChar { it.uppercase() }} committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - $description was not changed", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeCitizenManagementSubmissionInProgress = false
                        ToastPopup("Server rejected $description: [${outcome.code}]", this@CityScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeCitizenManagementSubmissionInProgress = false
                        ToastPopup("Server response was lost - retry will use the same command", this@CityScreen)
                    }
                    null -> {
                        authoritativeCitizenManagementSubmissionInProgress = false
                        ToastPopup("Authoritative game was closed before $description", this@CityScreen)
                    }
                }
            }
        }
    }

    internal fun submitAuthoritativeBuildingSale(buildingName: String) =
        submitAuthoritativeCitizenPolicy("building sale") {
            game.onlineMultiplayer.authoritativeSession?.sellBuildingIfOpen(
                city.civ.gameInfo.gameId,
                city.id,
                buildingName,
            )
        }

    private fun submitAuthoritativeCityGovernance(action: CityGovernanceAction) =
        submitAuthoritativeCitizenPolicy("city governance") {
            game.onlineMultiplayer.authoritativeSession?.setCityGovernanceIfOpen(
                city.civ.gameInfo.gameId,
                city.id,
                action,
            )
        }

    /** Ask whether user wants to buy [selectedTile] for gold.
     *
     * Used from onClick and keyboard dispatch, thus only minimal parameters are passed,
     * and it needs to do all checks and the sound as appropriate.
     */
    internal fun askToBuyTile(selectedTile: Tile) {
        // These checks are redundant for the onClick action, but not for the keyboard binding
        if (!canChangeState) return
        val authoritative = isAuthoritativeGame()
        val projectedPurchase = if (authoritative) authoritativeProjectedCity()?.tilePurchases
            ?.singleOrNull { it.x == selectedTile.position.x && it.y == selectedTile.position.y }
        else null
        if (authoritative && (projectedPurchase == null || !projectedPurchase.affordable)) return
        if (!authoritative && !city.expansion.canBuyTile(selectedTile)) return
        val goldCostOfTile = projectedPurchase?.goldCost
            ?: city.expansion.getGoldCostOfTile(selectedTile)
        if (!authoritative && !city.civ.hasStatToBuy(Stat.Gold, goldCostOfTile)) return
        val goldBalance = if (authoritative) authoritativeProjection()?.gold ?: return else city.civ.gold

        closeAllPopups()

        val purchasePrompt = "Currently you have [$goldBalance] [Gold].".tr() + "\n\n" +
            "Would you like to purchase [Tile] for [$goldCostOfTile] [${Stat.Gold.character}]?".tr()
        ConfirmPopup(
            this,
            purchasePrompt,
            "Purchase",
            true,
            restoreDefault = { update() }
        ) {
            if (isAuthoritativeGame()) submitAuthoritativeTilePurchase(selectedTile)
            else {
                SoundPlayer.play(UncivSound.Coin)
                city.expansion.buyTile(selectedTile)
                // preselect the next tile on city screen rebuild so bulk buying can go faster
                UncivGame.Current.replaceCurrentScreen(CityScreen(city, initSelectedTile = city.expansion.chooseNewTileToOwn()))
            }
        }.open()
    }

    private fun submitAuthoritativeTilePurchase(selectedTile: Tile) {
        if (authoritativeTilePurchaseSubmissionInProgress) return
        authoritativeTilePurchaseSubmissionInProgress = true
        Concurrency.runOnNonDaemonThreadPool("Buy authoritative city tile") {
            val outcome = try {
                game.onlineMultiplayer.authoritativeSession?.buyCityTileIfOpen(
                    city.civ.gameInfo.gameId,
                    city.id,
                    selectedTile.position.x,
                    selectedTile.position.y,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeTilePurchaseSubmissionInProgress = false
                    ToastPopup("Could not submit tile purchase: [${ex.message ?: "Unknown"}]", this@CityScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        SoundPlayer.play(UncivSound.Coin)
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Tile purchase committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - tile was not purchased", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeTilePurchaseSubmissionInProgress = false
                        ToastPopup("Server rejected tile purchase: [${outcome.code}]", this@CityScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeTilePurchaseSubmissionInProgress = false
                        ToastPopup("Server response was lost - retry will use the same command", this@CityScreen)
                    }
                    null -> {
                        authoritativeTilePurchaseSubmissionInProgress = false
                        ToastPopup("Authoritative game was closed before tile purchase", this@CityScreen)
                    }
                }
            }
        }
    }

    private fun submitAuthoritativeTileConstruction(building: Building, selectedTile: Tile) {
        if (authoritativeTileConstructionSubmissionInProgress) return
        authoritativeTileConstructionSubmissionInProgress = true
        Concurrency.runOnNonDaemonThreadPool("Queue authoritative tile construction") {
            val outcome = try {
                game.onlineMultiplayer.authoritativeSession?.queueConstructionAtTileIfOpen(
                    city.civ.gameInfo.gameId,
                    city.id,
                    building.name,
                    selectedTile.position.x,
                    selectedTile.position.y,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    authoritativeTileConstructionSubmissionInProgress = false
                    ToastPopup("Could not submit tile construction: [${ex.message ?: "Unknown"}]", this@CityScreen)
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Tile construction committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        city.civ.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - construction was not queued", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        authoritativeTileConstructionSubmissionInProgress = false
                        ToastPopup("Server rejected tile construction: [${outcome.code}]", this@CityScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        authoritativeTileConstructionSubmissionInProgress = false
                        ToastPopup("Server response was lost - retry will use the same command", this@CityScreen)
                    }
                    null -> {
                        authoritativeTileConstructionSubmissionInProgress = false
                        ToastPopup("Authoritative game was closed before tile construction", this@CityScreen)
                    }
                }
            }
        }
    }


    private fun tileWorkedIconDoubleClick(tileGroup: CityTileGroup, city: City) {
        if (!canChangeState || city.isPuppet || tileGroup.tileState != CityTileState.WORKABLE) return
        val tile = tileGroup.tile

        if (isAuthoritativeGame()) {
            submitAuthoritativeCityTileAssignment(tile, CityTileAssignment.Locked)
            return
        }

        // Double-click should lead to locked tiles - both for unworked AND worked tiles

        if (!tile.isWorked()) // If not worked, try to work it first
            tileWorkedIconOnClick(tileGroup, city)

        if (tile.isWorked())
            city.lockedTiles.add(tile.position)

        update()
    }

    private fun tileGroupOnClick(tileGroup: CityTileGroup, city: City) {
        if (city.isPuppet) return
        val tileInfo = tileGroup.tile

        /** [UniqueType.CreatesOneImprovement] support - select tile for improvement */
        if (pickTileData != null) {
            val pickTileData = this.pickTileData!!
            this.pickTileData = null
            val improvement = pickTileData.improvement
            val projectedTarget = if (!isAuthoritativeGame()) false else {
                val queueIndex = selectedQueueEntry.takeIf { it >= 0 }
                if (pickTileData.isBuying)
                    authoritativeProjectedPurchase(
                        pickTileData.building.name, pickTileData.buyStat.name, queueIndex,
                    )?.legalTargets?.any {
                        it.x == tileInfo.position.x && it.y == tileInfo.position.y
                    } == true
                else authoritativeProjectedCity()?.constructionOptions
                    ?.singleOrNull { it.name == pickTileData.building.name }
                    ?.placementTargets?.any {
                        it.x == tileInfo.position.x && it.y == tileInfo.position.y
                    } == true
            }
            if (projectedTarget || !isAuthoritativeGame() &&
                city.cityConstructions.canPlaceCreateOneImprovementOn(improvement, tileInfo)) {
                
                if (isAuthoritativeGame() && !pickTileData.isBuying) {
                    submitAuthoritativeTileConstruction(pickTileData.building, tileInfo)
                } else if (pickTileData.isBuying) {
                    BuyButtonFactory(this).askToBuyConstruction(pickTileData.building, pickTileData.buyStat, tileInfo)
                } else {
                    city.cityConstructions.addToQueue(pickTileData.building, tile = tileInfo)
                }
            }
            update()
            return
        }

        selectTile(tileInfo)
        update()
    }

    /** Convenience shortcut to [CivConstructions.hasFreeBuilding][com.unciv.logic.civilization.CivConstructions.hasFreeBuilding], nothing more */
    internal fun hasFreeBuilding(building: Building) =
        city.civ.civConstructions.hasFreeBuilding(city, building)

    fun selectConstructionFromQueue(index: Int) {
        selectConstruction(city.cityConstructions.constructionQueue[index])
    }
    fun selectConstruction(name: String) {
        selectConstruction(city.cityConstructions.getConstruction(name))
    }
    fun selectConstruction(newConstruction: IConstruction) {
        selectedConstruction = newConstruction
        if (newConstruction is Building && newConstruction.hasCreateOneImprovementUnique()) {
            val improvement = newConstruction.getImprovementToCreate(city.getRuleset(), city.civ)
            selectedQueueEntryTargetTile = if (improvement == null) null
                else city.cityConstructions.getTileForImprovement(improvement.name)
        } else {
            selectedQueueEntryTargetTile = null
            pickTileData = null
        }
        selectedTile = null
    }
    private fun selectTile(newTile: Tile?) {
        selectedConstruction = null
        selectedQueueEntryTargetTile = null
        pickTileData = null
        selectedTile = newTile
    }
    fun clearSelection() = selectTile(null)

    fun startPickTileForCreatesOneImprovement(construction: Building, stat: Stat, isBuying: Boolean) {
        val improvement = construction.getImprovementToCreate(city.getRuleset(), city.civ) ?: return
        pickTileData = PickTileForImprovementData(construction, improvement, isBuying, stat)
        updateTileGroups()
        ToastPopup("Please select a tile for this building's [${improvement.name}]", this)
    }
    fun stopPickTileForCreatesOneImprovement() {
        if (pickTileData == null) return
        pickTileData = null
        updateTileGroups()
    }

    fun exit() {
        val newScreen = game.popScreen()
        if (newScreen is WorldScreen) {
            newScreen.mapHolder.setCenterPosition(city.location.toHexCoord(), immediately = true)
            newScreen.bottomUnitTable.selectUnit()
        }
    }

    private fun passOnCityAmbiencePlayer(): CityAmbiencePlayer? {
        val player = cityAmbiencePlayer
        cityAmbiencePlayer = null
        return player
    }

    fun page(delta: Int) {
        // Normal order is create new, then dispose old. But CityAmbiencePlayer delegates to a single instance of MusicController,
        // leading to one extra play followed by a stop for the city ambience sounds. To avoid that, we pass our player on and relinquish control.

        val numCities = viewableCities.size
        if (numCities == 0) return
        val indexOfCity = viewableCities.indexOf(city)
        val indexOfNextCity = (indexOfCity + delta + numCities) % numCities
        val newCityScreen = CityScreen(viewableCities[indexOfNextCity], ambiencePlayer = passOnCityAmbiencePlayer())
        newCityScreen.mapScrollPane.zoom(mapScrollPane.scaleX) // Retain zoom
        newCityScreen.update()
        game.replaceCurrentScreen(newCityScreen)
    }

    // Don't use passOnCityAmbiencePlayer here - continuing play on the replacement screen would be nice,
    // but the rapid firing of several resize events will get that un-synced, they would no longer stop on leaving.
    override fun recreate(): BaseScreen = CityScreen(city, selectedConstruction, selectedTile)

    override fun dispose() {
        cityAmbiencePlayer?.dispose()
        fireworks?.dispose()
        super.dispose()
    }

    override fun render(delta: Float) {
        super.render(delta)
        if (pauseFireworks) return
        fireworks?.render(stage, delta)
    }
}
