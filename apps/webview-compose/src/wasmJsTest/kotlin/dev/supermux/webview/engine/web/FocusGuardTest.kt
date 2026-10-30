package dev.supermux.webview.engine.web

import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EngineEvent
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
        h.live("<input id='i'>")
        focusElement(input)
        assertTrue(isActiveElement(input))
        h.tell("""{"focus": "i"}""")
        pause(600)
        assertTrue(isActiveElement(input), "the app's field lost the keyboard to ${activeTag()}")
        assertTrue(h.events.none { it == EngineEvent.FocusChanged(true) }, "${h.events}")
        removeElementNow(input)
        h.close()
    }

    /** The shim saw the user press in the page: the focus that follows is the user's. */
    @Test fun aPressInTheDemoLetsItTakeTheKeyboard() = runTest(timeout = 1.minutes) {
        val input = appInput()
        val h = ListHarness(trustSyntheticInput = true)
        h.live("<input id='i'>")
        focusElement(input)
        h.tell("""{"press": "i"}""")
        waitUntil("the page's focus") { EngineEvent.FocusChanged(true) in h.events }
        frames(2)
        assertEquals("iframe", activeTag())
        removeElementNow(input)
        h.close()
    }

    /** A page of another site runs no shim: the app's transient activation is all there is to go on. */
    @Test fun aUrlPageTakesTheKeyboardOnlyWithTheAppsActivation() = runTest(timeout = 1.minutes) {
        val input = appInput()
        val inactive = ListHarness(trustSyntheticInput = false, userActivation = { false })
        inactive.live(WebContent.Url(focusPageUrl()))
        focusElement(input)
        tellRaw(iframeIn(inactive.engine.frame.cut), "focus")
        pause(600)
        assertTrue(isActiveElement(input), "the app's field lost the keyboard to ${activeTag()}")
        assertTrue(inactive.events.none { it == EngineEvent.FocusChanged(true) }, "${inactive.events}")
        inactive.close()

        val active = ListHarness(trustSyntheticInput = false, userActivation = { true })
        active.live(WebContent.Url(focusPageUrl()))
        focusElement(input)
        tellRaw(iframeIn(active.engine.frame.cut), "focus")
        waitUntil("the page's focus") { EngineEvent.FocusChanged(true) in active.events }
        removeElementNow(input)
        active.close()
    }
}
