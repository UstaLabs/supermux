package dev.supermux.ui.whatsnew

import dev.supermux.ui.prefs.FakeSettingsStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What's new (#90): which edition a device is offered after an update, and which of its chapters this platform has.
 * Shown once per version per device, never on a first install, and never for a release without an edition.
 */
class WhatsNewTest {

    // ── the edition's manifest ────────────────────────────────────────────────────────────────

    private val page = """
        <!doctype html><html><head>
        <script type="application/json" id="edition">
          {"version":"0.17.7","chapters":[
            {"id":"browser","on":["mac","win","android","iphone"]},
            {"id":"cards"},
            {"id":"signed","on":["win"]}
          ]}
        </script></head><body></body></html>
    """.trimIndent()

    @Test fun an_edition_names_its_version_and_chapters() {
        val edition = parseEdition(page)!!
        assertEquals("0.17.7", edition.version)
        assertEquals(listOf("browser", "cards", "signed"), edition.chapters.map { it.id })
    }

    @Test fun a_page_without_a_manifest_is_no_edition() {
        assertNull(parseEdition("<html><body>hi</body></html>"))
    }

    @Test fun a_chapter_without_platforms_is_everywhere_and_one_with_them_only_there() {
        val edition = parseEdition(page)!!
        assertEquals(listOf("browser", "cards", "signed"), edition.chaptersFor(ReleasePlatform.Windows).map { it.id })
        assertEquals(listOf("browser", "cards"), edition.chaptersFor(ReleasePlatform.Mac).map { it.id })
        assertEquals(listOf("cards"), edition.chaptersFor(ReleasePlatform.Web).map { it.id })
    }

    @Test fun an_unknown_platform_gets_only_the_chapters_every_platform_has() {
        assertEquals(listOf("cards"), parseEdition(page)!!.chaptersFor(null).map { it.id })
    }

    // ── when it is offered ────────────────────────────────────────────────────────────────────

    private fun tracker(store: FakeSettingsStore, current: String, editions: List<String> = listOf("0.17.7")) =
        WhatsNewTracker(store, current, editions)

    @Test fun a_first_install_is_offered_nothing_and_never_this_version_later() = runTest {
        val store = FakeSettingsStore()
        assertTrue(tracker(store, "0.17.7").start(existingInstall = false).isEmpty())
        assertTrue(tracker(store, "0.17.7").start(existingInstall = true).isEmpty(), "the next launch: still nothing")
    }

    @Test fun an_update_to_a_version_with_an_edition_offers_it() = runTest {
        val store = FakeSettingsStore()
        tracker(store, "0.17.6", editions = emptyList()).start(existingInstall = true)
        assertEquals(listOf("0.17.7"), tracker(store, "0.17.7").start(existingInstall = true))
    }

    @Test fun a_device_that_had_the_app_before_this_feature_is_offered_the_edition() = runTest {
        // Nothing stored yet, but it already has machines paired: an update, not a first install.
        assertEquals(listOf("0.17.7"), tracker(FakeSettingsStore(), "0.17.7").start(existingInstall = true))
    }

    @Test fun it_stays_offered_across_launches_until_it_is_opened_or_dismissed() = runTest {
        val store = FakeSettingsStore()
        tracker(store, "0.17.6", editions = emptyList()).start(existingInstall = true)
        assertEquals(listOf("0.17.7"), tracker(store, "0.17.7").start(existingInstall = true))
        val again = tracker(store, "0.17.7")
        assertEquals(listOf("0.17.7"), again.start(existingInstall = true))
        again.settle()
        assertTrue(tracker(store, "0.17.7").start(existingInstall = true).isEmpty())
    }

    @Test fun a_release_without_an_edition_offers_nothing() = runTest {
        val store = FakeSettingsStore()
        tracker(store, "0.17.7").apply { start(existingInstall = true); settle() }
        assertTrue(tracker(store, "0.17.8").start(existingInstall = true).isEmpty())
    }

    @Test fun skipped_releases_offer_every_missed_edition_newest_first() = runTest {
        val store = FakeSettingsStore()
        tracker(store, "0.17.6", editions = emptyList()).start(existingInstall = true)
        val editions = listOf("0.17.7", "0.17.9", "0.18.0")
        assertEquals(listOf("0.17.9", "0.17.7"), tracker(store, "0.17.9", editions).start(existingInstall = true))
    }

    @Test fun an_edition_newer_than_the_running_build_is_not_offered() = runTest {
        assertTrue(tracker(FakeSettingsStore(), "0.17.6").start(existingInstall = true).isEmpty())
    }
}
