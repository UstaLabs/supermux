package dev.supermux.webview.engine.mac

import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The real glue, as `stageMacGlue` packaged it into this build's resources: on a Mac only. */
class MacNativeLibraryMacTest {
    @BeforeTest fun onlyOnAMac() {
        assumeTrue("the glue builds and loads on a Mac only", MacPlatform.isMac)
        assertNull(MacNativeLibrary.ensureLoaded(), "the packaged glue loads")
    }

    @Test fun theGlueSpeaksThisLibrarysAbi() {
        assertEquals(MacGlue.ABI, MacGlue.abiVersion())
    }

    /** The JSON writer (D5) on fixed inputs: what `evaluate` returns, as iOS's engine writes it. */
    @Test fun scriptResultsAreWrittenAsJson() {
        assertEquals(
            listOf(
                """"a\"b\\c\nd\u0001é"""",
                "true",
                "false",
                "42",
                "0.5",
                "nil",
                "null",
                """[1,"x",[true]]""",
                """{"k":"v"}""",
                "nil",
                "nil",
                "1e+21",
                "-3",
            ),
            MacGlue.jsonSelfTest().split('\n'),
        )
    }
}
