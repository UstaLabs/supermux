# editor-syntax native: tree-sitter behind the `ses_*` ABI

This directory builds the native half of `:editor-syntax`: tree-sitter
v0.25.10 behind an owned C ABI ([`include/supermux_syntax.h`](include/supermux_syntax.h)),
with every grammar's code compiled in and its parse tables moved into
compressed blobs. The Kotlin bindings call only `ses_*`: JNI on Android and the
desktop JVM ([`src/syntax_jni.c`](src/syntax_jni.c)), cinterop on iOS
(`../src/nativeInterop/cinterop/syntax.def`). The web client uses web-tree-sitter
instead (M2b) and never sees this code.

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
native/build.sh all      # gen + all nine targets + build/natives/manifest.json
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

- `upstream.lock.json`: the tree-sitter repository, tag and commit, and zlib's
  URL and sha256. The pin is v0.25.10 because it matches web-tree-sitter 0.25.10,
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
  The iOS simulator tests point `extraDirectories` at that staged directory.

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

## Included ranges (ABI 3)

`ses_parser_set_included_ranges` restricts a parser to packed UTF-16
`[start, end]*` ranges, for injected languages (a `<script>` element's content,
a Markdown fence). tree-sitter also wants each boundary's row and column, so it
takes the same pull reader as a parse and reads the document once up to the last
boundary. Ranges must be sorted and must not overlap; NULL / 0 resets the parser
to the whole document.

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
  as in tree-sitter-highlight 0.25.10, Helix and nvim. An npm file written the
  other way round (a catch-all like `(identifier) @variable` after the specific
  patterns) is reversed: glsl, go, pascal and scala today.
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
  `A-Z`). `golden/regexes.txt` holds every shipped regex against 68 samples;
  JVM, iOS and Android must all reproduce it.
- **Our grammar versions**: the queries are compiled against the built library;
  a node, field or token our grammar lacks drops that alternative of a `[...]`
  list, or else the pattern.
- Everything rewritten or dropped is listed in the lock's `notes`.

M2b also moved three grammars to the ones these query sets target:
kotlin to fwcd's `tree-sitter-kotlin` 0.3.8 (npm), clojure to sogaiu's and groovy
to murtaza64's, both git tarballs at the commits Helix pins (`grammars.lock.json`
`note`). The npm packages M2a used (`@tree-sitter-grammars/tree-sitter-kotlin`
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

### Performance

A 10,729-line Kotlin file (267,012 UTF-16 units, one header and the sample's
declarations repeated), medians after warm-up; `PerfTest`:

| | full parse + highlight of all of it | incremental reparse (1 keystroke) | 60 lines | 180 lines (the worker's: viewport + 1 screen each side) | keystroke + 60 lines | keystroke + 180 lines |
|---|---|---|---|---|---|---|
| Mac JVM (M1, jvmTest) | 212 ms | 0.76 ms | 0.60 ms | 2.3 ms | **1.4 ms** | 3.1 ms |
| iOS simulator, release test binary | 225 ms | 0.69 ms | 0.47 ms | 2.0 ms | 1.2 ms | 2.7 ms |
| iOS simulator, debug test binary | 930 ms | 0.70 ms | 3.1 ms | 11 ms | 3.8 ms | 12 ms |
| Android emulator (API 35 arm64, debug APK) | 2.3 s | 2.0 ms | 2.3 ms | 13.5 ms | 4.3 ms | 15.5 ms |

The budget, an incremental reparse plus viewport highlighting under 8 ms on the
Mac JVM, holds with a wide margin, even with the worker's 180 lines. The iOS perf
binary is `linkPerfReleaseTestIosSimulatorArm64`, run with
`xcrun simctl spawn --standalone <device> perf.kexe --ktest_filter='*PerfTest*'`.

Three things mattered:
- Before injections were found only where the document changed, Kotlin's
  injections query ran over the whole document on every keystroke (58 ms). Helix's
  Kotlin injections only inject `comment` and `regex`, which have no grammar here,
  so the tool now drops those too.
- Flattening one layer used to paint every capture's whole range, which is
  quadratic in the nesting depth (153 s for the whole file in the iOS debug
  binary). It is now one sweep with a stack of the enclosing nodes.
- A benchmark file must be valid: 530 repeated `package`/`import` headers made
  every parse error recovery (400 ms full, and nothing reused incrementally).

## Licences

- tree-sitter: MIT; the ICU headers it vendors under `lib/src/unicode/`: the
  Unicode/ICU licence.
- zlib: the zlib licence. It is compiled into the Linux and Windows libraries.
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
