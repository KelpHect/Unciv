package com.unciv.app.desktop

import com.unciv.logic.multiplayer.authoritative.ApiV3SessionTokenStore
import com.unciv.logic.multiplayer.authoritative.MAX_API_V3_SESSION_TOKEN_BYTES
import com.unciv.logic.multiplayer.authoritative.requireValidApiV3SessionToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Uses the freedesktop Secret Service through `secret-tool`.
 *
 * The session token is supplied only over the child process's stdin. It never
 * appears in command arguments, environment variables, preferences, or files.
 */
class LinuxApiV3SessionTokenStore private constructor(
    private val executable: Path,
    private val account: String,
) : ApiV3SessionTokenStore {
    private val mutex = Mutex()

    override suspend fun load(): String? = mutex.withLock {
        val result = run(
            "lookup",
            "service",
            SERVICE,
            "account",
            account,
        )
        if (result.exitCode == NOT_FOUND_EXIT) return@withLock null
        check(result.exitCode == 0) { "Secret Service rejected the API-v3 credential lookup" }
        val token = result.output.toString(Charsets.UTF_8).trimEnd('\r', '\n')
        result.output.fill(0)
        try {
            requireValidApiV3SessionToken(token)
            token
        } catch (_: Exception) {
            clearSecret()
            null
        }
    }

    override suspend fun save(token: String) = mutex.withLock {
        requireValidApiV3SessionToken(token)
        val bytes = token.toByteArray(Charsets.UTF_8)
        try {
            val result = run(
                "store",
                "--label",
                LABEL,
                "service",
                SERVICE,
                "account",
                account,
                input = bytes,
            )
            result.output.fill(0)
            check(result.exitCode == 0) { "Secret Service rejected the API-v3 credential write" }
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun clear() = mutex.withLock {
        clearSecret()
    }

    private suspend fun clearSecret() {
        val result = run(
            "clear",
            "service",
            SERVICE,
            "account",
            account,
        )
        result.output.fill(0)
        check(result.exitCode == 0 || result.exitCode == NOT_FOUND_EXIT) {
            "Secret Service rejected the API-v3 credential deletion"
        }
    }

    private suspend fun run(
        vararg arguments: String,
        input: ByteArray? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(executable.toString()) + arguments.toList())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = CompletableFuture.supplyAsync {
            process.inputStream.use(::readBounded)
        }
        try {
            process.outputStream.use { stream ->
                if (input != null) stream.write(input)
            }
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Secret Service API-v3 credential operation timed out")
            }
            CommandResult(
                process.exitValue(),
                output.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
            if (!output.isDone) output.cancel(true)
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream(MAX_COMMAND_OUTPUT_BYTES)
        val buffer = ByteArray(256)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_COMMAND_OUTPUT_BYTES) {
                "Secret Service returned an oversized credential response"
            }
            output.write(buffer, 0, read)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private data class CommandResult(val exitCode: Int, val output: ByteArray)

    companion object {
        private const val SERVICE = "com.unciv.api-v3-session"
        private const val LABEL = "Unciv API v3 multiplayer session"
        private const val NOT_FOUND_EXIT = 1
        private const val COMMAND_TIMEOUT_SECONDS = 5L
        private const val MAX_COMMAND_OUTPUT_BYTES = MAX_API_V3_SESSION_TOKEN_BYTES + 2

        fun create(
            account: String,
            path: String? = System.getenv("PATH"),
        ): LinuxApiV3SessionTokenStore? {
            if (!account.matches(Regex("[0-9a-f]{64}"))) return null
            val executable = path
                ?.split(java.io.File.pathSeparatorChar)
                ?.asSequence()
                ?.filter(String::isNotBlank)
                ?.map { Path.of(it).resolve("secret-tool").toAbsolutePath().normalize() }
                ?.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
                ?: return null
            return LinuxApiV3SessionTokenStore(executable, account)
        }
    }
}
