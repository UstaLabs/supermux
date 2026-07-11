package dev.supermux.desktop.host

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Resolves the host helper binaries the desktop-as-host needs — the bundled Bun **broker**, a
 * static **tmux** (Linux/macOS), and **frpc** (all platforms) — for BOTH a dev checkout and a
 * packaged app image, then materializes them to a per-user dir with exec perms on first use
 * (Plan 3 Task 5 / spec §6, D7, D11).
 *
 * ### The broker: one binary, the canonical compile path
 * The bundled broker is the SAME `supermux` single-file binary [scripts/build-binary.sh] compiles
 * from `src/cli.ts` (which boots `src/main.ts` when run with no subcommand). We reuse that one
 * `bun build --compile` artifact — named `supermux-broker` inside the app image — instead of a
 * separate `bun build --compile src/main.ts` so the desktop host runs the exact release-smoked
 * broker (PWA + pty-helper embedded), with no second compile path to drift. The desktop spawns it
 * with `MUX_WEB_PORT` (see [BrokerSidecar.buildSpawnEnv]); with no args it is the broker.
 *
 * ### tmux + frpc: bare-name execs off an augmented PATH
 * The broker execs bare `tmux` / `frpc` (see `src/core/session-manager/tmux.ts`,
 * `src/core/relay/frp-provider.ts`). So rather than teach the broker new paths, the desktop
 * materializes the bundled `tmux`/`frpc` into ONE bin dir and prepends it to the spawned broker's
 * `PATH` ([prependPath]); the broker then finds them with no change. Windows ships only `frpc`
 * (client-only for now — it can't host, but carries frpc for when it later does); it bundles no
 * broker and no tmux.
 *
 * ### Dev vs packaged
 * Packaged apps expose their resources via Compose Desktop's `compose.application.resources.dir`
 * system property (jpackage passes `-Dcompose.application.resources.dir=<image>/resources`); that
 * dir is the merged `appResourcesRootDir` `common/ <os>/ <os>-<arch>/` subtree, so a lookup is
 * just `<resourcesDir>/<fileName>`. In a dev checkout the property is absent → tmux/frpc resolve
 * off `$PATH` and the broker path is null (the sidecar then runs `bun <repo>/src/main.ts`).
 *
 * ### Materialization (mirrors `src/core/runtime-assets.ts`)
 * A bundled binary inside an installed image may be read-only or lose its exec bit through
 * `.deb`/`.msi` packaging, so it is copied out to `<stateDir>/desktop-assets/bin/<name>` and
 * chmod'd `0755` — atomically (tmp write → move) and sig-keyed (`size:mtime`), so an app update
 * shipping a fresh binary re-materializes rather than serving a stale copy, exactly like the
 * broker's own `materializeAsset`. The path/name/policy helpers are pure and unit-tested
 * ([HostBinariesTest]); the copy is exercised by a temp-dir round-trip there and end-to-end by
 * the sidecar smoke pointed at a real bundled broker.
 */
object HostBinaries {

    /** System property Compose Desktop sets in a packaged/jpackaged app image (absent in dev). */
    const val RESOURCES_PROP = "compose.application.resources.dir"

    /** Where materialized execs live under the sidecar state dir (`~/.mux/state`). */
    const val BIN_SUBDIR = "desktop-assets/bin"

    private const val STAMP_SUFFIX = ".stamp"

    enum class Os { LINUX, MAC, WINDOWS, OTHER }

    enum class Binary { Broker, Tmux, Frpc }

    /** The materialized host binaries handed to the sidecar. Nulls = not bundled / not found. */
    data class SidecarBinaries(
        val brokerPath: Path?, // packaged: the bundled broker exec; dev: null (→ bun src/main.ts)
        val binDir: Path?,     // the dir to prepend to the broker's PATH (holds tmux + frpc), or null
        val frpcPath: Path?,   // resolved frpc (feeds the broker's relay provider via PATH)
        val tmuxPath: Path?,   // resolved tmux (Linux/macOS)
    )

    // ── pure policy / naming ──────────────────────────────────────────────────────────

    fun detectOs(osName: String? = System.getProperty("os.name")): Os {
        val n = osName?.lowercase() ?: return Os.OTHER
        return when {
            n.contains("mac") || n.contains("darwin") -> Os.MAC
            n.contains("win") -> Os.WINDOWS
            n.contains("nux") || n.contains("nix") || n.contains("aix") -> Os.LINUX
            else -> Os.OTHER
        }
    }

    /** File name of [binary] inside the resources / bin dir. `.exe` on Windows for the execs. */
    fun fileName(binary: Binary, os: Os): String = when (binary) {
        Binary.Broker -> "supermux-broker" + exeSuffix(os)
        Binary.Frpc -> "frpc" + exeSuffix(os)
        Binary.Tmux -> "tmux" // Linux/macOS only; never suffixed
    }

    /** Whether [os]'s shipped app image carries [binary] (Windows is client-only: frpc only). */
    fun isBundled(binary: Binary, os: Os): Boolean = when (os) {
        Os.LINUX, Os.MAC -> true
        Os.WINDOWS -> binary == Binary.Frpc
        Os.OTHER -> false
    }

    private fun exeSuffix(os: Os) = if (os == Os.WINDOWS) ".exe" else ""

    /** Packaged app resources dir from the system property, or null in a dev checkout. */
    fun resourcesDir(prop: String? = System.getProperty(RESOURCES_PROP)): Path? =
        prop?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }

    fun isPackaged(): Boolean = resourcesDir() != null

    /** `<binDir><sep><existing PATH>` so bundled tmux/frpc win over any system copies. */
    fun prependPath(binDir: Path, existing: String? = System.getenv("PATH")): String =
        if (existing.isNullOrEmpty()) binDir.toString() else binDir.toString() + File.pathSeparator + existing

    // ── resolution ────────────────────────────────────────────────────────────────────

    /**
     * Resolve the host binaries for this run. Packaged ⇒ materialize each shipped binary into one
     * per-user bin dir; dev ⇒ broker is null (repo+bun) and tmux/frpc come off `$PATH`. Never
     * throws — a slot the packager did not fill (or a copy failure) resolves to null.
     */
    fun resolve(
        stateDir: Path,
        os: Os = detectOs(),
        resourcesDir: Path? = resourcesDir(),
        onPath: (String) -> Path? = ::whichOnPath,
    ): SidecarBinaries {
        if (resourcesDir == null) {
            // DEV: the broker runs from the repo via bun; helpers come off PATH.
            return SidecarBinaries(
                brokerPath = null,
                binDir = null,
                frpcPath = if (isBundled(Binary.Frpc, os)) onPath("frpc") else null,
                tmuxPath = if (isBundled(Binary.Tmux, os)) onPath("tmux") else null,
            )
        }
        val binDir = stateDir.resolve(BIN_SUBDIR)
        fun mat(b: Binary): Path? {
            if (!isBundled(b, os)) return null
            val src = resourcesDir.resolve(fileName(b, os))
            if (!Files.exists(src)) return null
            return runCatching { materialize(src, binDir, fileName(b, os), executable = true) }.getOrNull()
        }
        val broker = mat(Binary.Broker)
        val frpc = mat(Binary.Frpc)
        val tmux = mat(Binary.Tmux)
        return SidecarBinaries(
            brokerPath = broker,
            binDir = if (broker != null || frpc != null || tmux != null) binDir else null,
            frpcPath = frpc,
            tmuxPath = tmux,
        )
    }

    /** First executable named [name] on `$PATH`, or null. (Dev helper lookup; POSIX hosts.) */
    fun whichOnPath(name: String): Path? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val p = runCatching { Path.of(dir).resolve(name) }.getOrNull() ?: continue
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p
        }
        return null
    }

    // ── materialization (mirrors runtime-assets.ts) ─────────────────────────────────────

    /**
     * Copy [source] to `<destDir>/<name>` with exec perms, atomically and sig-keyed. A prior copy
     * from the identical source (matching `size:mtime` stamp) is reused; a changed source (app
     * update) re-materializes. Never leaves a partial file the fast-path would then trust.
     */
    fun materialize(source: Path, destDir: Path, name: String, executable: Boolean): Path {
        val dest = destDir.resolve(name)
        val stamp = destDir.resolve(name + STAMP_SUFFIX)
        val sig = signature(source)
        if (Files.exists(dest) && runCatching { Files.readString(stamp) }.getOrNull() == sig) return dest

        Files.createDirectories(destDir)
        val tmp = destDir.resolve("$name.tmp.${ProcessHandle.current().pid()}")
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
            if (executable) makeExecutable(tmp)
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching { Files.writeString(stamp, sig) }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
        return dest
    }

    private fun signature(source: Path): String {
        val size = runCatching { Files.size(source) }.getOrDefault(-1L)
        val mtime = runCatching { Files.getLastModifiedTime(source).toMillis() }.getOrDefault(-1L)
        return "$size:$mtime"
    }

    private fun makeExecutable(p: Path) {
        runCatching {
            if (Files.getFileAttributeView(p, PosixFileAttributeView::class.java) != null) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"))
            } else {
                p.toFile().setExecutable(true, false) // non-POSIX (Windows) FS
            }
        }
    }
}
