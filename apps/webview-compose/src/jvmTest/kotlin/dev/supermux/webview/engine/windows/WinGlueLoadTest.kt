package dev.supermux.webview.engine.windows

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #137: the glue Gradle staged loads, as the app loads it: extracted from the jar's resources, its sha256 checked, jawt
 * first, and its ABI this library's. It runs where [REQUIRED] is set (the release's Windows job, after
 * native/windows/ci-build.ps1), and is skipped everywhere else: a dev machine or CI lane without the glue has nothing to
 * load.
 */
class WinGlueLoadTest {
    @Test fun theStagedGlueLoadsAndSpeaksThisLibrarysAbi() {
        assumeTrue("$REQUIRED is not set: no Windows glue to load here", System.getenv(REQUIRED) == "1")
        val file = WinNativeLoader().load()
        assertTrue(file.isFile, "the glue was extracted to $file")
        assertEquals(WinGlue.ABI, WinGlue.abiVersion(), "the loaded glue's ABI")
    }

    private companion object {
        const val REQUIRED = "WEBVIEW_REQUIRE_WINDOWS_GLUE"
    }
}
