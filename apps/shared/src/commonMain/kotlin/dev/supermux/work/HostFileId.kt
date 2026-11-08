package dev.supermux.work

/**
 * The id a host file (#95: Files "Save and open…") is kept under in the media store. It names one version of the
 * file — its [path], [mtime] and [size] — so an unchanged file is found again instead of fetched twice, and a changed
 * one never stands in for it. 32 lowercase hex, the shape of a broker file id, which is all `MediaKey` accepts.
 *
 * Two 64-bit FNV-1a passes with different offset bases: a name, not a secret — the store is this device's own.
 */
internal fun hostFileId(path: String, mtime: Long, size: Long): String {
    val bytes = "$path|$mtime|$size".encodeToByteArray()
    return hex(fnv1a(bytes, FNV_OFFSET_BASIS)) + hex(fnv1a(bytes, SECOND_OFFSET_BASIS))
}

/**
 * The folder a finished download is staged in to be handed to another app under its REAL name (#95):
 * `<staging>/<this>/<name>`. The media store keeps the file under an id of its own, which would arrive as
 * "3f9c4d….bin"; this folder, 16 lowercase hex digits of a hash of [storedPath] (where the file is stored), is the
 * same for one file and apart for any two, so two different `report.pdf`s never overwrite each other while an app
 * reads one. A plain path segment on every file system: nothing to escape.
 */
fun stagingFolderName(storedPath: String): String = hex(fnv1a(storedPath.encodeToByteArray(), FNV_OFFSET_BASIS))

private fun fnv1a(bytes: ByteArray, basis: ULong): ULong {
    var hash = basis
    for (b in bytes) hash = (hash xor b.toUByte().toULong()) * FNV_PRIME
    return hash
}

private fun hex(v: ULong): String = v.toString(16).padStart(16, '0')

private const val FNV_PRIME: ULong = 0x100000001b3uL
private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL

// Any other basis makes the second half independent of the first: the standard one with its halves swapped.
private const val SECOND_OFFSET_BASIS: ULong = 0x84222325cbf29ce4uL
