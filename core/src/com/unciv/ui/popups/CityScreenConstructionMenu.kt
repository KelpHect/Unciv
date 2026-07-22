package com.unciv.ui.popups

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.GUI
import com.unciv.logic.city.City
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.multiplayer.authoritative.ConstructionQueueAction
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.ui.components.input.KeyboardBinding
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly

//todo Check add/remove-all for "place one improvement" buildings

/**
 *  "Context menu" for City constructions - available by right-clicking (or long-press) in
 *   City Screen, left side, available constructions or queue entries.
 *
 *  @param city The [City] calling us - we need only `cityConstructions`, but future expansion may be easier having the parent
 *  @param construction The construction that was right-clicked
 *  @param onButtonClicked Callback if closed due to any action having been chosen - to update CityScreen
 */
class CityScreenConstructionMenu(
    stage: Stage,
    positionNextTo: Actor,
    private val city: City,
    private val construction: IConstruction,
    private val onButtonClicked: () -> Unit,
    private val authoritativeActions: List<ConstructionQueueAction>? = null,
    private val onAuthoritativeAction: ((ConstructionQueueAction) -> Unit)? = null,
) : AnimatedMenuPopup(stage, positionNextTo) {

    // These are only readability shorteners
    private val cityConstructions = city.cityConstructions
    private val constructionName = construction.name
    private val queueSizeWithoutPerpetual get() =
        cityConstructions.constructionQueue
        .count { it !in PerpetualConstruction.perpetualConstructionsMap }
    private val myIndex by lazy { cityConstructions.constructionQueue.indexOf(constructionName) }
    /** Cities (including this one) where changing the construction queue makes sense
     *  (excludes isBeingRazed even though technically that would be allowed) */
    // Can't use CityScreen.canChangeState for other cities
    @Readonly private fun candidateCities() = city.civ.cities.asSequence()
        .filterNot { it.isPuppet || it.isInResistance() || it.isBeingRazed }
    /** Check whether an "All cities" menu makes sense: `true` if there's more than one city, it's not a Wonder, and any city's queue matches [predicate]. */
    @Readonly private fun allCitiesEntryValid(predicate: (CityConstructions) -> Boolean) =
        city.civ.cities.size > 1 &&  // Yes any 2 cities, not candidateCities.drop(1).any()
        (construction as? Building)?.isAnyWonder() != true &&
        candidateCities().map { it.cityConstructions }.any(predicate)
    @Readonly private fun forAllCities(action: (CityConstructions) -> Unit) =
        candidateCities().map { it.cityConstructions }.forEach(action)

    private val settings = GUI.getSettings()
    private val disabledAutoAssignConstructions = settings.disabledAutoAssignConstructions

    init {
        closeListeners.add {
            if (anyButtonWasClicked) onButtonClicked()
        }
    }

    override fun createContentTable(): Table? {
        val table = super.createContentTable()!!
        if (authoritativeActions != null) addAuthoritativeActions(table)
        else addLocalQueueActions(table)
        if (canDisable())
            table.add(getButton("Disable", KeyboardBinding.BuildDisabled, ::disableEntry)).row()
        if (canEnable())
            table.add(getButton("Enable", KeyboardBinding.BuildDisabled, ::enableEntry)).row()
        return table.takeUnless { it.cells.isEmpty }
    }

    private fun addLocalQueueActions(table: Table) {
        if (canMoveQueueTop())
            table.add(getButton("Move to the top of the queue", KeyboardBinding.RaisePriority, ::moveQueueTop)).row()
        if (canMoveQueueEnd())
            table.add(getButton("Move to the end of the queue", KeyboardBinding.LowerPriority, ::moveQueueEnd)).row()
        if (canAddQueueTop())
            table.add(getButton("Add to the top of the queue", KeyboardBinding.AddConstructionTop, ::addQueueTop)).row()
        if (canAddAllQueues())
            table.add(getButton("Add to the queue in all cities", KeyboardBinding.AddConstructionAll, ::addAllQueues)).row()
        if (canAddAllQueuesTop())
            table.add(getButton("Add or move to the top in all cities", KeyboardBinding.AddConstructionAllTop, ::addAllQueuesTop)).row()
        if (canRemoveAllQueues())
            table.add(getButton("Remove from the queue in all cities", KeyboardBinding.RemoveConstructionAll, ::removeAllQueues)).row()
    }

    private fun addAuthoritativeActions(table: Table) {
        val submit = requireNotNull(onAuthoritativeAction)
        for (action in authoritativeActions.orEmpty()) {
            val (text, binding) = when (action) {
                ConstructionQueueAction.MoveToTop ->
                    "Move to the top of the queue" to KeyboardBinding.RaisePriority
                ConstructionQueueAction.MoveToEnd ->
                    "Move to the end of the queue" to KeyboardBinding.LowerPriority
                ConstructionQueueAction.AddToTop ->
                    "Add to the top of the queue" to KeyboardBinding.AddConstructionTop
                ConstructionQueueAction.AddToAllCities ->
                    "Add to the queue in all cities" to KeyboardBinding.AddConstructionAll
                ConstructionQueueAction.AddOrMoveToTopAllCities ->
                    "Add or move to the top in all cities" to KeyboardBinding.AddConstructionAllTop
                ConstructionQueueAction.RemoveFromAllCities ->
                    "Remove from the queue in all cities" to KeyboardBinding.RemoveConstructionAll
            }
            table.add(getButton(text, binding) { submit(action) }).row()
        }
    }

    @Pure
    private fun canMoveQueueTop(): Boolean {
        if (construction is PerpetualConstruction)
            return false
        return myIndex > 0
    }
    private fun moveQueueTop() = cityConstructions.moveEntryToTop(myIndex)

    @Pure
    private fun canMoveQueueEnd(): Boolean {
        if (construction is PerpetualConstruction)
            return false
        return myIndex in 0 until queueSizeWithoutPerpetual - 1
    }
    private fun moveQueueEnd() = cityConstructions.moveEntryToEnd(myIndex)

    @Readonly private fun isConstructionImprovementCreationBuilding() =
        construction is Building && construction.hasCreateOneImprovementUnique()

    @Readonly
    private fun canAddQueueTop() = construction !is PerpetualConstruction &&
        cityConstructions.canAddToQueue(construction)
            && !isConstructionImprovementCreationBuilding()

    private fun addQueueTop() = cityConstructions.addToQueue(construction, addToTop = true)

    @Readonly
    private fun canAddAllQueues() = allCitiesEntryValid {
        it.canAddToQueue(construction)
                && !isConstructionImprovementCreationBuilding()
        // A Perpetual that is already queued can still be added says canAddToQueue, but here we don't want to count that
                && !(construction is PerpetualConstruction && it.isBeingConstructedOrEnqueued(constructionName))
    }
    private fun addAllQueues() = forAllCities { it.addToQueue(construction) }

    @Readonly
    private fun canAddAllQueuesTop() = construction !is PerpetualConstruction &&
        allCitiesEntryValid {
            (it.canAddToQueue(construction) && !isConstructionImprovementCreationBuilding())
                    || it.isEnqueuedForLater(constructionName) }

    private fun addAllQueuesTop() = forAllCities {
        val index = it.constructionQueue.indexOf(constructionName)
        if (index > 0)
            it.moveEntryToTop(index)
        else
            it.addToQueue(construction, true)
    }

    @Readonly private fun canRemoveAllQueues() = allCitiesEntryValid { it.isBeingConstructedOrEnqueued(constructionName) }
    private fun removeAllQueues() = forAllCities { it.removeAllByName(constructionName) }

    @Readonly private fun canDisable() = constructionName !in disabledAutoAssignConstructions &&
        construction != PerpetualConstruction.Idle
    private fun disableEntry() {
        disabledAutoAssignConstructions.add(constructionName)
        settings.save()
    }

    @Readonly private fun canEnable() = constructionName in disabledAutoAssignConstructions
    private fun enableEntry() {
        disabledAutoAssignConstructions.remove(constructionName)
        settings.save()
    }
}
