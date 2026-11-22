package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

/** #163: the notification settings the broker keeps, as the app reads and changes them. */
class NotificationsTest {
    @Test fun parses_the_settings_frame_with_overrides() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"notification_prefs","prefs":{"agents":"all","sessions":"needs","applets":"on","needsYou":true,"notices":false,"showTip":true,"overrides":{"session":{"s1":"off"},"applet":{"a1":"off"}},"updatedAt":"t","updatedBy":"{}"}}""",
        )
        assertTrue(f is ServerFrame.NotificationPrefsChanged)
        val p = (f as ServerFrame.NotificationPrefsChanged).prefs
        assertEquals(NotifyLevel.Needs, p.sessions)
        assertEquals(false, p.notices)
        assertEquals(NotifyLevel.Off, p.sessionLevel("s1", agent = false))
        assertEquals(NotifyLevel.Needs, p.sessionLevel("s2", agent = false))
        assertEquals(NotifyLevel.All, p.sessionLevel("s2", agent = true))
        assertEquals(NotifyLevel.Off, p.appletLevel("a1"))
        assertEquals(NotifyLevel.On, p.appletLevel("a2"))
    }

    @Test fun message_append_carries_alert_and_an_older_broker_leaves_it_null() {
        val entry = """{"id":"e","ts":"t","direction":"outbound","channel":"web","op":"reply","text":"hi"}"""
        val quiet = json.decodeFromString<ServerFrame>("""{"type":"message_append","session":"s","entry":$entry,"alert":false}""") as ServerFrame.MessageAppend
        assertEquals(false, quiet.alert)
        val old = json.decodeFromString<ServerFrame>("""{"type":"message_append","session":"s","entry":$entry}""") as ServerFrame.MessageAppend
        assertNull(old.alert)
    }

    @Test fun a_patch_sends_only_what_changed_and_a_cleared_item_as_null() {
        val patch = NotificationPrefsPatch(sessions = NotifyLevel.Off, needsYou = false, session = mapOf("s1" to null, "s2" to NotifyLevel.All))
        assertEquals("""{"sessions":"off","needsYou":false,"session":{"s1":null,"s2":"all"}}""", patch.toJson().toString())
        val after = patch.applyTo(NotificationPrefs(overrides = NotificationOverrides(session = mapOf("s1" to NotifyLevel.Off))))
        assertEquals(NotifyLevel.Off, after.sessions)
        assertEquals(mapOf("s2" to NotifyLevel.All), after.overrides.session)
        assertEquals(false, after.needsYou)
        assertEquals(NotifyLevel.All, after.agents)
    }
}
