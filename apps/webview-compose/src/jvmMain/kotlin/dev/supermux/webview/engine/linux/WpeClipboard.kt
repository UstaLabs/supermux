package dev.supermux.webview.engine.linux

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent

/**
 * The system's clipboard for WebKit's pages, text only. WPE keeps a clipboard of its own otherwise, shared with no other
 * app. The glue's clipboard ([WpeNative.clipboard], `wv_platform.c`) answers a page's paste synchronously on the WebKit
 * thread, which never waits on the JVM, so it is told what the system's clipboard holds beforehand: when a page's
 * surface takes the focus, and before a paste shortcut reaches the page ([sync]). A page's copy reaches the system's
 * clipboard at once ([onPageCopied]). Main thread.
 * @param system the clipboard: AWT's system clipboard, or null where there is none (headless).
 * @param toWebKit tells the glue what it holds: its text, or null for none.
 */
internal class WpeClipboard(private val system: () -> Clipboard?, private val toWebKit: (String?) -> Unit) {
    /** What WebKit was last told, or holds from a page's own copy. */
    private var told: String? = null
    private var toldOnce = false

    /** Tells WebKit what the system's clipboard holds now, if that changed since. */
    fun sync() {
        val text = try {
            system()?.getData(DataFlavor.stringFlavor) as? String
        } catch (e: Exception) {
            null
        }
        if (toldOnce && text == told) return
        told = text
        toldOnce = true
        toWebKit(text)
    }

    /**
     * A page copied [text]: the system's clipboard takes it, so other apps paste it. WebKit keeps the page's own copy
     * (its HTML too): it isn't told back the text alone.
     */
    fun onPageCopied(text: String) {
        told = text
        toldOnce = true
        try {
            system()?.setContents(StringSelection(text), null)
        } catch (e: Exception) {
            // Another app holds the clipboard, or there is none: the copy stays the pages'.
        }
    }

    companion object {
        /** The process's: AWT's system clipboard and the glue. */
        val System: WpeClipboard by lazy {
            WpeClipboard({ runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() }, WpeNative::clipboard)
        }

        /** Whether an AWT key pastes: Ctrl+V (with Shift too), or Shift+Insert. */
        fun isPaste(virtualKey: Int, ctrl: Boolean, shift: Boolean): Boolean =
            (ctrl && virtualKey == KeyEvent.VK_V) || (shift && !ctrl && virtualKey == KeyEvent.VK_INSERT)
    }
}
