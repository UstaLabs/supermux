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
