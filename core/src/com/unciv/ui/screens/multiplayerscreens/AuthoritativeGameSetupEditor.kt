package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.multiplayer.authoritative.ApiV3AiSlot
import com.unciv.logic.multiplayer.authoritative.ApiV3BarbarianMode
import com.unciv.logic.multiplayer.authoritative.ApiV3GameSetup
import com.unciv.logic.multiplayer.authoritative.ApiV3GeneratedMapShape
import com.unciv.logic.multiplayer.authoritative.ApiV3GeneratedMapSize
import com.unciv.logic.multiplayer.authoritative.ApiV3GeneratedMapType
import com.unciv.logic.multiplayer.authoritative.ApiV3MapResourceDensity
import com.unciv.logic.multiplayer.authoritative.ApiV3MirroringType
import com.unciv.models.ruleset.Ruleset
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * Closed API-v3 setup editor split into purpose-specific lobby pages. It edits
 * typed server intent only and never creates or mutates a local GameInfo.
 */
internal class AuthoritativeGameSetupEditor(
    private val ruleset: Ruleset,
    initial: ApiV3GameSetup,
    /**
     * Raised whenever the owner touches any control. The lobby screen debounces
     * this into one revisioned reconfiguration so the room stays live without
     * committing a canonical mutation per keystroke.
     */
    private val onEdited: () -> Unit = {},
    /** Current human seat count, so the AI roster always fills the rest. */
    private val humanSlots: () -> Int = { 1 },
    /** Civilizations already spoken for by the owner or a joined member. */
    private val unavailableCivilizations: () -> Set<String> = { emptySet() },
) {
    private val difficulty = textSelect(ruleset.difficulties.keys, initial.difficulty)
    private val speed = textSelect(ruleset.speeds.keys, initial.speed)
    private val startingEra = textSelect(ruleset.eras.keys, initial.startingEra)
    private val mapType = enumSelect(ApiV3GeneratedMapType.entries, initial.mapType)
    private val mapShape = enumSelect(ApiV3GeneratedMapShape.entries, initial.mapShape)
    private val mapSize = enumSelect(ApiV3GeneratedMapSize.entries, initial.mapSize)
    private val mapResources =
        enumSelect(ApiV3MapResourceDensity.entries, initial.mapResources)
    private val barbarians = enumSelect(ApiV3BarbarianMode.entries, initial.barbarians)
    private val mirroring = enumSelect(ApiV3MirroringType.entries, initial.mirroring)

    private val majorCivilizations = numberField(initial.majorCivilizations)
    private val cityStates = numberField(initial.cityStates)
    private val maxTurns = numberField(initial.maxTurns)
    private val mapSeed =
        UncivTextField("Blank lets the server choose", initial.mapSeed?.toString().orEmpty())
    private val customMapRadius = numberField(initial.customMapRadius ?: 20)
    private val customMapWidth = numberField(initial.customMapWidth ?: 44)
    private val customMapHeight = numberField(initial.customMapHeight ?: 29)
    private val customDimensions = Table(BaseScreen.skin)
    private val tilesPerBiomeArea = numberField(initial.tilesPerBiomeArea)
    private val maxCoastExtension = numberField(initial.maxCoastExtension)
    private val elevationExponent = numberField(initial.elevationExponent)
    private val temperatureIntensity = numberField(initial.temperatureIntensity)
    private val temperatureShift = numberField(initial.temperatureShift)
    private val vegetationRichness = numberField(initial.vegetationRichness)
    private val rareFeaturesRichness = numberField(initial.rareFeaturesRichness)
    private val resourceRichness = numberField(initial.resourceRichness)
    private val waterThreshold = numberField(initial.waterThreshold)

    private val oneCityChallenge = checkBox("One City Challenge", initial.oneCityChallenge)
    private val nuclearWeaponsEnabled =
        checkBox("Nuclear weapons", initial.nuclearWeaponsEnabled)
    private val espionageEnabled = checkBox("Espionage", initial.espionageEnabled)
    private val noStartBias = checkBox("Disable start bias", initial.noStartBias)
    private val shufflePlayerOrder =
        checkBox("Shuffle player order", initial.shufflePlayerOrder)
    private val noCityRazing = checkBox("Disable city razing", initial.noCityRazing)
    private val worldWrap = checkBox("World wrap", initial.worldWrap)
    private val strategicBalance = checkBox("Strategic balance", initial.strategicBalance)
    private val legendaryStart = checkBox("Legendary start", initial.legendaryStart)
    private val noRuins = checkBox("Disable ancient ruins", initial.noRuins)
    private val noNaturalWonders =
        checkBox("Disable natural wonders", initial.noNaturalWonders)
    private val victoryTypes = ruleset.victories.values
        .filterNot { it.hiddenInVictoryScreen }
        .associate { victory ->
            victory.name to checkBox(victory.name, victory.name in initial.victoryTypes)
        }

    /**
     * One entry per AI civilization: a pinned major civilization name, or blank
     * for "let the server draw it". Length always tracks
     * `majorCivilizations - humanSlots`, which is what the server validates.
     */
    private val aiSlots = ArrayList(initial.aiCivilizations.orEmpty())
    private val aiRoster = Table(BaseScreen.skin)
    private val playableCivilizations = ruleset.nations.values
        .filter { it.isMajorCiv }
        .map { it.name }
        .sorted()

    val gamePage = page("GAME RULES").apply {
        addField("Difficulty", difficulty)
        addField("Game speed", speed)
        addField("Starting era", startingEra)
        addField("Major civilizations", majorCivilizations)
        addField("City-states", cityStates)
        addField("Turn limit", maxTurns)
    }

    val aiPage = page("AI CIVILIZATIONS").apply {
        add(aiRoster).colspan(2).growX().left().row()
        add(
            ("Every AI slot the server has not been told to pin is drawn randomly " +
                "from the civilizations nobody claimed.").toLabel(Color.LIGHT_GRAY),
        ).colspan(2).growX().left().row()
    }

    val worldPage = page("WORLD SETTINGS").apply {
        addField("Map type", mapType)
        addField("Map shape", mapShape)
        addField("World size", mapSize)
        add(customDimensions).colspan(2).growX().row()
        addField("Resources", mapResources)
        addField("Barbarians", barbarians)
        addField("Game seed", mapSeed)
        addField("Mirroring", mirroring)
        addSection("STARTING AREA")
        addCheckboxGrid(
            listOf(
                worldWrap,
                strategicBalance,
                legendaryStart,
                noRuins,
                noNaturalWonders,
            ),
        )
    }

    val victoryPage = page("VICTORY CONDITIONS").apply {
        addCheckboxGrid(victoryTypes.values)
    }

    val advancedPage = page("ADVANCED SETTINGS").apply {
        addSection("MAP GENERATION")
        addField("Tiles per biome area", tilesPerBiomeArea)
        addField("Maximum coast extension", maxCoastExtension)
        addField("Elevation exponent", elevationExponent)
        addField("Temperature intensity", temperatureIntensity)
        addField("Temperature shift", temperatureShift)
        addField("Vegetation richness", vegetationRichness)
        addField("Rare feature richness", rareFeaturesRichness)
        addField("Resource richness", resourceRichness)
        addField("Water threshold", waterThreshold)
        addSection("GAMEPLAY RULES")
        addCheckboxGrid(
            listOf(
                oneCityChallenge,
                nuclearWeaponsEnabled,
                espionageEnabled,
                noStartBias,
                shufflePlayerOrder,
                noCityRazing,
            ),
        )
    }

    init {
        mapSize.onChange { renderCustomDimensions() }
        mapShape.onChange { renderCustomDimensions() }
        worldWrap.onChange { renderCustomDimensions() }
        renderCustomDimensions()
        majorCivilizations.onChange { renderAiRoster() }
        renderAiRoster()
        editableControls().forEach { control -> control.onChange { onEdited() } }
    }

    /** AI seats are whatever the major civilization count leaves over. */
    private fun aiSlotCount(): Int =
        ((majorCivilizations.text.trim().toIntOrNull() ?: 0) - humanSlots())
            .coerceIn(0, MAX_AI_SLOTS)

    private fun renderAiRoster() {
        val count = aiSlotCount()
        while (aiSlots.size < count) aiSlots.add(ApiV3AiSlot())
        while (aiSlots.size > count) aiSlots.removeAt(aiSlots.size - 1)

        aiRoster.clear()
        aiRoster.defaults().pad(4f)
        val taken = unavailableCivilizations()
        aiSlots.forEachIndexed { index, slot ->
            val choices = playableCivilizations
                .filterNot { it in taken }
                .filterNot { civilization ->
                    civilization != slot.civilizationId &&
                        aiSlots.any { it.civilizationId == civilization }
                }
            val civilization = slotSelect(
                listOf(RANDOM_CIVILIZATION) + choices,
                slot.civilizationId,
                RANDOM_CIVILIZATION,
            ) { chosen -> aiSlots[index] = aiSlots[index].copy(civilizationId = chosen) }
            val difficulty = slotSelect(
                listOf(MATCH_DEFAULT) + ruleset.difficulties.keys,
                slot.difficulty,
                MATCH_DEFAULT,
            ) { chosen -> aiSlots[index] = aiSlots[index].copy(difficulty = chosen) }
            val personality = slotSelect(
                listOf(NATION_DEFAULT) + ruleset.personalities.keys,
                slot.personality,
                NATION_DEFAULT,
            ) { chosen -> aiSlots[index] = aiSlots[index].copy(personality = chosen) }
            val remove = "Remove".toTextButton()
            remove.onActivation { removeAiSlot(index) }
            aiRoster.add(LobbyChrome.nationBadge(ruleset, slot.civilizationId, 32f)).left()
            aiRoster.add(civilization).minWidth(190f).growX().left()
            aiRoster.add(difficulty).minWidth(140f).left()
            aiRoster.add(personality).minWidth(150f).left()
            aiRoster.add(remove).right().row()
        }
        val add = "Add AI".toTextButton()
        add.onActivation { addAiSlot() }
        aiRoster.add(
            "${aiSlots.size} AI  +  ${humanSlots()} human".toLabel(Color.LIGHT_GRAY),
        ).left().colspan(4)
        aiRoster.add(add).right().row()
    }

    /**
     * One roster dropdown. [neutral] is the "let the server decide" entry, which
     * is stored as a blank so the server keeps its own default.
     */
    private fun slotSelect(
        choices: Collection<String>,
        selectedValue: String,
        neutral: String,
        onPicked: (String) -> Unit,
    ): SelectBox<String> {
        val select = SelectBox<String>(BaseScreen.skin).apply {
            items = com.badlogic.gdx.utils.Array(choices.toTypedArray())
            selected = selectedValue.ifBlank { neutral }
        }
        select.onChange {
            onPicked(select.selected.takeIf { it != neutral }.orEmpty())
            renderAiRoster()
            onEdited()
        }
        return select
    }

    private fun addAiSlot() {
        if (aiSlots.size >= MAX_AI_SLOTS) return
        setMajorCivilizations(aiSlots.size + 1 + humanSlots())
    }

    private fun removeAiSlot(index: Int) {
        aiSlots.removeAt(index)
        setMajorCivilizations(aiSlots.size + humanSlots())
    }

    private fun setMajorCivilizations(value: Int) {
        majorCivilizations.text = value.coerceIn(2, 16).toString()
        renderAiRoster()
        onEdited()
    }

    /** Every control whose value is part of the typed setup sent to the server. */
    private fun editableControls(): List<Actor> = buildList {
        addAll(openableLists())
        addAll(
            listOf(
                majorCivilizations, cityStates, maxTurns, mapSeed,
                customMapRadius, customMapWidth, customMapHeight,
                tilesPerBiomeArea, maxCoastExtension, elevationExponent,
                temperatureIntensity, temperatureShift, vegetationRichness,
                rareFeaturesRichness, resourceRichness, waterThreshold,
            ),
        )
        addAll(
            listOf(
                oneCityChallenge, nuclearWeaponsEnabled, espionageEnabled, noStartBias,
                shufflePlayerOrder, noCityRazing, worldWrap, strategicBalance,
                legendaryStart, noRuins, noNaturalWonders,
            ),
        )
        addAll(victoryTypes.values)
    }

    private companion object {
        const val RANDOM_CIVILIZATION = "Random"
        const val MATCH_DEFAULT = "Match difficulty"
        const val NATION_DEFAULT = "Nation default"
        const val MAX_AI_SLOTS = 15
    }

    fun build(ownerCivilizationId: String): ApiV3GameSetup {
        val victories = victoryTypes.filterValues(CheckBox::isChecked).keys.toList()
        require(victories.isNotEmpty()) { "Select at least one victory condition." }
        val custom = mapSize.selected == ApiV3GeneratedMapSize.Custom
        val rectangular = mapShape.selected == ApiV3GeneratedMapShape.Rectangular
        val radius = if (custom && !rectangular) {
            customMapRadius.intValue("Custom radius", 2..100)
        } else null
        val width = if (custom && rectangular) {
            customMapWidth.intValue("Custom width", 3..220)
        } else null
        val height = if (custom && rectangular) {
            customMapHeight.intValue("Custom height", 3..220)
        } else null
        if (width != null && height != null) {
            require(width <= height * 16 && height <= width * 16) {
                "Custom map dimensions cannot exceed a 16:1 aspect ratio."
            }
            require(!worldWrap.isChecked || width >= 32 && width % 2 == 0) {
                "World wrap requires an even custom width of at least 32."
            }
        }
        val majors = majorCivilizations.intValue("Major civilizations", 2..16)
        val humans = humanSlots()
        require(humans in 1..majors) {
            "Human player slots must fit the major civilizations."
        }
        // The roster is resized here as well as on render, so a stale control can
        // never submit a roster the server would reject as inconsistent.
        val roster = List(majors - humans) { aiSlots.getOrElse(it) { ApiV3AiSlot() } }
        val pinned = roster.map { it.civilizationId }.filter(String::isNotEmpty)
        require(pinned.distinct().size == pinned.size) {
            "Each pinned AI civilization can only be used once."
        }
        require(ownerCivilizationId !in pinned) {
            "Your own civilization cannot also be pinned to an AI."
        }
        return ApiV3GameSetup(
            ownerCivilizationId = ownerCivilizationId,
            aiCivilizations = roster,
            difficulty = difficulty.selected,
            speed = speed.selected,
            startingEra = startingEra.selected,
            victoryTypes = victories,
            majorCivilizations = majors,
            cityStates = cityStates.intValue("City-states", 0..64),
            maxTurns = maxTurns.intValue("Turn limit", 100..1500),
            mapType = mapType.selected,
            mapShape = mapShape.selected,
            mapSize = mapSize.selected,
            customMapRadius = radius,
            customMapWidth = width,
            customMapHeight = height,
            mapResources = mapResources.selected,
            barbarians = barbarians.selected,
            oneCityChallenge = oneCityChallenge.isChecked,
            nuclearWeaponsEnabled = nuclearWeaponsEnabled.isChecked,
            espionageEnabled = espionageEnabled.isChecked,
            noStartBias = noStartBias.isChecked,
            shufflePlayerOrder = shufflePlayerOrder.isChecked,
            // V3 turns stay sequential by design (unlimited human turn time); the
            // worker still pins this flag in the canonical setup payload.
            simultaneousHumanTurns = false,
            noCityRazing = noCityRazing.isChecked,
            worldWrap = worldWrap.isChecked,
            strategicBalance = strategicBalance.isChecked,
            legendaryStart = legendaryStart.isChecked,
            noRuins = noRuins.isChecked,
            noNaturalWonders = noNaturalWonders.isChecked,
            mapSeed = mapSeed.text.trim().takeIf(String::isNotEmpty)?.toLong(),
            mirroring = mirroring.selected,
            tilesPerBiomeArea = tilesPerBiomeArea.intValue("Tiles per biome area", 1..15),
            maxCoastExtension = maxCoastExtension.intValue("Maximum coast extension", 1..5),
            elevationExponent = elevationExponent.floatValue("Elevation exponent", 0.6f..0.8f),
            temperatureIntensity =
                temperatureIntensity.floatValue("Temperature intensity", 0.4f..0.8f),
            temperatureShift = temperatureShift.floatValue("Temperature shift", -0.4f..0.4f),
            vegetationRichness =
                vegetationRichness.floatValue("Vegetation richness", 0f..1f),
            rareFeaturesRichness =
                rareFeaturesRichness.floatValue("Rare feature richness", 0f..0.5f),
            resourceRichness = resourceRichness.floatValue("Resource richness", 0f..0.5f),
            waterThreshold = waterThreshold.floatValue("Water threshold", -0.1f..0.1f),
        )
    }

    fun closeOpenLists() {
        openableLists().forEach(SelectBox<*>::hideScrollPane)
    }

    private fun openableLists(): List<SelectBox<*>> = listOf(
        difficulty,
        speed,
        startingEra,
        mapType,
        mapShape,
        mapSize,
        mapResources,
        barbarians,
        mirroring,
    )

    private fun renderCustomDimensions() {
        customDimensions.clear()
        customDimensions.defaults().pad(6f)
        if (mapSize.selected != ApiV3GeneratedMapSize.Custom) return
        if (mapShape.selected == ApiV3GeneratedMapShape.Rectangular) {
            customDimensions.addField("Custom width", customMapWidth)
            customDimensions.addField("Custom height", customMapHeight)
            if (worldWrap.isChecked) {
                customDimensions.add(
                    "World wrap requires an even width of at least 32."
                        .toLabel(Color.LIGHT_GRAY),
                ).colspan(2).growX().left().row()
            }
        } else {
            customDimensions.addField("Custom radius", customMapRadius)
        }
    }

    private fun page(title: String): Table =
        object : Table(BaseScreen.skin), TabbedPager.IPageExtensions {
            init {
                defaults().pad(8f)
                add(title.toLabel(Color.GOLD)).colspan(2).growX().left().row()
            }

            override fun activated(index: Int, caption: String, pager: TabbedPager) = Unit

            override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
                closeOpenLists()
            }
        }

    private fun Table.addSection(title: String) {
        add(title.toLabel(Color.GOLD)).colspan(2).growX().left().padTop(14f).row()
    }

    private fun Table.addField(label: String, field: Actor) {
        add(label.toLabel(Color.LIGHT_GRAY)).left()
        add(field).minWidth(220f).growX().left().row()
    }

    private fun Table.addCheckboxGrid(items: Collection<CheckBox>) {
        items.forEachIndexed { index, checkBox ->
            add(checkBox).growX().left()
            if (index % 2 == 1) row()
        }
        if (items.size % 2 == 1) {
            add()
            row()
        }
    }

    private fun checkBox(label: String, checked: Boolean) = label.toCheckBox(checked)

    private fun numberField(value: Number) = UncivTextField("", value.toString())

    private fun textSelect(values: Collection<String>, selectedValue: String): SelectBox<String> {
        val choices = values.ifEmpty { listOf(selectedValue) }.toTypedArray()
        return SelectBox<String>(BaseScreen.skin).apply {
            items = com.badlogic.gdx.utils.Array(choices)
            selected = selectedValue.takeIf { it in choices } ?: choices.first()
        }
    }

    private fun <T> enumSelect(values: List<T>, selectedValue: T): SelectBox<T> =
        SelectBox<T>(BaseScreen.skin).apply {
            items = com.badlogic.gdx.utils.Array<T>(values.size).apply {
                values.forEach(::add)
            }
            selected = selectedValue
        }

    private fun UncivTextField.intValue(label: String, range: IntRange): Int {
        val value = text.trim().toIntOrNull()
        require(value != null && value in range) {
            "$label must be between ${range.first} and ${range.last}."
        }
        return value
    }

    private fun UncivTextField.floatValue(
        label: String,
        range: ClosedFloatingPointRange<Float>,
    ): Float {
        val value = text.trim().toFloatOrNull()
        require(value != null && value.isFinite() && value in range) {
            "$label must be between ${range.start} and ${range.endInclusive}."
        }
        return value
    }
}
