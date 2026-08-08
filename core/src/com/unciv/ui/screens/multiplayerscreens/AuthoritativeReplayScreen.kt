package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.badlogic.gdx.utils.Align
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession
import com.unciv.logic.multiplayer.authoritative.ReplayProjection
import com.unciv.logic.multiplayer.authoritative.ReplayCivilization
import com.unciv.logic.multiplayer.authoritative.ReplayStatsEntry
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * YouTube-style replay screen for watching completed or live public matches.
 * Fetches no-fog-of-war replay projections from the server for each revision
 * and displays them with play/pause/step controls and civ stats.
 */
class AuthoritativeReplayScreen(
    private val session: AuthoritativeMultiplayerSession,
    private val gameId: String,
) : PickerScreen() {

    private val revisions = mutableListOf<Long>()
    private var currentRevisionIndex = 0
    private var currentProjection: ReplayProjection? = null
    private var isPlaying = false
    private var playSpeedMs = 500L // ms between revisions during playback

    private val statsTable = Table()
    private val civTable = Table()
    private val turnLabel = "Loading...".toLabel()
    private val revisionLabel = "".toLabel()
    private val playButton = "Play".toTextButton()
    private val stepForwardButton = "→".toTextButton()
    private val stepBackButton = "←".toTextButton()
    private val speedButton = "1x".toTextButton()
    private lateinit var revisionSlider: Slider

    init {
        setupUI()
        loadRevisions()
    }

    private fun setupUI() {
        val topBar = Table().apply {
            add(turnLabel).left().padRight(20f)
            add(revisionLabel).left()
            row()
        }

        // Civ stats table (scrollable)
        val statsScroll = AutoScrollPane(statsTable)
        val civScroll = AutoScrollPane(civTable)

        // Playback controls
        revisionSlider = Slider(0f, 1f, 0.01f, false, skin)
        revisionSlider.onChange { _ ->
            val index = revisionSlider.value.toInt()
            if (index != currentRevisionIndex) {
                currentRevisionIndex = index
                loadProjection(index)
            }
        }

        playButton.onActivation { togglePlay() }
        stepForwardButton.onActivation {
            if (currentRevisionIndex < revisions.size - 1) {
                currentRevisionIndex++
                loadProjection(currentRevisionIndex)
            }
        }
        stepBackButton.onActivation {
            if (currentRevisionIndex > 0) {
                currentRevisionIndex--
                loadProjection(currentRevisionIndex)
            }
        }
        speedButton.onActivation {
            playSpeedMs = when (playSpeedMs) {
                500L -> 200L  // 2x
                200L -> 100L  // 5x
                100L -> 1000L // 0.5x
                else -> 500L  // 1x
            }
            speedButton.setText(when (playSpeedMs) {
                200L -> "2x"; 100L -> "5x"; 1000L -> "0.5x"; else -> "1x"
            })
        }

        val controlsBar = Table().apply {
            add(stepBackButton).padRight(10f)
            add(playButton).padRight(10f)
            add(stepForwardButton).padRight(10f)
            add(speedButton).padRight(10f)
            add(revisionSlider).growX().padRight(10f)
        }

        // Layout: top bar (turn info), stats table, civ table, controls bar
        val content = Table().apply {
            add(topBar).growX().pad(10f)
            row()
            add(statsScroll).growX().pad(10f)
            row()
            add(civScroll).grow().pad(10f)
            row()
            add(controlsBar).growX().pad(10f)
            row()
        }

        pickerPane.add(content).grow()
    }

    private fun loadRevisions() {
        Concurrency.run("loadRevisions") {
            try {
                val result = session.getRevisions(gameId)
                launchOnGLThread {
                    revisions.clear()
                    revisions.addAll(result.revisions.map { it.revision })
                    if (revisions.isNotEmpty()) {
                        revisionSlider.setRange(0f, (revisions.size - 1).toFloat())
                        currentRevisionIndex = 0
                        loadProjection(0)
                    } else {
                        turnLabel.setText("No revisions found")
                    }
                }
            } catch (e: Exception) {
                launchOnGLThread {
                    turnLabel.setText("Error: ${e.message}")
                }
            }
        }
    }

    private fun loadProjection(index: Int) {
        if (index < 0 || index >= revisions.size) return
        currentRevisionIndex = index
        revisionSlider.value = index.toFloat()
        revisionLabel.setText("Revision ${revisions[index]} (${index + 1}/${revisions.size})")
        turnLabel.setText("Loading...")

        Concurrency.run("loadReplayProjection") {
            try {
                val projection = session.getReplayProjection(gameId, revisions[index])
                launchOnGLThread {
                    currentProjection = projection.projection
                    updateDisplay(projection.projection)
                }
            } catch (e: Exception) {
                launchOnGLThread {
                    turnLabel.setText("Error: ${e.message}")
                }
            }
        }
    }

    private fun updateDisplay(projection: ReplayProjection) {
        turnLabel.setText("Turn ${projection.turn}")
        statsTable.clear()
        civTable.clear()

        // Victory display
        if (projection.victory != null) {
            statsTable.add("${projection.victory.winningCivilizationId} won by ${projection.victory.victoryType} on turn ${projection.victory.victoryTurn}".toLabel()).colspan(6).padBottom(10f)
            statsTable.row()
        }

        // Stats header
        statsTable.add("Civ".toLabel()).padRight(20f)
        statsTable.add("Gold".toLabel()).padRight(15f)
        statsTable.add("Cities".toLabel()).padRight(15f)
        statsTable.add("Units".toLabel()).padRight(15f)
        statsTable.add("Pop".toLabel()).padRight(15f)
        statsTable.add("Tech".toLabel()).padRight(15f)
        statsTable.add("Status".toLabel())
        statsTable.row()

        // Civ stats rows
        for (civ in projection.majorCivilizations) {
            val status = if (civ.defeated) "DEFEATED" else "ALIVE"
            statsTable.add(civ.displayName.toLabel()).padRight(20f)
            statsTable.add(civ.gold.toString().toLabel()).padRight(15f)
            statsTable.add(civ.cityCount.toString().toLabel()).padRight(15f)
            statsTable.add(civ.unitCount.toString().toLabel()).padRight(15f)
            statsTable.add(civ.population.toString().toLabel()).padRight(15f)
            statsTable.add(civ.technologiesResearched.toString().toLabel()).padRight(15f)
            statsTable.add(status.toLabel())
            statsTable.row()
        }

        // Stats history chart (simple text-based for now)
        if (projection.majorCivilizations.isNotEmpty()) {
            civTable.add("=== Stats History ===".toLabel()).padBottom(10f)
            civTable.row()
            val header = Table()
            header.add("Turn".toLabel()).padRight(20f)
            for (civ in projection.majorCivilizations) {
                header.add(civ.displayName.take(4).toLabel()).padRight(15f)
            }
            civTable.add(header).padBottom(5f)
            civTable.row()

            // Show score history for each turn
            val allTurns = projection.majorCivilizations
                .flatMap { it.statsHistory.map { s -> s.turn } }
                .distinct()
                .sorted()
            for (turn in allTurns) {
                val row = Table()
                row.add("T$turn".toLabel()).padRight(20f)
                for (civ in projection.majorCivilizations) {
                    val stat = civ.statsHistory.find { it.turn == turn }
                    row.add((stat?.score?.toString() ?: "-").toLabel()).padRight(15f)
                }
                civTable.add(row)
                civTable.row()
            }
        }
    }

    private fun togglePlay() {
        isPlaying = !isPlaying
        playButton.setText(if (isPlaying) "Pause" else "Play")
        if (isPlaying) {
            startPlayback()
        }
    }

    private fun startPlayback() {
        Concurrency.run("replayPlayback") {
            while (isPlaying && currentRevisionIndex < revisions.size - 1) {
                delay(playSpeedMs)
                if (!isPlaying) break
                val nextIndex = currentRevisionIndex + 1
                launchOnGLThread {
                    if (isPlaying) {
                        loadProjection(nextIndex)
                    }
                }
            }
            launchOnGLThread {
                isPlaying = false
                playButton.setText("Play")
            }
        }
    }
}
