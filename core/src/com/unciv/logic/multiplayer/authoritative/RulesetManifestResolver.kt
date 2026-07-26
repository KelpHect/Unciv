package com.unciv.logic.multiplayer.authoritative

/** Resolves one exact installed content-addressed bundle without allowing a
 * caller to invent a manifest hash or silently select between versions. */
internal class RulesetManifestResolver(private val transport: ApiV3Transport) {
    suspend fun resolve(
        baseRulesetName: String,
        modNames: Set<String>,
    ): ApiV3RulesetManifestSummary {
        require(baseRulesetName.isNotBlank()) { "Base ruleset name must not be blank" }
        require(modNames.size <= 64 && modNames.none(String::isBlank)) {
            "Ruleset mod names must be bounded and non-blank"
        }
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        val matches = mutableListOf<ApiV3RulesetManifestSummary>()
        do {
            val page = transport.listRulesetManifests(cursor, 100)
            matches += page.manifests.filter { manifest ->
                manifest.baseRuleset.name == baseRulesetName &&
                    manifest.mods.map { it.name }.toSet() == modNames
            }
            cursor = page.nextCursor
            check(cursor == null || seenCursors.add(cursor)) {
                "API v3 manifest pagination repeated a cursor"
            }
        } while (cursor != null)
        return matches.singleOrNull()
            ?: error(
                if (matches.isEmpty())
                    "No installed API v3 ruleset manifest matches this setup"
                else
                    "Multiple installed API v3 ruleset manifests match this setup",
            )
    }
}
