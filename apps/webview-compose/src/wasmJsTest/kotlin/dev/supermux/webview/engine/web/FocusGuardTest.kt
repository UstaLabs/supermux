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

private fun activeTag(): String = js("String(document.activeElement && document.activeElement.localName)")

/** A page of another site on the test server that focuses its input when told `'focus'` (karma.config.d). */
private fun focusPageUrl(): String =
    js("location.protocol + '//' + (location.hostname === 'localhost' ? '127.0.0.1' : 'localhost') + ':' + location.port + '/webview-test/focus'")

private fun tellRaw(iframe: HTMLElement, message: String): Unit = js("{ iframe.contentWindow.postMessage(message, '*'); }")

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
}
