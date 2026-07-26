package com.unciv.app.server.authoritative

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import java.nio.file.Paths
import kotlin.system.exitProcess

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
        WorkerRulesetAssets.validate(Paths.get("").toAbsolutePath().normalize())
        val loadingErrors = RulesetCache.loadRulesets(consoleMode = true, noMods = false)
        require(loadingErrors.isEmpty()) { "Worker ruleset loading failed" }
        InstalledRulesetCatalog.initialize()
        if (args.contentEquals(arrayOf("--print-catalog"))) {
            val status = runCatching {
                EngineWorkerAssetValidation.printCatalog()
            }.fold({ 0 }, {
                System.err.println("Worker asset catalog failed closed")
                2
            })
            System.out.flush()
            System.err.flush()
            exitProcess(status)
        }
        if (args.firstOrNull() == "--validate-manifest") {
            val status = runCatching {
                require(args.size == 2) { "Manifest validation requires exactly one manifest path" }
                EngineWorkerAssetValidation.validate(Paths.get(args[1]))
            }.fold({ 0 }, {
                System.err.println("Worker asset validation failed closed")
                2
            })
            System.out.flush()
            System.err.flush()
            exitProcess(status)
        }
        require(args.isEmpty()) { "Unknown authoritative worker arguments" }
        val port = System.getenv("UNCIV_ENGINE_WORKER_PORT")?.toIntOrNull() ?: 43170
        val authentication = System.getenv("UNCIV_ENGINE_WORKER_SECRET")
            ?.let(EngineWorkerAuthentication::fromHex)
            ?: error("UNCIV_ENGINE_WORKER_SECRET is required")
        val runtimeLimits = EngineWorkerRuntimeLimits.fromEnvironment()
        val releaseBundleId = System.getenv("UNCIV_V3_RELEASE_BUNDLE_ID")
            ?: if (System.getenv("UNCIV_V3_UNPACKAGED_DEV") == "1") "dev-unpackaged"
            else error("UNCIV_V3_RELEASE_BUNDLE_ID is required")
        LoopbackEngineWorkerServer(
            worker = AuthoritativeEngineWorker(releaseBundleId),
            authentication = authentication,
            runtimeLimits = runtimeLimits,
        ).serve(port)
    }
}
