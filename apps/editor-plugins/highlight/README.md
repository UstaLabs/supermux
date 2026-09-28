# editor-plugins/highlight

Syntax highlighting as a plugin (spec §7: "highlight: the language set from §5.4, including
injections"): editor-syntax's `Syntax` extension, and the **host side** every editor needs around
it, the `SyntaxHost`, which used to be hand-wired in `editor-sample`.

```kotlin
val view = rememberEditorView { EditorState.create(text, extensions = extensionOf(highlight("kotlin"), basics(), history())) }
rememberSyntaxHost(view, backend, widgets = registry)   // Compose hosts
Editor(view, widgets = registry)
```

- **`highlight(language)`** (null: plain text) is data only: `Syntax.extension(language)` (the spans
  field, mapped through every edit, and editor-core's `tokenContextFacet`, which bracket matching
  reads) plus the compartment the "syntax off" panel appears in. No native handle ever sits in the
  state, which is why the backend is NOT a parameter of the extension (the plan's
  `highlight(language, backend)`): it belongs to the host.
- **`SyntaxHost(view, backend, registry, scope, limits, hop)`** owns ONE `SyntaxWorker` per
  `EditorView`:
  - every transaction's state goes to the worker (a view listener), and so does a replaced state
    (`view.setState`, another file shown in the same view: `EditorView.addReplaceListener`); the
    worker takes a new syntax field instance (its epoch) as a new document and starts over;
  - the worker's results hop onto the UI thread in order (`hop`, by default a launch in the UI
    `scope`) and are dropped once the host is closed;
  - `start()` feeds `Syntax.setViewport` from `view.viewport` (the surface's laid-out lines), or a
    host calls `onViewport` itself from `Editor(onViewport = …)` (`start(followViewport = false)`);
  - `precompile()` compiles the language's queries first (and those its documents always inject:
    Markdown's inline grammar), one task each: on the web a query compile is one uninterruptible
    call (M3a's cold start);
  - when syntax turns off for the document (`Syntax.isOff`: too big, a line too long, a parse over
    budget, a dead wasm runtime) it shows the `syntax-off` panel (bottom; `registerWidgets` gives
    its content, a one-line notice in the editor's theme) and sets `isOff` (snapshot state, for a
    status line);
  - `close()` stops the worker, which frees every native parser and tree on its own thread
    (`join()` waits).
- **`rememberSyntaxHost(view, backend, registry, widgets, limits)`**: a host for as long as the
  composition shows `view`: precompiled, started, closed when the view changes or leaves.
- **`precompileSyntax(backend, registry, language, onCompile, yieldBetween)`**: the precompile on its
  own, for a host that times it (the sample's web cold-start phases).

**Theme tokens.** `EditorTheme.light` / `dark` colour all 29 `tok-*` classes of
`TokenClasses.ALL` (editor-compose's test) and every class the syntax layer actually emits for a
spread of languages (this module's test).

Tests: `./gradlew :editor-plugins:highlight:jvmTest`, over the real worker and the native binding:
open, type, spans arrive; closing frees every native tree (`SyntaxDebug.liveTrees()` back to what it
was); a document switch resets the worker (Kotlin, then Markdown in the same view, no Kotlin span
left); a document too big shows the syntax-off panel; bracket matching skips a `(` inside a
real string; every emitted class is coloured in both themes; `rememberSyntaxHost` feeds the
surface's viewport and frees the trees when the editor leaves the composition.
