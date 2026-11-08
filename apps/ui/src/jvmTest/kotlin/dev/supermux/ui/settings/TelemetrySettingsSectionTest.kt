package dev.supermux.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.TelemetryState
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals

/** "What's sent" opens the telemetry document through the app's [dev.supermux.ui.platform.Platform]. */
@OptIn(ExperimentalTestApi::class)
class TelemetrySettingsSectionTest {
    @Test fun whats_sent_opens_the_document_through_the_platform() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform = platform) {
            Column { TelemetrySettingsSection(SystemSettingsActions(getTelemetry = { TelemetryState(enabled = true) })) }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("system_telemetry_doc").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("system_telemetry_doc").performClick()
        waitForIdle()
        assertEquals(listOf(TELEMETRY_DOC_URL), platform.openedUrls)
    }
}
