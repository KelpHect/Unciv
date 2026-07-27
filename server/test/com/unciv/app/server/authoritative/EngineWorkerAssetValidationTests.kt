package com.unciv.app.server.authoritative

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
        fun loadInstalledRulesets() = PackagedWorkerParityHarness.initializeRulesets()
    }
}
