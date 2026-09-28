package dev.supermux.editor.plugins.view

import dev.supermux.editor.compose.EditorThemeMode
import dev.supermux.editor.compose.EditorZoom
import dev.supermux.editor.compose.fontSizeFacet
import dev.supermux.editor.compose.indentUnitFacet
import dev.supermux.editor.compose.lineNumbersFacet
import dev.supermux.editor.compose.lineWrappingFacet
import dev.supermux.editor.compose.tabSizeFacet
import dev.supermux.editor.compose.themeModeFacet
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.Compartment
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.StateEffect
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import kotlin.math.roundToInt

/**
 * The editor's view settings, as DATA: what `:ui`'s editor settings screen and its prefs hold
 * (`UiPrefs`: font size 10–24 px, default 13; wrap long lines, default on), plus the tab size, the
 * indent unit, the line numbers and the theme.
 */
data class EditorSettings(
    /** sp (px in the web editor's terms), [EditorZoom.MIN]..[EditorZoom.MAX]. */
    val fontSize: Float = EditorZoom.DEFAULT,
    val lineWrap: Boolean = true,
    val tabSize: Int = 4,
    val indentUnit: String = "    ",
    val showLineNumbers: Boolean = true,
    val theme: EditorThemeMode = EditorThemeMode.SYSTEM,
) {
    init {
        require(tabSize in 1..16) { "tab size $tabSize" }
        require(indentUnit.isNotEmpty() && indentUnit.all { it == ' ' || it == '\t' }) { "indent unit must be spaces or a tab" }
    }
}

/**
 * View settings as facets in compartments (spec §7 "view settings"): [extension] puts an
 * [EditorSettings] into the state; [reconfigure] / [apply] change it at run time with ONE
 * transaction of compartment effects (no edit, no userEvent: the document, the selection, the undo
 * history and the folds stay as they are). The surface reads them from the state (editor-compose's
 * `lineWrappingFacet`, `fontSizeFacet`, `themeModeFacet`, `tabSizeFacet`, `indentUnitFacet`,
 * `lineNumbersFacet`), overriding `Editor(...)`'s parameters.
 *
 * **Zoom.** The keys (`Mod +` / `Mod −` / `Mod 0`) and a pinch zoom the view; `Editor(onFontSize =
 * [fontSizeReporter])` rounds the size to a whole px (10–24, like today's editor, which rounds and
 * clamps every step: `clampFont`), hands it to the host to persist per app, and puts it in the
 * state's settings. A size the host sets ([apply]) replaces a zoom; `Mod 0` goes back to 13.
 */
object ViewSettings {
    private val fontSize = Compartment("view.fontSize")
    private val lineWrap = Compartment("view.lineWrap")
    private val tabSize = Compartment("view.tabSize")
    private val indentUnit = Compartment("view.indentUnit")
    private val lineNumbers = Compartment("view.lineNumbers")
    private val theme = Compartment("view.theme")
    private val data = Compartment("view.settings")

    private val settingsFacet: Facet<EditorSettings, EditorSettings?> = Facet.define("view.settings") { it.firstOrNull() }

    /** The settings, for a new state. */
    fun extension(settings: EditorSettings = EditorSettings()): Extension = extensionOf(
        fontSize.of(fontSizeFacet.of(settings.fontSize)),
        lineWrap.of(lineWrappingFacet.of(settings.lineWrap)),
        tabSize.of(tabSizeFacet.of(settings.tabSize)),
        indentUnit.of(indentUnitFacet.of(settings.indentUnit)),
        lineNumbers.of(lineNumbersFacet.of(settings.showLineNumbers)),
        theme.of(themeModeFacet.of(settings.theme)),
        data.of(settingsFacet.of(settings)),
    )

    /** The effects that change a state's settings to [settings] (only the compartments that differ). */
    fun reconfigure(state: EditorState, settings: EditorSettings): List<StateEffect<*>> {
        val old = current(state)
        return buildList {
            if (old?.fontSize != settings.fontSize) add(fontSize.reconfigure(fontSizeFacet.of(settings.fontSize)))
            if (old?.lineWrap != settings.lineWrap) add(lineWrap.reconfigure(lineWrappingFacet.of(settings.lineWrap)))
            if (old?.tabSize != settings.tabSize) add(tabSize.reconfigure(tabSizeFacet.of(settings.tabSize)))
            if (old?.indentUnit != settings.indentUnit) add(indentUnit.reconfigure(indentUnitFacet.of(settings.indentUnit)))
            if (old?.showLineNumbers != settings.showLineNumbers) add(lineNumbers.reconfigure(lineNumbersFacet.of(settings.showLineNumbers)))
            if (old?.theme != settings.theme) add(theme.reconfigure(themeModeFacet.of(settings.theme)))
            if (old != settings) add(data.reconfigure(settingsFacet.of(settings)))
        }
    }

    /** Change [target]'s settings to [settings] now (nothing when they are the same). */
    fun apply(target: CommandTarget, settings: EditorSettings) {
        val effects = reconfigure(target.state, settings)
        if (effects.isNotEmpty()) target.dispatch(TransactionSpec(effects = effects))
    }

    /** Change one setting: `update(view) { it.copy(lineWrap = false) }`. */
    fun update(target: CommandTarget, change: (EditorSettings) -> EditorSettings) =
        apply(target, change(current(target.state) ?: EditorSettings()))

    /** [state]'s settings, or null when it has none (no [extension]). */
    fun current(state: EditorState): EditorSettings? = state.facet(settingsFacet)

    /** A size as today's editor keeps it: a whole px, [EditorZoom.MIN]..[EditorZoom.MAX] (CM6's clampFont). */
    fun clampFontSize(size: Float): Int =
        if (size.isNaN()) EditorZoom.DEFAULT.toInt() else size.roundToInt().coerceIn(EditorZoom.MIN.toInt(), EditorZoom.MAX.toInt())

    /**
     * The `Editor(onFontSize = …)` callback: every zoom (a key, a pinch once the fingers lift) is
     * rounded and clamped ([clampFontSize]), handed to [persist] (the host keeps it per app: `:ui`'s
     * `putEditorFontSize`) when it changed, and put into [target]'s settings.
     */
    fun fontSizeReporter(target: CommandTarget, persist: (Int) -> Unit): (Float) -> Unit = { size ->
        val px = clampFontSize(size)
        val now = current(target.state)
        if (now == null || now.fontSize != px.toFloat()) {
            persist(px)
            update(target) { it.copy(fontSize = px.toFloat()) }
        }
    }
}

/** [ViewSettings.extension]. */
fun viewSettings(settings: EditorSettings = EditorSettings()): Extension = ViewSettings.extension(settings)
