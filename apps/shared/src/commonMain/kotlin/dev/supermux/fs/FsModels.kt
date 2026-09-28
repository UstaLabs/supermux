// Wire models of the host file-system service (spec docs/superpowers/specs/2026-09-27-host-filesystem-service-design.md).
package dev.supermux.fs

import dev.supermux.net.FsEntry
import kotlinx.serialization.Serializable

@Serializable
data class FsTruncated(val total: Int)

@Serializable
data class DirSnapshot(
    val path: String,
    val real: String = path,
    val version: String,
    val entries: List<FsEntry> = emptyList(),
    val truncated: FsTruncated? = null,
)

@Serializable
data class FsStat(
    val name: String,
    val type: String,
    val size: Long? = null,
    val mtime: Long? = null,
    val ignored: Boolean = false,
    val git: String? = null,
    val target: String? = null,
    val real: String,
)

@Serializable
data class SearchHit(
    val path: String,
    val name: String,
    val type: String,
    val score: Double,
    val hits: List<Int> = emptyList(),
)

@Serializable
data class FsWriteResult(val size: Long, val mtime: Long)

/**
 * POST /fs/ops body. `to` only for rename/move. `permanent` only for delete: skip the trash (the
 * broker refuses a trash move across filesystems with 409 EXDEV; the UI then asks for this).
 */
@Serializable
data class FsOpRequest(val op: String, val path: String, val to: String? = null, val permanent: Boolean? = null)
