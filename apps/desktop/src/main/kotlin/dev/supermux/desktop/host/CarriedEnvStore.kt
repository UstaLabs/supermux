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
 */
class CarriedEnvStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun load(): Map<String, String> =
        runCatching { json.decodeFromString(serializer, Files.readString(file)) }.getOrDefault(emptyMap())

    @Synchronized
    fun save(env: Map<String, String>) {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, "carried", ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
            Files.writeString(tmp, json.encodeToString(serializer, env))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
