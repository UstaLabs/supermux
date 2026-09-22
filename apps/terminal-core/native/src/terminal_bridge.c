/*
 * supermux terminal-core: the st_* ABI v1 implementation over the pinned
 * libghostty-vt. The single implementation of terminal semantics shared by
 * every binding (JNI, cinterop, wasm). See include/supermux_terminal.h for
 * the contract and native/README.md for the wire format, ownership,
 * generation and coordinate rules.
 *
 * Portability: this file is compiled for every native target AND for
 * wasm32-freestanding. It therefore uses no libc at all (no stdio, no
 * malloc, no string.h): only compiler-provided freestanding headers and the
 * Ghostty API. Every byte of memory it owns comes from ghostty_alloc /
 * ghostty_free with the terminal's allocator (NULL = Ghostty's default: libc
 * malloc on native, the module allocator on wasm).
 *
 * Upstream enums and structs are converted field by field with explicit
 * switch/table code; nothing upstream is ever copied onto the wire.
 */
#include "supermux_terminal.h"

#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define GHOSTTY_STATIC 1
#include <ghostty/vt.h>

/* Worst-case full frame: every cell at its maximum encoding (u32 text length
 * + ST_MAX_CELL_TEXT + i32 width + 2 x u64 colours + 2 x i32), every row's
 * index + count, the link budget, and the fixed fields (generation, size,
 * counts, cursor, modes, scroll, selection, held: < 256 bytes). */
_Static_assert((uint64_t)ST_MAX_CELLS * (32u + ST_MAX_CELL_TEXT) + (uint64_t)ST_MAX_DIMENSION * 8u +
                       ST_MAX_LINK_BYTES + 256u <=
                   ST_MAX_PAYLOAD,
               "a worst-case frame must fit the codec payload");

/* ================================================================== */
/* Small freestanding helpers                                          */
/* ================================================================== */

#define ST_UNUSED(x) ((void)(x))

static void st_copy(void *dst, const void *src, size_t n) {
  if (n) __builtin_memcpy(dst, src, n);
}

static bool st_equal(const uint8_t *a, const uint8_t *b, size_t n) {
  for (size_t i = 0; i < n; i++)
    if (a[i] != b[i]) return false;
  return true;
}

/* ================================================================== */
/* Memory: every allocation carries a header so a buffer can be freed  */
/* with only its pointer (st_free_buffer), after its terminal is gone.  */
/* ================================================================== */

#define ST_MEM_MAGIC 0x5354424du /* "STBM" */
#define ST_MEM_HDR 48u           /* header space before the 16-aligned payload */

typedef struct {
  uint8_t *raw;          /* pointer returned by ghostty_alloc */
  size_t raw_len;        /* length passed to ghostty_alloc */
  void *ctx;             /* copy of the GhosttyAllocator (valid iff has_alloc) */
  const GhosttyAllocatorVtable *vtable;
  uint32_t magic;
  uint32_t has_alloc;
} st_mem_hdr;

_Static_assert(sizeof(st_mem_hdr) <= ST_MEM_HDR, "st_mem_hdr too large");

static void *st_mem_alloc(const GhosttyAllocator *a, size_t len) {
  if (len > SIZE_MAX - ST_MEM_HDR - 16u) return NULL;
  size_t raw_len = len + ST_MEM_HDR + 16u;
  uint8_t *raw = ghostty_alloc(a, raw_len);
  if (!raw) return NULL;
  uintptr_t p = ((uintptr_t)raw + ST_MEM_HDR + 15u) & ~(uintptr_t)15u;
  st_mem_hdr *h = (st_mem_hdr *)(p - ST_MEM_HDR);
  h->raw = raw;
  h->raw_len = raw_len;
  h->ctx = a ? a->ctx : NULL;
  h->vtable = a ? a->vtable : NULL;
  h->has_alloc = a ? 1u : 0u;
  h->magic = ST_MEM_MAGIC;
  return (void *)p;
}

static bool st_mem_free(void *p) {
  if (!p) return true;
  if (((uintptr_t)p & 15u) != 0) return false;
  st_mem_hdr *h = (st_mem_hdr *)((uintptr_t)p - ST_MEM_HDR);
  if (h->magic != ST_MEM_MAGIC) return false;
  GhosttyAllocator a = {.ctx = h->ctx, .vtable = h->vtable};
  bool has = h->has_alloc != 0;
  uint8_t *raw = h->raw;
  size_t raw_len = h->raw_len;
  h->magic = 0;
  ghostty_free(has ? &a : NULL, raw, raw_len);
  return true;
}

/* ================================================================== */
/* Byte buffers and the little-endian wire writer                      */
/* ================================================================== */

typedef struct {
  const GhosttyAllocator *alloc;
  uint8_t *data; /* st_mem_alloc payload */
  uint32_t len;
  uint32_t cap;
  uint32_t limit;  /* maximum len */
  st_status err;   /* sticky: first failure */
} st_buf;

static void st_buf_init(st_buf *b, const GhosttyAllocator *a, uint32_t limit) {
  b->alloc = a;
  b->data = NULL;
  b->len = 0;
  b->cap = 0;
  b->limit = limit;
  b->err = ST_OK;
}

static void st_buf_release(st_buf *b) {
  st_mem_free(b->data);
  b->data = NULL;
  b->len = 0;
  b->cap = 0;
}

static bool st_buf_reserve(st_buf *b, uint32_t extra) {
  if (b->err != ST_OK) return false;
  if (extra > b->limit || b->len > b->limit - extra) {
    b->err = ST_ERR_LIMIT;
    return false;
  }
  uint32_t need = b->len + extra;
  if (need <= b->cap) return true;
  uint64_t cap = b->cap ? b->cap : 256u;
  while (cap < need) cap *= 2u;
  if (cap > b->limit) cap = b->limit;
  uint8_t *nd = st_mem_alloc(b->alloc, (size_t)cap);
  if (!nd) {
    b->err = ST_ERR_OUT_OF_MEMORY;
    return false;
  }
  st_copy(nd, b->data, b->len);
  st_mem_free(b->data);
  b->data = nd;
  b->cap = (uint32_t)cap;
  return true;
}

static void st_put_bytes(st_buf *b, const void *src, uint32_t n) {
  if (!st_buf_reserve(b, n)) return;
  st_copy(b->data + b->len, src, n);
  b->len += n;
}

static void st_put_u8(st_buf *b, uint8_t v) { st_put_bytes(b, &v, 1); }

static void st_put_u16(st_buf *b, uint16_t v) {
  uint8_t t[2] = {(uint8_t)v, (uint8_t)(v >> 8)};
  st_put_bytes(b, t, 2);
}

static void st_put_u32(st_buf *b, uint32_t v) {
  uint8_t t[4] = {(uint8_t)v, (uint8_t)(v >> 8), (uint8_t)(v >> 16), (uint8_t)(v >> 24)};
  st_put_bytes(b, t, 4);
}

static void st_put_u64(st_buf *b, uint64_t v) {
  st_put_u32(b, (uint32_t)v);
  st_put_u32(b, (uint32_t)(v >> 32));
}

static void st_put_i32(st_buf *b, int32_t v) { st_put_u32(b, (uint32_t)v); }
static void st_put_i64(st_buf *b, int64_t v) { st_put_u64(b, (uint64_t)v); }
static void st_put_bool(st_buf *b, bool v) { st_put_u8(b, v ? 1u : 0u); }

static void st_patch_u32(st_buf *b, uint32_t off, uint32_t v) {
  if (b->err != ST_OK || off + 4u > b->len) return;
  b->data[off] = (uint8_t)v;
  b->data[off + 1] = (uint8_t)(v >> 8);
  b->data[off + 2] = (uint8_t)(v >> 16);
  b->data[off + 3] = (uint8_t)(v >> 24);
}

/* Length of the UTF-8 sequence starting at s[i] if it is a valid, shortest
 * encoding of a scalar value (no surrogates, <= U+10FFFF); 0 otherwise. */
static uint32_t st_utf8_seq(const uint8_t *s, size_t n, size_t i) {
  uint8_t c = s[i];
  if (c < 0x80) return 1;
  if (c >= 0xC2 && c <= 0xDF) {
    return (i + 1 < n && (s[i + 1] & 0xC0) == 0x80) ? 2 : 0;
  }
  if (c >= 0xE0 && c <= 0xEF) {
    if (i + 2 >= n) return 0;
    uint8_t c1 = s[i + 1], c2 = s[i + 2];
    if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80) return 0;
    if (c == 0xE0 && c1 < 0xA0) return 0; /* overlong */
    if (c == 0xED && c1 >= 0xA0) return 0; /* surrogate */
    return 3;
  }
  if (c >= 0xF0 && c <= 0xF4) {
    if (i + 3 >= n) return 0;
    uint8_t c1 = s[i + 1], c2 = s[i + 2], c3 = s[i + 3];
    if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) return 0;
    if (c == 0xF0 && c1 < 0x90) return 0; /* overlong */
    if (c == 0xF4 && c1 >= 0x90) return 0; /* > U+10FFFF */
    return 4;
  }
  return 0;
}

static bool st_utf8_valid(const uint8_t *s, size_t n) {
  for (size_t i = 0; i < n;) {
    uint32_t k = st_utf8_seq(s, n, i);
    if (!k) return false;
    i += k;
  }
  return true;
}

/* Wire string: u32 byte length + UTF-8, invalid sequences replaced by
 * U+FFFD (one per offending byte) so every wire string is valid UTF-8. */
static void st_put_str(st_buf *b, const uint8_t *s, size_t n) {
  if (!s) n = 0;
  if (n > ST_MAX_PAYLOAD) {
    b->err = b->err ? b->err : ST_ERR_LIMIT;
    return;
  }
  if (st_utf8_valid(s, n)) {
    st_put_u32(b, (uint32_t)n);
    st_put_bytes(b, s, (uint32_t)n);
    return;
  }
  uint64_t out = 0;
  for (size_t i = 0; i < n;) {
    uint32_t k = st_utf8_seq(s, n, i);
    out += k ? k : 3u;
    i += k ? k : 1u;
  }
  if (out > ST_MAX_PAYLOAD) {
    b->err = b->err ? b->err : ST_ERR_LIMIT;
    return;
  }
  st_put_u32(b, (uint32_t)out);
  static const uint8_t repl[3] = {0xEF, 0xBF, 0xBD};
  for (size_t i = 0; i < n;) {
    uint32_t k = st_utf8_seq(s, n, i);
    if (k) {
      st_put_bytes(b, s + i, k);
      i += k;
    } else {
      st_put_bytes(b, repl, 3);
      i += 1;
    }
  }
}

/* Envelope: reserve the 12-byte header, then fill it once the payload is done. */
static void st_env_begin(st_buf *b) {
  st_put_u32(b, ST_CODEC_MAGIC);
  st_put_u16(b, (uint16_t)ST_ABI_VERSION);
  st_put_u16(b, 0);
  st_put_u32(b, 0);
}

static void st_env_finish(st_buf *b, uint16_t kind) {
  if (b->err != ST_OK) return;
  b->data[6] = (uint8_t)kind;
  b->data[7] = (uint8_t)(kind >> 8);
  st_patch_u32(b, 8, b->len - ST_ENVELOPE_HEADER);
}

/* Hand an envelope to the caller (ownership moves to st_free_buffer). */
static st_status st_env_take(st_buf *b, uint8_t **out_buf, uint32_t *out_len) {
  if (b->err != ST_OK) {
    st_status s = b->err;
    st_buf_release(b);
    return s;
  }
  *out_buf = b->data;
  *out_len = b->len;
  b->data = NULL;
  b->len = b->cap = 0;
  return ST_OK;
}

/* ================================================================== */
/* Engine state                                                         */
/* ================================================================== */

#define ST_ENGINE_MAGIC 0x53544547u /* "STEG" */
#define ST_SLOT_BITS 10u
#define ST_SLOT_MASK ((1u << ST_SLOT_BITS) - 1u)
_Static_assert((1u << ST_SLOT_BITS) == ST_MAX_TERMINALS, "slot bits");

enum { ST_SINK_RESPONSE = 0, ST_SINK_INPUT = 1 };

/* Terminal-derived frame metadata captured together with every render-state
 * update, so a frame is internally consistent even while a synchronized-output
 * hold keeps an old render state around. */
typedef struct {
  uint16_t cols, rows;
  uint32_t cell_w, cell_h;
  int64_t history_rows;
  int64_t viewport_top;
  bool alt_screen, mouse_tracking, bracketed_paste;
  bool has_selection;
  bool held; /* captured when a synchronized-output hold began */
  int64_t sel_start_row, sel_end_row;
  int32_t sel_start_col, sel_end_col;
  int32_t cursor_fallback_col, cursor_fallback_row; /* viewport coords, may be off-screen */
  st_buf links;      /* encoded TerminalLink records */
  uint32_t link_count;
} st_snapshot;

typedef struct st_engine {
  uint32_t magic;
  st_handle handle;

  GhosttyAllocator alloc_copy;
  const GhosttyAllocator *alloc; /* NULL or &alloc_copy */

  GhosttyTerminal term;
  GhosttyRenderState rs;
  GhosttyRenderStateRowIterator it;
  GhosttyRenderStateRowCells cells;
  GhosttyKeyEncoder kenc;
  GhosttyKeyEvent kev;
  GhosttyMouseEncoder menc;
  GhosttyMouseEvent mev;

  uint16_t cols, rows;
  uint32_t cell_w, cell_h;
  uint32_t buttons_down; /* bit per MouseButton 1..3 */

  /* Per-call callback context. */
  uint32_t origin; /* ST_ORIGIN_* of the running st_feed (LIVE otherwise) */
  uint32_t sink;   /* where write_pty bytes go */
  st_status call_status; /* worst effect-queue failure in the running call */

  /* Effect queue: records in wire format (tag + fields), count separate. */
  st_buf queue;
  uint32_t queue_count;
  uint32_t coalesce_tag;     /* 0 = none */
  uint32_t coalesce_len_off; /* offset of the u32 length of the open record */

  /* Generations (see README "Generations"). */
  int64_t gen;       /* bumped after every mutation, and at a hold capture */
  int64_t rs_gen;    /* engine generation the render state reflects */
  int64_t ser_gen;   /* generation of the last serialized frame (0 = none) */
  bool ser_valid;    /* render state untouched since that frame was serialized */
  bool ser_full;
  uint64_t full_epoch;      /* bumped by every event that requires a full frame */
  uint64_t full_done_epoch; /* epoch covered by an acknowledged full frame */
  uint64_t ser_epoch;
  uint64_t rs_epoch;        /* full_epoch when the render state was captured */

  bool held; /* synchronized-output hold active; render state frozen */

  st_snapshot snap;

  /* Scratch buffers (UTF-8 text, URIs, encoder output). */
  uint8_t *scratch;
  uint32_t scratch_cap;
  uint8_t *scratch2;
  uint32_t scratch2_cap;
} st_engine;

/* Handle table. A slot holds the engine pointer and, separately, the handle
 * currently issued for it. Lookups compare the handle ATOMICALLY before ever
 * dereferencing the engine, so a stale handle (destroyed, slot reused by
 * another thread's terminal) is rejected without touching memory another
 * thread may be freeing. Only a use racing the destroy of the SAME handle is
 * undefined (documented: callers serialize per handle). */
static _Atomic uintptr_t g_slots[ST_MAX_TERMINALS];
static _Atomic uint32_t g_slot_handle[ST_MAX_TERMINALS];
static _Atomic uint32_t g_slot_gen[ST_MAX_TERMINALS];
#define ST_SLOT_RESERVED ((uintptr_t)1)

static st_engine *st_lookup(st_handle h) {
  if (h == 0) return NULL;
  uint32_t idx = h & ST_SLOT_MASK;
  if (atomic_load_explicit(&g_slot_handle[idx], memory_order_acquire) != h) return NULL;
  uintptr_t p = atomic_load_explicit(&g_slots[idx], memory_order_acquire);
  if (p <= ST_SLOT_RESERVED) return NULL;
  st_engine *e = (st_engine *)p;
  if (e->magic != ST_ENGINE_MAGIC || e->handle != h) return NULL;
  return e;
}

static bool st_scratch(st_engine *e, uint8_t **buf, uint32_t *cap, size_t need) {
  if (need <= *cap) return true;
  if (need > ST_MAX_PAYLOAD) return false;
  uint32_t ncap = *cap ? *cap : 64u;
  while (ncap < need) ncap *= 2u;
  uint8_t *nb = st_mem_alloc(e->alloc, ncap);
  if (!nb) return false;
  st_mem_free(*buf);
  *buf = nb;
  *cap = ncap;
  return true;
}

/* ================================================================== */
/* Effect queue                                                         */
/* ================================================================== */

/* Start of every st_* entry point that can queue effects. */
static void st_begin_call(st_engine *e, uint32_t origin, uint32_t sink) {
  e->origin = origin;
  e->sink = sink;
  e->call_status = ST_OK;
  e->coalesce_tag = 0;
}

static void st_note_failure(st_engine *e, st_status s) {
  if (e->call_status == ST_OK) e->call_status = s;
}

/* Append a record atomically: either the whole record lands or the queue is
 * unchanged (and the failure is reported by the running call). */
typedef struct {
  uint32_t len;
  uint32_t count;
  uint32_t coalesce_tag;
} st_queue_mark;

static st_queue_mark st_queue_mark_now(st_engine *e) {
  return (st_queue_mark){e->queue.len, e->queue_count, e->coalesce_tag};
}

static void st_queue_commit_or_rollback(st_engine *e, st_queue_mark m) {
  if (e->queue.err == ST_OK) return;
  st_note_failure(e, e->queue.err);
  e->queue.err = ST_OK;
  e->queue.len = m.len;
  e->queue_count = m.count;
  e->coalesce_tag = m.coalesce_tag; /* the queue is exactly as before the attempt */
}

/* Response / Input bytes. Consecutive chunks of the same kind within one
 * st_* call are merged into one effect (Ghostty may emit a single reply in
 * several write_pty chunks). */
static void st_queue_bytes(st_engine *e, uint32_t tag, const uint8_t *data, size_t len) {
  if (len == 0) return;
  st_queue_mark m = st_queue_mark_now(e);
  if (len > ST_MAX_PAYLOAD) {
    st_note_failure(e, ST_ERR_LIMIT);
    return;
  }
  if (e->coalesce_tag == tag) {
    uint32_t off = e->coalesce_len_off;
    uint32_t old = (uint32_t)e->queue.data[off] | ((uint32_t)e->queue.data[off + 1] << 8) |
                   ((uint32_t)e->queue.data[off + 2] << 16) | ((uint32_t)e->queue.data[off + 3] << 24);
    st_put_bytes(&e->queue, data, (uint32_t)len);
    if (e->queue.err == ST_OK) st_patch_u32(&e->queue, off, old + (uint32_t)len);
    st_queue_commit_or_rollback(e, m);
    return;
  }
  st_put_u8(&e->queue, (uint8_t)tag);
  uint32_t off = e->queue.len;
  st_put_u32(&e->queue, (uint32_t)len);
  st_put_bytes(&e->queue, data, (uint32_t)len);
  if (e->queue.err == ST_OK) {
    e->queue_count++;
    e->coalesce_tag = tag;
    e->coalesce_len_off = off;
  }
  st_queue_commit_or_rollback(e, m);
}

static void st_queue_title(st_engine *e, const uint8_t *s, size_t n) {
  st_queue_mark m = st_queue_mark_now(e);
  e->coalesce_tag = 0;
  st_put_u8(&e->queue, ST_EFFECT_TITLE);
  st_put_str(&e->queue, s, n);
  if (e->queue.err == ST_OK) e->queue_count++;
  st_queue_commit_or_rollback(e, m);
}

static void st_queue_bell(st_engine *e) {
  st_queue_mark m = st_queue_mark_now(e);
  e->coalesce_tag = 0;
  st_put_u8(&e->queue, ST_EFFECT_BELL);
  if (e->queue.err == ST_OK) e->queue_count++;
  st_queue_commit_or_rollback(e, m);
}

static bool st_queue_clipboard(st_engine *e, bool write, const uint8_t *text, size_t n, bool has_text) {
  st_queue_mark m = st_queue_mark_now(e);
  e->coalesce_tag = 0;
  st_put_u8(&e->queue, ST_EFFECT_CLIPBOARD);
  st_put_bool(&e->queue, write);
  st_put_bool(&e->queue, has_text);
  if (has_text) st_put_str(&e->queue, text, n);
  bool ok = e->queue.err == ST_OK;
  if (ok) e->queue_count++;
  st_queue_commit_or_rollback(e, m);
  return ok;
}

/* ================================================================== */
/* Ghostty callbacks (run synchronously inside Ghostty calls)          */
/* ================================================================== */

static void st_capture(st_engine *e);

static void cb_write_pty(GhosttyTerminal t, void *ud, const uint8_t *data, size_t len) {
  ST_UNUSED(t);
  st_engine *e = ud;
  if (e->sink == ST_SINK_INPUT) {
    st_queue_bytes(e, ST_EFFECT_INPUT, data, len);
  } else if (e->origin == ST_ORIGIN_LIVE) {
    st_queue_bytes(e, ST_EFFECT_RESPONSE, data, len);
  }
}

static void cb_bell(GhosttyTerminal t, void *ud) {
  ST_UNUSED(t);
  st_engine *e = ud;
  if (e->origin == ST_ORIGIN_LIVE) st_queue_bell(e);
}

static void cb_title(GhosttyTerminal t, void *ud) {
  st_engine *e = ud;
  GhosttyString title = {0};
  if (ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_TITLE, &title) != GHOSTTY_SUCCESS) return;
  st_queue_title(e, title.ptr, title.len);
}

static bool st_mime_is_text(GhosttyString mime) {
  static const uint8_t prefix[] = {'t', 'e', 'x', 't', '/'};
  return mime.ptr && mime.len >= sizeof(prefix) && st_equal(mime.ptr, prefix, sizeof(prefix));
}

static bool st_mime_is_plain(GhosttyString mime) {
  static const uint8_t plain[] = {'t', 'e', 'x', 't', '/', 'p', 'l', 'a', 'i', 'n'};
  return mime.ptr && mime.len >= sizeof(plain) && st_equal(mime.ptr, plain, sizeof(plain));
}

static void st_clipboard_reply(const GhosttyClipboardWrite *w, GhosttyClipboardWriteResult r) {
  if (!w->reply) return;
  GhosttyClipboardWriteReply reply = GHOSTTY_INIT_SIZED(GhosttyClipboardWriteReply);
  reply.result = r;
  reply.remember = false;
  w->reply(w, &reply);
}

/* OSC 52 / 1337 / 5522 writes (already decoded by Ghostty). Queued as
 * ClipboardRequest(write=true, text) for LIVE output only; the text is the
 * first text/plain representation (else the first text/ one). */
static void cb_clipboard_write(GhosttyTerminal t, void *ud, const GhosttyClipboardWrite *w) {
  ST_UNUSED(t);
  st_engine *e = ud;
  if (e->origin != ST_ORIGIN_LIVE) {
    st_clipboard_reply(w, GHOSTTY_CLIPBOARD_WRITE_RESULT_DENIED);
    return;
  }
  if (w->contents_len == 0) {
    bool ok = st_queue_clipboard(e, true, NULL, 0, false);
    st_clipboard_reply(w, ok ? GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS : GHOSTTY_CLIPBOARD_WRITE_RESULT_BUSY);
    return;
  }
  const GhosttyClipboardContent *pick = NULL;
  for (size_t i = 0; i < w->contents_len && !pick; i++)
    if (st_mime_is_plain(w->contents[i].mime)) pick = &w->contents[i];
  for (size_t i = 0; i < w->contents_len && !pick; i++)
    if (st_mime_is_text(w->contents[i].mime)) pick = &w->contents[i];
  if (!pick) {
    st_clipboard_reply(w, GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED);
    return;
  }
  bool ok = st_queue_clipboard(e, true, pick->data.ptr, pick->data.len, true);
  st_clipboard_reply(w, ok ? GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS : GHOSTTY_CLIPBOARD_WRITE_RESULT_BUSY);
}

/* OSC 52 "?" reads must be answered synchronously; the engine cannot wait
 * for the embedder, so it always denies (the program sees an empty
 * clipboard, as with xterm's disallowed reads) and reports the attempt as
 * ClipboardRequest(write=false) for LIVE output. */
static void cb_clipboard_read(GhosttyTerminal t, void *ud, const GhosttyClipboardRead *r) {
  ST_UNUSED(t);
  st_engine *e = ud;
  if (e->origin == ST_ORIGIN_LIVE) st_queue_clipboard(e, false, NULL, 0, false);
  if (r->reply) {
    GhosttyClipboardReadReply reply = GHOSTTY_INIT_SIZED(GhosttyClipboardReadReply);
    reply.result = GHOSTTY_CLIPBOARD_READ_RESULT_DENIED;
    r->reply(r, &reply);
  }
}

static void cb_render_hold(GhosttyTerminal t, void *ud, bool held) {
  ST_UNUSED(t);
  st_engine *e = ud;
  if (held) {
    st_capture(e); /* sets e->held on success */
  } else {
    e->held = false;
  }
}

static bool cb_size(GhosttyTerminal t, void *ud, GhosttySizeReportSize *out) {
  ST_UNUSED(t);
  st_engine *e = ud;
  out->rows = e->rows;
  out->columns = e->cols;
  out->cell_width = e->cell_w;
  out->cell_height = e->cell_h;
  return true;
}

/* DA1: VT220-class (level 2) with ANSI colour; DA2: VT220 firmware 0. */
static bool cb_device_attributes(GhosttyTerminal t, void *ud, GhosttyDeviceAttributes *out) {
  ST_UNUSED(t);
  ST_UNUSED(ud);
  out->primary.conformance_level = GHOSTTY_DA_CONFORMANCE_LEVEL_2;
  out->primary.features[0] = GHOSTTY_DA_FEATURE_ANSI_COLOR;
  out->primary.num_features = 1;
  out->secondary.device_type = GHOSTTY_DA_DEVICE_TYPE_VT220;
  out->secondary.firmware_version = 0;
  out->secondary.rom_cartridge = 0;
  out->tertiary.unit_id = 0;
  return true;
}

/* ================================================================== */
/* Snapshot capture (render state + consistent metadata)               */
/* ================================================================== */

static bool st_mode(st_engine *e, GhosttyMode mode) {
  GhosttyTerminalModeConfig cfg = {.mode = mode, .value = false};
  if (ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_MODE, &cfg) != GHOSTTY_SUCCESS) return false;
  return cfg.value;
}

static st_status st_map_result(GhosttyResult r) {
  switch (r) {
    case GHOSTTY_SUCCESS: return ST_OK;
    case GHOSTTY_OUT_OF_MEMORY: return ST_ERR_OUT_OF_MEMORY;
    case GHOSTTY_INVALID_VALUE: return ST_ERR_INVALID_ARGUMENT;
    case GHOSTTY_LIMIT_EXCEEDED: return ST_ERR_LIMIT;
    case GHOSTTY_REJECTED: return ST_ERR_REJECTED;
    default: return ST_ERR_INTERNAL;
  }
}

/* Viewport cell (x, y) hyperlink URI into scratch2. Returns false on
 * allocation failure; *len = 0 when the cell has no link. */
static bool st_cell_uri(st_engine *e, uint16_t x, uint16_t y, uint32_t *len, bool *spacer_tail) {
  *len = 0;
  *spacer_tail = false;
  GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_VIEWPORT, .value = {.coordinate = {.x = x, .y = y}}};
  GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  if (ghostty_terminal_grid_ref(e->term, pt, &ref) != GHOSTTY_SUCCESS) return true;
  GhosttyCell cell = 0;
  if (ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS) return true;
  GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
  if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide) == GHOSTTY_SUCCESS)
    *spacer_tail = wide == GHOSTTY_CELL_WIDE_SPACER_TAIL;
  bool has = false;
  if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &has) != GHOSTTY_SUCCESS || !has) return true;
  size_t n = 0;
  GhosttyResult r = ghostty_grid_ref_hyperlink_uri(&ref, e->scratch2, e->scratch2_cap, &n);
  if (r == GHOSTTY_OUT_OF_SPACE) {
    if (!st_scratch(e, &e->scratch2, &e->scratch2_cap, n)) return false;
    r = ghostty_grid_ref_hyperlink_uri(&ref, e->scratch2, e->scratch2_cap, &n);
  }
  if (r != GHOSTTY_SUCCESS) return true;
  *len = (uint32_t)n;
  return true;
}

/* Links beyond the ST_MAX_LINK_BYTES frame budget are omitted (documented),
 * so a frame always fits the payload. Returns whether the link was kept. */
static bool st_emit_link(st_engine *e, st_buf *links, uint16_t y, int32_t first, int32_t last,
                         const uint8_t *uri, uint32_t uri_len) {
  ST_UNUSED(e);
  if ((uint64_t)links->len + 16u + (st_utf8_valid(uri, uri_len) ? uri_len : 3u * (uint64_t)uri_len) >
      ST_MAX_LINK_BYTES)
    return false;
  st_put_i32(links, y);
  st_put_i32(links, first);
  st_put_i32(links, last);
  st_put_str(links, uri, uri_len);
  return true;
}

/* OSC 8 spans on every viewport row: runs of cells with the same URI
 * (spacer tails of wide characters extend the run to their left). */
static st_status st_compute_links(st_engine *e, st_buf *links, uint32_t *count) {
  *count = 0;
  uint8_t *cur = NULL; /* current span URI (copy) */
  uint32_t cur_len = 0, cur_cap = 0;
  st_status status = ST_OK;
  for (uint16_t y = 0; y < e->snap.rows && status == ST_OK; y++) {
    GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_VIEWPORT, .value = {.coordinate = {.x = 0, .y = y}}};
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(e->term, pt, &ref) != GHOSTTY_SUCCESS) continue;
    GhosttyRow row = 0;
    bool row_has_link = false;
    if (ghostty_grid_ref_row(&ref, &row) != GHOSTTY_SUCCESS) continue;
    if (ghostty_row_get(row, GHOSTTY_ROW_DATA_HYPERLINK, &row_has_link) != GHOSTTY_SUCCESS || !row_has_link)
      continue;
    int32_t first = -1, last = -1;
    for (uint16_t x = 0; x < e->snap.cols; x++) {
      uint32_t n = 0;
      bool tail = false;
      if (!st_cell_uri(e, x, y, &n, &tail)) {
        status = ST_ERR_OUT_OF_MEMORY;
        break;
      }
      if (n == 0 && tail && first >= 0 && last == (int32_t)x - 1) {
        last = x;
        continue;
      }
      bool same = first >= 0 && n == cur_len && n > 0 && st_equal(e->scratch2, cur, n);
      if (same) {
        last = x;
        continue;
      }
      if (first >= 0) {
        if (st_emit_link(e, links, y, first, last, cur, cur_len)) (*count)++;
        first = -1;
      }
      if (n > 0) {
        if (!st_scratch(e, &cur, &cur_cap, n)) {
          status = ST_ERR_OUT_OF_MEMORY;
          break;
        }
        st_copy(cur, e->scratch2, n);
        cur_len = n;
        first = last = x;
      }
    }
    if (status == ST_OK && first >= 0 && st_emit_link(e, links, y, first, last, cur, cur_len)) (*count)++;
  }
  st_mem_free(cur);
  if (status == ST_OK && links->err != ST_OK) status = links->err;
  return status;
}

/* Everything the frame needs from the terminal (not the render state),
 * captured right after a render-state update. */
static st_status st_capture_meta(st_engine *e, bool held) {
  st_snapshot *s = &e->snap;
  s->held = held;
  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(e->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(e->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  s->cols = cols;
  s->rows = rows;
  s->cell_w = e->cell_w;
  s->cell_h = e->cell_h;

  GhosttyTerminalScrollbar bar = {0, 0, 0};
  ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);
  s->history_rows = bar.total >= bar.len ? (int64_t)(bar.total - bar.len) : 0;
  s->viewport_top = (int64_t)bar.offset;

  GhosttyTerminalScreen scr = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &scr);
  s->alt_screen = scr == GHOSTTY_TERMINAL_SCREEN_ALTERNATE;
  bool tracking = false;
  ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
  s->mouse_tracking = tracking;
  s->bracketed_paste = st_mode(e, GHOSTTY_MODE_BRACKETED_PASTE);

  uint16_t cx = 0, cy = 0;
  ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_CURSOR_X, &cx);
  ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &cy);
  s->cursor_fallback_col = cx;
  s->cursor_fallback_row = (int32_t)(s->history_rows + (int64_t)cy - s->viewport_top);

  s->has_selection = false;
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) == GHOSTTY_SUCCESS) {
    GhosttyPointCoordinate a = {0, 0}, b = {0, 0};
    if (ghostty_terminal_point_from_grid_ref(e->term, &sel.start, GHOSTTY_POINT_TAG_SCREEN, &a) == GHOSTTY_SUCCESS &&
        ghostty_terminal_point_from_grid_ref(e->term, &sel.end, GHOSTTY_POINT_TAG_SCREEN, &b) == GHOSTTY_SUCCESS) {
      s->has_selection = true;
      s->sel_start_row = a.y;
      s->sel_start_col = a.x;
      s->sel_end_row = b.y;
      s->sel_end_col = b.x;
    }
  }

  s->links.len = 0;
  s->links.err = ST_OK;
  return st_compute_links(e, &s->links, &s->link_count);
}

/* Update the render state from the terminal and capture the metadata. On
 * failure the frame state is uncertain: force the next frame full. */
static st_status st_update(st_engine *e, bool held) {
  GhosttyResult r = ghostty_render_state_update(e->rs, e->term);
  e->rs_gen = e->gen;
  e->rs_epoch = e->full_epoch;
  e->ser_valid = false;
  if (r != GHOSTTY_SUCCESS) {
    e->full_epoch++;
    return st_map_result(r);
  }
  st_status s = st_capture_meta(e, held);
  if (s != ST_OK) e->full_epoch++;
  return s;
}

/* Synchronized output began: capture the frame the program wants shown. If
 * that fails the hold is ignored (always allowed) and frames stay live. */
static void st_capture(st_engine *e) {
  e->gen++;
  if (st_update(e, true) == ST_OK) {
    e->held = true;
  } else {
    e->held = false;
    st_note_failure(e, ST_ERR_OUT_OF_MEMORY);
  }
}

/* ================================================================== */
/* Enum conversion (package constants -> pinned Ghostty)                */
/* ================================================================== */

static int32_t st_cursor_shape(GhosttyRenderStateCursorVisualStyle s) {
  switch (s) {
    case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK: return 0;        /* CursorShape.BLOCK */
    case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR: return 1;          /* CursorShape.BAR */
    case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE: return 2;    /* CursorShape.UNDERLINE */
    case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK_HOLLOW: return 3; /* CursorShape.BLOCK_HOLLOW */
    default: return 0;
  }
}

static int32_t st_underline(int u) {
  switch (u) {
    case GHOSTTY_SGR_UNDERLINE_NONE: return 0;
    case GHOSTTY_SGR_UNDERLINE_SINGLE: return 1;
    case GHOSTTY_SGR_UNDERLINE_DOUBLE: return 2;
    case GHOSTTY_SGR_UNDERLINE_CURLY: return 3;
    case GHOSTTY_SGR_UNDERLINE_DOTTED: return 4;
    case GHOSTTY_SGR_UNDERLINE_DASHED: return 5;
    default: return 1; /* an unknown underline kind is still an underline */
  }
}

static int32_t st_cell_flags(const GhosttyStyle *s) {
  int32_t f = 0;
  if (s->bold) f |= 1 << 0;
  if (s->italic) f |= 1 << 1;
  if (s->faint) f |= 1 << 2;
  if (s->blink) f |= 1 << 3;
  if (s->inverse) f |= 1 << 4;
  if (s->invisible) f |= 1 << 5;
  if (s->strikethrough) f |= 1 << 6;
  if (s->overline) f |= 1 << 7;
  return f;
}

static int32_t st_cell_width(GhosttyCellWide w) {
  switch (w) {
    case GHOSTTY_CELL_WIDE_NARROW: return 1;
    case GHOSTTY_CELL_WIDE_WIDE: return 2;
    case GHOSTTY_CELL_WIDE_SPACER_TAIL: return 0;
    case GHOSTTY_CELL_WIDE_SPACER_HEAD: return 0;
    default: return 1;
  }
}

#define ST_COLOR_DEFAULT ((uint64_t)1 << 32)

static uint64_t st_rgba(GhosttyColorRgb c) {
  return ((uint64_t)c.r << 24) | ((uint64_t)c.g << 16) | ((uint64_t)c.b << 8) | 0xFFu;
}

/* Package modifiers (Modifiers.kt) -> GhosttyMods, bit by bit. */
static GhosttyMods st_mods(uint32_t m) {
  GhosttyMods g = 0;
  if (m & (1u << 0)) g |= GHOSTTY_MODS_SHIFT;
  if (m & (1u << 1)) g |= GHOSTTY_MODS_CTRL;
  if (m & (1u << 2)) g |= GHOSTTY_MODS_ALT;
  if (m & (1u << 3)) g |= GHOSTTY_MODS_SUPER;
  if (m & (1u << 4)) g |= GHOSTTY_MODS_CAPS_LOCK;
  if (m & (1u << 5)) g |= GHOSTTY_MODS_NUM_LOCK;
  return g;
}

#define ST_MODS_MASK 0x3Fu

typedef struct {
  uint16_t hid;
  GhosttyKey key;
  uint8_t unshifted; /* US-layout base character, 0 if none */
} st_key_map;

/* TerminalKeys (USB HID usage ids, page 0x07) -> GhosttyKey. */
static const st_key_map ST_KEYS[] = {
    {0x04, GHOSTTY_KEY_A, 'a'}, {0x05, GHOSTTY_KEY_B, 'b'}, {0x06, GHOSTTY_KEY_C, 'c'},
    {0x07, GHOSTTY_KEY_D, 'd'}, {0x08, GHOSTTY_KEY_E, 'e'}, {0x09, GHOSTTY_KEY_F, 'f'},
    {0x0A, GHOSTTY_KEY_G, 'g'}, {0x0B, GHOSTTY_KEY_H, 'h'}, {0x0C, GHOSTTY_KEY_I, 'i'},
    {0x0D, GHOSTTY_KEY_J, 'j'}, {0x0E, GHOSTTY_KEY_K, 'k'}, {0x0F, GHOSTTY_KEY_L, 'l'},
    {0x10, GHOSTTY_KEY_M, 'm'}, {0x11, GHOSTTY_KEY_N, 'n'}, {0x12, GHOSTTY_KEY_O, 'o'},
    {0x13, GHOSTTY_KEY_P, 'p'}, {0x14, GHOSTTY_KEY_Q, 'q'}, {0x15, GHOSTTY_KEY_R, 'r'},
    {0x16, GHOSTTY_KEY_S, 's'}, {0x17, GHOSTTY_KEY_T, 't'}, {0x18, GHOSTTY_KEY_U, 'u'},
    {0x19, GHOSTTY_KEY_V, 'v'}, {0x1A, GHOSTTY_KEY_W, 'w'}, {0x1B, GHOSTTY_KEY_X, 'x'},
    {0x1C, GHOSTTY_KEY_Y, 'y'}, {0x1D, GHOSTTY_KEY_Z, 'z'},
    {0x1E, GHOSTTY_KEY_DIGIT_1, '1'}, {0x1F, GHOSTTY_KEY_DIGIT_2, '2'}, {0x20, GHOSTTY_KEY_DIGIT_3, '3'},
    {0x21, GHOSTTY_KEY_DIGIT_4, '4'}, {0x22, GHOSTTY_KEY_DIGIT_5, '5'}, {0x23, GHOSTTY_KEY_DIGIT_6, '6'},
    {0x24, GHOSTTY_KEY_DIGIT_7, '7'}, {0x25, GHOSTTY_KEY_DIGIT_8, '8'}, {0x26, GHOSTTY_KEY_DIGIT_9, '9'},
    {0x27, GHOSTTY_KEY_DIGIT_0, '0'},
    {0x28, GHOSTTY_KEY_ENTER, 0}, {0x29, GHOSTTY_KEY_ESCAPE, 0}, {0x2A, GHOSTTY_KEY_BACKSPACE, 0},
    {0x2B, GHOSTTY_KEY_TAB, 0}, {0x2C, GHOSTTY_KEY_SPACE, ' '},
    {0x2D, GHOSTTY_KEY_MINUS, '-'}, {0x2E, GHOSTTY_KEY_EQUAL, '='}, {0x2F, GHOSTTY_KEY_BRACKET_LEFT, '['},
    {0x30, GHOSTTY_KEY_BRACKET_RIGHT, ']'}, {0x31, GHOSTTY_KEY_BACKSLASH, '\\'},
    {0x33, GHOSTTY_KEY_SEMICOLON, ';'}, {0x34, GHOSTTY_KEY_QUOTE, '\''}, {0x35, GHOSTTY_KEY_BACKQUOTE, '`'},
    {0x36, GHOSTTY_KEY_COMMA, ','}, {0x37, GHOSTTY_KEY_PERIOD, '.'}, {0x38, GHOSTTY_KEY_SLASH, '/'},
    {0x3A, GHOSTTY_KEY_F1, 0}, {0x3B, GHOSTTY_KEY_F2, 0}, {0x3C, GHOSTTY_KEY_F3, 0}, {0x3D, GHOSTTY_KEY_F4, 0},
    {0x3E, GHOSTTY_KEY_F5, 0}, {0x3F, GHOSTTY_KEY_F6, 0}, {0x40, GHOSTTY_KEY_F7, 0}, {0x41, GHOSTTY_KEY_F8, 0},
    {0x42, GHOSTTY_KEY_F9, 0}, {0x43, GHOSTTY_KEY_F10, 0}, {0x44, GHOSTTY_KEY_F11, 0}, {0x45, GHOSTTY_KEY_F12, 0},
    {0x49, GHOSTTY_KEY_INSERT, 0}, {0x4A, GHOSTTY_KEY_HOME, 0}, {0x4B, GHOSTTY_KEY_PAGE_UP, 0},
    {0x4C, GHOSTTY_KEY_DELETE, 0}, {0x4D, GHOSTTY_KEY_END, 0}, {0x4E, GHOSTTY_KEY_PAGE_DOWN, 0},
    {0x4F, GHOSTTY_KEY_ARROW_RIGHT, 0}, {0x50, GHOSTTY_KEY_ARROW_LEFT, 0}, {0x51, GHOSTTY_KEY_ARROW_DOWN, 0},
    {0x52, GHOSTTY_KEY_ARROW_UP, 0},
};

static const st_key_map *st_find_key(uint32_t hid) {
  for (size_t i = 0; i < sizeof(ST_KEYS) / sizeof(ST_KEYS[0]); i++)
    if (ST_KEYS[i].hid == hid) return &ST_KEYS[i];
  return NULL;
}

/* ================================================================== */
/* Lifecycle                                                            */
/* ================================================================== */

uint32_t st_abi_version(void) { return ST_ABI_VERSION; }

static bool st_valid_size(uint32_t cols, uint32_t rows, uint32_t cw, uint32_t ch) {
  return cols >= 1 && cols <= ST_MAX_DIMENSION && rows >= 1 && rows <= ST_MAX_DIMENSION &&
         (uint64_t)cols * rows <= ST_MAX_CELLS && cw >= 1 && cw <= 0xFFFFu && ch >= 1 && ch <= 0xFFFFu;
}

static void st_engine_free(st_engine *e) {
  if (!e) return;
  ghostty_mouse_event_free(e->mev);
  ghostty_mouse_encoder_free(e->menc);
  ghostty_key_event_free(e->kev);
  ghostty_key_encoder_free(e->kenc);
  ghostty_render_state_row_cells_free(e->cells);
  ghostty_render_state_row_iterator_free(e->it);
  ghostty_render_state_free(e->rs);
  ghostty_terminal_free(e->term);
  st_buf_release(&e->queue);
  st_buf_release(&e->snap.links);
  st_mem_free(e->scratch);
  st_mem_free(e->scratch2);
  e->magic = 0;
  e->handle = 0;
  st_mem_free(e);
}

static st_status st_set(st_engine *e, GhosttyTerminalOption opt, const void *value) {
  return st_map_result(ghostty_terminal_set(e->term, opt, value));
}

st_status st_create(uint32_t abi_version, uint32_t columns, uint32_t rows, uint32_t cell_width_px,
                    uint32_t cell_height_px, uint32_t history_lines, uint64_t history_bytes,
                    const struct GhosttyAllocator *allocator, st_handle *out_handle) {
  if (abi_version != ST_ABI_VERSION) return ST_ERR_ABI_MISMATCH;
  if (!out_handle || !st_valid_size(columns, rows, cell_width_px, cell_height_px)) return ST_ERR_INVALID_ARGUMENT;
  if (allocator && (!allocator->vtable || !allocator->vtable->alloc || !allocator->vtable->free ||
                    !allocator->vtable->resize || !allocator->vtable->remap))
    return ST_ERR_INVALID_ARGUMENT;

  /* Claim a slot first so a full table fails before any allocation. */
  uint32_t idx = ST_MAX_TERMINALS;
  for (uint32_t i = 0; i < ST_MAX_TERMINALS; i++) {
    uintptr_t expected = 0;
    if (atomic_compare_exchange_strong(&g_slots[i], &expected, ST_SLOT_RESERVED)) {
      idx = i;
      break;
    }
  }
  if (idx == ST_MAX_TERMINALS) return ST_ERR_LIMIT;

  st_engine *e = st_mem_alloc(allocator, sizeof(st_engine));
  if (!e) {
    atomic_store(&g_slots[idx], 0);
    return ST_ERR_OUT_OF_MEMORY;
  }
  __builtin_memset(e, 0, sizeof(*e));
  if (allocator) {
    e->alloc_copy = *allocator;
    e->alloc = &e->alloc_copy;
  }
  e->cols = (uint16_t)columns;
  e->rows = (uint16_t)rows;
  e->cell_w = cell_width_px;
  e->cell_h = cell_height_px;
  st_buf_init(&e->queue, e->alloc, ST_MAX_PAYLOAD - 4u);
  st_buf_init(&e->snap.links, e->alloc, ST_MAX_PAYLOAD);
  e->gen = 1;
  e->full_epoch = 1;
  e->full_done_epoch = 0;

  st_status s = ST_OK;
  GhosttyResult r = ghostty_terminal_new(e->alloc, &e->term, (uint16_t)columns, (uint16_t)rows);
  if (r == GHOSTTY_SUCCESS) r = ghostty_terminal_resize(e->term, (uint16_t)columns, (uint16_t)rows, cell_width_px, cell_height_px);
  if (r == GHOSTTY_SUCCESS) r = ghostty_render_state_new(e->alloc, &e->rs);
  if (r == GHOSTTY_SUCCESS) r = ghostty_render_state_row_iterator_new(e->alloc, &e->it);
  if (r == GHOSTTY_SUCCESS) r = ghostty_render_state_row_cells_new(e->alloc, &e->cells);
  if (r == GHOSTTY_SUCCESS) r = ghostty_key_encoder_new(e->alloc, &e->kenc);
  if (r == GHOSTTY_SUCCESS) r = ghostty_key_event_new(e->alloc, &e->kev);
  if (r == GHOSTTY_SUCCESS) r = ghostty_mouse_encoder_new(e->alloc, &e->menc);
  if (r == GHOSTTY_SUCCESS) r = ghostty_mouse_event_new(e->alloc, &e->mev);
  s = st_map_result(r);

  if (s == ST_OK) {
    size_t lines = history_lines;
    size_t bytes = history_bytes > (uint64_t)SIZE_MAX ? SIZE_MAX : (size_t)history_bytes;
    /* Ghostty's line limit always keeps at least one page of rows, so "no
     * scrollback" is expressed through the byte limit (0 = none). */
    if (history_lines == 0) bytes = 0;
    uint64_t kitty_limit = 0; /* no image storage: the package renders no images */
    bool track_last_cell = true;
    ghostty_mouse_encoder_setopt(e->menc, GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL, &track_last_cell);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_USERDATA, e);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)cb_write_pty);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_BELL, (const void *)cb_bell);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, (const void *)cb_title);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, (const void *)cb_clipboard_write);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ, (const void *)cb_clipboard_read);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void *)cb_render_hold);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_SIZE, (const void *)cb_size);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES, (const void *)cb_device_attributes);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &kitty_limit);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);
    if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &bytes);
  }
  if (s != ST_OK) {
    st_engine_free(e);
    atomic_store(&g_slots[idx], 0);
    return s == ST_ERR_INVALID_ARGUMENT ? ST_ERR_INTERNAL : s;
  }

  uint32_t g = (atomic_fetch_add(&g_slot_gen[idx], 1u) + 1u) & ((1u << (32u - ST_SLOT_BITS)) - 1u);
  if (g == 0) g = 1;
  e->handle = (g << ST_SLOT_BITS) | idx;
  e->magic = ST_ENGINE_MAGIC;
  atomic_store_explicit(&g_slots[idx], (uintptr_t)e, memory_order_release);
  atomic_store_explicit(&g_slot_handle[idx], e->handle, memory_order_release);
  *out_handle = e->handle;
  return ST_OK;
}

st_status st_destroy(st_handle handle) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  uint32_t idx = handle & ST_SLOT_MASK;
  uint32_t expected = handle;
  /* Retire the handle first: from here on lookups of it fail without
   * dereferencing; then release the slot for reuse and free. */
  if (!atomic_compare_exchange_strong(&g_slot_handle[idx], &expected, 0u)) return ST_ERR_INVALID_HANDLE;
  atomic_store_explicit(&g_slots[idx], 0, memory_order_release);
  st_engine_free(e);
  return ST_OK;
}

/* ================================================================== */
/* Mutation                                                             */
/* ================================================================== */

st_status st_feed(st_handle handle, const uint8_t *data, uint32_t len, uint32_t origin) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (origin != ST_ORIGIN_LIVE && origin != ST_ORIGIN_REPLAY) return ST_ERR_INVALID_ARGUMENT;
  if (len > 0 && !data) return ST_ERR_INVALID_ARGUMENT;
  if (len == 0) return ST_OK;
  st_begin_call(e, origin, ST_SINK_RESPONSE);
  ghostty_terminal_vt_write(e->term, data, len);
  e->gen++;
  st_status s = e->call_status;
  st_begin_call(e, ST_ORIGIN_LIVE, ST_SINK_RESPONSE);
  return s;
}

st_status st_reset(st_handle handle) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  st_begin_call(e, ST_ORIGIN_LIVE, ST_SINK_RESPONSE);
  ghostty_terminal_reset(e->term);
  ghostty_mouse_encoder_reset(e->menc);
  e->buttons_down = 0;
  e->held = false;
  e->gen++;
  e->full_epoch++;
  return e->call_status;
}

st_status st_resize(st_handle handle, uint32_t columns, uint32_t rows, uint32_t cell_width_px,
                    uint32_t cell_height_px) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (!st_valid_size(columns, rows, cell_width_px, cell_height_px)) return ST_ERR_INVALID_ARGUMENT;
  st_begin_call(e, ST_ORIGIN_LIVE, ST_SINK_RESPONSE);
  GhosttyResult r = ghostty_terminal_resize(e->term, (uint16_t)columns, (uint16_t)rows, cell_width_px, cell_height_px);
  e->gen++;
  e->full_epoch++;
  if (r != GHOSTTY_SUCCESS) return st_map_result(r);
  e->cols = (uint16_t)columns;
  e->rows = (uint16_t)rows;
  e->cell_w = cell_width_px;
  e->cell_h = cell_height_px;
  return e->call_status;
}

static bool st_color_in(uint64_t c, GhosttyColorRgb *out) {
  if (c >> 32) return false; /* DEFAULT or invalid high bits */
  out->r = (uint8_t)(c >> 24);
  out->g = (uint8_t)(c >> 16);
  out->b = (uint8_t)(c >> 8);
  return true;
}

st_status st_colors(st_handle handle, const uint64_t *colors, uint32_t count) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (!colors || count != ST_COLOR_COUNT) return ST_ERR_INVALID_ARGUMENT;
  GhosttyColorRgb fg, bg, cursor, palette[256];
  bool ok = st_color_in(colors[0], &fg) && st_color_in(colors[1], &bg) && st_color_in(colors[2], &cursor);
  for (uint32_t i = 0; i < 256 && ok; i++) ok = st_color_in(colors[3 + i], &palette[i]);
  if (!ok) return ST_ERR_INVALID_ARGUMENT;
  st_status s = st_set(e, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg);
  if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg);
  if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor);
  if (s == ST_OK) s = st_set(e, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette);
  e->gen++;
  e->full_epoch++;
  return s;
}

st_status st_scroll_to(st_handle handle, int64_t row) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (row < 0) row = 0;
  GhosttyTerminalScrollViewport sv = {.tag = GHOSTTY_SCROLL_VIEWPORT_ROW};
  sv.value.row = (uint64_t)row > (uint64_t)SIZE_MAX ? SIZE_MAX : (size_t)row;
  GhosttyTerminalScrollbar before = {0, 0, 0}, after = {0, 0, 0};
  ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &before);
  ghostty_terminal_scroll_viewport(e->term, sv);
  ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &after);
  e->gen++;
  if (after.offset != before.offset) e->full_epoch++;
  return ST_OK;
}

static bool st_screen_ref(st_engine *e, int64_t row, uint32_t col, GhosttyGridRef *out) {
  if (row < 0 || row > (int64_t)UINT32_MAX || col >= e->cols) return false;
  GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_SCREEN,
                     .value = {.coordinate = {.x = (uint16_t)col, .y = (uint32_t)row}}};
  *out = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  return ghostty_terminal_grid_ref(e->term, pt, out) == GHOSTTY_SUCCESS && out->node != NULL;
}

st_status st_select(st_handle handle, uint32_t has_selection, int64_t start_row, uint32_t start_column,
                    int64_t end_row, uint32_t end_column) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (has_selection > 1) return ST_ERR_INVALID_ARGUMENT;
  st_status s;
  if (!has_selection) {
    s = st_set(e, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
  } else {
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (!st_screen_ref(e, start_row, start_column, &sel.start) || !st_screen_ref(e, end_row, end_column, &sel.end))
      return ST_ERR_INVALID_ARGUMENT;
    sel.rectangle = false;
    s = st_set(e, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
  }
  e->gen++;
  return s;
}

/* ================================================================== */
/* Input                                                                */
/* ================================================================== */

/* Encoder output lands in scratch (grown on OUT_OF_SPACE), then one Input effect. */
static st_status st_key_encode(st_engine *e, size_t *n) {
  if (!st_scratch(e, &e->scratch, &e->scratch_cap, 128)) return ST_ERR_OUT_OF_MEMORY;
  GhosttyResult r = ghostty_key_encoder_encode(e->kenc, e->kev, (char *)e->scratch, e->scratch_cap, n);
  if (r == GHOSTTY_OUT_OF_SPACE) {
    if (!st_scratch(e, &e->scratch, &e->scratch_cap, *n)) return ST_ERR_OUT_OF_MEMORY;
    r = ghostty_key_encoder_encode(e->kenc, e->kev, (char *)e->scratch, e->scratch_cap, n);
  }
  return st_map_result(r);
}

st_status st_key(st_handle handle, uint32_t physical_code, const uint8_t *text, uint32_t text_len,
                 uint32_t modifiers, uint32_t action) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if ((modifiers & ~ST_MODS_MASK) != 0 || (text_len > 0 && !text) || text_len > 4096)
    return ST_ERR_INVALID_ARGUMENT;
  GhosttyKeyAction ga;
  switch (action) {
    case 0: ga = GHOSTTY_KEY_ACTION_PRESS; break;   /* KeyAction.PRESS */
    case 1: ga = GHOSTTY_KEY_ACTION_RELEASE; break; /* KeyAction.RELEASE */
    case 2: ga = GHOSTTY_KEY_ACTION_REPEAT; break;  /* KeyAction.REPEAT */
    default: return ST_ERR_INVALID_ARGUMENT;
  }
  /* Layout text must be printable UTF-8; C0/DEL or invalid text is ignored. */
  bool use_text = text_len > 0 && st_utf8_valid(text, text_len);
  for (uint32_t i = 0; use_text && i < text_len; i++)
    if (text[i] < 0x20 || text[i] == 0x7F) use_text = false;

  const st_key_map *km = st_find_key(physical_code);
  uint32_t unshifted = km ? km->unshifted : 0;
  if (use_text && text_len == 1 && text[0] >= 'A' && text[0] <= 'Z') unshifted = (uint32_t)(text[0] - 'A' + 'a');
  else if (use_text && text_len == 1 && text[0] > 0x20 && text[0] < 0x7F && !(modifiers & 1u)) unshifted = text[0];

  GhosttyMods mods = st_mods(modifiers);
  ghostty_key_event_set_action(e->kev, ga);
  ghostty_key_event_set_key(e->kev, km ? km->key : GHOSTTY_KEY_UNIDENTIFIED);
  ghostty_key_event_set_mods(e->kev, mods);
  /* Shift that produced the text is consumed by the layout. */
  ghostty_key_event_set_consumed_mods(e->kev, (use_text && (mods & GHOSTTY_MODS_SHIFT)) ? GHOSTTY_MODS_SHIFT : 0);
  ghostty_key_event_set_composing(e->kev, false);
  ghostty_key_event_set_utf8(e->kev, use_text ? (const char *)text : NULL, use_text ? text_len : 0);
  ghostty_key_event_set_unshifted_codepoint(e->kev, unshifted);
  ghostty_key_encoder_setopt_from_terminal(e->kenc, e->term);

  st_begin_call(e, ST_ORIGIN_LIVE, ST_SINK_INPUT);
  size_t n = 0;
  st_status s = st_key_encode(e, &n);
  if (s == ST_OK) st_queue_bytes(e, ST_EFFECT_INPUT, e->scratch, n);
  return s != ST_OK ? s : e->call_status;
}

st_status st_mouse(st_handle handle, int32_t column, int32_t row, uint32_t button, uint32_t modifiers,
                   uint32_t action) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if ((modifiers & ~ST_MODS_MASK) != 0) return ST_ERR_INVALID_ARGUMENT;
  if (column < -(int32_t)ST_MAX_DIMENSION || column > 2 * (int32_t)ST_MAX_DIMENSION ||
      row < -(int32_t)ST_MAX_DIMENSION || row > 2 * (int32_t)ST_MAX_DIMENSION)
    return ST_ERR_INVALID_ARGUMENT;
  GhosttyMouseAction ga;
  switch (action) {
    case 0: ga = GHOSTTY_MOUSE_ACTION_PRESS; break;   /* MouseAction.PRESS */
    case 1: ga = GHOSTTY_MOUSE_ACTION_RELEASE; break; /* MouseAction.RELEASE */
    case 2: ga = GHOSTTY_MOUSE_ACTION_MOTION; break;  /* MouseAction.MOTION */
    default: return ST_ERR_INVALID_ARGUMENT;
  }
  GhosttyMouseButton gb = GHOSTTY_MOUSE_BUTTON_UNKNOWN;
  switch (button) {
    case 0: break;                                      /* MouseButton.NONE */
    case 1: gb = GHOSTTY_MOUSE_BUTTON_LEFT; break;      /* LEFT */
    case 2: gb = GHOSTTY_MOUSE_BUTTON_RIGHT; break;     /* RIGHT */
    case 3: gb = GHOSTTY_MOUSE_BUTTON_MIDDLE; break;    /* MIDDLE */
    case 4: gb = GHOSTTY_MOUSE_BUTTON_FOUR; break;      /* WHEEL_UP */
    case 5: gb = GHOSTTY_MOUSE_BUTTON_FIVE; break;      /* WHEEL_DOWN */
    case 6: gb = GHOSTTY_MOUSE_BUTTON_SIX; break;       /* WHEEL_LEFT */
    case 7: gb = GHOSTTY_MOUSE_BUTTON_SEVEN; break;     /* WHEEL_RIGHT */
    default: return ST_ERR_INVALID_ARGUMENT;
  }
  if (button >= 1 && button <= 3) {
    if (ga == GHOSTTY_MOUSE_ACTION_PRESS) e->buttons_down |= 1u << button;
    if (ga == GHOSTTY_MOUSE_ACTION_RELEASE) e->buttons_down &= ~(1u << button);
  }
  ghostty_mouse_event_set_action(e->mev, ga);
  if (button == 0) ghostty_mouse_event_clear_button(e->mev);
  else ghostty_mouse_event_set_button(e->mev, gb);
  ghostty_mouse_event_set_mods(e->mev, st_mods(modifiers));
  GhosttyMousePosition pos = {
      .x = (float)column * (float)e->cell_w + (float)(e->cell_w / 2u),
      .y = (float)row * (float)e->cell_h + (float)(e->cell_h / 2u),
  };
  ghostty_mouse_event_set_position(e->mev, pos);

  ghostty_mouse_encoder_setopt_from_terminal(e->menc, e->term);
  GhosttyMouseEncoderSize sz = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
  sz.screen_width = (uint32_t)e->cols * e->cell_w;
  sz.screen_height = (uint32_t)e->rows * e->cell_h;
  sz.cell_width = e->cell_w;
  sz.cell_height = e->cell_h;
  ghostty_mouse_encoder_setopt(e->menc, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &sz);
  bool any = e->buttons_down != 0;
  ghostty_mouse_encoder_setopt(e->menc, GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED, &any);

  st_begin_call(e, ST_ORIGIN_LIVE, ST_SINK_INPUT);
  if (!st_scratch(e, &e->scratch, &e->scratch_cap, 128)) return ST_ERR_OUT_OF_MEMORY;
  size_t n = 0;
  GhosttyResult r = ghostty_mouse_encoder_encode(e->menc, e->mev, (char *)e->scratch, e->scratch_cap, &n);
  if (r == GHOSTTY_OUT_OF_SPACE) {
    if (!st_scratch(e, &e->scratch, &e->scratch_cap, n)) return ST_ERR_OUT_OF_MEMORY;
    r = ghostty_mouse_encoder_encode(e->menc, e->mev, (char *)e->scratch, e->scratch_cap, &n);
  }
  if (r != GHOSTTY_SUCCESS) return st_map_result(r);
  st_queue_bytes(e, ST_EFFECT_INPUT, e->scratch, n);
  return e->call_status;
}

typedef struct {
  const uint8_t *text;
  uint32_t len;
} st_paste_src;

static bool st_paste_read(void *ud, GhosttyString mime, GhosttyWriter w) {
  ST_UNUSED(mime);
  st_paste_src *src = ud;
  if (src->len == 0) return true;
  return w.write(w.userdata, src->text, src->len);
}

st_status st_paste(st_handle handle, const uint8_t *text, uint32_t len, uint32_t flags) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if ((len > 0 && !text) || (flags & ~ST_PASTE_ALLOW_UNSAFE) != 0 || len > ST_MAX_PAYLOAD / 2u)
    return ST_ERR_INVALID_ARGUMENT;
  static const uint8_t plain[] = {'t', 'e', 'x', 't', '/', 'p', 'l', 'a', 'i', 'n'};
  GhosttyString mime = {.ptr = plain, .len = sizeof(plain)};
  st_paste_src src = {.text = text, .len = len};
  GhosttyPaste p = GHOSTTY_INIT_SIZED(GhosttyPaste);
  p.location = GHOSTTY_CLIPBOARD_LOCATION_STANDARD;
  p.source = GHOSTTY_PASTE_SOURCE_TEXT; /* never a kitty paste event */
  p.mimes = &mime;
  p.mimes_len = 1;
  p.reader.read = st_paste_read;
  p.reader.userdata = &src;
  p.allow_unsafe = (flags & ST_PASTE_ALLOW_UNSAFE) != 0;
  st_begin_call(e, ST_ORIGIN_LIVE, ST_SINK_INPUT);
  bool written = false;
  GhosttyResult r = ghostty_terminal_paste(e->term, &p, &written);
  st_status s = st_map_result(r);
  st_begin_call(e, ST_ORIGIN_LIVE, ST_SINK_RESPONSE);
  return s;
}

st_status st_focus(st_handle handle, uint32_t focused) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (focused > 1) return ST_ERR_INVALID_ARGUMENT;
  if (!st_mode(e, GHOSTTY_MODE_FOCUS_EVENT)) return ST_OK;
  char buf[16];
  size_t n = 0;
  GhosttyResult r = ghostty_focus_encode(focused ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST, buf, sizeof(buf), &n);
  if (r != GHOSTTY_SUCCESS) return st_map_result(r);
  st_begin_call(e, ST_ORIGIN_LIVE, ST_SINK_INPUT);
  st_queue_bytes(e, ST_EFFECT_INPUT, (const uint8_t *)buf, n);
  return e->call_status;
}

/* ================================================================== */
/* Output                                                               */
/* ================================================================== */

static st_status st_put_cell(st_engine *e, st_buf *w) {
  GhosttyCell raw = 0;
  GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
  if (ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) != GHOSTTY_SUCCESS ||
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide) != GHOSTTY_SUCCESS)
    return ST_ERR_INTERNAL;
  int32_t width = st_cell_width(wide);

  size_t text_len = 0;
  if (width != 0) {
    if (!st_scratch(e, &e->scratch, &e->scratch_cap, 64)) return ST_ERR_OUT_OF_MEMORY;
    GhosttyBuffer gb = {.ptr = e->scratch, .cap = e->scratch_cap, .len = 0};
    GhosttyResult r =
        ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &gb);
    if (r == GHOSTTY_OUT_OF_SPACE) {
      if (!st_scratch(e, &e->scratch, &e->scratch_cap, gb.len)) return ST_ERR_OUT_OF_MEMORY;
      gb = (GhosttyBuffer){.ptr = e->scratch, .cap = e->scratch_cap, .len = 0};
      r = ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &gb);
    }
    if (r != GHOSTTY_SUCCESS) return st_map_result(r);
    text_len = gb.len;
    if (text_len > ST_MAX_CELL_TEXT) {
      /* Pathological cluster (e.g. dozens of combining marks): keep the
       * longest prefix that ends on a code point boundary. */
      text_len = ST_MAX_CELL_TEXT;
      while (text_len > 0 && (e->scratch[text_len] & 0xC0) == 0x80) text_len--;
    }
    if (!st_utf8_valid(e->scratch, text_len)) {
      static const uint8_t repl[3] = {0xEF, 0xBF, 0xBD};
      st_copy(e->scratch, repl, 3);
      text_len = 3;
    }
  }

  GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
  if (ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style) != GHOSTTY_SUCCESS)
    return ST_ERR_INTERNAL;
  uint64_t colors[2];
  const GhosttyRenderStateRowCellsData keys[2] = {GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
                                                  GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR};
  for (int i = 0; i < 2; i++) {
    GhosttyColorRgb rgb = {0, 0, 0};
    GhosttyResult r = ghostty_render_state_row_cells_get(e->cells, keys[i], &rgb);
    if (r == GHOSTTY_SUCCESS) colors[i] = st_rgba(rgb);
    else if (r == GHOSTTY_INVALID_VALUE || r == GHOSTTY_NO_VALUE) colors[i] = ST_COLOR_DEFAULT;
    else return st_map_result(r);
  }

  st_put_str(w, e->scratch, text_len);
  st_put_i32(w, width);
  st_put_u64(w, colors[0]);
  st_put_u64(w, colors[1]);
  st_put_i32(w, st_cell_flags(&style));
  st_put_i32(w, st_underline(style.underline));
  return w->err;
}

static st_status st_put_rows(st_engine *e, st_buf *w, bool full) {
  uint32_t count_off = w->len;
  st_put_u32(w, 0);
  if (ghostty_render_state_get(e->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &e->it) != GHOSTTY_SUCCESS)
    return ST_ERR_INTERNAL;
  uint32_t count = 0;
  for (uint16_t y = 0; ghostty_render_state_row_iterator_next(e->it); y++) {
    if (y >= e->snap.rows) return ST_ERR_INTERNAL;
    if (!full) {
      bool dirty = true;
      if (ghostty_render_state_row_get(e->it, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY, &dirty) != GHOSTTY_SUCCESS)
        dirty = true;
      if (!dirty) continue;
    }
    if (ghostty_render_state_row_get(e->it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &e->cells) != GHOSTTY_SUCCESS)
      return ST_ERR_INTERNAL;
    st_put_i32(w, y);
    st_put_u32(w, e->snap.cols);
    for (uint16_t x = 0; x < e->snap.cols; x++) {
      if (!ghostty_render_state_row_cells_next(e->cells)) return ST_ERR_INTERNAL;
      st_status s = st_put_cell(e, w);
      if (s != ST_OK) return s;
    }
    count++;
  }
  st_patch_u32(w, count_off, count);
  return w->err;
}

static st_status st_serialize(st_engine *e, bool full, st_buf *w) {
  st_snapshot *s = &e->snap;
  st_env_begin(w);
  st_put_i64(w, e->rs_gen);
  st_put_i32(w, s->cols);
  st_put_i32(w, s->rows);
  st_put_i32(w, (int32_t)s->cell_w);
  st_put_i32(w, (int32_t)s->cell_h);
  st_status st = st_put_rows(e, w, full);
  if (st != ST_OK) return st;

  GhosttyRenderStateCursor cur = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  if (ghostty_render_state_get(e->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cur) != GHOSTTY_SUCCESS) return ST_ERR_INTERNAL;
  if (cur.viewport_has_value) {
    st_put_i32(w, cur.viewport_x);
    st_put_i32(w, cur.viewport_y);
  } else {
    st_put_i32(w, s->cursor_fallback_col);
    st_put_i32(w, s->cursor_fallback_row);
  }
  st_put_i32(w, st_cursor_shape(cur.visual_style));
  st_put_bool(w, cur.visible && cur.viewport_has_value);

  st_put_bool(w, s->alt_screen);
  st_put_bool(w, s->mouse_tracking);
  st_put_bool(w, s->bracketed_paste);
  st_put_i64(w, s->history_rows);
  st_put_i64(w, s->viewport_top);
  st_put_bool(w, full);

  st_put_u32(w, s->link_count);
  st_put_bytes(w, s->links.data, s->links.len);

  st_put_bool(w, s->has_selection);
  if (s->has_selection) {
    st_put_i64(w, s->sel_start_row);
    st_put_i32(w, s->sel_start_col);
    st_put_i64(w, s->sel_end_row);
    st_put_i32(w, s->sel_end_col);
  }
  st_put_bool(w, s->held);
  st_env_finish(w, ST_KIND_VIEWPORT);
  return w->err;
}

st_status st_read_viewport(st_handle handle, uint32_t flags, uint8_t **out_buf, uint32_t *out_len,
                           uint32_t *out_frame_flags) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (!out_buf || !out_len || (flags & ~(ST_READ_FORCE_FULL | ST_READ_BREAK_HOLD)) != 0)
    return ST_ERR_INVALID_ARGUMENT;

  if (e->held && (flags & ST_READ_BREAK_HOLD)) {
    /* Owner timeout: end synchronized output ourselves (no callback fires). */
    GhosttyTerminalModeConfig cfg = {.mode = GHOSTTY_MODE_SYNC_OUTPUT, .value = false};
    ghostty_terminal_set(e->term, GHOSTTY_TERMINAL_OPT_MODE, &cfg);
    e->held = false;
    e->gen++;
  }
  if (!e->held) {
    st_status s = st_update(e, false);
    if (s != ST_OK) return s;
  }

  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
  if (ghostty_render_state_get(e->rs, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty) != GHOSTTY_SUCCESS)
    dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
  bool full = (flags & ST_READ_FORCE_FULL) != 0 || e->full_epoch != e->full_done_epoch ||
              dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL;

  st_buf w;
  st_buf_init(&w, e->alloc, ST_ENVELOPE_HEADER + ST_MAX_PAYLOAD);
  uint64_t estimate = 256u + (uint64_t)e->snap.rows * (8u + (uint64_t)e->snap.cols * 36u) + e->snap.links.len;
  if (full) st_buf_reserve(&w, estimate > ST_MAX_PAYLOAD ? ST_MAX_PAYLOAD : (uint32_t)estimate);
  w.err = ST_OK; /* the estimate is only a hint */
  st_status s = st_serialize(e, full, &w);
  if (s != ST_OK) {
    st_buf_release(&w);
    e->full_epoch++; /* uncertain what the owner has: next frame is full */
    return s;
  }
  e->ser_gen = e->rs_gen;
  e->ser_valid = true;
  e->ser_full = full;
  e->ser_epoch = e->rs_epoch;
  if (out_frame_flags) *out_frame_flags = e->held ? ST_FRAME_HELD : 0u;
  return st_env_take(&w, out_buf, out_len);
}

st_status st_acknowledge(st_handle handle, int64_t generation) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (generation < 0) return ST_ERR_INVALID_ARGUMENT;
  if (e->ser_gen != 0 && generation == e->ser_gen && e->ser_valid) {
    GhosttyResult r = ghostty_render_state_clean(e->rs);
    if (r != GHOSTTY_SUCCESS) {
      e->full_epoch++;
      return st_map_result(r);
    }
    if (e->ser_full) e->full_done_epoch = e->ser_epoch;
    e->ser_valid = false;
    return ST_OK;
  }
  if (generation <= e->ser_gen) return ST_OK; /* older or superseded frame: no-op */
  e->full_epoch++;
  return ST_ERR_INVALID_ARGUMENT;
}

st_status st_selected_text(st_handle handle, uint8_t **out_buf, uint32_t *out_len) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (!out_buf || !out_len) return ST_ERR_INVALID_ARGUMENT;
  GhosttyTerminalSelectionFormatOptions opts = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
  opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  opts.unwrap = true;
  opts.trim = true;
  opts.selection = NULL; /* the active selection */
  uint8_t *txt = NULL;
  size_t n = 0;
  GhosttyResult r = GHOSTTY_NO_VALUE;
  GhosttySelection probe = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_get(e->term, GHOSTTY_TERMINAL_DATA_SELECTION, &probe) == GHOSTTY_SUCCESS)
    r = ghostty_terminal_selection_format_alloc(e->term, e->alloc, opts, &txt, &n);
  if (r != GHOSTTY_SUCCESS && r != GHOSTTY_NO_VALUE) return st_map_result(r);
  if (r != GHOSTTY_SUCCESS) n = 0;
  st_buf w;
  st_buf_init(&w, e->alloc, ST_ENVELOPE_HEADER + ST_MAX_PAYLOAD);
  st_env_begin(&w);
  st_put_str(&w, txt, n);
  st_env_finish(&w, ST_KIND_SELECTED_TEXT);
  if (txt) ghostty_free(e->alloc, txt, n);
  return st_env_take(&w, out_buf, out_len);
}

st_status st_drain_effects(st_handle handle, uint8_t **out_buf, uint32_t *out_len) {
  st_engine *e = st_lookup(handle);
  if (!e) return ST_ERR_INVALID_HANDLE;
  if (!out_buf || !out_len) return ST_ERR_INVALID_ARGUMENT;
  st_buf w;
  st_buf_init(&w, e->alloc, ST_ENVELOPE_HEADER + ST_MAX_PAYLOAD);
  st_env_begin(&w);
  st_put_u32(&w, e->queue_count);
  st_put_bytes(&w, e->queue.data, e->queue.len);
  st_env_finish(&w, ST_KIND_EFFECTS);
  st_status s = st_env_take(&w, out_buf, out_len);
  if (s != ST_OK) return s; /* queue kept: nothing was consumed */
  e->queue.len = 0;
  e->queue_count = 0;
  e->coalesce_tag = 0;
  if (e->queue.cap > 64u * 1024u) st_buf_release(&e->queue);
  return ST_OK;
}

st_status st_free_buffer(uint8_t *buf) {
  if (!buf) return ST_OK;
  return st_mem_free(buf) ? ST_OK : ST_ERR_INVALID_ARGUMENT;
}
