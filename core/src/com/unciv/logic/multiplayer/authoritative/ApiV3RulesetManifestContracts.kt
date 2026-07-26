package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiV3PublicRulesetIdentity(
    val name: String,
    val sha256: String,
)

@Serializable
data class ApiV3RulesetManifestSummary(
    @SerialName("manifest_hash") val manifestHash: String,
    @SerialName("engine_build") val engineBuild: String,
    @SerialName("base_ruleset") val baseRuleset: ApiV3PublicRulesetIdentity,
    val mods: List<ApiV3PublicRulesetIdentity>,
)

@Serializable
data class ApiV3RulesetManifestPage(
    val manifests: List<ApiV3RulesetManifestSummary>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)
