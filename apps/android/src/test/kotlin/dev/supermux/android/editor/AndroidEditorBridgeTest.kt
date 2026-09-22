package dev.supermux.android.editor

import android.webkit.JavascriptInterface
import dev.supermux.ui.editor.engine.bridgeShimJs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two halves of ONE bridge must declare the same callbacks.
 *
 * The committed cm6 bundle is a single file shipped to both apps. It calls
 * `window.AndroidEditor.<name>(…)`; desktop DEFINES those globals in the page with the shared
 * [bridgeShimJs] (each routing through its message router), while Android answers them with a real
 * `@JavascriptInterface` object, [AndroidEditorBridge]. So the shim's name list IS the bundle's
 * contract, and a name Android does not declare is a silently dead in-editor affordance on phones —
 * no crash, no compile error, just a button in CodeMirror that does nothing. (That is exactly how
 * Android shipped without the in-editor comment threads desktop had.)
 *
 * String-level and reflection-only: no WebView, no Robolectric.
 */
class AndroidEditorBridgeTest {

    /** Every `post("<fn>", …)` the shim emits — the AndroidEditor members plus the LSP hook. */
    private val shimCallbacks: List<String> =
        Regex("""post\("(\w+)"""").findAll(bridgeShimJs("q")).map { it.groupValues[1] }.distinct().toList()

    private val declared: List<String> =
        AndroidEditorBridge::class.java.declaredMethods
            .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
            .map { it.name }

    @Test
    fun `the shim declares the callbacks we think it does`() {
        // A guard on the guard: if the regex ever stops matching, the coverage assertion below
        // would pass vacuously.
        assertTrue(shimCallbacks.size >= 12, "expected the full bridge, got $shimCallbacks")
        assertTrue("onChange" in shimCallbacks && "onCommentSubmit" in shimCallbacks, "$shimCallbacks")
        assertTrue("lspOut" in shimCallbacks, "the LSP hook is part of the same bridge")
    }

    @Test
    fun `android declares a JavascriptInterface method for every shim callback`() {
        val missing = shimCallbacks - declared.toSet()
        assertEquals(
            emptyList(), missing,
            "AndroidEditorBridge is missing @JavascriptInterface methods for $missing — those cm6 " +
                "callbacks would be silently dead on Android",
        )
    }

    @Test
    fun `every android bridge method is one the bundle actually calls`() {
        val extra = declared - shimCallbacks.toSet()
        assertEquals(emptyList(), extra, "dead bridge methods nothing in cm6 calls: $extra")
    }
}
