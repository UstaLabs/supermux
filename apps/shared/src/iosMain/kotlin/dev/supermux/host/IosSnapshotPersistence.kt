package dev.supermux.host

import kotlinx.cinterop.ExperimentalForeignApi
import dev.supermux.util.toByteArray
import dev.supermux.util.toNSData
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS [SnapshotPersistence] (spec §5): the per-host offline-session cache as one JSON file in
 * Application Support.
 *
 * A FILE rather than `NSUserDefaults`, unlike the host metadata beside it. The cache holds a
 * snapshot of every session on every host and grows with the fleet; `NSUserDefaults` is a plist
 * that is read whole into memory at launch and re-serialised on write, so parking tens of
 * kilobytes of churn there would tax every single defaults read the app makes. Application Support
 * (not Caches) because the OS may evict Caches under pressure, and a display that goes blank
 * because iOS reclaimed the cache would look like a bug.
 *
 * It holds no secrets — last-known session metadata only — so it is deliberately OUTSIDE the
 * Keychain, exactly as on Android. Every failure degrades to an empty cache: the host re-fetches
 * its live snapshot the moment it connects, so a read or write error is never fatal.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSnapshotPersistence : SnapshotPersistence {

    override fun loadAll(): List<HostSnapshot> {
        val path = filePath() ?: return emptyList()
        val data = NSData.dataWithContentsOfFile(path) ?: return emptyList()
        return HostSnapshotCodec.decode(data.toByteArray().decodeToString())
    }

    override fun saveAll(snapshots: List<HostSnapshot>) {
        val path = filePath() ?: return
        HostSnapshotCodec.encode(snapshots).encodeToByteArray().toNSData()
            .writeToFile(path, atomically = true)
    }

    /** `<Application Support>/supermux/host-snapshots.json`, creating the directory once. */
    private fun filePath(): String? {
        val base = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory.toULong(),
            NSUserDomainMask.toULong(),
            true,
        ).firstOrNull() as? String ?: return null
        val dir = "$base/supermux"
        // Application Support is NOT created for you the way Documents is — a fresh install has no
        // such directory, and writing into it would silently fail on first launch.
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return "$dir/host-snapshots.json"
    }
}
