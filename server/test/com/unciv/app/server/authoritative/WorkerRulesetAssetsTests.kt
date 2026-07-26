package com.unciv.app.server.authoritative

import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class WorkerRulesetAssetsTests {
    @Test
    fun acceptsBuiltinsAndBoundedStagedMods() {
        val root = Files.createTempDirectory("unciv-worker-assets")
        try {
            Files.createDirectories(root.resolve("jsons/Base"))
            Files.writeString(root.resolve("jsons/Base/Rules.json"), "{}")
            Files.createDirectories(root.resolve("mods/Example Mod/jsons"))
            Files.writeString(root.resolve("mods/Example Mod/jsons/ModOptions.json"), "{}")

            WorkerRulesetAssets.validate(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsModWithoutRulesetJson() {
        val root = Files.createTempDirectory("unciv-worker-assets-empty-mod")
        try {
            Files.createDirectories(root.resolve("jsons/Base"))
            Files.writeString(root.resolve("jsons/Base/Rules.json"), "{}")
            Files.createDirectories(root.resolve("mods/Empty Mod"))

            assertThrows(IllegalArgumentException::class.java) {
                WorkerRulesetAssets.validate(root)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsLinksBeforeRulesetLoading() {
        val root = Files.createTempDirectory("unciv-worker-assets-link")
        val outside = Files.createTempDirectory("unciv-worker-assets-outside")
        try {
            Files.createDirectories(root.resolve("jsons/Base"))
            Files.writeString(root.resolve("jsons/Base/Rules.json"), "{}")
            Files.createDirectories(root.resolve("mods/Linked Mod"))
            val link = root.resolve("mods/Linked Mod/jsons")
            val linked = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
            assumeTrue("Symbolic links are unavailable on this host", linked)

            assertThrows(IllegalArgumentException::class.java) {
                WorkerRulesetAssets.validate(root)
            }
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
