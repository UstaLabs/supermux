# editor-syntax native: tree-sitter behind the `ses_*` ABI

This directory builds the native half of `:editor-syntax`: tree-sitter
v0.25.10 behind an owned C ABI ([`include/supermux_syntax.h`](include/supermux_syntax.h)),
with every grammar's code compiled in and its parse tables moved into
compressed blobs. The Kotlin bindings call only `ses_*`: JNI on Android and the
desktop JVM ([`src/syntax_jni.c`](src/syntax_jni.c)), cinterop on iOS
(`../src/nativeInterop/cinterop/syntax.def`). The web client runs this same C,
compiled to one wasm32 module (M2c, see [Web](#web-m2c)).

Everything is UTF-16 code units end to end. tree-sitter parses with
`TSInputEncodingUTF16LE`, so a byte offset is exactly 2 x the UTF-16 index.
[`src/syntax_bridge.c`](src/syntax_bridge.c) does that x2 / /2 and nothing else
converts offsets.

Spec: `docs/superpowers/specs/2026-09-25-native-editor-design.md` §5.

## Pipeline

Everything runs on the Mac, inside `apps/editor-syntax`. Generated files live
under `build/` and are never committed.

```bash
native/fetch.sh          # tree-sitter @ locked commit, zlib 1.3.1, every grammar tarball (sha256-checked) -> build/
native/build.sh gen      # per grammar: extract tables + differential test (ASan + UBSan), then ctest      -> build/gen/<lang>/
native/build.sh ctest    # the C ABI tests (tests/bridge_test.c) under ASan + UBSan                         -> build/ctest/
native/build.sh <target> # one library                                                                     -> build/natives/<target>/lib/
native/build.sh all      # gen + all ten targets + build/natives/manifest.json
native/wasm/build.sh --test  # the wasm32 target, then its Node tests (native/wasm/test.mjs)
```

1. **fetch** clones tree-sitter at the commit in `upstream.lock.json` and
   downloads zlib and every grammar in `grammars.lock.json` except the
   `excluded` ones. It refuses any tarball whose sha256 differs from the lock.
   Every extracted directory holds a `.sha256` stamp of what it was extracted
   from, so a version bump re-extracts it. Extraction uses tarfile's `data`
   filter where Python has it, and explicit path checks on Python 3.9.
2. **gen** runs [`../tools/build-grammar.sh`](../tools/build-grammar.sh) for each
   parser directory of each grammar. A package can hold several parsers, for
   example typescript + tsx, php + php_only, markdown + markdown_inline, three
   for ocaml and two for fsharp, so one package can produce several `lang`s.
   For each parser:
   - [`../tools/sestables.py`](../tools/sestables.py) `prepare` finds the
     static tables that `tree_sitter_<lang>()`'s `TSLanguage` points at.
   - A host-compiled dumper `fwrite()`s the table bytes. They come from the C
     compiler, never from parsing C initializers.
   - `emit` writes three files:
     - `parser_<lang>.c`: the original minus the tables. `tree_sitter_<lang>()` now
       calls `ses_grammar_language()`.
     - `<lang>.sesz`: the tables, zlib-compressed.
     - `blob_<lang>.c`: the `.sesz` as a C array.
   - [`tests/difftest.c`](tests/difftest.c) links the ORIGINAL and the
     TRANSFORMED grammar against the same runtime, then compares them:
     - every symbol, field and `(state, symbol)` transition, and every state's
       lookahead set;
     - a full cursor walk plus the S-expression of each input.

   The difftest and everything it links are built with
   `-fsanitize=address,undefined`. An ASan report is fatal. UBSan reports,
   which come from third-party grammar code, are counted at the end of `gen`.
   `gen` then runs `ctest`, the ABI tests, where every sanitizer report is fatal.

   Any difference fails `gen`. The inputs are the package's own `test/corpus`
   (or `corpus/`) and `examples/` when its npm tarball ships them (only dart,
   pascal and clojure do), plus
   [`tests/inputs/`](tests/inputs). The language-level comparison is exhaustive
   for every grammar whatever its inputs.
3. **targets** compile `tree-sitter/lib.c`, the bridge, the loader, JNI (not for
   iOS), and every grammar's `parser_<lang>.c` and scanner into one library.
   A grammar marked `bundled` also gets its `blob_<lang>.c`. Everything is
   built with `-DNDEBUG`, like tree-sitter's release builds, so an internal
   `assert` cannot abort the app. A shared library exports only `Java_*` and
   `ses_*`, which `build.sh` checks after linking:
   - macOS: `-exported_symbols_list`;
   - Linux and Android: a version script plus `--exclude-libs,ALL`, so the
     static libc++ cannot interpose on another library;
   - Windows: only the dllexport'ed JNI functions (the grammars'
     `tree_sitter_<lang>` are excluded).

| target | toolchain | output |
|---|---|---|
| `macos-arm64`, `macos-x64` | Xcode clang | `libsupermux_syntax_jni.dylib` |
| `linux-x64`, `linux-arm64` | `zig cc -target <arch>-linux-gnu.2.28`, zlib compiled in | `libsupermux_syntax_jni.so` |
| `windows-x64` | `zig cc -target x86_64-windows-gnu`, zlib compiled in | `supermux_syntax_jni.dll` |
| `ios-arm64`, `ios-simulator-arm64` | Xcode clang + libtool | `libsupermux_syntax.a` (static, for cinterop) |
| `android-arm64`, `android-x64` | NDK 26.1 clang, API 26, 16 KB pages, stripped | `libsupermux_syntax_jni.so` |
| `wasm32` | `zig cc` / `zig c++ -fno-exceptions`, `-target wasm32-wasi`, reactor, zlib compiled in | `supermux-syntax.wasm` |

Apple and Android link the system `-lz`. The Linux and Windows cross-builds need
zig on the Mac: `brew install zig`. `build/natives/manifest.json` records the
target, file, sha256 and size of every library. Gradle packages only libraries
that match it:
- the desktop jar gets `dev/supermux/editor/syntax/natives/<target>/`, read by
  `SesNativeLoader`;
- the Android AAR gets `jniLibs/<abi>/`;
- iOS gets the `-libraryPath` passed to cinterop.

**Linux and Windows libraries are cross-compiled but never run here.** Before a
desktop release, CI must load-test them on real Linux x64/arm64 and Windows x64:
run `:editor-syntax:jvmTest` and `jvmResourceLoadTest` there.

## Lock files

- `upstream.lock.json`: the tree-sitter repository, tag and commit, zlib's
  URL and sha256, and the zig version the wasm32 target insists on. The pin is v0.25.10 because it matches web-tree-sitter 0.25.10,
  so every client parses with the same core.
- `grammars.lock.json`: for each grammar, its npm package, version, tarball URL
  and sha256, licence, the parser directories inside the tarball, and `tables`:
  - `bundled`: the tables blob is linked into the library. This is the core set
    of spec §5.3.
  - `code`: only the code is linked. The runtime reports `SES_ERR_NO_TABLES`
    until the tables are handed over with `ses_language_provide_tables`, which
    M2b does from resources. The tests do it already: Gradle stages
    `build/gen/fsharp/fsharp.sesz` as a test resource, with a tampered copy.
  - `excluded`: not built; `note` says why.

  Optional `regenerate: {cli, abi, parserSha256}`, for when the published
  `parser.c` targets an ABI this runtime refuses:
  - `fetch.sh` rebuilds it from the package's own `grammar.json` with that exact
    tree-sitter CLI. `npx` downloads the CLI from npm, so fetching needs network
    and Node.
  - It then insists on the recorded sha256 of each regenerated `parser.c`, and
    fails loudly on a mismatch, before `gen` difftests the result.
  - Only clojure 0.4.0 needs this: its npm parser is ABI 9.

  Optional `licenseFile` / `licenseSource`: the licence text, when the package
  ships none (groovy and vb-dotnet: their repositories have no licence file
  either; their manifests declare MIT).

To bump a grammar:
1. Edit `docs/superpowers/notes/m0-artifacts/grammar-inventory*.tsv`.
2. Rerun `tools/make-lock.py`, which rewrites the lock with fresh sha256s.
3. Restore the hand-made fields (`excluded` / `note`) and review the diff.
4. On the Mac, run `native/fetch.sh && native/build.sh all`. The difftest must
   pass for the new version before it ships.
5. Run `tools/make-notices.py` there too, and commit the regenerated
   `THIRD-PARTY-NOTICES.md`.

Each grammar's code carries the SHA-256 of the exact `.sesz` it was generated
with. `ses_language_provide_tables` refuses any blob that hashes differently
before accepting it, so the loader refuses a blob for any other grammar version,
and a corrupted or altered one.

Bundled blobs are not re-hashed by default. They sit in the same read-only
binary as the hash, and hashing costs too much at first use: fsharp's 835 KB
took 5.2-5.5 ms on the JVM on an M-series Mac, 7.3-9.6 ms in the iOS simulator and
14-67 ms on the Android emulator, against a 5 ms budget. `-DSES_VERIFY_BUNDLED=1` turns
the check on.

A failed load is remembered, so a bad blob is inflated at most once.

## Tables resources (code-only grammars)

`build.sh` (every target, or `build.sh manifest`) lists each code-only grammar's
`build/gen/<lang>/<lang>.sesz` in `build/natives/manifest.json` under `tables`
(sha256 + size). Gradle's `stageTables` checks every blob against it and stages
them as resources `editor-syntax/tables/<lang>.sesz` (29 blobs, 5.4 MB):
- **JVM**: jvmMain resources, so the desktop jar carries them.
- **Android**: Java resources of every variant (`variant.sources.resources`), so
  the AAR carries them. They are read through the class loader like on the JVM,
  so the binding needs no `Context` (the plan said assets; resources work the
  same and keep the lookup identical to the desktop's).
- **iOS**: a static framework has no resources of its own. `SyntaxResources`
  looks in the app bundle, `<main bundle>/editor-syntax/tables/<lang>.sesz`,
  then in `SyntaxResources.extraDirectories`. **Copy step for `:ios`**: run
  `./gradlew :editor-syntax:stageTables`, then add an Xcode "Run Script" build
  phase before "Copy Bundle Resources" finishes:
  ```sh
  rsync -a "$SRCROOT/../editor-syntax/build/gradle/generated/tables/editor-syntax" \
        "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/"
  ```
  Wired but OFF: `apps/iosApp/project.yml` has a pre-build script that runs
  `:editor-syntax:stageTables` and copies `editor-syntax/tables/` into
  `Supermux.app`, gated by the build setting `SUPERMUX_EDITOR_SYNTAX_TABLES`
  (`"NO"`). No app module links `:editor-syntax` yet, so builds skip it and do
  not ship the 5.1 MB. **M3 turns it on** in the change that adds
  `api(project(":editor-syntax"))` to `:ios`: set the setting to `"YES"` in
  `project.yml` and regenerate the project. The script never fails the app
  build: stale natives or an empty tables directory (it needs at least one
  `.sesz`) print a warning and bundle nothing. The iOS simulator tests get the same directory next to their
  executable, which is their main bundle (`bundleTablesFor*`), and load all 29
  code-only grammars through that lookup (`MainBundleTablesTest`: ruby).

`NativeBackend.ensureLanguage(lang)` provides a code-only grammar's blob from
there on first use; a missing resource is `SyntaxException(NO_TABLES)` naming it.
The loader then checks the blob against the SHA-256 compiled into the grammar.

## Tables as data

A generated grammar is mostly parse tables. For fsharp, the tables are 11.2 MB
of its 11.4 MB, and they gzip to under 1 MB. Keeping the lexers and external
scanners compiled while moving the tables into zlib blobs is what makes it
possible to ship every grammar:
- The blob is inflated into memory on the grammar's first use (thread-safe, once).
- The strings tables are rebuilt as pointer arrays.
- The grammar fills its own `TSLanguage` ([`src/ses_grammar.c`](src/ses_grammar.c)).

Blob formats are documented in `sestables.py`.

**Native grammar code is never downloaded.** Apple's App Store and Google Play
both forbid downloading executable code, so every grammar's code ships inside
the library. Only tables, which are data, may later be fetched on demand and
handed to `ses_language_provide_tables`. The SHA-256 compiled into the code
guards that data: a downloaded blob that is not exactly the one generated with
this code is refused.

The generated `parser_<lang>.c` also carries a `_Static_assert` per byte table:
the target compiler must agree on each table's size with the host that laid out
the blob.

## Predicates

`ses_query_captures` evaluates these predicates inside the query cursor loop:
- `#eq?`, `#not-eq?`, `#any-eq?`, `#any-not-eq?`, `#any-of?` and `#not-any-of?`,
  in UTF-16, against the document.
- `#match?`, `#not-match?`, `#any-match?` and `#any-not-match?`, through a
  `ses_match_fn` callback.

A match failing a predicate is removed from the cursor, like tree-sitter's own
bindings do. Details:
- The regexes are a per-query table (`ses_query_regex`).
  `SyntaxQuery` compiles each one once, as a Kotlin `Regex`, and tests it with
  `containsMatchIn`.
- A malformed predicate makes the query fail with `SES_ERR_QUERY`: a capture
  where a string belongs, or the wrong arity. So does a `#set!` whose key or
  value is a capture (`(#set! key @c)`).
- tree-sitter returns a match once per capture; its predicates (and so its
  regex upcalls) run once per match within one `ses_query_captures` call.
- `#lua-match?` is not supported. It passes, and `ses_query_flags` bit 0 is set.
  The shipped queries never use it (`tools/fetch-queries.py` rewrites or drops it).
- `#set!`, `#is?` and `#is-not?` are returned per pattern, with their capture,
  by `ses_query_pattern_settings` and `SyntaxQuery.patternSettings` (a
  `List<PatternSetting>`; `settingsMap` is the capture-less `#set!` as a map).
  Other directives are ignored.
- Captures are `[start, end, captureIndex, patternIndex]`.
- A cursor keeps at most `SES_QUERY_MATCH_LIMIT` (65536) matches in progress.
  When it had to drop some, `ses_query_captures` sets its
  `out_exceeded_match_limit` flag (`Captures.exceededMatchLimit` in Kotlin).

## Included ranges and resumable parses (ABI 3)

- `ses_parser_set_included_ranges` restricts a parser to UTF-16 ranges for
  injected languages (a `<script>` element's content, a Markdown fence). The
  caller passes 6 uint32s per range: start, end, and both boundaries' row and
  UTF-16 column. The Kotlin side reads them from the Rope in O(log n)
  (`PointSource`; `RopeText` for a Rope, `LineTable` for any `TextSource`), so
  the C side never walks the text. Ranges must be sorted and must not overlap;
  NULL / 0 resets the parser to the whole document.
- A parse that hits its timeout returns `SES_ERR_TIMEOUT` and keeps its state:
  the next `ses_parser_parse` with the same text and old tree RESUMES it. The
  syntax worker parses in 50 ms slices that way and checks for a newer snapshot
  between slices. `ses_parser_reset` discards a timed-out parse (before parsing
  anything else).

## Queries (M2b)

`highlights.scm`, `injections.scm` and `folds.scm` per language live, committed,
in `src/commonMain/resources/queries/<lang>/`. The build compiles them into the
library as Kotlin strings (`BundledQueries`, task `generateBundledQueries`), so
every backend, the web included, reads the same text. They are generated by
`tools/fetch-queries.py` from `native/queries.lock.json` (run it on the Mac after
`native/fetch.sh` and `native/build.sh macos-arm64`; `--pin` records new sources):
- **Sources**, per file, in this order of preference: the grammar's own npm
  package (sha-pinned in `grammars.lock.json`); Helix at a pinned commit
  (MPL-2.0); nvim-treesitter at a pinned commit (Apache-2.0). Every fetched file,
  inherited ones included, is recorded with its sha256. The lock's `why` says
  where a file is taken out of that order (Kotlin's highlights, Markdown's
  injections, the TypeScript composition).
- **`; inherits:`** (Helix, nvim) is inlined, recursively, in order.
- **Precedence**: when several patterns capture one node, the LATER pattern wins,
  as in tree-sitter-highlight 0.25.10, Helix and nvim. npm files often put
  their catch-alls (`(identifier) @variable`, `"if" @keyword`) last: in npm
  highlights files the catch-alls are hoisted to the top and every other pattern
  keeps its order (reversing whole files broke mixed ones such as Go, whose
  `@function.builtin` follows `@function`).
- **Predicates**: `#lua-match?` becomes `#match?` when the Lua pattern converts;
  `#contains?` becomes an escaped `#match?`; `#is-not? local` and `#trim!` are
  removed; any other pattern with a predicate or directive the backend does not
  evaluate is dropped. `ShippedQueriesTest` fails if one ever ships.
- **Injections into a language with no grammar here** (the lock's
  `unavailableInjectionLanguages`: comment, regex, jsdoc, ...) are dropped: they
  can never be drawn and only cost query time. A file left with no pattern is not
  shipped (21 injection files and glsl's folds).
- **Regexes** are written for Rust's regex crate, where `\w` and `\d` are
  Unicode and POSIX classes ASCII. Kotlin's `Regex` differs per platform
  (java.util.regex's ASCII classes on the JVM, ICU's Unicode ones on Android), so
  the tool spells them out (`\w` -> `[\p{L}\p{M}\p{Nd}\p{Pc}]`, `[:upper:]` ->
  `A-Z`).
- **`--check`** regenerates into a temporary directory and fails unless the
  committed files and notes are exactly that; 404s are never cached. `golden/regexes.txt` holds every shipped regex against 68 samples;
  JVM, iOS and Android must all reproduce it.
- **Our grammar versions**: the queries are compiled against the built library;
  a node, field or token our grammar lacks drops that alternative of a `[...]`
  list, or else the pattern.
- Everything rewritten or dropped is listed in the lock's `notes`.

M2b also moved three grammars to the ones these query sets target: kotlin to
fwcd's, clojure to sogaiu's and groovy to murtaza64's, all git tarballs at the
commits Helix pins (`grammars.lock.json` `note`; fwcd's commits its `parser.c`,
ABI 14, so no regeneration). The npm packages M2a used (`@tree-sitter-grammars/tree-sitter-kotlin`
1.1.0, oakmac's `tree-sitter-clojure`, amaanq's `tree-sitter-groovy`) have other
node names and no query set anywhere.

### Plain text

`LanguageRegistry.forFile` covers every extension cm6's `langFor` recognises.
These have no tree-sitter grammar here and open as plain text: `.erl` `.hrl`
(erlang), `.cr` (crystal), `.coffee`, `.ini`, `.properties`, `.ps1` `.psm1`
`.psd1` (PowerShell), `.proto`, `.tex` `.latex`, `.diff` `.patch`, `.pug`
`.jade`, `.f` `.for` `.f90` `.f95` (Fortran), `.vbs`, `.cmake`, and the file
names `Dockerfile` (and `Dockerfile.*`), `CMakeLists.txt`, `Makefile`.
`.vb` parses (vb_dotnet) but no query set exists for that grammar, so it is
drawn as plain text too.

## Highlighting (M2b)

Everything above the binding is platform-free Kotlin (commonMain), so M2c's
web backend only implements `SyntaxBackend`:
- `SyntaxBackend` / `ParserHandle` / `TreeHandle` / `QueryHandle`; `NativeBackend`
  over the ses_* binding. `QueryHandle.matches(..., childrenOf)` groups captures
  per match and reports one capture's children (`ses_query_matches`).
- `Highlighter`: one parse (host + injected layers, nested up to depth 3) and
  spans / folds for a range. When several patterns capture one node the latest
  wins, an inner node wins over the outer one, `@none` clears, and injected
  layers paint over the host. The result is non-overlapping
  `Decoration.Mark(setOf("tok-..."))` marks. On a reparse the injections are only
  searched again where the tree changed or the text was edited; queries with
  `injection.combined` still use the whole document.
- `Syntax` (editor-core extension) + `SyntaxWorker` (one per document, its own
  single-threaded dispatcher). See the KDoc for the effect and snapshot API.
  - Each syntax field instance has an `epoch`: a replaced state (version 0
    again) is never painted with the old document's spans.
  - The host delivers the worker's dispatches in order (FIFO); the field also
    ignores an update older than one it has applied.
  - Spans are kept within 2 screens around the viewport, so mapping them through
    a keystroke stays under 1 ms on the UI thread.
  - A host language whose tables are missing turns syntax off for the document
    (`Syntax.isOff`); an injected one is skipped. A language that is not loaded
    yet is skipped for one cycle, loaded (`suspend ensureLanguage`), and the
    document is parsed again.
  - A parse runs in 50 ms slices within a 10 s budget; beyond it, syntax is
    off. Between slices it is abandoned only for another TEXT (epoch, version
    or document), never for a viewport or selection change, never when it is a
    first parse (no old tree to resume from), and only in the first half of the
    time a full parse of the document took; otherwise it finishes and the new
    edits apply incrementally. (Cancelling on every newer snapshot restarted a
    60k-line file's parse forever while scrolling: 147 restarts, no spans.)
- Queries are compiled once per backend and language (`sharedQuery`), not per
  document (kotlin's highlights cost ~35 ms to compile).

### On the web

See [Web (M2c)](#web-m2c): the web runs this layer unchanged, over the same C.

### Performance

10k-line documents (`PerfCases`: Kotlin 10,730 lines, Markdown 10,013 with
3,578 layers, a Vue SFC and a PHP file of 10k lines each), one keystroke in the
middle. "keystroke" = the edits plus the incremental reparse (every layer);
"+180" adds the spans of the worker's range (viewport of 60 lines plus one screen
each side); "cycle" = the whole worker cycle through a real `SyntaxWorker`,
from the transaction to the spans applied. Medians (ms), measured 2026-09-25 on
the M1 Mac while other sessions kept it at a load average of 9-16, so single
numbers are noisy:

| | Kotlin keystroke / +180 / cycle | Markdown | Vue | PHP |
|---|---|---|---|---|
| Mac JVM, BudgetTest (best of 3) | 0.67 / 2.3 / 4.7 | 1.5 / 2.2 / 7.8 | | |
| Mac JVM, PerfTest (one run) | 0.71 / 2.3 / 5.7 | 9.8 / 10.8 / 10.4 | 5.2 / 7.4 / 12.5 | 6.4 / 8.1 / 13.3 |
| iOS simulator, release binary | 2.2 / 5.7 / 10.1 | 4.4 / 5.1 / 10.5 | 9.3 / 11.6 / 14.7 | 5.5 / 6.7 / 11.7 |
| iOS simulator, debug binary | 0.9 / 11.4 / 31.8 | 28.7 / 32.3 / 45.5 | 5.9 / 19.2 / 27.9 | 6.0 / 12.2 / 28.7 |
| Android emulator, debug APK | 2.1 / 9.7 / 24.6 | 13.5 / 15.4 / 29.1 | 8.9 / 17.8 / 32.0 | 23.4 / 31.2 / 44.2 |
| Web: headless Chrome 153 on the Mac, wasm (2026-09-26) | 0.9 / 3.7 / 5.7 | 4.6 / 5.6 / 7.7 | 9.7 / 12.5 / 15.0 | 14.7 / 16.7 / 19.1 |

**Known worst case: flat Markdown.** A Markdown file without headings is one
flat sequence of blocks, and tree-sitter-markdown's incremental reparse of it
costs 25-75 ms per keystroke on the JVM (upstream behaviour; the sectioned file
above takes 1-10 ms, because sections bound what the block parser rescans).
PerfTest measures both ("markdown-flat": 10k lines, 23 ms keystroke, 32 ms
worker cycle on the Mac JVM). The worker's time slices keep the UI responsive;
the spans just arrive later.

Whole-document parse + highlight on the Mac JVM: Kotlin 164-194 ms, Markdown
309-519 ms (270 ms since M2c hashed the injection merge), Vue 214 ms, PHP 197 ms.
On the web: Kotlin 272 ms, Markdown 333 ms, Vue 264 ms, PHP 236 ms; flat Markdown
34 ms per keystroke, 44 ms per worker cycle. The web's worker runs on the UI thread,
so what matters there is how long it holds it: see [Web (M2c)](#web-m2c).

UI thread (at 70k possible spans, the field keeps a window around the
viewport): a keystroke's span mapping 0.09 ms and an update's replace 0.05 ms
on the JVM (BudgetTest); 0.6 / 0.5 ms iOS release; 2.3 / 1.3 ms Android debug.

The budgets hold on the Mac JVM (`BudgetTest`): Kotlin keystroke + 60 lines
under 8 ms, the Markdown worker cycle under 20 ms, the UI cost under 1 ms. The
PerfTest Markdown numbers include JIT warm-up of its first series (best of 3:
1.5 ms). Vue's and PHP's keystroke is mostly the host grammar: Vue rescans the
whole `<script>` raw text token; PHP's `ts_tree_get_changed_ranges` over a
10k-line program costs 3-7 ms.

The iOS perf binary is `linkPerfReleaseTestIosSimulatorArm64`, run with
`xcrun simctl spawn --standalone <device> perf.kexe --ktest_filter='*PerfTest*'`.

What mattered:
- Markdown has a layer per paragraph. One keystroke must not touch every
  layer: edits go to a log applied to a tree only when it is used, untouched
  layers are handed on as they are (no reparse, no copy, no JNI call), and
  injections are re-found only around the change.
- Included-range points come from the host (Rope, O(log n)); the C side used
  to walk the document from 0 for every layer (2.3 s per Markdown keystroke).
- Flattening one layer paints each unit once (a sweep with a stack of the
  enclosing nodes).
- A benchmark file must be valid: repeated `package`/`import` headers made
  every parse error recovery.

## Web (M2c)

The browser runs the same `ses_*` binding as every native target: tree-sitter, the
bridge, the tables loader, zlib and every grammar's code, compiled by zig into
**one wasm32-wasi module** (`build/natives/wasm32/lib/supermux-syntax.wasm`,
`native/build.sh wasm32` or `native/wasm/build.sh --test`). Nothing above the C is
web-specific either: `wasmJs` is a target of the `nativeBacked` source set, so
`NativeBackend`, `SyntaxQuery`, the Kotlin `Regex` evaluation of the `#match?`
family, `Highlighter`, `SyntaxWorker` and `Syntax` are the code the JVM runs. The
same golden files pass on all four platforms (JVM, iOS simulator, Android
emulator, headless Chrome), the regex golden included: Kotlin/Wasm's `Regex` gives
the same results for every shipped regex as JVM, Android and iOS do. There is no
web variant of any query.

```
Highlighter / SyntaxWorker / Syntax               commonMain, unchanged
NativeBackend, SyntaxQuery, ...                   nativeBackedMain, unchanged
  WasmBackend (loading) + Ses.wasmJs.kt           wasmJsMain: the actual of the ses_* ABI
    syntax-loader.mjs                             instantiate, copies, the two host callbacks
      supermux-syntax.wasm                        imports: 4 WASI functions + env.ses_host_read / ses_host_match
```

- **Size**: 7,454,854 bytes, 2,687,736 gzipped (`gzip -9`), with the bundled
  grammars' tables inside, as on native. `manifest.json` records both (`size`,
  `gzip_size`). The 29 code-only grammars' tables (5.4 MB) are fetched on first
  use, see below.
- **Callbacks** never enter the wasm function table: [`src/syntax_wasm.c`](src/syntax_wasm.c)
  holds fixed trampolines (`ses_wasm_parser_parse`, `ses_wasm_query_captures`,
  `ses_wasm_query_matches`) whose C callbacks call two imports with a host context id;
  Kotlin maps the id to its `TextSource` / regex matcher (0 = none, a NULL callback).
  A chunk is written as UTF-16 into one grow-only buffer (`ses_wasm_scratch`). A throwing
  source or matcher is caught in Kotlin and rethrown after the C call returns, as on iOS.
- **Imports**, exactly (checked by `native/wasm/test.mjs` against
  `native/wasm/expected-imports.json`, and by the loader, which refuses any other):
  `env.ses_host_read`, `env.ses_host_match`, and `wasi_snapshot_preview1.fd_write`,
  `fd_seek`, `fd_close` (wasi-libc's stdio, reached only by tree-sitter's debug
  printing: `fd_write` goes to the console) and `clock_time_get` (`clock_gettime`,
  the parse timeout: `performance.now()`). Exports: every `SES_API` function and
  `memory`, `_initialize` (a reactor: no `_start`).
- **Threads**: none. The module is single-threaded wasm32-wasi; `ses_grammar.c`'s
  mutex compiles to nothing under `__wasm__`.
- **Vue's C++ scanner** compiles with `zig c++ -target wasm32-wasi -fno-exceptions`
  and links against zig's wasi libc++ with no changes: vue is fully highlighted on
  the web, like everywhere else.
- **Crossing the boundary**: every value is a number or a string. Byte buffers cross
  as latin1 strings and int arrays as strings of two UTF-16 units per int, because
  Kotlin/Wasm converts a whole string in one copy, while a typed array costs one JS
  call per element.
- **Tables**: bundled grammars (the core set) are inside the module. A code-only
  grammar's `<lang>.sesz` is fetched by `WasmBackend.ensureLanguage` from
  `tablesUrl` (default: `editor-syntax/tables/` next to the wasm module) straight into
  the module, and checked there against the SHA-256 compiled into its code, as on
  native. `isReady` never blocks.
- **The worker shares the UI thread.** `SyntaxLimits.parseSliceMicros` is 8 ms on the
  web (50 ms natively), and `Highlighter.parseSuspending` gives the thread back
  (one `MessageChannel` task) between slices, before a layer's parse once a slice is
  used up, and between finding and merging injections; the worker also yields before
  its spans and its folds. Natively `platformSliceYield` is null and nothing changes.
  Measured in headless Chrome on the Mac (`MainThreadTest`, a `MessageChannel`
  ping-pong: the longest the thread was held), 10k lines:

  | | first parse, longest hold | keystroke cycles, longest hold |
  |---|---|---|
  | Kotlin (asserted: <= 16 ms) | 9.1 ms (18.3 ms when the code itself is not compiled yet) | 4.5 ms |
  | Markdown | 39.2 ms | 10.1 ms |
  | Vue | 26.0 ms | 10.5 ms |
  | PHP | 31.7 ms | 12.0 ms |

  **Over 16 ms, not yet fixed:** Markdown's, Vue's and PHP's first parse run the
  host's injection query over the whole document in one C call (Markdown: 46 ms
  alone). Splitting it into windows is future work; M2c found and fixed Markdown's 334 ms merge of 3.5k
  injection sites (now hashed, on every platform) and a 25 ms long-line scan.
- **Memory**: wasm memory only grows. The Node test, which loads every bundled
  grammar and parses a 2 MB JSON document, ends at 273 MiB.

### Serving it (what M5 does)

The Kotlin/Wasm toolchain does not copy a dependency klib's resources next to the
consumer's module, exactly as for terminal-core: **`:web` must re-export
`syntax-loader.mjs` and `supermux-syntax.wasm` as its own wasmJs resources**,
copied from `:editor-syntax:stageWasmResources`
(`build/gradle/generated/wasmResources/supermux-syntax.wasm`, verified against the
manifest) and `src/wasmJsMain/resources/syntax-loader.mjs`, like
`stageTerminalWasmAssets` in `apps/web/build.gradle.kts`. webpack then emits the
wasm from the loader's `new URL("./supermux-syntax.wasm", import.meta.url)` and
`stageForBroker` content-hashes it with the other assets, so the default URL is right.

The tables are fetched by name, so they cannot be content-hashed: serve
`:editor-syntax:stageTables`' `editor-syntax/tables/*.sesz` (29 files, 5.4 MB,
checked against the manifest) from the broker's static directory at a fixed path,
and pass that directory to `WasmBackend.load(tablesUrl = ...)`. They are data: the
module refuses any blob whose SHA-256 is not the one its code was generated with, so
a long cache lifetime is safe as long as the path changes with the grammar versions
(for example `/assets/editor-syntax/tables/<manifest sha>/`). Serve the wasm with
gzip or brotli; the loader compiles from bytes (`WebAssembly.compile`), so the MIME
type does not matter, but `application/wasm` would allow a later switch to
`compileStreaming`.

The browser tests (`:editor-syntax:wasmJsBrowserTest`, Karma and headless Chrome)
serve the same files: `karma.config.d/syntax-wasm.js` serves the goldens and tables,
and `syntax-test-setup.mjs` fetches them before any test runs, so the shared tests
read their resources synchronously, as on the JVM. `CHROME_BIN` defaults to the
Mac's Google Chrome.

## Licences

- tree-sitter: MIT; the ICU headers it vendors under `lib/src/unicode/`: the
  Unicode/ICU licence.
- zlib: the zlib licence. It is compiled into the Linux, Windows and wasm libraries.
- wasi-libc (the wasm module): MIT, with BSD-2-Clause (cloudlibc), MIT (musl) and
  CC0 (dlmalloc) parts; the texts are in `native/licenses/wasi-libc*`.
- Grammars: each grammar's licence is in `grammars.lock.json`; all are MIT
  except dart (ISC) and clojure (sogaiu's, CC0-1.0).
- Queries: each shipped query file names its sources and their licences in its
  header: the grammar's (npm), MPL-2.0 (Helix), Apache-2.0 (nvim-treesitter).
- `jni/linux/jni_md.h` and `jni/win32/jni_md.h` are vendored from OpenJDK 17
  (jdk-17.0.12-ga). They are GPLv2 with the Classpath exception, like every JDK
  header. They are build-time headers only, needed because the Mac's JDK ships
  only `include/darwin`.

[`../THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md) reproduces the notices
of everything linked into the libraries.
