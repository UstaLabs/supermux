# editor-plugins/lsp

A Language Server Protocol client on the public API: CM6's `@codemirror/lsp-client` (the feature set
of the CodeMirror editor it replaced) in Kotlin, on the tooltip layer, `editor-plugins/autocomplete` and
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
interface LspTransport {
    suspend fun send(message: String); val incoming: Flow<String>
    val status: StateFlow<LspConnState>; val connection: StateFlow<Int>  // a generation per (re)connection
}
```
One JSON-RPC message per string, no `Content-Length` framing (the broker's `lsp` channel frames on
the server side, `src/core/lsp/framing.ts`). **M5** adapts `:ui`'s `LspBridge` to it: `send` =
`rpcOut(serverId, message)`, `incoming` = `pumpRpcIn` filtered by session and server, `status` from
`lspStatus` / `open` (`ready` → CONNECTED, `error` / `exited` → DISCONNECTED). Also here:
`ProcessLspTransport` (jvm, the desktop): a server process over stdio with the framing; and
`:editor-plugins:lsp-fake`'s `FakeLspServer`, an in-process toy-language server (tests, the sample;
never a production host) whose transport can take its time (`sendDelayMs`), break (`failSends`) and
drop and reconnect inside one tick (`blip()`): the review's bugs only show with those.

## The workspace: one server document per URI

`client.workspace` (`LspWorkspace`, CM6's `Workspace`) holds ONE `LspDocument` per URI, shared by
every view showing it: each view's plugin (`client.plugin(uri, languageId)`) attaches to it; the first
attach sends `didOpen`, the last detach `didClose`. Every view of a document shows the same text: an
edit in one is applied to the others at once (a `remote` transaction, so each history undoes only its
own view's edits) and sent to the server once. Commands, completion, hover and signature help run for
the view they were asked in (its cursor, its lists); diagnostics reach every view. A workspace edit
(rename, a code action, the server's `applyEdit`) is applied to EVERY document open in this client
through its own views, each at the version it names, mapped to now; only documents that are not open
go to `onWorkspaceEdit`, and with no callback that is a loud failure (`applied: false`, `onMessage`).
`openDocuments`, `viewCount(uri)`, `version(uri)`, `serverText(uri)` read it. This is M5's shape: one
client per session and server, every open file and every view of it in its workspace.

**M5 lifetime.** A view's plugins stop when the view does (`startPlugins`' stop, the Editor leaving
the composition): that detaches it, and the last detach closes the document. So the host keeps a
tab's view alive across tab switches (the view, not the Editor composable, is the tab's; it calls
`view.startPlugins(hostScope)` itself for views it keeps), so switching tabs never sends
`didClose` / `didOpen` again. A `setState` (another document in the same view) detaches the old URI
and attaches the new one.

## Lifecycle and sync

- **initialize** sends CM6's client capabilities plus `general.positionEncodings: ["utf-16", "utf-8",
  "utf-32"]` (utf-16 first: the editor's unit, so a conversion is the Rope's line start, O(log n),
  plus the character); the server's `positionEncoding` is honoured (utf-8 / utf-32 count along the
  line). Then `initialized`, then `didOpen` for every document a view shows (`languageId` is the
  host's: it maps its syntax registry's language, e.g. `Syntax` "kotlin" → `kotlin`).
- **didChange**: the edits since the last sync compose into ONE change set, sent 50 ms after the last
  edit (`syncDelayMs`; the timer is the CLIENT's, so it still fires when the view that typed goes before
  it) and always right before a request; incremental ranges back to front, each in
  the synced text (CM6's `contentChangesFor`); full text to a server that asks for full sync; none to
  kind 0. **Order**: every outgoing message goes into ONE queue synchronously, in call order (sync is
  synchronous; a request queues its message before it first suspends and builds its params in the
  same step), and one sender drains it: the server always has the text a request's position is in,
  however slow the pipe (tested with a 100 ms send), and a cancelled caller never takes a queued
  `didChange` with it.
- **Versions**: the last 32 synced texts and the edits between them are kept, so a response computed
  on an older version is read in THAT text and mapped to the current document (definition,
  references, rename, code actions); a hover or signature help for a text that changed meanwhile is
  dropped; `publishDiagnostics` for another version is ignored (CM6); formatting is aborted when the
  user edited inside a range it changes (CM6).
- **didClose** when the view's plugin goes (a document switch, the editor disposed); `close()` sends
  `shutdown` + `exit`.
- **A dropped transport** (DISCONNECTED) fails every pending request; a new connection (a new
  `LspTransport.connection` generation while CONNECTED, even a drop and reconnect inside one tick that a
  StateFlow of the status alone would hide) makes the client initialize again and re-open its
  documents with their text as it is then. A send that throws fails the connection (`FAILED`, pending
  requests fail) and NOTHING more is sent (the rest of the queue is dropped) until the next
  generation: an adapter whose send failed must bump `connection` or report DISCONNECTED (the
  `LspTransport` KDoc), or the client stays FAILED.
- **Errors**: a server error, a timeout (`requestTimeoutMs`, 15 s), a failed send or a dropped
  connection never throws out of a request (every other exception becomes `LspException(DISCONNECTED)`)
  nor out of a feature (every launched command, code action and signature request is guarded); a user-asked one (definition, references, rename, format) is reported
  through `onMessage`. `RequestCancelled` / `ContentModified` are silent.
- **Threading**: everything runs on the client's scope (the UI thread). Incoming messages are parsed
  on a worker (`Dispatchers.Default`) on the JVM, Android and iOS; in the **browser**, a message over
  128 KB is parsed by `SlicedJson` (iterative, gives the thread back through a real macrotask every
  ~4 ms of work; strict RFC 8259: malformed input is refused, as kotlinx's parser does) and a
  completion list is mapped in slices of 1,000 items.
- `ProcessLspTransport` caps a message at 64 MB and a header line at 8 KB, bounds its queue (1,024
  messages: a flooding server waits on its pipe) and ends the connection on any read failure.

## Features

| LSP | here | userEvent |
|---|---|---|
| `publishDiagnostics` | `Lint.setDiagnostics` (severity, `source code`, the range mapped) | |
| `codeAction` | each diagnostic's actions (the 30 nearest the cursor, asked after each publish, attached BY THE DIAGNOSTIC'S ID; resolved with `codeAction/resolve` when needed, after a sync, and applied at THAT version; a Command runs `workspace/executeCommand`) | `edit.codeAction` |
| `completion` (+ `completionItem/resolve`) | a completion source: `textEdit` / `insertText` / snippets (`Snippet.fromLsp`) / `additionalTextEdits`; `filterText` is matched, `label` shown; the documentation shown when selected; `isIncomplete` asks again per keystroke, else CM6's `prefixRegexp` `validFor`; cancelled by typing (`$/cancelRequest`) | `input.complete` |
| trigger characters | the server's `completionProvider.triggerCharacters`, else today's list `. : " ' \` < / @ #` | |
| `hover` | `hoverTooltip("lsp")`: the markdown as text (`LspMarkdown.toPlainText`, or the host's `markdown`) in `tooltip:lsp-hover` | |
| `signatureHelp` | `tooltip:lsp-signature` ABOVE the caret, on the server's trigger characters (and retrigger ones while shown), again 250 ms after a cursor move while shown (CM6), the active parameter bold; `Mod-Shift-Space`, `Mod-Shift-ArrowUp/Down`, `Escape` | |
| `definition` | `F12`: this document: the cursor moves there (scrolled into view: a fold opens); another: `onNavigate(uri, range)` | `select.definition` |
| `references` | `Shift-F12`: the `lsp-references` panel (line, text); a tap or click goes there (another document: `onNavigate`); `Escape` or × closes it | `select.reference` |
| `rename` | `F2`: the `lsp-rename` prompt ("New name", the word selected), Enter renames, ALL OR NOTHING: an occurrence edited while the server worked (or a version gone) applies nothing and the prompt says "Rename out of date — try again"; other open documents through their own views, the rest to `onWorkspaceEdit` | `edit.rename` |
| `formatting` | `Shift-Alt-f` (CM6's `formatKeymap`): `tabSize` from `tabSizeFacet`, `insertSpaces` from `indentUnitFacet`; all or nothing | `edit.format` |
| `workspace/applyEdit` (server-initiated) | each document's edits at the `documentChanges` version they name (else the text the server has), mapped to now; answered `applied: true` only when EVERY edit applied (a version no longer kept, an edit the user changed since, a document that is not open and no host callback: `false`) | `lsp` (not recorded, not policed) |
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
