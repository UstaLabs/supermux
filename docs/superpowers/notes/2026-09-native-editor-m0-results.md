# Native editor M0: risk-check results (2026-09-25)

Plan: `docs/superpowers/plans/2026-09-25-native-editor-m0-risk-checks.md`.
Spec: `docs/superpowers/specs/2026-09-25-native-editor-design.md`. This document's §6 edits are applied to it.
The spike module `:editor-spike` is deleted. Its reusable pieces are in `m0-artifacts/` next to this file.

## 1. ktreesitter 0.25.1 (+ our own grammar build)

| Target | Result |
|---|---|
| JVM (Mac, arm64) | ✅ golden test 2/2, **with the backend change in §2** |
| iOS simulator | ✅ golden 2/2 (+ probe tests); `linkDebugFrameworkIosArm64` links; a signed device app links the framework |
| Android | ✅ 9/9 on the Mac's API 35 arm64 **emulator**. ⏳ The Galaxy Fold was unreachable: wireless debugging was off and no port was open. Rerun on the Fold during the M5 device pass. |

- The klib ktreesitter publishes, built with Kotlin 2.2.21, works with our Kotlin 2.4.10 on every target. No ABI or klib problem.
- **No grammar artifacts are published.** We build grammars ourselves from the npm packages (parser.c / scanner.c / queries):
  - clang for macOS and iOS
  - the NDK clang for Android. The Mac's default SDK has no NDK, so it falls back to `~/devtools/android-sdk/ndk/26.1.10909125`.
  - a small JNI shim that hands the `TSLanguage*` to ktreesitter as a `jlong`
  - a cinterop `.def` for iOS, which needs `kotlin.mpp.enableCInteropCommonization=true`
- Recipe: `m0-artifacts/build-grammars.sh` and `jni_shim.c`.

## 2. UTF-16: ❌ on native, ✅ on web

**ktreesitter cannot parse UTF-16.** It always turns the input String into UTF-8 itself, so a `UTF_16LE` parse
produces `(document (ERROR (UNEXPECTED 8827)))` on JVM, iOS and Android (`m0-artifacts/KtsProbeTest.kt`). Its UTF-8
also differs per platform. The sample is 37 UTF-16 units, or 40 real UTF-8 bytes:

| Platform | What ktreesitter hands tree-sitter |
|---|---|
| JVM | **modified** UTF-8: 😀 is 6 bytes, so 42 bytes total |
| Android | standard UTF-8, 40 bytes |
| iOS (Kotlin/Native) | UTF-8 bytes, but the **byte count is the UTF-16 length**. `parse(String)` cuts off any non-ASCII text (37 of 40 bytes), and the read callback under-reports every chunk. **This is a ktreesitter bug.** |

The spike passes by parsing UTF-8 through the read callback and mapping byte offsets back to UTF-16 with a
per-platform byte-width table. On iOS it also pads each chunk up to its real byte length
(`m0-artifacts/KtsHighlighter.kt`). That works, but it depends on ktreesitter's internals staying the same.

**web-tree-sitter** reports `startIndex`/`endIndex` in UTF-16 units: exactly the golden spans, with no conversion.

**Consequences:**
- The rope stays **UTF-16 only**, with no UTF-8 counts. The conversion belongs in `editor-syntax`, per
  backend, per edited region.
- **M2 decision:** patch ktreesitter (upstream a fix for the Native byte count, plus a raw-bytes read callback)
  or write our own thin binding over the tree-sitter C API. terminal-core already has the JNI, cinterop and
  WASM pipeline for that. The M2 plan will weigh it.

## 3. web-tree-sitter in wasmJs: ✅

Golden 2/2 in headless Chrome, with the same expected spans as native, so **every client produces identical colours**.
- The plan's style worked: a dynamic `import('web-tree-sitter')` inside `@JsFun`, with the wasm files served
  through `karma.config.d`.
- `@JsModule` was not needed.
- Webpack warns about web-tree-sitter's Node-only imports (`fs/promises`, `module`). This is harmless.
- `web-tree-sitter` 0.25.10 matches ktreesitter's tree-sitter core 0.25.
- `kotlinx-coroutines-test` still doesn't link on wasmJs with Kotlin 2.4.10, as the shared notes say. The spike used a
  small `runSpike` expect/actual (a `Promise` on wasm) instead.

## 4. The soft-keyboard hidden field (iPhone 15 Pro): ✅ all 8

Ahmet ran the checklist on the device on 2026-09-25: *"all of them works"*.
1. autocorrect `teh` → `the`
2. Turkish `ğüşıöç`
3. held Backspace past the field's left edge (re-windowing)
4. Backspace at the start of the document
5. Return
6. dictation
7. Japanese kana→kanji composition
8. a suggestion-bar tap

**A hidden field that holds a window of real document text, with each change diffed into a transaction, works.**
- Probe code: `m0-artifacts/ImeProbe.kt`.
- Gotcha from the first run: without an explicit theme and an opaque surface, the Compose view on iOS is black
  text on a black window and looks blank.
- Signing gotchas are in `m0-artifacts/iosProbe/probe-device.sh` and `~/.mux/domains/infra.md`:
  - Over SSH, Xcode has "No Accounts", so the build uses the App Store Connect API key.
  - `CADisableMinimumFrameDurationOnPhone` must be in Info.plist, or Compose's plist check aborts the app.
  - The keychain password is read from `~/.smux-dist-kc-pass` on the Mac and never committed.

## 5. Grammars: inventory and size

Full tables: `m0-artifacts/grammar-inventory.tsv` and `grammar-inventory-alternates.tsv`.

- **47 languages checked.** 38 have an npm grammar; alternates found for **r** (`@davisvaughan/tree-sitter-r`,
  complete), **wast** (`tree-sitter-wat`, no wasm) and **vb** (`tree-sitter-vb-dotnet`, no highlights).
- **Still missing on npm:** erlang, crystal, coffeescript, fortran, cmake, dockerfile. They open as plain text
  until a grammar is sourced from git; cmake and dockerfile exist on GitHub and should be sourced.
- **No `highlights.scm` shipped:** vue, **kotlin**, groovy, clojure, and vb-dotnet. They need queries from another
  source, such as nvim-treesitter (Apache-2.0) or Helix (MPL-2.0). **Kotlin matters: we must source its queries.**
- **No prebuilt wasm:** markdown, sql, xml, vue, swift, perl, clojure, elm, pascal, haxe, nginx, glsl.
  - These need a wasm build (the tree-sitter CLI plus emscripten, on the Mac).
  - yaml's measured wasm (1,654 bytes) is the wrong file, so re-check it.
- **Licences:** all MIT except dart (ISC, permissive).
- **Size:**

| | Total |
|---|---|
| iOS object code | ~74 MB (≈76 MB with the alternates) |
| wasm | ~59 MB |

  - Largest: fsharp 11.5 MB, julia 6.4 MB, ocaml 5.6, csharp 5.4, objc 5.3, perl 4.8.
  - Multi-parser packages (typescript+tsx, php, markdown inline, ocaml interface) were only partly measured,
    so the real totals are higher.

**Bundling everything is not acceptable.** The spec now bundles a core set and fetches the rest on demand on
every platform (§6).

## 6. Edits applied to the spec

1. §4.1: the rope stores **UTF-16 length and line count only**. Byte offsets are `editor-syntax`'s job.
2. §5.1/5.2: `edit()` converts to the backend's byte encoding. Recorded the ktreesitter encoding facts, and
   that M2 decides between patching ktreesitter and a thin binding of our own.
3. §5.3/5.4: grammar packaging becomes **a bundled core set plus on-demand grammar packs** served by the broker
   and cached on the device, on every platform. It also covers the missing-query and missing-wasm work, and the
   plain-text list.
4. §10: M0 is marked done with a link to this document. The Fold rerun is added to M5.
