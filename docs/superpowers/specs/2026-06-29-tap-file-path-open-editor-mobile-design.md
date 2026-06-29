# Tap a file path in a message to open it in the editor (iOS + Android)

**Status:** proposed
**Author:** supermux-11 (with Ahmet / testflight2)
**Date:** 2026-06-29
**Related:** web implementation — `src/web-app/src/lib/file-path-ref.ts`,
`src/web-app/src/lib/markdown.ts`, `src/web-app/src/lib/workdir-display.ts`,
`src/web-app/src/components/MessageText.vue`, `src/web-app/src/views/ChatView.vue`

## Summary

The web PWA already lets you **tap a file path in an agent's reply** to open that
file in the editor pane and scroll to the cited line. Bring the same behavior to
the native **iOS (SwiftUI)** and **Android (Compose)** apps, at full parity:

- Detect file paths in agent message text (`src/foo.ts`, `./bar.py`,
  `~/x/y.kt`, `/abs/p.rs`), including a `:line` or `:line-start-line-end` suffix.
- Render them as **tappable, tinted** spans inside the message.
- On tap: resolve to a project-relative path, open the editor pane, load the
  file (existing `fsRead` plumbing), and **scroll to the line/range**.

The detection + path-resolution logic goes in the **shared KMP module** (one
tested Kotlin implementation, used by both apps — mirroring web's TypeScript).
The only net-new editor capability is **jump-to-line**, added once to the shared
CodeMirror bridge and exposed to both apps.

## Goals

1. Bare file paths in agent messages become tappable on iOS and Android, with the
   **same detection rules as web** (relative/absolute/home paths, `:N` / `:N-M`
   suffix, known code extensions only).
2. Tapping opens the editor pane and the correct file, on both platforms.
3. Tapping `foo.ts:42` (or `:42-50`) also **scrolls to that line/range**.
4. A path **outside the session's project** shows the same "file is outside this
   project" notice web shows, and does nothing else.
5. Detection + workdir-resolution logic is **shared (KMP) and unit-tested**, ported
   from web's tests so the two stay in lockstep. UI/wiring is per-platform.

## Non-goals

- No broker changes. The read endpoint (`GET /sessions/:id/fs/read?path=`) and the
  shared `BrokerApi.fsRead` already exist and are used by both editors.
- No new path patterns beyond web's (no directories, no non-code extensions, no
  bare filenames without a directory segment — matching web's regex).
- No changes to the web app.
- Not adding markdown-preview-inside-editor path tapping (web's `MarkdownPreview`
  reuse) — chat messages only, for this pass. (The shared parser change makes it
  trivial to extend later.)
- No new WebSocket frames. (Web sends `editor_open`/`editor_close` for fs-watch;
  native already manages its own editor lifecycle, so nothing new is needed.)

## Background — how web does it (the parity target)

1. **Detect** — `file-path-ref.ts` defines `FILE_PATH_BODY` (the path shape) and
   `FILE_PATH_MATCH_RE` (path + optional `:N`/`:N-M` suffix). `parseFilePathRef`
   returns `{ path, line?, endLine? }`; it **rejects** non-numeric suffixes and
   inverted ranges (`:20-10` → null).
2. **Linkify** — `markdown.ts`:
   - `linkifyFilePaths(html)` splits on tags and runs `FILE_PATH_MATCH_RE` over
     text nodes; a match is linked **only if `hasKnownExtension`** (a fixed set of
     known code extensions — `ts/tsx/js/.../kt/swift/.../lock`). Produces
     `<a class="file-link" data-path data-line data-line-end>` (no `href`, so
     nothing navigable survives sanitization). NB this runs over the **whole**
     rendered HTML, so paths inside inline `code` spans are linkified too — a very
     common shape in agent output (`` `src/foo.ts:42` ``).
   - The `marked` `link` renderer also turns markdown `[label](path:line)` anchors
     into the same file-link when the href parses to a known-extension path.
3. **Tap** — `MessageText.vue` `onClick` finds `a.file-link`, reads
   `data-path/-line/-line-end`, emits `openFile(path, line?, endLine?)`.
4. **Route** — `ChatView.handleOpenFile` calls
   `toWorkdirRelativePath(path, workdir, homeDir)`; **null → toast "File is outside
   this session's project"** and stop. Else open the editor pane/tab and call
   `editorOpenFile(rel, line, endLine)`.
5. **Open + reveal** — `useEditor.openFile(rel)` fetches via `api.fsReadFile` and
   adds a tab; `EditorPane.revealFile` sets a `revealPosition` (converting the
   1-indexed line to 0-indexed); `CodeEditor.vue` dispatches a CodeMirror selection
   with `scrollIntoView`. A range selects `line..endLine`.

## Current native state

- **Shared markdown** (`apps/shared/src/commonMain/kotlin/dev/supermux/ui/Markdown.kt`):
  `SpanStyleKind { PLAIN, BOLD, ITALIC, CODE }`, `MdSpan(text, kind)`,
  `parseInlineMarkdown` (bold/italic/code only). **No LINK kind, no path detection.**
  Used by Android's renderer. (iOS does **not** use this; see below.)
- **iOS messages** (`apps/iosApp/Supermux/Chat/ChatMessages.swift` → `MessageRow`):
  agent text rendered by `MarkdownView`; flow blocks become a `UITextView`
  (`SelectableText` in `MarkdownView.swift`) from an `NSAttributedString`. Inline
  styling via `AttributedString(markdown:)` (so explicit `[label](url)` links →
  `.link`, but **bare paths are not linked**). `dataDetectorTypes = []`, **no
  `UITextViewDelegate`**.
- **Android messages** (`apps/android/.../chat/Timeline.kt`): `AssistantMessage` →
  `MarkdownBody` → renders `parseInlineMarkdown` spans as a Compose `Text`. **No
  clickable spans.**
- **iOS editor:** `EditorPane.openFile` → `EditorState.openFile(path)` →
  `fsRead` (→ `BrokerSession.fsRead` → `api.fsRead`). Webview bridge
  (`EditorWebView.swift`) has `cmSetScrollTop`/`cmGetScrollTop` only. Navigation:
  iPhone `PaneTab.editor` tab in `ChatView`; iPad `panes.editorOpen` in
  `IPadWorkspace`. **No line reveal.**
- **Android editor:** `EditorScreen.revealFile(path)` → `EditorState.openFile` →
  `fsRead`. `EditorEngine` has `cmSetScrollTop`/`cmGetScrollTop` only. The editor
  is `SessionPanel.Editor` in `ChatScreen`. **No line reveal.**
- **Shared CodeMirror** (`apps/android/codemirror/cm6-entry.mjs`): the single
  source for the editor webview. Built (per its `README.md`) into `cm6.js` and
  shipped to **both** `apps/android/src/main/assets/editor/` and
  `apps/iosApp/Supermux/EditorWeb/`. Exposes `window.cmInit/cmSetContent/
  cmGetScrollTop/cmSetScrollTop/…`. **No `cmRevealLine`.**

## Shared (KMP) changes

### 1. `FilePathRef.kt` — detection + parse (new, tested)

New file `apps/shared/src/commonMain/kotlin/dev/supermux/ui/FilePathRef.kt`,
package `dev.supermux.ui`. Faithful port of `file-path-ref.ts`:

```kotlin
data class FilePathRef(val path: String, val line: Int? = null, val endLine: Int? = null)

// Same body/match regex as web (Kotlin escaping). Lookbehind/ahead `(?<!\w)…(?!\w)`.
val FILE_PATH_BODY: String
val FILE_PATH_MATCH_RE: Regex   // global match over a text run

fun parseFilePathRef(raw: String): FilePathRef?      // null on bad/inverted suffix
fun stripFilePathRefSuffix(raw: String): String
fun formatFilePathRef(ref: FilePathRef): String

data class FilePathMatch(val start: Int, val end: Int, val ref: FilePathRef, val display: String)
/** All known-extension path matches in a plain text run (range + parsed ref). */
fun findFilePathRefs(text: String): List<FilePathMatch>

private val FILE_EXTENSIONS: Set<String>             // identical set to web's
fun hasKnownExtension(path: String): Boolean
```

- `findFilePathRefs` is the native equivalent of `linkifyFilePaths` minus HTML: it
  returns the **character ranges** to linkify in a plain run (only known
  extensions, only valid refs). Both platforms consume this.
- Kotlin `Regex` supports lookbehind/lookahead; the pattern is the same shape as
  web's. Verified against the ported test cases (below).

### 2. `WorkdirPath.kt` — workdir-relative resolver (new, tested)

New file `apps/shared/src/commonMain/kotlin/dev/supermux/ui/WorkdirPath.kt` (or
fold into an existing `session` util). Port of `toWorkdirRelativePath` +
`normalizeWorkdirKey` + `inferHomeDir` from `workdir-display.ts`:

```kotlin
fun toWorkdirRelativePath(path: String, workdir: String, homeDir: String?): String?
```

Returns the project-relative path, `""` for the workdir root, or **`null` when the
path resolves outside the workdir** (the signal both apps use to show the "outside
this project" notice). NB: `apps/shared` may already have an `inferHomeDir`/
`formatWorkdir` in `dev.supermux.session.SessionGrouping` — reuse/normalize rather
than duplicate if the semantics match; otherwise add a local normalizer matching
web's `normalizeWorkdirKey`.

### 3. `Markdown.kt` — `LINK` span (extend)

```kotlin
enum class SpanStyleKind { PLAIN, BOLD, ITALIC, CODE, LINK }   // + LINK
data class MdSpan(val text: String, val kind: SpanStyleKind, val ref: FilePathRef? = null)  // + ref
```

`parseInlineMarkdown`: after the existing bold/italic/code handling, run
`findFilePathRefs` over each emitted **PLAIN and CODE** run and split it into spans,
turning each matched range into a `LINK` span carrying the `FilePathRef`. Covering
CODE runs is important — agents very often cite paths inside inline code
(`` `src/foo.ts:42` ``), and web linkifies those too. The matched path text becomes
a LINK span (link styling wins over the original PLAIN/CODE styling, matching web's
`<a>` look); surrounding text keeps its original kind. `ref` defaults null so
existing exhaustive `when`s only need one new arm for `LINK`.

### 4. Tests (`commonTest`, `kotlin.test`)

Ported 1:1 from `file-path-ref.test.ts` and `workdir-display.test.ts`:

- `parseFilePathRef`: single-line, ranges, bare, absolute+line, home+range,
  reject non-numeric suffix, reject inverted range.
- `findFilePathRefs`: detects a path mid-sentence; honors `(?<!\w)`/`(?!\w)`
  boundaries; **skips unknown extensions**; multiple matches in one run; a `:N-M`
  suffix is captured; trailing punctuation not swallowed.
- `toWorkdirRelativePath`: relative pass-through, `./` strip, absolute-under-workdir
  strip, `~`-under-workdir strip, **outside → null**, suffix stripped before resolve.

These are the real runnable verification of the core (run on JVM via Gradle
`commonTest`).

## Android (Compose) changes

File: `apps/android/.../chat/Timeline.kt` (+ the call chain from `TimelineItemRow`).

- **Render LINK spans tappable.** Where `MarkdownBody`/`AssistantMessage` builds the
  `AnnotatedString` from `parseInlineMarkdown` spans, render a `LINK` span with the
  theme accent color and attach a `LinkAnnotation.Clickable` (or build an
  `AnnotatedString` with `withLink`/`LinkAnnotation`) whose listener calls
  `onOpenFile(ref)`. Use `LinkAnnotation` (Compose 1.7+ `Text` with links) so text
  selection/long-press still works; avoid the deprecated `ClickableText`.
- **Thread the callback.** Add `onOpenFile: (FilePathRef) -> Unit` to
  `AssistantMessage`/`MarkdownBody` and through `TimelineItemRow` up to where the
  timeline is hosted (`ChatScreen`).

File: `apps/android/.../chat/ChatScreen.kt`

- Implement `onOpenFile(ref)`:
  1. `val rel = toWorkdirRelativePath(ref.path, session.workdir, home)` —
     `null` → `snackbar`/toast "File is outside this session's project"; return.
  2. Ensure `SessionPanel.Editor` is in `openedPanels` and set
     `activePanel = SessionPanel.Editor`.
  3. Drive the editor: `editor.openFile(rel)` then, if `ref.line != null`,
     `editor.revealLine(ref.line, ref.endLine)`.
- **Reach the editor's `EditorState` from the chat screen.** Today `EditorPanel`
  builds/owns its `EditorState` internally and exposes `revealFile(path)` as an
  internal lambda. Hoist the per-session `EditorState` (or pass an imperative
  "open+reveal" handle out of `EditorPanel` via a callback ref / shared holder in
  `AppViewModel`) so `onOpenFile` can call it. Prefer the smallest change that lets
  chat call `openFile`+`revealLine` on the same `EditorState` the panel renders.

File: `apps/android/.../editor/EditorState.kt` / `EditorEngine.kt`

- `EditorState.revealLine(line: Int, endLine: Int?)`: remember a pending reveal and
  call `engine.revealLine(...)`; if the doc isn't loaded yet, apply on load.
- `EditorEngine.revealLine(line, endLine)`: `evaluateJavascript("cmRevealLine(line, endLine)")`,
  deferred until `ready` (same pattern as `lastScrollTop` is pushed on ready). When
  `openFile` loads new content, the pending reveal fires after `pushToView`.

## iOS (SwiftUI) changes

File: `apps/iosApp/Supermux/Chat/MarkdownView.swift`

- **Inject path links into the `NSAttributedString`.** After building the inline
  `NSAttributedString` for a flow block, run a pass over its plain text using the
  shared `FilePathRef.findFilePathRefs(text)` and, for each match range, set
  `.link` to a custom URL `supermux-file:///<pct-encoded path>?line=N&end=M` and a
  teal foreground/underline. (Markdown `[label](path)` already yields `.link`; we
  additionally normalize a `file://`/known-extension href to the same custom scheme
  so the delegate handles both.)
- **Intercept taps.** Add a `Coordinator: NSObject, UITextViewDelegate` to
  `SelectableText`; implement
  `textView(_:shouldInteractWith:in:interaction:) -> Bool`: if the URL scheme is
  `supermux-file`, parse path/line/end, call `onOpenFile(ref)`, return `false`
  (don't let UIKit navigate). Keep `dataDetectorTypes = []`. Selection still works.
- Thread `onOpenFile: (FilePathRef) -> Void` from `MessageRow` (ChatMessages.swift)
  up to the chat container.

File: `apps/iosApp/Supermux/Chat/ChatView.swift` (+ `IPadWorkspace.swift`)

- Implement `onOpenFile(ref)`:
  1. `toWorkdirRelativePath(ref.path, session.workdir, homeDir)` — `nil` → a toast
     "File is outside this session's project"; return.
  2. Bring the editor forward: iPhone set `tab = .editor`; iPad
     `layout.setPanes(editorOpen: true, …)`.
  3. `editorState.openFile(rel)` then, if line present, `editorState.revealLine(...)`.
- Use the memoized `BrokerSession.editorState(for: session.id)` so chat and the
  `EditorPane` share one `EditorState`.

File: `apps/iosApp/Supermux/Editor/EditorState.swift` / `EditorWebView.swift`

- `EditorState.revealLine(_ line: Int, _ endLine: Int?)`: stash a pending reveal;
  after `openFile`'s content is set, push it.
- `EditorWebView` coordinator: `func revealLine(_ line: Int, _ endLine: Int?)` →
  `evaluate("cmRevealLine(\(line), \(endLine ?? -1))")`, queued until `ready` like
  `lastScrollTop`. On a fresh file load, fire the pending reveal after content set.

## CodeMirror bridge change (shared, both apps)

File: `apps/android/codemirror/cm6-entry.mjs`

Add:

```js
// 1-indexed line; endLine optional (<=0 or === line → caret only).
window.cmRevealLine = function (line, endLine) {
  if (!view) return
  const doc = view.state.doc
  const ln = Math.max(1, Math.min(line || 1, doc.lines))
  const from = doc.line(ln).from
  let sel = { anchor: from }
  if (endLine && endLine > ln) {
    const en = Math.min(endLine, doc.lines)
    sel = { anchor: from, head: doc.line(en).to }
  }
  view.dispatch({ selection: sel, effects: EditorView.scrollIntoView(from, { y: "center" }) })
  view.focus()
}
```

Then **rebuild and redistribute** the bundle per `apps/android/codemirror/README.md`
(`bun build … --outfile apps/android/src/main/assets/editor/cm6.js …`) and copy the
built `cm6.js` to `apps/iosApp/Supermux/EditorWeb/`. (Confirm whether iOS consumes
`cm6.js` directly or an index that references it; ship to wherever iOS loads from.)

## Data flow (end to end)

```
agent message text
  → findFilePathRefs (shared)            // ranges of known-extension paths + ref
  → render LINK span (Android) / .link attr (iOS), tinted + tappable
  → tap → onOpenFile(ref)
  → toWorkdirRelativePath(ref.path, workdir, home)  // null → "outside project" notice
  → bring editor pane forward (tab/panel)
  → EditorState.openFile(rel)            // existing fsRead → content into CodeMirror
  → EditorState.revealLine(line, endLine)// → cmRevealLine once webview ready
```

## Detection parity (must match web exactly)

All rows below were verified by running the actual web `FILE_PATH_MATCH_RE` +
`parseFilePathRef` against the input; the Kotlin port must reproduce them exactly.

| Input in message            | Linkified?              | Result                                  |
|-----------------------------|-------------------------|-----------------------------------------|
| `src/main.ts`               | yes                     | path `src/main.ts`                      |
| `./src/main.ts`             | yes                     | path `./src/main.ts` (resolves→`src/…`) |
| `~/p/app/foo.ts:42`         | yes                     | path, line 42                           |
| `/home/u/app/a.rs:5-15`     | yes                     | path, line 5, endLine 15                |
| `src/file.ts:abc`           | yes — **path only**     | path `src/file.ts`; `:abc` left as text |
| `src/foo.ts:20-10` (inverted) | **no** — whole ref rejected (`line>endLine`→null), stays fully plain | — |
| `assets/logo.png` (unknown ext) | no                  | matches body but filtered by extension  |
| `file.ts:42` (no directory) | no                      | body needs a `/` segment → no match     |
| `justafilename`             | no                      | no match                                |

Two non-obvious rules to preserve: (1) a path needs **at least one `/` segment**
before the filename (bare `file.ext` never matches); (2) an **inverted range**
makes the *entire* reference non-clickable (not just the suffix) — `parseFilePathRef`
returns null and web leaves the whole token as plain text.

## Error handling & edge cases

- **Outside project** (`toWorkdirRelativePath` → null): toast/snackbar "File is
  outside this session's project"; no pane change.
- **Missing/unreadable file:** `fsRead` fails → existing editor load-error UI
  (`EditorState.loadError` on both). No new handling.
- **No line ref:** open the file, no reveal.
- **Same file already open:** focus the existing tab (existing `openFile` behavior),
  then reveal the new line.
- **Reveal before content ready:** queued; fires after the doc is pushed (mirrors
  how `scrollTop` is applied on `ready`).
- **Selection vs tap (iOS):** delegate returns `false` for our scheme; normal text
  selection drag is unaffected. (Android `LinkAnnotation` preserves selection.)

## Testing & verification

- **Shared (real):** `kotlin.test` unit tests for `FilePathRef` (`parseFilePathRef`,
  `findFilePathRefs`) and `toWorkdirRelativePath`, ported from the web tests; run via
  Gradle `commonTest` on the JVM. This is the authoritative verification of the core
  logic and web parity.
- **CodeMirror bundle:** rebuild must succeed (`bun build` on this Linux host with
  `src/web-app/node_modules`); a tiny manual/JS sanity check that `cmRevealLine`
  exists on the built bundle.
- **Android:** Kotlin compile / `assembleDebug` **if** the Android SDK is on the
  host; else static review. Optional: a small instrumented/robolectric check that a
  message containing `foo.ts:42` yields a LINK annotation with the right ref.
- **iOS:** building SwiftUI needs the **remote Mac** (no Xcode on this Linux host).
  Plan: static review here; a device/sim build + manual tap test on the remote Mac
  if available. **Verification limits will be stated explicitly in the final
  report — no "it works on iOS" claim without an actual build/run.**
- **Manual matrix (per platform, on sim/emulator/device):** tap a bare path → opens;
  tap `file:line` → opens + scrolls; tap `file:line-range` → selects range; tap an
  outside-project path → notice; tap a non-existent file → load error.

## Implementation order

1. **Shared:** `FilePathRef.kt` + `WorkdirPath.kt` (`toWorkdirRelativePath`) +
   `Markdown.kt` LINK span + `commonTest` tests. **Run the tests.**
2. **CodeMirror:** add `cmRevealLine`, rebuild bundle, ship to both asset dirs.
3. **Android:** tappable LINK rendering → `onOpenFile` → ChatScreen routing →
   `EditorState.revealLine`/`EditorEngine.revealLine`. Compile if SDK present.
4. **iOS:** `.link` injection + `UITextViewDelegate` → `onOpenFile` → ChatView/iPad
   routing → `EditorState.revealLine`/`EditorWebView.revealLine`. Build on remote
   Mac if available.

Steps 3 and 4 are independent once 1–2 land and can proceed in parallel.

## Risks / open questions

- **Regex parity in Kotlin:** Kotlin `Regex` (java.util.regex) supports the
  lookbehind/lookahead used; the port must be checked against the ported cases
  (covered by tests). Watch `\w` semantics (ASCII vs Unicode) — pin with tests; use
  the same character classes as web.
- **Android `EditorState` reachability from chat:** the cleanest hoist needs a small
  refactor of how `EditorPanel` owns its state. Keep it minimal; the plan will pick
  the exact seam.
- **iOS `cm6.js` location:** confirm the iOS `EditorWeb/` asset that's actually
  loaded (index.html references) and ship the rebuilt bundle there.
- **Reveal timing:** both editors must defer reveal until content+`ready`; reuse the
  existing scroll-restore deferral rather than inventing a new ready signal.
