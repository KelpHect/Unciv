package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

object PlayerProjectionDeltaApplier {
    const val MAX_OPERATIONS = 4_096
    const val MAX_PATH_BYTES = 1_024

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun apply(
        base: ApiV3GameProjection,
        delta: ApiV3GameProjectionDelta,
    ): ApiV3GameProjection {
        require(delta.gameId == base.gameId) { "Projection delta changed game identity" }
        require(delta.projectionVersion == base.projectionVersion) {
            "Projection delta changed projection version"
        }
        require(delta.projectionVersion == PlayerProjection.CURRENT_PROJECTION_VERSION) {
            "Projection delta uses an incompatible projection version"
        }
        require(delta.baseRevision == base.committedRevision) {
            "Projection delta does not continue the cached revision"
        }
        require(delta.baseCanonicalStateHash == base.canonicalStateHash) {
            "Projection delta does not continue the cached canonical hash"
        }
        require(delta.baseProjectionHash == base.projectionHash) {
            "Projection delta does not continue the cached projection hash"
        }
        require(delta.committedRevision >= delta.baseRevision) {
            "Projection delta moves revision backwards"
        }
        requireHash(delta.canonicalStateHash, "target canonical hash")
        requireHash(delta.projectionHash, "target projection hash")
        require(delta.operations.size <= MAX_OPERATIONS) {
            "Projection delta exceeds the operation limit"
        }

        var value = json.encodeToJsonElement(PlayerProjection.serializer(), base.projection)
        var priorPath: String? = null
        for (operation in delta.operations) {
            require(operation.path.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES) {
                "Projection delta path exceeds the byte limit"
            }
            val segments = parsePointer(operation.path)
            priorPath?.let {
                require(it < operation.path) {
                    "Projection delta paths must be strictly ordered and unique"
                }
                require(!pathsOverlap(it, operation.path)) {
                    "Projection delta paths must not overlap"
                }
            }
            value = replace(value, segments, operation.value)
            priorPath = operation.path
        }
        val projection = json.decodeFromJsonElement(PlayerProjection.serializer(), value)
        val computedHash = hash(json.encodeToString(PlayerProjection.serializer(), projection))
        require(computedHash == delta.projectionHash) {
            "Applied projection delta does not match its target hash"
        }
        return ApiV3GameProjection(
            gameId = delta.gameId,
            projectionVersion = delta.projectionVersion,
            committedRevision = delta.committedRevision,
            canonicalStateHash = delta.canonicalStateHash,
            projectionHash = delta.projectionHash,
            projection = projection,
        )
    }

    fun projectionHash(projection: PlayerProjection): String =
        hash(json.encodeToString(PlayerProjection.serializer(), projection))

    private fun replace(
        current: JsonElement,
        path: List<String>,
        replacement: JsonElement,
    ): JsonElement {
        require(path.isNotEmpty()) { "Projection delta cannot replace its document root" }
        val head = path.first()
        if (path.size == 1) {
            return when (current) {
                is JsonObject -> {
                    require(head in current) { "Projection delta references an unknown field" }
                    JsonObject(current.toMutableMap().apply { put(head, replacement) })
                }
                is JsonArray -> {
                    val index = parseIndex(head, current.size)
                    JsonArray(current.toMutableList().apply { this[index] = replacement })
                }
                else -> error("Projection delta traverses a scalar value")
            }
        }
        return when (current) {
            is JsonObject -> {
                val child = current[head]
                    ?: error("Projection delta references an unknown field")
                JsonObject(current.toMutableMap().apply {
                    put(head, replace(child, path.drop(1), replacement))
                })
            }
            is JsonArray -> {
                val index = parseIndex(head, current.size)
                JsonArray(current.toMutableList().apply {
                    this[index] = replace(this[index], path.drop(1), replacement)
                })
            }
            else -> error("Projection delta traverses a scalar value")
        }
    }

    private fun parsePointer(path: String): List<String> {
        require(path.startsWith('/') && path.length > 1) {
            "Projection delta path must be a non-root JSON pointer"
        }
        return path.substring(1).split('/').map(::decodeSegment)
    }

    private fun decodeSegment(segment: String): String {
        val result = StringBuilder(segment.length)
        var index = 0
        while (index < segment.length) {
            if (segment[index] != '~') {
                result.append(segment[index++])
                continue
            }
            require(index + 1 < segment.length) { "Projection delta has an invalid JSON pointer" }
            result.append(when (segment[index + 1]) {
                '0' -> '~'
                '1' -> '/'
                else -> error("Projection delta has an invalid JSON pointer escape")
            })
            index += 2
        }
        return result.toString()
    }

    private fun parseIndex(segment: String, size: Int): Int {
        require(segment == "0" || segment.firstOrNull() in '1'..'9') {
            "Projection delta has a non-canonical array index"
        }
        val index = segment.toIntOrNull()
            ?: error("Projection delta array index is invalid")
        require(index in 0 until size) { "Projection delta array index is out of bounds" }
        return index
    }

    private fun pathsOverlap(left: String, right: String): Boolean =
        right.startsWith("$left/")

    private fun requireHash(value: String, label: String) {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Projection delta $label is not a lowercase SHA-256 hash"
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
