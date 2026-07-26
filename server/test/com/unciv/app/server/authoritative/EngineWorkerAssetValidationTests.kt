package com.unciv.app.server.authoritative

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files

class EngineWorkerAssetValidationTests {
    @Test
    fun stagedManifestMustMatchParsedRulesAndPassSemanticValidation() {
        val installed = InstalledRulesetCatalog.named("Civ V - Vanilla")
        val valid = WorkerRulesetManifest(
            InstalledRulesetCatalog.engineBuild,
            installed,
        )
        val path = Files.createTempFile("unciv-worker-manifest", ".json")
        try {
            Files.writeString(path, EngineWorkerProtocol.json.encodeToString(valid))
            EngineWorkerAssetValidation.validate(path)

            Files.writeString(
                path,
                EngineWorkerProtocol.json.encodeToString(
                    valid.copy(baseRuleset = installed.copy(sha256 = "0".repeat(64))),
                ),
            )
            assertThrows(IllegalArgumentException::class.java) {
                EngineWorkerAssetValidation.validate(path)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadInstalledRulesets() {
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
            RulesetCache.loadRulesets(consoleMode = true, noMods = true)
            InstalledRulesetCatalog.initialize()
        }
    }
}
