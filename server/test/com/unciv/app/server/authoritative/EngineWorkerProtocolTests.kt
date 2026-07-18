package com.unciv.app.server.authoritative

import com.badlogic.gdx.files.FileHandle
import com.unciv.models.ruleset.RulesetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files

class EngineWorkerProtocolTests {
    @Test
    fun handshakeNeedsNoActorOrManifest() {
        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                operation = WorkerOperation.Handshake,
            ),
        )

        assertNull(response.error)
        assertEquals(InstalledRulesetCatalog.engineBuild, response.engineBuild)
        assertEquals(EngineWorkerProtocol.VERSION, response.protocolVersion)
        assertTrue(response.installedRulesets?.any { it.name == "Civ V - Vanilla" } == true)
    }

    @Test
    fun mismatchedRulesetBytesAreRejectedBeforeSnapshotParsing() {
        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                actorId = "account-1",
                rulesetManifest = WorkerRulesetManifest(
                    engineBuild = InstalledRulesetCatalog.engineBuild,
                    baseRuleset = WorkerRuleset("Civ V - Vanilla", "0".repeat(64)),
                ),
                operation = WorkerOperation.EndTurn("not-a-save", "Rome"),
            ),
        )

        assertEquals("engine_rejected", response.error?.code)
        assertTrue(response.error?.message?.contains("ruleset content", ignoreCase = true) == true)
        assertNull(response.snapshot)
    }

    @Test
    fun mismatchedEngineBuildIsRejectedBeforeGameExecution() {
        val response = AuthoritativeEngineWorker().execute(
            WorkerRequest(
                protocolVersion = EngineWorkerProtocol.VERSION,
                actorId = "account-1",
                rulesetManifest = WorkerRulesetManifest(
                    engineBuild = "not-this-worker",
                    baseRuleset = WorkerRuleset("Civ V - Vanilla", "0".repeat(64)),
                ),
                operation = WorkerOperation.CreateGame("{}"),
            ),
        )

        assertEquals("engine_rejected", response.error?.code)
        assertTrue(response.error?.message?.contains("engine build") == true)
        assertNull(response.snapshot)
    }

    @Test
    fun rulesetHashIsStableAcrossCreationOrderAndChangesWithContent() {
        val firstRoot = Files.createTempDirectory("unciv-ruleset-a")
        val secondRoot = Files.createTempDirectory("unciv-ruleset-b")
        try {
            Files.createDirectories(firstRoot.resolve("nested"))
            Files.writeString(firstRoot.resolve("z.json"), "z")
            Files.writeString(firstRoot.resolve("nested/a.json"), "a")
            Files.createDirectories(secondRoot.resolve("nested"))
            Files.writeString(secondRoot.resolve("nested/a.json"), "a")
            Files.writeString(secondRoot.resolve("z.json"), "z")

            val first = InstalledRulesetCatalog.hashDirectory(FileHandle(firstRoot.toFile()))
            val same = InstalledRulesetCatalog.hashDirectory(FileHandle(secondRoot.toFile()))
            assertEquals(first, same)

            Files.writeString(secondRoot.resolve("nested/a.json"), "changed")
            val changed = InstalledRulesetCatalog.hashDirectory(FileHandle(secondRoot.toFile()))
            assertNotEquals(first, changed)
        } finally {
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadInstalledRulesets() {
            RulesetCache.loadRulesets(consoleMode = true, noMods = true)
        }
    }
}
