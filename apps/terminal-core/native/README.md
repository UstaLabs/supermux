# terminal-core native: pinned libghostty-vt

This directory builds upstream Ghostty's `libghostty-vt` (the VT engine only:
parser, screen/scrollback state, render state, input encoders) from a pinned
commit, and proves with a native smoke test that the pinned C API does what
supermux needs. The supermux wrapper (`st_*` C ABI, JNI, cinterop) comes in
later tasks and plugs into the same build (see "Wrapper hook").

- Pin + toolchain: [`upstream.lock.json`](upstream.lock.json)
- API summary for wrapper/binding design: [`API-NOTES.md`](API-NOTES.md)
- Smoke test (real pinned headers only): [`tests/ghostty_smoke_test.c`](tests/ghostty_smoke_test.c)
- Header-only probe: [`tests/header_probe.c`](tests/header_probe.c)
- WASM: [`../wasm/build.sh`](../wasm/build.sh), [`../wasm/smoke-core.mjs`](../wasm/smoke-core.mjs)

## Pin

| | |
|---|---|
| Upstream | `https://github.com/ghostty-org/ghostty.git` |
| Commit | `22391ed6491f2924361dcad1f9a9176a390fd20f` (2026-09-21, "opengl: validate exported DMA-BUF planes (#14322)") |
| App version at pin | `1.3.2-dev` (`build.zig.zon`) |
| libghostty-vt version | `0.1.0-dev` (`build.zig` `lib_version`; passed explicitly via `-Dlib-version-string`) |
| Zig | `0.16.0` exactly (`build.zig.zon` `minimum_zig_version`, enforced by `requireZig`) |
| Zig tarball (x86_64-linux) | sha256 `70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00`, minisign-verified against `RWSGOq2NVecA2UPNdBUZykf1CCb147pkmdtYxgb3Ti+JO/wCYvhbAb/U` |

The upstream checkout is a shallow fetch of exactly that commit into the
ignored cache `apps/terminal-core/build/upstream/ghostty`. Nothing tracks a
moving branch and no prebuilt binary is downloaded; `build.sh` refuses a cache
whose HEAD differs from the lock or that has local modifications.

Zig fetches the dependencies declared in the pinned `build.zig.zon` files and
verifies each against its content hash: highway, wuffs, uucode, translate-c
(+ aro), and — pulled in by the build graph though lib-vt does not link them —
zlib, pixels and ghostty-themes (`deps.files.ghostty.org`, `github.com`,
`codeberg.org`). simdutf is vendored in the repo (`pkg/simdutf/vendor`).
Package cache: `build/zig-global-cache`.

## Build commands

```sh
bash apps/terminal-core/native/build.sh <target> [--test]
bash apps/terminal-core/wasm/build.sh [--test]
```

Targets: `linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64 android-arm64
android-x64 ios-arm64 ios-simulator-arm64`.

What `native/build.sh` does, in order:

1. Resolve Zig `0.16.0` (`~/.local/zig/0.16.0`, or download the pinned host
   tarball, check its sha256 and minisign signature).
2. Ensure the upstream cache is at the pinned commit.
3. Resolve the Android NDK (android targets) and `llvm-objcopy` (from the
   pinned NDK `28.2.13676358`, or `ST_LLVM_OBJCOPY`, or `PATH`).
4. **Header probe** — compile `tests/header_probe.c` against the pinned headers
   for the target (`-Werror`). A missing/renamed declaration fails here.
5. `zig build` libghostty-vt with the exact flags below (`nice`, `-j2`).
6. Stage headers + libraries to `build/native/<target>/{include,lib,bin}`.
7. **Normalize**: strip DWARF (`llvm-objcopy --strip-debug`, symbols kept) from
   shared libs and every archive member, and rebuild the static archive with
   member names `NNNN_<object>.o` (upstream's `zig ar -M` combine step names
   members by absolute Zig-cache paths). pkg-config files are dropped (they
   embed the absolute prefix).
8. Wrapper hook (no-op until `WRAPPER_SOURCES` is populated).
9. Compile the smoke test with `-Wall -Wextra -Werror` and link it against the
   **static** archive (Zig `cc` + Zig's libc++; Android: NDK clang +
   `-static-libstdc++`, 16 KB page size).
10. Fail if any artifact contains `$HOME`, the package path or the build path.
11. `--test`: run the smoke test only if the target matches this host (or, for
    Android, an adb device with the matching ABI). Otherwise the manifest says
    `runtime_tested: false` with the reason, and the script exits 3.
12. Write `build/native/<target>/manifest.json`: target, Zig triple/cpu, ABI
    version, Ghostty commit, Zig/NDK/objcopy versions, runtime-test result and
    host, and sha256 + size of every file.

### Exact upstream build flags

```sh
zig build -j2 \
  --global-cache-dir apps/terminal-core/build/zig-global-cache \
  --cache-dir apps/terminal-core/build/zig-cache/<target> \
  --prefix apps/terminal-core/build/work/<target>/ghostty \
  -Demit-lib-vt=true -Doptimize=ReleaseFast \
  -Dtarget=<zig_target> -Dcpu=<cpu> -Dlib-version-string=0.1.0-dev
```

WASM: same, with `-Doptimize=ReleaseSmall -Dtarget=wasm32-freestanding` and
no `-Dcpu` (upstream adds `+simd128`).

| target | `-Dtarget` | `-Dcpu` | notes |
|---|---|---|---|
| linux-x64 | `x86_64-linux-gnu.2.28` | `x86_64_v2` | glibc floor 2.28 |
| linux-arm64 | `aarch64-linux-gnu.2.28` | `baseline` | |
| macos-x64 | `x86_64-macos` | `baseline` | upstream CI cross-builds this from Linux |
| macos-arm64 | `aarch64-macos` | `baseline` | upstream CI cross-builds this from Linux |
| windows-x64 | `x86_64-windows-gnu` | `x86_64_v2` | upstream's default is MSVC ABI, which cannot be cross-built (no MSVC headers); GNU ABI is upstream-CI-tested |
| android-arm64 | `aarch64-linux-android.26` | `baseline` | API 26 = app `minSdk`; `ANDROID_NDK_HOME` = NDK 28.2.13676358 |
| android-x64 | `x86_64-linux-android.26` | `x86_64_v2` | |
| ios-arm64 | `aarch64-ios` | `baseline` | requires a macOS host with Xcode (upstream: "lib-vt requires macOS runner for macOS/iOS builds") |
| ios-simulator-arm64 | `aarch64-ios-simulator` | `baseline` | same |
| wasm32 | `wasm32-freestanding` | (default +simd128) | `ReleaseSmall` |

Other supported upstream flags (not used yet): `-Dsimd=false` (pure Zig, no
libc/C++ runtime, slower), `-Dvt-features=-all,+render-state,...` to trim
features, `-Demit-xcframework` (macOS host) for an Apple xcframework.

Environment: `ST_ZIG_JOBS` (default 2), `ST_ZIG_HOME`, `ANDROID_NDK_HOME`,
`ST_LLVM_OBJCOPY`; wasm: `ST_NODE`, `ST_CHROME`, `ST_NO_BROWSER=1`.

## Wrapper hook

`build.sh` has `WRAPPER_SOURCES=()` and `ST_ABI_VERSION=0`. The wrapper task
adds `native/src/terminal_bridge.c` (+ `terminal_jni.c` for JVM/Android) to
that array, implements `build_wrapper()` (compile against
`build/native/<target>/include`, link the static `libghostty-vt.a`, export
only `st_*`/`Java_*`), and bumps `ST_ABI_VERSION`. The manifest already
carries `abi_version` and `wrapper`.

## Results

Recorded 2026-09-22 on the shared Linux build host (x86_64, Ubuntu, glibc 2.43,
kernel 7.0.0-31). Artifacts land in `apps/terminal-core/build/native/<target>/`
(ignored); each has a `manifest.json` with per-file sha256.

Toolchain IDs:
- Zig `0.16.0` (bundled clang 21.1.0, libc++, glibc/musl/mingw/macOS libc stubs),
  tarball sha256 `70e49664…3d00`, minisign OK.
- Android NDK `28.2.13676358` (r28c): clang 19.0.1 (`r530567e`), used for the
  android sysroot (upstream `pkg/android-ndk`) and to link the Android smoke test.
- `llvm-objcopy` 19.0.1 from that NDK (strip + Mach-O ad-hoc re-sign).
- Node `v24.16.0` (nvm), Google Chrome `148.0.7778.178` headless.

| target | built | runtime-tested | result / notes |
|---|---|---|---|
| linux-x64 | yes | **yes** (this host) | `native/build.sh linux-x64 --test`: **66 checks, 0 failures — SMOKE PASSED**. `libghostty-vt.a` 3.3 MB (sha256 `727bd6eb4cfa…`), `.so` 2.4 MB (`c5a5b48ae08a…`); identical hashes across two builds. `.so` needs glibc ≤ 2.27 symbols. |
| wasm32 | yes | **yes** (Node 24 + headless Chrome 148) | `wasm/build.sh --test`: **30 checks, 0 failures in each runtime — WASM SMOKE PASSED**. `ghostty-vt.wasm` 814 KB ReleaseSmall (sha256 `75f0ed5b23ef…`), 187 function exports, all `ghostty_*`. |
| linux-arm64 | yes | no (no arm64 host/qemu here) | smoke test cross-linked (`aarch64`, glibc 2.28). |
| windows-x64 | yes | no (no Windows host/wine) | `x86_64-windows-gnu`: `ghostty-vt-static.lib`, `ghostty-vt.dll` + import lib, smoke `.exe`; imports only KERNEL32/ntdll/UCRT (`api-ms-win-crt-*`). PDBs dropped. |
| android-arm64 | yes | no (no adb device attached) | API 26, NDK-linked smoke test; `.so` has 16 KB-aligned LOAD segments, needs only libc/libm. Run `build.sh android-arm64 --test` with a device attached. |
| android-x64 | yes | no (no adb device/emulator running) | same as above; `--test` exits 3 with `no-matching-device` in the manifest. |
| macos-arm64 | yes (cross from Linux) | no | Mach-O dylib + static lib + smoke exe; ad-hoc signatures re-generated by llvm-objcopy after stripping and verified page-by-page (0 bad pages). Must be runtime-tested on the Mac. |
| macos-x64 | yes (cross from Linux) | no | as above. |
| ios-arm64 | **no — needs the Mac** | no | Direct `zig build -Dtarget=aarch64-ios` on Linux panics in `pkg/apple-sdk/build.zig` `pathsForTarget` (translate-c for wuffs needs the Apple SDK). `build.sh` fails fast with that message. Build on a macOS host with Xcode. |
| ios-simulator-arm64 | **no — needs the Mac** | no | same as ios-arm64. |

No artifact contains `$HOME`, the package path or the build path (checked by
`build.sh` / `wasm/build.sh` on every build). Every first build of a target
took ~14–23 min on this loaded host at `-j2`; cached rebuilds take ~20 s–2 min (plus a one-off libc++ build per target for the smoke link, ~5 min for Windows).

### What the native smoke test proves

`tests/ghostty_smoke_test.c` (real pinned headers, static link, no stubs):

- **Red-cell fixture** via the render-state API: `ESC[31mredESC[0m` → cells 0–2
  are `r`,`e`,`d`, style `fg=palette[1]`, resolved `FG_COLOR` = palette[1]
  (204,102,102), narrow; cell 3 empty/unstyled; cursor (3,0); dirty → clean
  cycle; resize 80x24 → 40x10 keeps content and reports pixel size.
- **Effects**: `CSI 6n` → `write_pty` gets `ESC[1;3R`; DECRQM reply; BEL; OSC 2 title.
- **Modes**: bracketed paste, mouse tracking + SGR, alt screen on/off.
- **Encoders**: Ctrl+C → `0x03`, ArrowUp under DECCKM → `ESC O A`, SGR mouse
  press → `ESC[<0;6;3M`, focus → `ESC[I`, bracketed `paste_encode`.
- **Content**: OSC 8 hyperlink URI via grid ref, wide char → WIDE + SPACER_TAIL,
  select-all → plain text `"ab\nlink plain\n世!"`.
- **Scrollback**: line and byte limits round-trip; scroll viewport to top; RIS.
- **Malformed input**: unfinished CSI/OSC, truncated UTF-8, huge params, 1,000
  dangling `ESC[`, C1 junk → no crash, still usable, no VT processing error;
  `vt_write_until_ground` stops at the boundary.
- **Lifecycle**: 1,000 × (new → feed → [resize] → free) with a counting
  allocator: 8,020 allocations, 0 live allocations / 0 bytes at the end.

### What the WASM smoke proves (`wasm/smoke-core.mjs`, same code in Node and Chrome)

No imports; explicit export surface (every function export is `ghostty_*`, required
exports present, `memory` + `__indirect_function_table` exported); host
allocation/free (256 distinct 16-byte-aligned buffers, zero-length → NULL);
struct layouts and enum values read from `ghostty_type_json()`; the red-cell
fixture through the render-state API; `CSI 6n` response and BEL delivered to
JS callbacks installed in the growable function table via a trampoline module;
memory growth (2.6 MB → 69.7 MB after a 32 MiB allocation) with views
re-acquired and old handles still valid; malformed input does not trap;
1,000 terminal create/free cycles with no further memory growth. Only the VT
engine is loaded — no ghostty-web renderer or DOM terminal.

## Package-owned constant mapping (TODO: Task 2)

Kotlin models use package-owned constants, not Ghostty enum ordinals. Task 2
fills this table (key physical codes, modifier bits, key/mouse actions, mouse
buttons, cursor shapes, style flags, underline kinds, colour encoding with the
default-colour flag, cell width `0/1/2`) and freezes it in codec fixtures.
The upstream values it maps from are listed in `API-NOTES.md` §5, §7, §8.
