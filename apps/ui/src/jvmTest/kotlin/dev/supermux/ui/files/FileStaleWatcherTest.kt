package dev.supermux.ui.files

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.fs.FileSystemService
import dev.supermux.net.BrokerApi
import dev.supermux.net.FsEntry
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import dev.supermux.ui.editor.DocumentStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FileStaleWatcherTest {
    private fun service(sent: MutableList<ClientFrame>) = FileSystemService(
        BrokerApi("http://h", "t", HttpClient(MockEngine { respond("{}") })),
        send = { synchronized(sent) { sent += it } },
        scope = CoroutineScope(Dispatchers.Unconfined),
        graceMs = 0,
    )

    private fun sentCopy(sent: MutableList<ClientFrame>) = synchronized(sent) { sent.toList() }

    private fun dir(path: String, version: String, vararg files: Pair<String, Long>) =
        ServerFrame.FsDir(path = path, version = version, entries = files.map { (n, m) -> FsEntry(name = n, type = "file", mtime = m, size = 1) })

    @Test fun anOutsideChangeRaisesTheBannerAndClosingReleasesTheFolder() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val docs = DocumentStore({ Result.success("x") }, { _, _ -> true }, CoroutineScope(Dispatchers.Unconfined))
        setContent { FileStaleWatcher(fs, "/w", docs) }
        docs.open("src/a.kt")
        docs.open("src/b.kt")
        waitForIdle()
        // One subscription for the shared folder.
        assertTrue(sentCopy(sent).count { it == ClientFrame.FsSub("/w/src") } == 1)
        fs.onFrame(dir("/w/src", "1", "a.kt" to 1, "b.kt" to 1))
        waitForIdle()
        assertFalse(docs.isStale("src/a.kt"))
        fs.onFrame(dir("/w/src", "2", "a.kt" to 2, "b.kt" to 1))
        waitForIdle()
        assertTrue(docs.isStale("src/a.kt"))
        assertFalse(docs.isStale("src/b.kt"))
        docs.close("src/a.kt")
        waitForIdle()
        assertFalse(ClientFrame.FsUnsub("/w/src") in sentCopy(sent))
        docs.close("src/b.kt")
        waitForIdle()
        assertTrue(ClientFrame.FsUnsub("/w/src") in sentCopy(sent))
    }

    @Test fun ourOwnSaveDoesNotRaiseTheBanner() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val write = CompletableDeferred<Boolean>()
        val docs = DocumentStore({ Result.success("x") }, { _, _ -> write.await() }, CoroutineScope(Dispatchers.Unconfined))
        setContent { FileStaleWatcher(fs, "/w", docs) }
        docs.open("a.kt")
        waitForIdle()
        fs.onFrame(dir("/w", "1", "a.kt" to 1))
        waitForIdle()
        docs.update("a.kt", "edited")
        docs.save(docs.get("a.kt")!!)
        // The folder event for our write lands before the save answers.
        fs.onFrame(dir("/w", "2", "a.kt" to 2))
        waitForIdle()
        write.complete(true)
        waitForIdle()
        // …and another one right after it answered.
        fs.onFrame(dir("/w", "3", "a.kt" to 3))
        waitForIdle()
        assertFalse(docs.isStale("a.kt"))
    }

    @Test fun aGoneFolderIsWatchedAgainWhenItComesBack() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = FileSystemService(
            BrokerApi("http://h", "t", HttpClient(MockEngine { respond("{}") })),
            send = { synchronized(sent) { sent += it } },
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 0,
            goneRetryBaseMs = 50,
        )
        val docs = DocumentStore({ Result.success("x") }, { _, _ -> true }, CoroutineScope(Dispatchers.Unconfined))
        setContent { FileStaleWatcher(fs, "/w", docs) }
        docs.open("build/a.kt")
        waitForIdle()
        fs.onFrame(dir("/w/build", "1", "a.kt" to 1))
        waitForIdle()
        fs.onFrame(ServerFrame.FsGone("/w/build")) // rm -rf build
        waitForIdle()
        synchronized(sent) { sent.clear() }
        waitUntil(timeoutMillis = 5_000) { ClientFrame.FsSub("/w/build") in sentCopy(sent) }
        fs.onFrame(dir("/w/build", "2", "a.kt" to 5))  // mkdir build && regenerate
        waitForIdle()
        assertTrue(docs.isStale("build/a.kt"))
    }
}
