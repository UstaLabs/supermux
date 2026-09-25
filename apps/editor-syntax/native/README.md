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
native/build.sh gen      # per grammar: extract tables + differential test                                 -> build/gen/<lang>/
native/build.sh <target> # one library                                                                     -> build/natives/<target>/lib/
native/build.sh all      # gen + all nine targets + build/natives/manifest.json
```

1. **fetch** clones tree-sitter at the commit in `upstream.lock.json` and
   downloads zlib and every grammar in `grammars.lock.json` except the
   `excluded` ones. It refuses any tarball whose sha256 differs from the lock.
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

   Any difference fails `gen`. The inputs are the package's own `test/corpus`
   (or `corpus/`) and `examples/` when its npm tarball ships them (only dart,
   pascal and clojure do), plus
   [`tests/inputs/`](tests/inputs). The language-level comparison is exhaustive
   for every grammar whatever its inputs.
3. **targets** compile `tree-sitter/lib.c`, the bridge, the loader, JNI (not for
   iOS), and every grammar's `parser_<lang>.c` and scanner into one library.
   A grammar marked `bundled` also gets its `blob_<lang>.c`.

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

## Lock files

- `upstream.lock.json`: the tree-sitter repository, tag and commit, and zlib's
  URL and sha256. The pin is v0.25.10 because it matches web-tree-sitter 0.25.10,
  so every client parses with the same core.
- `grammars.lock.json`: for each grammar, its npm package, version, tarball URL
  and sha256, licence, the parser directories inside the tarball, and `tables`:
  - `bundled`: the tables blob is linked into the library. This is the core set
    of spec §5.3, plus fsharp. fsharp is the largest grammar, and the binding
    tests check its first-use inflate on every platform.
  - `code`: only the code is linked. The runtime reports `SES_ERR_NO_TABLES`
    until the tables are handed over with `ses_language_provide_tables`, which
    M2b does from resources.
  - `excluded`: not built; `note` says why.

  Optional `regenerate: {cli, abi}`: the published `parser.c` targets an ABI this
  runtime refuses. `fetch.sh` rebuilds it from the package's own `grammar.json`
  with that exact tree-sitter CLI (`npx`), and `gen` then difftests the result.
  Only clojure 0.4.0 needs this: its npm parser is ABI 9.

To bump a grammar:
1. Edit `docs/superpowers/notes/m0-artifacts/grammar-inventory*.tsv`.
2. Rerun `tools/make-lock.py`, which rewrites the lock with fresh sha256s.
3. Restore the hand-made fields (`excluded` / `note`) and review the diff.
4. On the Mac, run `native/fetch.sh && native/build.sh all`. The difftest must
   pass for the new version before it ships.
5. Run `tools/make-notices.py` there too, and commit the regenerated
   `THIRD-PARTY-NOTICES.md`.

A tables blob carries the hash of the tables its code was generated with. The
loader refuses a blob for any other grammar version.

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
handed to `ses_language_provide_tables`. The blob's hash is checked against the
compiled-in code.

## Predicates

`ses_query_captures` evaluates `#eq?`, `#not-eq?`, `#any-eq?`, `#any-not-eq?`,
`#any-of?` and `#not-any-of?` in UTF-16 against the document.
The `#match?` family is NOT evaluated natively: those predicates pass, and
`ses_query_flags` bit 0 is set. M2b filters those captures with a Kotlin `Regex`.
Directives (`#set!` and the like) are ignored.

## Licences

- tree-sitter: MIT.
- zlib: the zlib licence. It is compiled into the Linux and Windows libraries.
- Grammars: each grammar's licence is in `grammars.lock.json`; all are MIT
  except dart, which is ISC.
- `jni/linux/jni_md.h` and `jni/win32/jni_md.h` are vendored from OpenJDK 17
  (jdk-17.0.12-ga). They are GPLv2 with the Classpath exception, like every JDK
  header. They are build-time headers only, needed because the Mac's JDK ships
  only `include/darwin`.

[`../THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md) reproduces the notices
of everything linked into the libraries.
