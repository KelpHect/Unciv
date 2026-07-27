package com.unciv.app.server.authoritative

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Validates the root-owned ruleset tree before the worker parses any content.
 *
 * The worker never downloads mods. Operators stage extracted bundles beneath
 * `mods/` while the service is stopped; systemd makes the complete asset root
 * read-only at runtime.
 */
object WorkerRulesetAssets {
    private const val maxMods = 64
    private const val maxEntries = 16_384
    private const val maxFileBytes = 16L * 1024 * 1024
    private const val maxTotalBytes = 512L * 1024 * 1024
    private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9 ._()+'-]{0,127}")

    fun validate(assetRoot: Path) {
        require(Files.isDirectory(assetRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Worker asset root is unavailable"
        }
        val budget = AssetBudget()
        validateTree(assetRoot.resolve("jsons"), "built-in rulesets", budget)
        validateModsRoot(assetRoot.resolve("mods"), budget, required = false)
    }

    fun validateModsRoot(modsRoot: Path) {
        validateModsRoot(modsRoot, AssetBudget(), required = true)
    }

    private fun validateModsRoot(modsRoot: Path, budget: AssetBudget, required: Boolean) {
        if (!Files.exists(modsRoot, LinkOption.NOFOLLOW_LINKS)) {
            require(!required) { "Worker mods path is unavailable" }
            return
        }
        require(Files.isDirectory(modsRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Worker mods path must be a directory"
        }
        val mods = Files.list(modsRoot).use { entries -> entries.toList() }
        require(mods.size <= maxMods) { "Worker mod count exceeds its limit" }
        mods.forEach { mod ->
            require(!Files.isSymbolicLink(mod) && Files.isDirectory(mod, LinkOption.NOFOLLOW_LINKS)) {
                "Worker mod entry must be a real directory"
            }
            require(safeName.matches(mod.fileName.toString())) { "Worker mod name is invalid" }
            validateTree(mod.resolve("jsons"), "mod ${mod.fileName}", budget)
        }
    }

    private fun validateTree(root: Path, label: String, budget: AssetBudget) {
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "$label JSON directory is unavailable"
        }
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                budget.entries++
                require(budget.entries <= maxEntries) { "Worker ruleset entry count exceeds its limit" }
                require(!Files.isSymbolicLink(path)) { "$label contains a symbolic link" }
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                require(attributes.isDirectory || attributes.isRegularFile) {
                    "$label contains an unsupported filesystem entry"
                }
                if (attributes.isRegularFile) {
                    require(attributes.size() <= maxFileBytes) { "$label contains an oversized file" }
                    budget.totalBytes = Math.addExact(budget.totalBytes, attributes.size())
                    require(budget.totalBytes <= maxTotalBytes) {
                        "Worker ruleset assets exceed their byte limit"
                    }
                }
            }
        }
    }

    private class AssetBudget(
        var entries: Int = 0,
        var totalBytes: Long = 0,
    )
}
