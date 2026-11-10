package dev.supermux.ui.update

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.chat.Composer
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakeAppUpdater
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.InFlightWork
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.platform.PickedFile
import dev.supermux.update.ClientUpdateStatus
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #113: "Restart to update" never cuts the app's own work. While a dictation or recording, or an attachment upload,
 * is running, the card's button says "Finishing upload…" (or "Finishing recording…") and the restart goes ahead by
 * itself once that work is done. No dialog.
 */
@OptIn(ExperimentalTestApi::class)
class RestartWaitsForWorkTest {
    @AfterTest fun clear() = InFlightWork.clearForTest()

    private val release = ClientUpdateStatus(
        currentVersion = "1.0.0", latestVersion = "1.1.0", updateAvailable = true, notesUrl = null,
        downloadUrl = "https://example.test/supermux-1.1.0.msi", canInstall = true,
    )

    private fun platform(updater: FakeAppUpdater) = FakePlatform(caps = NO_CAPS.copy(appUpdate = true)).also { it.updates = updater }

    @Test fun work_begins_and_ends_and_ending_twice_is_harmless() {
        val end = InFlightWork.begin(InFlightWork.Kind.Upload)
        assertEquals(listOf(InFlightWork.Kind.Upload), InFlightWork.active.value)
        end(); end()
        assertTrue(InFlightWork.active.value.isEmpty())
    }

    @Test fun restart_to_update_waits_for_an_upload_then_goes_ahead_by_itself() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release).also { it.restartsToUpdate = true }
        val end = InFlightWork.begin(InFlightWork.Kind.Upload)
        setPlatformContent(platform(updater)) {
            LaunchedEffect(updater) { updater.check(); updater.becomeReady() }
            AppUpdateCard()
        }
        waitForIdle()
        onNodeWithTag("app_update_card_update").performClick()
        waitForIdle()
        onNodeWithTag("app_update_card_update").assertTextEquals("Finishing upload…")
        assertTrue(updater.installed.isEmpty(), "not while the upload runs")
        end()
        waitUntil(timeoutMillis = 5_000) { updater.installed.isNotEmpty() }
    }

    @Test fun a_recording_says_finishing_recording() = runComposeUiTest {
        val updater = FakeAppUpdater(release = release).also { it.restartsToUpdate = true }
        InFlightWork.begin(InFlightWork.Kind.Recording)
        setPlatformContent(platform(updater)) {
            LaunchedEffect(updater) { updater.check(); updater.becomeReady() }
            AppUpdateCard()
        }
        waitForIdle()
        onNodeWithTag("app_update_card_update").performClick()
        waitForIdle()
        onNodeWithTag("app_update_card_update").assertTextEquals("Finishing recording…")
    }

    @Test fun a_composer_upload_counts_as_work_until_it_is_done() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val file = PickedFile("a.png", "image/png", ByteArrayChunkSource(ByteArray(8)))
        setPlatformContent(FakePlatform(pickResult = listOf(file))) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false, onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, _, _, _, _ -> gate.await(); "f-1" },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitUntil(timeoutMillis = 5_000) { InFlightWork.active.value == listOf(InFlightWork.Kind.Upload) }
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { InFlightWork.active.value.isEmpty() }
    }
}
