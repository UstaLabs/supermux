# terminal-core native: pinned libghostty-vt

This directory builds upstream Ghostty's `libghostty-vt` (the VT engine only:
parser, screen/scrollback state, render state, input encoders) from a pinned
commit, and proves with a native smoke test that the pinned C API does what
supermux needs. On top of it sits the package-owned **`st_*` C ABI v1**
([`include/supermux_terminal.h`](include/supermux_terminal.h),
[`src/terminal_bridge.c`](src/terminal_bridge.c)) — the single implementation
of terminal semantics that JNI, cinterop and the wasm loader all call (see
"st_* ABI v1" below).

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
   tarball, check its sha256 and minisign signature, extract into a staging
   dir next to the install dir and rename it into place, so a partial or
   corrupted install never survives). The minisign check needs python3
   `cryptography`; without it the download **fails** unless
   `ST_ALLOW_UNVERIFIED_ZIG=1` is set (then only the sha256 pin is enforced).
   A custom `ST_ZIG_HOME` that exists but holds the wrong Zig is never
   overwritten.
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
8. **Wrapper** (`WRAPPER_SOURCES`, `ST_ABI_VERSION=1`, must match the header):
   compile `src/terminal_bridge.c` (`-fvisibility=hidden -fPIC -Werror`), fold
   it and `libghostty-vt.a` into one static archive
   `lib/libsupermux_terminal.a` (`scripts/combine_archive.py`), link
   `lib/libsupermux_terminal.{so,dylib}` / `supermux_terminal.dll` (not iOS)
   exporting only `st_*`/`Java_*`/`JNI_On*` (ELF version script
   `exports/supermux_terminal.map`, Mach-O `exports/supermux_terminal.exp`,
   PE dllexport), and **fail if the shared library exports anything else**
   (or fewer than the 18 `st_*`). Stage `include/supermux_terminal.h`.
9. Compile the smoke test with `-Wall -Wextra -Werror` and link it against the
   **static** archive (Zig `cc` + Zig's libc++; Android: NDK clang +
   `-static-libstdc++`, 16 KB page size).
   Then compile `tests/terminal_bridge_test.c` and link it against
   `libsupermux_terminal.a` → `bin/terminal_bridge_test`.
10. Fail if any artifact contains `$HOME`, the package path or the build path;
    fail if `src/commonTest/.../CodecGolden.kt` is not what
    `scripts/gen_codec_golden.py` generates from `fixtures/codec/*.bin`.
11. `--test`: run the smoke test, the bridge test (with
    `ST_FIXTURES_DIR=fixtures/codec`, so golden buffers are compared byte for
    byte), a `dlopen` check of the shared library (python ctypes drives one
    terminal through the exported symbols only) and, on a Linux host with
    `gcc`, the bridge test again under **ASan + UBSan + LeakSanitizer**
    (`ST_SKIP_HEAVY=1`); for linux targets also the handle-table threads
    test (`tests/terminal_bridge_threads_test.c`), plainly and under
    **ThreadSanitizer** — all only if the target matches this host (or, for
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

Environment: `ST_ZIG_JOBS` (default 2), `ST_ZIG_HOME`, `ST_ALLOW_UNVERIFIED_ZIG`, `ANDROID_NDK_HOME`,
`ST_LLVM_OBJCOPY`; wasm: `ST_NODE`, `ST_CHROME`, `ST_NO_BROWSER=1`.

## st_* ABI v1

The owned C ABI every binding calls. Header:
[`include/supermux_terminal.h`](include/supermux_terminal.h) (the normative
per-function contract); implementation:
[`src/terminal_bridge.c`](src/terminal_bridge.c); tests:
[`tests/terminal_bridge_test.c`](tests/terminal_bridge_test.c); Kotlin decoder:
`src/commonMain/.../ViewportCodec.kt`.

### Functions

All sizes are fixed-width; every fallible call returns `st_status` (`int32_t`);
outputs are written only on `ST_OK`.

```c
uint32_t  st_abi_version(void);                                  /* == 1 */
st_status st_create(uint32_t abi_version, uint32_t columns, uint32_t rows,
                    uint32_t cell_width_px, uint32_t cell_height_px,
                    uint32_t history_lines, uint64_t history_bytes,
                    const struct GhosttyAllocator *allocator /* NULL = default */,
                    st_handle *out_handle);
st_status st_destroy(st_handle);                                 /* idempotent */
st_status st_feed(st_handle, const uint8_t *data, uint32_t len, uint32_t origin /* 0 LIVE, 1 REPLAY */);
st_status st_reset(st_handle);
st_status st_resize(st_handle, uint32_t columns, uint32_t rows, uint32_t cell_width_px, uint32_t cell_height_px);
st_status st_colors(st_handle, const uint64_t *colors, uint32_t count /* 259: fg, bg, cursor, palette[256] */);
st_status st_read_viewport(st_handle, uint32_t flags /* 1 FORCE_FULL | 2 BREAK_HOLD */,
                           uint8_t **out_buf, uint32_t *out_len, uint32_t *out_frame_flags /* 1 HELD; may be NULL */);
st_status st_acknowledge(st_handle, int64_t generation);
st_status st_scroll_to(st_handle, int64_t row);
st_status st_key(st_handle, uint32_t physical_code, const uint8_t *text, uint32_t text_len,
                 uint32_t modifiers, uint32_t action);
st_status st_mouse(st_handle, int32_t column, int32_t row, uint32_t button, uint32_t modifiers, uint32_t action);
st_status st_paste(st_handle, const uint8_t *text, uint32_t len, uint32_t flags /* 1 ALLOW_UNSAFE */);
st_status st_focus(st_handle, uint32_t focused);
st_status st_select(st_handle, uint32_t has_selection, int64_t start_row, uint32_t start_column,
                    int64_t end_row, uint32_t end_column);
st_status st_selected_text(st_handle, uint8_t **out_buf, uint32_t *out_len);
st_status st_drain_effects(st_handle, uint8_t **out_buf, uint32_t *out_len);
st_status st_free_buffer(uint8_t *buf);                          /* NULL ok */
```

Status: `ST_OK 0`, `INVALID_HANDLE -1`, `ABI_MISMATCH -2`,
`INVALID_ARGUMENT -3`, `OUT_OF_MEMORY -4`, `LIMIT -5` (terminal table full,
envelope > 8 MiB, effect queue full), `REJECTED -6` (unsafe paste),
`INTERNAL -7`. Integer-coded inputs use the package constants
(`TerminalConstants.kt`), converted by `switch`/table code in C (see the
mapping tables below); unknown values → `INVALID_ARGUMENT` (unknown physical
keys → `UNIDENTIFIED`).

### Handles

`st_handle` is an opaque `uint32_t` from a process-wide table of
`ST_MAX_TERMINALS = 1024` slots: `generation << 10 | slot`, never 0, never a
pointer. 0, a destroyed handle (including one whose slot was reused) and any
value never issued fail with `ST_ERR_INVALID_HANDLE`: the table stores the
issued handle next to the engine pointer and compares it atomically before
dereferencing, so a stale handle never touches memory another thread may be
freeing; `st_destroy` is idempotent. `st_create` rejects any `abi_version`
other than 1 with `ST_ERR_ABI_MISMATCH`. No callback ever crosses the ABI.

**Threading.** The table is safe for *different* handles on different
threads (create, every call and destroy may run concurrently as long as each
involves a different handle). Calls on the *same* handle must be externally
serialized — including `st_destroy` against any concurrent use of that
handle, which is undefined. Bindings (Task 4) therefore keep a per-engine lock
plus a closed flag and never call a handle after closing it. Tested by
`tests/terminal_bridge_threads_test.c` (6 threads × 150 cycles, two handles
each, create/feed/read/ack/drain/key/select/destroy, own-content check,
stale-handle check) plainly and under **ThreadSanitizer** (gcc) on the Linux
host; TSan found a real stale-handle race in the first design (lookup read a
reused slot's engine while its owner destroyed it), fixed by the atomic
handle compare.

### Ownership and freeing

- Every output buffer (`st_read_viewport`, `st_drain_effects`,
  `st_selected_text`) is a complete, caller-owned envelope. It stays valid and
  unchanged across later calls **and after `st_destroy`**, until passed to
  `st_free_buffer` exactly once — the one freeing function. The buffer carries
  a 48-byte hidden header (raw pointer, length, a copy of the allocator,
  magic) so it can be freed with only its pointer; a pointer without that
  header is rejected (`INVALID_ARGUMENT`); double free is undefined.
- Bindings copy the bytes (JNI `byte[]`, Kotlin/Native `ByteArray`, JS
  `Uint8Array.slice`) and free immediately.
- `st_drain_effects` is atomic: on failure nothing is consumed.
- A custom `GhosttyAllocator` must outlive the terminal and every buffer
  returned for it.

### Portability / memory

`terminal_bridge.c` uses **no libc** (no stdio, `malloc` or `string.h`; only
`stdint/stddef/stdbool/stdatomic` and the Ghostty API), so the identical file
compiles for every native target and `wasm32-freestanding`. All wrapper
memory comes from `ghostty_alloc`/`ghostty_free` with the terminal's allocator
(NULL → Ghostty's default: libc malloc on native, the module allocator on
wasm), over-allocated by 64 bytes for 16-byte alignment + the free header.

### Codec

```text
u32 magic = 0x53545654; u16 abi = 1; u16 kind; u32 payloadBytes; payload
All integers little-endian. Buffer length is exactly 12 + payloadBytes.
Limits: payload <= 8 MiB; columns/rows 1..4096 and columns*rows <= 100,000;
cell text <= 32 bytes; strings fit the payload.
kind=1 viewport, kind=2 effects, kind=3 selected text.
str = u32 byte length + UTF-8 (always valid: invalid input bytes become U+FFFD)
bytes = u32 length + raw bytes; bool = u8 0/1; nullable = u8 presence + value
Long = i64 except colours (u64, TerminalColor); Int = i32; list = u32 count + items
```

Payloads:

```text
viewport (kind 1), TerminalViewport field order:
  i64 generation
  i32 columns, i32 rows, i32 cellWidthPx, i32 cellHeightPx
  u32 rowCount; rows[]:  i32 index (ascending), u32 cellCount (== columns),
                         cells[]: str text, i32 width (0/1/2), u64 fg, u64 bg, i32 flags, i32 underline
  i32 cursorColumn, i32 cursorRow, i32 shape, bool visible
  bool alternateScreen, bool mouseTracking, bool bracketedPaste
  i64 historyRows, i64 viewportTop, bool full
  u32 linkCount; links[]: i32 row, i32 firstColumn, i32 lastColumn, str uri
  bool hasSelection; [i64 startRow, i32 startColumn, i64 endRow, i32 endColumn]
  bool held                      (synchronized-output frame, hold active)
effects (kind 2):
  u32 count; effects[]: u8 tag, then
    1 Response: bytes   2 Input: bytes   3 Title: str   4 Bell: -
    5 ClipboardRequest: bool write, bool hasText, [str text]
selected text (kind 3):
  str text
```

Decoders (C test, `ViewportCodec.kt`) reject: short buffers, bad
magic/ABI/kind, payload length ≠ remaining bytes or > 8 MiB, trailing bytes,
counts whose minimum encoding cannot fit the remaining bytes (row 8 B, cell
32 B, link 16 B, effect 1 B; list pre-sizing is additionally capped at 4096),
sizes outside 1..4096 or above 100,000 cells, rows out of order/range or not
`columns` wide, full frames without every row, cell text > 32 bytes or
non-empty in a width-0 cell, selection rows outside `0..historyRows+rows-1`,
width ∉ 0..2, invalid
colours (bits 33..63, or DEFAULT with RGBA bits), flags beyond 8 bits,
underline ∉ 0..5, shape ∉ 0..3, booleans ∉ {0,1}, unknown effect tags, a
clipboard read carrying text, links/selection outside the grid, scroll
position `viewportTop > historyRows`, and invalid UTF-8
(`TerminalCodecException` in Kotlin).

Golden fixtures shared by C and Kotlin: `fixtures/codec/*.bin` (written by
the bridge test with `ST_WRITE_GOLDEN=1`, compared byte for byte on every
native `--test` run and in the ASan run); `CodecGolden.kt` is generated from
them by `scripts/gen_codec_golden.py` and `build.sh` fails when it is stale.
To change the encoding: bump the ABI, rerun the bridge test with
`ST_WRITE_GOLDEN=1 ST_FIXTURES_DIR=apps/terminal-core/fixtures/codec`, then
the generator.

### Size cap: every accepted size renders

`ST_MAX_CELLS = 100,000` (`TerminalSize.MAX_CELLS`; e.g. 1000×100, 400×250,
4096×24), enforced by `st_create`/`st_resize` (`INVALID_ARGUMENT`, terminal
unchanged), `TerminalSize` and both decoders. Together with
`ST_MAX_CELL_TEXT = 32` (a longer grapheme cluster is cut at a code point
boundary) and `ST_MAX_LINK_BYTES = 1 MiB` (links beyond the budget are
omitted from that frame) a worst-case full frame is at most
100,000 × (32 + 32) + 4096 × 8 + 1 MiB + 256 = 7.48 MB < 8 MiB — a
`_Static_assert` in `terminal_bridge.c`. Tested: 1000×101 and 4096×64
rejected at create, 4096×25 at resize; 1000×100 and 4096×24 render; a
400×250 frame of 35-byte clusters with 1,200 distinct 2,000-byte links is
7,349,810 bytes (clusters cut to ≤ 32 bytes, 521 links kept within 1 MiB).

### Effects

- `st_feed` sets the origin for the duration of the call. Ghostty's
  `WRITE_PTY` bytes become **Response** (LIVE only); **Bell** and
  **ClipboardRequest** are LIVE only; **Title** (OSC 0/2) is reported for
  both origins. During REPLAY Ghostty still processes queries, but their
  replies are dropped and clipboard writes are denied.
- `st_key` / `st_mouse` / `st_paste` / `st_focus` queue **Input** (user input
  bytes), never Response. `st_paste` returns `ST_ERR_REJECTED` (Kotlin
  `paste(text, allowUnsafe = false): Boolean` → false) and queues nothing for
  text that could inject commands (a newline without bracketed paste, the
  bracketed-paste end marker `ESC[201~` with it); the host may confirm and
  retry with `ST_PASTE_ALLOW_UNSAFE`. Paste output (which Ghostty streams through
  `WRITE_PTY` in chunks) is routed to Input for that call.
- Consecutive chunks of the same kind within one call are merged into one
  effect (a CSI 6n reply is one Response; a bracketed paste is one Input).
  Order across kinds is stream order.
- OSC 52/1337/5522 writes arrive decoded; the text is the first `text/plain`
  (else first `text/*`) representation, `null` = clear.
- OSC 52 **reads** must be answered synchronously by Ghostty's API; the
  engine cannot wait for the embedder, so it always **denies** (the program
  receives an empty clipboard, xterm's behaviour for a disallowed read — a
  `Response ESC]52;c;BEL` effect follows the ClipboardRequest) and reports
  `ClipboardRequest(write=false)` for information only (the Kotlin KDoc says
  the same). Answering reads would
  need an ABI addition (a pre-set clipboard policy/content); v1 does not.
- DA1/DA2 (`CSI c`, `CSI > c`) answer as a VT220-class terminal with ANSI
  colour (`ESC[?62;22c`); XTWINOPS size queries (`CSI 14/16/18 t`) and mode
  2048 reports use the current size; XTVERSION reports `libghostty`.
- The queue is bounded by the 8 MiB payload; an effect that does not fit (or
  cannot be allocated) is dropped and the running call returns
  `ST_ERR_LIMIT`/`ST_ERR_OUT_OF_MEMORY` (the screen is updated regardless).

### Generations and dirty rows

The engine keeps a generation counter (bumped after every mutating call —
feed, reset, resize, colours, scroll, select — and once more when a
synchronized-output frame is captured), the generation its render state
reflects (`rs_gen`), and the last serialized frame (`ser_gen`, whether the
render state is still exactly that frame, whether it was full).

- `st_read_viewport` updates the render state (unless a hold is active) and
  serializes it. The frame is **full** when forced, when a full-requiring
  event happened since the last *acknowledged* full frame (first frame,
  resize, reset, colours, a scroll that moved the viewport, a failed
  serialization, an invalid acknowledgement), or when Ghostty reports its
  render state fully dirty (screen switch, …). Otherwise it carries the rows
  Ghostty marks dirty **since the last acknowledged frame** (render-state dirt
  accumulates until cleaned). Cursor, modes, scroll position, links and
  selection are always sent whole.
- `st_acknowledge(g)` cleans the render state **only if** `g` is the most
  recently serialized frame and the render state has not been updated since
  (no later read, no hold capture). Mutations fed after that frame were
  serialized are *not* lost by the clean: they live in the terminal's own
  dirty tracking and move into the render state at the next update (tested).
  The full-frame epoch a frame satisfies is recorded when its render state
  is captured (not when it is serialized), so an event between a hold
  capture and the read keeps the next frame full.
  Acknowledging an older/superseded frame is a no-op (`ST_OK`) — a pending
  generation is never cleaned by an older ack (tested). An ack of a
  generation never produced returns `INVALID_ARGUMENT` and forces the next
  frame full. A full frame that is never acknowledged keeps the next frames
  full.
- Rows are copied into the buffer; nothing in a returned frame aliases
  engine memory.
- Tested by a 400-step randomized run (feeds, CUP, erase, scroll, resize,
  alt screen, graphemes, selection, sync output; 1 in 6 partial frames
  "dropped": neither applied nor acknowledged) in which the owner's model
  built from partial frames always equals a forced full frame.

### Synchronized output (mode 2026)

The engine installs `RENDER_HOLD`. When a hold begins it captures the frame
the program wants shown (render-state update inside the callback, new
generation) and stops updating the render state; `st_read_viewport` then
returns that frame with `held = true` (last viewport field,
`TerminalViewport.held`) and `ST_FRAME_HELD` in `out_frame_flags`. Kotlin:
`viewport(forceFull, breakHold)`. The hold ends
when the program resets 2026, on RIS/`st_reset`, on `st_resize`, or when the
owner passes `ST_READ_BREAK_HOLD` — **Task 6's owner loop must do this after
~1 s of `ST_FRAME_HELD`** (the engine has no clock). If the capture fails the
hold is ignored (frames stay live). Begin+end within one chunk yields the
live frame (tested).

### Coordinates

- **Viewport** coordinates (rows, cursor, links, `TerminalMouse`): row 0 = top
  visible row, column 0 = left.
- **Absolute** rows (`TerminalPoint.row`, `st_select`, `st_scroll_to`,
  `viewportTop`): Ghostty's `GHOSTTY_POINT_TAG_SCREEN` y coordinate = the
  scrollbar row space: 0 = the **oldest retained** history row of the active
  screen; `historyRows` = scrollbar `total − len` (rows above the active
  area, 0 on the alternate screen); the first active row is `historyRows`.
  When history is pruned, absolute numbers of surviving rows decrease;
  Ghostty tracks the active selection across pruning/scrolling and the frame
  reports its current absolute points.
- `st_scroll_to(row)` clamps to `[0, historyRows]`; `viewportTop ==
  historyRows` means following the bottom.
- The cursor is reported in viewport coordinates even when scrolled off
  screen (then `visible = false`, row may be ≥ rows).
- `st_select` points must be inside the screen (row < historyRows + rows,
  column < columns); both ends inclusive, either order. Selected text is
  Ghostty's plain formatter with `unwrap` (soft wraps joined) and `trim`.
- Links: every viewport row whose row flag says "has hyperlink" is scanned
  cell by cell through `VIEWPORT` grid refs; runs of equal URIs (spacer tails
  of wide characters included) become one `TerminalLink`. They are captured
  together with the render state, so a held frame shows its own links.

### Input details

- Keys: HID usage → `GhosttyKey` table (`ST_KEYS`); layout text is passed as
  `utf8` unless it contains C0/DEL or is not UTF-8 (then ignored); the
  unshifted codepoint is the lower-cased single ASCII letter of the text, else
  the US-layout base character of the physical key; Shift is marked consumed
  when text is present. `setopt_from_terminal` runs before every encode.
- Mouse: cell → pixel at the cell centre with the current cell size, encoder
  `OPT_SIZE` = columns×cellWidth by rows×cellHeight, `setopt_from_terminal`
  before every encode, `ANY_BUTTON_PRESSED` from tracked left/right/middle
  state, motion de-duplicated per cell. Wheel buttons 4–7 under SGR (1006):
  `ESC[<64;x;yM`, `65`, `66`, `67` (tested; Ctrl adds 16; X10 encoding
  tested too).
- Focus: only while mode 1004 is set. Paste: `ghostty_terminal_paste` with
  source TEXT (never a kitty paste event), `text/plain`.

### History budgets

`history_lines` → `OPT_SCROLLBACK_MAX_LINES`, `history_bytes` →
`OPT_SCROLLBACK_MAX_BYTES`; `history_lines = 0` also sets bytes to 0 because
Ghostty's line limit always keeps at least one page. Kitty image storage is
disabled (limit 0: the package renders no images). Ghostty enforces both
limits by **evicting whole pages, oldest first** (a standard page is 215×215
cells of capacity, ~0.5 MiB); terminal pages come from the OS page allocator
(`mmap`, demand-paged; on wasm a module-wide page free list), **not** from the
`GhosttyAllocator`, so the byte budget is measured on process RSS. Measured
on linux-x64 (80×24, `tests/terminal_bridge_test.c`, 2026-09-22):

| fixture | limit | history rows kept | RSS growth | non-page heap |
|---|---|---|---|---|
| 60,000 lines | 10,000 lines | 9,691 (−309: page-granular) | — | — |
| 60,000 styled lines | 2 MiB | 2,755 | 1.91 MiB (0.96×) | 10.7 KB |
| 60,000 styled lines | 8 MiB | 11,425 | 7.66 MiB (0.96×) | 18.2 KB |
| 120,000 styled lines | 32 MiB | 47,727 | 31.77 MiB (0.99×) | 49.9 KB |
| 20,000 lines × 40 cells × 9-codepoint graphemes | 4 MiB | 903 | 4.79 MiB (1.20×: grapheme pages exceed the standard size) | 9.6 KB |
| same | 2,000 lines (64 MiB) | 1,481 | 9.04 MiB | 10.6 KB |

Every fixture checks that the oldest retained row is exactly
`N − 22 − historyRows` (eviction is oldest-first and nothing else is lost) and
that graphemes survive. The documented, asserted bound is **resident growth ≤
history_bytes + 2 MiB** (the active page, one partially filled/pooled page,
grapheme-overflow pages and the small non-page heap), and the line limit
holds to within ±1,500 rows (one page). No source patch was needed.

### Tests (`bash apps/terminal-core/native/build.sh linux-x64 --test`)

`terminal_bridge_test` (real pinned engine, every observation decoded through
a strict C mirror of `ViewportCodec.kt`): handles/ABI/argument validation for
every function, red/empty/wide/styled cells, effects and replay suppression,
key/mouse (incl. wheel 4–7)/focus/paste encodings, links, selection and
absolute coordinates, reset/resize, generations (older ack, post-serialization
mutation, invalid ack, randomized model check), synchronized output, buffer
ownership (valid after more output and after destroy; freed exactly once; no
leaks), framing (every truncation, trailing/magic/ABI/kind/impossible count/
> 8 MiB), the size cap and worst-case frame, whole vs split input at every
byte boundary of a 93-byte fixture and one byte at a time (identical frames
and effects), allocation failure injected at every allocation of a session (only
`OK`/`OUT_OF_MEMORY`, no leaks, engine usable afterwards), golden fixtures,
history budgets, the 1,024-terminal limit.

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
| linux-x64 | yes | **yes** (this host) | `native/build.sh linux-x64 --test`: **66 checks, 0 failures — SMOKE PASSED**; st_* bridge test **208 checks, 0 failures — BRIDGE TEST PASSED**; threads test 6 × 150 cycles OK, and again under TSan; `dlopen` check OK; ASan+UBSan+LSan bridge run **187 checks, 0 failures**. `libghostty-vt.a` 3.3 MB (sha256 `727bd6eb4cfa…`), `.so` 2.4 MB (`c5a5b48ae08a…`); identical hashes across two builds. `libsupermux_terminal.a` 3.4 MB (`fb26df366b41…`), `libsupermux_terminal.so` 2.4 MB (`2e53dcb19abd…`, exports exactly the 18 `st_*`, needs libc/librt only). `.so` files need glibc ≤ 2.27 symbols. |
| wasm32 | yes | **yes** (Node 24 + headless Chrome 148) | `wasm/build.sh --test`: **47 checks, 0 failures in each runtime — WASM SMOKE PASSED** (run against `supermux-terminal.wasm`). `supermux-terminal.wasm` 831 KB (sha256 `e8f5df919983…`): the same `terminal_bridge.c` compiled `wasm32-freestanding` and linked with the wasm `libghostty-vt.a` by `zig cc` (`--export-dynamic --export-table`, 128 KiB stack, table made growable by `wasm/patch_growable_table.py`), 205 function exports = 187 `ghostty_*` + the 18 `st_*`, no imports. Raw upstream `ghostty-vt.wasm` 814 KB (`75f0ed5b23ef…`) is still staged. |
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

No imports; explicit export surface (every function export is `ghostty_*` or `st_*`,
exactly the 18 `st_*`, required exports present, `memory` +
`__indirect_function_table` exported); through `st_*` + the codec envelope: the
red-cell fixture (three `palette[1]` cells, empty cell with DEFAULT fg), a
REPLAY `CSI 6n` queuing nothing and a LIVE one queuing exactly `ESC[1;4R`, and
predictable failure after `st_destroy` (u64 parameters are BigInts in JS); host
allocation/free (256 distinct 16-byte-aligned buffers, zero-length → NULL);
struct layouts and enum values read from `ghostty_type_json()`; the red-cell
fixture through the render-state API; `CSI 6n` response and BEL delivered to
JS callbacks installed in the growable function table via a trampoline module;
memory growth (2.6 MB → 69.7 MB after a 32 MiB allocation) with views
re-acquired and old handles still valid; malformed input does not trap;
1,000 terminal create/free cycles with no further memory growth. Only the VT
engine is loaded — no ghostty-web renderer or DOM terminal.

## Package-owned constant mapping

Kotlin models use package-owned constants
(`src/commonMain/kotlin/dev/supermux/terminal/TerminalConstants.kt`), never
Ghostty enum ordinals. The values are frozen by `TerminalConstantsTest`
(append, never renumber). The `st_*` wrapper (Task 3) translates them to the
pinned Ghostty enums below with explicit `switch`/table code — never by casting.
Upstream values: `API-NOTES.md` §5, §7, §8.

### Colours (`Long`, `TerminalColor`)

```
bit  63..33  32        31..24  23..16  15..8  7..0
     0       DEFAULT   R       G       B      A
```

- Explicit colour: `0x0000_0000_RRGGBBAA` (unsigned RGBA in the low 32 bits).
  Ghostty `GhosttyColorRgb {r,g,b}` → `r<<24 | g<<16 | b<<8 | 0xFF`.
- `TerminalColor.DEFAULT = 0x1_0000_0000` (bit 32 set, RGBA zero) = "default
  fg/bg": emitted when the render-state `FG_COLOR`/`BG_COLOR` query returns
  `GHOSTTY_INVALID_VALUE`. Palette colours arrive already resolved to RGBA.
- Bits 33..63 set = invalid. `TerminalColors.palette` always has 256 entries
  (→ `GHOSTTY_TERMINAL_OPT_COLOR_PALETTE`; alpha is dropped on the way in).

### Cell width (`TerminalCell.width`)

| package | Ghostty `GhosttyCellWide` |
|---|---|
| `1` narrow leading cell | `GHOSTTY_CELL_WIDE_NARROW` |
| `2` wide leading cell | `GHOSTTY_CELL_WIDE_WIDE` |
| `0` continuation / spacer (never rendered) | `GHOSTTY_CELL_WIDE_SPACER_TAIL`, `GHOSTTY_CELL_WIDE_SPACER_HEAD` |

### Style flags (`CellFlags`, bit set) → `GhosttyStyle` bools

| bit | package | Ghostty field |
|---|---|---|
| `1<<0` | `BOLD` | `bold` |
| `1<<1` | `ITALIC` | `italic` |
| `1<<2` | `FAINT` | `faint` |
| `1<<3` | `BLINK` | `blink` |
| `1<<4` | `INVERSE` | `inverse` |
| `1<<5` | `INVISIBLE` | `invisible` |
| `1<<6` | `STRIKETHROUGH` | `strikethrough` |
| `1<<7` | `OVERLINE` | `overline` |

### Underline (`Underline`) → `GhosttyStyle.underline`

| package | Ghostty |
|---|---|
| `0 NONE` | `GHOSTTY_SGR_UNDERLINE_NONE` |
| `1 SINGLE` | `GHOSTTY_SGR_UNDERLINE_SINGLE` |
| `2 DOUBLE` | `GHOSTTY_SGR_UNDERLINE_DOUBLE` |
| `3 CURLY` | `GHOSTTY_SGR_UNDERLINE_CURLY` |
| `4 DOTTED` | `GHOSTTY_SGR_UNDERLINE_DOTTED` |
| `5 DASHED` | `GHOSTTY_SGR_UNDERLINE_DASHED` |

### Cursor shape (`CursorShape`) ← render-state `CURSOR_VISUAL_STYLE`

Note the order differs from Ghostty's (`BAR=0, BLOCK=1`) on purpose: map by name.

| package | Ghostty `GhosttyRenderStateCursorVisualStyle` |
|---|---|
| `0 BLOCK` | `GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK` |
| `1 BAR` | `GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR` |
| `2 UNDERLINE` | `GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE` |
| `3 BLOCK_HOLLOW` | `GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK_HOLLOW` |

### Modifiers (`Modifiers`, bit set) → `GhosttyMods`

| bit | package | Ghostty |
|---|---|---|
| `1<<0` | `SHIFT` | `GHOSTTY_MODS_SHIFT` |
| `1<<1` | `CTRL` | `GHOSTTY_MODS_CTRL` |
| `1<<2` | `ALT` | `GHOSTTY_MODS_ALT` |
| `1<<3` | `SUPER` | `GHOSTTY_MODS_SUPER` |
| `1<<4` | `CAPS_LOCK` | `GHOSTTY_MODS_CAPS_LOCK` |
| `1<<5` | `NUM_LOCK` | `GHOSTTY_MODS_NUM_LOCK` |

The bit positions happen to equal Ghostty's today; the wrapper still maps them
bit by bit. The right-hand `*_SIDE` bits are not exposed (always left).

### Key action (`KeyAction`) → `GhosttyKeyAction`

| package | Ghostty |
|---|---|
| `0 PRESS` | `GHOSTTY_KEY_ACTION_PRESS` |
| `1 RELEASE` | `GHOSTTY_KEY_ACTION_RELEASE` |
| `2 REPEAT` | `GHOSTTY_KEY_ACTION_REPEAT` |

### Mouse action / button (`MouseAction`, `MouseButton`)

| package | Ghostty |
|---|---|
| `MouseAction 0 PRESS` | `GHOSTTY_MOUSE_ACTION_PRESS` |
| `MouseAction 1 RELEASE` | `GHOSTTY_MOUSE_ACTION_RELEASE` |
| `MouseAction 2 MOTION` | `GHOSTTY_MOUSE_ACTION_MOTION` |
| `MouseButton 0 NONE` | `ghostty_mouse_event_clear_button` (motion, no button) |
| `MouseButton 1 LEFT` | `GHOSTTY_MOUSE_BUTTON_LEFT` |
| `MouseButton 2 RIGHT` | `GHOSTTY_MOUSE_BUTTON_RIGHT` |
| `MouseButton 3 MIDDLE` | `GHOSTTY_MOUSE_BUTTON_MIDDLE` |
| `MouseButton 4 WHEEL_UP` | `GHOSTTY_MOUSE_BUTTON_FOUR` |
| `MouseButton 5 WHEEL_DOWN` | `GHOSTTY_MOUSE_BUTTON_FIVE` |
| `MouseButton 6 WHEEL_LEFT` | `GHOSTTY_MOUSE_BUTTON_SIX` |
| `MouseButton 7 WHEEL_RIGHT` | `GHOSTTY_MOUSE_BUTTON_SEVEN` |

`TerminalMouse` carries viewport **cell** column/row; Ghostty's mouse event takes
surface **pixels**. The wrapper sends the cell centre,
`x = column*cellWidthPx + cellWidthPx/2`, `y = row*cellHeightPx + cellHeightPx/2`,
with `OPT_SIZE` set from the current `TerminalSize` (no padding).

### Physical keys (`TerminalKeys`) → `GhosttyKey`

`physicalCode` values are USB HID Keyboard/Keypad usage IDs (HID Usage Tables,
page `0x07`) — the numbering W3C `KeyboardEvent.code` is defined against.
Anything not listed maps to `GHOSTTY_KEY_UNIDENTIFIED` (only `text` is used).

| package (HID usage) | Ghostty |
|---|---|
| `UNIDENTIFIED` `0x00` | `GHOSTTY_KEY_UNIDENTIFIED` |
| `A`..`Z` `0x04`..`0x1D` | `GHOSTTY_KEY_A`..`GHOSTTY_KEY_Z` |
| `DIGIT_1`..`DIGIT_9` `0x1E`..`0x26` | `GHOSTTY_KEY_DIGIT_1`..`GHOSTTY_KEY_DIGIT_9` |
| `DIGIT_0` `0x27` | `GHOSTTY_KEY_DIGIT_0` |
| `ENTER` `0x28` | `GHOSTTY_KEY_ENTER` |
| `ESCAPE` `0x29` | `GHOSTTY_KEY_ESCAPE` |
| `BACKSPACE` `0x2A` | `GHOSTTY_KEY_BACKSPACE` |
| `TAB` `0x2B` | `GHOSTTY_KEY_TAB` |
| `SPACE` `0x2C` | `GHOSTTY_KEY_SPACE` |
| `MINUS` `0x2D` | `GHOSTTY_KEY_MINUS` |
| `EQUAL` `0x2E` | `GHOSTTY_KEY_EQUAL` |
| `BRACKET_LEFT` `0x2F` | `GHOSTTY_KEY_BRACKET_LEFT` |
| `BRACKET_RIGHT` `0x30` | `GHOSTTY_KEY_BRACKET_RIGHT` |
| `BACKSLASH` `0x31` | `GHOSTTY_KEY_BACKSLASH` |
| `SEMICOLON` `0x33` | `GHOSTTY_KEY_SEMICOLON` |
| `QUOTE` `0x34` | `GHOSTTY_KEY_QUOTE` |
| `BACKQUOTE` `0x35` | `GHOSTTY_KEY_BACKQUOTE` |
| `COMMA` `0x36` | `GHOSTTY_KEY_COMMA` |
| `PERIOD` `0x37` | `GHOSTTY_KEY_PERIOD` |
| `SLASH` `0x38` | `GHOSTTY_KEY_SLASH` |
| `F1`..`F12` `0x3A`..`0x45` | `GHOSTTY_KEY_F1`..`GHOSTTY_KEY_F12` |
| `INSERT` `0x49` | `GHOSTTY_KEY_INSERT` |
| `HOME` `0x4A` | `GHOSTTY_KEY_HOME` |
| `PAGE_UP` `0x4B` | `GHOSTTY_KEY_PAGE_UP` |
| `DELETE` `0x4C` | `GHOSTTY_KEY_DELETE` |
| `END` `0x4D` | `GHOSTTY_KEY_END` |
| `PAGE_DOWN` `0x4E` | `GHOSTTY_KEY_PAGE_DOWN` |
| `ARROW_RIGHT` `0x4F` | `GHOSTTY_KEY_ARROW_RIGHT` |
| `ARROW_LEFT` `0x50` | `GHOSTTY_KEY_ARROW_LEFT` |
| `ARROW_DOWN` `0x51` | `GHOSTTY_KEY_ARROW_DOWN` |
| `ARROW_UP` `0x52` | `GHOSTTY_KEY_ARROW_UP` |

`TerminalKey.text` → `ghostty_key_event_set_utf8` (empty → NULL; never C0/DEL).
Numpad, modifier-only and international keys are deliberately not in the
first set; add them later by appending their HID usage IDs.
