package dev.supermux.desktop.host

import dev.supermux.host.HostSnapshot
import dev.supermux.host.HostSnapshotCodec
import dev.supermux.host.SnapshotPersistence
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop's offline-snapshot cache (spec §5; card #54): one JSON file beside the fleet files,
 * so the next launch draws the sidebar's sessions, workspaces and projects before its socket opens.
 * Holds no secrets. A missing, unreadable or foreign-version file is an empty cache.
 */
class DesktopSnapshotPersistence(private val file: Path) : SnapshotPersistence {
    override fun loadAll(): List<HostSnapshot> =
        runCatching { if (Files.exists(file)) HostSnapshotCodec.decode(Files.readString(file)) else emptyList() }
            .getOrDefault(emptyList())

    override fun saveAll(snapshots: List<HostSnapshot>) {
        runCatching { writeOwnerOnly(file, HostSnapshotCodec.encode(snapshots)) }
            .onFailure { println("[DesktopSnapshotPersistence] save failed: $it") }
    }

    companion object {
        const val FILE = "host-snapshots.json"
    }
}
