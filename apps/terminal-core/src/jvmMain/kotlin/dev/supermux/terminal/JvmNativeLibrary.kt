package dev.supermux.terminal

import dev.supermux.terminal.TerminalEngineUnavailableException.Reason
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * Finds, verifies, extracts and loads the JNI library packaged in this jar.
 *
 * Layout (written by the Gradle `stageJvmNativeResources` task from `build/native/<target>/`):
 * ```
 * /dev/supermux/terminal/native/<os>-<arch>/native.properties   target, library, sha256, size, abi, version, ghostty_commit
 * /dev/supermux/terminal/native/<os>-<arch>/<library>          libsupermux_terminal_jni.{so,dylib} / supermux_terminal_jni.dll
 * ```
 * `<os>` is linux / macos / windows, `<arch>` x64 / arm64. The library bytes must match the
 * manifest's sha256 and size, and the manifest's ABI must be the one this binding speaks; the
 * verified bytes are extracted to `<cache>/supermux-terminal/<version>/<sha256>/<library>` (an
 * existing file there is reused only if its hash matches; a bad one is replaced by atomic rename,
 * never rewritten in place, since a mapped library must not change under a running process) and
 * loaded with [System.load]. After
 * loading, `st_abi_version()` must match too.
 *
 * Every failure is a [TerminalEngineUnavailableException] with a [Reason]: UNSUPPORTED_PLATFORM,
 * MISSING_BINARY, CORRUPT_BINARY, ABI_MISMATCH, INITIALIZATION_FAILED.
 *
 * All parameters are test hooks; production uses the defaults.
 */
internal class JvmNativeLoader(
    private val resourceRoot: String = RESOURCE_ROOT,
    private val expectedAbi: Int = NativeStatus.ABI_VERSION,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val osArch: String = System.getProperty("os.arch").orEmpty(),
    private val cacheRoots: List<File> = defaultCacheRoots(),
    private val anchor: Class<*> = JvmNativeLoader::class.java,
    private val systemLoad: (String) -> Unit = { System.load(it) },
) {
    /** `<os>-<arch>` of the packaged library for this JVM. */
    fun platformKey(): String {
        val os = osName.lowercase()
        val o = when {
            os.startsWith("linux") -> "linux"
            os.startsWith("mac") || os.startsWith("darwin") -> "macos"
            os.startsWith("windows") -> "windows"
            else -> null
        }
        val a = when (osArch.lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> null
        }
        if (o == null || a == null) {
            throw TerminalEngineUnavailableException(
                "no supermux terminal engine for $osName/$osArch (supported: linux, macos, windows on x64/arm64)",
                reason = Reason.UNSUPPORTED_PLATFORM,
            )
        }
        return "$o-$a"
    }

    /** Load and verify the library; returns the loaded file. */
    fun load(): File {
        val key = platformKey()
        val dir = "$resourceRoot/$key"
        val props = Properties()
        val propsStream = anchor.getResourceAsStream("$dir/native.properties")
            ?: throw TerminalEngineUnavailableException(
                "no native library packaged for $key (missing resource $dir/native.properties)",
                reason = Reason.MISSING_BINARY,
            )
        propsStream.use { props.load(it) }
        val library = props.getProperty("library")?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
        val sha256 = props.getProperty("sha256")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val size = props.getProperty("size")?.toLongOrNull()
        val version = props.getProperty("version")?.takeIf { it.matches(Regex("[A-Za-z0-9._+-]+")) } ?: "unversioned"
        if (library == null || sha256 == null || size == null) {
            throw TerminalEngineUnavailableException(
                "invalid native manifest $dir/native.properties", reason = Reason.CORRUPT_BINARY,
            )
        }
        val abi = props.getProperty("abi")?.toIntOrNull()
        if (abi != expectedAbi) {
            throw TerminalEngineUnavailableException(
                "packaged $key library implements st_* ABI $abi, this binding needs $expectedAbi",
                reason = Reason.ABI_MISMATCH,
            )
        }
        val bytes = (anchor.getResourceAsStream("$dir/$library")
            ?: throw TerminalEngineUnavailableException(
                "native library $dir/$library is not packaged", reason = Reason.MISSING_BINARY,
            )).use { it.readBytes() }
        val actual = sha256(bytes)
        if (bytes.size.toLong() != size || actual != sha256) {
            throw TerminalEngineUnavailableException(
                "packaged $library does not match its manifest (size ${bytes.size}/$size, sha256 $actual/$sha256)",
                reason = Reason.CORRUPT_BINARY,
            )
        }
        val file = extract(bytes, sha256, version, library)
        try {
            systemLoad(file.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw TerminalEngineUnavailableException(
                "cannot load ${file.absolutePath}: ${e.message}", e, Reason.INITIALIZATION_FAILED,
            )
        }
        NativeTerminal.verifyAbi(expectedAbi, library)
        return file
    }

    private fun extract(bytes: ByteArray, sha256: String, version: String, library: String): File {
        val errors = mutableListOf<String>()
        for (root in cacheRoots) {
            val dir = File(root, "supermux-terminal/$version/$sha256")
            val target = File(dir, library)
            try {
                if (target.isFile && sha256(target.readBytes()) == sha256) return target
                Files.createDirectories(dir.toPath())
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
                // Re-read what landed on disk: another process may have raced us with other bytes.
                if (sha256(target.readBytes()) == sha256) return target
                errors += "$target: hash mismatch after extraction"
            } catch (e: IOException) {
                errors += "$dir: ${e.message}"
            } catch (e: SecurityException) {
                errors += "$dir: ${e.message}"
            }
        }
        throw TerminalEngineUnavailableException(
            "cannot extract $library (${errors.joinToString("; ")})", reason = Reason.INITIALIZATION_FAILED,
        )
    }

    companion object {
        const val RESOURCE_ROOT = "/dev/supermux/terminal/native"

        /**
         * `-Dsupermux.terminal.cacheDir`, else the OS user cache dir (XDG_CACHE_HOME or ~/.cache,
         * ~/Library/Caches, %LOCALAPPDATA%), then java.io.tmpdir as the fallback.
         */
        fun defaultCacheRoots(): List<File> {
            val roots = mutableListOf<File>()
            System.getProperty("supermux.terminal.cacheDir")?.takeIf { it.isNotBlank() }?.let { roots += File(it) }
            val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            val os = System.getProperty("os.name").orEmpty().lowercase()
            when {
                os.startsWith("windows") -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { roots += File(it) }
                os.startsWith("mac") -> home?.let { roots += File(it, "Library/Caches") }
                else -> (System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                    ?: home?.let { File(it, ".cache") })?.let { roots += it }
            }
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { roots += File(it) }
            return roots
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The process-wide library state. The library is loaded once; a failure is remembered for the
 * current [loader] and rethrown on every later [ensureLoaded] (a JVM cannot unload a library and
 * retrying a verification failure does not help).
 */
internal object JvmNativeLibrary {
    /** Test hook: replace to point the factory at other resources / a fake ABI. */
    @Volatile
    var loader: JvmNativeLoader = JvmNativeLoader()

    private var loadedBy: JvmNativeLoader? = null
    private var failure: TerminalEngineUnavailableException? = null

    fun ensureLoaded() {
        synchronized(this) {
            val current = loader
            if (loadedBy === current) {
                failure?.let { throw TerminalEngineUnavailableException(it.message ?: "", it, it.reason) }
                return
            }
            loadedBy = current
            failure = null
            try {
                current.load()
            } catch (e: TerminalEngineUnavailableException) {
                failure = e
                throw e
            } catch (e: Throwable) {
                val wrapped = TerminalEngineUnavailableException(
                    "native engine initialization failed: $e", e, TerminalEngineUnavailableException.Reason.INITIALIZATION_FAILED,
                )
                failure = wrapped
                throw wrapped
            }
        }
    }
}
