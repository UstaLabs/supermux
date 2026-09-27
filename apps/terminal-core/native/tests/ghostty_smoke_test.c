/*
 * Native feasibility smoke test for the pinned libghostty-vt C API.
 *
 * Uses ONLY the real pinned headers (include/ghostty/vt.h from the pinned
 * Ghostty commit recorded in native/upstream.lock.json). There is no wrapper
 * or stub here: every check exercises upstream code.
 *
 * Note: the pinned API has no `GhosttyTerminalOptions` struct. Terminals are
 * created with ghostty_terminal_new(allocator, &out, cols, rows) and then
 * configured with ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_*, ptr).
 *
 * Exit code 0 = all checks passed. Each check prints "ok" / "FAIL" lines.
 */
#if !defined(_WIN32) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200112L /* posix_memalign */
#endif
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#include <malloc.h>
#endif

#include <ghostty/vt.h>

static int g_failures = 0;
static int g_checks = 0;

/* Both macros evaluate `cond` exactly once (conditions may have side effects,
 * e.g. advancing an iterator). */
#define CHECK(cond, ...) check_impl((cond) ? 1 : 0, __FILE__, __LINE__, __VA_ARGS__)

#define REQUIRE(cond, ...)                                        \
  do {                                                            \
    if (!CHECK(cond, __VA_ARGS__)) {                              \
      printf("aborting: required check failed\n");                \
      return 1;                                                   \
    }                                                             \
  } while (0)

#include <stdarg.h>
static int check_impl(int ok, const char *file, int line, const char *fmt, ...) {
  g_checks++;
  if (ok) {
    printf("ok   - ");
  } else {
    g_failures++;
    printf("FAIL - (%s:%d) ", file, line);
  }
  va_list ap;
  va_start(ap, fmt);
  vprintf(fmt, ap);
  va_end(ap);
  printf("\n");
  return ok;
}

static void feed(GhosttyTerminal t, const char *s) {
  ghostty_terminal_vt_write(t, (const uint8_t *)s, strlen(s));
}

static void feed_n(GhosttyTerminal t, const char *s, size_t n) {
  ghostty_terminal_vt_write(t, (const uint8_t *)s, n);
}

/* ------------------------------------------------------------------ */
/* Counting allocator: proves the allocator vtable is honoured and that */
/* ghostty_terminal_free releases everything ghostty_terminal_new took. */
/* ------------------------------------------------------------------ */

typedef struct {
  long long live_bytes;
  long long live_allocs;
  long long total_allocs;
} CountingCtx;

static void *counting_alloc(void *ctx, size_t len, uint8_t alignment_log2,
                            uintptr_t ret_addr) {
  (void)ret_addr;
  CountingCtx *c = ctx;
  size_t align = (size_t)1 << alignment_log2;
  if (align < sizeof(void *)) align = sizeof(void *);
  size_t rounded = (len + align - 1) / align * align;
  if (rounded == 0) rounded = align;
#if defined(_WIN32)
  void *p = _aligned_malloc(rounded, align);
#else
  void *p = NULL;
  if (posix_memalign(&p, align, rounded) != 0) p = NULL;
#endif
  if (p) {
    c->live_bytes += (long long)len;
    c->live_allocs++;
    c->total_allocs++;
  }
  return p;
}

static bool counting_resize(void *ctx, void *memory, size_t memory_len,
                            uint8_t alignment_log2, size_t new_len,
                            uintptr_t ret_addr) {
  (void)alignment_log2;
  (void)ret_addr;
  (void)memory;
  /* Only allow shrinking in place; growth makes the caller alloc+copy. */
  if (new_len <= memory_len) {
    CountingCtx *c = ctx;
    c->live_bytes += (long long)new_len - (long long)memory_len;
    return true;
  }
  return false;
}

static void *counting_remap(void *ctx, void *memory, size_t memory_len,
                            uint8_t alignment_log2, size_t new_len,
                            uintptr_t ret_addr) {
  if (counting_resize(ctx, memory, memory_len, alignment_log2, new_len,
                      ret_addr))
    return memory;
  return NULL;
}

static void counting_free(void *ctx, void *memory, size_t memory_len,
                          uint8_t alignment_log2, uintptr_t ret_addr) {
  (void)alignment_log2;
  (void)ret_addr;
  CountingCtx *c = ctx;
  c->live_bytes -= (long long)memory_len;
  c->live_allocs--;
#if defined(_WIN32)
  _aligned_free(memory);
#else
  free(memory);
#endif
}

static const GhosttyAllocatorVtable counting_vtable = {
    .alloc = counting_alloc,
    .resize = counting_resize,
    .remap = counting_remap,
    .free = counting_free,
};

/* ------------------------------------------------------------------ */
/* Effect callbacks                                                     */
/* ------------------------------------------------------------------ */

typedef struct {
  char pty[256];
  size_t pty_len;
  int bells;
  int titles;
} Effects;

static void on_write_pty(GhosttyTerminal t, void *ud, const uint8_t *data,
                         size_t len) {
  (void)t;
  Effects *e = ud;
  if (e->pty_len + len < sizeof(e->pty)) {
    memcpy(e->pty + e->pty_len, data, len);
    e->pty_len += len;
    e->pty[e->pty_len] = 0;
  }
}

static void on_bell(GhosttyTerminal t, void *ud) {
  (void)t;
  ((Effects *)ud)->bells++;
}

static void on_title(GhosttyTerminal t, void *ud) {
  (void)t;
  ((Effects *)ud)->titles++;
}

/* ------------------------------------------------------------------ */
/* Helpers                                                              */
/* ------------------------------------------------------------------ */

static bool mode_value(GhosttyTerminal t, GhosttyMode mode) {
  GhosttyTerminalModeConfig cfg = {.mode = mode, .value = false};
  if (ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_MODE, &cfg) !=
      GHOSTTY_SUCCESS)
    return false;
  return cfg.value;
}

static uint16_t term_u16(GhosttyTerminal t, GhosttyTerminalData d) {
  uint16_t v = 0;
  ghostty_terminal_get(t, d, &v);
  return v;
}

/* Read cell (x, y) in active coordinates via a grid ref. */
static GhosttyResult cell_at(GhosttyTerminal t, uint16_t x, uint32_t y,
                             GhosttyGridRef *out_ref, GhosttyCell *out_cell) {
  GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_ACTIVE,
                     .value = {.coordinate = {.x = x, .y = y}}};
  *out_ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  GhosttyResult r = ghostty_terminal_grid_ref(t, pt, out_ref);
  if (r != GHOSTTY_SUCCESS) return r;
  return ghostty_grid_ref_cell(out_ref, out_cell);
}

/* ------------------------------------------------------------------ */
/* Tests                                                                */
/* ------------------------------------------------------------------ */

/* The plan's core fixture: ESC[31mredESC[0m -> three red cells, via the */
/* render-state API that the renderer will actually use.                 */
static int test_red_cells(void) {
  printf("# red-cell fixture (render state)\n");
  GhosttyTerminal t = NULL;
  REQUIRE(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS && t,
          "ghostty_terminal_new 80x24");

  feed(t, "\x1b[31mred\x1b[0m");

  GhosttyRenderState rs = NULL;
  REQUIRE(ghostty_render_state_new(NULL, &rs) == GHOSTTY_SUCCESS,
          "ghostty_render_state_new");
  REQUIRE(ghostty_render_state_update(rs, t) == GHOSTTY_SUCCESS,
          "ghostty_render_state_update");

  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  CHECK(ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_COLORS,
                                 &colors) == GHOSTTY_SUCCESS,
        "render state colors");

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  CHECK(cols == 80 && rows == 24, "render state size %ux%u", cols, rows);

  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  CHECK(dirty != GHOSTTY_RENDER_STATE_DIRTY_FALSE, "first frame is dirty (%d)",
        (int)dirty);

  GhosttyRenderStateRowIterator it = NULL;
  GhosttyRenderStateRowCells cells = NULL;
  REQUIRE(ghostty_render_state_row_iterator_new(NULL, &it) == GHOSTTY_SUCCESS,
          "row iterator new");
  REQUIRE(ghostty_render_state_row_cells_new(NULL, &cells) == GHOSTTY_SUCCESS,
          "row cells new");
  REQUIRE(ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                                   &it) == GHOSTTY_SUCCESS,
          "populate row iterator");
  REQUIRE(ghostty_render_state_row_iterator_next(it), "first row present");
  REQUIRE(ghostty_render_state_row_get(it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                                       &cells) == GHOSTTY_SUCCESS,
          "row cells for row 0");

  const char expect[] = "red";
  int red_cells = 0;
  for (uint16_t x = 0; x < 4; x++) {
    REQUIRE(ghostty_render_state_row_cells_select(cells, x) == GHOSTTY_SUCCESS,
            "select cell %u", x);
    uint8_t utf8[16];
    GhosttyBuffer buf = {.ptr = utf8, .cap = sizeof(utf8), .len = 0};
    GhosttyResult r = ghostty_render_state_row_cells_get(
        cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &buf);
    GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
    ghostty_render_state_row_cells_get(
        cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
    GhosttyColorRgb fg = {0, 0, 0};
    GhosttyResult fr = ghostty_render_state_row_cells_get(
        cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &fg);
    GhosttyCell raw = 0;
    ghostty_render_state_row_cells_get(
        cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_MAX_VALUE;
    ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);

    if (x < 3) {
      bool text_ok = r == GHOSTTY_SUCCESS && buf.len == 1 &&
                     utf8[0] == (uint8_t)expect[x];
      bool style_ok = style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE &&
                      style.fg_color.value.palette == 1;
      bool color_ok = fr == GHOSTTY_SUCCESS && fg.r == colors.palette[1].r &&
                      fg.g == colors.palette[1].g &&
                      fg.b == colors.palette[1].b;
      CHECK(text_ok && style_ok && color_ok && wide == GHOSTTY_CELL_WIDE_NARROW,
            "cell %u = '%c' fg=palette[1] rgb(%u,%u,%u) narrow "
            "[text=%d style=%d(tag %d idx %u) color=%d(res %d, want %u,%u,%u)]",
            x, expect[x], fg.r, fg.g, fg.b, text_ok, style_ok,
            (int)style.fg_color.tag, style.fg_color.value.palette, color_ok,
            (int)fr, colors.palette[1].r, colors.palette[1].g,
            colors.palette[1].b);
      if (text_ok && style_ok && color_ok) red_cells++;
    } else {
      CHECK(r == GHOSTTY_SUCCESS && buf.len == 0 &&
                style.fg_color.tag == GHOSTTY_STYLE_COLOR_NONE &&
                fr != GHOSTTY_SUCCESS,
            "cell 3 empty and unstyled after SGR 0");
    }
  }
  CHECK(red_cells == 3, "exactly three red cells (%d)", red_cells);

  GhosttyRenderStateCursor cur = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  CHECK(ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cur) ==
                GHOSTTY_SUCCESS &&
            cur.viewport_has_value && cur.viewport_x == 3 &&
            cur.viewport_y == 0 && cur.visible,
        "cursor at (3,0) visible");

  CHECK(ghostty_render_state_clean(rs) == GHOSTTY_SUCCESS,
        "render state clean");
  ghostty_render_state_update(rs, t);
  ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  CHECK(dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE,
        "no dirt after clean + idle update");

  /* resize, content survives */
  CHECK(ghostty_terminal_resize(t, 40, 10, 8, 16) == GHOSTTY_SUCCESS,
        "resize to 40x10");
  CHECK(term_u16(t, GHOSTTY_TERMINAL_DATA_COLS) == 40 &&
            term_u16(t, GHOSTTY_TERMINAL_DATA_ROWS) == 10,
        "terminal reports 40x10");
  uint32_t wpx = 0;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_WIDTH_PX, &wpx);
  CHECK(wpx == 320, "width_px = cols * cell_width (%u)", wpx);
  ghostty_render_state_update(rs, t);
  ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  CHECK(cols == 40 && rows == 10, "render state follows resize %ux%u", cols,
        rows);
  GhosttyGridRef ref;
  GhosttyCell c0 = 0;
  uint32_t cp = 0;
  cell_at(t, 1, 0, &ref, &c0);
  ghostty_cell_get(c0, GHOSTTY_CELL_DATA_CODEPOINT, &cp);
  CHECK(cp == 'e', "content preserved after resize");

  ghostty_render_state_row_cells_free(cells);
  ghostty_render_state_row_iterator_free(it);
  ghostty_render_state_free(rs);
  ghostty_terminal_free(t);
  return 0;
}

static int test_effects_and_modes(void) {
  printf("# effects, modes, hyperlinks, wide chars\n");
  GhosttyTerminal t = NULL;
  REQUIRE(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS,
          "terminal new");
  Effects fx;
  memset(&fx, 0, sizeof(fx));
  CHECK(ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_USERDATA, &fx) ==
            GHOSTTY_SUCCESS,
        "set userdata");
  CHECK(ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
                             (const void *)on_write_pty) == GHOSTTY_SUCCESS,
        "set write_pty");
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_BELL, (const void *)on_bell);
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
                       (const void *)on_title);

  /* terminal responses: DSR cursor position report */
  feed(t, "ab\x1b[6n");
  CHECK(strcmp(fx.pty, "\x1b[1;3R") == 0, "CSI 6n -> write_pty \\e[1;3R (%zu bytes)",
        fx.pty_len);
  fx.pty_len = 0;
  fx.pty[0] = 0;
  /* DECRQM for bracketed paste (reset) */
  feed(t, "\x1b[?2004$p");
  CHECK(strcmp(fx.pty, "\x1b[?2004;2$y") == 0, "DECRQM 2004 -> reset report");

  feed(t, "\x07");
  CHECK(fx.bells == 1, "bell callback");
  feed(t, "\x1b]2;supermux\x07");
  GhosttyString title = {0};
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_TITLE, &title);
  CHECK(fx.titles == 1 && title.len == 8 &&
            memcmp(title.ptr, "supermux", 8) == 0,
        "OSC 2 title callback + DATA_TITLE");

  /* modes */
  feed(t, "\x1b[?2004h\x1b[?1000h\x1b[?1006h");
  CHECK(mode_value(t, GHOSTTY_MODE_BRACKETED_PASTE), "bracketed paste on");
  bool tracking = false;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
  CHECK(tracking && mode_value(t, GHOSTTY_MODE_SGR_MOUSE),
        "mouse tracking + SGR on");
  feed(t, "\x1b[?1049h");
  GhosttyTerminalScreen scr = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &scr);
  CHECK(scr == GHOSTTY_TERMINAL_SCREEN_ALTERNATE, "alt screen active");
  feed(t, "\x1b[?1049l");
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &scr);
  CHECK(scr == GHOSTTY_TERMINAL_SCREEN_PRIMARY, "back to primary screen");

  /* key encoder follows terminal modes */
  GhosttyKeyEncoder kenc = NULL;
  GhosttyKeyEvent kev = NULL;
  REQUIRE(ghostty_key_encoder_new(NULL, &kenc) == GHOSTTY_SUCCESS &&
              ghostty_key_event_new(NULL, &kev) == GHOSTTY_SUCCESS,
          "key encoder/event new");
  char out[64];
  size_t n = 0;
  ghostty_key_event_set_action(kev, GHOSTTY_KEY_ACTION_PRESS);
  ghostty_key_event_set_key(kev, GHOSTTY_KEY_C);
  ghostty_key_event_set_mods(kev, GHOSTTY_MODS_CTRL);
  ghostty_key_event_set_utf8(kev, "c", 1);
  ghostty_key_event_set_unshifted_codepoint(kev, 'c');
  ghostty_key_encoder_setopt_from_terminal(kenc, t);
  CHECK(ghostty_key_encoder_encode(kenc, kev, out, sizeof(out), &n) ==
                GHOSTTY_SUCCESS &&
            n == 1 && out[0] == 0x03,
        "Ctrl+C -> 0x03");
  feed(t, "\x1b[?1h"); /* DECCKM */
  ghostty_key_encoder_setopt_from_terminal(kenc, t);
  ghostty_key_event_set_key(kev, GHOSTTY_KEY_ARROW_UP);
  ghostty_key_event_set_mods(kev, 0);
  ghostty_key_event_set_utf8(kev, NULL, 0);
  ghostty_key_event_set_unshifted_codepoint(kev, 0);
  n = 0;
  CHECK(ghostty_key_encoder_encode(kenc, kev, out, sizeof(out), &n) ==
                GHOSTTY_SUCCESS &&
            n == 3 && memcmp(out, "\x1bOA", 3) == 0,
        "ArrowUp in DECCKM -> ESC O A");
  ghostty_key_event_free(kev);
  ghostty_key_encoder_free(kenc);

  /* mouse encoder follows terminal modes (1000 + 1006 SGR) */
  GhosttyMouseEncoder menc = NULL;
  GhosttyMouseEvent mev = NULL;
  REQUIRE(ghostty_mouse_encoder_new(NULL, &menc) == GHOSTTY_SUCCESS &&
              ghostty_mouse_event_new(NULL, &mev) == GHOSTTY_SUCCESS,
          "mouse encoder/event new");
  ghostty_mouse_encoder_setopt_from_terminal(menc, t);
  GhosttyMouseEncoderSize msz = {.size = sizeof(GhosttyMouseEncoderSize),
                                 .screen_width = 800,
                                 .screen_height = 480,
                                 .cell_width = 10,
                                 .cell_height = 20};
  ghostty_mouse_encoder_setopt(menc, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &msz);
  ghostty_mouse_event_set_action(mev, GHOSTTY_MOUSE_ACTION_PRESS);
  ghostty_mouse_event_set_button(mev, GHOSTTY_MOUSE_BUTTON_LEFT);
  ghostty_mouse_event_set_position(mev, (GhosttyMousePosition){.x = 55.0f, .y = 45.0f});
  n = 0;
  GhosttyResult mr = ghostty_mouse_encoder_encode(menc, mev, out, sizeof(out), &n);
  out[n < sizeof(out) ? n : sizeof(out) - 1] = 0;
  CHECK(mr == GHOSTTY_SUCCESS && strcmp(out, "\x1b[<0;6;3M") == 0,
        "left press at cell (5,2) -> SGR \\e[<0;6;3M");
  ghostty_mouse_event_free(mev);
  ghostty_mouse_encoder_free(menc);

  /* focus encoding */
  n = 0;
  CHECK(ghostty_focus_encode(GHOSTTY_FOCUS_GAINED, out, sizeof(out), &n) ==
                GHOSTTY_SUCCESS &&
            n == 3 && memcmp(out, "\x1b[I", 3) == 0,
        "focus gained -> ESC [ I");

  /* terminal-free paste encoding (bracketed) */
  n = 0;
  CHECK(ghostty_paste_encode((char *)"hi", 2, true, out, sizeof(out), &n) ==
                GHOSTTY_SUCCESS &&
            n == 14 && memcmp(out, "\x1b[200~hi\x1b[201~", 14) == 0,
        "paste_encode bracketed");

  /* OSC 8 hyperlink */
  feed(t, "\r\n\x1b]8;;https://example.com/x\x1b\\link\x1b]8;;\x1b\\ plain");
  uint16_t cy = term_u16(t, GHOSTTY_TERMINAL_DATA_CURSOR_Y);
  GhosttyGridRef ref;
  GhosttyCell cell = 0;
  bool has_link = false;
  cell_at(t, 0, cy, &ref, &cell);
  ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &has_link);
  char uri[64];
  size_t ulen = 0;
  GhosttyResult ur =
      ghostty_grid_ref_hyperlink_uri(&ref, (uint8_t *)uri, sizeof(uri), &ulen);
  CHECK(has_link && ur == GHOSTTY_SUCCESS && ulen == 21 &&
            memcmp(uri, "https://example.com/x", 21) == 0,
        "OSC 8 hyperlink URI via grid ref");
  cell_at(t, 5, cy, &ref, &cell);
  has_link = true;
  ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &has_link);
  CHECK(!has_link, "text after OSC 8 close has no link");

  /* wide character: leading WIDE + SPACER_TAIL */
  feed(t, "\r\n\xe4\xb8\x96!"); /* U+4E16 */
  cy = term_u16(t, GHOSTTY_TERMINAL_DATA_CURSOR_Y);
  GhosttyCellWide w0 = 0, w1 = 0;
  cell_at(t, 0, cy, &ref, &cell);
  ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &w0);
  cell_at(t, 1, cy, &ref, &cell);
  ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &w1);
  CHECK(w0 == GHOSTTY_CELL_WIDE_WIDE && w1 == GHOSTTY_CELL_WIDE_SPACER_TAIL &&
            term_u16(t, GHOSTTY_TERMINAL_DATA_CURSOR_X) == 3,
        "wide char occupies two cells");

  /* selection -> text */
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  CHECK(ghostty_terminal_select_all(t, &sel) == GHOSTTY_SUCCESS,
        "select_all");
  GhosttyTerminalSelectionFormatOptions fo =
      GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
  fo.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  fo.unwrap = true;
  fo.trim = true;
  fo.selection = &sel;
  uint8_t *txt = NULL;
  size_t txt_len = 0;
  GhosttyResult sr =
      ghostty_terminal_selection_format_alloc(t, NULL, fo, &txt, &txt_len);
  const char *want = "ab\nlink plain\n\xe4\xb8\x96!";
  CHECK(sr == GHOSTTY_SUCCESS && txt_len == strlen(want) &&
            memcmp(txt, want, txt_len) == 0,
        "selection_format_alloc plain text (%zu bytes)", txt_len);
  ghostty_free(NULL, txt, txt_len);

  /* scrollback limits (lines + bytes are both first-class options) */
  size_t max_bytes = 4u * 1024u * 1024u, max_lines = 1000, got = 0;
  CHECK(ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES,
                             &max_bytes) == GHOSTTY_SUCCESS &&
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBACK_MAX_BYTES,
                                 &got) == GHOSTTY_SUCCESS &&
            got == max_bytes,
        "scrollback max bytes round-trips");
  CHECK(ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES,
                             &max_lines) == GHOSTTY_SUCCESS &&
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBACK_MAX_LINES,
                                 &got) == GHOSTTY_SUCCESS &&
            got == max_lines,
        "scrollback max lines round-trips");
  for (int i = 0; i < 200; i++) feed(t, "line of scrollback text\r\n");
  size_t sb = 0;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &sb);
  CHECK(sb > 0, "scrollback rows accumulate (%zu)", sb);
  GhosttyTerminalScrollViewport sv = {.tag = GHOSTTY_SCROLL_VIEWPORT_TOP};
  ghostty_terminal_scroll_viewport(t, sv);
  bool at_active = true;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE, &at_active);
  GhosttyTerminalScrollbar bar = {0};
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);
  CHECK(!at_active && bar.offset == 0 && bar.len == 24 && bar.total > 24,
        "scroll viewport to top (total=%llu len=%llu)",
        (unsigned long long)bar.total, (unsigned long long)bar.len);

  /* reset */
  ghostty_terminal_reset(t);
  CHECK(term_u16(t, GHOSTTY_TERMINAL_DATA_CURSOR_X) == 0 &&
            term_u16(t, GHOSTTY_TERMINAL_DATA_CURSOR_Y) == 0 &&
            !mode_value(t, GHOSTTY_MODE_BRACKETED_PASTE),
        "RIS reset clears cursor + modes");

  ghostty_terminal_free(t);
  return 0;
}

static int test_malformed(void) {
  printf("# malformed / incomplete input\n");
  GhosttyTerminal t = NULL;
  REQUIRE(ghostty_terminal_new(NULL, &t, 20, 5) == GHOSTTY_SUCCESS,
          "terminal new");

  feed(t, "\x1b[31;");       /* unfinished CSI */
  bool ground = true;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_VT_GROUND, &ground);
  CHECK(!ground, "parser not at ground mid-CSI");
  feed(t, "m");               /* completes as SGR 31 */
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_VT_GROUND, &ground);
  CHECK(ground, "parser back to ground after final byte");

  feed(t, "\x1b]8;;http://unterminated"); /* OSC never terminated */
  feed_n(t, "\xe2\x82", 2);               /* truncated UTF-8 */
  feed(t, "\x1b[99999999999999999999;9999999999999999H"); /* huge params */
  feed(t, "\x1b[?9999999h\x1bP\x1b\\\x1b_garbage\x1b\\");
  static const char junk[] = {0x1b, (char)0xff, (char)0xfe, 0x1b, '[',
                              (char)0x80, 0x00, (char)0x9b, '1', ';', 'x',
                              (char)0xc0, (char)0xaf, 0x1b, ']', (char)0x9c};
  feed_n(t, junk, sizeof(junk));
  for (int i = 0; i < 1000; i++) feed(t, "\x1b[");
  feed(t, "\x1b\\\x1b[0m\x1b[H\x1b[2Jok");

  GhosttyGridRef ref;
  GhosttyCell c = 0;
  uint32_t cp0 = 0, cp1 = 0;
  cell_at(t, 0, 0, &ref, &c);
  ghostty_cell_get(c, GHOSTTY_CELL_DATA_CODEPOINT, &cp0);
  cell_at(t, 1, 0, &ref, &c);
  ghostty_cell_get(c, GHOSTTY_CELL_DATA_CODEPOINT, &cp1);
  CHECK(cp0 == 'o' && cp1 == 'k', "terminal still usable after garbage");
  bool vt_err = true;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_VT_PROCESSING_ERROR, &vt_err);
  CHECK(!vt_err, "no non-graceful VT processing error recorded");

  /* write_until_ground stops at the boundary */
  size_t consumed = 0;
  feed(t, "\x1b[3");
  GhosttyResult gr = ghostty_terminal_vt_write_until_ground(
      t, (const uint8_t *)"1mXYZ", 5, &consumed);
  CHECK(gr == GHOSTTY_SUCCESS && consumed == 2,
        "vt_write_until_ground consumed %zu", consumed);

  ghostty_terminal_free(t);
  return 0;
}

static int test_lifecycle(void) {
  printf("# 1000 create/free cycles with a counting allocator\n");
  CountingCtx ctx = {0, 0, 0};
  GhosttyAllocator alloc = {.ctx = &ctx, .vtable = &counting_vtable};
  int ok = 0;
  for (int i = 0; i < 1000; i++) {
    GhosttyTerminal t = NULL;
    if (ghostty_terminal_new(&alloc, &t, 80, 24) != GHOSTTY_SUCCESS || !t)
      break;
    feed(t, "\x1b[31mred\x1b[0m\r\n");
    if (i % 100 == 0) ghostty_terminal_resize(t, 100, 30, 8, 16);
    ghostty_terminal_free(t);
    ok++;
  }
  CHECK(ok == 1000, "1000 terminals created, fed and freed (%d)", ok);
  CHECK(ctx.total_allocs > 0, "custom allocator used (%lld allocations)",
        ctx.total_allocs);
  CHECK(ctx.live_allocs == 0 && ctx.live_bytes == 0,
        "no leaked allocations (live=%lld bytes=%lld)", ctx.live_allocs,
        ctx.live_bytes);
  ghostty_terminal_free(NULL); /* documented no-op */
  return 0;
}

int main(void) {
  const char *manifest = ghostty_type_json();
  CHECK(manifest && strstr(manifest, "\"schema\"") != NULL,
        "ghostty_type_json manifest available");

  if (test_red_cells()) return 1;
  if (test_effects_and_modes()) return 1;
  if (test_malformed()) return 1;
  if (test_lifecycle()) return 1;

  printf("# %d checks, %d failures\n", g_checks, g_failures);
  if (g_failures) {
    printf("SMOKE FAILED\n");
    return 1;
  }
  printf("SMOKE PASSED\n");
  return 0;
}
