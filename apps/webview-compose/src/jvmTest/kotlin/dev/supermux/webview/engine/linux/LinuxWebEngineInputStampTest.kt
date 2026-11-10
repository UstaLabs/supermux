package dev.supermux.webview.engine.linux

import dev.supermux.webview.bridge.inputClock
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebSettings
import dev.supermux.webview.engine.pageSetup
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The input stamp on Linux (click-to-send): the engine answers the host from its stamp, one input for one send, and a
 * new document starts with none. What marks it, a press or key Compose delivers to the surface ([LinuxWebEngine.pointer],
 * [LinuxWebEngine.key]), needs Compose events no test can make: that is unverified here.
 */
class LinuxWebEngineInputStampTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
    private val other = WebContent.Html("<p>other</p>", WebOrigin("other"))

    private fun EngineRig.loaded() {
        warm()
        engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        engine.load(demo)
    }

    @Test fun theEngineConsumesWhatTheStampHoldsOnceAndANewDocumentStartsWithNone() = runTest {
        val rig = EngineRig(this)
        rig.loaded()
        assertFalse(rig.engine.consumeUserInput(), "no input yet: a send on load")
        rig.engine.inputStamp.mark(inputClock())
        assertTrue(rig.engine.consumeUserInput())
        assertFalse(rig.engine.consumeUserInput(), "one input, one send")
        rig.engine.inputStamp.mark(inputClock())
        rig.engine.load(other)
        assertFalse(rig.engine.consumeUserInput(), "an input in the document before is none in this one")
        rig.close()
    }
}
