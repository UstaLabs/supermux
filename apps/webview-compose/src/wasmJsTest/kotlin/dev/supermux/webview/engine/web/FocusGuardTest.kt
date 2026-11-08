package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** A text field of the app's own, below the test viewport. */
private fun appInput(): HTMLElement = js("""(function () {
  var input = document.createElement('input');
  input.setAttribute('data-test-app-input', '');
  input.style.position = 'fixed';
  input.style.left = '0px';
  input.style.top = '640px';
  document.body.appendChild(input);
  return input;
})()""")

private fun removeElementNow(element: HTMLElement): Unit = js("{ element.remove(); }")

/**
 * A field of the app's own inside an open shadow root, as Compose's is in its canvas's: `[host, input]`. With
 * [takesItselfBack], it focuses itself again on its blur, as Compose's text field does with its backing textarea.
 */
private fun shadowField(takesItselfBack: Boolean = false): JsArray<HTMLElement> = js("""(function () {
  var host = document.createElement('div');
  host.style.position = 'fixed';
  host.style.left = '0px';
  host.style.top = '680px';
  var input = document.createElement('input');
  host.attachShadow({ mode: 'open' }).appendChild(input);
  if (takesItselfBack) input.addEventListener('blur', function () { if (!input.__off) input.focus(); });
  document.body.appendChild(host);
  return [host, input];
})()""")

private fun stopTakingItselfBack(input: HTMLElement): Unit = js("{ input.__off = true; }")

/** Whether the demo said, when last asked (`{reportFocus}`), that it has the keyboard; null before it said. */
private fun demoSaidItHasFocus(): Boolean? = js("(window.__demoHasFocus === undefined) ? null : window.__demoHasFocus")

private fun listenForFocusReports(): Unit = js("""{
  if (!window.__focusReports) {
    window.__focusReports = true;
    window.addEventListener('message', function (e) { if (e.data && typeof e.data.demoHasFocus === 'boolean') window.__demoHasFocus = e.data.demoHasFocus; });
  }
  window.__demoHasFocus = undefined;
}""")

/** Whether the demo itself says it has the keyboard: the app's document can name its own field while a frame has it. */
private suspend fun ListHarness.demoHasKeyboard(): Boolean {
    listenForFocusReports()
    tell("""{"reportFocus": true}""")
    waitUntil("the demo's report") { demoSaidItHasFocus() != null }
    return demoSaidItHasFocus() == true
}

/** Whether [element], maybe inside a shadow root, has the keyboard. */
private fun hasKeyboard(element: HTMLElement): Boolean = js("""(function () {
  var a = document.activeElement;
  while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
  return a === element && document.hasFocus();
})()""")

private fun activeTag(): String = js("String(document.activeElement && document.activeElement.localName)")

/** A page of another site on the test server that focuses its input when told `'focus'` (karma.config.d). */
private fun focusPageUrl(): String =
    js("location.protocol + '//' + (location.hostname === 'localhost' ? '127.0.0.1' : 'localhost') + ':' + location.port + '/webview-test/focus'")

private fun tellRaw(iframe: HTMLElement, message: String): Unit = js("{ iframe.contentWindow.postMessage(message, '*'); }")

/**
 * A document with no shim that hands over a port of its own, with no secret, tells on it of a press the user never
 * made, and takes the keyboard.
 */
private const val FORGED_PRESS = """<input id='i'><script>
var ch = new MessageChannel();
parent.postMessage({ webviewBridge: 1, secret: '', text: 'forged' }, '*', [ch.port2]);
setTimeout(function () {
  ch.port1.postMessage({ webviewPress: 1 });
  setTimeout(function () { document.getElementById('i').focus(); }, 50);
}, 200);
</script>"""

/** A document with no shim that hands over a port of its own, and focuses its input when told `'focus'`. */
private const val OWN_PORT_FOCUSING = """<input id='i'><script>
var ch = new MessageChannel();
parent.postMessage({ webviewBridge: 1, secret: '', text: 'own port' }, '*', [ch.port2]);
addEventListener('message', function (e) { if (e.data === 'focus') document.getElementById('i').focus(); });
</script>"""

private fun blurActive(): Unit = js("{ if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); }")

/**
 * The user's own field of the app has the keyboard, for real: the window is focused, not only its `activeElement` set.
 * A test that removed a focused frame leaves the window unfocused, and focusing an element that is already the active
 * one does nothing then.
 */
private fun focusApp(input: HTMLElement): Unit = js("{ input.blur(); input.focus(); }")

/** Two live demos, one above the other, each engine taking the page's synthetic presses for the user's. */
private class PairHarness {
    val first = IframeWebEngine(EngineConfig(WebProfile.Default), trustSyntheticInput = true)
    val second = IframeWebEngine(EngineConfig(WebProfile.Default), trustSyntheticInput = true)
    val firstEvents = ArrayList<EngineEvent>()
    val secondEvents = ArrayList<EngineEvent>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch { first.events.collect { firstEvents += it } }
        scope.launch { second.events.collect { secondEvents += it } }
    }

    suspend fun live(firstBody: String, secondBody: String) {
        for ((engine, body) in listOf(first to firstBody, second to secondBody)) {
            val content = WebContent.Html("<!doctype html><html><body style=\"margin:0\">$body$DRIVER</body></html>")
            engine.configure(PageSetup(true, false, false, false, null, setOf(content.origin.origin)))
            engine.load(content)
            engine.setVisible(true)
        }
        TestViewport.show {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(200.dp)) { first.Surface(Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxWidth().height(200.dp)) { second.Surface(Modifier.fillMaxSize()) }
            }
        }
        waitUntil("both first paints", 20_000) { EngineEvent.FirstPaint in firstEvents && EngineEvent.FirstPaint in secondEvents }
        pause(200)
    }

    fun tell(engine: IframeWebEngine, message: String) = tell(iframeIn(engine.frame.cut), message)

    suspend fun close() {
        TestViewport.clear()
        scope.cancel()
        first.release()
        second.release()
    }
}

/**
 * A page can't take the keyboard from the app on its own: keys the user types in the app would land in it. The
 * app's window has transient activation while the user types (a key press is activating input), so that alone can't
 * tell the user's focus from a page's. A demo's shim reports the user's presses in it; a page without one has only
 * the app's activation to go on.
 */
class FocusGuardTest {
    @Test fun aDemoCannotTakeTheKeyboardFromTheApp() = runTest(timeout = 1.minutes) {
        val input = appInput()
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        try {
            h.live("<input id='i'>")
            focusApp(input)
            assertTrue(isActiveElement(input))
            h.tell("""{"focus": "i"}""")
            pause(600)
            assertTrue(isActiveElement(input), "the app's field lost the keyboard to ${activeTag()}")
            assertTrue(h.events.none { it == EngineEvent.FocusChanged(true) }, "${h.events}")
        } finally {
            removeElementNow(input)
            h.close()
        }
    }

    /**
     * Compose's focused element is inside its canvas's shadow root, and `document.activeElement` names the shadow's
     * host, which can't take focus: the keyboard goes back to the element itself (web-check's focus mode, with real
     * typing into a Compose text field, found it given to the host, and kept by the demo).
     */
    @Test fun aDemoCannotTakeTheKeyboardFromAFieldInAShadowRoot() = runTest(timeout = 1.minutes) {
        val field = shadowField()
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        try {
            h.live("<input id='i'>")
            focusApp(field[1]!!)
            assertTrue(hasKeyboard(field[1]!!))
            h.tell("""{"focus": "i"}""")
            pause(600)
            assertTrue(hasKeyboard(field[1]!!), "the app's field lost the keyboard to ${activeTag()}")
            assertTrue(h.events.none { it == EngineEvent.FocusChanged(true) }, "${h.events}")
        } finally {
            removeElementNow(field[0]!!)
            h.close()
        }
    }

    /**
     * Compose's text field focuses its backing textarea again on its blur: when the app's window hears it lost the
     * keyboard, its document names the field as active already, and a `focus()` on it asks the browser for nothing
     * (web-check's focus mode, with real typing, saw every key go to the demo). The keyboard comes back all the same.
     * Karma's Chrome keeps these frames in its own process, where focus moves at once: this checks the field's own
     * blur handling, not the race between processes, which web-check's focus mode checks.
     */
    @Test fun aDemoCannotTakeTheKeyboardFromAFieldThatTakesItselfBack() = runTest(timeout = 1.minutes) {
        val field = shadowField(takesItselfBack = true)
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        try {
            h.live("<input id='i'>")
            focusApp(field[1]!!)
            assertTrue(hasKeyboard(field[1]!!))
            h.tell("""{"focus": "i"}""")
            pause(600)
            assertFalse(h.demoHasKeyboard(), "the demo has the keyboard")
            assertTrue(hasKeyboard(field[1]!!), "the app's field lost the keyboard to ${activeTag()}")
        } finally {
            stopTakingItselfBack(field[1]!!)
            removeElementNow(field[0]!!)
            h.close()
        }
    }

    /** The shim saw the user press in the page: the focus that follows is the user's. */
    @Test fun aPressInTheDemoLetsItTakeTheKeyboard() = runTest(timeout = 1.minutes) {
        val input = appInput()
        val h = ListHarness(trustSyntheticInput = true)
        try {
            h.live("<input id='i'>")
            focusApp(input)
            h.tell("""{"press": "i"}""")
            waitUntil("the page's focus") { EngineEvent.FocusChanged(true) in h.events }
            frames(2)
            assertEquals("iframe", activeTag())
        } finally {
            removeElementNow(input)
            h.close()
        }
    }

    /** The app's window has the keyboard, on nothing of its own (a click on the canvas): a page can't take it either. */
    @Test fun aDemoCannotTakeTheKeyboardWhenTheAppHasNoFocusedField() = runTest(timeout = 1.minutes) {
        val input = appInput()
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        try {
            h.live("<input id='i'>")
            focusApp(input)
            blurActive()
            assertEquals("body", activeTag())
            h.tell("""{"focus": "i"}""")
            pause(600)
            assertTrue(activeTag() != "iframe", "the page has the keyboard")
            assertTrue(h.events.none { it == EngineEvent.FocusChanged(true) }, "${h.events}")
        } finally {
            removeElementNow(input)
            h.close()
        }
    }

    /**
     * A page that takes the keyboard from another page the user is typing in gives it back to that page. The app's own
     * document hears nothing when the keyboard moves between two frames: the shims tell.
     */
    @Test fun aDemoCannotTakeTheKeyboardFromAnotherDemo() = runTest(timeout = 1.minutes) {
        val input = appInput()
        val typing = PairHarness()
        try {
            typing.live("<input id='i'>", "<input id='j'>")
            focusApp(input)
            typing.tell(typing.first, """{"press": "i"}""")
            waitUntil("the first page's focus") { EngineEvent.FocusChanged(true) in typing.firstEvents }
            typing.tell(typing.second, """{"focus": "j"}""")
            pause(600)
            assertTrue(isActiveElement(iframeIn(typing.first.frame.cut)), "the keyboard went to ${activeTag()}")
            assertTrue(typing.secondEvents.none { it == EngineEvent.FocusChanged(true) }, "${typing.secondEvents}")
            assertEquals(EngineEvent.FocusChanged(true), typing.firstEvents.last { it is EngineEvent.FocusChanged })
        } finally {
            removeElementNow(input)
            typing.close()
        }
    }

    /** A page of another site runs no shim: the app's transient activation is all there is to go on. */
    @Test fun aUrlPageTakesTheKeyboardOnlyWithTheAppsActivation() = runTest(timeout = 1.minutes) {
        val input = appInput()
        try {
            val inactive = ListHarness(trustSyntheticInput = false, userActivation = { false })
            try {
                inactive.live(WebContent.Url(focusPageUrl()))
                focusApp(input)
                tellRaw(iframeIn(inactive.engine.frame.cut), "focus")
                pause(600)
                assertTrue(isActiveElement(input), "the app's field lost the keyboard to ${activeTag()}")
                assertTrue(inactive.events.none { it == EngineEvent.FocusChanged(true) }, "${inactive.events}")
            } finally {
                inactive.close()
            }
            val active = ListHarness(trustSyntheticInput = false, userActivation = { true })
            try {
                active.live(WebContent.Url(focusPageUrl()))
                focusApp(input)
                tellRaw(iframeIn(active.engine.frame.cut), "focus")
                waitUntil("the page's focus") { EngineEvent.FocusChanged(true) in active.events }
            } finally {
                active.close()
            }
        } finally {
            removeElementNow(input)
        }
    }

    /**
     * A document a demo navigates to runs no shim, so no press of the user's in it is ever told. One that hands over a
     * port of its own, tells on it of a press it made up, then takes the keyboard (the review: a forged press opens the
     * guard), gives it back at once.
     */
    @Test fun aDocumentADemoNavigatesToCannotTakeTheKeyboardWithAPressItMadeUp() = runTest(timeout = 1.minutes) {
        val input = appInput()
        val h = ListHarness(trustSyntheticInput = false, userActivation = { false })
        try {
            h.live(navigatingDemo(dataUrlOf(FORGED_PRESS)))
            focusApp(input)
            h.tell("""{"go": true}""")
            pause(1_500)
            assertTrue(isActiveElement(input), "the app's field lost the keyboard to ${activeTag()}")
            assertTrue(h.events.none { it == EngineEvent.FocusChanged(true) }, "${h.events}")
        } finally {
            removeElementNow(input)
            h.close()
        }
    }

    /**
     * A `Url` page runs no shim, so whether it has the keyboard is the app's document's to say, even when a document in
     * its frame hands over a port of its own. Such a page was taken for one whose shim says when the keyboard leaves it,
     * and kept the keyboard as far as the engine knew.
     */
    @Test fun aUrlPageWithAPortOfItsOwnLosesTheKeyboardWhenTheAppTakesItBack() = runTest(timeout = 1.minutes) {
        val input = appInput()
        val h = ListHarness(trustSyntheticInput = false, userActivation = { true })
        try {
            h.live(WebContent.Url(focusPageUrl()))
            setSrc(iframeIn(h.engine.frame.cut), dataUrlOf(OWN_PORT_FOCUSING))
            waitUntil("its handshake") { h.events.any { it is EngineEvent.PageMessage && it.json == "own port" } }
            focusApp(input)
            tellRaw(iframeIn(h.engine.frame.cut), "focus")
            waitUntil("the page's focus") { EngineEvent.FocusChanged(true) in h.events }
            focusApp(input)
            waitUntil("the page's losing it") { h.events.last { it is EngineEvent.FocusChanged } == EngineEvent.FocusChanged(false) }
            assertTrue(isActiveElement(input), "the app's field has the keyboard")
        } finally {
            removeElementNow(input)
            h.close()
        }
    }
}
