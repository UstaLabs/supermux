package dev.supermux.webview.engine.mac

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.util.Properties

/** Why the glue can't be loaded: engines report it as `Unavailable(NativeLibraryFailed, message)`. */
internal class MacGlueException(message: String) : Exception(message)

/**
 * Finds, extracts, verifies and loads the macOS engine's glue (D8).
 *
 * - The jar holds `dev/supermux/webview/native/macos/native.properties` (`library`, `abi`, `version`) and the library
 *   it names, staged by Gradle's `stageMacGlue` on a Mac.
 * - The properties' ABI must be [MacGlue.ABI] before anything loads, and the loaded library's `abiVersion()` too.
 * - The jar's own bytes are the reference: their SHA-256 names the directory they are extracted to,
 *   `<cache>/dev.supermux.webview/<version>/<sha-256>/<library>`. Every level is created owner-only and used only if it
 *   is a real directory (not a link) of this user that neither group nor others can write. An existing file there is
 *   reused only if its hash matches; otherwise a fresh copy replaces it by an atomic rename, never in place. The file
 *   is hashed again just before `System.load`. No digest is recorded at build time: Compose's mac packaging re-signs
 *   Mach-Os inside jars (M11), which would refuse every packaged app.
 * - `-Ddev.supermux.webview.macLibrary=<path>` loads that file instead, unextracted, ABI still checked: for working on
 *   the glue.
 *
 * Every failure is a [MacGlueException]. The parameters are test hooks.
 */
internal class MacNativeLoader(
    private val resource: (String) -> InputStream? = { MacNativeLoader::class.java.getResourceAsStream(it) },
    private val cacheRoots: List<File> = defaultCacheRoots(),
    private val override: String? = System.getProperty(LIBRARY_PROPERTY)?.takeIf { it.isNotBlank() },
    private val isMac: Boolean = MacPlatform.isMac,
    private val systemLoad: (String) -> Unit = { System.load(it) },
    private val abiOfLoaded: () -> Int = { MacGlue.abiVersion() },
    private val afterExtract: (File) -> Unit = {},
) {
    /** Loads the glue; returns the file it loaded. */
    fun load(): File {
        if (!isMac) throw MacGlueException("the macOS engine's glue runs on macOS only")
        override?.let { return loadFile(File(it)) }
        val properties = Properties()
        val stream = resource("$RESOURCE_ROOT/native.properties")
            ?: throw MacGlueException("this build has no macOS glue ($RESOURCE_ROOT/native.properties is missing): build the library on a Mac")
        stream.use { properties.load(it) }
        val library = properties.getProperty("library")?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
            ?: throw MacGlueException("$RESOURCE_ROOT/native.properties names no library")
        val abi = properties.getProperty("abi")?.toIntOrNull()
        if (abi != MacGlue.ABI) throw MacGlueException("the packaged glue speaks ABI $abi, this library needs ${MacGlue.ABI}")
        val version = properties.getProperty("version")?.takeIf { it.matches(VERSION) } ?: "unversioned"
        val bytes = (resource("$RESOURCE_ROOT/$library") ?: throw MacGlueException("$RESOURCE_ROOT/$library is not packaged"))
            .use { it.readBytes() }
        val sha = sha256(bytes)
        val file = extract(bytes, sha, version, library)
        afterExtract(file)
        val onDisk = try {
            sha256(file.readBytes())
        } catch (e: IOException) {
            throw MacGlueException("can't read $file back: ${e.message}")
        }
        if (onDisk != sha) throw MacGlueException("$file changed between its extraction and its loading")
        return loadFile(file)
    }

    private fun loadFile(file: File): File {
        if (!file.isFile) throw MacGlueException("$file does not exist")
        try {
            systemLoad(file.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw MacGlueException("can't load $file: ${e.message}")
        }
        val abi = try {
            abiOfLoaded()
        } catch (e: UnsatisfiedLinkError) {
            throw MacGlueException("$file has no abiVersion(): ${e.message}")
        }
        if (abi == NO_UPCALL) {
            throw MacGlueException(
                "$file can't find MacGlue.onEvent, its one call back into this library: a release build shrunk with " +
                    "ProGuard removed it, as nothing in Kotlin calls it. Keep it: $KEEP_RULE (webview-compose's README, macOS)",
            )
        }
        if (abi != MacGlue.ABI) throw MacGlueException("$file speaks ABI $abi, this library needs ${MacGlue.ABI}")
        return file
    }

    private fun extract(bytes: ByteArray, sha: String, version: String, library: String): File {
        val errors = mutableListOf<String>()
        for (root in cacheRoots) {
            try {
                Files.createDirectories(root.toPath())
                val me = currentUser(root.toPath())
                val dir = privateDir(privateDir(privateDir(root, BASE_DIR, me), version, me), sha, me)
                val target = File(dir, library)
                if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && sha256(target.readBytes()) == sha) return target
                val part = Files.createTempFile(dir.toPath(), "$library.", ".part", *fileAttributes())
                try {
                    Files.write(part, bytes)
                    try {
                        Files.move(part, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(part, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(part)
                }
                if (sha256(target.readBytes()) == sha) return target
                errors += "$target: its hash differs after extraction"
            } catch (e: IOException) {
                errors += "$root: ${e.message}"
            } catch (e: SecurityException) {
                errors += "$root: ${e.message}"
            }
        }
        throw MacGlueException("can't extract $library (${errors.joinToString("; ")})")
    }

    /** `parent/name`, made owner-only if missing, and used only if it is a private directory (see the class). */
    private fun privateDir(parent: File, name: String, me: UserPrincipal): File {
        val dir = File(parent, name).toPath()
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(dir, *directoryAttributes())
            } catch (_: FileAlreadyExistsException) {
                // Another process made it first: checked below like any existing one.
            }
        }
        if (!isPrivateDir(dir, me)) throw IOException("$dir is not a private directory")
        return dir.toFile()
    }

    companion object {
        const val RESOURCE_ROOT: String = "/dev/supermux/webview/native/macos"
        const val LIBRARY_PROPERTY: String = "dev.supermux.webview.macLibrary"
        const val CACHE_PROPERTY: String = "dev.supermux.webview.cacheDir"
        const val BASE_DIR: String = "dev.supermux.webview"

        /** What the glue's `abiVersion()` says when, loading, it found no `MacGlue.onEvent` to call back (I5). */
        const val NO_UPCALL: Int = -1

        /** The ProGuard rule a shrunk release build needs (`proguard/webview-compose-desktop.pro`, I5). */
        const val KEEP_RULE: String = "-keep class dev.supermux.webview.engine.mac.MacGlue { *; }"
        private val VERSION = Regex("[A-Za-z0-9._+-]+")
        private val posix: Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

        private fun directoryAttributes(): Array<FileAttribute<*>> =
            if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) else emptyArray()

        private fun fileAttributes(): Array<FileAttribute<*>> =
            if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()

        /** The user this JVM runs as: the owner of a file it just made in [dir]. */
        fun currentUser(dir: Path): UserPrincipal {
            val probe = Files.createTempFile(dir, ".owner", ".tmp")
            try {
                return Files.getOwner(probe)
            } finally {
                Files.deleteIfExists(probe)
            }
        }

        /** A real directory (not a link), owned by [me], that neither its group nor others can write. */
        fun isPrivateDir(dir: Path, me: UserPrincipal): Boolean {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false
            if (Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS) != me) return false
            if (!posix) return true
            val permissions = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
            return PosixFilePermission.GROUP_WRITE !in permissions && PosixFilePermission.OTHERS_WRITE !in permissions
        }

        /** `-Ddev.supermux.webview.cacheDir`, then `~/Library/Caches`, then `java.io.tmpdir`. */
        fun defaultCacheRoots(): List<File> = listOfNotNull(
            System.getProperty(CACHE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File),
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it, "Library/Caches") },
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let(::File),
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Runs [load] at most once, on the first [ensureLoaded], and gives the same answer every time after: null once the
 * glue is loaded, else why it isn't. A JVM can't unload a library, and a failed verification doesn't heal by retrying.
 */
internal open class GlueLoading(private val load: () -> Unit) {
    private var tried = false
    private var failure: String? = null

    @Synchronized
    fun ensureLoaded(): String? {
        if (tried) return failure
        tried = true
        failure = try {
            load()
            null
        } catch (e: MacGlueException) {
            e.message ?: "the macOS glue failed to load"
        } catch (e: Throwable) {
            "the macOS glue failed to load: $e"
        }
        return failure
    }
}

/** The process's glue: loaded by whichever needs it first (an engine, the memory hook, the test driver). */
internal object MacNativeLibrary : GlueLoading({ MacNativeLoader().load() })
