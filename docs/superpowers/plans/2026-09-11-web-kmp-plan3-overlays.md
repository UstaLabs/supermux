# Web → KMP, Plan 3 of 5: terminal, editor, VNC, uploads, mic, read-aloud

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The browser host reaches feature parity with desktop for the heavy panes: an xterm.js terminal with the shared predictive echo, the committed CodeMirror bundle in a same-origin iframe driving the shared `EditorEngine` protocol (LSP included), VNC displays (already wired — verify), chat uploads through `BlobChunkSource`, dictation via `MediaRecorder`, read-aloud audio chunks via `AudioContext`, clipboard image paste. Also: the proper `:shared` fix for the cookie-credential host (no more sentinel token) and a bundle-size pass.

**Architecture:** Every heavy pane is a DOM element positioned by `HtmlElementView` inside a `KeepAlivePanel` (panes swap, never overlay; hidden = 0×0 + `visibility:hidden`). The terminal factory is a copy of desktop's 6-line `rememberTerminalSurface` shape over an xterm.js `Terminal`; prediction is the shared Kotlin `PredictionEngine` with an xterm `PredictionSink` ported from the old web adapter, lock-free (single thread). The editor is the desktop engine's protocol verbatim (`EditorBridge` builds every string; `parseBridgeEvent` parses every callback) with `iframe.contentWindow.eval` outbound and `window.parent.postMessage` inbound through a 20-line shim in the iframe page. Mic/TTS/clipboard are `Platform` seams over browser APIs; each must run inside the user gesture where the browser demands it.

**Tech Stack:** Kotlin/Wasm + kotlinx-browser 0.5.0, npm `@xterm/xterm` 5.5 + `@xterm/addon-fit` 0.10 + `@xterm/addon-webgl` 0.18 (via KGP `npm()`), the committed `apps/android/src/main/assets/editor/{index.html,cm6.js}`, Web APIs: `MediaRecorder`, `AudioContext.decodeAudioData`, `navigator.clipboard.read`, `ResizeObserver`.

**Spec:** `docs/superpowers/specs/2026-09-11-web-to-kmp-compose-design.md` §4 (Platform table), §6 (overlays), §10 step 3. Plans 1–2 Results + carry-forwards.

**Facts (verified 2026-09-11 — do not re-derive):**
- Terminal seam: `TerminalViewFactory { available; rememberTerminalSurface(connect: () -> TerminalClient): TerminalSurface; TerminalView(...) }`, `TerminalSurface { keys: TerminalKeySink; @Composable Content(modifier, active, onExit: (() -> Unit)?) }`, `PredictionSink { available; cursor(): CursorPos; render(ops: List<DisplayOp>) }` (apps/ui/.../terminal/TerminalViewFactory.kt:56–200). Desktop's `rememberTerminalSurface` (apps/desktop/.../terminal/JediTermTerminalViewFactory.kt:35–43) = `rememberLazyTerminalClient(connect)` + `rememberTerminalKeySink { bytes -> client.get().sendInput(bytes) }` + `remember(client, keys) { Surface(...) }`. Lifecycle in `JediTermTerminalView.kt`: output `client.output.collect { pred.handleOutput(bytes) { connector.offerServerBytes(bytes) } }`; input `connector.write(bytes)` → prediction tap → `client.sendInput(bytes)`; resize: grid measures → `if (cols > 0 && rows > 0) scope.launch { client.resize(cols, rows) }`; focus `client.focus(active && windowFocused)`; `client.exit.collect { onExit?.invoke() }`; dispose `client.stop()`. `TerminalKeySink(send: (ByteArray) -> Unit)`: `press(key)`, `applyArmedModifiers(data): ByteArray?` (non-null = re-encoded printable under an armed Ctrl/Alt from the touch key bar; send that and skip prediction). `KeepAlivePanel` lays a hidden tab out at 0×0 → keep the cols/rows guard. `TerminalTabs` mounts each surface in `key(id)`; the key bar is drawn by `TerminalPane` under Touch.
- Prediction engine (apps/shared/.../net/PredictiveEcho.kt): `PredictionEngine(cfg: PredictionConfig = DEFAULT_CONFIG, now: () -> Long)`, `setLatencyEstimate(ms)`, `onInput(ev: InputEvent, serverCursor: CursorPos): List<DisplayOp>`, `onServerData(bytes): List<DisplayOp>`, `reset()`, `decodeInput(data: String): InputEvent`; ops `DrawDim(id,row,col,char)`, `MoveCaret(row,col)`, `RestoreCell(id,row,col)`, `Passthrough(bytes)`, `HideCaret`, `ShowCaret`; `CursorPos(row, col)` 0-based. Wiring template: apps/desktop/.../terminal/PredictionPipeline.kt (drop its `synchronized` — wasm is single-threaded and `synchronized` doesn't exist there). The old web xterm adapter that rendered these ops as ANSI: `src/web-app/src/lib/predictive-echo/` (read `xterm-adapter.ts` / `renderer.ts` — whichever draws dim cells and moves the caret).
- Editor seam (apps/ui/.../editor/engine/EditorEngine.kt + EditorBridge.kt): outbound strings — `bridgeShimJs(queryFn)` + `initScript(queryFn, content, filename, lineWrap, fontSize)` (one eval), `EditorPushPlanner` emits `cmSetContent/cmSetLanguage/cmSetLineWrap/cmSetFontSize/cmSetScrollTop/cmRevealLine`, `showDiffRegionJs(...)`, `window.cmLspConnect/cmLspMessage/cmLspDisconnect`, reads via `evalResultJs(queryFn, id, "cmGetContent()" | "cmGetScrollTop()")`; inbound `parseBridgeEvent(request: String): BridgeEvent?` over `{fn,arg}` (`BridgeEvent.Ready/Change/Save/FontSize/LspOut/DiffLineClick/DiffExpand/DiffPage/CommentSubmit/ReplySubmit/ResolveThread/ComposerState/EvalResult(id,value)`); `EDITOR_READY_TIMEOUT_MS = 8_000`, `EDITOR_BG = 0xFF282C34`. Desktop engine (apps/desktop/.../editor/DesktopEditorEngine.kt) is the reference for state transitions and the `pendingEvaluations` map; `QUERY_FN = "smxEditorQuery"`. The bundle (apps/android/codemirror/cm6-entry.mjs:150) finds its host as `window.AndroidEditor` (10 hooks) and LSP-out as `window.webkit.messageHandlers.lsp.postMessage`; **it has no `window.parent.postMessage` path** — the shim must define those objects. Web already has `interface DomEditorEngine : EditorEngine { attach(container: HTMLElement); detach() }` + `EditorEngineHost` via `HtmlElementView` (apps/ui/src/wasmJsMain/.../editor/EditorSurface.wasmJs.kt).
- VNC: nothing to build. `DisplayPanel` gets its `VncClient` from `HostStore.connectVnc(streamId)` (baseUrl/token/http come from the connected store), drives `VncFramebuffer.applyUpdate`, and the wasm `VncFramebuffer` + pako `ZlibInflater` already exist; transport switch is `displayTransportFor(transport, decoder != null, caps.scrcpy)` — VNC-or-unsupported on web with zero changes. Only verification.
- Mic: `MicCapture { start(): Boolean; stop(): CapturedAudio?; cancel(); suspend requestPermission(): Boolean; available; liveTranscript: LiveTranscript? (null ok) }`; `CapturedAudio(bytes, filename, mime)`. Broker `POST /sessions/<id>/transcribe` takes multipart field `audio` of any container (webm/opus is normal). **Wire gap:** `Dictation.kt:243` calls `transcribeAudio(audio.bytes, audio.filename)` dropping `mime`; `FleetStore.kt:1003` has no mime param; `HostStore.transcribeAudio(…, mime = "audio/wav")`; `BrokerApi.transcribeAudio(sessionId, bytes, filename, mime = "audio/mp4")`. The Vue app recorded `audio/webm;codecs=opus` first.
- Read-aloud: `MessageTts.speakRemoteStream` → `BrokerApi.speakStream` → NDJSON `{i,n,mime:"audio/mpeg",audio:<b64>}` per line → `TtsEngine.playAudioChunk(bytes)` receives **one complete MP3 file per chunk** and must suspend until it finished playing (gapless relies on it). `speak(text)` = platform voice (already `speechSynthesis`).
- Clipboard: `hasImage()` is only called after Ctrl/Cmd+V matched or inside the open attach menu — a static capability probe (`navigator.clipboard.read` exists) is correct; `readImages()` is called from `scope.launch { withContext(Dispatchers.Default) { … } }` — on Safari the `clipboard.read()` promise must be created inside the gesture; fire it synchronously and await after.
- `FleetStore.sync` (apps/shared/.../state/FleetStore.kt:376–378) filters `token.isNotBlank()`; `HostMetaCodec` (apps/shared/.../host/HostMetaCodec.kt:27–66) persists a private `Meta` without the token. Only other token-truthiness site: `PairingState.kt:144`. Web currently stores the sentinel `"cookie"` (`apps/web/.../WebHostStores.kt`), which spends the broker's brute-force budget once the cookie expires.
- Bundle: `apps/web/build.gradle.kts:67` `maxGzipBytes = 6 MiB`, currently ≈5.98 MB staged (Skiko 3.4 MB + app ≈2.5 MB); no `applyBinaryen()`, no wasm compiler flags anywhere. The guard sums `.wasm/.js/.mjs` under `assets/` only.
- Compose-Web interop rules from plans 1–2: `HtmlElementView(modifier, factory, update, onRelease)`; `update` runs once at attach (before sizing) and then only on snapshot reads → gate one-shot attach on an `onGloballyPositioned`-written state; hidden pane = `KeepAlivePanel` 0×0 + `style.visibility`. kotlinx-browser lacks several accessors (`style.overflow` → `setProperty`, no `ChildNode.remove`, `setTimeout` handler returns `JsAny?`). A Kotlin lambda can't be an `external` property; use `js()` helpers. Karma lane: `CHROME_BIN=/usr/bin/google-chrome ./gradlew :web:wasmJsBrowserTest` (35 tests today, ~1.7 min).

---

## File structure

| File | Responsibility |
|---|---|
| `apps/shared/src/commonMain/kotlin/dev/supermux/host/PairedHost.kt` | + `ambientAuth: Boolean = false`. |
| `apps/shared/src/commonMain/kotlin/dev/supermux/host/HostMetaCodec.kt` | persist `ambientAuth`. |
| `apps/shared/src/commonMain/kotlin/dev/supermux/state/FleetStore.kt` | `sync` dials `token.isNotBlank() \|\| ambientAuth`. |
| `apps/shared/src/commonMain/kotlin/dev/supermux/host/PairedHostStore.kt` | `add(..., ambientAuth = false)`. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebHostStores.kt` | drop the sentinel; `token = "", ambientAuth = true`. |
| `apps/web/build.gradle.kts` | `applyBinaryen()`, debug-info off, npm xterm deps, editor bundle staging, ceiling 8 MiB. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/terminal/Xterm.kt` | `@JsModule` externals for `Terminal`, `FitAddon`, `WebglAddon`. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/terminal/XtermTerminalSurface.kt` | `TerminalSurface` + factory (copy of desktop's shape). |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/terminal/XtermPrediction.kt` | `PredictionSink` over xterm + lock-free pipeline. |
| `apps/web/src/wasmJsMain/resources/editor/index.html` | the committed page + the iframe shim `<script>`. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/editor/WebEditorEngine.kt` | `DomEditorEngine` over a same-origin iframe. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/editor/WebEditorEngineFactory.kt` | `EditorEngineFactory` (Initializing → Ready). |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/seams/WebMic.kt` | `MediaRecorder` capture. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/seams/WebTts.kt` | + `AudioContext` chunk playback. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/seams/WebClipboard.kt` | real `navigator.clipboard.read`. |
| `apps/ui/.../chat/Dictation.kt`, `apps/shared/.../state/{FleetStore,HostStore}.kt`, `net/BrokerApi.kt` | pass `CapturedAudio.mime` through. |
| `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/...` | `XtermPredictionSinkTest`, `EditorBridgeIframeTest`, codec/filter tests in `:shared` commonTest. |

---

### Task 1: Ambient-credential host in `:shared` (replaces the sentinel)

**Files:** `PairedHost.kt`, `HostMetaCodec.kt`, `FleetStore.kt:363–378`, `PairedHostStore.kt` (`add`), `apps/web/.../WebHostStores.kt`, `LocalStorageHostPersistence.kt` (KDoc), tests `apps/shared/src/commonTest/kotlin/dev/supermux/host/HostMetaCodecTest.kt` (extend or create) and `apps/shared/src/jvmTest/.../state/FleetStoreSyncTest.kt` (find the existing sync test file by grepping `wanted`/`sync` in `apps/shared/src/jvmTest/kotlin/dev/supermux/state`).

- [ ] **Step 1: Failing tests** — codec round-trips `ambientAuth=true` (and defaults false for old metadata JSON without the key); `FleetStore.sync` dials a host with `token=""`, `ambientAuth=true`, `directUrl` set (assert via the existing fake `appFactory` that a `HostStore` is created for it) and still skips `token=""`, `ambientAuth=false`.
- [ ] **Step 2: Implement** — `val ambientAuth: Boolean = false` on `PairedHost` (KDoc: "the transport carries the credential itself — the browser's HttpOnly cookie — so a blank token still dials"); `Meta.ambientAuth` in the codec (encode + decode, default false); `sync` filter becomes `hosts.filter { it.token.isNotBlank() || it.ambientAuth }.mapNotNull { … }` keeping the KDoc accurate; `PairedHostStore.add(..., ambientAuth: Boolean = false)` threads it; `WebHostStores.ensureOriginHost` passes `token = "", ambientAuth = true` and deletes `COOKIE_TOKEN`; fix the KDocs that mention the sentinel; `LocalStorageHostPersistence` needs no change (tokens map stays, values blank).
- [ ] **Step 3: Verify** — `./gradlew :shared:jvmTest :shared:compileKotlinWasmJs :ui:jvmTest --console=plain` green (run `:ui:jvmTest` under `xvfb-run -a`); `:web:compileKotlinWasmJs`; Karma lane (35) green. Grep `"cookie"` under apps/web → no sentinel left.
- [ ] **Step 4: Commit** — `feat(shared): ambient-credential hosts — FleetStore dials the browser's cookie session without a token sentinel`.

---

### Task 2: Bundle-size pass + editor/xterm assets in the build

**Files:** `apps/web/build.gradle.kts`, `apps/web/src/wasmJsMain/resources/editor/` (populated at build time), `apps/gradle/libs.versions.toml` (no change unless a version ref is wanted).

- [ ] **Step 1: Optimise** — in `wasmJs { … }` add `applyBinaryen()` (KGP DSL; if the accessor is missing on this KGP, use `compilerOptions { freeCompilerArgs.addAll("-Xwasm-debug-info=false") }` and the `kotlin.wasm.debug=false` property instead, and say so). Measure before/after with `./gradlew :web:stageForBroker` (the task prints the gzip total). Record both numbers.
- [ ] **Step 2: Ceiling** — set `maxGzipBytes = 8L * 1024 * 1024` with a comment quoting the measured breakdown (Skiko ≈3.4 MB is the floor; the whole `:ui` app ≈2.5 MB; xterm.js ≈0.3 MB). The number stays a bloat catch, not a target.
- [ ] **Step 3: xterm deps** — `wasmJsMain.dependencies { implementation(npm("@xterm/xterm", "5.5.0")); implementation(npm("@xterm/addon-fit", "0.10.0")); implementation(npm("@xterm/addon-webgl", "0.18.0")) }`; run `./gradlew kotlinWasmUpgradeYarnLock`; commit the lockfile. xterm's CSS (`node_modules/@xterm/xterm/css/xterm.css`) must reach the page: copy it into `apps/web/src/wasmJsMain/resources/xterm.css` via a Gradle `processResources`-style copy from the KGP node_modules dir (`rootProject.layout.buildDirectory.dir("wasm/node_modules/@xterm/xterm/css")`) and add `<link rel="stylesheet" href="xterm.css">` to `index.html` (root, no-cache rule — 4 KB, fine).
- [ ] **Step 4: Editor bundle** — a `Copy` of `${rootProject.projectDir}/android/src/main/assets/editor/{index.html,cm6.js}` into `apps/web/src/wasmJsMain/resources/editor/` is NOT the shape (resources are committed); instead have `stageForBroker` copy them from the android assets dir straight into the staged tree at **`editor/`** (root, no-cache rule, excluded from hashing — the page references `cm6.js` relatively and a rename would break it; 1.3 MB revalidated per load is acceptable, plan 5 may hash it) and overlay `apps/web/src/wasmJsMain/resources/editor-shim.js` next to it, then rewrite the staged `editor/index.html` to load `editor-shim.js` BEFORE `cm6.js` (a one-line `replace("<script src=\"cm6.js\">", "<script src=\"editor-shim.js\"></script><script src=\"cm6.js\">")` in the task). Single source of truth stays `apps/android/src/main/assets/editor/`, as desktop does.
- [ ] **Step 5: Verify + commit** — `stageForBroker` twice → identical; `ls src/channels/web/static/editor` shows the three files; `curl` through a hermetic broker shows `editor/index.html` served `text/html` + `no-cache`. Commit `build(web): binaryen, 8 MiB ceiling, xterm deps, editor bundle staged at /editor`.

---

### Task 3: xterm.js terminal with shared predictive echo

**Files:** `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/terminal/{Xterm.kt,XtermTerminalSurface.kt,XtermPrediction.kt}`, `WebPlatform.kt` (`terminalView()` + `WEB_CAPS.terminal = true`), test `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/XtermPredictionSinkTest.kt`.

- [x] **Step 1: Externals** (`Xterm.kt`):

```kotlin
@file:JsModule("@xterm/xterm")
package dev.supermux.web.terminal
import org.w3c.dom.HTMLElement
external class Terminal(options: JsAny? = definedExternally) : JsAny {
    fun open(parent: HTMLElement)
    fun write(data: JsAny)            // Uint8Array or String
    fun onData(cb: (String) -> Unit): JsAny
    fun onBinary(cb: (String) -> Unit): JsAny
    fun onResize(cb: (JsAny) -> Unit): JsAny   // {cols, rows}
    fun loadAddon(addon: JsAny)
    fun focus(); fun blur(); fun dispose(); fun clear()
    val cols: Int; val rows: Int
    val buffer: JsAny                  // buffer.active.cursorX/cursorY read via a js() helper
    val element: HTMLElement?
}
```
plus `@file:JsModule("@xterm/addon-fit") external class FitAddon : JsAny { fun fit() }` and `WebglAddon` in their own files (one `@file:JsModule` per file). Kotlin lambdas as `onData` callbacks: pass through a `js()` helper if the compiler rejects a Kotlin function type on an external member (`fun onData(term: Terminal, cb: (String) -> Unit): Unit = js("term.onData(cb)")`). Read `cursorX/cursorY` with `private fun cursor(term: Terminal): JsAny = js("({row: term.buffer.active.cursorY, col: term.buffer.active.cursorX})")` and unpack.

- [x] **Step 2: Surface + factory** (`XtermTerminalSurface.kt`) — copy desktop's `rememberTerminalSurface` shape; `Content(modifier, active, onExit)`:
  - `KeepAlivePanel(visible = active-or-mounted…)`: NO — `TerminalTabs` already wraps `Content` in `KeepAlivePanel`; `Content` just renders `HtmlElementView<HTMLDivElement>(modifier.fillMaxSize(), factory = { div with `style.setProperty("overflow","hidden")`, background `#0b0b0b` }, update = { attach-once when sized (the plan-1 `onGloballyPositioned` pattern) → `term.open(div)`, `loadAddon(fit)`, try `loadAddon(webgl)` in `runCatching` (falls back to canvas renderer), `fit.fit()`, install a `ResizeObserver` on the div → `fit.fit()` }, onRelease = { observer.disconnect(); term.dispose() })`.
  - output: `LaunchedEffect(client) { client.get().output.collect { bytes -> prediction.handleOutput(bytes) { term.write(bytes.toUint8Array()) } } }`.
  - input: `term.onData { s -> val bytes = s.encodeToByteArray(); keys.applyArmedModifiers(bytes)?.let { client.get().sendInput(it) } ?: run { prediction.handleInput(bytes); client.get().sendInput(bytes) } }` — read `apps/android/.../terminal/TermlibTerminalView.kt` (or wherever Android applies `applyArmedModifiers`) and mirror its order exactly.
  - resize: `term.onResize { if (cols > 0 && rows > 0) scope.launch { client.get().resize(cols, rows) } }`; also send once after the first `fit()`.
  - focus: `LaunchedEffect(active, foreground) { client.get().focus(active && foreground); if (active) term.focus() }` using `WebAppState.foreground`.
  - exit: `LaunchedEffect(client) { client.get().exit.collect { onExit?.invoke() } }`.
  - `keys = rememberTerminalKeySink { bytes -> client.get().sendInput(bytes) }`; `available = true`.
  - Wheel/touch scroll: xterm handles both (tmux `mouse on` gets SGR reports from xterm's own mouse handling); do NOT port `TerminalScroll`.
  - Mount rule: the div must ignore Compose pointer input under it (the canvas is below the DOM node, so pointer events reach xterm first — verify; if Compose steals wheel events, `style.setProperty("pointer-events","auto")` on the div and `Modifier.pointerInput { }` consuming nothing).
- [x] **Step 3: Prediction** (`XtermPrediction.kt`) — `class XtermPredictionSink(term: Terminal) : PredictionSink` rendering ops as ANSI written to the terminal, ported from the old `src/web-app/src/lib/predictive-echo/` xterm adapter (read it first; it is the exact behaviour spec: `DrawDim` = save cursor, CUP to row/col (1-based in ANSI!), `ESC[2m` + char + `ESC[22m`, restore; `RestoreCell` = rewrite the original cell — read it from `term.buffer.active.getLine(row)?.getCell(col)` via a js() helper; `MoveCaret` = CUP; `Passthrough` = write bytes; `Hide/ShowCaret` = `ESC[?25l/h`). Pipeline = `PredictionPipeline` minus the lock: `handleInput(bytes)`, `handleOutput(bytes, fallback)`, `teardown()`. Latency estimate from RTT of the last keystroke. `available = true`.
  - Test (`XtermPredictionSinkTest`, Karma): mount a real xterm into a detached div (xterm requires an attached element — attach to `document.body`), write "abc", call `render(listOf(DrawDim(1,0,3,'x')))` and assert the cell at (0,3) reads `x` via `getLine(0).getCell(3).getChars()`; `RestoreCell` clears it; `MoveCaret` moves `cursorX`.
- [x] **Step 4: Wire** — `WebPlatform.terminalView() = XtermTerminalViewFactory` (single instance), `WEB_CAPS.terminal = true`.
- [x] **Step 5: Verify** — `:web:compileKotlinWasmJs`, Karma lane; then stage + hermetic broker + headless Chrome: open the fixture session, add a terminal tab (the `+` in the pane strip / the Native toggle — find the affordance in `TerminalTabs`), type `echo hello-web` + Enter through `page.keyboard`, assert the DOM (`.xterm-rows` text or the accessibility tree) contains `hello-web`; screenshot. Also verify resize (set viewport 800×600 → 1200×800) reflows.
- [x] **Step 6: Commit** — `feat(web): xterm.js terminal surface with the shared predictive echo`.

---

### Task 4: CodeMirror editor engine over a same-origin iframe

**Files:** `apps/web/src/wasmJsMain/resources/editor-shim.js`, `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/editor/{WebEditorEngine,WebEditorEngineFactory}.kt`, `WebPlatform.kt` (`editorEngine`), test `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/EditorBridgeIframeTest.kt`.

- [x] **Step 1: The shim** (`editor-shim.js`, loaded before `cm6.js` inside the iframe):

```js
// Host bridge for the iframe editor. The bundle talks to `window.AndroidEditor` (10 hooks) and
// `window.webkit.messageHandlers.lsp`; both are routed to the parent window as the same
// `{fn, arg}` request string the desktop/Android hosts receive (parseBridgeEvent in :ui).
(function () {
  var post = function (fn, arg) { window.parent.postMessage(JSON.stringify({ fn: fn, arg: arg == null ? "" : String(arg) }), window.location.origin); };
  var hooks = ["onChange","onSave","onReady","onFontSize","onDiffLineClick","onDiffExpand","onDiffPage","onCommentSubmit","onReplySubmit","onResolveThread","onComposerState"];
  var ae = {}; hooks.forEach(function (h) { ae[h] = function (arg) { post(h, arg); }; });
  window.AndroidEditor = ae;
  window.webkit = { messageHandlers: { lsp: { postMessage: function (payload) { post("lspOut", payload); } } } };
  // The desktop `smxEditorQuery` query function: eval results come back the same way.
  window.smxEditorQuery = function (q) { window.parent.postMessage(q.request, window.location.origin); };
  window.smxEditorQueryCancel = function () {};
  // Inbound: the host evals code strings built by EditorBridge (same-origin, so this is just a
  // message-shaped eval; the host never sends untrusted text).
  window.addEventListener("message", function (ev) {
    if (ev.source !== window.parent || typeof ev.data !== "string" || ev.data.indexOf("__smxEval:") !== 0) return;
    try { (0, eval)(ev.data.slice(10)); } catch (e) { post("evalError", String(e)); }
  });
})();
```
Check `bridgeShimJs(queryFn)` in EditorBridge.kt: if it already defines `window.AndroidEditor` in terms of `window[queryFn]`, DROP the `ae`/`webkit` parts of this shim and only provide `window.smxEditorQuery` — the desktop engine evals `bridgeShimJs(QUERY_FN) + initScript(...)` and that shim will do the wiring. Read it and pick the smaller shim; record which.

- [x] **Step 2: Engine** (`WebEditorEngine : DomEditorEngine`) — mirrors `DesktopEditorEngine` with: `attach(container)` creates `<iframe src="editor/index.html" style="width:100%;height:100%;border:0;background:#282c34">`, appends, on `load` evals `bridgeShimJs(QUERY_FN) + initScript(QUERY_FN, content, filename, lineWrap, fontSize)` through `postEval(code)` = `iframe.contentWindow.postMessage("__smxEval:" + code, origin)`; a `window` `message` listener filtered by `ev.source === iframe.contentWindow` feeds `parseBridgeEvent(ev.data)`; `pendingEvaluations: MutableMap<Long, (String) -> Unit>` + `nextId` for `getContent`/`readScrollTop` via `evalResultJs`; ready timeout 8 s → `failed`; `detach()` removes the iframe and listener; `dispose()` likewise. Every `EditorEngine` method delegates to the planner/bridge exactly as desktop does (copy the method bodies; only `executeJavaScript` → `postEval`).
- [x] **Step 3: Factory** — `WebEditorEngineFactory : EditorEngineFactory` with `state` `Initializing` → `Ready` once `editor/index.html` is fetchable (a HEAD via Ktor in `ensureInit()`, or simply `Ready` immediately — the iframe load itself reports failure); `create(lineWrap, fontSize) = WebEditorEngine(...)`. `WebPlatform.editorEngine = WebEditorEngineFactory()`.
- [x] **Step 4: Test** (`EditorBridgeIframeTest`, Karma; Karma serves only the test page, so the iframe must be `srcdoc`): build an iframe with `srcdoc` = the shim + a stub `window.cmInit = function(){ window.AndroidEditor.onReady(""); }`, attach `WebEditorEngine` to it, assert `ready` flips true within 8 s and that `getContent` round-trips a stubbed `cmGetContent()`.
- [x] **Step 5: Verify** — stage + hermetic broker + headless Chrome: open the fixture session, open the editor pane, open a file from the fixture workdir (the fixture has files; find the file-tree affordance), assert the iframe exists and its `.cm-content` contains the file's first line; screenshot; type a character and confirm `onChange` reached Kotlin (the tab shows a dirty marker or `getContent` differs).
- [x] **Step 6: Commit** — `feat(web): CodeMirror editor engine over a same-origin iframe, desktop's bridge protocol verbatim`.

---

### Task 5: Mic, read-aloud audio, clipboard images, upload check

**Files:** `seams/WebMic.kt` (replace `NoWebMic`), `seams/WebTts.kt`, `seams/WebClipboard.kt`, `WebPlatform.kt` (`WEB_CAPS.clipboardImages = true`, `mic = WebMic`), and the mime pass-through: `apps/ui/.../chat/Dictation.kt:131,243`, `apps/shared/.../state/FleetStore.kt:~1003`, `HostStore.kt:~1714`, `BrokerApi.kt:~2155` (+ their existing tests).

- [ ] **Step 1: Mime pass-through (TDD in `:shared` jvmTest)** — `transcribeAudio(bytes, filename, mime)` end to end; default stays for callers that don't pass it; the multipart part's content-type equals the passed mime (assert with MockEngine).
- [ ] **Step 2: `WebMic`** — `available = true` iff `navigator.mediaDevices?.getUserMedia` exists; `requestPermission()` = `getUserMedia({audio:true})` (keep the stream); `start()` = new `MediaRecorder(stream, {mimeType: first supported of ["audio/webm;codecs=opus","audio/webm","audio/mp4;codecs=mp4a.40.2","audio/mp4","audio/ogg;codecs=opus"]})`, `ondataavailable` collects blobs, `start()`; `stop()` must be synchronous by contract but `MediaRecorder.stop()` delivers the final blob asynchronously → collect chunks on `dataavailable` with `start(250)` (timeslice) so `stop()` can return what has been gathered so far plus flush the last blob via `requestData()` before `stop()`; read blob bytes through `BlobChunkSource` (sync XHR) — document the ≤250 ms tail loss; `CapturedAudio(bytes, "dictation.webm" (extension from the mime), mime)`; `cancel()` stops without returning; `liveTranscript = null`.
- [ ] **Step 3: `WebTts.playAudioChunk`** — one `AudioContext` (created lazily inside `speak`/first chunk; browsers require a gesture to start it — call `resume()`), `decodeAudioData(bytes.buffer)` → `BufferSource` → `start()` and `suspendCancellableCoroutine` resumed on `onended`; `stop()` stops the current source and clears the queue; `shutdown()` closes the context.
- [ ] **Step 4: `WebClipboard`** — `hasImage() = navigator.clipboard?.read exists`; `readImages()`: call `navigator.clipboard.read()` synchronously (before any suspension) and await the promise; for each item with an `image/*` type → `getType` → `Blob` → `PickedFile("pasted.<ext>", type, BlobChunkSource(blob))`. `WEB_CAPS.clipboardImages = true`.
- [ ] **Step 5: Verify** — Karma unit tests where feasible (`WebTts` decode of a tiny embedded MP3 is optional); headless Chrome with `--use-fake-device-for-media-stream --use-fake-ui-for-media-stream`: tap the mic in the composer, stop, assert a `/transcribe` request left with `audio/webm` content-type (Playwright `page.on("request")`); upload check: attach a generated 12 MB file via the picker (`page.setInputFiles` on the hidden input) and assert `POST /upload/init` + ≥2 `PATCH` requests (chunked path) before send.
- [ ] **Step 6: Commit** — `feat(web): MediaRecorder dictation, AudioContext read-aloud, clipboard image paste; mime passes through to /transcribe`.

---

### Task 6: VNC verification + Results

- [ ] **Step 1:** Displays screen on the hermetic broker: no real display exists here, so verify the negative path — the Displays screen lists none and the "Unsupported transport" text renders for an h264 stream if the fixture can register one (skip if not; say so). If a VNC server is reachable on this host (`x11vnc`/`tigervnc` — check `which`), register a display through the broker's `/displays` API and confirm the framebuffer paints (screenshot). Otherwise record "VNC path untested in browser, all code shared and unit-tested".
- [ ] **Step 2:** Append `## Results` to this plan (terminal/editor/mic/upload/VNC outcomes, bundle numbers before/after binaryen, timings) and commit with `git add -f`.

---

## Not in this plan

Setup wizard, web push + `sw.js`, manifest/icons, `Caps.setupWizard` → plan 4. Playwright journey rewrite, CI/Docker, cm6 lockfile, Vue deletion, `font/ttf` + hashed editor bundle → plan 5. scrcpy/WebCodecs → later spec.

---

## Results — Task 3 (xterm.js terminal, 2026-09-12)

Browser terminal works end to end against a hermetic broker: a real pty through tmux, typed with
`page.keyboard`, resized both ways by the pane.

| Check | Result |
|---|---|
| `:web:compileKotlinWasmJs` | green (4 s incremental) |
| Karma (`:web:wasmJsBrowserTest`) | **40 tests** green (36 before + 4 `XtermPredictionSinkTest`), 1 m 44 s |
| `echo hello-web` typed into the pane | `hello-web` on screen AND in the tmux pane's own scrollback |
| `tput cols` at 1200×800 → 800×600 → 1200×800 | **123 → 66 → 123** (the resize reaches the remote pty, not just the DOM) |
| Bundle | 6 085 KB gzip staged (plan 2: 5 980 KB) — **+105 KB** for xterm + fit + webgl, inlined into `app.js` |
| Console | no `pageerror`; only the known WebGL driver warnings and `[BrokerClient] send dropped (not connected)` |

Screenshots: session scratchpad `plan3/` — `3a-add-menu.png` (the pane "+" popover), `3-terminal-open.png`
(shell prompt in the pane), `5-echoed.png` (`echo hello-web` → `hello-web`), `6-narrow.png` (800×600),
`7-wide-again.png`, plus `s1..s3` for the tab-strip probe.

**Interop findings (read before Task 4 — the editor iframe will hit the same two):**

1. **`@JsModule` on a top-level external class resolves to the module's DEFAULT export on wasm.**
   `@JsModule("@xterm/xterm") external class Terminal` compiled fine and died in the browser with
   `_ref_….default is not a constructor`. The working shape is `:shared`'s `Pako` shape: an
   external OBJECT per module with the class NESTED inside (that resolves as `ns.Terminal`, the real
   named export), plus a `typealias` for call sites. The plan's Step-1 sketch (`@file:JsModule`) is
   therefore wrong for wasm; `Xterm.kt`/`FitAddon.kt`/`WebglAddon.kt` carry the corrected shape and
   the reason. Kotlin lambdas DID work as `external` function PARAMETERS (`onData`, `ResizeObserver`
   through a `js()` helper) — only external *properties* reject them.
2. **The Compose canvas above an `HtmlElementView` stops painting — UNFIXED, measured precisely.**
   See "The interop hole" below.
3. `.xterm-rows` is EMPTY with the canvas/WebGL renderer — DOM text assertions are impossible
   without `screenReaderMode`. The browser check reads the tmux pane instead
   (`tmux -L muxterm capture-pane`), which is stronger evidence anyway (it proves the bytes reached
   the pty). Karma's `XtermPredictionSinkTest` reads cells through xterm's own buffer API.
4. The fixture (`scripts/test-broker.sh`) stubs `tmux` with an exit-0 script, so a web terminal
   cannot spawn there as-is; the run symlinks the real `/usr/bin/tmux` into the fixture's `stubbin`
   for the duration and kills ONLY the tmux sessions it created (the host's live `muxterm` server
   shares that socket). A future plan may want a `MUX_TEST_REAL_TMUX=1` switch in the script.
5. Prediction never ENGAGES on localhost (RTT ≪ the 40 ms `latencyThresholdMs` gate), by design —
   the renderer is covered by the Karma tests instead. `XtermPredictionPipeline` is desktop's
   `PredictionPipeline` minus the monitor (wasm is single-threaded and has no `synchronized`).

---

## Results — Task 4 (CodeMirror editor engine, 2026-09-12)

The committed cm6 bundle runs in the browser host, in a same-origin iframe, over **desktop's bridge
protocol unchanged**. Nothing in `:ui` or `:shared` had to move.

| Check | Result |
|---|---|
| `:web:compileKotlinWasmJs` | green (4 s incremental) |
| Karma (`:web:wasmJsBrowserTest`) | **45 tests** green (42 before + 3 `EditorBridgeIframeTest`), 1 m 44 s |
| `stageForBroker` | green (10 m 37 s cold); `editor/` = `index.html` + `cm6.js` + `editor-shim.js`, and the staged page loads the shim BEFORE `cm6.js` |
| Open a file from the tree (`notes.txt`) | the iframe is at `…/editor/index.html` and its `.cm-content` reads `hello from the fixture\nsecond line\nthird line` — the file's real first line |
| Type `ZZ` → cm6 `onChange` → Kotlin | `.cm-content` becomes `hello from the fixtureZZ`, the pane tab flips to `notes.txt ✓ ✕` (the save affordance appears — Kotlin's dirty state) |
| Ctrl+S → `onSave` → Kotlin → broker → disk | `/…/workdir/notes.txt` on disk becomes `hello from the fixtureZZ`. Both bridge directions proved by a file write, not a DOM read |
| Console | no `pageerror`, no console errors, in any phase |

Screenshots: session scratchpad `plan3/` — `e2-add-menu.png` (the pane "+" popover: Chat / Terminal /
Files / Changes / Display), `e3-files-pane.png` (the file tree split right), `e4-editor-open.png`
(CodeMirror painting the file), `e5-typed.png` + `e5-strip.png` (the edited doc; the strip crop shows
the dirty/save affordance), `e6-saved.png`.

**The shim is the SMALL variant** (`apps/web/editor/editor-shim.js`, ~20 lines of code). The plan's
Step 1 offered a big one that defines `window.AndroidEditor`'s ten hooks and
`window.webkit.messageHandlers.lsp`; that is unnecessary, because `:ui`'s `bridgeShimJs(queryFn)`
ALREADY defines both of those in terms of `window[queryFn]`, and this engine evals
`bridgeShimJs(QUERY_FN) + cmInit(...)` into the frame exactly as desktop's JCEF engine does. So the
page only has to supply the two ends of the TRANSPORT that JCEF's message router gives desktop for
free: `window.smxEditorQuery({request})` → `parent.postMessage`, and a `message` listener that runs
`"__smxEval:"`-prefixed code through `(0, eval)`. One shared, unit-tested definition of the ten hooks
for all four hosts; the web page owns no copy that could drift.

**Review follow-ups (second commit).** The bridge is now origin-checked on BOTH ends —
the shim drops any `message` whose `ev.origin` is not its own (source alone is not enough: a
cross-origin framer is still `window.parent`), and the engine's listener requires
`ev.origin === location.origin` alongside `ev.source === iframe.contentWindow`. The outer half of
that guard is a broker header: `serveStatic` (src/channels/web/static-serve.ts) now sends
`content-security-policy: frame-ancestors 'self'` on every static response, so a foreign page
cannot frame the editor and become that parent at all (3 bun tests). `onFrameLoad` treats a
SECOND load as renderer loss (un-ready + `planner.onRendererLost()` + re-armed timeout) so a
reloaded frame gets the document re-pushed instead of silently losing it; `adopt` clears
`failed`; the ready-timeout branch expires pending reads; `dispose` drops the diff region and
`detach` removes the `load` listener. And the test no longer inlines the shim — see finding 6.

**Findings:**

1. **A `srcdoc` frame's `location.origin` may be opaque.** The Karma test cannot load `editor/`
   (Karma serves only its own page), so it mounts the shim in a `srcdoc` frame — where
   `location.origin` can be the string `"null"` and a `postMessage` pinned to it would be silently
   dropped. The shim falls back to `"*"` for exactly that case and pins to the real origin
   otherwise; the parent→frame direction always pins to the app's origin, which a `srcdoc` frame
   inherits. Documented in the shim.
2. **`MessageEvent.source`/`.data` and `Window.postMessage` are not on kotlinx-browser 0.5.0**, nor
   is `ChildNode.remove`. Each crossing is a one-line `js(…)` helper at the bottom of
   `WebEditorEngine.kt` — the same shape `Xterm.kt` uses. Kotlin lambdas again worked fine as
   `js(…)` PARAMETERS (`setTimeout(cb, ms)`), and `addEventListener` takes a plain `(Event) -> Unit`.
3. **The engine is desktop's, minus the threading.** Wasm is single-threaded, so every `onEdt`
   marshal collapses to a direct call and `AtomicLong`/`@Volatile` are plain `var`s. The only real
   substitutions are `executeJavaScript` → `postEval` and JCEF's load handler → the iframe's `load`
   event. `EditorPushPlanner` does the rest: the document queued BEFORE the page loaded is flushed
   by `cmInit`, which the browser run confirms (the first `getContent` returns it).
4. **`WebEditorEngineFactory.state` is permanently `Ready`.** There is no runtime to start — the
   host already is a browser. A page that fails to load reports itself through
   `WebEditorEngine.failed`, whose 8 s ready timeout names the URL; a pre-flight HEAD would only
   move an equivalent failure earlier.
5. **KNOWN ISSUE, the editor shows it too:** the ~32 px pane tab strip above an interop element
   paints solid black (plan 3 Task 3 finding 2). It is NOT permanent here — while typing, the strip
   repainted correctly (`e5-strip.png` shows the real tab), then went black again after the save
   (`e6-saved.png`). So it is a stale-clear artifact that any Compose-side invalidation fixes, which
   matches the interop-hole diagnosis. **Re-checked after 2d98be01** (the terminal's "pane strip
   drawn above the interop hole" fix) on a freshly staged bundle: the editor pane's strip is STILL
   black, so whatever that commit did for the terminal does not reach the editor pane. Not
   investigated here — flagged for whoever owns the interop-clear fix.
6. **The shim reaches Karma as a test RESOURCE.** The inlined copy in `EditorBridgeIframeTest` was
   an assertion against itself, so the shipped file is now fetched at run time. Karma serves only
   what is in `config.files`, so it took both halves: an `editorShimTestResource` `Copy` task wiring
   `apps/web/editor/editor-shim.js` into the wasmJsTest processed resources (KGP lands those under
   `<karma basePath>/kotlin/`), and a `karma.config.d/editor-shim.js` snippet registering that one
   file `included: false` and proxying it to `/editor-shim.js`. The test `fetch`es that URL and
   injects the text into its stub page's `srcdoc`; a non-2xx throws rather than stubbing the shim
   out. The sha256-constant fallback was not needed.

7. **The fixture workdir is EMPTY** (`scripts/test-broker.sh` creates it and nothing else), contrary
   to the plan's note — the run seeded `notes.txt`, `main.rs` and `src/lib.ts` into it first. A
   future plan may want the seed script to drop a couple of files there for exactly this kind of
   check.

### The interop hole (C1) — measured, two fixes attempted, both ineffective

**Repro (2 min, no code):** stage the bundle, open the fixture session in headless Chrome, run
`document.body.style.background = "#ff00ff"`, add a Terminal through the pane "+", screenshot.

**Measurement (magenta = the page body showing through a transparent canvas):**

| | rect |
|---|---|
| interop element (`.xterm`'s host div, and CMP's wrapper) | `320,32 880×768` — **correct** |
| visible hole in the Compose canvas | `320,0 880×32` — the pane tab strip, magenta on every pixel of rows 0–31 |

So the hole is the element's rect **one vertical translation short**: CMP clears at the parent
Column's origin instead of the element's placed position, which is exactly the `PaneTabStrip` band.
Normally it is invisible-ish because `index.html`'s body is `#0b0b0b` — it reads as "the strip went
black". The strip still WORKS: clicking "Chat" at (350,16) switches tabs and the xterm leaves the
DOM. It is purely a paint bug.

**Attempt 1 — `Modifier.zIndex(1f)` on `PaneTabStrip` (PaneHost.kt:644).** Staged and re-measured:
the hole is still `320,0 880×32`, in pointer mode AND with touch emulation. z-order does not move a
clear-rect that is drawn at the wrong coordinates. **Not kept**, and not only because it is
ineffective: with that one line the FULL `:ui:jvmTest` suite goes red on
`DragReorderTest.anOverwrittenOrderDoesNotWedgeTheRestOfTheGesture` (3/3 runs), while HEAD is green
(2/2) and a no-op recompile of the same file is green (1/1). It passes in isolation and in a
`panes.*`+`session.*` run, and that test composes no pane code at all — a whole-suite timing
coupling in a test that documents its own past flakes. Shipping a line that fixes nothing and
reddens the suite was the wrong trade; the one-liner is trivial to re-apply if the upstream fix
turns out to need it.

**Attempt 2 — wrapping the interop node in `Box(Modifier.fillMaxSize().clipToBounds())`.** Staged
and re-measured: hole unchanged (`320,0 880×32`). The clear-rect is not subject to our subtree's
clip.

**Where that leaves it:** a CMP 1.11.1 `HtmlElementView` bug, reproducible in two lines of JS, worth
an upstream issue with the numbers above. The surface carries a NOTE next to `HtmlElementView` so
nobody re-tries the same two fixes. Task 4's editor pane mounts its iframe the same way and will
show the same band above it. Cosmetic mitigations that would work without upstream: paint the body
the theme's strip colour (hides the band, still hides the tabs) or inset the element by the strip
height (moves the hole into a deliberate gap) — neither is worth it.

### Review follow-ups also in this commit

`WebglAddon` is held in the attach state, registers `onContextLoss { dispose() }` (xterm then falls
back to its canvas renderer) and is disposed with the composition — a leaked WebGL context counts
against the per-page cap the Compose canvas already spends one of. Disposal is ordered in the
terminal's own `DisposableEffect`: `pred.teardown()` → `webgl.dispose()` → `term.dispose()`, and
`onRelease`/`onReset` only clear the observer and the attach flag, so a REUSED DOM node can never
`open()` a disposed terminal. `cursor()` documents that `term.write` is asynchronous, so the caret
can lag queued output exactly as the old PWA adapter's did (the engine rolls back from the stored
snapshot, never from a re-read). Focus is one predicate (`active && foreground`, `attached` gating
only the DOM call). Karma is **44** tests (+2: `Passthrough` with a multi-byte glyph, and the
`HideCaret`/`ShowCaret` bracket).
