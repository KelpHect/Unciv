package com.unciv.app.server.authoritative

import com.badlogic.gdx.files.FileHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class InstalledRulesetCatalogTests {
    @Test
    fun capturedCatalogDoesNotFollowLaterFilesystemChanges() {
        val root = Files.createTempDirectory("unciv-ruleset-snapshot")
        try {
            Files.writeString(root.resolve("Rules.json"), """{"value":"original"}""")
            val catalog = RulesetCatalogSnapshot.capture(
                "engine-1",
                mapOf("Base" to FileHandle(root.toFile())),
            )
            val captured = catalog.rulesets.getValue("Base")

            Files.writeString(root.resolve("Rules.json"), """{"value":"replaced"}""")

            assertEquals(captured, catalog.rulesets.getValue("Base"))
            catalog.requireAvailable(
                WorkerRulesetManifest("engine-1", captured),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun manifestIdentityMustBeCanonicalAndBounded() {
        val installed = WorkerRuleset("Base", "a".repeat(64))
        val catalog = RulesetCatalogSnapshot("engine-1", mapOf("Base" to installed))

        assertThrows(IllegalArgumentException::class.java) {
            catalog.requireAvailable(
                WorkerRulesetManifest("engine-1", installed.copy(sha256 = "A".repeat(64))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.requireAvailable(
                WorkerRulesetManifest(
                    "engine-1",
                    installed,
                    List(65) { index -> WorkerRuleset("mod-$index", "b".repeat(64)) },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.requireAvailable(
                WorkerRulesetManifest(
                    "engine-1",
                    installed,
                    listOf(installed),
                ),
            )
        }
    }
}
