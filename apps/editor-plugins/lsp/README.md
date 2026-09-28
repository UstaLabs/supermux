# editor-plugins/lsp

A Language Server Protocol client on the public API: CM6's `@codemirror/lsp-client` (today's
`cm6-entry.mjs` feature set) in Kotlin, on the tooltip layer, `editor-plugins/autocomplete` and
`editor-plugins/lint`.

```kotlin
val client = LspClient(transport, scope, LspClientConfig(rootUri = "file:///work", onNavigate = { uri, range -> host.open(uri, range) }))
val view = EditorView(EditorState.create(text, extensions = extensionOf(
    autocompletion(), lint(), history(), client.plugin("file:///work/main.kt", "kotlin"),
)))
val registry = WidgetRegistry().also { Autocomplete.registerWidgets(it); Lint.registerWidgets(it); client.registerWidgets(it) }
Editor(view, widgets = registry)
// later: client.close()   (didClose, shutdown, exit)
```

## The transport (the M5 seam)

```kotlin
interface LspTransport { suspend fun send(message: String); val incoming: Flow<String>; val status: StateFlow<LspConnState> }
```
One JSON-RPC message per string, no `Content-Length` framing (the broker's `lsp` channel frames on
the server side, `src/core/lsp/framing.ts`). **M5** adapts `:ui`'s `LspBridge` to it: `send` =
`rpcOut(serverId, message)`, `incoming` = `pumpRpcIn` filtered by session and server, `status` from
`lspStatus` / `open` (`ready` → CONNECTED, `error` / `exited` → DISCONNECTED). Also here:
`ProcessLspTransport` (jvm, the desktop): a server process over stdio with the framing; and
`:editor-plugins:lsp-fake`'s `FakeLspServer`, an in-process toy-language server (tests, the sample;
never a production host).

## Lifecycle and sync

- **initialize** sends CM6's client capabilities plus `general.positionEncodings: ["utf-16", "utf-8",
  "utf-32"]` (utf-16 first: the editor's unit, so a conversion is the Rope's line start, O(log n),
  plus the character); the server's `positionEncoding` is honoured (utf-8 / utf-32 count along the
  line). Then `initialized`, then `didOpen` for every document a view shows (`languageId` is the
  host's: it maps its syntax registry's language, e.g. `Syntax` "kotlin" → `kotlin`).
- **didChange**: the edits since the last sync compose into ONE change set, sent 50 ms after the last
  edit (`syncDelayMs`) and always right before a request; incremental ranges back to front, each in
  the synced text (CM6's `contentChangesFor`); full text to a server that asks for full sync; none to
  kind 0. Every send goes through one lock, so didOpen / didChange / requests leave in order.
- **Versions**: the last 32 synced texts and the edits between them are kept, so a response computed
  on an older version is read in THAT text and mapped to the current document (definition,
  references, rename, code actions); a hover or signature help for a text that changed meanwhile is
  dropped; `publishDiagnostics` for another version is ignored (CM6); formatting is aborted when the
  user edited inside a range it changes (CM6).
- **didClose** when the view's plugin goes (a document switch, the editor disposed); `close()` sends
  `shutdown` + `exit`.
- **A dropped transport** (DISCONNECTED) fails every pending request; back to CONNECTED, the client
  initializes again and re-opens its documents with their text as it is then.
- **Errors**: a server error, a timeout (`requestTimeoutMs`, 15 s) or a dropped connection never
  throws out of a feature; a user-asked one (definition, references, rename, format) is reported
  through `onMessage`. `RequestCancelled` / `ContentModified` are silent.
- **Threading**: everything runs on the client's scope (the UI thread). Incoming messages are parsed
  on a worker (`Dispatchers.Default`) on the JVM, Android and iOS; in the **browser**, a message over
  128 KB is parsed by `SlicedJson` (iterative, gives the thread back through a real macrotask every
  ~4 ms of work) and a completion list is mapped in slices of 1,000 items.

## Features

| LSP | here | userEvent |
|---|---|---|
| `publishDiagnostics` | `Lint.setDiagnostics` (severity, `source code`, the range mapped) | |
| `codeAction` | each diagnostic's actions (the 30 nearest the cursor, asked after each publish; resolved with `codeAction/resolve` when needed; a Command runs `workspace/executeCommand`) | `edit.codeAction` |
| `completion` (+ `completionItem/resolve`) | a completion source: `textEdit` / `insertText` / snippets (`Snippet.fromLsp`) / `additionalTextEdits`; `filterText` is matched, `label` shown; the documentation shown when selected; `isIncomplete` asks again per keystroke, else CM6's `prefixRegexp` `validFor`; cancelled by typing (`$/cancelRequest`) | `input.complete` |
| trigger characters | the server's `completionProvider.triggerCharacters`, else today's list `. : " ' \` < / @ #` | |
| `hover` | `hoverTooltip("lsp")`: the markdown as text (`LspMarkdown.toPlainText`, or the host's `markdown`) in `tooltip:lsp-hover` | |
| `signatureHelp` | `tooltip:lsp-signature` ABOVE the caret, on the server's trigger characters (and retrigger ones while shown), again 250 ms after a cursor move while shown (CM6), the active parameter bold; `Mod-Shift-Space`, `Mod-Shift-ArrowUp/Down`, `Escape` | |
| `definition` | `F12`: this document: the cursor moves there (scrolled into view: a fold opens); another: `onNavigate(uri, range)` | `select.definition` |
| `references` | `Shift-F12`: the `lsp-references` panel (line, text); a tap or click goes there (another document: `onNavigate`); `Escape` or × closes it | `select.reference` |
| `rename` | `F2`: the `lsp-rename` prompt ("New name", the word selected), Enter renames; this document's edits as ONE transaction, another's to `onWorkspaceEdit` | `edit.rename` |
| `formatting` | `Shift-Alt-f` (CM6's `formatKeymap`), tab size and spaces from `indentUnitFacet` | `edit.format` |
| `workspace/applyEdit` (server-initiated) | applied, answered `applied` | `lsp` (not recorded, not policed) |
| `workspace/configuration`, `client/registerCapability`, `window/workDoneProgress/create` | answered (nulls) | |
| `window/showMessage` | `onMessage(type, message)` | |

The keys are CM6's (`jumpToDefinitionKeymap`, `findReferencesKeymap`, `renameKeymap`,
`formatKeymap`, `signatureKeymap`). Named commands `lsp.definition`, `lsp.references`, `lsp.rename`,
`lsp.format`, `lsp.signature`, `lsp.hover` (touch: `Hover.showHover`). URIs are compared loosely
(percent-escapes, a drive letter's case).

**Touch**: no hover (long press selects), so the LSP information touch sees is the completion list's
documentation and signature help, which opens by itself on `(` and `,`; a host gives `lsp.hover` a
button. The panels' rows are 44 dp on touch; their taps never take the editor's focus.

## Deliberate differences from CM6's lsp-client

- Position encoding is negotiated (CM6 assumes utf-16 without saying so).
- Code actions reach the lint tooltip as buttons (CM6's lsp-client has no code actions).
- Signature help also closes on `Escape`.
- References list only this document's line text (other documents: file and line; the host has them).
- A completion's documentation is converted only when it is selected (5,000 conversions cost ~30 ms).

## Performance (5,000-item completion response, 1.6 MB, `CompletionParsePerfTest`, best of 5)

| | JSON parse | map to options | where |
|---|---|---|---|
| JVM (Mac) | 4–9 ms | 5–6 ms | a worker thread |
| iOS simulator (Debug test binary) | 64 ms | 18 ms | a worker thread |
| Chrome (headless, wasm) | kotlinx 11–15 ms; sliced: 18 ms in 5 slices, the longest 4.3 ms | 3–4 ms, sliced per 1,000 | the page's thread |

Filtering those 5,000 options per keystroke: see `editor-plugins/autocomplete` (0.7 ms worst on the JVM).

## A real server

`RealServerTest` (jvmTest) runs against **clangd** over stdio when the machine has it (the Mac:
Xcode's `/usr/bin/clangd`; skipped elsewhere): utf-16 negotiated, incremental sync (an error goes
after the fix is typed), hover, go to definition, completion (`printf`). First diagnostics ~0.2–2 s.

Tests: `./gradlew :editor-plugins:lsp:jvmTest` (commonTest: initialize, sync through 120 random edit
batches with emoji, Turkish and line breaks in utf-16 / utf-8 / utf-32 / full sync (the fake server's
own text must equal ours after every batch), positions, diagnostics and code actions, a stale
diagnostics version, completion after `.` and explicit with resolve and a snippet, cancellation on
typing, hover and a stale hover, signature help, definition into a fold, references, rename, format
and its abort, a server-pushed edit, errors and timeouts, reconnect, close; jvmTest: the surface
(hover, signature above the caret, the rename prompt, the references panel) and clangd),
`:editor-plugins:lsp:iosSimulatorArm64Test`, `:editor-plugins:lsp:wasmJsBrowserTest` (Karma, the
Mac's Chrome).
