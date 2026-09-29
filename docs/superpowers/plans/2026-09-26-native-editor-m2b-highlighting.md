# Native editor M2b: the highlighting layer (native backends). Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn M2a's raw binding into what an editor uses. A document's syntax is kept up to date in the background
as the user types. Colours for the visible lines come back as `editor-core` decorations, and they keep following
edits until the new parse lands. Embedded languages (Vue/HTML `<script>`/`<style>`, Markdown fences) are coloured by
their own grammar. Fold ranges are available for the M4 fold plugin. Every grammar works on JVM, Android and iOS:
the core set's tables are bundled, and the rest come from app resources.

**Architecture:**
```
editor-core EditorState ──(transactions)──► SyntaxPlugin (Extension: a StateField + a decorations facet)
      ▲                                           │  records ChangeSets since the last spans version,
      │ effects: SyntaxSpans(version, RangeSet)   │  maps the current spans through every edit at once
      │                                           ▼
SyntaxWorker (one per document, owns parser + trees, runs off the UI thread)
      rope snapshot + edits ─► edit tree ─► reparse ─► injections ─► highlight the viewport ─► effect
```
- The worker owns every native object: parsers, trees and queries. **No native handle ever sits inside an
  `EditorState`.** States are immutable and kept for undo, so their lifetimes can't be tied to native memory.
- The field holds only data: spans as a `RangeSet<Decoration>`, the viewport, a version counter, and the pending
  ChangeSets.

**Tech stack:**
- Kotlin 2.4.10 Multiplatform: the `nativeBacked` source set (jvm, android, ios), plus `commonMain` interfaces that
  M2c implements for wasmJs.
- `kotlinx.coroutines`. It's already in the version catalog, as `libs.coroutines.core` 1.9.0.
- `:editor-core` (`api` dependency) and `:editor-syntax`'s M2a binding.

**Not pre-verified.** Unlike M1 and M2a, this plan is written against M2a's API but was **not** prototyped. The
interfaces and the test specifications below are the contract. Implement by TDD: write each test first and watch it
fail. If an interface here turns out to be wrong in use, fix it, keep the tests' intent, and report the change.

Spec: `docs/superpowers/specs/2026-09-25-native-editor-design.md` §5 (the interface in §5.1, and the behaviour in
§5.2, which this plan implements).

## Ground rules
- The same as M2a: **no builds on the Linux host.** Use `scripts/editor/mac-sync.sh`, then
  `ssh mac "$MACENV; …"`. Heavy jobs run one at a time. Boot the emulator `-read-only` and shut it down afterwards.
- Commits end with a blank line + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `docs/` needs
  `git add -f`.
- Tests run on JVM for speed; the iOS simulator and the Android emulator run at Task 9.

## File map (`apps/editor-syntax/`)
| Path | What |
|---|---|
| `native/include/supermux_syntax.h`, `native/src/syntax_bridge.c`, JNI, cinterop | + `ses_parser_set_included_ranges`; review follow-ups (Task 1) |
| `src/commonMain/.../syntax/Backend.kt` | `SyntaxBackend`, `ParserHandle`, `TreeHandle`, `QueryHandle`: platform-free interfaces |
| `src/nativeBackedMain/.../syntax/NativeBackend.kt` | `SyntaxBackend` over M2a's `SyntaxParser`/`SyntaxTree`/`SyntaxQuery` |
| `src/commonMain/.../syntax/Languages.kt` | `LanguageRegistry`: file name → language id; query sources; table blobs |
| `src/commonMain/.../syntax/Captures.kt` | capture name → token class (`tok-…`) mapping |
| `src/commonMain/.../syntax/Edits.kt` | `ChangeSet` + `Rope` → `TextEdit`s (UTF-16 rows and columns) |
| `src/commonMain/.../syntax/Highlighter.kt` | one parse snapshot → spans for a range, including injections |
| `src/commonMain/.../syntax/SyntaxWorker.kt` | the background loop per document |
| `src/commonMain/.../syntax/SyntaxPlugin.kt` | the `editor-core` extension: field, effects, decorations |
| `src/commonMain/resources/queries/<lang>/{highlights,injections,folds}.scm` | query files |
| `native/queries.lock.json` | per language: where each query file came from (package / repo + commit), licence, sha256 |
| `tools/fetch-queries.py` | fills `src/commonMain/resources/queries` from the lock (run on the Mac, output committed) |

Query files are small, reviewed text with explicit licences, so they are **committed**. Grammar sources are not.

---

### Task 1: ABI additions and review follow-ups

**Files:** `native/include/supermux_syntax.h`, `native/src/syntax_bridge.c`, `native/src/syntax_jni.c`,
`src/*/Ses.*.kt`, `src/nativeBackedMain/.../Syntax.kt`, the C tests (`native/tests/`), `src/nativeBackedTest/...`

- [ ] **Step 1: Failing tests first**, in C (under ASan) and in `nativeBackedTest`:
  1. `includedRangesParseOnlyThoseRanges`. Parse `"<p>x</p><script>let a = 1;</script>"`, first with the html
     grammar, then with javascript restricted to the `<script>` content range. The JS tree's root spans exactly that
     range, and a `let` capture lands at the right UTF-16 offset.
  2. `setWithCaptureValueIsRefused`: `(#set! k @c)` gives `SES_ERR_QUERY`.
  3. `settingsKeepTheCaptureId`: `(#set! @a k "1") (#set! @b k "2")` on one pattern. The Kotlin API returns both,
     keyed by capture.
  4. `matchCallbackRunsOncePerMatch`: a pattern with 3 captures and one `#match?` makes exactly 1 upcall per
     match (count them in the callback).
  5. `matchLimitExceededIsReported`: a pathological query over a large generated document sets the new
     `exceededMatchLimit` flag.
- [ ] **Step 2: Implement:**
  - `SES_API ses_status ses_parser_set_included_ranges(ses_parser *p, const int32_t *ranges /* [start,end]* UTF-16 */, uint32_t count_ints, const ses_read_fn read, void *ctx)`.
    It needs the text to compute points (row and column), so it takes a reader. Pass NULL/0 to reset to the
    whole document.
  - Refuse a `#set!` whose value is a capture.
  - Settings records keep the capture id, and Kotlin decodes them into `SyntaxQuery.patternSettings(pattern): List<PatternSetting>` with
    `data class PatternSetting(val kind: Kind, val captureId: Int?, val key: String, val value: String?)`. Keep a
    `Map` convenience for the capture-less `#set!`.
  - Cache each `#match?` result per `(match id, predicate index)` inside one `ses_query_captures` call.
  - Report `ts_query_cursor_did_exceed_match_limit` through a new out-flag. In Kotlin, `captures(...)` returns a
    `Captures(val ints: IntArray, val exceededMatchLimit: Boolean)`.
  - `ses_tables_provide`: copy first, then hash the copy.
  - Bump `SES_ABI_VERSION` to 3.
- [ ] **Step 3:** Run the C tests (ASan) and `:editor-syntax:jvmTest` on the Mac, then commit:
  `feat(editor-syntax): included ranges, capture-keyed settings, per-match regex cache (ABI 3)`.

---

### Task 2: The platform-free backend interface

**Files:** Create `src/commonMain/.../syntax/Backend.kt` and `src/nativeBackedMain/.../syntax/NativeBackend.kt`;
tests in `src/nativeBackedTest/.../NativeBackendTest.kt`.

```kotlin
package dev.supermux.editor.syntax

/** One syntax implementation: native (ses_*) on jvm/android/ios, web-tree-sitter on wasmJs (M2c). */
interface SyntaxBackend {
    /** Language ids this backend can parse right now or after [ensureLanguage]. */
    val languages: Set<String>
    /** Make [language] usable (load bundled tables, or provide them from app resources). Idempotent. */
    fun ensureLanguage(language: String)
    fun newParser(language: String): ParserHandle
    fun newQuery(language: String, source: String): QueryHandle
}

interface ParserHandle : AutoCloseable {
    val language: String
    /** Restrict the next parses to these UTF-16 ranges ([start,end]* packed); empty = whole document. */
    fun setIncludedRanges(ranges: IntArray, text: TextSource)
    fun setTimeoutMicros(micros: Long)
    /** Parse; [old] must already carry every edit since it was produced. Throws SyntaxException(TIMEOUT). */
    fun parse(text: TextSource, old: TreeHandle?): TreeHandle
}

interface TreeHandle : AutoCloseable {
    fun edit(e: TextEdit)
    fun copy(): TreeHandle
    /** [start,end]* UTF-16 ranges whose syntax differs between this (edited) tree and [new]. */
    fun changedRanges(new: TreeHandle): IntArray
    val hasError: Boolean
}

interface QueryHandle : AutoCloseable {
    val captureNames: List<String>
    val patternCount: Int
    fun settings(pattern: Int): List<PatternSetting>
    /** [start, end, captureIndex, patternIndex]* over nodes intersecting [start, end); predicates applied. */
    fun captures(tree: TreeHandle, start: Int, end: Int, text: TextSource): Captures
}
```
`PatternSetting` and `Captures` move to `commonMain`. `NativeBackend` is a thin adapter over M2a's classes. Tests:
the M0 golden test, re-run through `SyntaxBackend` (`NativeBackend`), gives the same `GOLDEN` spans.

- [ ] Write the tests, implement, run on the Mac, commit: `feat(editor-syntax): platform-free SyntaxBackend over the native binding`.

---

### Task 3: Tables for every grammar from app resources

- **Packaging:** each code-only grammar's `.sesz` is packaged as a resource, `editor-syntax/tables/<lang>.sesz`:
  - JVM: jvmMain resources, staged from `build/gen` like the natives, with the manifest sha256.
  - Android: assets.
  - iOS: the framework's resources. Pick the mechanism that works for a static framework consumed by the iOS app;
    `compose.resources` is not available here, so use `NSBundle` lookup for a bundle copied by `:ios`, and document
    the copy step.
- **Loading:** `NativeBackend.ensureLanguage(lang)`: if `!SyntaxLanguages.hasTables(lang)`, it reads the resource
  and calls `provideTables`. A missing resource throws `SyntaxException(NO_TABLES)` naming the resource.
- **Tests:** `everyGrammarParsesAfterEnsureLanguage`. For every name in `SyntaxLanguages.names()`, call
  `ensureLanguage`, then parse a one-line sample, with no exception. This replaces `AllGrammarsTest`'s "reports
  NO_TABLES" arm.

- [ ] TDD it, then commit: `feat(editor-syntax): every grammar's tables load from app resources`.

---

### Task 4: Language registry and queries

**Files:** `Languages.kt`, `native/queries.lock.json`, `tools/fetch-queries.py`,
`src/commonMain/resources/queries/**`.

- **Language ids** follow the grammar names (`javascript`, `tsx`, `typescript`, `python`, `kotlin`, `bash`, …).
- **`LanguageRegistry.forFile(name: String): String?`:**
  - extensions: `.kt` `.kts` → kotlin, `.ts` → typescript, `.tsx` → tsx, `.mjs` `.cjs` `.js` `.jsx` → javascript,
    `.sh` `.bash` → bash, `.md` → markdown, `.yml` `.yaml` → yaml, …
  - special names: `Dockerfile`, `Makefile`, `CMakeLists.txt`, and `.bashrc`-style dotfiles.
  - The extension table must cover **every file type today's cm6 bundle recognises**. Transcribe the `switch (ext)`
    block in `apps/android/codemirror/cm6-entry.mjs` (~line 84). Where tree-sitter has no grammar for one, map it
    to `null` (plain text) and list those in the README.
- **Query sources (lock).** Per language, `highlights`, `injections` and `folds`. Preference order:
  1. the grammar's own npm package `queries/`, which is already sha-pinned;
  2. **Helix** (`helix-editor/helix`, `runtime/queries/<lang>/`, MPL-2.0) pinned by commit;
  3. **nvim-treesitter** (Apache-2.0) pinned by commit. Only use it if Helix lacks the language: its queries use
     nvim-only predicates.
  - For each file: source URL + commit/version, licence, sha256.
  - `fetch-queries.py` downloads the files, verifies them, and writes them under `src/commonMain/resources/queries/`,
    with a header comment naming the source and licence.
  - It resolves `; inherits: a,b`: Helix and nvim use this for ecma/jsx/typescript and for c→cpp. Inline the
    inherited files, in order, into the output file.
- **Predicates.** Some predicates would pass silently: `#lua-match?`, `#contains?`, `#has-ancestor?`, `#has-parent?`,
  `#not-*` variants, `#is?` `local`. `fetch-queries.py` must **rewrite or drop** the patterns that use them:
  - convert `#lua-match?` to `#match?` when the pattern is a plain character class;
  - otherwise drop the pattern.
  - Record every dropped pattern in the lock's `notes`. A predicate the backend doesn't understand must never ship.
- **Tests:**
  - `everyShippedQueryCompiles`. For every language and every query file: `newQuery` succeeds, and the query uses
    no unknown predicate. `SyntaxQuery.flags` bit 0 must be clear.
  - `forFileCoversTheCm6Extensions`. A table-driven test over the extension list transcribed from `cm6-entry.mjs`.
  - `kotlinHasHighlights`. A Kotlin sample yields `keyword`, `function` and `string` captures.
- **Regex dialect.** Query regexes are written for Rust's `regex` crate. Add a test that compiles **every
  `#match?` regex in every shipped query** with Kotlin `Regex` on JVM, iOS and Android, and checks it against a small
  set of strings, comparing with the result on the JVM. Rewrite any regex that fails to compile or behaves
  differently in `fetch-queries.py`, and record it.

- [ ] TDD it; commit the lock, the tool and the generated query files: `feat(editor-syntax): language registry and pinned, licensed query sources`.

---

### Task 5: Capture names → token classes

`Captures.kt`:
- a fixed vocabulary of about 25 **token classes** that the M3 theme will colour: `tok-keyword`, `tok-string`,
  `tok-string-special`, `tok-number`, `tok-constant`, `tok-constant-builtin`, `tok-comment`, `tok-function`,
  `tok-function-builtin`, `tok-method`, `tok-type`, `tok-type-builtin`, `tok-variable`, `tok-variable-builtin`,
  `tok-parameter`, `tok-property`, `tok-operator`, `tok-punctuation`, `tok-tag`, `tok-attribute`, `tok-namespace`,
  `tok-label`, `tok-escape`, `tok-regexp`, `tok-markup-heading`, `tok-markup-emphasis`, `tok-markup-link`,
  `tok-diff-plus`, `tok-diff-minus`;
- `fun tokenClassFor(capture: String): String?`. It maps capture names **by longest dotted prefix**:
  `function.method.builtin` → `tok-method`, `string.special.key` → `tok-string-special`, `punctuation.bracket` →
  `tok-punctuation`, and `_private` or unknown → `null` (not drawn). It covers the names used by tree-sitter's
  standard highlight names, Helix, and the npm packages.
- A test lists **every capture name that appears in any shipped query**, extracted by scanning the files, and
  asserts each maps to a class or is on an explicit, reviewed `IGNORED` list. This way a new query can't silently
  lose colour.

- [ ] TDD it, then commit: `feat(editor-syntax): capture names map to a fixed token vocabulary`.

---

### Task 6: ChangeSet → TextEdits

`Edits.kt`: `fun textEditsFor(changes: ChangeSet, before: Rope, after: Rope): List<TextEdit>`.
- Returns one `TextEdit` per `Change`, in order.
- Each edit's coordinates must be relative to the document **as the earlier edits in the list have already left
  it**, because tree-sitter applies edits sequentially. So compute each edit's start in B coordinates of the edits
  before it: process `iterChanges()` front to back and use `fromB` for starts.
- Rows and columns come from the rope (UTF-16 columns from the line start).
- **Tests:**
  - A random property test (500 cases). Applying the edits one by one to a plain String model, via each edit's
    `start`/`oldEnd`/`newEnd` and the inserted text, reproduces `after`.
  - Every row/column pair agrees with a String-based computation.
  - Multi-change sets, a CRLF-free multi-line insert, emoji, and a deletion across lines.

- [ ] TDD it, then commit: `feat(editor-syntax): translate editor-core change sets into tree-sitter edits`.

---

### Task 7: Highlighter: one snapshot → spans, with injections

`Highlighter.kt` takes one parsed snapshot and answers **spans for a range**.

```kotlin
/** One parse of one document version, including injected sub-trees. Owned by the worker. */
class ParsedDocument internal constructor(/* host tree, injection layers, text */) : AutoCloseable

class Highlighter(private val backend: SyntaxBackend, val language: String, private val registry: LanguageRegistry) : AutoCloseable {
    /** Parse [text] (reusing [previous] if given, which must already carry the edits). */
    fun parse(text: TextSource, length: Int, previous: ParsedDocument?): ParsedDocument
    /** Spans in [start, end): sorted, non-overlapping after priority resolution, each a token class. */
    fun spans(doc: ParsedDocument, start: Int, end: Int, text: TextSource): List<Ranged<Decoration>>
    /** @fold ranges intersecting the range, as UTF-16 [start, end) pairs. */
    fun folds(doc: ParsedDocument, start: Int, end: Int, text: TextSource): IntArray
}
```
- **Injections:**
  - Run the host language's `injections.scm` over the host tree.
  - For each match, the content node is `@injection.content`.
  - The language comes from `#set! injection.language "<name>"`, or from the text of `@injection.language`,
    normalised through `LanguageRegistry.aliasFor(name)`: `js` → javascript, `ts` → typescript, `sh` → bash,
    `py` → python, `yml` → yaml, the Markdown fence info strings, and so on.
  - `injection.combined` groups every match of a pattern into one included-ranges parse; otherwise there is one
    parse per match.
  - `injection.include-children` controls whether child nodes are excluded from the ranges.
  - Nest up to depth 3 (Markdown → HTML → JavaScript).
  - Reuse layer trees across parses by (language, pattern, position) where the ranges only shifted; otherwise
    re-parse the layer.
- **Priority:**
  - An injected span wins over the host span it overlaps.
  - Within one layer, tree-sitter's order (earlier pattern first) wins. Take the first capture for a given node
    range and resolve overlaps by innermost node.
  - Output is flattened into **non-overlapping** marks: simpler for M3, and spans are few per line.
  - `Decoration.Mark(setOf(tokenClass))` with `inclusiveStart = false`, `inclusiveEnd = false`.
- **Tests** (JVM first, then all platforms in Task 9):
  - Golden spans for small samples: JSON (the M0 sample), Kotlin, TypeScript/TSX, Python, Markdown with a
    ```` ```kotlin ```` fence, HTML with `<script>` and `<style>`, Vue SFC.
  - Store each expected dump as a text file under `src/nativeBackedTest/resources/golden/<name>.txt`, one
    `start-end tok-class` line per span.
  - **Review each golden by eye once**, and note in the commit message that it was reviewed. M2c reuses these
    goldens for web.
  - `injectedSpansWinOverHost`, `markdownFenceUsesTheFenceLanguage`, `unknownFenceLanguageStaysPlain`,
    `depthIsCapped`.
  - Folds: a Kotlin class with methods yields one fold per block.

- [ ] TDD it, then commit: `feat(editor-syntax): viewport highlighting with injections and folds`.

---

### Task 8: The worker and the editor-core plugin

`SyntaxWorker.kt` and `SyntaxPlugin.kt`.

**Plugin (`editor-core` Extension):**
```kotlin
object Syntax {
    /** The extension for one document. [language] null = plain text (no parsing). */
    fun extension(language: String?): Extension
    /** Effects the host/worker dispatch: */
    val setViewport: StateEffectType<IntRange>   // UTF-16 [start, end) the surface shows (+ margin)
    val spans: StateEffectType<SyntaxSpansUpdate>
    /** What the worker needs from a state: */
    fun snapshot(state: EditorState): SyntaxSnapshot?   // doc rope, version, viewport, pending changes
    fun folds(state: EditorState): IntArray
}
data class SyntaxSpansUpdate(val version: Long, val start: Int, val end: Int, val spans: RangeSet<Decoration>, val folds: IntArray)
```
- **The field** holds:
  - `version`: incremented on every doc-changing transaction.
  - `changesSince`: the ChangeSets since the last applied spans version, composed.
  - `spans`, `folds` and `viewport`.
- **When a transaction changes the doc,** the field maps `spans` and `folds` through `tr.changes` at once, which
  prevents flicker, and composes `changesSince`.
- **When a `spans` effect arrives for version V < current,** the field maps its spans through the changes since V.
  It keeps a bounded log of the last 64 ChangeSets keyed by version and drops an update older than that. Then it
  **replaces** the spans inside `[start, end)` and keeps the outside ones.
- It provides `decorationsFacet.compute(FacetDep.field(field)) { st -> st.field(field).spans }`.

**Worker** (`class SyntaxWorker(backend, registry, scope: CoroutineScope, dispatch: (TransactionSpec) -> Unit)`):
- `fun onState(state: EditorState)`. The host calls this after every transaction; it's cheap and never blocks. It
  posts the snapshot to a conflated channel.
- **The loop,** on the worker's own single-threaded dispatcher:
  1. Take the latest snapshot.
  2. Apply `textEditsFor` for every ChangeSet since its last parse to its tree: it keeps a log of ChangeSets by
     version from the snapshots.
  3. Reparse with `Rope.chunkAt` as the `TextSource`. The rope snapshot is immutable, so this is safe off the UI thread.
  4. Compute spans for the viewport plus a margin of 1 screen above and below.
  5. `dispatch(TransactionSpec(effects = listOf(Syntax.spans.of(update))))`.
- The worker owns and **closes** every native handle when its scope is cancelled (`close()`).
- **Limits:**
  - A parse timeout of 200 ms. If it hits, the language goes to plain text for this document and the worker
    dispatches an empty spans update with a `syntaxOff` flag; the M3 UI shows "syntax off".
  - A document over 5 MB, or with a line over 20,000 units, is plain text: no parse.
- **Tests** (JVM, with a test dispatcher and a real native backend; use `runBlocking` + a real single-thread
  executor if `kotlinx-coroutines-test` is inconvenient):
  - `typingMapsSpansImmediatelyThenReplacesThem`. Type into the middle of a Kotlin file. Right after the
    transaction the old spans have shifted (no gap). After the worker runs, spans equal a fresh full highlight.
  - `staleUpdateIsMappedThroughLaterEdits`. Dispatch an update for version V after two more edits; the spans land
    in the right places.
  - `viewportChangeRequestsNewSpans`.
  - `hugeDocumentIsPlainText`, `timeoutTurnsSyntaxOff`, `closeFreesEveryTree` (use `ses_debug_live_trees`).
  - A **random edit soak**: 300 random edits to a Kotlin file with the worker racing. When it's idle, the spans
    equal a fresh highlight of the final text.

- [ ] TDD it, then commit: `feat(editor-syntax): background syntax worker and the editor-core syntax plugin`.

---

### Task 9: Everything on every native platform

- [ ] Run `:editor-syntax:jvmTest`, `jvmResourceLoadTest`, `iosSimulatorArm64Test` and `connectedAndroidTest` on
  the Mac. The emulator runs read-only and is shut down afterwards. All must pass, **including the golden span files,
  identical on all three**.
- [ ] Measure and record in the README, on the Mac JVM and the iOS simulator:
  - full parse + highlight of a 10k-line Kotlin file;
  - an incremental reparse after one keystroke;
  - viewport highlight time for 60 lines;
  - the same on the Android emulator, where feasible.

  Budget: an incremental reparse plus viewport highlighting under **8 ms** on the Mac JVM. Report if it's over.
- [ ] Append a dated entry to `~/.mux/domains/_inbox.md` covering the API shape M3 and M2c depend on, the query
  provenance policy, and the performance numbers. Commit the README.

## Not in M2b
- **M2c:** the wasmJs backend (web-tree-sitter) implementing `SyntaxBackend`, grammar `.wasm` builds from the same
  pinned and patched `parser.c`, and native-vs-web golden comparison using Task 7's golden files.
- **M3:** drawing the token classes (theme), the viewport effect from the real surface, and the "syntax off" note.
- **M4:** indentation (`indents.scm`), bracket matching, and the fold UI, all built on `Syntax.folds` and the tree API.
