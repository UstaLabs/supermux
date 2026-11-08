package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

private fun iframesIn(cut: HTMLElement): Int = js("cut.querySelectorAll('iframe').length")

class IframeWebEngineLifecycleTest {
    @Test fun aReloadKeepsTheOldPageOnScreenUntilTheNewOnePaints() = runTest(timeout = 1.minutes) {
        val content = WebContent.Html("<!doctype html><html><body>reload me</body></html>")
        val engine = IframeWebEngine(EngineConfig(WebProfile.Ephemeral))
        val events = ArrayList<EngineEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch { engine.events.collect { events += it } }
        engine.configure(PageSetup(true, false, false, false, null, setOf(content.origin.origin)))
        engine.load(content)
        TestViewport.show { Box(Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        waitUntil("the first paint") { events.count { it == EngineEvent.FirstPaint } == 1 }

        engine.reload()
        assertEquals("visible", styleValue(engine.frame.outer, "visibility"), "the old page stays on screen")
        assertEquals(2, iframesIn(engine.frame.cut), "the old iframe holds paint while the new one loads")
        waitUntil("the new document's paint") { events.count { it == EngineEvent.FirstPaint } == 2 }
        assertEquals(1, iframesIn(engine.frame.cut), "then it goes")
        assertEquals("visible", styleValue(engine.frame.outer, "visibility"))

        TestViewport.clear()
        scope.cancel()
        engine.release()
    }
}
