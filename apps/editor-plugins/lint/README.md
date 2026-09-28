# editor-plugins/lint

CM6's `@codemirror/lint` in Kotlin, on the public API: diagnostics as data, shown as squiggles,
gutter markers, a hover tooltip and a panel. The LSP plugin feeds it from `publishDiagnostics`; any
linter can.

```kotlin
val view = EditorView(EditorState.create(text, extensions = extensionOf(lint(), history(), fold())))
val registry = WidgetRegistry().also { Lint.registerWidgets(it) }     // tooltip:lint, panel:lint
view.dispatch(Lint.setDiagnostics(view.state, listOf(Diagnostic(10, 13, Severity.ERROR, "unknown name", source = "kotlin"))))
```

- **Data**: `Diagnostic(from, to, severity, message, source, actions)`, `Severity` HINT < INFO <
  WARNING < ERROR. `Lint.setDiagnostics(state, list)` is the transaction that REPLACES them (CM6's);
  they live in `Lint.field` as a `RangeSet` and move with every edit (typing inside one grows it, at
  its edges does not; one whose text was deleted goes; one whose text was replaced whole stays over the
  replacement until the linter answers again). `Lint.diagnostics(state)` (document order),
  `Lint.at(state, from, to)` (worst first).
- **Marks**: `lint-error`, `lint-warning`, `lint-info`, `lint-hint` on the range (a zero-length one on
  the character after it). editor-compose draws them as squiggles: `EditorTheme.squiggles`
  (`SquiggleStyle(color, dotted)`, `EditorTheme.lintSquiggles(...)`; wavy, hints dotted), per row of
  the text, in the layout pass's frame.
- **Gutter**: the `lint` column, one marker per line with its worst severity (`lint-error`,
  `lint-warning`, else `lint-info`), the messages as its tooltip (a screen reader's label). A click or
  **tap** on it shows the line's diagnostics in the tooltip: touch has no hover.
- **Tooltip** (`tooltip:lint`, a `hoverTooltip` with id `lint`): the diagnostics under the mouse,
  worst first, each with its message, its source and its actions as buttons. A tap on an action runs
  it WITHOUT taking the focus (`detectTapGestures`).
- **Actions**: `DiagnosticAction(name) { target, from, to -> }`, the source's callback (the LSP
  plugin's code actions). `Lint.runAction` gives it a target whose edits carry userEvent
  **`edit.codeAction`** unless they name one: recorded (one undo step), policed, dropped when read-only.
- **Keys**: `F8` next diagnostic (CM6's `lintKeymap`), `Shift-F8` previous (VS Code's; CM6 binds none),
  `Mod-Shift-m` the panel. Next / previous select the diagnostic's range (scrolled into view: a fold
  holding it opens through `revealFacet`), wrap around, and show its tooltip.
- **Panel** (`panel:lint`, at the bottom): "Problems (N)", one row per diagnostic (severity,
  `line:column`, message, source). Opening it gives it the focus; `ArrowUp` / `ArrowDown` move, `Enter`
  or a tap goes to the diagnostic and gives the focus back to the editor, `Escape` or × closes it.
  Rows are 44 dp on touch.

**Deliberate differences from CM6**: no lint sources run by the plugin itself (`linter(source)`,
CM6's delay-driven runner: a source dispatches `setDiagnostics`; the LSP plugin is push-based
anyway); no `markerFilter` / `tooltipFilter`; `Shift-F8` bound; a gutter marker's tap shows the
tooltip.

Tests: `./gradlew :editor-plugins:lint:jvmTest` (commonTest: mapping through edits, a replacing
update, marks and markers by severity, F8 / Shift-F8 wrapping with the tooltip, the panel and a
diagnostic in a fold, an action as one `edit.codeAction` undo step, the hover source; jvmTest: F8 then
a tap on the tooltip's action, the panel by keys and taps) and `:editor-plugins:lint:iosSimulatorArm64Test`.
