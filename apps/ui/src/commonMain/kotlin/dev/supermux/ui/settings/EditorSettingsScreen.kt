// Editor & terminal (#97 spec §2.2, §11): this device's editor and terminal choices, Coding's middle section. Today's
// Editor screen restyled in place with the Settings kit, without the language servers (they are Code intelligence
// now), and with the terminal's font size, which before could only be pinched or keyed in a terminal.
//
// Every row writes `UiPrefs` the moment it changes: the keys `WebCodeEditor`/`DiffView`/the terminal already read.
package dev.supermux.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.prefs.EDITOR_ACCESSORY_BAR_DEFAULT
import dev.supermux.ui.prefs.EDITOR_DIFF_SIDE_BY_SIDE_DEFAULT
import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.ui.prefs.EDITOR_FONT_MAX
import dev.supermux.ui.prefs.EDITOR_FONT_MIN
import dev.supermux.ui.prefs.EDITOR_LINE_WRAP_DEFAULT
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.TERMINAL_FONT_DEFAULT
import dev.supermux.ui.prefs.TERMINAL_FONT_MAX
import dev.supermux.ui.prefs.TERMINAL_FONT_MIN
import dev.supermux.ui.settings.kit.KitStepperRow
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.keys.KeyOs
import dev.supermux.ui.keys.currentKeyOs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.supermux.ui.i18n.localized

/**
 * Wrap long lines · Editor font size · Terminal font size · Side-by-side diffs · and, on a touch device, Keys above
 * the keyboard. Device rows only: they work while the machine is offline.
 */
@Composable
fun EditorSection(modifier: Modifier = Modifier) {
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val lineWrap by prefs.editorLineWrap.collectAsState(EDITOR_LINE_WRAP_DEFAULT)
    val fontSize by prefs.editorFontSize.collectAsState(EDITOR_FONT_DEFAULT)
    val terminalFontSize by prefs.terminalFontSize.collectAsState(TERMINAL_FONT_DEFAULT)
    val sideBySide by prefs.editorDiffSideBySide.collectAsState(EDITOR_DIFF_SIDE_BY_SIDE_DEFAULT)
    val accessoryBar by prefs.editorAccessoryBar.collectAsState(EDITOR_ACCESSORY_BAR_DEFAULT)
    val pointer = LocalPointerAvailable.current

    Column(modifier.fillMaxWidth().testTag("editor_settings_screen")) {
        KitToggleRow(
            label = localized(Str.editor_settings_wrap),
            checked = lineWrap,
            onCheckedChange = { on -> scope.launch { prefs.putEditorLineWrap(on) } },
            modifier = Modifier.kitAnchor("wrap").testTag("editor_wrap_switch"),
            description = localized(Str.editor_settings_wrap_description),
        )
        // Each step moves the CURRENT stored size rather than the one on screen: the shown value catches up a frame
        // after the write, so two fast taps that both saw the same frame would otherwise write the same size twice
        // and lose a step. The puts clamp, so neither end can be overrun.
        KitStepperRow(
            label = localized(Str.editor_settings_editor_font),
            value = fontSize,
            onChange = { next ->
                val step = next - fontSize
                scope.launch { prefs.putEditorFontSize(prefs.editorFontSize.first() + step) }
            },
            range = EDITOR_FONT_MIN..EDITOR_FONT_MAX,
            modifier = Modifier.kitAnchor("editor-font").testTag("coding_editor-font"),
            tag = "editor_font",
        )
        KitStepperRow(
            label = localized(Str.editor_settings_terminal_font),
            value = terminalFontSize,
            onChange = { next ->
                val step = next - terminalFontSize
                scope.launch { prefs.putTerminalFontSize(prefs.terminalFontSize.first() + step) }
            },
            range = TERMINAL_FONT_MIN..TERMINAL_FONT_MAX,
            modifier = Modifier.kitAnchor("terminal-font").testTag("coding_terminal-font"),
            description = if (pointer) terminalZoomHintText(currentKeyOs) else localized(Str.editor_settings_terminal_pinch),
            tag = "terminal_font",
        )
        // The Changes pane's per-file diff: inline (default) or side by side. The pane's own toggle writes the same value.
        KitToggleRow(
            label = localized(Str.editor_settings_side_by_side),
            checked = sideBySide,
            onCheckedChange = { on -> scope.launch { prefs.putEditorDiffSideBySide(on) } },
            modifier = Modifier.kitAnchor("side-by-side").testTag("editor_side_by_side_switch"),
            description = localized(Str.editor_settings_side_by_side_description),
        )
        // The key row over a soft keyboard (Tab, arrows, undo/redo, find): only where fingers type.
        if (!pointer) {
            KitToggleRow(
                label = localized(Str.editor_settings_accessory_bar),
                checked = accessoryBar,
                onCheckedChange = { on -> scope.launch { prefs.putEditorAccessoryBar(on) } },
                modifier = Modifier.kitAnchor("accessory-bar").testTag("editor_accessory_bar_switch"),
                description = localized(Str.editor_settings_accessory_bar_description),
            )
        }
    }
}

/** The terminal's zoom keys as [os] writes them (the terminal's own rule: ⌘ on Apple, Ctrl elsewhere), in English for the tests. */
internal fun terminalZoomHint(os: KeyOs): String =
    if (os == KeyOs.Apple) "Also ⌘+ and ⌘− in a terminal." else "Also Ctrl+Plus and Ctrl+Minus in a terminal." // i18n-ok: tests' English twin of terminalZoomHintText

/** [terminalZoomHint] in the app's language: what the row shows. */
@Composable
internal fun terminalZoomHintText(os: KeyOs): String =
    localized(if (os == KeyOs.Apple) Str.editor_settings_zoom_apple else Str.editor_settings_zoom_other)
