package dev.supermux.editor.syntax

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * Finds and loads `supermux_syntax_jni`, in this order:
 * 1. `-Deditor.syntax.lib=<absolute path>` (JVM tests; no checks).
 * 2. The desktop library packaged in the jar for this os/arch, staged by
 *    `:editor-syntax:stageJvmNativeResources` as
 *    `/dev/supermux/editor/syntax/natives/<os>-<arch>/{<library>, native.properties}`. Its sha256 and
 *    size are checked, then it is written once to a content-addressed per-user cache,
 *    `<cache>/supermux/natives/<sha256>/<library>` (`~/.cache` or `$XDG_CACHE_HOME` on Linux,
 *    `~/Library/Caches` on macOS, `%LOCALAPPDATA%` on Windows), through a temp file and an atomic
 *    rename, and loaded from there. An existing file whose bytes hash to the same sha256 is reused.
 *    Only when no cache directory is writable does it go to a fresh private temp directory.
 * 3. `System.loadLibrary`: Android, where the library comes from the APK's jniLibs.
 *
 * The sha256 in native.properties comes from the same jar as the library, so it catches a truncated
 * or corrupted jar or extraction, NOT tampering: whoever can change the jar can change both.
 */
internal object SesNativeLoader {
    const val LIBRARY_PROPERTY = "editor.syntax.lib"
    const val RESOURCE_ROOT = "/dev/supermux/editor/syntax/natives"

    fun load() {
        System.getProperty(LIBRARY_PROPERTY)?.takeIf { it.isNotBlank() }?.let { System.load(it); return }
        val key = platformKey()
        val props = key?.let { SesNativeLoader::class.java.getResourceAsStream("$RESOURCE_ROOT/$it/native.properties") }
        if (props == null) {
            try {
                System.loadLibrary("supermux_syntax_jni")
            } catch (e: UnsatisfiedLinkError) {
                val platform = key ?: "${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
                throw UnsatisfiedLinkError(
                    "editor-syntax: no native library is packaged for $platform, and " +
                        "System.loadLibrary(\"supermux_syntax_jni\") failed: ${e.message}",
                ).apply { initCause(e) }
            }
            return
        }
        try {
            loadPackaged(key, props)
        } catch (e: IOException) {
            throw UnsatisfiedLinkError("editor-syntax ($key): ${e.message}").apply { initCause(e) }
        }
    }

    private fun loadPackaged(key: String, propsStream: InputStream) {
        val p = Properties().apply { propsStream.use { load(it) } }
        val library = p.getProperty("library")?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
            ?: throw IOException("$RESOURCE_ROOT/$key/native.properties names no library")
        val sha = p.getProperty("sha256")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw IOException("$RESOURCE_ROOT/$key/native.properties has no valid sha256")
        val bytes = SesNativeLoader::class.java.getResourceAsStream("$RESOURCE_ROOT/$key/$library")?.use { it.readBytes() }
            ?: throw IOException("$RESOURCE_ROOT/$key/$library is not packaged")
        if (sha256(bytes) != sha || bytes.size.toString() != p.getProperty("size")) {
            throw IOException("the packaged $library is truncated or corrupt (it does not match native.properties)")
        }
        val file = cached(bytes, sha, library)
        try {
            System.load(file.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError("editor-syntax ($key): cannot load $file: ${e.message}").apply { initCause(e) }
        }
    }

    /** The library at `<root>/supermux/natives/<sha>/<library>` of the first root that works. */
    private fun cached(bytes: ByteArray, sha: String, library: String): File {
        val errors = mutableListOf<String>()
        for (root in cacheRoots()) {
            try {
                val dir = File(root, "supermux/natives/$sha")
                Files.createDirectories(dir.toPath())
                val target = File(dir, library)
                if (target.isFile && sha256(target.readBytes()) == sha) return target
                val tmp = Files.createTempFile(dir.toPath(), "$library.", ".tmp")
                try {
                    Files.write(tmp, bytes)
                    try {
                        Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(tmp)
                }
                if (sha256(target.readBytes()) == sha) return target
                errors += "$target: hash mismatch after writing"
            } catch (e: IOException) {
                errors += "$root: ${e.message}"
            } catch (e: SecurityException) {
                errors += "$root: ${e.message}"
            }
        }
        // Last resort: a fresh owner-only directory, never one another user could have prepared.
        val dir = Files.createTempDirectory("supermux-syntax-").toFile()
        val file = File(dir, library)
        file.writeBytes(bytes)
        dir.deleteOnExit()
        file.deleteOnExit()
        if (errors.isNotEmpty()) System.err.println("editor-syntax: no writable cache (${errors.joinToString("; ")}); using $dir")
        return file
    }

    private fun cacheRoots(): List<File> {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val root = when {
            os.startsWith("windows") -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
            os.startsWith("mac") || os.startsWith("darwin") -> home?.let { File(it, "Library/Caches") }
            else -> System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: home?.let { File(it, ".cache") }
        }
        return listOfNotNull(root)
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** `<os>-<arch>` as native/build.sh names the desktop targets, or null for anything else. */
    fun platformKey(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val o = when {
            os.startsWith("linux") -> "linux"
            os.startsWith("mac") || os.startsWith("darwin") -> "macos"
            os.startsWith("windows") -> "windows"
            else -> return null
        }
        val a = when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return "$o-$a"
    }
}
