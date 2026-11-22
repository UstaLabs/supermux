// #163: what may notify the person, kept per account on the broker (GET / PATCH /notifications, the
// `notification_prefs` frame). The broker decides every push and alert with it; the app only shows and edits it.
package dev.supermux.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A group's or an item's level. [Needs]: only what blocks an agent (a question, a permission prompt). Applets use On / Off. */
@Serializable
enum class NotifyLevel(val wire: String) {
    @SerialName("all") All("all"),
    @SerialName("needs") Needs("needs"),
    @SerialName("off") Off("off"),
    @SerialName("on") On("on"),
}

@Serializable
data class NotificationOverrides(
    /** Sessions (agents included) by session id. */
    val session: Map<String, NotifyLevel> = emptyMap(),
    /** Applets by applet id. */
    val applet: Map<String, NotifyLevel> = emptyMap(),
)

@Serializable
data class NotificationPrefs(
    val agents: NotifyLevel = NotifyLevel.All,
    val sessions: NotifyLevel = NotifyLevel.Needs,
    val applets: NotifyLevel = NotifyLevel.On,
    /** Questions and permission prompts notify even where the level is Off. */
    val needsYou: Boolean = true,
    /** supermux's own notices: a worktree finished, the memory tidy-up. */
    val notices: Boolean = true,
    /** The one-time "Sessions: Needs me" offer (an account from before notification settings). */
    val showTip: Boolean = false,
    val overrides: NotificationOverrides = NotificationOverrides(),
    val updatedAt: String? = null,
    val updatedBy: String? = null,
) {
    /** The level a session's group gives it: the Agents level for an Agent, else the Sessions level. */
    fun groupLevel(agent: Boolean): NotifyLevel = if (agent) agents else sessions

    /** A session's (or Agent's) own level, or null when it follows its group. */
    fun ownLevel(sessionId: String): NotifyLevel? = overrides.session[sessionId]

    /** What a session gets: its own level, else its group's. */
    fun sessionLevel(sessionId: String, agent: Boolean): NotifyLevel = overrides.session[sessionId] ?: groupLevel(agent)

    fun appletLevel(appletId: String): NotifyLevel = overrides.applet[appletId] ?: applets
}

/** A change for PATCH /notifications: only the fields given; an item set to null follows its group again. */
data class NotificationPrefsPatch(
    val agents: NotifyLevel? = null,
    val sessions: NotifyLevel? = null,
    val applets: NotifyLevel? = null,
    val needsYou: Boolean? = null,
    val notices: Boolean? = null,
    val showTip: Boolean? = null,
    /** Session id → its own level, or null to clear it. */
    val session: Map<String, NotifyLevel?> = emptyMap(),
    /** Applet id → its own level, or null to clear it. */
    val applet: Map<String, NotifyLevel?> = emptyMap(),
) {
    /** The JSON body, written by hand: a cleared item must go out as an explicit null. */
    fun toJson(): JsonObject = buildJsonObject {
        agents?.let { put("agents", it.wire) }
        sessions?.let { put("sessions", it.wire) }
        applets?.let { put("applets", it.wire) }
        needsYou?.let { put("needsYou", it) }
        notices?.let { put("notices", it) }
        showTip?.let { put("showTip", it) }
        if (session.isNotEmpty()) put("session", JsonObject(session.mapValues { (_, v) -> v?.let { JsonPrimitive(it.wire) } ?: JsonNull }))
        if (applet.isNotEmpty()) put("applet", JsonObject(applet.mapValues { (_, v) -> v?.let { JsonPrimitive(it.wire) } ?: JsonNull }))
    }

    /** What the broker will hold after this patch, so a page can show it before the answer comes back. */
    fun applyTo(p: NotificationPrefs): NotificationPrefs = p.copy(
        agents = agents ?: p.agents,
        sessions = sessions ?: p.sessions,
        applets = applets ?: p.applets,
        needsYou = needsYou ?: p.needsYou,
        notices = notices ?: p.notices,
        showTip = showTip ?: p.showTip,
        overrides = NotificationOverrides(
            session = merged(p.overrides.session, session),
            applet = merged(p.overrides.applet, applet),
        ),
    )

    private fun merged(cur: Map<String, NotifyLevel>, change: Map<String, NotifyLevel?>): Map<String, NotifyLevel> {
        if (change.isEmpty()) return cur
        val out = cur.toMutableMap()
        change.forEach { (k, v) -> if (v == null) out.remove(k) else out[k] = v }
        return out
    }
}
