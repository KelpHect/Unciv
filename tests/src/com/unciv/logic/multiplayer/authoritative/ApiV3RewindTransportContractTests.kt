package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiV3RewindTransportContractTests {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    @Test
    fun rewindProposalUsesTheClosedServerFieldNames() {
        val encoded = json.encodeToString(
            ApiV3ProposeRewindRequest(
                "00000000-0000-4000-8000-000000000001",
                14,
                8,
            ),
        )

        assertTrue(encoded.contains("\"request_id\""))
        assertTrue(encoded.contains("\"expected_head_revision\":14"))
        assertTrue(encoded.contains("\"target_revision\":8"))
        assertFalse(encoded.contains("requestId"))
    }

    @Test
    fun rewindStatusDecodesTheExactRustResponse() {
        val status = json.decodeFromString<ApiV3RewindStatus>(
            """
            {
              "request_id":"00000000-0000-4000-8000-000000000001",
              "expected_head_revision":14,
              "target_revision":8,
              "status":"pending",
              "approvals":1,
              "required_approvals":2,
              "actor_approved":true,
              "applied_revision":null
            }
            """.trimIndent(),
        )

        assertEquals(14, status.expectedHeadRevision)
        assertEquals(8, status.targetRevision)
        assertEquals(2, status.requiredApprovals)
        assertEquals(true, status.actorApproved)
        assertEquals(null, status.appliedRevision)
    }
}
