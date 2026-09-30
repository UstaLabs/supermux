package dev.supermux.desktop.host

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * MUX_* settings carried over from a service the app took over (spec §Takeover). Kept apart from
 * `hosting.json` because it can hold secrets (bot tokens), and it must outlive the takeover journal:
 * every later install/update/child start rebuilds the broker env from it.
 *
 * During a takeover the new env is written to a PENDING file; [promotePending] makes it current on
 * commit, [deletePending] drops it on rollback, so a failed takeover never changes the current env.
 */
class CarriedEnvStore(private val file: Path, private val log: (String) -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val pendingFile: Path = file.resolveSibling(file.fileName.toString().removeSuffix(".json") + ".pending.json")

    fun load(): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        return try {
            json.decodeFromString(serializer, Files.readString(file))
        } catch (e: Exception) {
            log("couldn't read the carried-over settings in $file: ${e.message}")
            emptyMap()
        }
    }

    fun save(env: Map<String, String>) = write(file, env)

    fun savePending(env: Map<String, String>) = write(pendingFile, env)

    @Synchronized
    fun promotePending() {
        if (!Files.exists(pendingFile)) return
        Files.move(pendingFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    fun deletePending() {
        Files.deleteIfExists(pendingFile)
    }

    @Synchronized
    private fun write(target: Path, env: Map<String, String>) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "carried", ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
            Files.writeString(tmp, json.encodeToString(serializer, env))
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
