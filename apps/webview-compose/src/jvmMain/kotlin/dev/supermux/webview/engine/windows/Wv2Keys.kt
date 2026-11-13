package dev.supermux.webview.engine.windows

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Page-first keys (#122), as in Chrome: an accelerator goes to the page first, and the app gets it only if the page left
 * it unused (`preventDefault` not called). A key script in an isolated world says which, through a CDP binding only that
 * world has, so the page can neither see nor forge it. The app keeps, before any page, what Chrome keeps from pages
 * (new and closed tabs and windows, tab n, next and previous tab) plus its own splits and settings, Alt+F4 and every
 * Windows-key chord.
 */
internal object Wv2Keys {
    const val WORLD = "wvKeys"
    const val BINDING = "__wvKeyVerdict"

    /** [flags]: 1 Ctrl, 2 Shift, 4 Alt, 8 Win (the glue's). */
    fun pageFirst(vk: Int, flags: Int): Boolean {
        val ctrl = flags and 1 != 0
        val alt = flags and 4 != 0
        val win = flags and 8 != 0
        val function = vk in 0x70..0x87
        if (win || (!ctrl && !alt && !function)) return false
        if (alt && vk == 0x73) return false // Alt+F4
        if (ctrl && !alt && vk in setOf('T'.code, 'W'.code, 'N'.code)) return false
        if (ctrl && vk in '1'.code..'9'.code) return false
        if (ctrl && vk in setOf(0x09, 0x21, 0x22, 0xDC, 0xBC)) return false // Tab, PgUp, PgDn, \, comma
        return true
    }

    /**
     * The key script: after a key's dispatch, whether the page took it, and which key ("used:75"). A modifier's own keydown
     * is skipped: its answer would otherwise arrive first and speak for the shortcut (seen on the VM). Read the binding
     * lazily: it may land after the script.
     */
    private const val SCRIPT = "(function(){addEventListener('keydown',function(e){" +
        "if(!(e.ctrlKey||e.altKey||/^F[0-9]+${'$'}/.test(e.key)))return;" +
        "if(/^(Control|Shift|Alt|Meta|AltGraph)${'$'}/.test(e.key))return;" +
        "setTimeout(function(){var p=window.$BINDING;if(p)p((e.defaultPrevented?'used':'unused')+':'+e.keyCode);},0);},true);})();"

    /** The script's answer as (used, the key's virtual-key code), or null if it isn't one. */
    fun verdict(payload: String): Pair<Boolean, Int>? {
        val (word, code) = payload.split(':').takeIf { it.size == 2 } ?: return null
        val vk = code.toIntOrNull() ?: return null
        return when (word) {
            "used" -> true to vk
            "unused" -> false to vk
            else -> null
        }
    }

    val scriptParams: String = buildJsonObject {
        put("source", JsonPrimitive(SCRIPT))
        put("worldName", JsonPrimitive(WORLD))
        put("runImmediately", JsonPrimitive(true))
    }.toString()

    val bindingParams: String = buildJsonObject {
        put("name", JsonPrimitive(BINDING))
        put("executionContextName", JsonPrimitive(WORLD))
    }.toString()
}
