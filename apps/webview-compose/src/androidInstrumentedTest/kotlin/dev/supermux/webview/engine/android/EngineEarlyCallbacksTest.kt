package dev.supermux.webview.engine.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.DialogResult
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebDialog
import dev.supermux.webview.engine.EngineEvent
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a document does at once, from an inline script or at onload, reaches the lease (the gate fix), on the phone.
 * WebView calls these back synchronously, which can be before the engine's posted completion of the commit runs. Each
 * test loads a fresh page [TIMES] times, so a race shows.
 */
@RunWith(AndroidJUnit4::class)
class EngineEarlyCallbacksTest {
    @Test fun aPagesFirstConsoleLinesArriveAfterItsCommitEveryTime() = EngineHarness.run {
        repeat(TIMES) { n ->
            val page = TestPages.earlyConsole(n)
            val mark = loadMarked(page, setupFor(page, bridgeOrigins = emptySet()))
            await("load $n's inline console line") { (it as? EngineEvent.Console)?.message?.message == "inline line $n" }
            await("load $n's onload console line") { (it as? EngineEvent.Console)?.message?.message == "onload line $n" }
            val after = seen.drop(mark)
            val commit = after.indexOf(EngineEvent.UrlChanged(page.origin.url))
            val inline = after.indexOfFirst { (it as? EngineEvent.Console)?.message?.message == "inline line $n" }
            assertTrue(commit in 0 until inline, "load $n: the commit, then the document's console: $after")
        }
    }

    @Test fun anInlineAlertAndConfirmReachTheClientAndItsAnswerReachesThePage() = EngineHarness.run {
        val client = RecordingClient(dialog = { if (it is WebDialog.Confirm) DialogResult.Confirmed else DialogResult.Dismissed })
        main { this.client = client }
        repeat(TIMES) { n ->
            val page = TestPages.earlyDialogs(n)
            main {
                configure(setupFor(page))
                load(page)
            }
            await("load $n's answer") { it == EngineEvent.TitleChanged("confirm true $n") }
            val asked = main { client.asked.filterIsInstance<WebDialog>() }
            assertEquals(2 * (n + 1), asked.size, "load $n: the client was asked both, every time: $asked")
            assertEquals(listOf("hello $n", "sure $n?"), asked.takeLast(2).map { it.message })
            assertTrue(asked[asked.size - 2] is WebDialog.Alert && asked.last() is WebDialog.Confirm, "an alert, then a confirm")
        }
    }

    @Test fun anEarlyPermissionPromptReachesTheClient() = EngineHarness.run {
        val client = RecordingClient(permission = false)
        main { this.client = client }
        repeat(TIMES) { n ->
            val page = TestPages.earlyCamera(n)
            main {
                configure(setupFor(page))
                load(page)
            }
            await("load $n's refusal") { it == EngineEvent.TitleChanged("camera NotAllowedError $n") }
            val asked = main { client.asked.filterIsInstance<PermissionRequest>() }
            assertEquals(n + 1, asked.size, "load $n: the client was asked, every time: $asked")
            assertEquals(PermissionRequest(page.origin.origin, setOf(PermissionKind.Camera)), asked.last())
        }
    }

    private companion object {
        const val TIMES = 5
    }
}
