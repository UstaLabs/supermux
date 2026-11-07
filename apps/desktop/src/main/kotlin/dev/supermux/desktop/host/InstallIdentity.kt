package dev.supermux.desktop.host

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Which copy of the app this is (card #77). Every copy gives its background service the same name
 * ([BrokerService]), so a second copy (a dev build, a reinstall with fresh app data) finds the first
 * one's definition under it. The id each definition carries ([BrokerService.INSTALL_ENV]) says which
 * copy set it up, and a copy acts on its own definitions only.
 *
 * Kept with the app's own data (paired hosts, tokens), not in hosting.json: on Windows that file sits
 * in %USERPROFILE%\.config while the app's data is in %APPDATA%, and fresh app data is a new install.
 */
class InstallIdentity(private val file: Path) {
    @Serializable
    private data class Stored(val id: String, val ownsUnstamped: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What [read] found: no file yet, this install's id, or a file that can't be read (never replaced). */
    sealed interface Read {
        data object Missing : Read
        data class Found(val owner: BrokerService.Owner) : Read
        data class Unreadable(val why: String) : Read
    }

    fun read(): Read {
        for (attempt in 1..3) {
            try {
                val s = json.decodeFromString(Stored.serializer(), Files.readString(file))
                return if (ID.matches(s.id)) Read.Found(BrokerService.Owner(s.id, s.ownsUnstamped)) else Read.Unreadable("not an install id")
            } catch (_: NoSuchFileException) {
                return Read.Missing
            } catch (e: SerializationException) {
                return Read.Unreadable(e.message ?: "not an install id")
            } catch (e: IOException) {
                // Windows: another process may hold the file a moment.
                if (attempt == 3) return Read.Unreadable(e.message ?: e.toString())
                Thread.sleep(100)
            }
        }
        error("unreachable")
    }

    /** This install, or null when there is none yet or it can't be read. */
    fun load(): BrokerService.Owner? = (read() as? Read.Found)?.owner

    /**
     * Saves [owner] as this install's id, unless one was saved meanwhile (another launch of this copy):
     * then that one, so both end up with the same id. Throws when it can't be saved.
     */
    fun create(owner: BrokerService.Owner): BrokerService.Owner {
        Files.createDirectories(file.parent)
        try {
            Files.writeString(file, encode(owner), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            return owner
        } catch (_: FileAlreadyExistsException) {
            // The other launch may still be writing it.
            repeat(10) { (read() as? Read.Found)?.let { return it.owner }; Thread.sleep(50) }
            throw IOException("$file appeared but can't be read")
        }
    }

    /** Replaces the saved id's details (it stays the same id). */
    fun save(owner: BrokerService.Owner) = BrokerService.writePrivate(file, encode(owner))

    private fun encode(owner: BrokerService.Owner) = json.encodeToString(Stored.serializer(), Stored(owner.id, owner.ownsUnstamped))

    companion object {
        private val ID = Regex("[0-9a-f]{32}")

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")

        fun defaultFile(): Path = DesktopHostStores.defaultDir().resolve("install.json")
    }
}
