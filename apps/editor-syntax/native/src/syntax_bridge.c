/*
 * supermux editor-syntax: the ses_* ABI (include/supermux_syntax.h) over tree-sitter v0.25.10.
 * Everything is UTF-16: tree-sitter parses TSInputEncodingUTF16LE, so byte = 2 x unit, and this
 * file is the only place that multiplies or divides by 2.
 */
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "tree_sitter/api.h"
#include "ses_registry.h"
#include "supermux_syntax.h"

struct ses_parser {
  TSParser *ts;
  uint64_t timeout_us;
};

/* A ses_tree IS a TSTree (no wrapper allocation): cast only here. */
#define TREE(t) ((TSTree *)(t))

enum { P_EQ, P_ANY_OF, P_MATCH };

typedef struct {
  const uint16_t *s;
  uint32_t len;
} u16str;

typedef struct {
  int op;
  bool positive;      /* eq? vs not-eq? */
  bool match_all;     /* eq? (every node) vs any-eq? (some node) */
  uint32_t capture;   /* left-hand capture id */
  int32_t other;      /* right-hand capture id, or -1 when comparing against values */
  u16str *values;
  uint32_t value_count;
} predicate;

struct ses_query {
  TSQuery *ts;
  const TSLanguage *lang;
  uint32_t flags;
  uint32_t pattern_count;
  uint32_t *pred_start; /* pattern_count + 1 offsets into preds */
  predicate *preds;
  uint16_t *u16_pool;
  u16str *vals;
};

uint32_t ses_abi_version(void) { return SES_ABI_VERSION; }
void ses_free(void *p) { free(p); }

/* ---------------------------------------------------------------- languages --- */

uint32_t ses_language_count(void) { return ses_registry_count; }

const char *ses_language_name(uint32_t i) { return i < ses_registry_count ? ses_registry[i].name : NULL; }

ses_status ses_language_has_tables(const char *name) {
  const ses_registry_entry *e = ses_registry_find(name);
  if (!e) return SES_ERR_UNKNOWN_LANGUAGE;
  return ses_tables_available(e->grammar(), e);
}

ses_status ses_language_provide_tables(const char *name, const uint8_t *sesz, size_t len) {
  const ses_registry_entry *e = ses_registry_find(name);
  if (!e) return SES_ERR_UNKNOWN_LANGUAGE;
  if (!sesz) return SES_ERR_INVALID_ARGUMENT;
  return ses_tables_provide(e->grammar(), sesz, len);
}

static const TSLanguage *language_for(const char *name, ses_status *st) {
  const ses_registry_entry *e = ses_registry_find(name);
  if (!e) { *st = SES_ERR_UNKNOWN_LANGUAGE; return NULL; }
  ses_grammar *g = e->grammar();
  const TSLanguage *l = ses_grammar_language(g);
  *st = l ? SES_OK : (ses_grammar_status(g) ? ses_grammar_status(g) : SES_ERR_NO_TABLES);
  return l;
}

ses_status ses_language_load(const char *name) {
  ses_status st;
  language_for(name, &st);
  return st;
}

/* ------------------------------------------------------------------ parser --- */

ses_parser *ses_parser_new(void) {
  ses_parser *p = calloc(1, sizeof *p);
  if (!p) return NULL;
  p->ts = ts_parser_new();
  if (!p->ts) { free(p); return NULL; }
  return p;
}

void ses_parser_free(ses_parser *p) {
  if (!p) return;
  ts_parser_delete(p->ts);
  free(p);
}

ses_status ses_parser_set_language(ses_parser *p, const char *name) {
  if (!p) return SES_ERR_INVALID_ARGUMENT;
  ses_status st;
  const TSLanguage *l = language_for(name, &st);
  if (!l) return st;
  return ts_parser_set_language(p->ts, l) ? SES_OK : SES_ERR_INCOMPATIBLE_LANGUAGE;
}

void ses_parser_set_timeout_micros(ses_parser *p, uint64_t micros) { if (p) p->timeout_us = micros; }

typedef struct {
  ses_read_fn fn;
  void *ctx;
} reader;

static const char *ts_read(void *payload, uint32_t byte, TSPoint pt, uint32_t *bytes_read) {
  (void)pt;
  reader *r = payload;
  uint32_t n = 0;
  const uint16_t *c = r->fn(r->ctx, byte / 2, &n);
  *bytes_read = c ? n * 2 : 0;
  return c ? (const char *)c : "";
}

typedef struct {
  const uint16_t *text;
  uint32_t len;
} buffer;

static const uint16_t *buffer_read(void *ctx, uint32_t index, uint32_t *out_len) {
  buffer *b = ctx;
  if (index >= b->len) { *out_len = 0; return NULL; }
  *out_len = b->len - index;
  return b->text + index;
}

static uint64_t now_us(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000u + (uint64_t)ts.tv_nsec / 1000u;
}

typedef struct {
  uint64_t deadline_us;
} progress;

static bool on_progress(TSParseState *s) {
  progress *pr = s->payload;
  return pr->deadline_us && now_us() > pr->deadline_us; /* true = cancel */
}

ses_tree *ses_parser_parse(ses_parser *p, const ses_tree *old, ses_read_fn fn, void *ctx, ses_status *st) {
  ses_status dummy;
  if (!st) st = &dummy;
  if (!p || !fn) { *st = SES_ERR_INVALID_ARGUMENT; return NULL; }
  if (!ts_parser_language(p->ts)) { *st = SES_ERR_UNKNOWN_LANGUAGE; return NULL; }
  reader r = {fn, ctx};
  TSInput in = {.payload = &r, .read = ts_read, .encoding = TSInputEncodingUTF16LE, .decode = NULL};
  progress pr = {p->timeout_us ? now_us() + p->timeout_us : 0};
  TSParseOptions opt = {.payload = &pr, .progress_callback = p->timeout_us ? on_progress : NULL};
  TSTree *t = ts_parser_parse_with_options(p->ts, old ? TREE(old) : NULL, in, opt);
  if (!t) {
    ts_parser_reset(p->ts); /* a cancelled parse would otherwise resume on the next call */
    *st = SES_ERR_TIMEOUT;
    return NULL;
  }
  *st = SES_OK;
  return (ses_tree *)t;
}

ses_tree *ses_parser_parse_utf16(ses_parser *p, const ses_tree *old, const uint16_t *text, uint32_t len,
                                 ses_status *st) {
  buffer b = {text, len};
  return ses_parser_parse(p, old, buffer_read, &b, st);
}

/* -------------------------------------------------------------------- tree --- */

ses_tree *ses_tree_copy(const ses_tree *t) { return t ? (ses_tree *)ts_tree_copy(TREE(t)) : NULL; }
void ses_tree_free(ses_tree *t) { if (t) ts_tree_delete(TREE(t)); }

void ses_tree_edit(ses_tree *t, uint32_t start, uint32_t old_end, uint32_t new_end, uint32_t sr, uint32_t sc,
                   uint32_t oer, uint32_t oec, uint32_t ner, uint32_t nec) {
  if (!t) return;
  TSInputEdit e = {
      .start_byte = start * 2, .old_end_byte = old_end * 2, .new_end_byte = new_end * 2,
      .start_point = {sr, sc * 2}, .old_end_point = {oer, oec * 2}, .new_end_point = {ner, nec * 2},
  };
  ts_tree_edit(TREE(t), &e);
}

char *ses_tree_root_sexp(const ses_tree *t) {
  if (!t) return NULL;
  return ts_node_string(ts_tree_root_node(TREE(t))); /* malloc'd by tree-sitter's default allocator */
}

int32_t ses_tree_has_error(const ses_tree *t) { return t ? ts_node_has_error(ts_tree_root_node(TREE(t))) : 0; }

ses_status ses_tree_changed_ranges(const ses_tree *old, const ses_tree *new_tree, int32_t **out, uint32_t *count) {
  if (!old || !new_tree || !out || !count) return SES_ERR_INVALID_ARGUMENT;
  uint32_t n = 0;
  TSRange *r = ts_tree_get_changed_ranges(TREE(old), TREE(new_tree), &n);
  int32_t *a = malloc((n ? n : 1) * 2 * sizeof(int32_t));
  if (!a) { free(r); return SES_ERR_OUT_OF_MEMORY; }
  for (uint32_t i = 0; i < n; i++) { a[2 * i] = (int32_t)(r[i].start_byte / 2); a[2 * i + 1] = (int32_t)(r[i].end_byte / 2); }
  free(r);
  *out = a;
  *count = n * 2;
  return SES_OK;
}

/* ------------------------------------------------------------------- query --- */

/* UTF-8 -> UTF-16, appended to a growing pool (the pool is fixed once the query is built). */
static uint32_t utf8_to_utf16(const char *s, uint32_t len, uint16_t *out) {
  uint32_t n = 0;
  for (uint32_t i = 0; i < len;) {
    uint32_t c = (unsigned char)s[i], k = c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
    if (k == 2) c &= 0x1F; else if (k == 3) c &= 0x0F; else if (k == 4) c &= 0x07;
    for (uint32_t j = 1; j < k && i + j < len; j++) c = (c << 6) | ((unsigned char)s[i + j] & 0x3F);
    i += k;
    if (c >= 0x10000) { c -= 0x10000; out[n++] = (uint16_t)(0xD800 | (c >> 10)); out[n++] = (uint16_t)(0xDC00 | (c & 0x3FF)); }
    else out[n++] = (uint16_t)c;
  }
  return n;
}

static bool is(const char *a, uint32_t alen, const char *b) { return strlen(b) == alen && memcmp(a, b, alen) == 0; }

#define STEP_STRING TSQueryPredicateStepTypeString
#define STEP_CAPTURE TSQueryPredicateStepTypeCapture
#define STEP_DONE TSQueryPredicateStepTypeDone

/* The string step [id] as a UTF-16 value in the next slot. Pass 1 sized both: one slot per string
   step and one pool unit per UTF-8 byte (UTF-16 units <= UTF-8 bytes). */
static u16str *add_value(ses_query *q, uint32_t id, uint32_t *vc, uint32_t *pool) {
  uint32_t l;
  const char *v = ts_query_string_value_for_id(q->ts, id, &l);
  u16str *slot = &q->vals[(*vc)++];
  slot->s = q->u16_pool + *pool;
  slot->len = utf8_to_utf16(v, l, q->u16_pool + *pool);
  *pool += slot->len;
  return slot;
}

/* SES_ERR_QUERY for a malformed text predicate (wrong arity, a capture where a string belongs). */
static ses_status build_predicates(ses_query *q) {
  TSQuery *tq = q->ts;
  uint32_t np = ts_query_pattern_count(tq), total = 0, strings = 0, u16_total = 0;
  for (uint32_t i = 0; i < np; i++) {
    uint32_t steps;
    const TSQueryPredicateStep *s = ts_query_predicates_for_pattern(tq, i, &steps);
    for (uint32_t k = 0; k < steps; k++) {
      if (s[k].type == STEP_DONE) total++;
      if (s[k].type == STEP_STRING) {
        uint32_t l;
        ts_query_string_value_for_id(tq, s[k].value_id, &l);
        strings++;
        u16_total += l;
      }
    }
  }
  q->pattern_count = np;
  q->pred_start = calloc(np + 1, sizeof(uint32_t));
  q->preds = calloc(total ? total : 1, sizeof(predicate));
  q->u16_pool = malloc((u16_total ? u16_total : 1) * sizeof(uint16_t));
  q->vals = calloc(strings + 1, sizeof(u16str));
  if (!q->pred_start || !q->preds || !q->u16_pool || !q->vals) return SES_ERR_OUT_OF_MEMORY;
  uint32_t pc = 0, pool = 0, vc = 0;
  for (uint32_t i = 0; i < np; i++) {
    q->pred_start[i] = pc;
    uint32_t steps;
    const TSQueryPredicateStep *s = ts_query_predicates_for_pattern(tq, i, &steps);
    for (uint32_t k = 0; k < steps;) {
      uint32_t e = k;
      while (e < steps && s[e].type != STEP_DONE) e++;
      uint32_t n = e - k, nl = 0; /* steps of this predicate, its name included */
      const char *name = n && s[k].type == STEP_STRING ? ts_query_string_value_for_id(tq, s[k].value_id, &nl) : "";
      predicate pr = {0};
      pr.other = -1;
      bool eq = is(name, nl, "eq?"), neq = is(name, nl, "not-eq?"), aeq = is(name, nl, "any-eq?"), aneq = is(name, nl, "any-not-eq?");
      bool anyof = is(name, nl, "any-of?"), nanyof = is(name, nl, "not-any-of?");
      bool match = is(name, nl, "match?") || is(name, nl, "not-match?") || is(name, nl, "any-match?") ||
                   is(name, nl, "any-not-match?") || is(name, nl, "lua-match?");
      if (eq || neq || aeq || aneq) {
        if (n != 3 || s[k + 1].type != STEP_CAPTURE) return SES_ERR_QUERY;
        pr.op = P_EQ; pr.positive = eq || aeq; pr.match_all = eq || neq; pr.capture = s[k + 1].value_id;
        if (s[k + 2].type == STEP_CAPTURE) pr.other = (int32_t)s[k + 2].value_id;
        else { pr.values = add_value(q, s[k + 2].value_id, &vc, &pool); pr.value_count = 1; }
        q->preds[pc++] = pr;
      } else if (anyof || nanyof) {
        if (n < 2 || s[k + 1].type != STEP_CAPTURE) return SES_ERR_QUERY;
        for (uint32_t j = k + 2; j < e; j++)
          if (s[j].type != STEP_STRING) return SES_ERR_QUERY;
        pr.op = P_ANY_OF; pr.positive = anyof; pr.capture = s[k + 1].value_id; pr.values = &q->vals[vc];
        for (uint32_t j = k + 2; j < e; j++) add_value(q, s[j].value_id, &vc, &pool);
        pr.value_count = n - 2;
        q->preds[pc++] = pr;
      } else if (match) {
        q->flags |= 1u; /* not evaluated: see ses_query_flags */
      } /* directives (#set! #is? #offset! ...) and unknown predicates: ignored, like a filter-less engine */
      k = e + 1;
    }
  }
  q->pred_start[np] = pc;
  return SES_OK;
}

ses_query *ses_query_new(const char *language, const char *src, uint32_t len, uint32_t *err_offset,
                         int32_t *err_type, ses_status *st) {
  ses_status dummy;
  if (!st) st = &dummy;
  const TSLanguage *l = language_for(language, st);
  if (!l) return NULL;
  uint32_t off = 0;
  TSQueryError et = TSQueryErrorNone;
  TSQuery *tq = ts_query_new(l, src, len, &off, &et);
  if (err_offset) *err_offset = off;
  if (err_type) *err_type = (int32_t)et;
  if (!tq) { *st = SES_ERR_QUERY; return NULL; }
  ses_query *q = calloc(1, sizeof *q);
  if (!q) { ts_query_delete(tq); *st = SES_ERR_OUT_OF_MEMORY; return NULL; }
  q->ts = tq;
  q->lang = l;
  if ((*st = build_predicates(q)) != SES_OK) {
    if (*st == SES_ERR_QUERY) { if (err_offset) *err_offset = 0; if (err_type) *err_type = -1; }
    ses_query_free(q);
    return NULL;
  }
  return q;
}

void ses_query_free(ses_query *q) {
  if (!q) return;
  ts_query_delete(q->ts);
  free(q->pred_start);
  free(q->preds);
  free(q->vals);
  free(q->u16_pool);
  free(q);
}

uint32_t ses_query_capture_count(const ses_query *q) { return q ? ts_query_capture_count(q->ts) : 0; }

const char *ses_query_capture_name(const ses_query *q, uint32_t i, uint32_t *len) {
  if (!q || i >= ts_query_capture_count(q->ts)) { if (len) *len = 0; return NULL; }
  uint32_t l;
  const char *n = ts_query_capture_name_for_id(q->ts, i, &l);
  if (len) *len = l;
  return n;
}

uint32_t ses_query_flags(const ses_query *q) { return q ? q->flags : 0; }

/* Text of UTF-16 [s, e) through the reader into a growing scratch buffer. */
typedef struct {
  reader r;
  uint16_t *buf;
  uint32_t cap;
} texter;

static bool text_of(texter *tx, uint32_t s, uint32_t e, u16str *out) {
  uint32_t n = e - s;
  if (n > tx->cap) {
    uint16_t *b = realloc(tx->buf, n * sizeof(uint16_t));
    if (!b) return false;
    tx->buf = b; tx->cap = n;
  }
  uint32_t got = 0;
  while (got < n) {
    uint32_t cl = 0;
    const uint16_t *c = tx->r.fn(tx->r.ctx, s + got, &cl);
    if (!c || cl == 0) break;
    uint32_t take = cl < n - got ? cl : n - got;
    memcpy(tx->buf + got, c, take * sizeof(uint16_t));
    got += take;
  }
  out->s = tx->buf; out->len = got;
  return true;
}

static bool u16eq(u16str a, u16str b) { return a.len == b.len && memcmp(a.s, b.s, a.len * 2) == 0; }

static bool node_text_is(texter *tx, TSNode n, u16str v) {
  uint32_t s = ts_node_start_byte(n) / 2, e = ts_node_end_byte(n) / 2;
  if (e - s != v.len) return false;
  u16str t;
  return text_of(tx, s, e, &t) && u16eq(t, v);
}

static bool predicates_pass(const ses_query *q, const TSQueryMatch *m, texter *tx) {
  for (uint32_t pi = q->pred_start[m->pattern_index]; pi < q->pred_start[m->pattern_index + 1]; pi++) {
    const predicate *p = &q->preds[pi];
    if (!tx->r.fn) continue; /* no text: cannot evaluate (documented) */
    bool any = false, all = true, seen = false;
    for (uint16_t c = 0; c < m->capture_count; c++) {
      if (m->captures[c].index != p->capture) continue;
      seen = true;
      TSNode n = m->captures[c].node;
      bool ok;
      if (p->op == P_EQ && p->other >= 0) {
        ok = true;
        for (uint16_t d = 0; d < m->capture_count; d++) {
          if (m->captures[d].index != (uint32_t)p->other) continue;
          TSNode o = m->captures[d].node;
          uint32_t os = ts_node_start_byte(o) / 2, oe = ts_node_end_byte(o) / 2;
          /* copy the other node's text first: text_of reuses one scratch buffer */
          u16str t; uint16_t small[256]; uint16_t *heap = NULL;
          if (!text_of(tx, os, oe, &t)) return false;
          uint16_t *keep = t.len <= 256 ? small : (heap = malloc(t.len * 2));
          if (!keep) return false;
          memcpy(keep, t.s, t.len * 2);
          u16str ov = {keep, t.len};
          ok = node_text_is(tx, n, ov);
          free(heap);
          break;
        }
        ok = ok == p->positive;
      } else if (p->op == P_EQ) {
        ok = node_text_is(tx, n, p->values[0]) == p->positive;
      } else {
        bool in = false;
        for (uint32_t v = 0; v < p->value_count && !in; v++) in = node_text_is(tx, n, p->values[v]);
        ok = in == p->positive;
      }
      any = any || ok; all = all && ok;
    }
    bool pass = p->op == P_ANY_OF ? all : (p->match_all ? all : (seen && any));
    if (!pass) return false;
  }
  return true;
}

ses_status ses_query_captures(const ses_query *q, const ses_tree *t, uint32_t start, uint32_t end, ses_read_fn fn,
                              void *ctx, int32_t **out, uint32_t *count) {
  if (!q || !t || !out || !count || end < start) return SES_ERR_INVALID_ARGUMENT;
  if (ts_tree_language(TREE(t)) != q->lang) return SES_ERR_INVALID_ARGUMENT;
  TSQueryCursor *cur = ts_query_cursor_new();
  if (!cur) return SES_ERR_OUT_OF_MEMORY;
  ts_query_cursor_set_byte_range(cur, start * 2, end * 2);
  ts_query_cursor_exec(cur, q->ts, ts_tree_root_node(TREE(t)));
  uint32_t cap = 3 * 64, n = 0;
  int32_t *a = malloc(cap * sizeof(int32_t));
  texter tx = {{fn, ctx}, NULL, 0};
  ses_status st = a ? SES_OK : SES_ERR_OUT_OF_MEMORY;
  TSQueryMatch m;
  uint32_t ci;
  while (st == SES_OK && ts_query_cursor_next_capture(cur, &m, &ci)) {
    if (q->pred_start[m.pattern_index] != q->pred_start[m.pattern_index + 1] && !predicates_pass(q, &m, &tx)) {
      ts_query_cursor_remove_match(cur, m.id);
      continue;
    }
    TSNode node = m.captures[ci].node;
    if (n + 3 > cap) {
      int32_t *b = realloc(a, (cap *= 2) * sizeof(int32_t));
      if (!b) { st = SES_ERR_OUT_OF_MEMORY; break; }
      a = b;
    }
    a[n++] = (int32_t)(ts_node_start_byte(node) / 2);
    a[n++] = (int32_t)(ts_node_end_byte(node) / 2);
    a[n++] = (int32_t)m.captures[ci].index;
  }
  free(tx.buf);
  ts_query_cursor_delete(cur);
  if (st != SES_OK) { free(a); return st; }
  *out = a;
  *count = n;
  return SES_OK;
}

ses_status ses_query_captures_utf16(const ses_query *q, const ses_tree *t, uint32_t start, uint32_t end,
                                    const uint16_t *text, uint32_t len, int32_t **out, uint32_t *count) {
  buffer b = {text, len};
  return ses_query_captures(q, t, start, end, text ? buffer_read : NULL, &b, out, count);
}
