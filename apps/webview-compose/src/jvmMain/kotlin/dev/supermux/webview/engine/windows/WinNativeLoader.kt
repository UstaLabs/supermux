package dev.supermux.webview.engine.windows

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.util.Properties

/** Why the glue can't be loaded: engines report it as `Unavailable(NativeLibraryFailed, message)`. */
internal class WinGlueException(message: String) : Exception(message)

/**
 * Finds, extracts, verifies and loads the Windows engine's glue (D19).
 * - The jar holds `dev/supermux/webview/native/windows-<arch>/native.properties` (`library`, `abi`, `version`, `sha256`)
 *   and the DLL it names, staged by Gradle's `stageWindowsGlue`.
 * - The ABI is checked before anything loads, and the loaded DLL's `abiVersion()` after.
 * - The jar's bytes must hash to the recorded `sha256`. They are extracted to
 *   `<cache>/dev.supermux.webview/<version>/<sha256>/<library>`; each level is used only if it is a real directory (not a
 *   link or a junction) this user owns. A file there is reused only if its hash matches; otherwise a fresh copy is renamed
 *   over it. The file is hashed again just before `System.load`.
 * - `jawt` loads first: the glue finds `JAWT_GetAWT` in it, and links no JDK library.
 * - `-Ddev.supermux.webview.windowsLibrary=<path>` loads that file instead (ABI still checked): for working on the glue.
 */
internal class WinNativeLoader(
    private val resource: (String) -> InputStream? = { WinNativeLoader::class.java.getResourceAsStream(it) },
    private val cacheRoots: List<File> = defaultCacheRoots(),
    private val override: String? = System.getProperty(LIBRARY_PROPERTY)?.takeIf { it.isNotBlank() },
    private val arch: String? = archOf(System.getProperty("os.arch").orEmpty()),
    private val loadJawt: () -> Unit = {
        java.awt.Toolkit.getDefaultToolkit()
        System.loadLibrary("jawt")
    },
    private val systemLoad: (String) -> Unit = { System.load(it) },
    private val abiOfLoaded: () -> Int = { WinGlue.abiVersion() },
) {
    fun load(): File {
        val arch = arch ?: throw WinGlueException("no Windows glue is built for ${System.getProperty("os.arch")}")
        try {
            loadJawt()
        } catch (e: Throwable) {
            throw WinGlueException("can't load jawt, which the glue needs: $e")
        }
        override?.let { return loadFile(File(it)) }
        val root = "$RESOURCE_ROOT/windows-$arch"
        val properties = Properties()
        (resource("$root/native.properties")
            ?: throw WinGlueException("this build has no Windows glue ($root/native.properties is missing): winvm.sh build, then Gradle"))
            .use { properties.load(it) }
        val library = properties.getProperty("library")?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
            ?: throw WinGlueException("$root/native.properties names no library")
        val abi = properties.getProperty("abi")?.toIntOrNull()
        if (abi != WinGlue.ABI) throw WinGlueException("the packaged glue speaks ABI $abi, this library needs ${WinGlue.ABI}")
        val version = properties.getProperty("version")?.takeIf { it.matches(VERSION) } ?: "unversioned"
        val recorded = properties.getProperty("sha256")?.lowercase()
        val bytes = (resource("$root/$library") ?: throw WinGlueException("$root/$library is not packaged")).use { it.readBytes() }
        val sha = sha256(bytes)
        if (sha != recorded) throw WinGlueException("$root/$library doesn't match its recorded sha256")
        val file = extract(bytes, sha, version, library)
        val onDisk = try {
            sha256(file.readBytes())
        } catch (e: IOException) {
            throw WinGlueException("can't read $file back: ${e.message}")
        }
        if (onDisk != sha) throw WinGlueException("$file changed between its extraction and its loading")
        return loadFile(file)
    }

    private fun loadFile(file: File): File {
        if (!file.isFile) throw WinGlueException("$file does not exist")
        try {
            systemLoad(file.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw WinGlueException("can't load $file: ${e.message}")
        }
        val abi = try {
            abiOfLoaded()
        } catch (e: UnsatisfiedLinkError) {
            throw WinGlueException("$file has no abiVersion(): ${e.message}")
        }
        if (abi == NO_UPCALL) {
            throw WinGlueException(
                "$file can't find WinGlue's upcalls: a release build shrunk with ProGuard removed them, as nothing in Kotlin " +
                    "calls them. Keep them: $KEEP_RULE (webview-compose's README, Windows)",
            )
        }
        if (abi != WinGlue.ABI) throw WinGlueException("$file speaks ABI $abi, this library needs ${WinGlue.ABI}")
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
                val part = Files.createTempFile(dir.toPath(), "$library.", ".part")
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
                // A DLL another process of the app has loaded can't be replaced: the next root is tried.
                errors += "$root: ${e.message}"
            } catch (e: SecurityException) {
                errors += "$root: ${e.message}"
            }
        }
        throw WinGlueException("can't extract $library (${errors.joinToString("; ")})")
    }

    private fun privateDir(parent: File, name: String, me: UserPrincipal): File {
        val dir = File(parent, name).toPath()
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(dir)
            } catch (_: FileAlreadyExistsException) {
                // Another process made it first: checked below like any other.
            }
        }
        if (!isPrivateDir(dir, me)) throw IOException("$dir is not a directory of this user's own")
        return dir.toFile()
    }

    companion object {
        const val RESOURCE_ROOT: String = "/dev/supermux/webview/native"
        const val LIBRARY_PROPERTY: String = "dev.supermux.webview.windowsLibrary"
        const val CACHE_PROPERTY: String = "dev.supermux.webview.cacheDir"
        const val BASE_DIR: String = "dev.supermux.webview"
        const val NO_UPCALL: Int = -1
        const val KEEP_RULE: String = "-keep class dev.supermux.webview.engine.windows.WinGlue { *; }"
        private val VERSION = Regex("[A-Za-z0-9._+-]+")

        fun archOf(osArch: String): String? = when (osArch.lowercase()) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> null
        }

        fun currentUser(dir: Path): UserPrincipal {
            val probe = Files.createTempFile(dir, ".owner", ".tmp")
            try {
                return Files.getOwner(probe)
            } finally {
                Files.deleteIfExists(probe)
            }
        }

        /** A real directory (not a symbolic link, nor a junction: a reparse point is "other"), owned by [me]. */
        fun isPrivateDir(dir: Path, me: UserPrincipal): Boolean {
            val attributes = try {
                Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                return false
            }
            if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) return false
            return Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS) == me
        }

        /** `-Ddev.supermux.webview.cacheDir`, then `%LOCALAPPDATA%\dev.supermux.webview\cache`, then `java.io.tmpdir`. */
        fun defaultCacheRoots(): List<File> = listOfNotNull(
            System.getProperty(CACHE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File),
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "dev.supermux.webview\\cache") },
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let(::File),
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** The process's glue, loaded at most once (macOS's `GlueLoading`): null once loaded, else why not, every time after. */
internal object WinNativeLibrary {
    private var tried = false
    private var failure: String? = null

    @Synchronized
    fun ensureLoaded(): String? {
        if (tried) return failure
        tried = true
        failure = try {
            WinNativeLoader().load()
            null
        } catch (e: WinGlueException) {
            e.message ?: "the Windows glue failed to load"
        } catch (e: Throwable) {
            "the Windows glue failed to load: $e"
        }
        return failure
    }
}
