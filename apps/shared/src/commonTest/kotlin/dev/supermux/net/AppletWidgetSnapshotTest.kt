package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AppletWidgetSnapshotTest {
    @Test fun file_keys_are_stable_hex_and_differ_per_input() {
        // FNV-1a 64 of "" is its offset basis.
        assertEquals("cbf29ce484222325", widgetFileKey(""))
        assertEquals(16, widgetFileKey("host-1/issues").length)
        assertEquals(widgetFileKey("h/a"), widgetFileKey("h/a"))
        assertNotEquals(widgetFileKey("h/a"), widgetFileKey("h/b"))
    }

    @Test fun applets_become_widget_rows_pinned_ones_in_place_order_gone_ones_left_out() {
        val applets = listOf(
            AppletDto(id = "1", name = "issues", title = "Issues", pin = 1, summary = AppletSummaryDto("31", "open"), updatedAt = "t1"),
            AppletDto(id = "2", name = "jobs", title = "", pin = 0, needsYou = listOf(AppletNeedDto("GMAIL", "expired")), iconUrl = "/applets/jobs/icon"),
            AppletDto(id = "3", name = "old", title = "Old", status = "missing", pin = 2),
            AppletDto(id = "4", name = "notes", title = "Notes"),
        )
        val rows = applets.toWidgetApplets("h1")
        assertEquals(listOf("issues", "jobs", "notes"), rows.map { it.name })
        val jobs = rows.first { it.name == "jobs" }
        assertEquals("jobs", jobs.title)
        assertEquals(true, jobs.needs)
        assertEquals("icon-${widgetFileKey("h1/jobs")}.png", jobs.iconFile)
        assertEquals("|/applets/jobs/icon|jobs", jobs.iconAt)
        assertEquals(listOf("jobs", "issues"), WidgetHost("h1", 0, rows).pinned().map { it.name })
        assertEquals("host-${widgetFileKey("h1")}.json", widgetHostFile("h1"))
    }
}
