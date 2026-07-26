package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
class ProjectionDisclosurePolicyTests {
    @Test
    fun everySerializedProjectionLeafHasAnExplicitDisclosureDecision() {
        val policy = readPolicy()
        val serialized = leafPaths(PlayerProjection.serializer().descriptor, PLAYER_PREFIX) +
            leafPaths(SpectatorProjection.serializer().descriptor, SPECTATOR_PREFIX)

        assertEquals(
            "Projection policy and serializers differ. Every added, removed, or renamed " +
                "field requires an explicit confidentiality review.\n" +
                "Missing policy:\n${(serialized - policy.keys).sorted().joinToString("\n")}\n" +
                "Stale policy:\n${(policy.keys - serialized).sorted().joinToString("\n")}",
            serialized,
            policy.keys,
        )
    }

    @Test
    fun disclosurePolicyRowsAreBoundedAndWellFormed() {
        val policy = readPolicy()
        assertTrue(policy.isNotEmpty())
        for ((path, decision) in policy) {
            assertTrue(path.startsWith(PLAYER_PREFIX) || path.startsWith(SPECTATOR_PREFIX))
            assertTrue(decision.classification in CLASSIFICATIONS)
            assertTrue(decision.rationale.length in 12..240)
            assertEquals(
                if (path.startsWith(PLAYER_PREFIX)) "player" else "spectator",
                decision.audience,
            )
        }
    }

    private fun readPolicy(): Map<String, DisclosureDecision> {
        val rows = policyFile().readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
        val result = linkedMapOf<String, DisclosureDecision>()
        for ((index, row) in rows.withIndex()) {
            val columns = row.split('\t')
            require(columns.size == 4) {
                "Projection policy row ${index + 1} must contain four tab-separated columns"
            }
            val (audience, path, classification, rationale) = columns
            require(result.put(path, DisclosureDecision(
                audience,
                classification,
                rationale,
            )) == null) {
                "Projection policy repeats $path"
            }
        }
        return result
    }

    private fun leafPaths(
        descriptor: SerialDescriptor,
        prefix: String,
        ancestors: Set<String> = emptySet(),
    ): Set<String> {
        if (descriptor.serialName in ancestors) return setOf(prefix)
        return when (descriptor.kind) {
            is PrimitiveKind, SerialKind.ENUM -> setOf(prefix)
            StructureKind.LIST ->
                leafPaths(descriptor.getElementDescriptor(0), "$prefix[]", ancestors)
            StructureKind.MAP ->
                leafPaths(descriptor.getElementDescriptor(1), "$prefix{}", ancestors)
            else -> buildSet {
                val nextAncestors = ancestors + descriptor.serialName
                for (index in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(index)
                    addAll(leafPaths(
                        descriptor.getElementDescriptor(index),
                        "$prefix.$name",
                        nextAncestors,
                    ))
                }
            }
        }
    }

    private fun policyFile(): File = generateSequence(
        File(System.getProperty("user.dir")),
    ) { it.parentFile }
        .map { File(it, "docs/security/projection-disclosure-policy.tsv") }
        .first(File::isFile)

    private data class DisclosureDecision(
        val audience: String,
        val classification: String,
        val rationale: String,
    )

    private companion object {
        const val PLAYER_PREFIX = "player"
        const val SPECTATOR_PREFIX = "spectator"
        val CLASSIFICATIONS = setOf(
            "public",
            "player_private",
            "legally_known",
            "action_allowlist",
            "presentation",
            "redacted",
        )
    }
}
