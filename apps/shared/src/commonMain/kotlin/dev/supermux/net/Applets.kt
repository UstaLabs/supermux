// #117: applets as a device sees them (GET /applets). An applet is a personal web tool supermux runs on the host, at
// /a/<name>/; it is not a session and not a project. The broker owns the list; every device of a host shows the same
// one, and the same pins.
package dev.supermux.net

import kotlinx.serialization.Serializable

@Serializable
data class AppletDto(
    /** Stable; survives a rename. */
    val id: String,
    /** The folder and the route segment. */
    val name: String,
    val title: String = "",
    /** The applet's page, relative to the host ("/a/<name>/"). */
    val url: String = "",
    /** Its icon, relative to the host, or null: the app draws a lettered tile. */
    val iconUrl: String? = null,
    /** ok · invalid (its applet.json can't be used) · missing (its folder is gone). */
    val status: String = "ok",
    /** static · server */
    val kind: String = "static",
    val error: String? = null,
    /** Secrets it asks for that have no value or have expired: the "Needs you" of its menu and page. */
    val needsYou: List<AppletNeedDto> = emptyList(),
    /** Its place in the sidebar grid, or null when it isn't pinned. */
    val pin: Int? = null,
    val process: AppletProcessDto? = null,
    val updatedBy: String? = null,
    val updatedAt: String = "",
    val createdAt: String = "",
)

@Serializable
data class AppletNeedDto(val secret: String, val state: String)

@Serializable
data class AppletProcessDto(
    /** starting · running · crashed · stopped */
    val state: String = "stopped",
    val restarts: Int = 0,
    val startedAt: String? = null,
    val error: String? = null,
)

/** The applets the sidebar grid shows, in order: the pinned ones by their place. */
fun List<AppletDto>.pinned(): List<AppletDto> = filter { it.pin != null }.sortedBy { it.pin }

/** How many applets the sidebar grid holds (desktop 3×3); a phone shows the first [PHONE_PINS]. */
const val MAX_APPLET_PINS = 9
const val PHONE_PINS = 8

/** GET /applets/<name>: everything Applet info shows. */
@Serializable
data class AppletDetailDto(
    val id: String,
    val name: String,
    val title: String = "",
    val url: String = "",
    val iconUrl: String? = null,
    val status: String = "ok",
    val kind: String = "static",
    val stopped: Boolean = false,
    val error: String? = null,
    val needsYou: List<AppletNeedDto> = emptyList(),
    val pin: Int? = null,
    val process: AppletProcessDto? = null,
    val secrets: List<AppletSecretDto> = emptyList(),
    val jobs: List<AppletJobDto> = emptyList(),
    val settings: List<AppletSettingDto> = emptyList(),
    val backups: List<AppletBackupDto> = emptyList(),
    val data: AppletDataDto? = null,
    /** Its start command, from applet.json; null for a static applet. */
    val run: String? = null,
    /** Its folder on the host. */
    val folder: String = "",
    val updatedBy: String? = null,
    val updatedAt: String = "",
    val createdAt: String = "",
)

@Serializable
data class AppletSecretDto(
    val name: String,
    /** set · missing · expired */
    val state: String,
    val expiresAt: String? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    /** applet.json asks for it (false: a value kept for a name it no longer asks for). */
    val wanted: Boolean = true,
)

@Serializable
data class AppletJobDto(val name: String, val every: String = "", val running: Boolean = false, val lastRun: AppletJobRunSummaryDto? = null)

@Serializable
data class AppletJobRunSummaryDto(val startedAt: String = "", val finishedAt: String? = null, val exitCode: Int? = null, val trigger: String = "")

/** One run of a job, with the tail of what it printed. */
@Serializable
data class AppletJobRunDto(
    val id: Long = 0,
    val job: String = "",
    val trigger: String = "",
    val startedAt: String = "",
    val finishedAt: String? = null,
    val exitCode: Int? = null,
    val output: String = "",
)

@Serializable
data class AppletSettingDto(
    val key: String,
    val label: String = "",
    val type: String = "text",
    val options: List<String>? = null,
    val value: String? = null,
)

@Serializable
data class AppletBackupDto(val id: String, val kind: String = "nightly", val createdAt: String = "", val why: String? = null, val bytes: Long = 0)

@Serializable
data class AppletDataDto(
    val dir: String = "",
    val tables: List<AppletTableDto> = emptyList(),
    val files: Int = 0,
    val bytes: Long = 0,
    val databaseBytes: Long = 0,
)

@Serializable
data class AppletTableDto(val name: String, val rows: Long = 0, val file: String = "")

@Serializable
internal data class AppletValueBody(val value: String, val expiresAt: String? = null)

@Serializable
internal data class AppletTitleBody(val title: String)
