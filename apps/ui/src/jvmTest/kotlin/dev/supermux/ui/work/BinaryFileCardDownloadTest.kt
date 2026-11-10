package dev.supermux.ui.work

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.net.FsException
import dev.supermux.proto.ViewDto
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.editor.BinaryFileCard
import dev.supermux.ui.editor.DocumentStore
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.shell.ShellActions
import dev.supermux.ui.shell.ViewHost
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadSource
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Files / editor "Save and open…" on the fleet's downloads (#95). The transfer used to run on the tab's coroutine,
 * so closing the tab cancelled it. The card now starts the host file's download in the center and only waits for
 * it: closing the tab lets go, the download carries on and waits in the bar, announced; staying hands the FILE over
 * at once, as a tap always did (R1). A host that keeps no downloads keeps the bytes path.
 */
@OptIn(ExperimentalTestApi::class)
class BinaryFileCardDownloadTest {
    // The center runs here: unconfined, so a start has run up to its first suspension by the time it returns.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tempFiles = CopyOnWriteArrayList<File>()

    /** What the bytes path read into memory: a download never does. */
    private val byteReads = AtomicInteger(0)

    @AfterTest fun tearDown() {
        scope.cancel()
        tempFiles.forEach { it.delete() }
    }

    private val path = "/w/report.bin"

    /**
     * A host file as the host's download serves it: 42 of its 100 bytes, then it waits for [gate] and leaves a real
     * file (a hand-over checks the file is there) — or, with [refusal], fails the way the host refuses one.
     * [stopped]: its transfer was cancelled.
     */
    private inner class HostFile(private val refusal: Throwable? = null) : DownloadSource {
        val gate = CompletableDeferred<Unit>()
        val stopped = AtomicBoolean(false)

        override suspend fun fetch(progress: (received: Long, total: Long?) -> Unit): DownloadedFile? {
            refusal?.let { throw it }
            progress(42, 100)
            try {
                gate.await()
            } catch (c: CancellationException) {
                stopped.set(true)
                throw c
            }
            val file = File.createTempFile("mux95-host", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            tempFiles += file
            return DownloadedFile("report.bin", "application/octet-stream", 3, file.absolutePath)
        }
    }

    /** [path]'s card in a tab of [center]'s fleet, while [shown]; a desktop (a pointer: "Save and open…"). */
    private fun ComposeUiTest.card(
        platform: FakePlatform,
        center: DownloadCenter,
        download: ((String) -> Pair<String, DownloadSource>?)?,
        shown: () -> Boolean = { true },
    ) = setPlatformContent(platform) {
        CompositionLocalProvider(LocalDownloads provides center) {
            if (shown()) {
                BinaryFileCard(
                    absPath = path,
                    loadBytes = {
                        byteReads.incrementAndGet()
                        Result.success(byteArrayOf(1, 2, 3))
                    },
                    download = download,
                )
            }
        }
    }

    /** What [center] announces as finished with nobody waiting (the shell's "<name> downloaded" notice). */
    private fun announced(center: DownloadCenter): List<Download> {
        val heard = CopyOnWriteArrayList<Download>()
        scope.launch { center.unclaimedCompletions.collect { heard += it } }
        return heard
    }

    /**
     * Gives work that hops threads (a hand-over checks its file off the main thread) up to [ms] to be [done], and
     * returns either way: the assertion after it says what was wanted.
     */
    private fun ComposeUiTest.settle(ms: Long = 5_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) {
            waitForIdle()
            Thread.sleep(10)
        }
    }

    /** Until [block] holds (a recomposition after work on another thread), at most five seconds. */
    private fun ComposeUiTest.waitFor(block: () -> Unit) =
        waitUntil(timeoutMillis = 5_000) {
            try {
                block()
                true
            } catch (_: Throwable) {
                false
            }
        }

    private fun FakePlatform.handedOverNothing() = assertEquals(
        emptyList(),
        files.savedFiles + files.openedFiles + files.sharedFiles + files.openedSaved.map { it.name } + files.saved + files.opened,
        "nothing was saved, opened or shared",
    )

    // ── the regression ─────────────────────────────────────────────────────────────────────────

    @Test fun closingTheTabNeitherStopsTheDownloadNorOpensItLater() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        val source = HostFile()
        var shown by mutableStateOf(true)
        card(platform, center, download = { p -> "fs:h:$p@1" to source }, shown = { shown })
        onNodeWithTag("editor_binary_open").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("fs:h:/w/report.bin@1"), center.items.value.map { it.key })

        // He closes the tab; the download goes on without it.
        shown = false
        waitForIdle()
        onNodeWithTag("editor_binary_card").assertDoesNotExist()
        source.gate.complete(Unit)
        settle { heard.isNotEmpty() }

        assertIs<DownloadState.Done>(center.items.value.single().state)
        assertEquals(listOf("fs:h:/w/report.bin@1"), heard.map { it.key }, "announced once, for the bar and the notice")
        assertFalse(source.stopped.get(), "closing the tab stopped the transfer")
        assertEquals(0, byteReads.get(), "never read into memory")
        // Nothing pops up over whatever he went to (2a): no Save dialog, no other app.
        platform.handedOverNothing()
    }

    @Test fun stayingSavesTheFileThenOpensWhatWasSaved() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val heard = announced(center)
        val source = HostFile()
        card(platform, center, download = { p -> "fs:h:$p@1" to source })
        onNodeWithTag("editor_binary_open").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("fs:h:/w/report.bin@1"), center.items.value.map { it.key })
        source.gate.complete(Unit)
        settle { platform.files.openedSaved.isNotEmpty() }

        assertEquals(listOf("report.bin"), platform.files.savedFiles)
        assertEquals(listOf("report.bin"), platform.files.openedSaved.map { it.name })
        assertEquals(0, byteReads.get(), "the FILE is handed over, never read into memory")
        assertEquals(emptyList(), platform.files.saved, "no bytes were saved")
        assertEquals(emptyList(), heard.toList(), "the tab that waited acted on it: no notice")
    }

    // ── what the card says ─────────────────────────────────────────────────────────────────────

    @Test fun whileItDownloadsTheButtonSaysSoAndTakesNoSecondTap() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val source = HostFile()
        val asked = CopyOnWriteArrayList<String>()
        card(platform, center, download = { p ->
            asked += p
            "fs:h:$p@${asked.size}" to source
        })
        onNodeWithTag("editor_binary_open").assertTextEquals("Save and open…").performClick()
        settle { center.items.value.isNotEmpty() }
        assertEquals(listOf("fs:h:/w/report.bin@1"), center.items.value.map { it.key })
        onNodeWithTag("editor_binary_open").assertIsNotEnabled().assertTextEquals("Downloading…")

        source.gate.complete(Unit)
        settle { platform.files.openedSaved.isNotEmpty() }
        waitFor { onNodeWithTag("editor_binary_open").assertIsEnabled().assertTextEquals("Save and open…") }
        assertEquals(listOf(path), asked.toList(), "one tap, one download")
    }

    @Test fun aDownloadThatFailsWhileTheTabIsOpenSaysWhyInTheHostsWords() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val source = HostFile(refusal = FsException(413, "File too large (max 64 MB)"))
        card(platform, center, download = { p -> "fs:h:$p@1" to source })
        onNodeWithTag("editor_binary_open").performClick()
        settle { platform.notices.shown.isNotEmpty() }

        assertEquals(listOf("File too large (max 64 MB)"), platform.notices.shown)
        assertEquals(listOf("fs:h:/w/report.bin@1"), center.items.value.map { it.key })
        assertIs<DownloadState.Failed>(center.items.value.single().state, "failed in the bar too, retryable")
        platform.handedOverNothing()
    }

    @Test fun aHostThatKeepsNoDownloadsKeepsTheBytesPath() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        card(platform, center, download = { null })
        onNodeWithTag("editor_binary_open").performClick()
        settle { platform.files.openedSaved.isNotEmpty() }

        assertEquals(listOf("report.bin|application/octet-stream|3"), platform.files.saved)
        assertEquals(listOf("report.bin"), platform.files.openedSaved.map { it.name })
        assertEquals(emptyList(), center.items.value, "no download to list")
    }

    // ── the wiring: a workspace's file tab ─────────────────────────────────────────────────────

    @Test fun aFileTabDownloadsFromItsWorkspacesHost() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope)
        val source = HostFile()
        val asked = CopyOnWriteArrayList<String>()
        val actions = ShellActions(
            hostFileDownloadFor = { workspaceId, p ->
                asked += "$workspaceId|$p"
                "fs:h:$p@1" to source
            },
        )
        // The host refuses the file as text (415), so the tab offers to hand it to the OS instead.
        val documents = DocumentStore(
            fsRead = { Result.failure(FsException(415, "Binary file")) },
            fsWrite = { _, _ -> true },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val view = ViewDto(
            id = "v1",
            workspaceId = "w1",
            kind = "editor",
            state = JsonObject(mapOf("mode" to JsonPrimitive("file"), "path" to JsonPrimitive("report.bin"))),
        )
        setPlatformContent(platform) {
            CompositionLocalProvider(LocalDownloads provides center) {
                ViewHost(
                    view = view,
                    workspaceId = "w1",
                    workdir = "/w",
                    actions = actions,
                    drafts = remember { mutableStateMapOf<String, String>() },
                    documents = documents,
                )
            }
        }
        onNodeWithTag("editor_binary_open").performClick()
        settle { center.items.value.isNotEmpty() }

        assertEquals(listOf("w1|/w/report.bin"), asked.toList(), "the workspace's host, for the file's absolute path")
        assertEquals(listOf("fs:h:/w/report.bin@1"), center.items.value.map { it.key })
        assertEquals(emptyList(), platform.notices.shown, "not the bytes path (with no file system: \"Host offline\")")
    }
}
