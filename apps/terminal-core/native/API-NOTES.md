# libghostty-vt C API notes (pinned)

Pin: Ghostty `22391ed6491f2924361dcad1f9a9176a390fd20f` (libghostty-vt `0.1.0-dev`,
Zig `0.16.0`). Source of truth: `build/upstream/ghostty/include/ghostty/vt/*.h`
(umbrella `ghostty/vt.h`). Everything below was read from those headers and the
parts marked **[smoke]** are exercised by `native/tests/ghostty_smoke_test.c`
and/or `wasm/smoke-core.mjs`.

These notes exist so the `st_*` wrapper and Kotlin bindings can be designed
without re-reading 12k lines of headers. When they disagree with the headers,
the headers win; update this file.

## 0. Conventions

- Every function returns `GhosttyResult` unless noted:
  `GHOSTTY_SUCCESS=0, OUT_OF_MEMORY=-1, INVALID_VALUE=-2, OUT_OF_SPACE=-3,
  NO_VALUE=-4, IO_ERROR=-5, LIMIT_EXCEEDED=-6, REJECTED=-7`.
- All C enums are `int`-sized (sentinel `*_MAX_VALUE = INT_MAX`).
- **Sized structs**: first field `size_t size`; init with
  `GHOSTTY_INIT_SIZED(Type)` (`(Type){ .size = sizeof(Type) }`). Used for
  `GhosttyStyle`, `GhosttyGridRef`, `GhosttySelection`, `GhosttyRenderStateCursor`,
  `GhosttyRenderStateColors`, `GhosttyRenderStateRowSelection`,
  `GhosttyMouseEncoderSize`, formatter/selection option structs, `GhosttyPaste`,
  clipboard request/reply structs.
- `GhosttyString { const uint8_t* ptr; size_t len; }` (borrowed, not NUL-terminated).
  `GhosttyBuffer { uint8_t* ptr; size_t cap; size_t len; }` (caller buffer; on
  `OUT_OF_SPACE`, `len` = required size).
- Opaque handles (pointers): `GhosttyTerminal`, `GhosttyRenderState`,
  `GhosttyRenderStateRowIterator`, `GhosttyRenderStateRowCells`, `GhosttyKeyEncoder`,
  `GhosttyKeyEvent`, `GhosttyMouseEncoder`, `GhosttyMouseEvent`, `GhosttyFormatter`,
  `GhosttySearch`, `GhosttySelectionGesture`, `GhosttyTrackedGridRef`, `GhosttySnapshotDecoder`.
- Static linking: define `GHOSTTY_STATIC` before including headers (otherwise
  `GHOSTTY_API` is `dllimport` on Windows).
- `const char* ghostty_type_json(void)` — versioned ABI manifest (JSON, process
  lifetime): sizes/offsets/alignments/enum values/packed bit layouts for the
  linked build. Bindings with costly FFI (WASM) should read layouts from it, not
  hardcode them. **[smoke]** (wasm reads every offset/enum from it)
- Threading: no internal threads/timers. A terminal and everything derived from
  it must be serialized by the caller. Callbacks run synchronously on the
  thread calling `vt_write`; they **must not re-enter** `vt_write` on the same terminal.

## 1. Allocator

```c
typedef struct {
  void* (*alloc)(void* ctx, size_t len, uint8_t alignment, uintptr_t ret_addr);
  bool  (*resize)(void* ctx, void* mem, size_t mem_len, uint8_t alignment, size_t new_len, uintptr_t ret_addr);
  void* (*remap)(void* ctx, void* mem, size_t mem_len, uint8_t alignment, size_t new_len, uintptr_t ret_addr);
  void  (*free)(void* ctx, void* mem, size_t mem_len, uint8_t alignment, uintptr_t ret_addr);
} GhosttyAllocatorVtable;
typedef struct GhosttyAllocator { void* ctx; const GhosttyAllocatorVtable* vtable; } GhosttyAllocator;
uint8_t* ghostty_alloc(const GhosttyAllocator*, size_t len);
void     ghostty_free(const GhosttyAllocator*, uint8_t* ptr, size_t len);
```

- Zig allocator semantics: `alignment` is **log2** of the byte alignment; `free`
  receives the exact length; `resize` may return false (caller then alloc+copy);
  `remap` may return NULL.
- Every `*_new` takes `const GhosttyAllocator*` (NULL = default allocator; on
  native targets that is the C allocator when libc is linked).
- Buffers returned by `*_alloc` APIs (formatter, selection, snapshot,
  continuation) are freed with `ghostty_free(same_allocator, ptr, len)`.
- **[smoke]** A counting allocator passed to 1,000 `ghostty_terminal_new`/`free`
  cycles ends at 0 live allocations / 0 live bytes.
- ⚠️ Terminal **pages** (all cell/scrollback storage) do NOT come from this
  allocator: `PageList` allocates them from `std.heap.page_allocator` (mmap,
  demand-paged; Darwin: tagged mach pages; wasm: a module-wide page free list
  that never shrinks). The `GhosttyAllocator` only sees the small non-page heap
  (render state, nodes, pins, formatter output: ~10–50 KB per terminal). So a
  custom allocator can NOT enforce a hard memory budget; the scrollback byte
  limit (§6) is what bounds page memory. (Verified by
  `tests/terminal_bridge_test.c` history fixtures: RSS grows with the byte
  budget while the counting allocator stays at ~10 KB.)
- `ghostty_alloc(alloc, n)` requests alignment 1 (log2 0); the st_* wrapper
  over-allocates to align its own structures.

## 2. Terminal lifecycle

There is **no `GhosttyTerminalOptions` struct** in this pin. Create, then configure:

```c
GhosttyResult ghostty_terminal_new(const GhosttyAllocator*, GhosttyTerminal* out, uint16_t cols, uint16_t rows);
void          ghostty_terminal_free(GhosttyTerminal);            // NULL ok
void          ghostty_terminal_reset(GhosttyTerminal);           // RIS; keeps size
GhosttyResult ghostty_terminal_resize(GhosttyTerminal, uint16_t cols, uint16_t rows,
                                      uint32_t cell_width_px, uint32_t cell_height_px);
GhosttyResult ghostty_terminal_set(GhosttyTerminal, GhosttyTerminalOption, const void* value);
GhosttyResult ghostty_terminal_get(GhosttyTerminal, GhosttyTerminalData, void* out);
GhosttyResult ghostty_terminal_get_multi(GhosttyTerminal, size_t n, const GhosttyTerminalData* keys,
                                         void** values, size_t* out_written);
void          ghostty_terminal_vt_write(GhosttyTerminal, const uint8_t* data, size_t len);  // never fails
GhosttyResult ghostty_terminal_vt_write_until_ground(GhosttyTerminal, const uint8_t*, size_t,
                                                     size_t* out_consumed);  // NO_VALUE = not at ground yet
```

- `resize` reflows the primary screen (wraparound on), not the alt screen; it
  ends any synchronized-output hold and emits a mode-2048 in-band size report
  if enabled. Pixel size is `cols*cell_w` x `rows*cell_h`. **[smoke]**
- `vt_write` treats input as untrusted: malformed/incomplete sequences never
  fail or crash; parser state carries across calls. **[smoke]** (unterminated
  CSI/OSC, truncated UTF-8, huge params, 1,000 dangling `ESC[`, C1 junk).
- `GHOSTTY_TERMINAL_DATA_VT_GROUND` (bool) says whether the parser is at ground
  (safe point to inject out-of-band bytes). `GHOSTTY_TERMINAL_DATA_VT_PROCESSING_ERROR`
  (bool, sticky) reports non-graceful internal failures.
- Replay continuation (for reconnect/replay boundaries):
  `GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES` (size_t*, 0/NULL disables) then
  `ghostty_terminal_continuation_buf/_alloc/_write` return the exact byte
  suffix needed to rebuild unfinished parser state in another terminal.
- Snapshots (full state serialization, `snapshot.h`): `ghostty_snapshot_encode[_buf|_alloc]`
  and `ghostty_snapshot_decoder_*` (new/new_buf/set/ready/next/decode/get/free).
- Compression of scrollback is caller-driven: `ghostty_terminal_compression_activity(t, &token)`
  and `ghostty_terminal_compress(t, INCREMENTAL|FULL, &result)`.

### `GhosttyTerminalOption` (for `ghostty_terminal_set`)

| id | option | value |
|---|---|---|
| 0 | `USERDATA` | `void*` passed to every callback (one per terminal) |
| 1 | `WRITE_PTY` | `GhosttyTerminalWritePtyFn` |
| 2 | `BELL` | `GhosttyTerminalBellFn` |
| 3 | `ENQUIRY` | `GhosttyTerminalEnquiryFn` |
| 4 | `XTVERSION` | `GhosttyTerminalXtversionFn` |
| 5 | `TITLE_CHANGED` | `GhosttyTerminalTitleChangedFn` |
| 6 | `SIZE` | `GhosttyTerminalSizeFn` (CSI 14/16/18 t, mode 2048) |
| 7 | `COLOR_SCHEME` | `GhosttyTerminalColorSchemeFn` (CSI ? 996 n) |
| 8 | `DEVICE_ATTRIBUTES` | `GhosttyTerminalDeviceAttributesFn` (DA1/2/3) |
| 9 | `TITLE` | `GhosttyString*` set title manually |
| 10 | `PWD` | `GhosttyString*` |
| 11–14 | `COLOR_FOREGROUND/BACKGROUND/CURSOR/PALETTE` | `GhosttyColorRgb*` / `GhosttyColorRgb[256]*` (defaults; OSC overrides layer on top) |
| 15–18 | `KITTY_IMAGE_STORAGE_LIMIT` (`uint64_t*`, 0 disables kitty graphics), `KITTY_IMAGE_MEDIUM_FILE/TEMP_FILE/SHARED_MEM` | |
| 19, 20 | `APC_MAX_BYTES`, `APC_MAX_BYTES_KITTY` | `size_t*` |
| 21 | `SELECTION` | `GhosttySelection*` (NULL clears) |
| 22, 23 | `DEFAULT_CURSOR_STYLE` (`GhosttyTerminalCursorStyle*`), `DEFAULT_CURSOR_BLINK` (`bool*`) | |
| 24 | `GLYPH_PROTOCOL` | `bool*` |
| 25 | `PWD_CHANGED` | `GhosttyTerminalPwdChangedFn` |
| 26 | `CLIPBOARD_WRITE` | `GhosttyTerminalClipboardWriteFn` (OSC 52 / 1337 / 5522) |
| 27 | `SCROLLBACK_MAX_BYTES` | `size_t*`; NULL = unlimited; 0 = no scrollback |
| 28 | `SCROLLBACK_MAX_LINES` | `size_t*`; NULL = unlimited |
| 29 | `DESKTOP_NOTIFICATION` | `GhosttyTerminalDesktopNotificationFn` (OSC 9/777) |
| 30 | `PROGRESS_REPORT` | `GhosttyTerminalProgressReportFn` (OSC 9;4) |
| 31 | `CONTINUATION_MAX_BYTES` | `size_t*` |
| 32 | `TITLE_REPORT` | `bool*` enable CSI 21 t (off by default — injection risk) |
| 33 | `MODE_DEFAULT` | `GhosttyTerminalModeConfig*` (also survives RIS) |
| 34 | `MODE` | `GhosttyTerminalModeConfig*` set current mode value |
| 35, 36 | `UNKNOWN_SEQUENCE` (fn), `UNKNOWN_MAX_BYTES` (`size_t*`) | APC capture |
| 37 | `TERMINFO_NAME` | `GhosttyString*` (XTGETTCAP TN) |
| 38 | `CLIPBOARD_READ` | `GhosttyTerminalClipboardReadFn` (OSC 52 `?`, 5522) |
| 39 | `CLIPBOARD_WRITE_MAX_BYTES` | `size_t*` (OSC 5522 only; default 64 MiB) |
| 40 | `RESIZE_PULL_SCROLLBACK` | `bool*` (set false for ConPTY) |
| 41 | `RENDER_HOLD` | `GhosttyTerminalRenderHoldFn` (mode 2026 begin/end) |

Callback values are passed **as the function pointer itself** (cast to
`const void*`), not a pointer to it. NULL clears.

### `GhosttyTerminalData` (for `ghostty_terminal_get`)

`COLS`/`ROWS` (u16), `CURSOR_X`/`CURSOR_Y` (u16, active area), `CURSOR_PENDING_WRAP` (bool),
`ACTIVE_SCREEN` (`GhosttyTerminalScreen`: `PRIMARY=0, ALTERNATE=1`), `CURSOR_VISIBLE` (bool),
`KITTY_KEYBOARD_FLAGS` (u8), `SCROLLBAR` (`GhosttyTerminalScrollbar {u64 total, offset, len}`),
`CURSOR_STYLE` (`GhosttyStyle` = current SGR pen), `MOUSE_TRACKING` (bool: any of 9/1000/1002/1003),
`TITLE`/`PWD` (`GhosttyString`, borrowed until next mutating call), `TOTAL_ROWS`/`SCROLLBACK_ROWS` (size_t),
`WIDTH_PX`/`HEIGHT_PX` (u32), `COLOR_*` and `COLOR_*_DEFAULT` (`GhosttyColorRgb`, `NO_VALUE` if unset;
palette always present), `KITTY_*`, `SELECTION` (`GhosttySelection`, `NO_VALUE` if none),
`VIEWPORT_ACTIVE` (bool: viewport pinned to bottom), `VT_PROCESSING_ERROR`, `SCROLLBACK_MAX_BYTES`/
`SCROLLBACK_MAX_LINES` (size_t, `NO_VALUE` if unlimited), `CONTINUATION_MAX_BYTES`,
`MODE` (in/out `GhosttyTerminalModeConfig`), `VT_GROUND` (bool), `CURSOR_AT_PROMPT` (bool, OSC 133),
`CLIPBOARD_WRITE_MAX_BYTES`.

## 3. Effects / callbacks (terminal → embedder)

All share `(GhosttyTerminal terminal, void* userdata, ...)`; userdata is the
single `OPT_USERDATA` pointer. By default (no callback installed) queries are
silently ignored — **no response bytes are produced unless `WRITE_PTY` is set**.

```c
typedef void (*GhosttyTerminalWritePtyFn)(GhosttyTerminal, void*, const uint8_t* data, size_t len);
typedef void (*GhosttyTerminalBellFn)(GhosttyTerminal, void*);
typedef void (*GhosttyTerminalTitleChangedFn)(GhosttyTerminal, void*);   // read DATA_TITLE after
typedef void (*GhosttyTerminalPwdChangedFn)(GhosttyTerminal, void*);     // read DATA_PWD (raw bytes, e.g. file:// URI)
typedef GhosttyString (*GhosttyTerminalEnquiryFn)(GhosttyTerminal, void*);
typedef GhosttyString (*GhosttyTerminalXtversionFn)(GhosttyTerminal, void*);   // empty = "libghostty"
typedef bool (*GhosttyTerminalSizeFn)(GhosttyTerminal, void*, GhosttySizeReportSize* out); // {u16 rows, columns; u32 cell_width, cell_height}
typedef bool (*GhosttyTerminalColorSchemeFn)(GhosttyTerminal, void*, GhosttyColorScheme* out);  // LIGHT=0, DARK=1
typedef bool (*GhosttyTerminalDeviceAttributesFn)(GhosttyTerminal, void*, GhosttyDeviceAttributes* out);
typedef void (*GhosttyTerminalClipboardWriteFn)(GhosttyTerminal, void*, const GhosttyClipboardWrite*);
typedef void (*GhosttyTerminalClipboardReadFn)(GhosttyTerminal, void*, const GhosttyClipboardRead*);
typedef void (*GhosttyTerminalDesktopNotificationFn)(GhosttyTerminal, void*, const GhosttyTerminalDesktopNotification*); // {size, GhosttyString title, body}
typedef void (*GhosttyTerminalProgressReportFn)(GhosttyTerminal, void*, const GhosttyTerminalProgressReport*); // {size, state, int8 progress(-1)}
typedef void (*GhosttyTerminalRenderHoldFn)(GhosttyTerminal, void*, bool held);
typedef void (*GhosttyTerminalUnknownSequenceFn)(GhosttyTerminal, void*, const GhosttyTerminalUnknownSequence*);
```

- **Terminal responses** (DSR `CSI 6n`, DECRQM, DA, XTVERSION, mode-2048
  reports, OSC color queries, kitty keyboard query, paste output) are delivered
  through **`WRITE_PTY`**, synchronously, possibly in several chunks per
  `vt_write`; data is valid only during the call. **[smoke]** `ab ESC[6n` → `ESC[1;3R`;
  `ESC[?2004$p` → `ESC[?2004;2$y`; WASM: same via table-installed callback.
- **Bell** → `BELL`. **Title** (OSC 0/2) → `TITLE_CHANGED`, then `DATA_TITLE`. **[smoke]**
- **Clipboard write** (OSC 52/1337/5522): `GhosttyClipboardWrite {size, location, contents[] {mime, data},
  contents_len (0 = clear), name, granted, can_remember, ctx, reply}`; must call
  `write->reply(write, &(GhosttyClipboardWriteReply){.size, .result, .remember})`
  **before returning** (no reply = denied). Clipboard read likewise via
  `GhosttyClipboardRead {size, location, mimes[], list, name, granted, can_remember, ctx, reply}`
  and `GhosttyClipboardReadReply {size, result, contents[], available[], remember}` — synchronous:
  an async permission prompt must block inside the callback or deny.
- **Render hold** (mode 2026): callback with `held=true` → call
  `ghostty_render_state_update` inside the callback to capture the frame, stop
  updating until `held=false`; embedder must time out (~1 s) by setting
  `GHOSTTY_MODE_SYNC_OUTPUT` false via `OPT_MODE`. Resize/reset end holds.
- There is no generic OSC-8 callback: hyperlinks are cell state (see §5).

## 4. Render state (the renderer's read path)

```c
GhosttyResult ghostty_render_state_new(const GhosttyAllocator*, GhosttyRenderState*);
void          ghostty_render_state_free(GhosttyRenderState);
GhosttyResult ghostty_render_state_update(GhosttyRenderState, GhosttyTerminal);          // = begin + end
GhosttyResult ghostty_render_state_begin_update(GhosttyRenderState, GhosttyTerminal);    // needs terminal lock
GhosttyResult ghostty_render_state_end_update(GhosttyRenderState);                       // no terminal access
GhosttyResult ghostty_render_state_clean(GhosttyRenderState);   // clears global + all row dirty flags
GhosttyResult ghostty_render_state_get(GhosttyRenderState, GhosttyRenderStateData, void* out);
GhosttyResult ghostty_render_state_get_multi(...);
GhosttyResult ghostty_render_state_set(GhosttyRenderState, GHOSTTY_RENDER_STATE_OPTION_DIRTY, const GhosttyRenderStateDirty*);

GhosttyResult ghostty_render_state_row_iterator_new(const GhosttyAllocator*, GhosttyRenderStateRowIterator*);
void          ghostty_render_state_row_iterator_free(GhosttyRenderStateRowIterator);
bool          ghostty_render_state_row_iterator_next(GhosttyRenderStateRowIterator);            // rows y=0.. in order
bool          ghostty_render_state_row_iterator_next_dirty(GhosttyRenderStateRowIterator, uint16_t* out_y);
GhosttyResult ghostty_render_state_row_get(GhosttyRenderStateRowIterator, GhosttyRenderStateRowData, void* out);
GhosttyResult ghostty_render_state_row_set(GhosttyRenderStateRowIterator, GHOSTTY_RENDER_STATE_ROW_OPTION_DIRTY, const bool*);

GhosttyResult ghostty_render_state_row_cells_new(const GhosttyAllocator*, GhosttyRenderStateRowCells*);
void          ghostty_render_state_row_cells_free(GhosttyRenderStateRowCells);
bool          ghostty_render_state_row_cells_next(GhosttyRenderStateRowCells);
GhosttyResult ghostty_render_state_row_cells_select(GhosttyRenderStateRowCells, uint16_t x);
GhosttyResult ghostty_render_state_row_cells_get(GhosttyRenderStateRowCells, GhosttyRenderStateRowCellsData, void* out);
GhosttyResult ghostty_render_state_row_cells_get_multi(...);
```

Usage (**[smoke]**): create the iterator/cells handles once; per frame
`ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &iter)`
(pass a **pointer to the handle**), then per row
`ghostty_render_state_row_get(iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &cells)`.
Row/cell data is invalid after the next `update`.

`GhosttyRenderStateData`: `COLS`,`ROWS` (u16), `DIRTY` (`GhosttyRenderStateDirty`: `FALSE=0, PARTIAL=1, FULL=2`),
`ROW_ITERATOR`, `COLOR_BACKGROUND/FOREGROUND` (`GhosttyColorRgb`), `COLOR_CURSOR` (+`COLOR_CURSOR_HAS_VALUE`),
`COLOR_PALETTE` (`GhosttyColorRgb[256]`), `CURSOR_VISUAL_STYLE` (`BAR=0, BLOCK=1, UNDERLINE=2, BLOCK_HOLLOW=3`),
`CURSOR_VISIBLE`, `CURSOR_BLINKING`, `CURSOR_PASSWORD_INPUT`, `CURSOR_VIEWPORT_HAS_VALUE/X/Y/WIDE_TAIL`,
`CURSOR` → `GhosttyRenderStateCursor {size, viewport_has_value, viewport_x, viewport_y, wide_tail, visible,
blinking, password_input, visual_style}`, `COLORS` → `GhosttyRenderStateColors {size, background, foreground,
cursor, cursor_has_value, palette[256]}`.

`GhosttyRenderStateRowData`: `DIRTY` (bool), `RAW` (`GhosttyRow`), `CELLS`, `SELECTION`
(`GhosttyRenderStateRowSelection {size, start_x, end_x}` inclusive, `NO_VALUE` if none),
`CELLS_RAW` (`GhosttyCellsView {const GhosttyCell* ptr; size_t len;}` whole row in one call — bits
must be decoded via the manifest).

`GhosttyRenderStateRowCellsData`: `RAW` (`GhosttyCell`), `STYLE` (`GhosttyStyle`),
`GRAPHEMES_LEN` (u32, 0 = empty), `GRAPHEMES_BUF` (`uint32_t[]` codepoints),
`BG_COLOR` / `FG_COLOR` (`GhosttyColorRgb`, palette already resolved; **`INVALID_VALUE` means
"default color"**; bold-brightening not applied), `SELECTED` (bool), `HAS_STYLING` (bool),
`GRAPHEMES_UTF8` (`GhosttyBuffer*`, whole cluster as UTF-8; len 0 = empty).

Dirty protocol: `update` only sets dirt; the embedder clears it
(`ghostty_render_state_clean` after a full frame). `next_dirty` returns every
row for `FULL`, only dirty rows for `PARTIAL`, nothing for `FALSE`. **[smoke]**

## 5. Cells, rows, styles, colors, hyperlinks

```c
typedef uint64_t GhosttyCell;  typedef uint64_t GhosttyRow;   // packed; query, don't hardcode bits
GhosttyResult ghostty_cell_get(GhosttyCell, GhosttyCellData, void* out);
GhosttyResult ghostty_row_get(GhosttyRow, GhosttyRowData, void* out);
```

- `GhosttyCellData`: `CODEPOINT` (u32), `CONTENT_TAG` (`CODEPOINT=0, CODEPOINT_GRAPHEME=1,
  BG_COLOR_PALETTE=2, BG_COLOR_RGB=3`), `WIDE` (`GhosttyCellWide`: `NARROW=0, WIDE=1,
  SPACER_TAIL=2` (do not render), `SPACER_HEAD=3` (end-of-line spacer before a wrapped wide char)),
  `HAS_TEXT`, `HAS_STYLING`, `STYLE_ID` (u16), `HAS_HYPERLINK`, `PROTECTED`,
  `SEMANTIC_CONTENT` (`OUTPUT/INPUT/PROMPT`), `COLOR_PALETTE` (u8), `COLOR_RGB`.
  Package width mapping: `NARROW→1, WIDE→2, SPACER_TAIL→0, SPACER_HEAD→0`. **[smoke]** (U+4E16 → WIDE + SPACER_TAIL)
- `GhosttyRowData`: `WRAP`, `WRAP_CONTINUATION`, `GRAPHEME`, `STYLED`, `HYPERLINK`,
  `SEMANTIC_PROMPT`, `KITTY_VIRTUAL_PLACEHOLDER`, `DIRTY`.
- `GhosttyStyle {size_t size; GhosttyStyleColor fg_color, bg_color, underline_color; bool bold, italic,
  faint, blink, inverse, invisible, strikethrough, overline; int underline; }` where
  `GhosttyStyleColor {GhosttyStyleColorTag tag /*NONE=0,PALETTE=1,RGB=2*/; union {uint8 palette; GhosttyColorRgb rgb; uint64 _pad;} value;}`
  and `underline` is `GHOSTTY_SGR_UNDERLINE_*` (none/single/double/curly/dotted/dashed; see `sgr.h`).
  **[smoke]** `ESC[31m` → `fg_color.tag=PALETTE, value.palette=1`.
- `GhosttyColorRgb {uint8_t r, g, b}` (3 bytes). No alpha; package encodes its own RGBA+default flag.
- **OSC 8 hyperlinks**: per-cell flag (`CELL_DATA_HAS_HYPERLINK`, `ROW_DATA_HYPERLINK`); the URI is
  read through a grid ref: `ghostty_grid_ref_hyperlink_uri(const GhosttyGridRef*, uint8_t* buf,
  size_t buf_len, size_t* out_len)` (`OUT_SPACE` → required len; 0 len = no link). Link spans must be
  derived by scanning runs of cells with the flag and comparing URIs. The render-state cell iterator does
  not expose the URI (use `GHOSTTY_POINT_TAG_VIEWPORT` grid refs for visible links). **[smoke]**
- Grid refs (`grid_ref.h`), not for per-frame rendering:
  `ghostty_terminal_grid_ref(t, GhosttyPoint, GhosttyGridRef* out)`;
  `GhosttyPoint {GhosttyPointTag tag /*ACTIVE=0,VIEWPORT=1,SCREEN=2,HISTORY=3*/; union {GhosttyPointCoordinate{uint16 x; uint32 y;} coordinate; ...} value;}`;
  `ghostty_grid_ref_cell/_row/_graphemes/_hyperlink_uri/_style`;
  `ghostty_terminal_point_from_grid_ref(t, ref, tag, GhosttyPointCoordinate* out)`.
  Untracked refs die at the next mutating call; tracked refs
  (`ghostty_terminal_grid_ref_track`, `ghostty_tracked_grid_ref_*`) follow scroll/reflow/prune.

## 6. Viewport / scrollback

```c
void ghostty_terminal_scroll_viewport(GhosttyTerminal, GhosttyTerminalScrollViewport);
// {tag: TOP | BOTTOM | DELTA (value.delta intptr_t, up negative) | ROW (value.row size_t, absolute)}
```

- Position: `DATA_SCROLLBAR` → `{total, offset, len}` rows (poll per frame; no
  change notification). `DATA_VIEWPORT_ACTIVE` = following the bottom.
  `ROW` scroll uses the same row space as `offset`. **[smoke]**
- **Scrollback limits: both lines and bytes.** `OPT_SCROLLBACK_MAX_LINES` and
  `OPT_SCROLLBACK_MAX_BYTES` (`size_t*`), first limit reached wins, both are
  **page-granular estimates** (a page is ~400 KB; the line limit is usually
  exceeded by dozens–~100 lines). Lowering prunes immediately; bytes=0 disables
  scrollback. **[smoke]** round-trips both.
  → A byte budget *is* enforceable to within one page per screen (measured:
  RSS growth 0.96–0.99 × budget for plain text, 1.2 × with grapheme-heavy
  pages; README "History budgets"). The line limit always keeps at least one
  page of rows (0 still keeps ~a page: use bytes = 0 for "no scrollback").
  The custom allocator (§1) does not see page memory.
- Scrolling is purely local state: nothing is written to the pty.

## 7. Modes

`GhosttyMode` is a packed `uint16_t` (`value | ansi<<15`), built with
`ghostty_mode_new(value, ansi)`; constants `GHOSTTY_MODE_*`, e.g.
`ALT_SCREEN_SAVE`(1049), `ALT_SCREEN`(1047), `ALT_SCREEN_LEGACY`(47), `BRACKETED_PASTE`(2004),
`X10_MOUSE`(9), `NORMAL_MOUSE`(1000), `BUTTON_MOUSE`(1002), `ANY_MOUSE`(1003), `SGR_MOUSE`(1006),
`UTF8_MOUSE`(1005), `URXVT_MOUSE`(1015), `SGR_PIXELS_MOUSE`(1016), `FOCUS_EVENT`(1004), `ALT_SCROLL`(1007),
`DECCKM`(1), `KEYPAD_KEYS`(66), `CURSOR_VISIBLE`(25), `CURSOR_BLINKING`(12), `WRAPAROUND`(7),
`SYNC_OUTPUT`(2026), `GRAPHEME_CLUSTER`(2027), `IN_BAND_RESIZE`(2048), `PASTE_EVENTS`(5522), ...

- Read: `GhosttyTerminalModeConfig cfg = {.mode = GHOSTTY_MODE_BRACKETED_PASTE};
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_MODE, &cfg); cfg.value`.
- Alt screen: `DATA_ACTIVE_SCREEN == GHOSTTY_TERMINAL_SCREEN_ALTERNATE` (covers 47/1047/1049).
- Mouse tracking: `DATA_MOUSE_TRACKING` (bool, any tracking mode). **[smoke]** all three.

## 8. Input encoders

Key (`key/event.h`, `key/encoder.h`):
```c
ghostty_key_event_new(alloc, &ev); ghostty_key_event_free(ev);
ghostty_key_event_set_action(ev, GHOSTTY_KEY_ACTION_{RELEASE=0,PRESS=1,REPEAT=2});
ghostty_key_event_set_key(ev, GhosttyKey /* W3C physical code: GHOSTTY_KEY_A, _ENTER, _ARROW_UP, _F1, _NUMPAD_0 ... */);
ghostty_key_event_set_mods(ev, GhosttyMods /* SHIFT=1<<0 CTRL=1<<1 ALT=1<<2 SUPER=1<<3 CAPS_LOCK=1<<4 NUM_LOCK=1<<5, *_SIDE bits 6..9 = right-hand */);
ghostty_key_event_set_consumed_mods(ev, mods); ghostty_key_event_set_composing(ev, bool);
ghostty_key_event_set_utf8(ev, const char*, len);   // layout text, unmodified by Ctrl/Meta; NEVER C0/DEL/PUA (pass NULL)
ghostty_key_event_set_unshifted_codepoint(ev, uint32_t);
ghostty_key_encoder_new(alloc, &enc); ghostty_key_encoder_free(enc);
ghostty_key_encoder_setopt(enc, GhosttyKeyEncoderOption, const void*);   // CURSOR_KEY_APPLICATION, KEYPAD_KEY_APPLICATION,
    // IGNORE_KEYPAD_WITH_NUMLOCK, ALT_ESC_PREFIX, MODIFY_OTHER_KEYS_STATE_2, KITTY_FLAGS(u8), MACOS_OPTION_AS_ALT, BACKARROW_KEY_MODE
ghostty_key_encoder_setopt_from_terminal(enc, terminal);   // copy DECCKM/keypad/kitty flags/etc. from terminal state
GhosttyResult ghostty_key_encoder_encode(enc, ev, char* out, size_t cap, size_t* out_len); // NULL/0 → OUT_OF_SPACE + size; len 0 = no output
```
**[smoke]** Ctrl+C → `0x03`; ArrowUp under DECCKM → `ESC O A`. Call
`setopt_from_terminal` before encoding whenever terminal modes may have changed.

Mouse (`mouse/event.h`, `mouse/encoder.h`):
```c
ghostty_mouse_event_new/free; _set_action(PRESS=0,RELEASE=1,MOTION=2); _set_button(LEFT=1,RIGHT=2,MIDDLE=3,FOUR..ELEVEN; xterm convention: 4/5 wheel up/down, 6/7 left/right — SGR encodes them as 64/65/66/67, tested in `terminal_bridge_test.c`);
_clear_button (motion without button); _set_mods(GhosttyMods); _set_position(GhosttyMousePosition{float x, y} /* surface PIXELS */);
ghostty_mouse_encoder_new/free/reset;
ghostty_mouse_encoder_setopt(enc, OPT_EVENT(GhosttyMouseTrackingMode NONE/X10/NORMAL/BUTTON/ANY) | OPT_FORMAT(X10/UTF8/SGR/URXVT/SGR_PIXELS)
    | OPT_SIZE(GhosttyMouseEncoderSize{size, screen_width, screen_height, cell_width, cell_height, padding_top/bottom/right/left})
    | OPT_ANY_BUTTON_PRESSED(bool*) | OPT_TRACK_LAST_CELL(bool*), value);
ghostty_mouse_encoder_setopt_from_terminal(enc, terminal);   // tracking mode + format from terminal
ghostty_mouse_encoder_encode(enc, ev, char* out, size_t cap, size_t* out_len);   // len 0 when tracking is off / event filtered
```
**[smoke]** 1000+1006, 10x20 px cells, press at (55,45) px → `ESC[<0;6;3M`. The
encoder always needs `OPT_SIZE` (pixel → cell mapping).

Focus: `ghostty_focus_encode(GHOSTTY_FOCUS_GAINED|LOST, buf, len, &written)` → `ESC[I` / `ESC[O`
(embedder checks `GHOSTTY_MODE_FOCUS_EVENT`). **[smoke]**

Paste: `ghostty_terminal_paste(t, const GhosttyPaste*, bool* out_written)` with
`GhosttyPaste {size, location, source (CLIPBOARD|TEXT), const GhosttyString* mimes, mimes_len,
GhosttyMimeReader reader {bool (*read)(void* ud, GhosttyString mime, GhosttyWriter w); void* ud}, allow_unsafe}`
— applies bracketed paste / newline conversion / unsafe filtering from terminal
state and streams output through **`WRITE_PTY`** (required; `INVALID_VALUE`
without it). Returns `REJECTED` for injection-prone text unless `allow_unsafe`.
Terminal-free helpers: `ghostty_paste_is_safe(data,len)`, `ghostty_paste_encode(data, len, bracketed, buf, cap, &written)`. **[smoke]**
Size report encoding: `ghostty_size_report_encode(style, GhosttySizeReportSize, buf, len, &written)`.

## 9. Selection and text extraction

```c
typedef struct { size_t size; GhosttyGridRef start; GhosttyGridRef end; bool rectangle; } GhosttySelection;
ghostty_terminal_select_all(t, GhosttySelection* out);
ghostty_terminal_select_word(t, const GhosttyTerminalSelectWordOptions* {size, ref, boundary_codepoints, len}, out);
ghostty_terminal_select_word_between(t, opts, out); ghostty_terminal_select_line(t, opts{size, ref, whitespace, len, semantic_prompt_boundary}, out);
ghostty_terminal_select_output(t, GhosttyGridRef, out);        // OSC 133 command output
ghostty_terminal_selection_adjust(t, GhosttySelection*, GhosttySelectionAdjust);  // LEFT/RIGHT/UP/DOWN/HOME/END/PAGE_*/..._OF_LINE
ghostty_terminal_selection_order / _ordered / _contains(t, sel, GhosttyPoint, bool*) / _equal
ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);   // make it the active (tracked) selection → render state SELECTED/row SELECTION
ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SELECTION, &sel);  // untracked snapshot of the active one
ghostty_terminal_selection_format_alloc(t, alloc, GhosttyTerminalSelectionFormatOptions{size, emit, unwrap, trim, const GhosttySelection* selection /*NULL = active*/},
                                        uint8_t** out_ptr, size_t* out_len);   // free with ghostty_free; also _format_buf
```
Copy semantics matching Ghostty: `emit=GHOSTTY_FORMATTER_FORMAT_PLAIN, unwrap=true, trim=true`.
**[smoke]** select_all → `"ab\nlink plain\n世!"`.

Gesture state machine (click/double/triple/drag/autoscroll):
`ghostty_selection_gesture_new/free/reset/get`, events via
`ghostty_selection_gesture_event_new(alloc, &ev, PRESS|RELEASE|DRAG|AUTOSCROLL_TICK|DEEP_PRESS)` +
`_event_set(ev, OPT_REF|POSITION|REPEAT_DISTANCE|TIME_NS|REPEAT_INTERVAL_NS|WORD_BOUNDARY_CODEPOINTS|BEHAVIORS|RECTANGLE|GEOMETRY|VIEWPORT, value)`
and `ghostty_selection_gesture_event(gesture, t, ev, GhosttySelection* out)`.

Formatter (whole screen, e.g. dumps/tests): `ghostty_formatter_terminal_new(alloc, &f, t,
GhosttyFormatterTerminalOptions{size, emit PLAIN|VT|HTML, unwrap, trim, extra{...}, selection})`,
`ghostty_formatter_format_alloc/_buf/format(writer)`, `ghostty_formatter_free`.
Search: `ghostty_search_new(alloc, &s, t)`, `_set/_tick/_feed/_run/_get/_free`.

## 10. Global system hooks (`sys.h`)

`ghostty_sys_set(GHOSTTY_SYS_OPT_USERDATA | DECODE_PNG | LOG | RANDOM_SECURE, value)` — process-wide:
log sink (`GhosttySysLogFn(ud, level, scope, len, msg, len)`, `ghostty_sys_log_stderr` provided),
PNG decoder for kitty graphics, secure RNG (required on wasm32-freestanding for
kitty paste-event passwords; otherwise `IO_ERROR`).

## 11. WASM specifics (`-Dtarget=wasm32-freestanding`, `bin/ghostty-vt.wasm`)

- Build: `zig build -Demit-lib-vt=true -Dtarget=wasm32-freestanding -Doptimize=ReleaseSmall`
  (upstream adds `+simd128` automatically). It is a reactor module: no entry point,
  `rdynamic` exports of **every `ghostty_*` C function**, plus `memory` and
  `__indirect_function_table`. Stack is 128 KiB preallocated in linear memory.
  **[smoke]** export surface check.
- Imports: **none** (the ReleaseSmall module imports nothing; upstream's example
  host passes an unused `env.log`). Exports: 187 `ghostty_*` functions + `memory`
  + `__indirect_function_table` (814 KB ReleaseSmall at this pin).
- Pointers and `size_t` are 32-bit; by-value structs (e.g.
  `GhosttyTerminalSelectionFormatOptions`, `GhosttyFormatterTerminalOptions`,
  `GhosttyPoint`, `GhosttyTerminalScrollViewport`) are passed **by pointer** per
  the wasm C ABI. Read all sizes/offsets/enums from `ghostty_type_json()`.
- Memory ownership: host scratch memory via `ghostty_wasm_alloc(len)` /
  `ghostty_wasm_free(ptr, len)` (same length; aligned to `abi.max_alignment`;
  len 0 → NULL); handle out-params via `ghostty_wasm_alloc_opaque()` +
  `ghostty_wasm_take_opaque(slot)` + `ghostty_wasm_free_opaque(slot)`; buffers
  from `*_alloc` APIs → `ghostty_free(0, ptr, len)`; handles → their `*_free`.
- Memory growth: any export may grow memory; numeric pointers stay valid but
  `ArrayBuffer`/typed-array views must be re-acquired after every call.
  **[smoke]** 32 MiB alloc grows memory; old handles keep working.
- **Callbacks**: the function table is exported and patched to be growable
  (`src/build/wasm_patch_growable_table.zig`). A JS function cannot be stored
  directly (no `WebAssembly.Function` in stable engines), so the host
  instantiates a tiny trampoline module that imports the JS function and exports
  a wasm function of the right i32 signature, then `table.grow(1)` +
  `table.set(idx, trampoline)` and passes `idx` as the function pointer to
  `ghostty_terminal_set`. **[smoke]** `write_pty` (4 × i32) and `bell` (2 × i32)
  delivered in Node 24 and headless Chrome 148.

## 12. Build flags (supported, from `build.zig` / `src/build/Config.zig`)

- `-Demit-lib-vt=true` — libghostty-vt-only build (no app, docs, xcframework).
- `-Dtarget=<triple>`, `-Dcpu=<model>`, `-Doptimize=Debug|ReleaseSafe|ReleaseFast|ReleaseSmall`.
- `-Dsimd=<bool>` (default true except wasm/freestanding; false = no libc/C++ needed, slower).
- `-Dvt-features=<list>` — e.g. `-all,+render-state` (default: all on). Features
  (`src/terminal/build_options.zig`): `snapshot`, `formatter`, `selection`, `search`,
  `render_state`, `input_encode`, `color`, `grid_introspection`, `glyph_protocol`,
  `kitty_graphics`. We build with all features; trimming (e.g. `-kitty-graphics`) is a later size optimisation.
- `-Dlib-version-string=<semver>` (otherwise derived from git).
- `-Dstrip=<bool>` (default true for ReleaseFast/ReleaseSmall).
- `-Demit-xcframework` (macOS host only) builds an Apple universal static xcframework.
- Windows: upstream defaults to the MSVC ABI when none is given; cross-building
  MSVC from Linux is unsupported upstream, so we pin `x86_64-windows-gnu` (CI-tested upstream).
- Android: needs the NDK (`ANDROID_NDK_HOME`); links with 16 KB max page size.
- Static archive is a fat archive (libghostty-vt + vendored highway/simdutf C++
  SIMD code + compiler_rt/ubsan_rt); consumers link it plus the C++ runtime.
- Shared library exports: the Apple-ld dylib path restricts exports to `_ghostty_*`,
  but the Zig-linked ELF `.so` (linux-x64) also exports vendored `wuffs_*`,
  `hwy_*` and `__cxa_pure_virtual` symbols (204 `ghostty_*` symbols total). The
  supermux JNI library should link the **static** archive and export only its
  own `st_*`/`Java_*` symbols (version script / `-fvisibility=hidden`).
- linux-x64 `.so` needs glibc >= 2.27 symbols only (`expf`/`logf`); pinned target glibc 2.28.
