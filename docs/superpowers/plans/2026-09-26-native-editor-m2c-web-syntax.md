# Native editor M2c: syntax on the web (our binding as wasm). Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:editor-syntax` gets a wasmJs target that runs the **same** `ses_*` C binding as native, compiled to
one wasm32 module: tree-sitter, the bridge, the loader and every grammar's code. `Highlighter`, `SyntaxWorker` and
the `Syntax` plugin run unchanged in the browser and produce the **same golden spans** as JVM, iOS and Android.

**Decision (Ahmet, 2026-09-26):** use our own binding compiled to wasm, **not** web-tree-sitter. The spec's §5.2a
and §5.3 are updated.

**Architecture:**
```
Kotlin/Wasm (commonMain Highlighter / SyntaxWorker / Syntax, unchanged)
   └─ WasmBackend : SyntaxBackend            (wasmJsMain)
        └─ @JsModule("./syntax-loader.mjs")  (JS glue: instantiate, memory copies, callback trampolines)
             └─ supermux-syntax.wasm         (zig cc -target wasm32-wasi: tree-sitter + ses_* + zlib + all grammar code)
                  imports: a tiny WASI shim + env.ses_host_read / env.ses_host_match (JS → Kotlin)
   tables: <lang>.sesz fetched on first use (core ones compiled in, as on native)
```
- Callbacks cross the boundary through **fixed trampolines** compiled into the module. These are wasm-only C
  entry points such as `ses_wasm_parser_parse(parser, old, ctx_id, …)` that pass a C `ses_read_fn` which calls
  the imported `env.ses_host_read(ctx_id, index, out_ptr, out_len_ptr)`. So no JS function is ever added to the
  wasm function table.
- Kotlin keeps a map from context id to `TextSource` / `Matcher`.
- Text chunks are copied into a reusable buffer in module memory (`ses_wasm_scratch(n)`), as UTF-16.

**Tech stack:**
- zig 0.16 (`-target wasm32-wasi`, zig bundles wasi-libc), on the Mac.
- Kotlin/Wasm (wasmJs, browser).
- Karma with headless Chrome on the Mac, following `apps/terminal-core`'s wasm setup: `wasm/build.sh`,
  `wasm/terminal-loader.mjs`, `karma.config.d/terminal-wasm.js`, and its `build.gradle.kts` wasm staging around
  lines 280–320.

**Not pre-verified.** TDD it. The goldens in `src/nativeBackedTest/resources/golden/` and the M0 golden are the
contract: web must match them byte for byte.

## Ground rules
- The same as M2a and M2b: **no builds on the Linux host**; `scripts/editor/mac-sync.sh`, then `ssh mac "$MACENV; …"`.
- The Mac's Chrome: `CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'`.
- `kotlinx-coroutines-test` does not link on wasmJs with Kotlin 2.4.10 (see `~/.mux/domains/infra.digest.md`).
  Use a `Promise`-returning test helper, like the M0 spike did.
- Commits end with a blank line + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: The wasm module

**Files:** `native/wasm/build.sh`, `native/wasm/wasi-shim.mjs` (or inline in the loader), `native/src/syntax_wasm.c`,
and edits to `native/build.sh` (add a `wasm32` target) and `native/upstream.lock.json` (the zig version for wasm).

- **Build with** `zig cc -target wasm32-wasi -Os`. Include:
  - `lib.c` (tree-sitter), the vendored zlib, `syntax_bridge.c`, `ses_grammar.c`, the generated registry, and every
    `parser_<lang>.c`, scanner and bundled `blob_<lang>.c`;
  - the new `syntax_wasm.c`.

  Also:
  - Export `ses_*`, `ses_wasm_*`, `malloc`, `free` and the memory.
  - Export no `_start`. Use `-mexec-model=reactor` and `-Wl,--no-entry`.
  - Use `-Wl,--export-dynamic` only if it's needed; prefer an explicit export list.
- **C++ scanners (vue):** use `zig c++ -target wasm32-wasi -fno-exceptions`. If libc++ for wasi doesn't link
  cleanly, record the exact error, and port vue's scanner call into C if that is small. Otherwise leave vue
  code-only on web, but that must be a recorded, reported exception, not a silent one.
- **Threads:** single-threaded. Make `ses_grammar.c`'s pthread mutex and atomics compile for wasm32-wasi. wasi-libc
  has pthread stubs in the single-threaded build; otherwise guard them with `#ifdef __wasm__`.
- **`syntax_wasm.c`** holds trampolines for every callback-taking entry point:
  - `ses_parser_parse`
  - `ses_query_captures` / `ses_query_matches` (read + match)
  - `ses_parser_set_included_ranges`, if it still takes a reader

  Plus `ses_wasm_scratch(n)`, a grow-only buffer the host writes chunk text into.
- **Imports** are only: `env.ses_host_read`, `env.ses_host_match`, and the WASI functions wasi-libc actually needs.
  List the real imports with `wasm-objdump -x` or `wasm-tools`, and shim exactly those: typically `fd_write`
  (to console), `proc_exit`, `clock_time_get` (use `performance.now`), `random_get`, `environ_*` and `args_*` (empty).
- **Tests** (Node, in `build.sh --test`, like terminal-core):
  - Instantiate the module, then parse the M0 JSON sample through the trampolines.
  - The captures equal the M0 golden.
  - `ses_debug_live_trees()` returns to 0.
  - Record the size, raw and gzipped, in the manifest and the README.
- [ ] TDD it, then commit: `feat(editor-syntax): the ses_* binding as one wasm32 module`.

### Task 2: The JS loader and the Kotlin/Wasm binding

**Files:** `src/wasmJsMain/resources/syntax-loader.mjs`, `src/wasmJsMain/kotlin/.../WasmSes.kt` (externals + memory
helpers), `src/wasmJsMain/kotlin/.../WasmBackend.kt` (implements `SyntaxBackend`, `ParserHandle`, `TreeHandle`,
`QueryHandle`), `build.gradle.kts` (the wasmJs target plus the staging of the wasm and `.sesz` files as wasmJsMain
resources), and `karma.config.d/syntax-wasm.js`.

- **Loader.** An `async init(url)` instantiates the module. It exposes thin functions that take and return numbers
  and strings only, following terminal-loader.mjs's style. `ses_host_read` and `ses_host_match` call back into
  Kotlin through functions Kotlin registers once.
- **WasmBackend** mirrors `NativeBackend`:
  - It has the same query cache (`sharedQuery`).
  - `suspend ensureLanguage(lang)` fetches `<lang>.sesz` when the tables aren't compiled in, then calls
    `ses_language_provide_tables`. Resolve the URL relative to the module's own URL, and make it configurable for
    the app (M5).
  - `isReady` never blocks.
- **Regexes** go through Kotlin `Regex` via the match callback, as on native. Add a test that Kotlin/Wasm's `Regex`
  gives the same results on the `\p{…}` rewrites, reusing the regex golden from M2b. If Kotlin/Wasm's `Regex` differs
  from JVM/Native for any shipped regex, record it and fix it in `fetch-queries.py`, not with a web-only fork.
- **Worker on the web.** The `SyntaxWorker` runs on the main thread: Kotlin/Wasm has no background threads here. Set
  `SyntaxLimits.parseSliceMicros` to about 8 ms on wasm so a slice never blows a frame, and yield to the event
  loop between slices. The sliced parse already yields.
  - Test: a 10k-line Kotlin first parse never holds the main thread for more than 16 ms at a time. Measure the gaps
    with `performance.now()` around each slice.
- [ ] TDD it, then commit: `feat(editor-syntax): wasmJs SyntaxBackend over the wasm binding`.

### Task 3: The shared test suite runs on wasm

- Make the platform-free tests run on wasmJs too, with the goldens as test resources:
  - Golden spans (all golden files), the injection replay, `ShippedQueries`, the regex golden, `TextEdits`, the
    highlighter/worker tests.
  - Move them from `nativeBackedTest` to `commonTest` where they only use `SyntaxBackend`, with an `expect fun
    testBackend(): SyntaxBackend`.
  - Tests that need native-only things (`ses_debug_live_trees`, JNI resource loading) stay where they are, or get a
    wasm equivalent. The wasm module exports `ses_debug_live_trees` too.
- **Every grammar loads on web:** fetch every `.sesz`, parse a one-line sample, no exception.
- **Performance** in headless Chrome, 10k lines: keystroke plus the worker cycle for Kotlin, sectioned Markdown,
  Vue and PHP. Add rows to the README table. There's no hard budget on web yet; report the numbers. Flag anything
  over 16 ms per slice as a problem.
- [ ] Run jvmTest, iosSimulatorArm64Test and wasmJsBrowserTest on the Mac, and connectedAndroidTest once at the end
  (emulator read-only, shut down afterwards). **The goldens must be byte-identical on all four.**
- [ ] Commit: `test(editor-syntax): one suite, four platforms, identical goldens`.

### Task 4: Docs and memory
- [ ] README: the web architecture, the module size (raw and gzipped), which tables are fetched and from where, the
  WASI imports, and what M5 must do to serve `supermux-syntax.wasm` and the `.sesz` files from the broker's web
  static dir. `apps/web`'s Gradle stages terminal-core's wasm today; mirror that.
- [ ] Append a dated entry to `~/.mux/domains/_inbox.md`. Commit.

## Not in M2c
- M3: the Compose surface. M5: serving the files from the broker, and wiring `:web`.
