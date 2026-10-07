package dev.supermux.terminal

import dev.supermux.terminal.TerminalEngineUnavailableException.Reason
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
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
 * never rewritten in place, since a mapped library must not change under a running process), the
 * file's sha256 is checked once more immediately before [System.load], and after loading
 * `st_abi_version()` must match too.
 *
 * Cache directory safety: every directory of the cache path is created owner-only (POSIX 0700
 * where the file system supports it) and an existing one is used only if it is a real directory
 * (not a symlink) owned by the current user and not group/world-writable. Otherwise the loader
 * falls back to a FRESH private directory (`Files.createTempDirectory`, 0700) under the same
 * root, then to the next root (user cache first, `java.io.tmpdir` last), so a pre-created or
 * loosened `/tmp/supermux-terminal` from another user is never used. Residual risk, accepted:
 * the re-hash and `System.load` are two steps, so a process running as the SAME user could swap
 * the file in between (it can equally attach to or ptrace the JVM); nobody else can write into an
 * owner-only directory. On Windows only ownership is checked (%LOCALAPPDATA% is per-user).
 *
 * Developer override: `-Dsupermux.terminal.nativeLibrary=<path>` loads that file instead of the
 * packaged one (no manifest/hash check, ABI still checked); Gradle's jvmTest uses it to load the
 * test-hook build of the JNI library.
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
    private val libraryOverride: String? = System.getProperty(LIBRARY_PROPERTY)?.takeIf { it.isNotBlank() },
    /** Runs between extraction and the pre-load hash check (tests tamper here). */
    private val afterExtract: (File) -> Unit = {},
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
        libraryOverride?.let { return loadOverride(File(it)) }
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
        afterExtract(file)
        // Last check right before the load (see "Residual risk" above).
        val onDisk = try {
            sha256(file.readBytes())
        } catch (e: IOException) {
            throw TerminalEngineUnavailableException("cannot re-read $file: ${e.message}", e, Reason.INITIALIZATION_FAILED)
        }
        if (onDisk != sha256) {
            throw TerminalEngineUnavailableException(
                "$file changed between extraction and loading (sha256 $onDisk, expected $sha256)",
                reason = Reason.CORRUPT_BINARY,
            )
        }
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

    private fun loadOverride(file: File): File {
        // Loud on purpose: this bypasses the manifest sha256/size check that every packaged load
        // goes through, so a build that honours it must say so (it is a developer/CI hook only).
        System.getLogger(JvmNativeLoader::class.java.name).log(
            System.Logger.Level.WARNING,
            "terminal-core: loading the native engine from -D$LIBRARY_PROPERTY=${'$'}{file.absolutePath} " +
                "instead of the packaged library; its bytes are NOT verified against a manifest (ABI still checked)",
        )
        if (!file.isFile) {
            throw TerminalEngineUnavailableException(
                "-D$LIBRARY_PROPERTY=$file does not exist", reason = Reason.MISSING_BINARY,
            )
        }
        try {
            systemLoad(file.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw TerminalEngineUnavailableException(
                "cannot load ${file.absolutePath}: ${e.message}", e, Reason.INITIALIZATION_FAILED,
            )
        }
        NativeTerminal.verifyAbi(expectedAbi, file.name)
        return file
    }

    private fun extract(bytes: ByteArray, sha256: String, version: String, library: String): File {
        val errors = mutableListOf<String>()
        for (root in cacheRoots) {
            try {
                Files.createDirectories(root.toPath())
                val me = currentUser(root.toPath())
                val base = privateBase(root, me)
                val dir = privateSubdir(privateSubdir(base, version, me), sha256, me)
                val target = File(dir, library)
                val path = target.toPath()
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && sha256(target.readBytes()) == sha256) return target
                val tmp = Files.createTempFile(dir.toPath(), "$library.", ".tmp", *privateFileAttrs())
                try {
                    Files.write(tmp, bytes)
                    try {
                        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(tmp)
                }
                if (sha256(target.readBytes()) == sha256) return target
                errors += "$target: hash mismatch after extraction"
            } catch (e: IOException) {
                errors += "$root: ${e.message}"
            } catch (e: SecurityException) {
                errors += "$root: ${e.message}"
            }
        }
        throw TerminalEngineUnavailableException(
            "cannot extract $library (${errors.joinToString("; ")})", reason = Reason.INITIALIZATION_FAILED,
        )
    }

    /**
     * `<root>/supermux-terminal` if it is (or can be created as) a private directory, else a fresh
     * private `<root>/supermux-terminal-*` directory. Throws [IOException] if neither works.
     */
    private fun privateBase(root: File, me: UserPrincipal): File {
        val preferred = File(root, BASE_DIR).toPath()
        if (!Files.exists(preferred, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(preferred, *privateDirAttrs())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // raced with another process; checked below like any existing directory
            }
        }
        if (isPrivateDir(preferred, me)) return preferred.toFile()
        val fresh = Files.createTempDirectory(root.toPath(), "$BASE_DIR-", *privateDirAttrs())
        if (!isPrivateDir(fresh, me)) throw IOException("cannot create a private directory under $root")
        return fresh.toFile()
    }

    /** `<parent>/<name>`, created owner-only; must be private (its parent is). */
    private fun privateSubdir(parent: File, name: String, me: UserPrincipal): File {
        val dir = File(parent, name).toPath()
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(dir, *privateDirAttrs())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
            }
        }
        if (!isPrivateDir(dir, me)) throw IOException("$dir is not a private directory")
        return dir.toFile()
    }

    companion object {
        const val RESOURCE_ROOT = "/dev/supermux/terminal/native"
        const val LIBRARY_PROPERTY = "supermux.terminal.nativeLibrary"
        const val BASE_DIR = "supermux-terminal"

        private val posix: Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
        private val OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------")
        private val OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------")

        private fun privateDirAttrs(): Array<FileAttribute<*>> =
            if (posix) arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR)) else emptyArray()

        private fun privateFileAttrs(): Array<FileAttribute<*>> =
            if (posix) arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)) else emptyArray()

        /** The user this JVM runs as = the owner of a file it just created in [dir]. */
        internal fun currentUser(dir: Path): UserPrincipal {
            val probe = Files.createTempFile(dir, ".owner-probe", ".tmp")
            try {
                return Files.getOwner(probe)
            } finally {
                Files.deleteIfExists(probe)
            }
        }

        /** A real directory (no symlink), owned by [me], not group- or world-writable. */
        internal fun isPrivateDir(dir: Path, me: UserPrincipal): Boolean {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false
            if (Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS) != me) return false
            if (!posix) return true
            val perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
            return PosixFilePermission.GROUP_WRITE !in perms && PosixFilePermission.OTHERS_WRITE !in perms
        }

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
