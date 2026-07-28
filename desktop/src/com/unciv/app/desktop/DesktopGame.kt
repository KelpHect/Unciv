package com.unciv.app.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.sun.jna.platform.win32.Kernel32Util
import com.unciv.UncivGame
import com.unciv.logic.multiplayer.authoritative.ApiV3SessionTokenStore
import com.unciv.logic.multiplayer.authoritative.apiV3CredentialScope
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Path



class DesktopGame(config: Lwjgl3ApplicationConfiguration, override var customDataDirectory: String?) : UncivGame() {

    private var discordUpdater = DiscordUpdater()
    private val windowListener = UncivWindowListener()

    init {
        config.setWindowListener(windowListener)

        discordUpdater.setOnUpdate {

            if (!isInitialized)
                return@setOnUpdate null

            val info = DiscordGameInfo()
            val game = gameInfo

            if (game != null) {
                info.gameTurn = game.turns
                info.gameLeader = game.getCurrentPlayerCivilization().nation.leaderName
                info.gameNation = game.getCurrentPlayerCivilization().nation.name
            }

            return@setOnUpdate info

        }

        discordUpdater.startUpdates()
    }

    override fun installAudioHooks() {
        (Gdx.app as HardenGdxAudio).installHooks(
            musicController.getAudioLoopCallback(),
            musicController.getAudioExceptionHandler()
        )
    }

    override fun notifyTurnStarted() {
        windowListener.turnStarted()
    }

    override fun dispose() {
        discordUpdater.stopUpdates()
        super.dispose()
    }

    override fun getSystemErrorMessage(errorCode: Int): String? {
        return try {
            if (System.getProperty("os.name")?.contains("Windows") == true)
                Kernel32Util.formatMessage(errorCode)
            else null
        } catch (_: Throwable) {
            null
        }
    }

    override fun createApiV3SessionTokenStore(serverBaseUrl: String): ApiV3SessionTokenStore? {
        val operatingSystem = System.getProperty("os.name").orEmpty()
        val scope = apiV3CredentialScope(serverBaseUrl)
        val dataDirectory = customDataDirectory ?: "."
        return when {
            operatingSystem.startsWith("Windows", ignoreCase = true) ->
                WindowsApiV3SessionTokenStore(
                    Path.of(
                        dataDirectory,
                        ".unciv",
                        "credentials",
                        "api-v3-$scope.dpapi",
                    ),
                )
            operatingSystem.startsWith("Mac", ignoreCase = true) ->
                runCatching { MacOsApiV3SessionTokenStore(scope) }.getOrNull()
            operatingSystem.startsWith("Linux", ignoreCase = true) ->
                LinuxApiV3SessionTokenStore.create(scope)
            else -> null
        }
    }
}
