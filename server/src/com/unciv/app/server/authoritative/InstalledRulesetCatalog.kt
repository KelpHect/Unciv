package com.unciv.app.server.authoritative

import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.models.ruleset.RulesetCache
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Immutable content identities for the parsed rulesets held by this JVM.
 *
 * Capture happens immediately after [RulesetCache] loads. Commands compare
 * their manifest with this snapshot instead of re-reading mutable files while
 * executing an older in-memory ruleset.
 */
object InstalledRulesetCatalog {
    @Volatile
    private var captured: RulesetCatalogSnapshot? = null

    val engineBuild: String
        get() = snapshot().engineBuild

    fun initialize() {
        check(captured == null) { "Installed ruleset catalog is already initialized" }
        captured = RulesetCatalogSnapshot.capture(
            UncivGame.VERSION.toSerializeString(),
            RulesetCache.mapValues { (_, ruleset) ->
                ruleset.folderLocation?.child("jsons")
                    ?: FileHandle("jsons/${ruleset.name}")
            },
        )
    }

    fun all(): List<WorkerRuleset> = snapshot().rulesets.values.toList()

    fun named(name: String): WorkerRuleset =
        snapshot().rulesets[name] ?: error("Pinned ruleset is unavailable: $name")

    fun requireAvailable(manifest: WorkerRulesetManifest) = snapshot().requireAvailable(manifest)

    private fun snapshot() = checkNotNull(captured) {
        "Installed ruleset catalog was not captured at worker startup"
    }
}

internal data class RulesetCatalogSnapshot(
    val engineBuild: String,
    val rulesets: Map<String, WorkerRuleset>,
) {
    fun requireAvailable(manifest: WorkerRulesetManifest) {
        require(manifest.engineBuild == engineBuild && safeName.matches(manifest.engineBuild)) {
            "Pinned engine build is unavailable"
        }
        val requested = listOf(manifest.baseRuleset) + manifest.mods
        require(requested.size <= maxRulesets) { "Ruleset manifest exceeds its component limit" }
        require(requested.all { safeName.matches(it.name) && lowercaseSha256.matches(it.sha256) }) {
            "Ruleset manifest contains an invalid identity"
        }
        require(requested.map { it.name }.distinct().size == requested.size) {
            "Ruleset manifest contains duplicate names"
        }
        requested.forEach { expected ->
            require(rulesets[expected.name]?.sha256 == expected.sha256) {
                "Pinned ruleset content is unavailable: ${expected.name}"
            }
        }
    }

    companion object {
        private const val maxRulesets = 65
        private val safeName = Regex("[^\\p{Cc}\\p{Cf}]{1,128}")
        private val lowercaseSha256 = Regex("[0-9a-f]{64}")

        fun capture(engineBuild: String, roots: Map<String, FileHandle>): RulesetCatalogSnapshot {
            require(engineBuild.isNotBlank()) { "Engine build must not be blank" }
            require(roots.isNotEmpty()) { "Worker has no installed rulesets" }
            val rulesets = roots.toSortedMap().mapValues { (name, root) ->
                require(name.isNotBlank()) { "Ruleset name must not be blank" }
                require(root.exists() && root.isDirectory) { "Ruleset JSON is unavailable: $name" }
                WorkerRuleset(name, hashDirectory(root))
            }
            return RulesetCatalogSnapshot(engineBuild, rulesets)
        }

        internal fun hashDirectory(root: FileHandle): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val files = collectFiles(root).sortedBy { it.first }
            require(files.isNotEmpty()) { "Ruleset JSON directory is empty" }
            files.forEach { (relativePath, file) ->
                val path = relativePath.replace('\\', '/').toByteArray(Charsets.UTF_8)
                val bytes = file.readBytes()
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array())
                digest.update(path)
                digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun collectFiles(
            root: FileHandle,
            prefix: String = "",
        ): List<Pair<String, FileHandle>> = root.list().flatMap { child ->
            val relative = if (prefix.isEmpty()) child.name() else "$prefix/${child.name()}"
            if (child.isDirectory) collectFiles(child, relative) else listOf(relative to child)
        }
    }
}
