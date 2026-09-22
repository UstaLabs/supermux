# terminal-core — verification record (Plan 1 acceptance)

What has actually been built, run and measured for `dev.supermux.terminal:terminal-core`
`0.1.0-dev.1`, and — just as important — what has **not**. A target is called *supported* only
where a test executed on it. Everything else says "built, not runtime-tested" with the reason.

Recorded **2026-09-22** on branch `mux/supermux-54` (base commit `d9927005`; the follow-up
review fixes of §4 are included).
Design and contracts: [`native/README.md`](native/README.md). Consumer checks:
[`consumer-smoke/README.md`](consumer-smoke/README.md).

## 1. Pin, toolchain, ABI

| | |
|---|---|
| Package | `dev.supermux.terminal:terminal-core:0.1.0-dev.1` (MIT — [`LICENSE`](LICENSE), notices in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)) |
| Upstream | Ghostty `22391ed6491f2924361dcad1f9a9176a390fd20f` (2026-09-21), app `1.3.2-dev`, `libghostty-vt` `0.1.0-dev`, built with `-Demit-lib-vt=true -Doptimize=ReleaseFast` (wasm: `ReleaseSmall`) |
| Owned ABI | **st_\* ABI v1** (`ST_ABI_VERSION=1`): 18 `st_*` functions; JNI libraries additionally export exactly 17 `Java_dev_supermux_terminal_NativeTerminal_*` + `JNI_OnLoad`, and the export check fails on anything else |
| Zig | `0.16.0`, tarball sha256 `70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00`, minisign-verified (bundled clang 21.1.0) |
| Android NDK | `28.2.13676358` (r28c, clang 19.0.1); `llvm-objcopy` 19.0.1 from it |
| Browser / Node | Google Chrome `148.0.7778.178` headless, Node `v24.16.0` |
| Linux build host | x86_64, Ubuntu, glibc 2.43, kernel 7.0.0-31; JDK 17.0.20 |
| Mac build host | `aarch64-macos` (macOS 26.6 / Darwin 25.6.0), Xcode 26.5, JDK 17 |
| Pin enforcement | `native/upstream.lock.json` + `build.sh` refuse a checkout that is not exactly the pinned commit or that has local modifications; every dependency is content-hash verified by Zig |
| Licences | `LICENSE` (MIT) + `THIRD-PARTY-NOTICES.md`, whose five quoted texts were **re-fetched from upstream at the pinned revisions on 2026-09-22 and diffed — all byte-identical**; the provenance table in that file records the exact revision and method per text |

Per-artifact provenance ships **inside** the package as
`dev/supermux/terminal/abi-manifest.json` (jvm jar + wasm klib): ABI version, Ghostty commit, Zig
version, publication profile, and for every target its library name, sha256, size, zig target,
build host and `runtime_tested` flag — plus `missing_targets`, so an incomplete package declares
itself.

## 2. Per-target result

"Runtime-tested" means code from that artifact executed on that OS/CPU.

| target | built | runtime-tested | evidence / why not |
|---|---|---|---|
| **linux-x64** | yes (Linux host) | **yes** (this host) | C smoke **66 checks / 0 failures**, st_* bridge **208 / 0**, threads test (6 × 150 cycles) plain and under **TSan**, `dlopen` ctypes check of `.so` and JNI `.so`, bridge again under **ASan+UBSan+LSan** (**187 / 0**). Kotlin: `:terminal-core:jvmTest` **86 tests / 0 failures** (2026-09-22 21:29). Consumer: 5 / 0 from the published jar (§5) |
| **wasm32** | yes (Linux host) | **yes** (Chrome 148 + Node 24) | `wasm/build.sh --test`: smoke **47 / 0 in each runtime**, loader test **38 / 0 in each runtime**. Kotlin: `:terminal-core:wasmJsBrowserTest` **75 tests / 0 failures** in headless Chrome (2026-09-22 21:32). Consumer: 1 / 0 from the published klib (§5) |
| **macos-arm64** | yes (**Mac only** — dylibs need ld64) | **yes** (Mac) | `native/build.sh macos-arm64 --test`: smoke **66 / 0**, bridge **204 / 0** (the 4 RSS assertions are Linux-only), `dlopen` checks of all three dylibs, `codesign -v` OK. `:terminal-core:jvmTest` on the macOS JVM **60 / 0** — but that run is from the **Task-5 source tree**: it predates `TerminalSession`, so the 26 session tests have **never run on macOS** |
| **macos-x64** | yes (**Mac only**) | **partly** (Rosetta 2) | smoke **66 / 0**, bridge **204 / 0** under `arch -x86_64`; manifest `test_host: aarch64-macos … (x86_64 under Rosetta 2)`. **No x86_64 JVM anywhere here**, so the packaged JNI dylib has never been loaded from Java. A Linux cross-build of this target produces the static archive only (no dylib) |
| **ios-simulator-arm64** | yes (**Mac only**) | **yes** (simulator) | `:terminal-core:iosSimulatorArm64Test` through cinterop: **38 tests / 0 failures** (2026-09-22 19:37, Task-5 tree). Same gap as macOS: no `TerminalSession` suite has run on Apple targets |
| **ios-arm64** | yes (**Mac only**) | **no** — link only | `libsupermux_terminal.a` 2,734,904 B; `:terminal-core:linkDebugTestIosArm64` links the Kotlin test binary against it. **Never run on a device** |
| **linux-arm64** | yes (cross, Linux host) | **no** | no arm64 machine and no qemu here. Smoke test cross-linked (glibc 2.28), JNI `.so` export-checked and packaged in the jar |
| **windows-x64** | yes (cross, `x86_64-windows-gnu`) | **no** | no Windows host and no wine here. `supermux_terminal_jni.dll` export-checked with llvm-readobj (18 `st_*` + 17 `Java_*` + `JNI_OnLoad`), imports only KERNEL32/ntdll/UCRT. **Never loaded by a JVM** |
| **android-arm64** | yes (NDK 28.2.13676358, API 26) | **no** | no adb device/emulator attached. `.so` has 16 KB-aligned LOAD segments, needs only libc/libm/libdl, ships in the AAR as `jni/arm64-v8a/` |
| **android-x64** | yes | **no** | same; `native/build.sh android-x64 --test` exits 3 with `no-matching-device`. Ships as `jni/x86_64/` |

Not verified anywhere yet: **Safari/WebKit** (Plan 4), any **Android device**, any **Windows** or
**arm64 Linux** machine, and **x86_64 macOS from Java**.

## 3. Artifacts and sizes

### Published (`:terminal-core:publishAllPublicationsToLocalTestRepository` → `build/test-repository`, 5.5 MB total)

| coordinate | artifact | bytes | sha256 (first 16) |
|---|---|---|---|
| `dev.supermux.terminal:terminal-core:0.1.0-dev.1` | `.jar` (metadata klib) | 22,099 | `fbcab0e444c5d841` |
| | `-sources.jar` | 31,993 | `0038c748a4fe2a0d` |
| | `.module` / `.pom` | 11,711 / 2,775 | — |
| `…:terminal-core-jvm:0.1.0-dev.1` | `.jar` | **2,866,147** | `47ccb7e410e6c65e` |
| | `-sources.jar` | 34,570 | `958b89d8d16cbe86` |
| `…:terminal-core-android:0.1.0-dev.1` | `.aar` | **1,802,416** | `aba960a6b95107ee` |
| | `-sources.jar` | 29,993 | `2fbc8abd8cfc7157` |
| `…:terminal-core-wasm-js:0.1.0-dev.1` | `.klib` | **427,960** | `c6761d2fda430a5a` |
| | `-sources.jar` | 30,681 | `0d9f03f2b3cdc98f` |

Those hashes are **stable**: two full `--rerun-tasks` publishes of the identical tree produced
byte-identical jars, aar and klib. That needed `isPreserveFileTimestamps = false` +
`isReproducibleFileOrder = true` on the archive tasks — measured before adding them, two publishes
of the same tree differed in every archive except the metadata jar.

Every jar and the AAR carry `META-INF/dev.supermux.terminal/LICENSE` (1,075 B) and
`THIRD-PARTY-NOTICES.md` (25,015 B); the jvm jar and the wasm klib also carry
`dev/supermux/terminal/abi-manifest.json` (3,289 B). Every POM carries
`<terminal.publishProfile>` and `<terminal.abiVersion>` (§4).

### Native payload inside those artifacts (this dev publish)

| inside | path | bytes | sha256 (first 12) |
|---|---|---|---|
| jvm jar | `dev/supermux/terminal/native/linux-x64/libsupermux_terminal_jni.so` | 2,395,600 | `2bf7b385c51e` |
| jvm jar | `…/linux-arm64/libsupermux_terminal_jni.so` | 2,211,640 | `25ef38a34077` |
| jvm jar | `…/windows-x64/supermux_terminal_jni.dll` | 2,145,792 | `09fe10b31616` |
| jvm jar | `…/macos-x64`, `…/macos-arm64` | **absent** | dev publish on Linux (§4) |
| aar | `jni/arm64-v8a/libsupermux_terminal_jni.so` | 1,906,880 (AGP-stripped from 2,197,664) | `2b4d8dbe88fa` (pre-strip) |
| aar | `jni/x86_64/libsupermux_terminal_jni.so` | 2,076,992 (from 2,374,872) | `308a74bb5645` (pre-strip) |
| wasm klib | `supermux-terminal.wasm` | 830,845 | `42be902a16e0` |
| wasm klib | `terminal-loader.mjs` | 12,468 | — |
| Apple klibs | `libsupermux_terminal.a` (2,734,904 B per iOS target) | **absent** | Apple targets are disabled on Linux (§4) |

Build-tree reference sizes (`build/native/<target>/lib/`, not published directly):
`libsupermux_terminal.a` 3.36 MB (linux-x64) / 2.72 MB (macos-arm64), `libsupermux_terminal.so`
2.39 MB, macOS `libsupermux_terminal_jni.dylib` 1,478,032 B, raw upstream `ghostty-vt.wasm`
813,917 B. Builds are byte-identical across reruns (same sha256 for `libghostty-vt.a`, the `.so`
and the wasm module).

## 4. Publication gates — release vs dev

`verifyNativeArtifacts*` runs before every publish task, so an incomplete or stale package is a
build failure rather than an artifact.

| profile | gate | required targets | result today |
|---|---|---|---|
| `dev` (default for a `-dev` version) | `verifyNativeArtifactsForHost` | everything the publishing host can build + `wasm32` — here `linux-x64`, `linux-arm64`, `windows-x64`, `android-arm64`, `android-x64`, `wasm32` | **passes**: "'dev' profile satisfied — 6 required targets … present and verified" |
| `release` | `verifyNativeArtifacts` | all ten (5 desktop JVM + 2 Android + 2 iOS + wasm32) | **fails as designed** on this host: `macos-x64: lib/libsupermux_terminal_jni.dylib or manifest.json missing`, same for `macos-arm64`, `ios-arm64: lib/libsupermux_terminal.a … missing`, `ios-simulator-arm64: …` |

A *stale* artifact (bytes not matching its `manifest.json`, or a different `abi_version`) fails
**both** profiles, even for an optional target.

### The lenient gate cannot be forced onto a release number

`-Pterminal.publishProfile=dev` on a version WITHOUT a `-dev`/`-SNAPSHOT` qualifier is refused
outright — otherwise real release coordinates could be minted with missing targets, visible only
deep inside `abi-manifest.json`. The deliberate escape hatch
`-Pterminal.allowIncompleteReleasePublish=true` is not quiet: it prints a banner in the build log,
stamps the POM (`<terminal.incompleteTargets>` plus an "INCOMPLETE BUILD: … carries NO native
library for …" suffix on the description) and sets `incomplete_release: true` +
`incomplete_targets: [...]` in the ABI manifest, so the **artifact identifies itself**. Every POM
also carries `<terminal.publishProfile>` and `<terminal.abiVersion>`.

Checked by `bash apps/terminal-core/scripts/check-publish-guard.sh` (log:
`build/publish-guard-check.log`), which drives the real build through four scenarios and asserts
the outcome, the log text and the manifest fields:

| scenario | expected | actual |
|---|---|---|
| `0.1.0-dev.1`, no profile property | dev profile, host gate passes | **OK**, manifest `profile: dev`, `incomplete_release: false` |
| `-Pterminal.version=1.0.0 -Pterminal.publishProfile=dev` | **refused** | **OK** — "refusing the 'dev' publication profile for version '1.0.0'" |
| `-Pterminal.version=1.0.0`, default profile | release gate, fails here | **OK** — names the missing Mac-only targets |
| `1.0.0` + `-Pterminal.allowIncompleteReleasePublish=true` | allowed, loud, stamped | **OK** — banner printed; manifest `incomplete_release: true`, `incomplete_targets: [ios-arm64, ios-simulator-arm64, macos-arm64, macos-x64]` |

### Where the gate does NOT reach

The gates hang off the publication tasks (`AbstractPublishToMaven`, `GenerateModuleMetadata`).
**Running `jvmJar`, `bundleReleaseAar` or `assemble` and copying the outputs by hand bypasses them
entirely** — plain `assemble` will happily build a jar holding three of the five desktop targets,
and nothing complains. That matters because release packaging is exactly a hand-assembled tree.

The entry point that closes it is `:terminal-core:verifyReleaseArtifacts`: it verifies the **full**
release set first, then runs `assemble`, and finally fails if the artifacts it just built were
stamped with the `dev` profile. Plan 4's packaging (and any other process that ships these
artifacts without going through `publish*`) must call that task rather than `assemble`.

### Release runbook (not yet exercised end to end)

1. On the **Linux** host: `native/build.sh` for `linux-x64`, `linux-arm64`, `windows-x64`,
   `android-arm64`, `android-x64`, and `wasm/build.sh`.
2. On the **Mac**: `native/build.sh` for `macos-x64`, `macos-arm64`, `ios-arm64`,
   `ios-simulator-arm64` (dylibs need ld64, the iOS archives need Xcode's SDK).
3. Copy the Mac's `build/native/{macos-x64,macos-arm64,ios-arm64,ios-simulator-arm64}/` directories
   (library + `manifest.json`) into the Linux tree. Every copied file's sha256 is re-checked
   against its manifest on arrival by the gate — a truncated or mismatched copy fails.
4. `./gradlew :terminal-core:verifyReleaseArtifacts -Pterminal.version=<release>` — verifies all
   ten targets, then assembles.
5. `./gradlew :terminal-core:publishAllPublicationsToLocalTestRepository -Pterminal.version=<release>`
   (the release profile is implied by a version without `-dev`), then run `consumer-smoke` against
   that repository.
6. Apple publications only exist when step 5 runs on a **Mac** (their Kotlin targets are disabled
   on Linux), so a complete set of coordinates needs the Mac to publish too. That asymmetry is
   untested — see §9.

**A release package cannot be produced on one machine.** macOS dylibs must be linked by ld64 and
the iOS archives need Xcode's SDK; Windows and Android are cross-built on Linux. A release
therefore means: build the Linux-side targets here, build `macos-*` + `ios-*` on the Mac, copy the
Mac's `build/native/{macos-x64,macos-arm64,ios-arm64,ios-simulator-arm64}/` (library +
`manifest.json`, whose sha256s are re-verified on arrival) into the Linux tree, then
`-Pterminal.publishProfile=release`. The dev publish recorded here was made on Linux and therefore
has **no Apple publications at all** and no macOS entries in the jar — the packaged ABI manifest
lists exactly that under `missing_targets`.

## 5. Consumer acceptance (`consumer-smoke/`)

A separate Gradle build (own `settings.gradle.kts`, no supermux project on its classpath, no Zig /
NDK / C compiler) that resolves `dev.supermux.terminal:terminal-core:0.1.0-dev.1` — and only that
group — from `build/test-repository`.

| check | result | evidence printed by the run |
|---|---|---|
| `PackagedJvmEngineTest` (4 tests) + `PackagedAndroidArtifactTest` (1) | **5 tests / 0 failures** | see below |
| `PackagedWasmEngineTest` (headless Chrome 148) | **1 test / 0 failures** | `consumer-smoke(wasm): red/wide/DSR/resize OK (frame gen 7, 10x40)` |
| `PackagedIosEngineTest` | **not run** — Mac only, and needs a Mac-made publish | — |

```
consumer-smoke: API jar = …/terminal-core/build/test-repository/dev/supermux/terminal/
                terminal-core-jvm/0.1.0-dev.1/terminal-core-jvm-0.1.0-dev.1.jar
consumer-smoke: packaged linux-x64/libsupermux_terminal_jni.so = 2395600 bytes,
                sha256 2bf7b385c51e0397f790269d2d39a7cfa4fe5a69bbe3affe733ce7bfa61ab243
consumer-smoke: red/wide/DSR/resize OK (frame gen 7, 10x40)
consumer-smoke: mapped native library = …/consumer-smoke/build/terminal-native-cache/
                supermux-terminal/0.1.0-dev.1/2bf7b385…ab243/libsupermux_terminal_jni.so
consumer-smoke(android): aar = …/terminal-core-android-0.1.0-dev.1.aar (1802416 bytes)
consumer-smoke(android): jni/arm64-v8a/libsupermux_terminal_jni.so = 1906880 bytes
consumer-smoke(android): jni/x86_64/libsupermux_terminal_jni.so = 2076992 bytes
```

That the engine ran from the **packaged** library, not from the build tree, is asserted four ways:
the API's code source is the published jar; the native manifest resolves to a `jar:file:…!/…` URL
inside it; `terminal-core/src`, `build/gradle`, `build/native` and `build/wasm` are all absent from
the classpath and `-Dsupermux.terminal.nativeLibrary` is unset; and `/proc/self/maps` shows the
only `supermux_terminal` mapping is the file the loader extracted into the consumer's own
`build/terminal-native-cache/supermux-terminal/<version>/<sha256>/`.

Caveat on the third of those: it reads `java.class.path`, which Gradle may collapse into a single
synthetic jar whose manifest holds the real `Class-Path` — the list would then look clean whether
or not it is. Treat it as a tripwire. The other three read what the JVM actually loaded (a class's
code source, the resource URL, the process's own memory map) and are not affected.

The Android row is a **packaging** check (AAR contents), not a runtime one — nothing Android
executes; android-arm64 / android-x64 stay "not runtime-tested" until a device run (Plan 3).

**Finding (browser hosts):** the Kotlin/Wasm toolchain does not copy a *dependency* klib's
resources next to the consumer's compiled module, so a plain `wasmJs` consumer fails to bundle with
`Can't resolve './terminal-loader.mjs'`. Every browser host must re-export `terminal-loader.mjs` +
`supermux-terminal.wasm` from the klib as its own wasmJs resources; the recipe is in
`consumer-smoke/README.md` and `native/README.md`. The consumer check does exactly that and passes.

## 6. Behaviour verified by the native suites (linux-x64, the reference target)

- `ghostty_smoke_test` (**66 / 0**): red-cell fixture through the render-state API, effects
  (`CSI 6n`, DECRQM, BEL, OSC 2), modes (bracketed paste, mouse tracking, alt screen), key/mouse/
  focus/paste encoders, OSC 8 links, wide characters, selection text, scrollback limits, malformed
  input (unfinished CSI/OSC, truncated UTF-8, 1,000 dangling `ESC[`), and 1,000 × create/feed/free
  with a counting allocator: 8,020 allocations, **0 live at the end**.
- `terminal_bridge_test` (**208 / 0**, every observation decoded by a strict C mirror of
  `ViewportCodec.kt`): argument/handle/ABI validation for all 18 functions, generations and dirty
  rows (incl. a 400-step randomized model check where partial frames are dropped), synchronized
  output, buffer ownership across `st_destroy`, framing rejects (truncation, magic, ABI, kind,
  impossible counts, > 8 MiB), the size cap, whole-vs-split input at every byte boundary,
  allocation failure injected at **every** allocation of a session, golden fixtures shared with
  Kotlin, and the 1,024-terminal limit.
- Concurrency: `terminal_bridge_threads_test`, 6 threads × 150 cycles on two handles each, plain
  and under **ThreadSanitizer** (TSan found a real stale-handle race in the first design; fixed by
  an atomic handle compare and re-verified).
- Memory safety: the bridge suite again under **ASan + UBSan + LeakSanitizer** — 187 / 0, no leaks.

### History budgets (measured on linux-x64, 80×24, 2026-09-22)

Ghostty evicts whole pages (~0.5 MiB), so the bound is resident growth, not an exact byte count.

| fixture | limit | history rows kept | RSS growth | non-page heap |
|---|---|---|---|---|
| 60,000 lines | 10,000 lines | 9,691 (−309: page-granular) | — | — |
| 60,000 styled lines | 2 MiB | 2,755 | 1.91 MiB (0.96×) | 10.7 KB |
| 60,000 styled lines | 8 MiB | 11,425 | 7.66 MiB (0.96×) | 18.2 KB |
| 120,000 styled lines | 32 MiB | 47,727 | 31.77 MiB (0.99×) | 49.9 KB |
| 20,000 lines × 40 cells × 9-codepoint graphemes | 4 MiB | 903 | 4.79 MiB (1.20×) | 9.6 KB |
| same | 2,000 lines (64 MiB) | 1,481 | 9.04 MiB | 10.6 KB |

Asserted bound: **resident growth ≤ `history_bytes` + 2 MiB**; the line limit holds to within one
page (±1,500 rows). Every fixture also checks that the oldest retained row is exactly
`N − 22 − historyRows` (eviction is oldest-first, nothing else is lost) and that graphemes survive.
Worst-case full frame (400×250, 35-byte clusters, 1,200 links of 2,000 bytes): **7,349,810 bytes**,
under the 8 MiB envelope cap that `_Static_assert` guarantees.

## 7. Test counts per platform (Kotlin suites)

| suite | platform | tests | failures | when |
|---|---|---|---|---|
| `:terminal-core:jvmTest` | linux-x64 (JDK 17) | **86** | 0 | 2026-09-22 21:29 |
| `:terminal-core:wasmJsBrowserTest` | headless Chrome 148 | **75** | 0 | 2026-09-22 21:32 |
| `:terminal-core:jvmTest` | macOS arm64 (JDK 17) | **60** | 0 | 2026-09-22 19:38 — **Task-5 tree, no `TerminalSession` suites** |
| `:terminal-core:iosSimulatorArm64Test` | iOS simulator (arm64) | **38** | 0 | 2026-09-22 19:37 — same gap |
| `:terminal-core:testDebugUnitTest` / `testReleaseUnitTest` | Android host JVM | **disabled** | — | `System.loadLibrary` cannot find a `.so` that only exists inside an APK; the same JNI binding is covered by `jvmTest` |
| `consumer-smoke:jvmTest` | linux-x64, published artifacts | **5** | 0 | 2026-09-22 23:19 |
| `consumer-smoke:wasmJsBrowserTest` | headless Chrome 148, published klib | **1** | 0 | 2026-09-22 23:20 |

Linux 86 = EngineContractTest 12, TerminalSessionTest 23, TerminalSessionStressTest 3,
JvmNativeLoaderTest 13, ViewportCodecTest 11, TerminalTypesTest 8, TerminalConstantsTest 7,
JniBindingTest 7, ConcurrencyTest 2. Wasm 75 = the same 61 common tests + WasmRuntimeTest 14.

## 8. Commands and where their output lives

Build outputs are under `apps/terminal-core/build/` (git-ignored, i.e. local to this machine).

| command | log / results |
|---|---|
| `bash native/build.sh <target> [--test]` | `build/<target>*.log`, manifest `build/native/<target>/manifest.json` |
| `bash wasm/build.sh --test` | `build/wasm-task5.log`, `build/wasm/manifest.json` |
| `./gradlew :terminal-core:jvmTest` | `build/task7-jvmTest.log`, `build/gradle/test-results/jvmTest/*.xml` |
| `./gradlew :terminal-core:wasmJsBrowserTest` | `build/task7-wasmTest.log`, `build/gradle/test-results/wasmJsBrowserTest/*.xml` |
| `./gradlew :terminal-core:verifyNativeArtifacts[ForHost]` | console (both outcomes quoted in §4) |
| `./gradlew :terminal-core:verifyReleaseArtifacts` | console; the release packaging entry point (§4) |
| `bash scripts/check-publish-guard.sh` | `build/publish-guard-check.log` (four scenarios, §4) |
| `./gradlew :terminal-core:publishAllPublicationsToLocalTestRepository` | `build/test-repository/`, `build/gradle/generated/packageMetadata/dev/supermux/terminal/abi-manifest.json` |
| `./gradlew -p terminal-core/consumer-smoke jvmTest` | `build/consumer-smoke-jvm.log`, `consumer-smoke/build/test-results/jvmTest/*.xml` |
| `./gradlew -p terminal-core/consumer-smoke wasmJsBrowserTest` | `build/consumer-smoke-wasm.log`, `consumer-smoke/build/test-results/wasmJsBrowserTest/*.xml` |
| Mac side (separate host) | `build/mac-evidence/{logs*,manifests,test-results}/` |

All Gradle runs on this shared host use
`nice ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2048m -Dkotlin.compiler.execution.strategy=in-process --max-workers=2`.

## 9. Open gaps (nothing here is claimed as supported)

1. **No device runs**: Android (both ABIs), iOS hardware, Windows, arm64 Linux. Their libraries are
   built and export-checked only.
2. **macOS/iOS Kotlin suites are one task behind**: the Mac last ran at the Task-5 tree, so the
   `TerminalSession` owner (26 tests) has never executed on Apple targets. Re-run
   `:terminal-core:jvmTest` and `:terminal-core:iosSimulatorArm64Test` on the Mac at this commit.
3. **macos-x64 from Java**: no x86_64 JVM exists here; the packaged dylib is Rosetta-tested from C
   only. There is also no `macos-x64` manifest in `build/mac-evidence/manifests/` (only
   `macos-arm64`, `ios-arm64`, `ios-simulator-arm64`), so a release assembly must fetch it fresh.
4. **No release publication has ever been assembled**: the runbook in §4 (combined Linux + Mac
   artifact tree, `verifyReleaseArtifacts`, a release version number) is specified, gated and its
   guards are tested, but the full sequence has never been run — in particular the fact that the
   Apple publications can only be produced by a publish run ON the Mac.
5. **Safari/WebKit untested** (Plan 4). The loader only needs `fetch`, `WebAssembly.compile*`,
   BigInt and `Uint8Array`, but Kotlin/Wasm itself requires WasmGC (Safari 18.2+).
6. **Browser consumers need the two-file re-export** (§5). Until a host app does it, bundling fails.
7. The local test repository is a directory in `build/`; nothing has been uploaded anywhere, and no
   signing (GPG) or Maven Central metadata (javadoc jar) is set up — that is a release-time task.
