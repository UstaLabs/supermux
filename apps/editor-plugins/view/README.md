# editor-plugins/view

View settings as a plugin (spec §7: "view settings: font zoom (pinch, `Mod +/−/0`, 10–24px,
persisted per app) and line wrap"): the settings are DATA, put into the state as editor-compose's
facets in compartments, so the host changes them while the editor is open with one transaction.

```kotlin
val view = EditorView(EditorState.create(text, extensions = extensionOf(viewSettings(EditorSettings(fontSize = 15f, lineWrap = true)), ...)))
Editor(view, onFontSize = ViewSettings.fontSizeReporter(view) { px -> prefs.putEditorFontSize(px) })
ViewSettings.update(view) { it.copy(lineWrap = false) }   // the settings screen changed it
```

- **`EditorSettings(fontSize, lineWrap, tabSize, indentUnit, showLineNumbers, theme)`**, defaults
  as today's editor: 13 px, wrap ON (`UiPrefs.EDITOR_LINE_WRAP_DEFAULT`), tab 4, four spaces, line
  numbers, and `theme = null`: the host's `Editor(theme)` as it is. A mode (LIGHT, DARK, SYSTEM)
  picks the host's `Editor(lightTheme / darkTheme)`, else swaps only the palette under the host's
  theme (its own classes, `diff-add`, `search-match`, stay).
- **`viewSettings(settings)`** puts them in the state: `fontSizeFacet`, `lineWrappingFacet`,
  `tabSizeFacet`, `indentUnitFacet`, `lineNumbersFacet`, `themeModeFacet` (editor-compose), each in
  its own compartment. The surface reads them from the state and they OVERRIDE the `Editor(...)`
  parameters (`lineWrap`, `showLineNumbers`, the theme's palette; the theme's font stays).
- **`ViewSettings.apply(view, settings)` / `update(view) { … }`** reconfigure only the compartments
  that differ, in ONE transaction with no edit and no userEvent: the document, the selection, the
  undo history and the folds stay. A new tab size relayouts; a new theme mode repaints with
  `EditorTheme.light` / `dark`; wrap and line numbers change at once.
- **Zoom.** `Mod +` / `Mod −` / `Mod 0` and a pinch zoom the view (editor-compose). With
  `Editor(onFontSize = ViewSettings.fontSizeReporter(view, persist))`, every zoom is rounded to a
  whole px and clamped to 10–24 (`clampFontSize`, today's `clampFont`), handed to `persist` when it
  changed (the host keeps it per app), and put into the state's settings. A size the host sets
  replaces the user's zoom; `Mod 0` goes back to 13 (today's `FONT_DEFAULT`), even over a stored size.
- `ViewSettings.current(state)` reads them back.

**M5's mapping from `:ui`** (`EditorPanel.kt`, `EditorSettingsScreen.kt`, `UiPrefs.kt`): the panel's
`prefs.editorLineWrap` / `prefs.editorFontSize` seed `EditorSettings(lineWrap, fontSize)`; its
`onFontSize = { px -> prefs.putEditorFontSize(px) }` becomes `fontSizeReporter(view) { px -> scope.launch
{ prefs.putEditorFontSize(px) } }`; the settings screen's switch and stepper keep writing the prefs,
and the panel's `collectAsState` of them calls `ViewSettings.update(view) { it.copy(…) }`. Today's
font-size badge (a "15px" chip for 900 ms after a zoom) is not part of the editor; M5 can show it
from the same callback.

**Deliberate differences from today's CM6 editor.** A pinch is continuous and rounds to a whole px
only when the fingers lift (CM6 rounds each step). The size persists through the callback as an
integer px, as before.

Tests: `./gradlew :editor-plugins:view:jvmTest` (commonTest: the facets, a run-time reconfigure
keeping the document, selection, undo and folds, only changed compartments, clamping, the callback;
jvmTest: `Mod =` and `Mod 0` in a composed Editor persisted through the callback, and a host
setting shown at once). The surface side (wrap, tab size, theme mode and font size reconfigured
live) is editor-compose's `PluginFacetsTest`.
