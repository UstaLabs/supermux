package dev.supermux.webview.engine.windows

import dev.supermux.webview.UnavailableReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class Wv2RuntimeTest {
    private fun host(
        windows: Boolean = true,
        glue: String? = null,
        start: Int = WinGlue.START_OK,
        version: String? = "156.0.4314.56",
        opened: WinEvent.Environment = WinEvent.Environment(1, 0, "156.0.4314.56"),
        folders: MutableList<File> = mutableListOf(),
    ) = Wv2Runtime.Host(
        windows = windows,
        loadGlue = { glue },
        start = { start },
        runtimeVersion = { version },
        compare = { a, b -> a.substringBefore('.').toInt().compareTo(b.substringBefore('.').toInt()) },
        openEnvironment = { folder -> folders += folder; opened },
        localAppData = File("C:/Users/t/AppData/Local"),
        app = "chat",
    )

    @Test fun aWindowsWithTheRuntimeIsReadyOnTheAppsOwnFolder() = runTest {
        val folders = mutableListOf<File>()
        val state = Wv2Runtime.load(host(folders = folders))
        assertIs<Wv2Runtime.State.Ready>(state)
        assertEquals(listOf(File("C:/Users/t/AppData/Local/dev.supermux.webview/webview2/chat")), folders)
    }

    @Test fun aBrowserProcessThatExitedIsOpenedAgainForTheNextEngine() = runTest {
        // WebView2 (156): the browser process exits once its last page is gone, and createPage then fails with no
        // environment; the next engine's start opens it again.
        Wv2Runtime.resetForTests()
        try {
            val folders = mutableListOf<File>()
            val host = host(folders = folders)
            assertIs<Wv2Runtime.State.Ready>(Wv2Runtime.start(host))
            assertIs<Wv2Runtime.State.Ready>(Wv2Runtime.start(host))
            assertEquals(1, folders.size, "opened once while its browser runs")
            Wv2Runtime.browserExited()
            assertIs<Wv2Runtime.State.Ready>(Wv2Runtime.start(host))
            assertEquals(2, folders.size, "opened again")
        } finally {
            Wv2Runtime.resetForTests()
        }
    }

    @Test fun eachFailureSaysWhyWithItsReason() = runTest {
        assertEquals(UnavailableReason.NotImplemented, (Wv2Runtime.load(host(windows = false)) as Wv2Runtime.State.Unavailable).reason)
        assertEquals(UnavailableReason.NativeLibraryFailed, (Wv2Runtime.load(host(glue = "no glue")) as Wv2Runtime.State.Unavailable).reason)
        val capture = Wv2Runtime.load(host(start = WinGlue.START_NO_CAPTURE)) as Wv2Runtime.State.Unavailable
        assertEquals(UnavailableReason.NotImplemented, capture.reason)
        assertTrue("1809" in capture.detail, capture.detail)
        val none = Wv2Runtime.load(host(version = null)) as Wv2Runtime.State.Unavailable
        assertEquals(UnavailableReason.NoWebView2Runtime, none.reason)
        assertTrue(Wv2Runtime.DOWNLOAD in none.detail, none.detail)
        val old = Wv2Runtime.load(host(version = "109.0.1518.140")) as Wv2Runtime.State.Unavailable
        assertEquals(UnavailableReason.NoWebView2Runtime, old.reason)
        assertTrue("109.0.1518.140" in old.detail, old.detail)
        val shared = Wv2Runtime.load(host(opened = WinEvent.Environment(1, 0x8007139FL, null))) as Wv2Runtime.State.Unavailable
        assertEquals(UnavailableReason.CreationFailed, shared.reason)
        assertTrue("other options" in shared.detail, shared.detail)
    }
}
