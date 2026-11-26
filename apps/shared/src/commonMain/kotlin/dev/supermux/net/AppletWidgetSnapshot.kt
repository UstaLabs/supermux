// #117 W1: what the Home screen widgets draw from, written by the app (Android: its files dir; iOS: the App Group
// container, read by the SwiftUI widget extension, whose AppletSnapshot.swift mirrors these names). One file per host,
// host-<key>.json, plus "active" (the host picker's host) and the icons, icon-<key>.png.
package dev.supermux.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One applet as a widget shows it. */
@Serializable
data class WidgetApplet(
    val name: String,
    val title: String,
    /** A secret to fill in or renew: the red dot. */
    val needs: Boolean = false,
    val pin: Int? = null,
    val summary: AppletSummaryDto? = null,
    /** What its icon file was drawn from (updatedAt|iconUrl|title); a change draws it again. */
    val iconAt: String = "",
    /** Its icon's file beside the snapshot (drawn by the app; the Swift widgets read it by this name). */
    val iconFile: String = "",
    /** #165: the widget document (GET /applets widgetDoc); null: the widget draws [summary] as before. */
    val widget: JsonObject? = null,
    val widgetHtml: String? = null,
    val accent: String? = null,
    /** The actions that need a confirmation in the applet rather than a tap on the Home screen. */
    val confirm: List<String> = emptyList(),
    /** The pictures the app has drawn: [widgetPictureFile] names, by "<size>/<part>/<light|dark>". */
    val pictures: Map<String, String> = emptyMap(),
)

/** One host's applets, as last seen ([at]: epoch ms). */
@Serializable
data class WidgetHost(val hostId: String, val at: Long, val applets: List<WidgetApplet>) {
    /** The grid's applets: the pinned ones by their place, as the phone's sidebar (the first 8). */
    fun pinned(): List<WidgetApplet> = applets.filter { it.pin != null }.sortedBy { it.pin }.take(PHONE_PINS)
}

/** [hostId]'s applets as widgets keep them (gone folders left out). */
fun List<AppletDto>.toWidgetApplets(hostId: String): List<WidgetApplet> = filter { it.status != "missing" }.map { a ->
    val title = a.title.ifBlank { a.name }
    WidgetApplet(
        a.name, title, a.needsYou.isNotEmpty(), a.pin, a.summary,
        iconAt = a.updatedAt + "|" + (a.iconUrl ?: "") + "|" + title,
        iconFile = "icon-${widgetFileKey("$hostId/${a.name}")}.png",
        widget = a.widgetDoc,
        widgetHtml = a.widgetHtml,
        accent = a.widgetAccent,
        confirm = a.widgetActions.filterValues { it.confirm }.keys.sorted(),
    )
}

/**
 * #165: new rows keep the pictures the app drew for the same applet before: an older picture beats none while the new
 * one is drawn (the widget falls back to the block's native "alt" only when there is no picture at all).
 */
fun List<WidgetApplet>.keepPictures(before: List<WidgetApplet>?): List<WidgetApplet> {
    val old = before?.associateBy { it.name }.orEmpty()
    return map { w -> old[w.name]?.takeIf { it.pictures.isNotEmpty() && w.widgetHtml != null }?.let { w.copy(pictures = it.pictures) } ?: w }
}

/** The snapshot file of [hostId]. */
fun widgetHostFile(hostId: String): String = "host-${widgetFileKey(hostId)}.json"

/** The file-name key of a host ([hostId]) or an icon ("<hostId>/<name>"): 16 hex digits, stable across runs. */
fun widgetFileKey(s: String): String {
    // FNV-1a 64: no platform digest needed. The Swift side computes the same (fnv64 in AppletWidgetDoc.swift).
    var h = -0x340d631b7bdddcdbL
    for (b in s.encodeToByteArray()) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
    return h.toULong().toString(16).padStart(16, '0')
}
