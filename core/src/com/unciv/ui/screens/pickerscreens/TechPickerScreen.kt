package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TechManager
import com.unciv.logic.multiplayer.authoritative.AuthoritativeCommandOutcome
import com.unciv.logic.multiplayer.authoritative.ProjectedResearch
import com.unciv.logic.multiplayer.authoritative.ResearchQueueAction
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.NonTransformGroup
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency
import kotlinx.coroutines.CancellationException
import yairm210.purity.annotations.Readonly
import kotlin.math.abs


class TechPickerScreen(
    internal val civInfo: Civilization,
    centerOnTech: Technology? = null,
) : PickerScreen() {

    private val initialAuthoritativeResearch = if (isAuthoritativeGame())
        game.onlineMultiplayer.authoritativeSession
            ?.cachedProjectionIfOpen(civInfo.gameInfo.gameId)
            ?.research
    else null
    private val freeTechPick: Boolean = initialAuthoritativeResearch
        ?.freeTechnologyChoices
        ?.isNotEmpty()
        ?: (civInfo.tech.freeTechs != 0)
    private val ruleset = civInfo.gameInfo.ruleset
    private var techNameToButton = HashMap<String, TechButton>()
    private var selectedTech: Technology? = null
    private var civTech: TechManager = civInfo.tech
    private var tempTechsToResearch: ArrayList<String>
    private var authoritativeAppendSelection = false
    private var authoritativeResearch: ProjectedResearch? = initialAuthoritativeResearch
    private var lines = NonTransformGroup()
    private var orderIndicators = NonTransformGroup()
    private var eraLabels = ArrayList<Label>()

    private fun isAuthoritativeGame() = civInfo.gameInfo.gameParameters.isOnlineMultiplayer &&
        game.onlineMultiplayer.authoritativeSession?.isGameOpen(civInfo.gameInfo.gameId) == true

    /** We need this to be a separate table, and NOT the topTable, because *inhales*
     * When call setConnectingLines we need to pack() the table so that the lines will align correctly, BUT
     *  this causes the table to be SMALLER THAN THE SCREEN for small tech trees from mods,
     *  meaning the tech tree is in a crumpled heap at the lower-left corner of the screen
     * Having this be a separate table allows us to leave the TopTable as is (that is: auto-width to fit the scrollPane)
     *  leaving us the juicy small tech tree right in the center.
     */
    private val techTable = object : Table(){
        override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    }

    // All these are to counter performance problems when updating buttons for all techs.
    private var researchableTechs = if (isAuthoritativeGame()) hashSetOf() else
        ruleset.technologies.keys.filter { civTech.canBeResearched(it) }.toHashSet()

    private val currentTechColor = skinStrings.getUIColor("TechPickerScreen/CurrentTechColor", colorFromRGB(72, 147, 175))
    private val researchedTechColor = skinStrings.getUIColor("TechPickerScreen/ResearchedTechColor", colorFromRGB(255, 215, 0))
    private val researchableTechColor = skinStrings.getUIColor("TechPickerScreen/ResearchableTechColor", colorFromRGB(28, 170, 0))
    private val queuedTechColor = skinStrings.getUIColor("TechPickerScreen/QueuedTechColor", colorFromRGB(7*2, 46*2, 43*2))
    private val researchedFutureTechColor = skinStrings.getUIColor("TechPickerScreen/ResearchedFutureTechColor", colorFromRGB(127, 50, 0))

    private val turnsToTech = if (isAuthoritativeGame()) emptyMap() else
        ruleset.technologies.values.associateBy({ it.name }, { civTech.turnsToTech(it.name) })

    init {
        setDefaultCloseAction()
        scrollPane.setOverscroll(false, false)

        descriptionLabel.onClick {
            if (selectedTech != null)
                openCivilopedia(selectedTech!!.makeLink())
        }

        tempTechsToResearch = ArrayList(civTech.techsToResearch)

        createTechTable()
        setButtonsInfo()
        techTable.addActor(lines)
        techTable.addActor(orderIndicators)
        topTable.add(techTable)
        techTable.background = skinStrings.getUiBackground("TechPickerScreen/Background", tintColor = skinStrings.skinConfig.clearColor)
        pickerPane.bottomTable.background = skinStrings.getUiBackground("TechPickerScreen/BottomTable", tintColor = skinStrings.skinConfig.clearColor)
        
        rightSideButton.setText(if (freeTechPick) "Pick a free tech".tr() else "Pick a tech".tr())
        rightSideButton.onClick(UncivSound.Paper) { tryExit() }

        loadAuthoritativeResearchProjection()

        // per default show current/recent technology,
        // and possibly select it to show description,
        // which is very helpful when just discovered and clicking the notification
        val tech = centerOnTech ?: civInfo.tech.currentTechnology()
        if (tech != null) {
            // select only if there it doesn't mess up tempTechsToResearch
            if (isAuthoritativeGame())
                centerOnTechnology(tech)
            else if (civInfo.tech.isResearched(tech.name) || civInfo.tech.techsToResearch.size <= 1)
                selectTechnology(tech, queue = false, center = true)
            else centerOnTechnology(tech)
        } else {
            // center on any possible technology which is ready for the research right now
            val firstAvailable = researchableTechs.firstOrNull()
            val firstAvailableTech = ruleset.technologies[firstAvailable]
            if (firstAvailableTech != null)
                centerOnTechnology(firstAvailableTech)
        }
    }

    private fun loadAuthoritativeResearchProjection() {
        if (!isAuthoritativeGame()) return
        rightSideButton.setText("Loading authoritative research".tr())
        rightSideButton.disable()
        Concurrency.runOnNonDaemonThreadPool("Load authoritative research projection") {
            val projectedResearch = try {
                game.onlineMultiplayer.authoritativeSession
                    ?.projectionIfOpen(civInfo.gameInfo.gameId)
                    ?.research
            } catch (_: Exception) {
                null
            }
            Concurrency.runOnGLThread {
                if (projectedResearch == null) {
                    rightSideButton.setText("Authoritative research unavailable".tr())
                    rightSideButton.disable()
                    return@runOnGLThread
                }
                authoritativeResearch = projectedResearch
                tempTechsToResearch = ArrayList(projectedResearch.queue)
                researchableTechs = if (freeTechPick)
                    projectedResearch.freeTechnologyChoices.toHashSet()
                else
                    (projectedResearch.selectableTargets + projectedResearch.appendableTargets).toHashSet()
                setButtonsInfo()
                projectedResearch.queueEntries.forEachIndexed { index, entry ->
                    val techButton = techNameToButton[entry.technologyName]
                        ?: return@forEachIndexed
                    techButton.addContextMenu {
                        ResearchQueueMenu(stage, techButton, entry.availableActions) { action ->
                            submitAuthoritativeResearchQueueAction(
                                entry.technologyName,
                                index,
                                action,
                            )
                        }
                    }
                }
                selectedTech?.let { selectTechnology(it, authoritativeAppendSelection, center = false) }
            }
        }
    }

    private fun submitAuthoritativeResearchQueueAction(
        technologyName: String,
        queueIndex: Int,
        action: ResearchQueueAction,
    ) {
        rightSideButton.disable()
        Concurrency.runOnNonDaemonThreadPool("Manage authoritative research queue") {
            val outcome = try {
                game.onlineMultiplayer.authoritativeSession?.manageResearchQueueIfOpen(
                    civInfo.gameInfo.gameId,
                    technologyName,
                    queueIndex,
                    action,
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Concurrency.runOnGLThread {
                    rightSideButton.enable()
                    ToastPopup(
                        "Could not manage authoritative research: [${ex.message ?: "Unknown"}]",
                        this@TechPickerScreen,
                    )
                }
                return@runOnNonDaemonThreadPool
            }
            Concurrency.runOnGLThread {
                when (outcome) {
                    is AuthoritativeCommandOutcome.Accepted -> {
                        civInfo.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Research queue committed by the authoritative server", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.StaleRefreshed -> {
                        civInfo.gameInfo.isUpToDate = false
                        game.popScreen()
                        ToastPopup("Game changed on the server - research queue was not changed", GUI.getWorldScreen())
                    }
                    is AuthoritativeCommandOutcome.Rejected -> {
                        rightSideButton.enable()
                        ToastPopup("Server rejected research queue action: [${outcome.code}]", this@TechPickerScreen)
                    }
                    AuthoritativeCommandOutcome.RetryRequired -> {
                        rightSideButton.enable()
                        ToastPopup("Server response was lost - retry will use the same command", this@TechPickerScreen)
                    }
                    null -> {
                        rightSideButton.enable()
                        ToastPopup("Authoritative game was closed before research changed", this@TechPickerScreen)
                    }
                }
            }
        }
    }

    override fun getCivilopediaRuleset() = ruleset


    private fun tryExit() {
        if (isAuthoritativeGame()) {
            val technologyName = selectedTech?.name ?: return
            rightSideButton.disable()
            Concurrency.runOnNonDaemonThreadPool("Set authoritative technology") {
                val outcome = try {
                    if (freeTechPick)
                        game.onlineMultiplayer.authoritativeSession?.chooseFreeTechnologyIfOpen(
                            civInfo.gameInfo.gameId,
                            technologyName,
                        )
                    else game.onlineMultiplayer.authoritativeSession?.setResearchPathIfOpen(
                            civInfo.gameInfo.gameId,
                            technologyName,
                            authoritativeAppendSelection,
                        )
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                    Concurrency.runOnGLThread {
                        setButtonsInfo()
                        rightSideButton.enable()
                        ToastPopup(
                            "Could not submit authoritative technology: [${ex.message ?: "Unknown"}]",
                            this@TechPickerScreen,
                        )
                    }
                    return@runOnNonDaemonThreadPool
                }
                Concurrency.runOnGLThread {
                    if (outcome == null) {
                        setButtonsInfo()
                        rightSideButton.enable()
                        ToastPopup("Authoritative game was closed before the technology could be submitted", this@TechPickerScreen)
                        return@runOnGLThread
                    }
                    when (outcome) {
                        is AuthoritativeCommandOutcome.Accepted -> {
                            civInfo.gameInfo.isUpToDate = false
                            game.settings.addCompletedTutorialTask("Pick technology")
                            game.popScreen()
                            ToastPopup(
                                if (freeTechPick) "Free technology committed by the authoritative server"
                                else "Research committed by the authoritative server",
                                GUI.getWorldScreen(),
                            )
                        }
                        is AuthoritativeCommandOutcome.StaleRefreshed -> {
                            civInfo.gameInfo.isUpToDate = false
                            game.popScreen()
                            ToastPopup("Game changed on the server - technology was not submitted", GUI.getWorldScreen())
                        }
                        is AuthoritativeCommandOutcome.Rejected -> {
                            setButtonsInfo()
                            rightSideButton.enable()
                            ToastPopup("Server rejected technology: [${outcome.code}]", this@TechPickerScreen)
                        }
                        AuthoritativeCommandOutcome.RetryRequired -> {
                            setButtonsInfo()
                            rightSideButton.enable()
                            ToastPopup("Server response was lost - retry will use the same command", this@TechPickerScreen)
                        }
                    }
                }
            }
            return
        }
        finishLocalSelection()
    }

    private fun finishLocalSelection() {
        if (freeTechPick) {
            val freeTech = selectedTech!!.name
            // More evil people fast-clicking to cheat - #4977
            if (!researchableTechs.contains(freeTech)) return
            civTech.getFreeTechnology(selectedTech!!.name)
        }
        else civTech.techsToResearch = tempTechsToResearch

        civTech.updateResearchProgress()

        game.settings.addCompletedTutorialTask("Pick technology")

        game.popScreen()
    }

    private fun createTechTable() {

        for (label in eraLabels) label.remove()
        eraLabels.clear()

        val allTechs = ruleset.technologies.values
        if (allTechs.isEmpty()) return
        val columns = allTechs.maxOf { it.column!!.columnNumber } + 1
        val rows = allTechs.maxOf { it.row } + 1
        val techMatrix = Array<Array<Technology?>>(columns) { arrayOfNulls(rows) } // Divided into columns, then rows

        for (technology in allTechs) {
            techMatrix[technology.column!!.columnNumber][technology.row - 1] = technology
        }

        val erasNamesToColumns = LinkedHashMap<String, ArrayList<Int>>()
        for (tech in allTechs) {
            val era = tech.era()
            if (!erasNamesToColumns.containsKey(era)) erasNamesToColumns[era] = ArrayList()
            val columnNumber = tech.column!!.columnNumber
            if (!erasNamesToColumns[era]!!.contains(columnNumber)) erasNamesToColumns[era]!!.add(columnNumber)
        }
        for ((era, eraColumns) in erasNamesToColumns) {
            val columnSpan = eraColumns.size
            val color = when {
                civTech.era.name == era -> queuedTechColor
                ruleset.eras[era]!!.eraNumber < civTech.era.eraNumber -> colorFromRGB(255, 175, 0)
                else -> ImageGetter.CHARCOAL.cpy()
            }

            val table1 = Table().pad(1f)
            val table2 = Table()

            table1.background = skinStrings.getUiBackground("General/Border", tintColor = Color.WHITE)
            table2.background = skinStrings.getUiBackground("General/Border", tintColor = color)

            val label = era.toLabel().apply {
                setAlignment(Align.center)
                if (ruleset.eras[era]!!.eraNumber < civTech.era.eraNumber)
                    this.color = colorFromRGB(120, 46, 16) }

            eraLabels.add(label)

            table2.add(label).growX()
            table1.add(table2).growX()

            techTable.add(table1).fill().colspan(columnSpan)
        }

        for (rowIndex in 0 until rows) {

            techTable.row()

            for (columnIndex in techMatrix.indices) {
                val tech = techMatrix[columnIndex][rowIndex]

                val table = Table().pad(2f).padRight(60f).padLeft(20f)
                if (rowIndex == 0)
                    table.padTop(7f)

                if (erasNamesToColumns[civTech.era.name]?.contains(columnIndex) == true)
                    table.background = skinStrings.getUiBackground("TechPickerScreen/Background", tintColor = queuedTechColor.darken(0.5f))

                if (tech == null) {
                    techTable.add(table).fill()
                } else {
                    val techButton = TechButton(tech.name, civTech, false)
                    table.add(techButton)
                    techNameToButton[tech.name] = techButton
                    techButton.onClick { selectTechnology(tech, queue = false, center = false) }
                    techButton.onRightClick {
                        selectTechnology(tech, queue = true, center = false)
                    }
                    techButton.onDoubleClick(UncivSound.Paper) { tryExit() }
                    techTable.add(table).fillX()
                }
            }
        }
    }

    private fun setButtonsInfo() {
        val authoritative = isAuthoritativeGame()
        val projectedResearch = authoritativeResearch
        val projectedQueueEntries = projectedResearch?.queueEntries?.associateBy { it.technologyName }.orEmpty()
        for ((techName, techButton) in techNameToButton) {
            val isResearched = isResearchedForDisplay(techName)
            techButton.setButtonColor(when {
                isResearched && techName != Constants.futureTech -> researchedTechColor
                isResearched -> researchedFutureTechColor
                // if we're here to pick a free tech, show the current tech like the rest of the researchables so it'll be obvious what we can pick
                tempTechsToResearch.firstOrNull() == techName && !freeTechPick -> currentTechColor
                researchableTechs.contains(techName) -> researchableTechColor
                tempTechsToResearch.contains(techName) -> queuedTechColor
                else -> ImageGetter.CHARCOAL.cpy()
            })

            if (isResearched && techName != Constants.futureTech) {
                techButton.text.color = colorFromRGB(154, 98, 16)
            }

            if (authoritative) {
                val estimate = projectedQueueEntries[techName]?.estimatedTurns
                techButton.turns.setText(when {
                    techName !in projectedQueueEntries -> ""
                    estimate == null -> Fonts.infinity.toString()
                    else -> estimate.tr()
                } + if (techName in projectedQueueEntries) "${Fonts.turn}".tr() else "")
            } else if (!isResearched || techName == Constants.futureTech) {
                techButton.turns.setText(turnsToTech[techName] + "${Fonts.turn}".tr())
            }

            techButton.text.setText(techName.tr(true))
        }

        addConnectingLines()

        addOrderIndicators()
    }

    private fun isResearchedForDisplay(technologyName: String): Boolean =
        if (isAuthoritativeGame()) technologyName in authoritativeResearch?.researchedTechnologies.orEmpty()
        else civTech.isResearched(technologyName)

    private fun addConnectingLines() {
        techTable.pack() // required for the table to have the button positions set, so topTable.stageToLocalCoordinates will be correct
        scrollPane.updateVisualScroll()

        lines.clear()

        for (eraLabel in eraLabels) {
            val coords = Vector2(0f, 0f)
            eraLabel.localToStageCoordinates(coords)
            techTable.stageToLocalCoordinates(coords)
            val line = ImageGetter.getLine(coords.x-1f, coords.y, coords.x-1f, coords.y - 1000f, 1f)
            line.color = Color.GRAY.cpy().apply { a = 0.6f }
            line.toBack()
            lines.addActor(line)
        }

        for (tech in ruleset.technologies.values) {
            if (!techNameToButton.containsKey(tech.name)) {
                ToastPopup("Tech ${tech.name} appears to be missing - perhaps two techs have the same row & column", this)
                continue
            }
            val techButton = techNameToButton[tech.name]!!
            for (prerequisite in tech.prerequisites) {
                if (!techNameToButton.containsKey(prerequisite)) {
                    ToastPopup("Tech $prerequisite. prerequisite of ${tech.name}, appears to be missing - perhaps two techs have the same row & column", this)
                    continue
                }
                val prerequisiteButton = techNameToButton[prerequisite]!!
                val techButtonCoords = Vector2(0f, techButton.height / 2)
                techButton.localToStageCoordinates(techButtonCoords)
                techTable.stageToLocalCoordinates(techButtonCoords)

                val prerequisiteCoords = Vector2(prerequisiteButton.width, prerequisiteButton.height / 2)
                prerequisiteButton.localToStageCoordinates(prerequisiteCoords)
                techTable.stageToLocalCoordinates(prerequisiteCoords)

                val lineColor = when {
                    isResearchedForDisplay(tech.name) && !tech.isContinuallyResearchable() -> Color.WHITE.cpy()
                    isResearchedForDisplay(prerequisite) -> researchableTechColor
                    tempTechsToResearch.contains(tech.name) -> currentTechColor
                    else -> Color.WHITE.cpy()
                }

                val lineSize = when {
                    tempTechsToResearch.contains(tech.name) && !isResearchedForDisplay(prerequisite) -> 4f
                    else -> 2f
                }

                if (techButtonCoords.y != prerequisiteCoords.y) {

                    val r = 6f

                    val deltaX = techButtonCoords.x - prerequisiteCoords.x
                    val deltaY = techButtonCoords.y - prerequisiteCoords.y
                    val halfLength = deltaX / 2f

                    val line = ImageGetter.getWhiteDot().apply {
                        width = halfLength - r - lineSize/2
                        height = lineSize
                        x = prerequisiteCoords.x
                        y = prerequisiteCoords.y - lineSize / 2
                    }
                    val line1 = ImageGetter.getWhiteDot().apply {
                        width = halfLength - r - lineSize/2
                        height = lineSize
                        x = techButtonCoords.x - width
                        y = techButtonCoords.y - lineSize / 2
                    }
                    val line2 = ImageGetter.getWhiteDot().apply {
                        width = lineSize
                        height = abs(deltaY) - 2*r - lineSize
                        x = techButtonCoords.x - halfLength - lineSize / 2
                        y = techButtonCoords.y + (if (deltaY > 0f) -height-r-lineSize/2 else r+lineSize/2)
                    }

                    var line3: Image?
                    var line4: Image?

                    if (deltaY < 0) {
                        /* -\ */ line3 = ImageGetter.getLine(line2.x+lineSize/2+0.3f, line2.y + line2.height-lineSize/2,line.x + line.width-lineSize/2, line.y+lineSize/2+0.3f, lineSize)
                        /* \- */ line4 = ImageGetter.getLine(line2.x+lineSize/2-0.3f, line2.y+lineSize/2, line1.x+lineSize/2, line1.y+lineSize/2-0.3f, lineSize)
                    } else {
                        /* -/ */ line3 = ImageGetter.getLine(line2.x+lineSize/2+0.3f, line2.y+lineSize/2, line.x + line.width-lineSize/2, line.y+lineSize/2-0.3f, lineSize)
                        /* /- */ line4 = ImageGetter.getLine(line2.x+lineSize/2-0.3f, line2.y + line2.height-lineSize/2, line1.x+lineSize/2, line1.y+lineSize/2+0.3f, lineSize)
                    }

                    line.color = lineColor
                    line1.color = lineColor
                    line2.color = lineColor
                    line3.color = lineColor
                    line4.color = lineColor

                    lines.addActor(line)
                    lines.addActor(line1)
                    lines.addActor(line2)
                    lines.addActor(line3)
                    lines.addActor(line4)

                } else {

                    val line = ImageGetter.getWhiteDot().apply {
                        width = techButtonCoords.x - prerequisiteCoords.x
                        height = lineSize
                        x = prerequisiteCoords.x
                        y = prerequisiteCoords.y - lineSize / 2
                    }
                    line.color = lineColor

                    lines.addActor(line)
                }
            }
        }

        lines.children.filter { it.color == currentTechColor && it.color != Color.WHITE.cpy() }
            .forEach { it.toFront() }
    }

    private fun addOrderIndicators() {
        orderIndicators.clear()
        for ((techName, techButton) in techNameToButton) {
            val techButtonCoords = Vector2(0f, techButton.height / 2)
            techButton.localToStageCoordinates(techButtonCoords)
            techTable.stageToLocalCoordinates(techButtonCoords)
            if (tempTechsToResearch.contains(techName) && tempTechsToResearch.size > 1) {
                val index = tempTechsToResearch.indexOf(techName) + 1
                val orderIndicator = index.tr().toLabel(fontSize = 18)
                    .apply { setAlignment(Align.center) }
                    .surroundWithCircle(28f, color = skinStrings.skinConfig.baseColor)
                    .surroundWithCircle(30f,false)
                    .apply { setPosition(techButtonCoords.x - width, techButtonCoords.y - height / 2) }
                orderIndicators.addActor(orderIndicator)
            }
        }
        orderIndicators.toFront()
    }

    private fun selectTechnology(tech: Technology?, queue: Boolean = false, center: Boolean = false, switchFromWorldScreen: Boolean = true) {

        val previousSelectedTech = selectedTech
        selectedTech = tech
        descriptionLabel.setText(tech?.getDescription(civInfo))

        if (!switchFromWorldScreen)
            return

        if (tech == null)
            return

        if (isAuthoritativeGame() && !freeTechPick)
            authoritativeAppendSelection = queue

        // center on technology
        if (center) centerOnTechnology(tech)

        if (freeTechPick) {
            if (isAuthoritativeGame()) {
                val freeChoices = authoritativeResearch?.freeTechnologyChoices
                if (freeChoices == null) {
                    rightSideButton.setText("Loading authoritative research".tr())
                    rightSideButton.disable()
                } else if (tech.name !in freeChoices) {
                    rightSideButton.setText("Unavailable".tr())
                    rightSideButton.disable()
                } else {
                    pick("Pick [${tech.name}] as free technology".tr())
                    setButtonsInfo()
                }
                return
            }
            selectTechnologyForFreeTech(tech)
            setButtonsInfo()
            return
        }

        if (isAuthoritativeGame()) {
            selectAuthoritativeTechnology(tech, queue)
            return
        }

        if (civInfo.gameInfo.gameParameters.godMode && !civInfo.tech.isResearched(tech.name)
                && selectedTech == previousSelectedTech) {
            civInfo.tech.addTechnology(tech.name)
        }

        if (civTech.isResearched(tech.name) && !tech.isContinuallyResearchable()) {
            rightSideButton.setText("Pick a tech".tr())
            rightSideButton.disable()
            setButtonsInfo()
            return
        }

        if (!GUI.isAllowedChangeState()) {
            rightSideButton.disable()
            return
        }

        val pathToTech = civTech.getRequiredTechsToDestination(tech)
        for (requiredTech in pathToTech) {
            val unavailableUniques = requiredTech.uniqueObjects.filter {
                it.type == UniqueType.OnlyAvailable && !it.conditionalsApply(civInfo.state) ||
                    it.type == UniqueType.Unavailable && it.conditionalsApply(civInfo.state)
            }
            for (unique in unavailableUniques) {
                rightSideButton.setText(unique.getDisplayText().tr())
                rightSideButton.disable()
                return
            }
        }

        if (queue){
            for (pathTech in pathToTech) {
                if (pathTech.name !in tempTechsToResearch) {
                    tempTechsToResearch.add(pathTech.name)
                }
            }
        }else{
            tempTechsToResearch.clear()
            tempTechsToResearch.addAll(pathToTech.map { it.name })
        }

        if (tempTechsToResearch.any()) {
            val label = "Research [${tempTechsToResearch[0]}]".tr()
            val techProgression = getTechProgressLabel(tempTechsToResearch)
            pick("${label}\n${techProgression}")
        } else {
            rightSideButton.setText("Unavailable".tr())
            rightSideButton.disable()
        }
        setButtonsInfo()
    }

    private fun selectAuthoritativeTechnology(tech: Technology, append: Boolean) {
        authoritativeAppendSelection = append
        val research = authoritativeResearch
        if (research == null) {
            rightSideButton.setText("Loading authoritative research".tr())
            rightSideButton.disable()
            return
        }
        val legalTargets = if (append) research.appendableTargets else research.selectableTargets
        if (tech.name !in legalTargets) {
            rightSideButton.setText("Unavailable".tr())
            rightSideButton.disable()
            return
        }
        val action = if (append) "Add [${tech.name}] to research queue" else "Research [${tech.name}]"
        val queuedEntry = research.queueEntries.singleOrNull { it.technologyName == tech.name }
        val projectedProgress = if (queuedEntry == null) "" else {
            val overflow = if (research.currentTechnology == tech.name) research.overflowScience else 0
            "\n(${queuedEntry.storedScience + overflow}/${queuedEntry.cost})"
        }
        pick(action.tr() + projectedProgress)
        setButtonsInfo()
    }

    @Readonly
    private fun getTechProgressLabel(techs: List<String>): String {
        authoritativeResearch?.let { research ->
            val entries = research.queueEntries.filter { it.technologyName in techs }
            val progress = entries.sumOf { it.storedScience } + research.overflowScience
            val cost = entries.sumOf { it.cost }
            return "($progress/$cost)"
        }
        val progress = techs.sumOf { tech -> civTech.researchOfTech(tech) } + civTech.getOverflowScience()
        val techCost = techs.sumOf { tech -> civInfo.tech.costOfTech(tech) }
        return "(${progress}/${techCost})"
    }

    private fun centerOnTechnology(tech: Technology) {
        Concurrency.runOnGLThread {
            techNameToButton[tech.name]?.parent?.let {
                scrollPane.scrollTo(it.x, it.y, it.width, it.height, true, true)
                scrollPane.updateVisualScroll()
            }
        }
    }

    private fun selectTechnologyForFreeTech(tech: Technology) {
        if (researchableTechs.contains(tech.name)) {
            val label = "Pick [${tech.name}] as free tech".tr()
            val techProgression = getTechProgressLabel(listOf(tech.name))
            pick("${label}\n${techProgression}")
        } else {
            rightSideButton.setText("Pick a free tech".tr())
            rightSideButton.disable()
        }
    }
}
