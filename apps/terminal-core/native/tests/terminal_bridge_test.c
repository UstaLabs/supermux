/*
 * Contract tests for the st_* ABI (native/src/terminal_bridge.c) against the
 * real pinned libghostty-vt. Everything is observed THROUGH THE CODEC: each
 * buffer the wrapper returns is decoded here with a strict decoder that
 * mirrors ViewportCodec.kt, so framing, ownership and semantics are all
 * checked the way the Kotlin bindings will see them.
 *
 * Environment:
 *   ST_FIXTURES_DIR   directory of the golden codec fixtures (fixtures/codec)
 *   ST_WRITE_GOLDEN=1 (re)write the golden fixtures instead of comparing
 *   ST_SKIP_HEAVY=1   skip the history-budget and max-terminal fixtures
 *                     (used by the sanitizer run)
 *
 * Exit code 0 = all checks passed.
 */
#if !defined(_WIN32) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200112L
#endif
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if defined(_WIN32)
#include <malloc.h>
#endif

#include <ghostty/vt/allocator.h>

#include "supermux_terminal.h"

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond, ...) check_impl((cond) ? 1 : 0, __FILE__, __LINE__, __VA_ARGS__)
/* Preconditions: silent on success (not counted), abort on failure. */
#define REQUIRE(cond, ...)                                  \
  do {                                                      \
    if (!(cond)) {                                          \
      CHECK(0, __VA_ARGS__);                                \
      printf("aborting: required check failed\n");          \
      exit(1);                                              \
    }                                                       \
  } while (0)

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
  fflush(stdout);
  return ok;
}

#define COLOR_DEFAULT ((uint64_t)1 << 32)
static uint64_t rgba(unsigned r, unsigned g, unsigned b) {
  return ((uint64_t)r << 24) | ((uint64_t)g << 16) | ((uint64_t)b << 8) | 0xFFu;
}

/* ------------------------------------------------------------------ */
/* Counting allocator with failure injection                           */
/* ------------------------------------------------------------------ */

typedef struct {
  long long live_bytes, peak_bytes, live_allocs, total_allocs, frees;
  long long largest;
  long long fail_at; /* fail the Nth allocation (1-based); 0 = never */
  long long failed;
} Counting;

static void *c_alloc(void *ctx, size_t len, uint8_t align_log2, uintptr_t ret) {
  (void)ret;
  Counting *c = ctx;
  if (c->fail_at && c->total_allocs + 1 == c->fail_at) {
    c->total_allocs++;
    c->failed++;
    return NULL;
  }
  size_t align = (size_t)1 << align_log2;
  if (align < sizeof(void *)) align = sizeof(void *);
  size_t rounded = (len + align - 1) / align * align;
  if (rounded == 0) rounded = align;
  void *p = NULL;
#if defined(_WIN32)
  p = _aligned_malloc(rounded, align);
#else
  if (posix_memalign(&p, align, rounded) != 0) p = NULL;
#endif
  if (!p) return NULL;
  c->total_allocs++;
  c->live_allocs++;
  c->live_bytes += (long long)len;
  if (c->live_bytes > c->peak_bytes) c->peak_bytes = c->live_bytes;
  if ((long long)len > c->largest) c->largest = (long long)len;
  return p;
}

static bool c_resize(void *ctx, void *mem, size_t len, uint8_t a, size_t new_len, uintptr_t ret) {
  (void)mem, (void)a, (void)ret;
  if (new_len > len) return false;
  Counting *c = ctx;
  c->live_bytes += (long long)new_len - (long long)len;
  return true;
}

static void *c_remap(void *ctx, void *mem, size_t len, uint8_t a, size_t new_len, uintptr_t ret) {
  return c_resize(ctx, mem, len, a, new_len, ret) ? mem : NULL;
}

static void c_free(void *ctx, void *mem, size_t len, uint8_t a, uintptr_t ret) {
  (void)a, (void)ret;
  Counting *c = ctx;
  c->live_bytes -= (long long)len;
  c->live_allocs--;
  c->frees++;
#if defined(_WIN32)
  _aligned_free(mem);
#else
  free(mem);
#endif
}

static const GhosttyAllocatorVtable c_vtable = {c_alloc, c_resize, c_remap, c_free};

/* ------------------------------------------------------------------ */
/* Strict decoder (mirror of ViewportCodec.kt)                         */
/* ------------------------------------------------------------------ */

typedef struct {
  const uint8_t *p;
  size_t len, pos;
  bool ok;
  const char *why;
} Rd;

static void rd_fail(Rd *r, const char *why) {
  if (r->ok) r->why = why;
  r->ok = false;
}
static bool rd_need(Rd *r, size_t n) {
  if (!r->ok) return false;
  if (r->len - r->pos < n) {
    rd_fail(r, "truncated");
    return false;
  }
  return true;
}
static uint8_t rd_u8(Rd *r) { return rd_need(r, 1) ? r->p[r->pos++] : 0; }
static uint16_t rd_u16(Rd *r) {
  if (!rd_need(r, 2)) return 0;
  uint16_t v = (uint16_t)(r->p[r->pos] | (r->p[r->pos + 1] << 8));
  r->pos += 2;
  return v;
}
static uint32_t rd_u32(Rd *r) {
  if (!rd_need(r, 4)) return 0;
  uint32_t v = (uint32_t)r->p[r->pos] | ((uint32_t)r->p[r->pos + 1] << 8) | ((uint32_t)r->p[r->pos + 2] << 16) |
               ((uint32_t)r->p[r->pos + 3] << 24);
  r->pos += 4;
  return v;
}
static uint64_t rd_u64(Rd *r) {
  uint64_t lo = rd_u32(r);
  uint64_t hi = rd_u32(r);
  return lo | (hi << 32);
}
static int32_t rd_i32(Rd *r) { return (int32_t)rd_u32(r); }
static int64_t rd_i64(Rd *r) { return (int64_t)rd_u64(r); }
static bool rd_bool(Rd *r) {
  uint8_t v = rd_u8(r);
  if (v > 1) rd_fail(r, "bool not 0/1");
  return v == 1;
}
static uint32_t rd_count(Rd *r, size_t min_elem) {
  uint32_t n = rd_u32(r);
  if (r->ok && min_elem && (uint64_t)n * min_elem > r->len - r->pos) rd_fail(r, "impossible count");
  return r->ok ? n : 0;
}
static bool utf8_ok(const uint8_t *s, size_t n) {
  for (size_t i = 0; i < n;) {
    uint8_t c = s[i];
    size_t k;
    uint32_t cp;
    if (c < 0x80) { i++; continue; }
    else if (c >= 0xC2 && c <= 0xDF) { k = 2; cp = c & 0x1F; }
    else if (c >= 0xE0 && c <= 0xEF) { k = 3; cp = c & 0x0F; }
    else if (c >= 0xF0 && c <= 0xF4) { k = 4; cp = c & 0x07; }
    else return false;
    if (i + k > n) return false;
    for (size_t j = 1; j < k; j++) {
      if ((s[i + j] & 0xC0) != 0x80) return false;
      cp = (cp << 6) | (s[i + j] & 0x3F);
    }
    if ((k == 3 && cp < 0x800) || (k == 4 && (cp < 0x10000 || cp > 0x10FFFF)) || (cp >= 0xD800 && cp <= 0xDFFF))
      return false;
    i += k;
  }
  return true;
}
/* Returns a malloc'd NUL-terminated copy; *n = byte length. */
static char *rd_str(Rd *r, uint32_t *n_out) {
  uint32_t n = rd_u32(r);
  if (!rd_need(r, n)) return NULL;
  if (!utf8_ok(r->p + r->pos, n)) {
    rd_fail(r, "invalid utf-8");
    return NULL;
  }
  char *s = malloc((size_t)n + 1);
  memcpy(s, r->p + r->pos, n);
  s[n] = 0;
  r->pos += n;
  if (n_out) *n_out = n;
  return s;
}
static uint8_t *rd_bytes(Rd *r, uint32_t *n_out) {
  uint32_t n = rd_u32(r);
  if (!rd_need(r, n)) return NULL;
  uint8_t *s = malloc((size_t)n + 1);
  memcpy(s, r->p + r->pos, n);
  s[n] = 0;
  r->pos += n;
  *n_out = n;
  return s;
}
static bool color_ok(uint64_t c) { return (c >> 33) == 0 && (!(c & COLOR_DEFAULT) || c == COLOR_DEFAULT); }

/* Envelope: returns payload reader positioned after the header. */
static bool rd_envelope(Rd *r, const uint8_t *buf, size_t len, uint16_t kind) {
  *r = (Rd){buf, len, 0, true, NULL};
  uint32_t magic = rd_u32(r);
  uint16_t abi = rd_u16(r);
  uint16_t k = rd_u16(r);
  uint32_t payload = rd_u32(r);
  if (!r->ok) return false;
  if (magic != ST_CODEC_MAGIC) rd_fail(r, "bad magic");
  else if (abi != 1) rd_fail(r, "bad abi");
  else if (k != kind) rd_fail(r, "wrong kind");
  else if (payload > ST_MAX_PAYLOAD) rd_fail(r, "payload > 8 MiB");
  else if ((size_t)payload != len - ST_ENVELOPE_HEADER) rd_fail(r, "payload length mismatch");
  return r->ok;
}

typedef struct {
  char *text;
  uint32_t text_len;
  int32_t width;
  uint64_t fg, bg;
  int32_t flags, underline;
} Cell;
typedef struct {
  int32_t index;
  uint32_t ncells;
  Cell *cells;
} Row;
typedef struct {
  int32_t row, first, last;
  char *uri;
} Link;
typedef struct {
  int64_t gen;
  int32_t cols, rows, cw, ch;
  uint32_t nrows;
  Row *rows_v;
  int32_t cur_col, cur_row, shape;
  bool cur_visible;
  bool alt, mouse, bracketed;
  int64_t history, top;
  bool full;
  uint32_t nlinks;
  Link *links;
  bool has_sel;
  int64_t s_row, e_row;
  int32_t s_col, e_col;
  bool held;
} View;

static void view_free(View *v) {
  for (uint32_t i = 0; i < v->nrows; i++) {
    for (uint32_t j = 0; j < v->rows_v[i].ncells; j++) free(v->rows_v[i].cells[j].text);
    free(v->rows_v[i].cells);
  }
  free(v->rows_v);
  for (uint32_t i = 0; i < v->nlinks; i++) free(v->links[i].uri);
  free(v->links);
  memset(v, 0, sizeof(*v));
}

static bool decode_view(const uint8_t *buf, size_t len, View *v, const char **why) {
  memset(v, 0, sizeof(*v));
  Rd r;
  if (rd_envelope(&r, buf, len, ST_KIND_VIEWPORT)) {
    v->gen = rd_i64(&r);
    v->cols = rd_i32(&r);
    v->rows = rd_i32(&r);
    v->cw = rd_i32(&r);
    v->ch = rd_i32(&r);
    if (r.ok && (v->cols < 1 || v->cols > 4096 || v->rows < 1 || v->rows > 4096 || v->cw < 1 || v->ch < 1 ||
                 (int64_t)v->cols * v->rows > (int64_t)ST_MAX_CELLS))
      rd_fail(&r, "bad size");
    uint32_t n = rd_count(&r, 8);
    if (r.ok && n > (uint32_t)v->rows) rd_fail(&r, "more rows than viewport");
    if (r.ok) {
      v->rows_v = calloc(n ? n : 1, sizeof(Row));
      v->nrows = n;
    }
    int32_t prev = -1;
    for (uint32_t i = 0; i < n && r.ok; i++) {
      Row *row = &v->rows_v[i];
      row->index = rd_i32(&r);
      if (r.ok && (row->index <= prev || row->index >= v->rows)) rd_fail(&r, "bad row index");
      prev = row->index;
      uint32_t nc = rd_count(&r, 32);
      if (r.ok && nc != (uint32_t)v->cols) rd_fail(&r, "row width != columns");
      if (!r.ok) break;
      row->cells = calloc(nc ? nc : 1, sizeof(Cell));
      row->ncells = nc;
      for (uint32_t j = 0; j < nc && r.ok; j++) {
        Cell *c = &row->cells[j];
        c->text = rd_str(&r, &c->text_len);
        c->width = rd_i32(&r);
        c->fg = rd_u64(&r);
        c->bg = rd_u64(&r);
        c->flags = rd_i32(&r);
        c->underline = rd_i32(&r);
        if (r.ok && (c->width < 0 || c->width > 2)) rd_fail(&r, "bad width");
        if (r.ok && c->width == 0 && c->text_len != 0) rd_fail(&r, "text in a width-0 cell");
        if (r.ok && c->text_len > ST_MAX_CELL_TEXT) rd_fail(&r, "cell text too long");
        if (r.ok && (!color_ok(c->fg) || !color_ok(c->bg))) rd_fail(&r, "bad colour");
        if (r.ok && (c->flags & ~0xFF)) rd_fail(&r, "bad flags");
        if (r.ok && (c->underline < 0 || c->underline > 5)) rd_fail(&r, "bad underline");
      }
    }
    v->cur_col = rd_i32(&r);
    v->cur_row = rd_i32(&r);
    v->shape = rd_i32(&r);
    if (r.ok && (v->shape < 0 || v->shape > 3)) rd_fail(&r, "bad cursor shape");
    v->cur_visible = rd_bool(&r);
    v->alt = rd_bool(&r);
    v->mouse = rd_bool(&r);
    v->bracketed = rd_bool(&r);
    v->history = rd_i64(&r);
    v->top = rd_i64(&r);
    if (r.ok && (v->history < 0 || v->top < 0 || v->top > v->history)) rd_fail(&r, "bad scroll position");
    v->full = rd_bool(&r);
    if (r.ok && v->full && v->nrows != (uint32_t)v->rows) rd_fail(&r, "full frame without all rows");
    uint32_t nl = rd_count(&r, 16);
    if (r.ok) {
      v->links = calloc(nl ? nl : 1, sizeof(Link));
      v->nlinks = nl;
    }
    for (uint32_t i = 0; i < nl && r.ok; i++) {
      Link *l = &v->links[i];
      l->row = rd_i32(&r);
      l->first = rd_i32(&r);
      l->last = rd_i32(&r);
      l->uri = rd_str(&r, NULL);
      if (r.ok && (l->row < 0 || l->row >= v->rows || l->first < 0 || l->last < l->first || l->last >= v->cols))
        rd_fail(&r, "bad link");
    }
    v->has_sel = rd_bool(&r);
    if (v->has_sel) {
      v->s_row = rd_i64(&r);
      v->s_col = rd_i32(&r);
      v->e_row = rd_i64(&r);
      v->e_col = rd_i32(&r);
      int64_t max_row = v->history + v->rows - 1;
      if (r.ok && (v->s_row < 0 || v->s_row > max_row || v->e_row < 0 || v->e_row > max_row || v->s_col < 0 ||
                   v->s_col >= v->cols || v->e_col < 0 || v->e_col >= v->cols))
        rd_fail(&r, "selection out of range");
    }
    v->held = rd_bool(&r);
    if (r.ok && r.pos != r.len) rd_fail(&r, "trailing bytes");
  }
  if (why) *why = r.why;
  if (!r.ok) view_free(v);
  return r.ok;
}

typedef struct {
  uint8_t tag;
  uint8_t *data; /* bytes or UTF-8 text (NUL-terminated) */
  uint32_t len;
  bool write, has_text;
} Effect;

static void effects_free(Effect *e, uint32_t n) {
  for (uint32_t i = 0; i < n; i++) free(e[i].data);
  free(e);
}

static bool decode_effects(const uint8_t *buf, size_t len, Effect **out, uint32_t *n_out, const char **why) {
  Rd r;
  *out = NULL;
  *n_out = 0;
  Effect *fx = NULL;
  uint32_t n = 0;
  if (rd_envelope(&r, buf, len, ST_KIND_EFFECTS)) {
    n = rd_count(&r, 1);
    if (r.ok) fx = calloc(n ? n : 1, sizeof(Effect));
    for (uint32_t i = 0; i < n && r.ok; i++) {
      Effect *e = &fx[i];
      e->tag = rd_u8(&r);
      switch (e->tag) {
        case ST_EFFECT_RESPONSE:
        case ST_EFFECT_INPUT: e->data = rd_bytes(&r, &e->len); break;
        case ST_EFFECT_TITLE: e->data = (uint8_t *)rd_str(&r, &e->len); break;
        case ST_EFFECT_BELL: break;
        case ST_EFFECT_CLIPBOARD:
          e->write = rd_bool(&r);
          e->has_text = rd_bool(&r);
          if (e->has_text) e->data = (uint8_t *)rd_str(&r, &e->len);
          if (r.ok && !e->write && e->has_text) rd_fail(&r, "clipboard read carries text");
          break;
        default: rd_fail(&r, "unknown effect tag");
      }
    }
    if (r.ok && r.pos != r.len) rd_fail(&r, "trailing bytes");
  }
  if (why) *why = r.why;
  if (!r.ok) {
    effects_free(fx, n);
    return false;
  }
  *out = fx;
  *n_out = n;
  return true;
}

static char *decode_text(const uint8_t *buf, size_t len, const char **why) {
  Rd r;
  char *s = NULL;
  if (rd_envelope(&r, buf, len, ST_KIND_SELECTED_TEXT)) {
    s = rd_str(&r, NULL);
    if (r.ok && r.pos != r.len) rd_fail(&r, "trailing bytes");
  }
  if (why) *why = r.why;
  if (!r.ok) {
    free(s);
    return NULL;
  }
  return s;
}

/* ------------------------------------------------------------------ */
/* Engine helpers                                                      */
/* ------------------------------------------------------------------ */

static st_handle mk_alloc(uint32_t cols, uint32_t rows, uint32_t lines, uint64_t bytes, const GhosttyAllocator *a) {
  st_handle h = 0;
  st_status s = st_create(ST_ABI_VERSION, cols, rows, 8, 16, lines, bytes, (const struct GhosttyAllocator *)a, &h);
  REQUIRE(s == ST_OK && h != 0, "st_create %ux%u (status %d)", cols, rows, s);
  return h;
}
static st_handle mk(uint32_t cols, uint32_t rows) { return mk_alloc(cols, rows, 10000, 32u << 20, NULL); }

static st_status feed_o(st_handle h, const char *s, uint32_t origin) {
  return st_feed(h, (const uint8_t *)s, (uint32_t)strlen(s), origin);
}
static void feed(st_handle h, const char *s) {
  st_status st = feed_o(h, s, ST_ORIGIN_LIVE);
  if (st != ST_OK) CHECK(false, "feed failed with %d", st);
}

/* Read + decode + free. Returns the st status (decode failure = -100). */
static st_status read_view(st_handle h, uint32_t flags, View *v, uint32_t *frame_flags) {
  uint8_t *buf = NULL;
  uint32_t len = 0, ff = 0;
  st_status s = st_read_viewport(h, flags, &buf, &len, &ff);
  if (s != ST_OK) return s;
  const char *why = NULL;
  bool ok = decode_view(buf, len, v, &why);
  if (!ok) printf("   decode error: %s\n", why ? why : "?");
  st_free_buffer(buf);
  if (frame_flags) *frame_flags = ff;
  return ok ? ST_OK : -100;
}
static void must_view(st_handle h, uint32_t flags, View *v) {
  REQUIRE(read_view(h, flags, v, NULL) == ST_OK, "read viewport");
}

static Row *find_row(View *v, int32_t index) {
  for (uint32_t i = 0; i < v->nrows; i++)
    if (v->rows_v[i].index == index) return &v->rows_v[i];
  return NULL;
}

/* Row text as the Kotlin contract test builds it (width 0 skipped, empty = ' ', trailing spaces trimmed). */
static char g_text[65536];
static const char *row_text(View *v, int32_t index) {
  Row *row = find_row(v, index);
  g_text[0] = 0;
  if (!row) return "<missing>";
  size_t n = 0;
  for (uint32_t i = 0; i < row->ncells; i++) {
    Cell *c = &row->cells[i];
    if (c->width == 0) continue;
    const char *t = c->text_len ? c->text : " ";
    size_t k = strlen(t);
    if (n + k + 1 >= sizeof(g_text)) break;
    memcpy(g_text + n, t, k);
    n += k;
  }
  while (n > 0 && g_text[n - 1] == ' ') n--;
  g_text[n] = 0;
  return g_text;
}

static uint32_t drain(st_handle h, Effect **fx) {
  uint8_t *buf = NULL;
  uint32_t len = 0, n = 0;
  REQUIRE(st_drain_effects(h, &buf, &len) == ST_OK, "drain effects");
  const char *why = NULL;
  REQUIRE(decode_effects(buf, len, fx, &n, &why), "decode effects (%s)", why ? why : "");
  st_free_buffer(buf);
  return n;
}
static void drain_discard(st_handle h) {
  Effect *fx;
  uint32_t n = drain(h, &fx);
  effects_free(fx, n);
}
/* Single Input effect expected; returns 1 if its bytes equal want. */
static bool expect_input(st_handle h, const char *want, size_t want_len) {
  Effect *fx;
  uint32_t n = drain(h, &fx);
  bool ok;
  if (want_len == 0) ok = n == 0;
  else ok = n == 1 && fx[0].tag == ST_EFFECT_INPUT && fx[0].len == want_len && memcmp(fx[0].data, want, want_len) == 0;
  if (!ok) {
    printf("   got %u effects:", n);
    for (uint32_t i = 0; i < n; i++) {
      printf(" [tag %u len %u:", fx[i].tag, fx[i].len);
      for (uint32_t j = 0; j < fx[i].len; j++) printf(" %02x", fx[i].data[j]);
      printf("]");
    }
    printf("\n");
  }
  effects_free(fx, n);
  return ok;
}

static char *selected(st_handle h) {
  uint8_t *buf = NULL;
  uint32_t len = 0;
  if (st_selected_text(h, &buf, &len) != ST_OK) return NULL;
  char *s = decode_text(buf, len, NULL);
  st_free_buffer(buf);
  return s;
}

static void fixture_colors(uint64_t *c) {
  c[0] = rgba(0xDD, 0xDD, 0xDD);
  c[1] = rgba(0x11, 0x11, 0x11);
  c[2] = rgba(0xFF, 0xCC, 0x00);
  for (unsigned i = 0; i < 256; i++) c[3 + i] = rgba(i, 255 - i, (i * 7) & 0xFF);
  c[3 + 1] = rgba(204, 102, 102);
}

/* ------------------------------------------------------------------ */
/* Tests                                                                */
/* ------------------------------------------------------------------ */

static void test_handles(void) {
  printf("# handles, ABI, arguments\n");
  CHECK(st_abi_version() == 1, "st_abi_version() == 1");
  st_handle h = 0;
  CHECK(st_create(2, 80, 24, 8, 16, 100, 1 << 20, NULL, &h) == ST_ERR_ABI_MISMATCH && h == 0, "wrong ABI version rejected");
  CHECK(st_create(ST_ABI_VERSION, 0, 24, 8, 16, 100, 1 << 20, NULL, &h) == ST_ERR_INVALID_ARGUMENT, "0 columns rejected");
  CHECK(st_create(ST_ABI_VERSION, 80, 4097, 8, 16, 100, 1 << 20, NULL, &h) == ST_ERR_INVALID_ARGUMENT, "4097 rows rejected");
  CHECK(st_create(ST_ABI_VERSION, 80, 24, 0, 16, 100, 1 << 20, NULL, &h) == ST_ERR_INVALID_ARGUMENT, "0 px cell rejected");
  CHECK(st_create(ST_ABI_VERSION, 80, 24, 8, 16, 100, 1 << 20, NULL, NULL) == ST_ERR_INVALID_ARGUMENT, "NULL out handle rejected");

  uint8_t *buf = (uint8_t *)1;
  uint32_t len = 0;
  uint64_t colors[ST_COLOR_COUNT];
  fixture_colors(colors);
  st_handle bad[] = {0, 0xDEADBEEFu, 1u, 1024u};
  for (unsigned i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
    st_handle b = bad[i];
    bool all = st_feed(b, (const uint8_t *)"x", 1, 0) == ST_ERR_INVALID_HANDLE && st_reset(b) == ST_ERR_INVALID_HANDLE &&
               st_resize(b, 10, 10, 8, 16) == ST_ERR_INVALID_HANDLE && st_colors(b, colors, ST_COLOR_COUNT) == ST_ERR_INVALID_HANDLE &&
               st_read_viewport(b, 0, &buf, &len, NULL) == ST_ERR_INVALID_HANDLE && st_acknowledge(b, 1) == ST_ERR_INVALID_HANDLE &&
               st_scroll_to(b, 0) == ST_ERR_INVALID_HANDLE && st_key(b, 4, NULL, 0, 0, 0) == ST_ERR_INVALID_HANDLE &&
               st_mouse(b, 0, 0, 1, 0, 0) == ST_ERR_INVALID_HANDLE && st_paste(b, NULL, 0, 0) == ST_ERR_INVALID_HANDLE &&
               st_focus(b, 1) == ST_ERR_INVALID_HANDLE && st_select(b, 0, 0, 0, 0, 0) == ST_ERR_INVALID_HANDLE &&
               st_selected_text(b, &buf, &len) == ST_ERR_INVALID_HANDLE && st_drain_effects(b, &buf, &len) == ST_ERR_INVALID_HANDLE &&
               st_destroy(b) == ST_ERR_INVALID_HANDLE;
    CHECK(all && buf == (uint8_t *)1, "handle 0x%x fails every call with ST_ERR_INVALID_HANDLE, no output written", b);
  }

  h = mk(80, 24);
  st_handle h2 = mk(80, 24);
  CHECK(h != h2, "distinct handles");
  CHECK(st_destroy(h) == ST_OK, "destroy");
  CHECK(st_destroy(h) == ST_ERR_INVALID_HANDLE, "second destroy is a predictable no-op");
  CHECK(st_feed(h, (const uint8_t *)"x", 1, 0) == ST_ERR_INVALID_HANDLE &&
            st_read_viewport(h, 0, &buf, &len, NULL) == ST_ERR_INVALID_HANDLE,
        "closed handle fails predictably");
  st_handle h3 = mk(80, 24); /* may reuse the slot: the old handle must stay dead */
  CHECK(h3 != h && st_feed(h, (const uint8_t *)"x", 1, 0) == ST_ERR_INVALID_HANDLE, "reused slot does not revive the old handle");
  CHECK(st_feed(h3, (const uint8_t *)"x", 1, 7) == ST_ERR_INVALID_ARGUMENT, "bad origin rejected");
  CHECK(st_feed(h3, NULL, 3, 0) == ST_ERR_INVALID_ARGUMENT, "NULL data with length rejected");
  CHECK(st_read_viewport(h3, 0, NULL, &len, NULL) == ST_ERR_INVALID_ARGUMENT, "NULL out buffer rejected");
  CHECK(st_read_viewport(h3, 64, &buf, &len, NULL) == ST_ERR_INVALID_ARGUMENT, "unknown read flag rejected");
  CHECK(st_colors(h3, colors, ST_COLOR_COUNT - 1) == ST_ERR_INVALID_ARGUMENT, "colour count != 259 rejected");
  colors[3 + 5] = COLOR_DEFAULT;
  CHECK(st_colors(h3, colors, ST_COLOR_COUNT) == ST_ERR_INVALID_ARGUMENT, "DEFAULT palette colour rejected");
  colors[3 + 5] = (uint64_t)1 << 40;
  CHECK(st_colors(h3, colors, ST_COLOR_COUNT) == ST_ERR_INVALID_ARGUMENT, "colour with high bits rejected");
  CHECK(st_key(h3, 4, NULL, 0, 1u << 6, 0) == ST_ERR_INVALID_ARGUMENT, "unknown modifier bit rejected");
  CHECK(st_key(h3, 4, NULL, 0, 0, 3) == ST_ERR_INVALID_ARGUMENT, "unknown key action rejected");
  CHECK(st_mouse(h3, 0, 0, 8, 0, 0) == ST_ERR_INVALID_ARGUMENT, "unknown mouse button rejected");
  CHECK(st_mouse(h3, 0, 0, 1, 0, 3) == ST_ERR_INVALID_ARGUMENT, "unknown mouse action rejected");
  CHECK(st_focus(h3, 2) == ST_ERR_INVALID_ARGUMENT, "focus must be 0/1");
  CHECK(st_paste(h3, (const uint8_t *)"x", 1, 2) == ST_ERR_INVALID_ARGUMENT, "unknown paste flag rejected");
  CHECK(st_free_buffer(NULL) == ST_OK, "st_free_buffer(NULL) is a no-op");
  static _Alignas(16) uint8_t fake[128];
  CHECK(st_free_buffer(fake + 64) == ST_ERR_INVALID_ARGUMENT, "foreign pointer rejected by st_free_buffer");
  st_destroy(h2);
  st_destroy(h3);
}

static void test_red_cells_and_wide(void) {
  printf("# red cells, empty cells, wide cells (through the codec)\n");
  st_handle h = mk(80, 24);
  uint64_t colors[ST_COLOR_COUNT];
  fixture_colors(colors);
  CHECK(st_colors(h, colors, ST_COLOR_COUNT) == ST_OK, "st_colors");
  feed(h, "\x1b[31mred\x1b[0m");
  View v;
  must_view(h, ST_READ_FORCE_FULL, &v);
  CHECK(v.full && v.nrows == 24 && v.cols == 80 && v.rows == 24 && v.cw == 8 && v.ch == 16, "full 80x24 frame, 8x16 px cells");
  Row *r0 = find_row(&v, 0);
  REQUIRE(r0 && r0->ncells == 80, "row 0 has 80 cells");
  bool red = true;
  for (int i = 0; i < 3; i++) {
    Cell *c = &r0->cells[i];
    red = red && c->text_len == 1 && c->text[0] == "red"[i] && c->width == 1 && c->fg == colors[3 + 1] &&
          c->bg == COLOR_DEFAULT && c->flags == 0 && c->underline == 0;
  }
  CHECK(red, "cells 0-2 = r,e,d, fg = configured palette[1] (0x%llx), bg DEFAULT, no flags", (unsigned long long)r0->cells[0].fg);
  CHECK(r0->cells[3].text_len == 0 && r0->cells[3].fg == COLOR_DEFAULT && r0->cells[3].bg == COLOR_DEFAULT &&
            r0->cells[3].width == 1,
        "cell 3 empty: text \"\", fg/bg DEFAULT");
  CHECK(v.cur_col == 3 && v.cur_row == 0 && v.cur_visible && v.shape == 0, "cursor (3,0) visible block");
  CHECK(!v.alt && !v.mouse && !v.bracketed && v.history == 0 && v.top == 0 && v.nlinks == 0 && !v.has_sel,
        "default modes, no history, links or selection");
  view_free(&v);

  feed(h, "\r\n\xe4\xb8\x96!\x1b[1;3;4;9;53m\x1b[4:3mS\x1b[0m");
  must_view(h, ST_READ_FORCE_FULL, &v);
  Row *r1 = find_row(&v, 1);
  REQUIRE(r1 != NULL, "row 1");
  CHECK(strcmp(r1->cells[0].text, "\xe4\xb8\x96") == 0 && r1->cells[0].width == 2, "wide cell: text 世 width 2");
  CHECK(r1->cells[1].text_len == 0 && r1->cells[1].width == 0, "continuation cell: width 0, no text");
  CHECK(strcmp(r1->cells[2].text, "!") == 0 && r1->cells[2].width == 1, "'!' after the wide cell");
  Cell *s = &r1->cells[3];
  CHECK(strcmp(s->text, "S") == 0 && s->flags == (1 | 2 | 64 | 128) && s->underline == 3,
        "bold+italic+strike+overline flags (0x%x), curly underline (%d)", s->flags, s->underline);
  view_free(&v);

  /* SGR background: explicit bg colour, default fg */
  feed(h, "\r\n\x1b[42mG\x1b[0m\x1b[38;2;1;2;3mT\x1b[0m");
  must_view(h, ST_READ_FORCE_FULL, &v);
  Row *r2 = find_row(&v, 2);
  CHECK(r2 && r2->cells[0].bg == colors[3 + 2] && r2->cells[0].fg == COLOR_DEFAULT, "SGR 42: bg = palette[2], fg DEFAULT");
  CHECK(r2 && r2->cells[1].fg == rgba(1, 2, 3), "SGR 38;2 truecolor fg");
  view_free(&v);
  st_destroy(h);
}

static void test_effects(void) {
  printf("# effects: responses, replay suppression, bell, title, clipboard\n");
  st_handle h = mk(80, 24);
  Effect *fx;
  uint32_t n;

  CHECK(feed_o(h, "\x1b[6n", ST_ORIGIN_REPLAY) == ST_OK, "feed REPLAY");
  n = drain(h, &fx);
  CHECK(n == 0, "REPLAY CSI 6n queues nothing (%u effects)", n);
  effects_free(fx, n);

  feed(h, "ab\x1b[6n");
  n = drain(h, &fx);
  CHECK(n == 1 && fx[0].tag == ST_EFFECT_RESPONSE && fx[0].len == 6 && memcmp(fx[0].data, "\x1b[1;3R", 6) == 0,
        "LIVE CSI 6n -> exactly one Response ESC[1;3R");
  effects_free(fx, n);
  n = drain(h, &fx);
  CHECK(n == 0, "drain consumed the effects exactly once");
  effects_free(fx, n);

  const char *bell_clip = "\x07\x1b]52;c;aGk=\x07\x1b]52;c;?\x07";
  feed_o(h, bell_clip, ST_ORIGIN_REPLAY);
  n = drain(h, &fx);
  CHECK(n == 0, "REPLAY bell + OSC 52 write/read queue nothing");
  effects_free(fx, n);
  feed(h, bell_clip);
  n = drain(h, &fx);
  CHECK(n == 4 && fx[0].tag == ST_EFFECT_BELL && fx[1].tag == ST_EFFECT_CLIPBOARD && fx[1].write && fx[1].has_text &&
            strcmp((char *)fx[1].data, "hi") == 0 && fx[2].tag == ST_EFFECT_CLIPBOARD && !fx[2].write && !fx[2].has_text,
        "LIVE: Bell, ClipboardRequest(write, \"hi\"), ClipboardRequest(read) (%u effects)", n);
  /* Reads are answered synchronously by the engine with an empty clipboard (denied). */
  CHECK(n == 4 && fx[3].tag == ST_EFFECT_RESPONSE && fx[3].len == 8 && memcmp(fx[3].data, "\x1b]52;c;\x07", 8) == 0,
        "OSC 52 read is denied in-band: Response ESC]52;c;BEL");
  effects_free(fx, n);

  feed_o(h, "\x1b]2;replayed\x07", ST_ORIGIN_REPLAY);
  feed(h, "\x1b]2;t\xc3\xadtulo\x07");
  n = drain(h, &fx);
  CHECK(n == 2 && fx[0].tag == ST_EFFECT_TITLE && strcmp((char *)fx[0].data, "replayed") == 0 &&
            fx[1].tag == ST_EFFECT_TITLE && strcmp((char *)fx[1].data, "t\xc3\xadtulo") == 0,
        "Title reported for REPLAY too; UTF-8 title intact");
  effects_free(fx, n);
  feed(h, "\x1b]52;c;Yf9i\x07"); /* base64 of 61 ff 62: not UTF-8 */
  n = drain(h, &fx);
  CHECK(n == 1 && fx[0].tag == ST_EFFECT_CLIPBOARD && fx[0].has_text && strcmp((char *)fx[0].data, "a\xef\xbf\xbd" "b") == 0,
        "binary clipboard text: invalid UTF-8 replaced by U+FFFD on the wire");
  effects_free(fx, n);

  feed(h, "\x1b]52;c;\x07"); /* empty payload: clear */
  n = drain(h, &fx);
  CHECK(n == 1 && fx[0].tag == ST_EFFECT_CLIPBOARD && fx[0].write && !fx[0].has_text,
        "OSC 52 with empty data -> ClipboardRequest(write, null) (%u)", n);
  effects_free(fx, n);

  feed(h, "\x1b[c\x1b[18t");
  n = drain(h, &fx);
  CHECK(n == 1 && fx[0].tag == ST_EFFECT_RESPONSE && fx[0].len > 5 && memcmp(fx[0].data, "\x1b[?62", 5) == 0 &&
            strstr((char *)fx[0].data, "\x1b[8;24;80t") != NULL,
        "DA1 and XTWINOPS size replies merged into one Response (%.*s)", n ? (int)fx[0].len - 1 : 0,
        n ? (char *)fx[0].data + 1 : "");
  effects_free(fx, n);

  /* The queue keeps order across kinds. */
  feed(h, "\x07\x1b]2;t\x07\x1b[5n");
  n = drain(h, &fx);
  CHECK(n == 3 && fx[0].tag == ST_EFFECT_BELL && fx[1].tag == ST_EFFECT_TITLE && fx[2].tag == ST_EFFECT_RESPONSE &&
            fx[2].len == 4 && memcmp(fx[2].data, "\x1b[0n", 4) == 0,
        "effects keep stream order");
  effects_free(fx, n);
  st_destroy(h);
}

static void test_input(void) {
  printf("# input encodings -> Input effects\n");
  st_handle h = mk(80, 24);
  CHECK(st_key(h, 0x06, (const uint8_t *)"c", 1, 2 /* CTRL */, 0) == ST_OK && expect_input(h, "\x03", 1), "Ctrl+C -> 0x03");
  CHECK(st_key(h, 0x04, (const uint8_t *)"a", 1, 0, 0) == ST_OK && expect_input(h, "a", 1), "a -> 'a'");
  CHECK(st_key(h, 0x04, (const uint8_t *)"A", 1, 1 /* SHIFT */, 0) == ST_OK && expect_input(h, "A", 1), "Shift+a -> 'A'");
  CHECK(st_key(h, 0x28, NULL, 0, 0, 0) == ST_OK && expect_input(h, "\r", 1), "Enter -> CR");
  CHECK(st_key(h, 0x52, NULL, 0, 0, 0) == ST_OK && expect_input(h, "\x1b[A", 3), "ArrowUp -> ESC [ A");
  feed(h, "\x1b[?1h");
  CHECK(st_key(h, 0x52, NULL, 0, 0, 0) == ST_OK && expect_input(h, "\x1bOA", 3), "ArrowUp under DECCKM -> ESC O A");
  CHECK(st_key(h, 0x04, (const uint8_t *)"a", 1, 0, 1 /* RELEASE */) == ST_OK && expect_input(h, "", 0), "legacy release -> nothing");
  CHECK(st_key(h, 0, (const uint8_t *)"\xc3\xa9", 2, 0, 0) == ST_OK && expect_input(h, "\xc3\xa9", 2), "IME text on UNIDENTIFIED -> UTF-8");
  CHECK(st_key(h, 0x04, (const uint8_t *)"\x01", 1, 0, 0) == ST_OK, "C0 text accepted (ignored)");
  drain_discard(h);

  /* Mouse: nothing without tracking */
  CHECK(st_mouse(h, 5, 2, 1, 0, 0) == ST_OK && expect_input(h, "", 0), "mouse without tracking -> nothing");
  feed(h, "\x1b[?1000h\x1b[?1006h");
  CHECK(st_mouse(h, 5, 2, 1, 0, 0) == ST_OK && expect_input(h, "\x1b[<0;6;3M", 9), "left press cell (5,2) -> ESC[<0;6;3M");
  CHECK(st_mouse(h, 5, 2, 1, 0, 1) == ST_OK && expect_input(h, "\x1b[<0;6;3m", 9), "left release -> ESC[<0;6;3m");
  static const char *wheel[4] = {"\x1b[<64;3;2M", "\x1b[<65;3;2M", "\x1b[<66;3;2M", "\x1b[<67;3;2M"};
  static const char *names[4] = {"WHEEL_UP", "WHEEL_DOWN", "WHEEL_LEFT", "WHEEL_RIGHT"};
  for (uint32_t b = 4; b <= 7; b++) {
    CHECK(st_mouse(h, 2, 1, b, 0, 0) == ST_OK && expect_input(h, wheel[b - 4], strlen(wheel[b - 4])),
          "SGR %s at cell (2,1) -> %s", names[b - 4], wheel[b - 4] + 1);
  }
  CHECK(st_mouse(h, 2, 1, 4, 2 /* CTRL */, 0) == ST_OK && expect_input(h, "\x1b[<80;3;2M", 10), "Ctrl+wheel up -> button 80");
  feed(h, "\x1b[?1006l");
  CHECK(st_mouse(h, 2, 1, 4, 0, 0) == ST_OK && expect_input(h, "\x1b[M`#\"", 6), "X10 encoding wheel up -> ESC[M`#\"");
  feed(h, "\x1b[?1000l\x1b[?1002h\x1b[?1006h");
  CHECK(st_mouse(h, 1, 1, 0, 0, 2) == ST_OK && expect_input(h, "", 0), "button-event mode: motion without button -> nothing");
  CHECK(st_mouse(h, 1, 1, 1, 0, 0) == ST_OK && expect_input(h, "\x1b[<0;2;2M", 9), "press");
  CHECK(st_mouse(h, 3, 1, 1, 0, 2) == ST_OK && expect_input(h, "\x1b[<32;4;2M", 10), "drag with left held -> ESC[<32;4;2M");
  CHECK(st_mouse(h, 3, 1, 1, 0, 1) == ST_OK && expect_input(h, "\x1b[<0;4;2m", 9), "release");

  /* Focus */
  CHECK(st_focus(h, 1) == ST_OK && expect_input(h, "", 0), "focus without mode 1004 -> nothing");
  feed(h, "\x1b[?1004h");
  CHECK(st_focus(h, 1) == ST_OK && expect_input(h, "\x1b[I", 3), "focus gained -> ESC[I");
  CHECK(st_focus(h, 0) == ST_OK && expect_input(h, "\x1b[O", 3), "focus lost -> ESC[O");

  /* Paste */
  CHECK(st_paste(h, (const uint8_t *)"a\nb", 3, 0) == ST_ERR_REJECTED && expect_input(h, "", 0),
        "unbracketed multi-line paste rejected, nothing queued");
  CHECK(st_paste(h, (const uint8_t *)"a\nb", 3, ST_PASTE_ALLOW_UNSAFE) == ST_OK && expect_input(h, "a\rb", 3),
        "allowed unsafe paste -> newline converted to CR");
  feed(h, "\x1b[?2004h");
  CHECK(st_paste(h, (const uint8_t *)"hi", 2, 0) == ST_OK && expect_input(h, "\x1b[200~hi\x1b[201~", 14),
        "bracketed paste -> ESC[200~hi ESC[201~ as ONE Input effect");
  CHECK(st_paste(h, NULL, 0, 0) == ST_OK && expect_input(h, "", 0), "empty paste -> nothing");
  CHECK(st_paste(h, (const uint8_t *)"a\x1b[201~b", 9, 0) == ST_ERR_REJECTED && expect_input(h, "", 0),
        "bracketed paste containing the end marker rejected, nothing queued");
  CHECK(st_paste(h, (const uint8_t *)"a\x1b[201~b", 9, ST_PASTE_ALLOW_UNSAFE) == ST_OK, "... and accepted with ALLOW_UNSAFE");
  drain_discard(h);

  /* Input never mixes with responses */
  feed(h, "\x1b[6n");
  st_key(h, 0x04, (const uint8_t *)"a", 1, 0, 0);
  Effect *fx;
  uint32_t n = drain(h, &fx);
  CHECK(n == 2 && fx[0].tag == ST_EFFECT_RESPONSE && fx[1].tag == ST_EFFECT_INPUT, "Response then Input, distinct kinds");
  effects_free(fx, n);
  st_destroy(h);
}

static void test_links_selection_scroll(void) {
  printf("# links, selection, scrollback coordinates\n");
  st_handle h = mk(80, 24);
  feed(h, "\x1b]8;;https://example.com/a\x1b\\link\x1b]8;;\x1b\\ x \x1b]8;id=1;u2\x1b\\\xe4\xb8\x96\x1b]8;;\x1b\\");
  View v;
  must_view(h, ST_READ_FORCE_FULL, &v);
  CHECK(v.nlinks == 2 && v.links[0].row == 0 && v.links[0].first == 0 && v.links[0].last == 3 &&
            strcmp(v.links[0].uri, "https://example.com/a") == 0,
        "OSC 8 span row 0 cols 0-3");
  CHECK(v.nlinks == 2 && v.links[1].first == 7 && v.links[1].last == 8 && strcmp(v.links[1].uri, "u2") == 0,
        "wide linked char spans both columns (7-8)");
  view_free(&v);
  st_destroy(h);

  h = mk(80, 24);
  feed(h, "hello world\r\nsecond line");
  CHECK(st_select(h, 1, 0, 6, 1, 5) == ST_OK, "select (0,6)-(1,5)");
  char *t = selected(h);
  CHECK(t && strcmp(t, "world\nsecond") == 0, "selected text \"world\\nsecond\"");
  free(t);
  must_view(h, 0, &v);
  CHECK(v.has_sel && v.s_row == 0 && v.s_col == 6 && v.e_row == 1 && v.e_col == 5, "viewport reports the selection");
  view_free(&v);
  CHECK(st_select(h, 1, 1, 5, 0, 6) == ST_OK && (t = selected(h)) && strcmp(t, "world\nsecond") == 0, "reversed ends");
  free(t);
  CHECK(st_select(h, 1, 30, 0, 0, 0) == ST_ERR_INVALID_ARGUMENT, "row beyond screen rejected");
  CHECK(st_select(h, 1, 0, 80, 0, 0) == ST_ERR_INVALID_ARGUMENT, "column beyond width rejected");
  CHECK(st_select(h, 1, -1, 0, 0, 0) == ST_ERR_INVALID_ARGUMENT, "negative row rejected");
  CHECK(st_select(h, 0, 0, 0, 0, 0) == ST_OK && (t = selected(h)) && t[0] == 0, "cleared selection -> \"\"");
  free(t);
  must_view(h, 0, &v);
  CHECK(!v.has_sel, "viewport reports no selection");
  view_free(&v);
  st_destroy(h);

  /* soft-wrapped lines are unwrapped when copying */
  h = mk(10, 5);
  feed(h, "0123456789abcde");
  CHECK(st_select(h, 1, 0, 0, 1, 4) == ST_OK, "select across a soft wrap");
  t = selected(h);
  CHECK(t && strcmp(t, "0123456789abcde") == 0, "soft wrap unwrapped in selected text (%s)", t ? t : "NULL");
  free(t);
  st_destroy(h);

  /* history coordinates: TerminalPoint.row == scrollback-inclusive screen row */
  h = mk(80, 24);
  char line[64];
  for (int i = 1; i <= 100; i++) {
    snprintf(line, sizeof(line), "line %03d\r\n", i);
    feed(h, line);
  }
  must_view(h, ST_READ_FORCE_FULL, &v);
  int64_t H = v.history;
  CHECK(H == 77 && v.top == H, "100 lines in 24 rows: historyRows %lld (77), viewportTop == historyRows", (long long)H);
  CHECK(strcmp(row_text(&v, 0), "line 078") == 0, "bottom viewport row 0 = line 078 (%s)", row_text(&v, 0));
  view_free(&v);
  CHECK(st_scroll_to(h, 0) == ST_OK, "scroll_to(0)");
  must_view(h, 0, &v);
  CHECK(v.full && v.top == 0 && strcmp(row_text(&v, 0), "line 001") == 0, "top of history: row 0 = line 001, full frame");
  CHECK(!v.cur_visible && v.cur_row == 23 + 77, "cursor below the scrolled viewport: invisible, row %d", v.cur_row);
  view_free(&v);
  CHECK(st_select(h, 1, 0, 0, 0, 7) == ST_OK && (t = selected(h)) && strcmp(t, "line 001") == 0,
        "select absolute row 0 -> line 001");
  free(t);
  CHECK(st_select(h, 1, 77, 0, 77, 7) == ST_OK && (t = selected(h)) && strcmp(t, "line 078") == 0,
        "select absolute row 77 (first active row) -> line 078");
  free(t);
  st_scroll_to(h, 40);
  must_view(h, 0, &v);
  CHECK(v.top == 40 && strcmp(row_text(&v, 0), "line 041") == 0 && v.has_sel && v.s_row == 77,
        "scroll_to(40): row 0 = line 041; selection keeps absolute row 77");
  view_free(&v);
  st_scroll_to(h, INT64_MAX);
  must_view(h, 0, &v);
  CHECK(v.top == H, "scroll_to(huge) clamps to the bottom (%lld)", (long long)v.top);
  view_free(&v);
  st_scroll_to(h, -5);
  must_view(h, 0, &v);
  CHECK(v.top == 0, "scroll_to(negative) clamps to 0");
  view_free(&v);
  st_destroy(h);
}

static void test_reset_resize_modes(void) {
  printf("# reset, resize, modes\n");
  st_handle h = mk(80, 24);
  uint64_t colors[ST_COLOR_COUNT];
  fixture_colors(colors);
  st_colors(h, colors, ST_COLOR_COUNT);
  feed(h, "primary text");
  feed(h, "\x1b[?1000h\x1b[?2004h\x1b[?1049h\x1b[Halt text");
  View v;
  must_view(h, ST_READ_FORCE_FULL, &v);
  CHECK(v.alt && v.mouse && v.bracketed && strcmp(row_text(&v, 0), "alt text") == 0, "alt screen + mouse + bracketed on");
  view_free(&v);
  CHECK(st_reset(h) == ST_OK, "reset");
  must_view(h, 0, &v);
  bool empty = true;
  for (uint32_t i = 0; i < v.nrows; i++)
    for (uint32_t j = 0; j < v.rows_v[i].ncells; j++) empty = empty && v.rows_v[i].cells[j].text_len == 0;
  CHECK(v.full && v.nrows == 24 && empty && !v.alt && !v.mouse && !v.bracketed && v.cur_col == 0 && v.cur_row == 0,
        "after reset: full frame, all cells empty, modes off, cursor home");
  view_free(&v);
  feed(h, "\x1b[?1049l\x1b[31mX");
  must_view(h, ST_READ_FORCE_FULL, &v);
  CHECK(strcmp(row_text(&v, 0), "X") == 0 && find_row(&v, 0)->cells[0].fg == colors[4],
        "primary text gone after reset; configured palette survives");
  view_free(&v);

  feed(h, "\r\nhello");
  CHECK(st_resize(h, 40, 10, 9, 18) == ST_OK, "resize 40x10 @ 9x18");
  must_view(h, 0, &v);
  CHECK(v.full && v.cols == 40 && v.rows == 10 && v.cw == 9 && v.ch == 18 && v.nrows == 10, "resized full frame");
  CHECK(strcmp(row_text(&v, 1), "hello") == 0, "content survives resize");
  view_free(&v);
  CHECK(st_resize(h, 4097, 10, 9, 18) == ST_ERR_INVALID_ARGUMENT, "resize beyond 4096 rejected");
  feed(h, "\x1b[18t");
  Effect *fx;
  uint32_t n = drain(h, &fx);
  CHECK(n == 1 && fx[0].len == 10 && memcmp(fx[0].data, "\x1b[8;10;40t", 10) == 0, "size report follows resize");
  effects_free(fx, n);
  st_destroy(h);
}

/* Apply a (partial or full) frame onto a model of the owner's screen. */
typedef struct {
  int32_t cols, rows;
  View last; /* rows_v indexed by row index, always complete */
  bool valid;
} Model;

static bool cell_eq(const Cell *a, const Cell *b) {
  return a->text_len == b->text_len && memcmp(a->text, b->text, a->text_len) == 0 && a->width == b->width &&
         a->fg == b->fg && a->bg == b->bg && a->flags == b->flags && a->underline == b->underline;
}

static void model_apply(Model *m, View *v) {
  if (v->full || !m->valid || v->cols != m->cols || v->rows != m->rows) {
    if (!v->full) REQUIRE(false, "size change or first frame must be full");
    view_free(&m->last);
    m->last = *v;
    memset(v, 0, sizeof(*v));
    m->cols = m->last.cols;
    m->rows = m->last.rows;
    m->valid = true;
    return;
  }
  for (uint32_t i = 0; i < v->nrows; i++) {
    Row *src = &v->rows_v[i];
    Row *dst = &m->last.rows_v[src->index];
    for (uint32_t j = 0; j < dst->ncells; j++) free(dst->cells[j].text);
    free(dst->cells);
    *dst = *src;
    src->cells = NULL;
    src->ncells = 0;
  }
  view_free(v);
}

static bool model_matches(Model *m, View *full, int *bad_row) {
  if (full->cols != m->cols || full->rows != m->rows) return false;
  for (int32_t y = 0; y < full->rows; y++) {
    Row *a = &m->last.rows_v[y], *b = find_row(full, y);
    if (!b || a->ncells != b->ncells) {
      *bad_row = y;
      return false;
    }
    for (uint32_t x = 0; x < a->ncells; x++)
      if (!cell_eq(&a->cells[x], &b->cells[x])) {
        *bad_row = y;
        return false;
      }
  }
  return true;
}

static uint32_t g_rng = 12345;
static uint32_t rnd(uint32_t n) {
  g_rng = g_rng * 1103515245u + 12345u;
  return (g_rng >> 16) % n;
}

static void test_generations(void) {
  printf("# dirty generations and acknowledgement\n");
  st_handle h = mk(20, 6);
  View a, b, c;
  must_view(h, 0, &a);
  CHECK(a.full && a.nrows == 6, "first frame is full although not forced");
  CHECK(st_acknowledge(h, a.gen) == ST_OK, "ack first frame");
  must_view(h, 0, &b);
  CHECK(!b.full && b.nrows == 0 && b.gen == a.gen, "idle: empty partial frame, same generation");
  st_acknowledge(h, b.gen);
  int64_t idle_gen = b.gen;
  view_free(&a);
  view_free(&b);

  feed(h, "\x1b[3;1Hrow two");
  must_view(h, 0, &a);
  CHECK(!a.full && a.nrows <= 2 && find_row(&a, 2) && strcmp(row_text(&a, 2), "row two") == 0 &&
            (a.nrows == 1 || find_row(&a, 0)),
        "partial frame: the changed row 2 (+ the row the cursor left) only (%u rows)", a.nrows);
  CHECK(a.gen > idle_gen, "generation advanced (%lld -> %lld)", (long long)idle_gen, (long long)a.gen);
  /* a pending generation is not cleaned by acknowledging an older frame */
  feed(h, "\x1b[5;1Hrow four");
  must_view(h, 0, &b);
  CHECK(b.gen > a.gen && find_row(&b, 2) && find_row(&b, 4), "frame B: rows 2 (unacked A) and 4");
  CHECK(st_acknowledge(h, a.gen) == ST_OK, "ack of older frame A accepted as a no-op");
  must_view(h, 0, &c);
  CHECK(!c.full && find_row(&c, 2) && find_row(&c, 4), "older ack did not clean B's pending rows");
  view_free(&a);
  view_free(&b);
  CHECK(st_acknowledge(h, c.gen) == ST_OK, "ack current frame C");
  view_free(&c);
  must_view(h, 0, &a);
  CHECK(!a.full && a.nrows == 0, "after acking the current frame nothing is dirty");
  view_free(&a);

  /* mutation after serialization, then ack of that frame: the mutation is not lost */
  feed(h, "\x1b[1;1HA");
  must_view(h, 0, &a);
  feed(h, "\x1b[6;1HZ");
  CHECK(st_acknowledge(h, a.gen) == ST_OK, "ack frame A after a newer mutation");
  must_view(h, 0, &b);
  CHECK(!b.full && find_row(&b, 5) && strcmp(row_text(&b, 5), "Z") == 0 && !find_row(&b, 2),
        "the post-serialization mutation (row 5) is in the next frame; acked rows are not (%u rows)", b.nrows);
  st_acknowledge(h, b.gen);
  view_free(&a);
  view_free(&b);

  /* a generation that was never produced forces a full frame */
  CHECK(st_acknowledge(h, 1000000) == ST_ERR_INVALID_ARGUMENT, "ack of unknown future generation rejected");
  must_view(h, 0, &a);
  CHECK(a.full, "... and the next frame is full");
  view_free(&a);
  CHECK(st_acknowledge(h, -1) == ST_ERR_INVALID_ARGUMENT, "negative generation rejected");

  /* a full frame that is never acknowledged stays full */
  uint64_t colors[ST_COLOR_COUNT];
  fixture_colors(colors);
  st_colors(h, colors, ST_COLOR_COUNT);
  must_view(h, 0, &a);
  CHECK(a.full, "colour change -> full frame");
  view_free(&a);
  must_view(h, 0, &a);
  CHECK(a.full, "unacknowledged full frame -> still full");
  st_acknowledge(h, a.gen);
  view_free(&a);
  must_view(h, 0, &a);
  CHECK(!a.full, "acknowledged -> partial again");
  st_acknowledge(h, a.gen);
  view_free(&a);
  st_destroy(h);

  /* Randomized: partial frames applied to an owner model always equal a forced full frame. */
  h = mk_alloc(20, 6, 50, 1 << 20, NULL);
  Model m;
  memset(&m, 0, sizeof(m));
  int mismatches = 0, compared = 0, dropped = 0;
  for (int step = 0; step < 400; step++) {
    int ops = 1 + (int)rnd(3);
    for (int k = 0; k < ops; k++) {
      uint32_t op = rnd(100);
      char buf[128];
      if (op < 55) {
        int len = 1 + (int)rnd(12);
        for (int i = 0; i < len; i++) buf[i] = (char)('a' + rnd(26));
        buf[len] = 0;
        feed(h, buf);
      } else if (op < 70) {
        feed(h, "\r\n");
      } else if (op < 78) {
        snprintf(buf, sizeof(buf), "\x1b[%u;%uH\x1b[3%um", 1 + rnd(6), 1 + rnd(20), rnd(8));
        feed(h, buf);
      } else if (op < 82) {
        feed(h, rnd(2) ? "\x1b[2K" : "\x1b[J");
      } else if (op < 86) {
        st_scroll_to(h, (int64_t)rnd(60));
      } else if (op < 89) {
        st_scroll_to(h, INT64_MAX);
      } else if (op < 91) {
        st_resize(h, 15 + rnd(10), 4 + rnd(4), 8, 16);
      } else if (op < 93) {
        feed(h, rnd(2) ? "\x1b[?1049h" : "\x1b[?1049l");
      } else if (op < 95) {
        feed(h, "\xe4\xb8\x96\xcc\x81");
      } else if (op < 97) {
        st_select(h, 1, 0, 0, 0, 3);
      } else {
        feed(h, "\x1b[?2026h" "xyz" "\x1b[?2026l");
      }
    }
    View f;
    must_view(h, 0, &f);
    if (m.valid && !f.full && rnd(6) == 0) {
      dropped++; /* the owner never drew this frame: neither applied nor acknowledged */
      view_free(&f);
      continue;
    }
    int64_t g = f.gen;
    model_apply(&m, &f);
    st_acknowledge(h, g);
    View full;
    must_view(h, ST_READ_FORCE_FULL, &full);
    int bad = -1;
    compared++;
    if (!model_matches(&m, &full, &bad)) {
      mismatches++;
      if (mismatches < 4) printf("   step %d: model row %d differs from forced full frame\n", step, bad);
    }
    st_acknowledge(h, full.gen);
    view_free(&full);
  }
  CHECK(mismatches == 0, "randomized partial frames == forced full frame (%d compared, %d dropped frames, %d mismatches)",
        compared, dropped, mismatches);
  view_free(&m.last);
  st_destroy(h);
}

static void test_render_hold(void) {
  printf("# synchronized output (mode 2026) hold\n");
  st_handle h = mk(20, 4);
  feed(h, "A");
  feed(h, "\x1b[?2026hB");
  View v;
  uint32_t ff = 0;
  REQUIRE(read_view(h, 0, &v, &ff) == ST_OK, "read during hold");
  CHECK((ff & ST_FRAME_HELD) && v.held && strcmp(row_text(&v, 0), "A") == 0,
        "held frame (held field + flag) shows the pre-hold screen \"A\" (%s)", row_text(&v, 0));
  view_free(&v);
  feed(h, "C");
  REQUIRE(read_view(h, 0, &v, &ff) == ST_OK, "read during hold again");
  CHECK((ff & ST_FRAME_HELD) && strcmp(row_text(&v, 0), "A") == 0, "still the captured frame");
  view_free(&v);
  feed(h, "\x1b[?2026l");
  REQUIRE(read_view(h, 0, &v, &ff) == ST_OK, "read after hold");
  CHECK(!(ff & ST_FRAME_HELD) && !v.held && strcmp(row_text(&v, 0), "ABC") == 0, "hold released: \"ABC\", held = false");
  view_free(&v);

  feed(h, "\x1b[?2026hD");
  REQUIRE(read_view(h, ST_READ_BREAK_HOLD, &v, &ff) == ST_OK, "owner timeout: BREAK_HOLD");
  CHECK(!(ff & ST_FRAME_HELD) && !v.held && strcmp(row_text(&v, 0), "ABCD") == 0, "hold broken: live frame \"ABCD\"");
  view_free(&v);
  feed(h, "E");
  REQUIRE(read_view(h, 0, &v, &ff) == ST_OK, "read");
  CHECK(!(ff & ST_FRAME_HELD) && strcmp(row_text(&v, 0), "ABCDE") == 0, "mode 2026 stays off after the break");
  view_free(&v);

  /* hold begun and ended within one chunk: the final frame is live */
  feed(h, "\x1b[?2026hF\x1b[?2026l");
  REQUIRE(read_view(h, 0, &v, &ff) == ST_OK, "read");
  CHECK(!(ff & ST_FRAME_HELD) && strcmp(row_text(&v, 0), "ABCDEF") == 0, "begin+end in one chunk -> live frame");
  view_free(&v);
  /* reset ends a hold */
  feed(h, "\x1b[?2026h");
  st_reset(h);
  REQUIRE(read_view(h, 0, &v, &ff) == ST_OK, "read");
  CHECK(!(ff & ST_FRAME_HELD), "reset ends the hold");
  view_free(&v);
  st_destroy(h);
}

static void test_ownership(void) {
  printf("# buffer ownership\n");
  Counting c;
  memset(&c, 0, sizeof(c));
  GhosttyAllocator a = {.ctx = &c, .vtable = &c_vtable};
  st_handle h = mk_alloc(80, 24, 1000, 1 << 20, &a);
  feed(h, "first frame\x1b[6n");
  uint8_t *vp = NULL, *fx = NULL, *sel = NULL;
  uint32_t vlen = 0, flen = 0, slen = 0;
  REQUIRE(st_read_viewport(h, 0, &vp, &vlen, NULL) == ST_OK, "read viewport");
  REQUIRE(st_drain_effects(h, &fx, &flen) == ST_OK, "drain");
  st_select(h, 1, 0, 0, 0, 4);
  REQUIRE(st_selected_text(h, &sel, &slen) == ST_OK, "selected text");
  uint8_t *copy = malloc(vlen);
  memcpy(copy, vp, vlen);
  long long live_with_buffers = c.live_allocs;
  for (int i = 0; i < 200; i++) feed(h, "\x1b[2J\x1b[Hmore output that changes every row\r\n\x1b[6n");
  uint8_t *vp2 = NULL;
  uint32_t vlen2 = 0;
  REQUIRE(st_read_viewport(h, ST_READ_FORCE_FULL, &vp2, &vlen2, NULL) == ST_OK, "read again");
  CHECK(vlen2 > 0 && memcmp(vp, copy, vlen) == 0, "earlier viewport buffer unchanged after more output + reads");
  st_free_buffer(vp2);
  CHECK(st_destroy(h) == ST_OK, "destroy with buffers outstanding");
  CHECK(memcmp(vp, copy, vlen) == 0, "viewport buffer still valid after st_destroy");
  View v;
  const char *why = NULL;
  CHECK(decode_view(vp, vlen, &v, &why) && strcmp(row_text(&v, 0), "first frame") == 0, "and still decodes");
  view_free(&v);
  Effect *e;
  uint32_t n;
  CHECK(decode_effects(fx, flen, &e, &n, &why) && n == 1 && e[0].tag == ST_EFFECT_RESPONSE, "effects buffer valid after destroy");
  effects_free(e, n);
  char *s = decode_text(sel, slen, &why);
  CHECK(s && strcmp(s, "first") == 0, "selected-text buffer valid after destroy (%s)", s ? s : "NULL");
  free(s);
  CHECK(c.live_allocs == 3, "exactly the 3 returned buffers are live after destroy (%lld; %lld before destroy)",
        c.live_allocs, live_with_buffers);
  long long frees = c.frees;
  CHECK(st_free_buffer(vp) == ST_OK && c.frees == frees + 1 && c.live_allocs == 2, "st_free_buffer frees exactly once");
  st_free_buffer(fx);
  st_free_buffer(sel);
  CHECK(c.live_allocs == 0 && c.live_bytes == 0, "no live allocations after freeing every buffer (%lld allocs, %lld bytes)",
        c.live_allocs, c.live_bytes);
  free(copy);

  /* drain is atomic: nothing is consumed when the envelope cannot be allocated */
  memset(&c, 0, sizeof(c));
  h = mk_alloc(80, 24, 1000, 1 << 20, &a);
  feed(h, "\x07");
  c.fail_at = c.total_allocs + 1;
  uint8_t *b = NULL;
  uint32_t bl = 0;
  CHECK(st_drain_effects(h, &b, &bl) == ST_ERR_OUT_OF_MEMORY && b == NULL, "drain under OOM fails explicitly");
  c.fail_at = 0;
  n = drain(h, &e);
  CHECK(n == 1 && e[0].tag == ST_EFFECT_BELL, "the bell is still queued after the failed drain");
  effects_free(e, n);
  st_destroy(h);
  CHECK(c.live_allocs == 0, "no leaks");
}

static void test_framing(void) {
  printf("# codec framing: truncated / oversized / impossible counts\n");
  st_handle h = mk(20, 4);
  feed(h, "\x1b[31mhi\x1b]8;;u\x1b\\L\x1b]8;;\x1b\\");
  st_select(h, 1, 0, 0, 0, 1);
  uint8_t *buf = NULL;
  uint32_t len = 0;
  REQUIRE(st_read_viewport(h, ST_READ_FORCE_FULL, &buf, &len, NULL) == ST_OK, "read");
  View v;
  const char *why = NULL;
  CHECK(decode_view(buf, len, &v, &why), "decodes whole");
  view_free(&v);
  CHECK(len >= 12 && (uint32_t)(buf[8] | buf[9] << 8 | buf[10] << 16 | (uint32_t)buf[11] << 24) == len - 12 &&
            buf[0] == 0x54 && buf[1] == 0x56 && buf[2] == 0x54 && buf[3] == 0x53 && buf[4] == 1 && buf[5] == 0 && buf[6] == 1,
        "envelope header: magic 0x53545654 LE, abi 1, kind 1, payloadBytes = len-12");
  int accepted = 0;
  for (uint32_t cut = 0; cut < len; cut++) {
    uint8_t *t = malloc(cut ? cut : 1);
    memcpy(t, buf, cut);
    if (decode_view(t, cut, &v, &why)) {
      accepted++;
      view_free(&v);
    }
    /* also with the header claiming the truncated payload length */
    if (cut >= 12) {
      uint32_t pl = cut - 12;
      t[8] = (uint8_t)pl, t[9] = (uint8_t)(pl >> 8), t[10] = (uint8_t)(pl >> 16), t[11] = (uint8_t)(pl >> 24);
      if (decode_view(t, cut, &v, &why)) {
        accepted++;
        view_free(&v);
      }
    }
    free(t);
  }
  CHECK(accepted == 0, "every truncation of %u bytes rejected (%d accepted)", len, accepted);
  uint8_t *t = malloc(len + 1);
  memcpy(t, buf, len);
  t[len] = 0;
  CHECK(!decode_view(t, len + 1, &v, &why), "trailing byte rejected (%s)", why);
  t[0] ^= 1;
  CHECK(!decode_view(t, len, &v, &why), "bad magic rejected");
  t[0] ^= 1;
  t[4] = 2;
  CHECK(!decode_view(t, len, &v, &why), "abi 2 rejected");
  t[4] = 1;
  t[6] = 2;
  CHECK(!decode_view(t, len, &v, &why), "wrong kind rejected");
  t[6] = 1;
  /* row count sits right after generation(8) + size(16) */
  uint32_t off = 12 + 8 + 16;
  t[off] = t[off + 1] = t[off + 2] = t[off + 3] = 0xFF;
  CHECK(!decode_view(t, len, &v, &why) && why && strcmp(why, "impossible count") == 0, "impossible row count rejected (%s)", why);
  free(t);
  st_free_buffer(buf);

  /* payload > 8 MiB rejected even when the length is consistent */
  size_t big = 12 + ST_MAX_PAYLOAD + 1;
  uint8_t *bb = calloc(big, 1);
  bb[0] = 0x54, bb[1] = 0x56, bb[2] = 0x54, bb[3] = 0x53, bb[4] = 1, bb[6] = 2;
  uint32_t pl = ST_MAX_PAYLOAD + 1;
  bb[8] = (uint8_t)pl, bb[9] = (uint8_t)(pl >> 8), bb[10] = (uint8_t)(pl >> 16), bb[11] = (uint8_t)(pl >> 24);
  Effect *e;
  uint32_t n;
  CHECK(!decode_effects(bb, big, &e, &n, &why) && why && strcmp(why, "payload > 8 MiB") == 0, "oversized payload rejected");
  free(bb);
  st_destroy(h);

  /* Size cap: every accepted size can render; beyond ST_MAX_CELLS is refused up front. */
  st_handle hh = 0;
  CHECK(st_create(ST_ABI_VERSION, 1000, 101, 8, 16, 0, 0, NULL, &hh) == ST_ERR_INVALID_ARGUMENT,
        "1000x101 (101,000 cells > ST_MAX_CELLS) rejected at create");
  CHECK(st_create(ST_ABI_VERSION, 4096, 64, 8, 16, 0, 0, NULL, &hh) == ST_ERR_INVALID_ARGUMENT, "4096x64 rejected at create");
  h = mk_alloc(1000, 100, 0, 0, NULL);
  uint8_t *vb = NULL;
  uint32_t vl = 0;
  CHECK(st_read_viewport(h, ST_READ_FORCE_FULL, &vb, &vl, NULL) == ST_OK && vl <= 12 + ST_MAX_PAYLOAD,
        "1000x100 (exactly ST_MAX_CELLS) full frame fits (%u bytes)", vl);
  st_free_buffer(vb);
  CHECK(st_resize(h, 4096, 25, 8, 16) == ST_ERR_INVALID_ARGUMENT, "resize to 4096x25 (102,400 cells) rejected");
  CHECK(st_resize(h, 4096, 24, 8, 16) == ST_OK, "resize to 4096x24 (98,304 cells) accepted");
  vb = NULL;
  CHECK(st_read_viewport(h, 0, &vb, &vl, NULL) == ST_OK, "and renders (%u bytes)", vl);
  if (vb) {
    View vv;
    CHECK(decode_view(vb, vl, &vv, &why) && vv.full && vv.cols == 4096 && vv.rows == 24, "decodes, full 4096x24");
    view_free(&vv);
  }
  st_free_buffer(vb);
  st_destroy(h);

  /* Worst case at the cap: 400x250, every cell an 18-code-point (35-byte) cluster, 20 rows of
   * 2000-byte links, a different one per cell (far more than the link budget). */
  h = mk_alloc(400, 250, 0, 0, NULL);
  static char cell[128];
  size_t cl = 0;
  cell[cl++] = 'e';
  for (int m = 0; m < 17; m++) {
    cell[cl++] = (char)0xCC;
    cell[cl++] = (char)(0x80 + (m % 16));
  }
  static char uri[2100];
  size_t rowcap = 400 * (cl + 2100) + 64;
  char *row = malloc(rowcap);
  for (int y = 0; y < 250; y++) {
    size_t k = (size_t)snprintf(row, rowcap, "\x1b[%d;1H", y + 1);
    for (int x = 0; x < 400; x++) {
      if (y < 3) {
        int u = snprintf(uri, sizeof(uri), "\x1b]8;;https://example.com/%04d/%03d/", y, x);
        while (u < 2000) uri[u++] = 'x';
        uri[u++] = 0x1b, uri[u++] = '\\', uri[u] = 0;
        memcpy(row + k, uri, (size_t)u);
        k += (size_t)u;
      }
      memcpy(row + k, cell, cl);
      k += cl;
    }
    st_feed(h, (const uint8_t *)row, (uint32_t)k, ST_ORIGIN_LIVE);
  }
  free(row);
  vb = NULL;
  st_status ws = st_read_viewport(h, ST_READ_FORCE_FULL, &vb, &vl, NULL);
  CHECK(ws == ST_OK && vl <= 12 + ST_MAX_PAYLOAD, "worst-case 100,000-cell frame fits: %u bytes (status %d)", vl, ws);
  if (vb) {
    View vv;
    bool ok = decode_view(vb, vl, &vv, &why);
    bool cut = ok;
    for (uint32_t i = 0; ok && i < vv.nrows; i++)
      for (uint32_t j = 0; j < vv.rows_v[i].ncells; j++) {
        Cell *c = &vv.rows_v[i].cells[j];
        cut = cut && c->text_len <= ST_MAX_CELL_TEXT && c->text_len >= 31 && c->text[0] == 'e';
      }
    size_t link_bytes = 0;
    for (uint32_t i = 0; ok && i < vv.nlinks; i++) link_bytes += 16 + strlen(vv.links[i].uri);
    CHECK(ok && cut, "clusters cut to <= %u bytes at a code point boundary (%s)", ST_MAX_CELL_TEXT, ok ? "decoded" : why);
    CHECK(ok && vv.nlinks > 0 && vv.nlinks < 1200 && link_bytes <= ST_MAX_LINK_BYTES && link_bytes > ST_MAX_LINK_BYTES - 2100,
          "links beyond the %u-byte budget omitted (%u of 1200 kept, %zu bytes)", ST_MAX_LINK_BYTES, vv.nlinks, link_bytes);
    if (ok) view_free(&vv);
  }
  st_free_buffer(vb);
  st_destroy(h);
}

/* Whole vs split input at every byte boundary -> identical frame + effects. */
static void snapshot(st_handle h, uint8_t **frame, uint32_t *flen, uint8_t **fx, uint32_t *xlen) {
  REQUIRE(st_read_viewport(h, ST_READ_FORCE_FULL, frame, flen, NULL) == ST_OK, "read");
  REQUIRE(st_drain_effects(h, fx, xlen) == ST_OK, "drain");
}

static void test_chunk_splits(void) {
  printf("# chunk splits at every byte boundary\n");
  static const char fixture[] =
      "\x1b[1;31mA\x1b[0m\xc3\xa9\xe4\xb8\x96" "e\xcc\x81\x1b]8;;http://a\x1b\\L\x1b]8;;\x1b\\"
      "\x1b]2;T\x07\x1b[6n\r\nxy\x1b[2;5Hz\x07\x1b[?2004h\x1b]52;c;aGk=\x1b\\\x1bP$qm\x1b\\";
  size_t n = sizeof(fixture) - 1;
  st_handle h = mk(20, 4);
  feed(h, fixture);
  uint8_t *wf, *wx;
  uint32_t wfl, wxl;
  snapshot(h, &wf, &wfl, &wx, &wxl);
  st_destroy(h);
  Effect *e;
  uint32_t ne;
  const char *why;
  REQUIRE(decode_effects(wx, wxl, &e, &ne, &why), "decode baseline effects");
  CHECK(ne >= 5, "baseline fixture produces %u effects (title, response, bell, clipboard, DECRQSS)", ne);
  effects_free(e, ne);
  int bad = 0;
  for (size_t cut = 1; cut < n; cut++) {
    h = mk(20, 4);
    st_feed(h, (const uint8_t *)fixture, (uint32_t)cut, ST_ORIGIN_LIVE);
    st_feed(h, (const uint8_t *)fixture + cut, (uint32_t)(n - cut), ST_ORIGIN_LIVE);
    uint8_t *f, *x;
    uint32_t fl, xl;
    snapshot(h, &f, &fl, &x, &xl);
    /* skip the generation (8 bytes after the header): it counts feeds */
    bool same = fl == wfl && memcmp(f, wf, 12) == 0 && memcmp(f + 20, wf + 20, fl - 20) == 0 && xl == wxl &&
                memcmp(x, wx, xl) == 0;
    if (!same) {
      bad++;
      if (bad < 4) printf("   split at %zu differs (frame %u vs %u, effects %u vs %u)\n", cut, fl, wfl, xl, wxl);
    }
    st_free_buffer(f);
    st_free_buffer(x);
    st_destroy(h);
  }
  CHECK(bad == 0, "%zu split points: identical frames and effects (%d differ)", n - 1, bad);
  h = mk(20, 4);
  for (size_t i = 0; i < n; i++) st_feed(h, (const uint8_t *)fixture + i, 1, ST_ORIGIN_LIVE);
  {
    uint8_t *f, *x;
    uint32_t fl, xl;
    snapshot(h, &f, &fl, &x, &xl);
    CHECK(fl == wfl && memcmp(f, wf, 12) == 0 && memcmp(f + 20, wf + 20, fl - 20) == 0 && xl == wxl && memcmp(x, wx, xl) == 0,
          "one byte at a time (%zu feeds): identical frame and effects", n);
    st_free_buffer(f);
    st_free_buffer(x);
  }
  st_destroy(h);
  st_free_buffer(wf);
  st_free_buffer(wx);
}

/* Every allocation in a representative session fails once; each call must
 * report OK or OUT_OF_MEMORY, never leak, and the engine stays usable. */
static void test_alloc_failures(void) {
  printf("# allocation failure injection\n");
  int runs = 0, leaks = 0, bad_status = 0, injected = 0;
  for (long long fail_at = 1;; fail_at++) {
    Counting c;
    memset(&c, 0, sizeof(c));
    c.fail_at = fail_at;
    GhosttyAllocator a = {.ctx = &c, .vtable = &c_vtable};
    st_handle h = 0;
    st_status s = st_create(ST_ABI_VERSION, 30, 5, 8, 16, 100, 1 << 20, (const struct GhosttyAllocator *)&a, &h);
    runs++;
#define OKOOM(x)                                                                                 \
  do {                                                                                           \
    st_status _s = (x);                                                                          \
    if (_s != ST_OK && _s != ST_ERR_OUT_OF_MEMORY) {                                             \
      bad_status++;                                                                              \
      if (bad_status < 5) printf("   fail_at %lld: %s -> %d\n", fail_at, #x, _s);               \
    }                                                                                            \
  } while (0)
    if (s == ST_OK) {
      static const char in[] =
          "hello \x1b[31mred\x1b[0m\x1b]8;;http://x\x1b\\link\x1b]8;;\x1b\\ \x1b[6n\x1b]2;t\x07\xe4\xb8\x96" "e\xcc\x81\r\n"
          "\x1b]52;c;aGk=\x07more text\r\nand more\r\nline\r\nline\r\nline\r\n";
      OKOOM(st_feed(h, (const uint8_t *)in, sizeof(in) - 1, ST_ORIGIN_LIVE));
      OKOOM(st_select(h, 1, 0, 0, 1, 3));
      uint8_t *b = NULL;
      uint32_t bl = 0;
      s = st_read_viewport(h, 0, &b, &bl, NULL);
      OKOOM(s);
      if (s == ST_OK) {
        View v;
        if (!decode_view(b, bl, &v, NULL)) bad_status++;
        else view_free(&v);
        st_free_buffer(b);
      }
      s = st_selected_text(h, &b, &bl);
      OKOOM(s);
      if (s == ST_OK) st_free_buffer(b);
      OKOOM(st_key(h, 0x04, (const uint8_t *)"a", 1, 0, 0));
      OKOOM(st_paste(h, (const uint8_t *)"pasted", 6, 0));
      s = st_drain_effects(h, &b, &bl);
      OKOOM(s);
      if (s == ST_OK) st_free_buffer(b);
      OKOOM(st_resize(h, 25, 6, 8, 16));
      /* the engine is still usable once allocations succeed again */
      c.fail_at = 0;
      View v;
      if (read_view(h, ST_READ_FORCE_FULL, &v, NULL) != ST_OK || !v.full) bad_status++;
      else view_free(&v);
      st_destroy(h);
    } else if (s != ST_ERR_OUT_OF_MEMORY) {
      bad_status++;
      printf("   fail_at %lld: st_create -> %d\n", fail_at, s);
    }
#undef OKOOM
    if (c.live_allocs != 0 || c.live_bytes != 0) {
      leaks++;
      if (leaks < 5) printf("   fail_at %lld: leaked %lld allocations / %lld bytes\n", fail_at, c.live_allocs, c.live_bytes);
    }
    injected += c.failed > 0;
    if (c.failed == 0) break; /* the Nth allocation was never reached: every one has been failed once */
  }
  CHECK(bad_status == 0 && leaks == 0, "%d runs (%d with an injected failure): only OK/OOM statuses, no leaks", runs, injected);
}

/* Resident set size of this process (Linux), or -1. Ghostty allocates
 * terminal PAGES (the scrollback storage the byte budget accounts) straight
 * from the OS page allocator (mmap/munmap, demand-paged), not from the
 * GhosttyAllocator, so the budget is measured on RSS; the counting allocator
 * measures the remaining heap. */
static long long rss_bytes(void) {
#if defined(__linux__)
  FILE *f = fopen("/proc/self/statm", "r");
  long long size = 0, res = -1;
  if (f) {
    if (fscanf(f, "%lld %lld", &size, &res) != 2) res = -1;
    fclose(f);
  }
  return res < 0 ? -1 : res * 4096LL;
#else
  return -1;
#endif
}

#define MIB (1024LL * 1024LL)
/* Documented bound: resident growth <= history_bytes + 2 MiB. The slack
 * covers the active-area page, one pooled/partially-filled page (a standard
 * page is ~0.5 MiB; pruning is whole-page), grapheme-overflow pages and the
 * non-page heap (render state, pins, nodes). */
#define HISTORY_SLACK (2 * MIB)

typedef struct {
  long long rss0, rss_max, heap_max;
} Measure;

static void measure_sample(Measure *m, Counting *c) {
  long long r = rss_bytes();
  if (r > m->rss_max) m->rss_max = r;
  if (c->live_bytes > m->heap_max) m->heap_max = c->live_bytes;
}

/* History budgets: line-limit eviction (>50k lines), byte budget enforced on
 * whole pages, grapheme-heavy lines. */
static void test_history(void) {
  printf("# history budgets\n");
  char line[256];
  bool have_rss = rss_bytes() > 0;
  /* (a) > 50k lines, line limit binding */
  {
    Counting c;
    memset(&c, 0, sizeof(c));
    GhosttyAllocator a = {.ctx = &c, .vtable = &c_vtable};
    const uint32_t limit = 10000;
    st_handle h = mk_alloc(80, 24, limit, 64u << 20, &a);
    const int N = 60000;
    for (int i = 1; i <= N; i++) {
      int k = snprintf(line, sizeof(line), "L%06d the quick brown fox jumps over the lazy dog\r\n", i);
      st_feed(h, (const uint8_t *)line, (uint32_t)k, ST_ORIGIN_LIVE);
    }
    View v;
    st_scroll_to(h, 0);
    must_view(h, 0, &v);
    int oldest = atoi(row_text(&v, 0) + 1);
    long long H = v.history;
    CHECK(H >= limit - 1500 && H <= limit + 1500, "%d lines, line limit %u: %lld history rows (page-granular: %+lld)", N,
          limit, H, H - (long long)limit);
    CHECK(oldest == N - 22 - H, "oldest retained line L%06d == N-22-historyRows: oldest history evicted first", oldest);
    view_free(&v);
    st_destroy(h);
    CHECK(c.live_allocs == 0, "no leaks");
  }
  /* (b) byte budget binding (line limit far away) */
  const long long budgets[3] = {2 * MIB, 8 * MIB, 32 * MIB};
  for (int bi = 0; bi < 3; bi++) {
    long long budget = budgets[bi];
    Counting c;
    memset(&c, 0, sizeof(c));
    GhosttyAllocator a = {.ctx = &c, .vtable = &c_vtable};
    Measure m = {rss_bytes(), 0, 0};
    st_handle h = mk_alloc(80, 24, 1000000, (uint64_t)budget, &a);
    const int N = bi == 2 ? 120000 : 60000;
    for (int i = 1; i <= N; i++) {
      int k = snprintf(line, sizeof(line), "\x1b[3%dmL%06d\x1b[0m the quick brown fox jumps over the lazy dog %d\r\n",
                       i % 8, i, i * 7);
      st_feed(h, (const uint8_t *)line, (uint32_t)k, ST_ORIGIN_LIVE);
      if (i % 500 == 0) measure_sample(&m, &c);
    }
    View v;
    st_scroll_to(h, 0);
    must_view(h, 0, &v);
    int oldest = atoi(row_text(&v, 0) + 1);
    long long H = v.history;
    view_free(&v);
    CHECK(H > 0 && H < N - 24 && oldest == N - 22 - H,
          "byte budget %lld MiB: %lld history rows kept (%.0f B/row), oldest L%06d, evicted oldest-first", budget / MIB, H,
          (double)budget / (double)H, oldest);
    long long grow = m.rss_max - m.rss0;
    if (have_rss)
      CHECK(grow <= budget + HISTORY_SLACK, "byte budget %lld MiB: resident growth %.2f MiB <= budget + %lld MiB (heap max %.2f MiB)",
            budget / MIB, (double)grow / MIB, HISTORY_SLACK / MIB, (double)m.heap_max / MIB);
    printf("   measure: budget %lld MiB, %d lines: history rows %lld, RSS growth %.2f MiB (%.2f x budget), non-page heap max %lld B\n",
           budget / MIB, N, H, (double)grow / MIB, (double)grow / (double)budget, m.heap_max);
    st_destroy(h);
    CHECK(c.live_allocs == 0, "no leaks");
  }
  /* (c) many combining characters per line (grapheme storage) */
  for (int variant = 0; variant < 2; variant++) {
    Counting c;
    memset(&c, 0, sizeof(c));
    GhosttyAllocator a = {.ctx = &c, .vtable = &c_vtable};
    const long long budget = variant == 0 ? 4 * MIB : 64 * MIB;
    const uint32_t lines = variant == 0 ? 1000000u : 2000u;
    Measure m = {rss_bytes(), 0, 0};
    st_handle h = mk_alloc(80, 24, lines, (uint64_t)budget, &a);
    const int N = 20000;
    char big[4096];
    for (int i = 1; i <= N; i++) {
      int k = snprintf(big, sizeof(big), "C%06d ", i);
      for (int cell = 0; cell < 40; cell++) {
        big[k++] = 'e';
        for (int mk2 = 0; mk2 < 8; mk2++) { /* U+0300..U+0307 */
          big[k++] = (char)0xCC;
          big[k++] = (char)(0x80 + mk2);
        }
      }
      big[k++] = '\r';
      big[k++] = '\n';
      st_feed(h, (const uint8_t *)big, (uint32_t)k, ST_ORIGIN_LIVE);
      if (i % 200 == 0) measure_sample(&m, &c);
    }
    View v;
    st_scroll_to(h, 0);
    must_view(h, 0, &v);
    Row *r0 = find_row(&v, 0);
    int oldest = atoi(row_text(&v, 0) + 1);
    long long H = v.history;
    bool graphemes = r0 && r0->cells[8].text_len == 17;
    view_free(&v);
    CHECK(graphemes && oldest == N - 22 - H && (variant == 0 || (H >= (long long)lines - 1500 && H <= (long long)lines + 1500)),
          "combining fixture (%s): %lld history rows, oldest C%06d evicted in order, 9-codepoint graphemes intact",
          variant == 0 ? "4 MiB byte budget" : "2000-line limit, 64 MiB", H, oldest);
    long long grow = m.rss_max - m.rss0;
    if (have_rss && variant == 0)
      CHECK(grow <= budget + HISTORY_SLACK, "combining fixture: resident growth %.2f MiB <= budget + %lld MiB",
            (double)grow / MIB, HISTORY_SLACK / MIB);
    printf("   measure: combining (%s): history rows %lld, RSS growth %.2f MiB, non-page heap max %lld B\n",
           variant == 0 ? "bytes 4 MiB" : "lines 2000, bytes 64 MiB", H, (double)grow / MIB, m.heap_max);
    st_destroy(h);
    CHECK(c.live_allocs == 0, "no leaks");
  }
  /* (d) history_bytes = 0 disables scrollback */
  st_handle h = mk_alloc(80, 24, 1000, 0, NULL);
  for (int i = 0; i < 100; i++) feed(h, "x\r\n");
  View v;
  must_view(h, 0, &v);
  CHECK(v.history == 0, "history_bytes = 0: no scrollback (%lld)", (long long)v.history);
  view_free(&v);
  st_destroy(h);
  h = mk_alloc(80, 24, 0, 32u << 20, NULL);
  for (int i = 0; i < 3000; i++) feed(h, "x\r\n");
  must_view(h, 0, &v);
  CHECK(v.history == 0, "history_lines = 0: no scrollback either (%lld)", (long long)v.history);
  view_free(&v);
  st_destroy(h);
}

static void test_many_terminals(void) {
  printf("# handle table limit\n");
  static st_handle hs[ST_MAX_TERMINALS];
  uint32_t made = 0;
  st_status s = ST_OK;
  while (made < ST_MAX_TERMINALS) {
    s = st_create(ST_ABI_VERSION, 2, 1, 8, 16, 0, 0, NULL, &hs[made]);
    if (s != ST_OK) break;
    made++;
  }
  st_handle extra = 0;
  CHECK(made == ST_MAX_TERMINALS && st_create(ST_ABI_VERSION, 2, 1, 8, 16, 0, 0, NULL, &extra) == ST_ERR_LIMIT,
        "%u terminals live; one more -> ST_ERR_LIMIT", made);
  for (uint32_t i = 0; i < made; i++) st_destroy(hs[i]);
  CHECK(st_create(ST_ABI_VERSION, 2, 1, 8, 16, 0, 0, NULL, &extra) == ST_OK, "slots reusable after destroy");
  st_destroy(extra);
}

/* ------------------------------------------------------------------ */
/* Golden codec fixtures shared with ViewportCodecTest.kt              */
/* ------------------------------------------------------------------ */

static void golden(const char *name, const uint8_t *buf, uint32_t len) {
  const char *dir = getenv("ST_FIXTURES_DIR");
  if (!dir) {
    CHECK(true, "golden %s: ST_FIXTURES_DIR not set, comparison skipped", name);
    return;
  }
  char path[4096];
  snprintf(path, sizeof(path), "%s/%s.bin", dir, name);
  const char *w = getenv("ST_WRITE_GOLDEN");
  if (w && strcmp(w, "1") == 0) {
    FILE *f = fopen(path, "wb");
    CHECK(f && fwrite(buf, 1, len, f) == len && fclose(f) == 0, "wrote golden %s (%u bytes)", name, len);
    return;
  }
  FILE *f = fopen(path, "rb");
  if (!f) {
    CHECK(false, "golden %s missing (run with ST_WRITE_GOLDEN=1)", path);
    return;
  }
  uint8_t *want = malloc(len + 1);
  size_t got = fread(want, 1, len + 1, f);
  fclose(f);
  CHECK(got == len && memcmp(want, buf, len) == 0, "golden %s.bin byte-identical (%u bytes)", name, len);
  free(want);
}

static void test_golden(void) {
  printf("# golden codec fixtures\n");
  st_handle h = mk(12, 3);
  uint64_t colors[ST_COLOR_COUNT];
  fixture_colors(colors);
  st_colors(h, colors, ST_COLOR_COUNT);
  feed(h, "\x1b[31mred\x1b[0m \x1b[1;4mB\x1b[0m\xe4\xb8\x96\r\n\x1b]8;;https://x.y/z\x1b\\ln\x1b]8;;\x1b\\ e\xcc\x81\x1b[44m \x1b[0m");
  st_select(h, 1, 0, 0, 0, 5);
  uint8_t *b;
  uint32_t l;
  REQUIRE(st_read_viewport(h, ST_READ_FORCE_FULL, &b, &l, NULL) == ST_OK, "read");
  golden("viewport_full", b, l);
  st_free_buffer(b);
  REQUIRE(st_selected_text(h, &b, &l) == ST_OK, "selected text");
  golden("selected_text", b, l);
  View v;
  REQUIRE(read_view(h, 0, &v, NULL) == ST_OK, "read partial base");
  st_acknowledge(h, v.gen);
  view_free(&v);
  st_free_buffer(b);
  feed(h, "\x1b[3;1H\x1b[7mrev\x1b[0m\x1b[?1000h\x1b[?2004h");
  REQUIRE(st_read_viewport(h, 0, &b, &l, NULL) == ST_OK, "read partial");
  View pv;
  REQUIRE(decode_view(b, l, &pv, NULL), "decode partial");
  CHECK(!pv.full && pv.nrows == 2 && find_row(&pv, 1) && find_row(&pv, 2), "golden partial frame carries rows 1-2 only");
  view_free(&pv);
  golden("viewport_partial", b, l);
  st_free_buffer(b);
  drain_discard(h);
  feed(h, "\x1b[6n\x07\x1b]2;title \xc3\xa9\x07\x1b]52;c;aGk=\x07\x1b]52;c;?\x07\x1b]52;c;\x07");
  st_key(h, 0x06, (const uint8_t *)"c", 1, 2, 0);
  REQUIRE(st_drain_effects(h, &b, &l) == ST_OK, "drain");
  golden("effects", b, l);
  st_free_buffer(b);
  REQUIRE(st_drain_effects(h, &b, &l) == ST_OK, "drain empty");
  golden("effects_empty", b, l);
  st_free_buffer(b);
  feed(h, "\x1b[?2026h\x1b[3;5Hheld");
  uint32_t ff = 0;
  REQUIRE(st_read_viewport(h, 0, &b, &l, &ff) == ST_OK, "read held");
  REQUIRE(decode_view(b, l, &pv, NULL), "decode held");
  CHECK(pv.held && (ff & ST_FRAME_HELD) && strcmp(row_text(&pv, 2), "rev") == 0, "golden held frame: held, pre-hold content");
  view_free(&pv);
  golden("viewport_held", b, l);
  st_free_buffer(b);
  st_destroy(h);
}

int main(void) {
  const char *skip = getenv("ST_SKIP_HEAVY");
  bool heavy = !(skip && strcmp(skip, "1") == 0);
  test_handles();
  test_red_cells_and_wide();
  test_effects();
  test_input();
  test_links_selection_scroll();
  test_reset_resize_modes();
  test_generations();
  test_render_hold();
  test_ownership();
  test_framing();
  test_chunk_splits();
  test_alloc_failures();
  test_golden();
  if (heavy) {
    test_history();
    test_many_terminals();
  } else {
    printf("# history budgets + handle-table limit skipped (ST_SKIP_HEAVY=1)\n");
  }
  printf("# %d checks, %d failures\n", g_checks, g_failures);
  if (g_failures) {
    printf("BRIDGE TEST FAILED\n");
    return 1;
  }
  printf("BRIDGE TEST PASSED\n");
  return 0;
}
