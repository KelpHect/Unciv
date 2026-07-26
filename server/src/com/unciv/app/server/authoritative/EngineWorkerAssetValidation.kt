package com.unciv.app.server.authoritative

import com.unciv.models.ruleset.RulesetCache
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Offline operator-only semantic gate for a staged immutable manifest. */
object EngineWorkerAssetValidation {
    fun printCatalog() {
        println(
            EngineWorkerProtocol.json.encodeToString(
                WorkerAssetValidationResult(
                    engineBuild = InstalledRulesetCatalog.engineBuild,
                    installedRulesets = InstalledRulesetCatalog.all(),
                ),
            ),
        )
    }

    fun validate(manifestPath: Path) {
        require(!Files.isSymbolicLink(manifestPath)) { "Manifest path must not be a link" }
        require(Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            "Manifest file is unavailable"
        }
        val bytes = Files.readAllBytes(manifestPath)
        require(bytes.size in 1..65_536) { "Manifest file exceeds its size limit" }
        EngineWorkerProtocol.validateJsonFrame(bytes)
        val manifest = EngineWorkerProtocol.json.decodeFromString<WorkerRulesetManifest>(
            bytes.decodeToString(),
        )
        InstalledRulesetCatalog.requireAvailable(manifest)
        val (combined, errors) = RulesetCache.checkCombinedModLinks(
            LinkedHashSet(manifest.mods.map { it.name }),
            manifest.baseRuleset.name,
        )
        require(combined != null && !errors.isError()) {
            "Ruleset manifest failed semantic validation"
        }
        printCatalog()
    }
}

@kotlinx.serialization.Serializable
private data class WorkerAssetValidationResult(
    val valid: Boolean = true,
    val engineBuild: String,
    val installedRulesets: List<WorkerRuleset>,
)
