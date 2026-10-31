package dev.supermux.desktop.host

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Resolves the host helper binaries the desktop-as-host needs — the bundled Bun **broker**, a
 * static **tmux** (Linux/macOS) or **sessiond** (Windows) and the **zmx bundle** (Linux/macOS) — for BOTH a dev checkout and a packaged app image, then
 * materializes them to a per-user dir on first use (spec §6, D7, D11).
 *
 * ### The broker: one binary, the canonical compile path
 * The bundled broker is the SAME `supermux` single-file binary [scripts/build-binary.sh] compiles
 * from `src/cli.ts` (which boots `src/main.ts` when run with no subcommand). We reuse that one
 * `bun build --compile` artifact — named `supermux-broker` inside the app image — instead of a
 * separate `bun build --compile src/main.ts` so the desktop host runs the exact release-smoked
 * broker (PWA + pty-helper embedded), with no second compile path to drift. The desktop spawns it
 * with `MUX_WEB_PORT` (see [brokerEnv]); with no args it is the broker.
 *
 * ### zmx: an owned directory, never a PATH lookup
 * Workspace terminals run on a PINNED, PATCHED zmx (Plan 4): the broker verifies the daemon and
 * its framed helper against the manifest built beside them before it execs either. A bare-name
 * exec would defeat that — whatever `zmx` a user has installed would be found first, and the
 * patch supermux depends on (an explicit restore boundary, broker-chosen size ownership) is not
 * in it. So the three staged files are materialized back into the layout the broker verifies —
 * `<stateDir>/desktop-assets/zmx/{bin/zmx, bin/mux-zmx-helper, manifest.json}` — and that
 * DIRECTORY is handed over explicitly as `MUX_ZMX_BIN_DIR`. Windows stages none of it and keeps
 * sessiond; its broker never looks for a zmx bundle.
 *
 * ### tmux: a bare-name exec off an augmented PATH
 * The broker execs bare `tmux` (see `src/core/session-manager/tmux.ts`). So rather than teach the
 * broker a new path, the desktop materializes the bundled `tmux` into ONE bin dir and prepends it to
 * the spawned broker's `PATH` ([prependPath]); the broker then finds it with no change. Windows ships
 * the broker and sessiond; `MUX_SESSIOND_PATH` points the broker at the materialized sessiond executable.
 *
 * ### No frpc
 * The relay tunnel runs inside the broker (`@supermux/frp-client`). Earlier builds shipped and
 * materialized an `frpc`, which Windows Defender quarantines as `PUA:Win32/FRProxy`; [resolve]
 * deletes such a leftover copy ([STALE_HELPERS]).
 *
 * ### Dev vs packaged
 * Packaged apps expose their resources via Compose Desktop's `compose.application.resources.dir`
 * system property (jpackage passes `-Dcompose.application.resources.dir=<image>/resources`); that
 * dir is the merged `appResourcesRootDir` `common/ <os>/ <os>-<arch>/` subtree, so a lookup is
 * just `<resourcesDir>/<fileName>`. In a dev checkout the property is absent → tmux resolves
 * off `$PATH` and the broker path is null (the supervisor then runs `bun <repo>/src/main.ts`).
 *
 * ### Materialization (mirrors `src/core/runtime-assets.ts`)
 * A bundled binary inside an installed image may be read-only or lose its exec bit through
 * `.deb`/`.msi` packaging, so it is copied out to `<stateDir>/desktop-assets/bin/<name>` and
 * chmod'd `0755` — atomically (tmp write → move) and sig-keyed (`size:mtime`), so an app update
 * shipping a fresh binary re-materializes rather than serving a stale copy, exactly like the
 * broker's own `materializeAsset`. The path/name/policy helpers are pure and unit-tested
 * ([HostBinariesTest]); the copy is exercised by a temp-dir round-trip there and end-to-end by
 * a packaged app running the real bundled broker.
 *
 * ### macOS: our copies drop the download quarantine
 * The copy keeps the extended attributes of the app it came out of, so a Safari-downloaded app's
 * `com.apple.quarantine` lands on `desktop-assets/bin/supermux-broker` too — and Gatekeeper then
 * asks a SECOND time ("“supermux-broker” is an app downloaded from the Internet") the first time
 * launchd or the app runs it. The user already approved the app it came from, so after every
 * resolve the attribute is removed from OUR materialized copies — and only those
 * ([quarantineClearArgv]). The copy-out location itself is deliberate and stays: launchd runs it
 * after the app quits or updates, and TCC grants are keyed by that version-stable path.
 */
object HostBinaries {

    /** System property Compose Desktop sets in a packaged/jpackaged app image (absent in dev). */
    const val RESOURCES_PROP = "compose.application.resources.dir"

    /** Where materialized execs live under the broker's state dir (`~/.mux/state`). */
    const val BIN_SUBDIR = "desktop-assets/bin"

    /** The zmx bundle's own root, kept OUT of [BIN_SUBDIR] so nothing in it is ever on PATH. */
    const val ZMX_SUBDIR = "desktop-assets/zmx"

    private const val STAMP_SUFFIX = ".stamp"

    /** Helpers earlier builds materialized into [BIN_SUBDIR] and nothing runs any more. */
    val STALE_HELPERS = listOf("frpc", "frpc.exe")

    enum class Os { LINUX, MAC, WINDOWS, OTHER }

    enum class Binary { Broker, Sessiond, Tmux, Zmx, ZmxHelper, ZmxManifest }

    /** The materialized host binaries the supervisor runs. Nulls = not bundled / not found. */
    data class SidecarBinaries(
        val brokerPath: Path?, // packaged: the bundled broker exec; dev: null (→ bun src/main.ts)
        val binDir: Path?,     // the dir to prepend to the broker's PATH (holds platform helpers), or null
        val sessiondPath: Path?, // Windows session runtime helper
        val tmuxPath: Path?,   // resolved tmux (Linux/macOS)
        // The verified workspace-terminal bundle's root (→ MUX_ZMX_BIN_DIR), or null when this
        // platform/build has none. Never on PATH — see the class doc.
        val zmxDir: Path? = null,
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
        Binary.Sessiond -> "mux-sessiond" + exeSuffix(os)
        Binary.Tmux -> "tmux" // Linux/macOS only; never suffixed
        // Staged FLAT by scripts/stage-desktop-binaries.sh (the app resources dir is a merge of
        // common/<os>/<os>-<arch>, so a nested tree there is not worth the risk); the bundle's
        // real shape is rebuilt at materialization time by [materializeZmxBundle].
        Binary.Zmx -> "zmx"
        Binary.ZmxHelper -> "mux-zmx-helper"
        Binary.ZmxManifest -> "zmx-manifest.json"
    }

    /** Whether [os]'s shipped app image carries [binary]. */
    fun isBundled(binary: Binary, os: Os): Boolean = when (os) {
        Os.LINUX, Os.MAC ->
            binary == Binary.Broker || binary == Binary.Tmux ||
                binary == Binary.Zmx || binary == Binary.ZmxHelper || binary == Binary.ZmxManifest
        // No zmx on Windows: persistent terminals there are sessiond's, and a zmx bundle in the
        // MSI would be a second backend claiming the same workspaces.
        Os.WINDOWS -> binary == Binary.Broker || binary == Binary.Sessiond
        Os.OTHER -> false
    }

    private fun exeSuffix(os: Os) = if (os == Os.WINDOWS) ".exe" else ""

    /** Packaged app resources dir from the system property, or null in a dev checkout. */
    fun resourcesDir(prop: String? = System.getProperty(RESOURCES_PROP)): Path? =
        prop?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }

    fun isPackaged(): Boolean = resourcesDir() != null

    /** `<binDir><sep><existing PATH>` so the bundled tmux wins over any system copy. */
    fun prependPath(binDir: Path, existing: String? = System.getenv("PATH")): String =
        if (existing.isNullOrEmpty()) binDir.toString() else binDir.toString() + File.pathSeparator + existing

    // ── resolution ────────────────────────────────────────────────────────────────────

    /**
     * Resolve the host binaries for this run. Packaged ⇒ materialize each shipped binary into one
     * per-user bin dir; dev ⇒ broker is null (repo+bun) and tmux comes off `$PATH`. Never
     * throws — a slot the packager did not fill (or a copy failure) resolves to null.
     */
    fun resolve(
        stateDir: Path,
        os: Os = detectOs(),
        resourcesDir: Path? = resourcesDir(),
        onPath: (String) -> Path? = ::whichOnPath,
        clearQuarantine: (List<String>) -> Unit = ::runQuietly,
    ): SidecarBinaries {
        if (resourcesDir == null) {
            // DEV: the broker runs from the repo via bun; helpers come off PATH. zmx is
            // deliberately absent here — the dev broker finds the repo's own build/zmx/out, which
            // is the bundle the developer just built, and an env var pointing elsewhere would
            // quietly override it.
            return SidecarBinaries(
                brokerPath = null,
                binDir = null,
                sessiondPath = if (isBundled(Binary.Sessiond, os)) onPath(fileName(Binary.Sessiond, os)) else null,
                tmuxPath = if (isBundled(Binary.Tmux, os)) onPath("tmux") else null,
                zmxDir = null,
            )
        }
        val binDir = stateDir.resolve(BIN_SUBDIR)
        for (name in STALE_HELPERS) {
            runCatching { Files.deleteIfExists(binDir.resolve(name)) }
            runCatching { Files.deleteIfExists(binDir.resolve(name + STAMP_SUFFIX)) }
        }
        fun mat(b: Binary): Path? {
            if (!isBundled(b, os)) return null
            val src = resourcesDir.resolve(fileName(b, os))
            if (!Files.exists(src)) return null
            return runCatching { materialize(src, binDir, fileName(b, os), executable = true) }.getOrNull()
        }
        val broker = mat(Binary.Broker)
        val sessiond = mat(Binary.Sessiond)
        val tmux = mat(Binary.Tmux)
        val zmx = materializeZmxBundle(stateDir, os, resourcesDir)
        // Every run, not just after a fresh copy: an install from before this fix heals too.
        quarantineClearArgv(os, stateDir, listOfNotNull(broker, sessiond, tmux, zmx))
            ?.let { argv -> runCatching { clearQuarantine(argv) } }
        return SidecarBinaries(
            brokerPath = broker,
            binDir = if (broker != null || sessiond != null || tmux != null) binDir else null,
            sessiondPath = sessiond,
            tmuxPath = tmux,
            zmxDir = zmx,
        )
    }

    /**
     * macOS: the `xattr` call that strips the download quarantine from [paths] (files, and the zmx
     * bundle dir, recursively), or null when there is nothing to do. Anything not under
     * `<stateDir>/desktop-assets` is dropped: only our own copies are ever touched, never the app
     * or a user's file. `-r` also makes a copy that has no quarantine a silent no-op (exit 0).
     */
    fun quarantineClearArgv(os: Os, stateDir: Path, paths: List<Path>): List<String>? {
        if (os != Os.MAC) return null
        val root = stateDir.toAbsolutePath().normalize().resolve(ASSETS_SUBDIR)
        val ours = paths.map { it.toAbsolutePath().normalize() }.filter { it.startsWith(root) && it != root }.distinct()
        if (ours.isEmpty()) return null
        return listOf("/usr/bin/xattr", "-dr", QUARANTINE_XATTR) + ours.map { it.toString() }
    }

    private const val ASSETS_SUBDIR = "desktop-assets"
    private const val QUARANTINE_XATTR = "com.apple.quarantine"

    /** Best-effort [argv]: never throws, output discarded, killed after 10 s. */
    private fun runQuietly(argv: List<String>) {
        runCatching {
            val p = ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /**
     * Rebuild the staged zmx bundle into the layout the broker verifies, and return its root.
     *
     * ALL THREE FILES OR NONE. The broker hashes the two binaries against the manifest sitting
     * beside them, so a bundle missing one piece is not a degraded bundle — it is a manifest that
     * describes something that is not there, and the honest answer is "this app image has no zmx"
     * rather than a hash mismatch at the first attach. The manifest is written LAST for the same
     * reason: a crash midway leaves a directory that reads as absent, never as present-and-lying.
     *
     * Returns null when this platform ships no bundle, when the packager filled none of the slots,
     * or when the copy fails — the broker then reports `backend-unavailable` for workspace
     * terminals, which is exactly what is true.
     */
    fun materializeZmxBundle(stateDir: Path, os: Os, resourcesDir: Path?): Path? {
        if (resourcesDir == null || !isBundled(Binary.Zmx, os)) return null
        val pieces = listOf(Binary.Zmx, Binary.ZmxHelper, Binary.ZmxManifest)
            .map { it to resourcesDir.resolve(fileName(it, os)) }
        if (pieces.any { (_, src) -> !Files.exists(src) }) return null
        val root = stateDir.resolve(ZMX_SUBDIR)
        return runCatching {
            val bin = root.resolve("bin")
            materialize(pieces[0].second, bin, "zmx", executable = true)
            materialize(pieces[1].second, bin, "mux-zmx-helper", executable = true)
            materialize(pieces[2].second, root, "manifest.json", executable = false)
            root
        }.getOrNull()
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
    fun materialize(
        source: Path,
        destDir: Path,
        name: String,
        executable: Boolean,
        replace: (tmp: Path, dest: Path) -> Unit = ::replaceFile,
    ): Path {
        val dest = destDir.resolve(name)
        val stamp = destDir.resolve(name + STAMP_SUFFIX)
        val sig = signature(source)
        // Copies set aside by an earlier update (below) whose process has exited by now.
        sweepAside(destDir, name)
        if (Files.exists(dest) && runCatching { Files.readString(stamp) }.getOrNull() == sig) return dest

        Files.createDirectories(destDir)
        val tmp = destDir.resolve("$name.tmp.${ProcessHandle.current().pid()}")
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
            if (executable) makeExecutable(tmp)
            try {
                replace(tmp, dest)
            } catch (e: Exception) {
                // Windows can't overwrite an .exe something is running (the agents' MCP shims are
                // `supermux-broker.exe shim`, a live sessiond is mux-sessiond.exe), but it can RENAME
                // it: set the running copy aside, then put the new one in its place. Running
                // processes keep their image; the aside copy is deleted once nothing uses it.
                if (!Files.exists(dest)) throw e
                Files.move(dest, destDir.resolve("$name$ASIDE_INFIX${System.currentTimeMillis()}"))
                replace(tmp, dest)
            }
            runCatching { Files.writeString(stamp, sig) }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
        return dest
    }

    private const val ASIDE_INFIX = ".old-"

    private fun replaceFile(tmp: Path, dest: Path) {
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Best-effort: delete `<name>.old-*` copies; one still running (Windows) just stays until next time. */
    private fun sweepAside(destDir: Path, name: String) {
        if (!Files.isDirectory(destDir)) return
        runCatching {
            Files.newDirectoryStream(destDir, "$name$ASIDE_INFIX*").use { dir -> dir.forEach { runCatching { Files.deleteIfExists(it) } } }
        }
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
