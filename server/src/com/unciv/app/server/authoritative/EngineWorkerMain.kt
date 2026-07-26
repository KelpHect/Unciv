package com.unciv.app.server.authoritative

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache

/** Starts a private, headless Kotlin rules worker. It has no HTTP listener. */
object EngineWorkerMain {
    @JvmStatic fun main(args: Array<String>) {
        HeadlessApplication(object : ApplicationListener {
            override fun create() {}
            override fun render() {}
            override fun resize(width: Int, height: Int) {}
            override fun pause() {}
            override fun resume() {}
            override fun dispose() {}
        }, HeadlessApplicationConfiguration())
        UncivGame.Current = UncivGame().apply {
            files = UncivFiles(Gdx.files)
            settings = GameSettings()
        }
        RulesetCache.loadRulesets(noMods = true)
        val port = System.getenv("UNCIV_ENGINE_WORKER_PORT")?.toIntOrNull() ?: 43170
        val authentication = System.getenv("UNCIV_ENGINE_WORKER_SECRET")
            ?.let(EngineWorkerAuthentication::fromHex)
            ?: error("UNCIV_ENGINE_WORKER_SECRET is required")
        val runtimeLimits = EngineWorkerRuntimeLimits.fromEnvironment()
        LoopbackEngineWorkerServer(
            authentication = authentication,
            runtimeLimits = runtimeLimits,
        ).serve(port)
    }
}
