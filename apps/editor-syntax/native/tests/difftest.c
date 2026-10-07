/*
 * Differential test: the ORIGINAL grammar (ses_orig_tree_sitter_<L>, tables inline) versus the
 * TRANSFORMED one (tree_sitter_<L>, tables from the .sesz blob), same runtime, same scanner.
 *   difftest <blob.sesz> <input files...>
 * 1. Language level: counts, every symbol name/type/metadata, every field name, and for EVERY
 *    (state, symbol) pair ts_language_next_state() plus every state's lookahead set.
 * 2. Tree level: each input parsed as UTF-16LE by both; a full cursor walk (every node incl.
 *    anonymous: symbol, type, field, byte range, points, parse state, flags) plus the S-expression
 *    must be byte-identical.
 * Also times the first-use load (inflate + fix-up) through the ses_* API.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "tree_sitter/api.h"
#include "language.h" /* runtime-internal: TSLanguage.symbol_count WITHOUT aliases */
#include "supermux_syntax.h"

#define CAT2(a, b) a##b
#define CAT(a, b) CAT2(a, b)
#define STR2(x) #x
#define STR(x) STR2(x)
const TSLanguage *CAT(ses_orig_tree_sitter_, LANG)(void);
const TSLanguage *CAT(tree_sitter_, LANG)(void);

static double now_ms(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static char *slurp(const char *path, size_t *len) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  char *b = malloc(n + 1);
  *len = fread(b, 1, n, f);
  b[*len] = 0;
  fclose(f);
  return b;
}

/* UTF-8 -> UTF-16 (invalid bytes become U+FFFD) */
static uint16_t *to_utf16(const unsigned char *s, size_t n, uint32_t *out_len) {
  uint16_t *o = malloc((n + 1) * 2 * sizeof(uint16_t));
  uint32_t k = 0;
  for (size_t i = 0; i < n;) {
    uint32_t c = s[i], len = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 0;
    if (!len || i + len > n) { o[k++] = 0xFFFD; i++; continue; }
    if (len == 2) c &= 0x1F; else if (len == 3) c &= 0x0F; else if (len == 4) c &= 0x07;
    int bad = 0;
    for (uint32_t j = 1; j < len; j++) { if ((s[i + j] & 0xC0) != 0x80) bad = 1; c = (c << 6) | (s[i + j] & 0x3F); }
    if (bad) { o[k++] = 0xFFFD; i++; continue; }
    i += len;
    if (c >= 0x10000) { c -= 0x10000; o[k++] = 0xD800 | (c >> 10); o[k++] = 0xDC00 | (c & 0x3FF); }
    else o[k++] = (uint16_t)c;
  }
  *out_len = k;
  return o;
}

typedef struct { char *b; size_t n, cap; } sb;
static void sb_add(sb *s, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
#include <stdarg.h>
static void sb_add(sb *s, const char *fmt, ...) {
  va_list ap;
  for (;;) {
    va_start(ap, fmt);
    int w = vsnprintf(s->b + s->n, s->cap - s->n, fmt, ap);
    va_end(ap);
    if ((size_t)w < s->cap - s->n) { s->n += w; return; }
    s->cap = s->cap * 2 + w + 64;
    s->b = realloc(s->b, s->cap);
  }
}

static void walk(TSTree *t, sb *s) {
  TSTreeCursor c = ts_tree_cursor_new(ts_tree_root_node(t));
  for (;;) {
    TSNode n = ts_tree_cursor_current_node(&c);
    const char *f = ts_tree_cursor_current_field_name(&c);
    TSPoint a = ts_node_start_point(n), b = ts_node_end_point(n);
    sb_add(s, "%u %u:%s %s %u-%u (%u,%u)-(%u,%u) st=%u,%u %d%d%d%d%d\n", ts_tree_cursor_current_depth(&c),
           ts_node_grammar_symbol(n), ts_node_grammar_type(n), f ? f : "-", ts_node_start_byte(n), ts_node_end_byte(n),
           a.row, a.column, b.row, b.column, ts_node_parse_state(n), ts_node_next_parse_state(n), ts_node_is_named(n),
           ts_node_is_missing(n), ts_node_is_extra(n), ts_node_is_error(n), ts_node_has_error(n));
    if (ts_tree_cursor_goto_first_child(&c)) continue;
    while (!ts_tree_cursor_goto_next_sibling(&c)) {
      if (!ts_tree_cursor_goto_parent(&c)) { ts_tree_cursor_delete(&c); return; }
    }
  }
}

static int compare_languages(const TSLanguage *a, const TSLanguage *b) {
  int bad = 0;
#define EQ(x) if ((x(a)) != (x(b))) { printf("  LANG MISMATCH %s\n", #x); bad++; }
  EQ(ts_language_symbol_count) EQ(ts_language_state_count) EQ(ts_language_field_count) EQ(ts_language_abi_version)
  if (bad) return bad;
  uint32_t ns = ts_language_symbol_count(a), nst = ts_language_state_count(a), nf = ts_language_field_count(a);
  for (uint32_t i = 0; i < ns; i++) {
    const char *x = ts_language_symbol_name(a, i), *y = ts_language_symbol_name(b, i);
    if ((x == NULL) != (y == NULL) || (x && strcmp(x, y))) { printf("  symbol name %u\n", i); bad++; }
    if (ts_language_symbol_type(a, i) != ts_language_symbol_type(b, i)) { printf("  symbol type %u\n", i); bad++; }
    if (x && ts_language_symbol_for_name(a, x, strlen(x), ts_language_symbol_type(a, i) == TSSymbolTypeRegular) !=
                 ts_language_symbol_for_name(b, x, strlen(x), ts_language_symbol_type(b, i) == TSSymbolTypeRegular)) {
      printf("  symbol_for_name %u\n", i); bad++;
    }
  }
  for (uint32_t i = 0; i <= nf; i++) {
    const char *x = ts_language_field_name_for_id(a, i), *y = ts_language_field_name_for_id(b, i);
    if ((x == NULL) != (y == NULL) || (x && strcmp(x, y))) { printf("  field %u\n", i); bad++; }
  }
  unsigned long long checked = 0;
  for (uint32_t st = 0; st < nst && bad < 20; st++) {
    /* only real grammar symbols: ts_language_symbol_count() includes aliases, which index past
       the parse table (reading them is out of bounds in the ORIGINAL grammar too) */
    for (uint32_t sym = 0; sym < a->symbol_count; sym++) {
      if (ts_language_next_state(a, st, sym) != ts_language_next_state(b, st, sym)) { printf("  next_state %u %u\n", st, sym); bad++; }
      checked++;
    }
    TSLookaheadIterator *ia = ts_lookahead_iterator_new(a, st), *ib = ts_lookahead_iterator_new(b, st);
    if (ia && ib) {
      for (;;) {
        bool ha = ts_lookahead_iterator_next(ia), hb = ts_lookahead_iterator_next(ib);
        if (ha != hb || (ha && ts_lookahead_iterator_current_symbol(ia) != ts_lookahead_iterator_current_symbol(ib))) {
          printf("  lookahead state %u\n", st); bad++; break;
        }
        if (!ha) break;
      }
    }
    if (ia) ts_lookahead_iterator_delete(ia);
    if (ib) ts_lookahead_iterator_delete(ib);
  }
  printf("LANG %s: symbols=%u states=%u fields=%u next_state_pairs=%llu %s\n", STR(LANG), ns, nst, nf, checked, bad ? "FAIL" : "ok");
  return bad;
}

int main(int argc, char **argv) {
  if (argc < 2) { fprintf(stderr, "usage: difftest <blob.sesz> <inputs...>\n"); return 2; }
  size_t zl;
  char *z = slurp(argv[1], &zl);
  if (!z) { fprintf(stderr, "no blob\n"); return 2; }
  int st = ses_language_provide_tables(STR(LANG), (const uint8_t *)z, zl);
  if (st) { printf("provide_tables: %d\n", st); return 1; }
  double t0 = now_ms();
  st = ses_language_load(STR(LANG));
  double t1 = now_ms();
  if (st) { printf("load: %d\n", st); return 1; }
  const TSLanguage *orig = CAT(ses_orig_tree_sitter_, LANG)(), *xf = CAT(tree_sitter_, LANG)();
  printf("LOAD %s: first-use %.2f ms (blob %zu bytes)\n", STR(LANG), t1 - t0, zl);
  int bad = compare_languages(orig, xf);

  TSParser *pa = ts_parser_new(), *pb = ts_parser_new();
  if (!ts_parser_set_language(pa, orig) || !ts_parser_set_language(pb, xf)) { printf("set_language failed\n"); return 1; }
  size_t files = 0, nodes_bytes = 0, bytes = 0, errors = 0;
  double ta = 0, tb = 0;
  for (int i = 2; i < argc; i++) {
    size_t n;
    char *src = slurp(argv[i], &n);
    if (!src) continue;
    uint32_t ul;
    uint16_t *u = to_utf16((unsigned char *)src, n, &ul);
    double x0 = now_ms();
    TSTree *A = ts_parser_parse_string_encoding(pa, NULL, (const char *)u, ul * 2, TSInputEncodingUTF16LE);
    double x1 = now_ms();
    TSTree *B = ts_parser_parse_string_encoding(pb, NULL, (const char *)u, ul * 2, TSInputEncodingUTF16LE);
    double x2 = now_ms();
    ta += x1 - x0; tb += x2 - x1;
    sb sa = {malloc(1024), 0, 1024}, sbb = {malloc(1024), 0, 1024};
    walk(A, &sa);
    walk(B, &sbb);
    char *ea = ts_node_string(ts_tree_root_node(A)), *eb = ts_node_string(ts_tree_root_node(B));
    if (sa.n != sbb.n || memcmp(sa.b, sbb.b, sa.n) || strcmp(ea, eb)) { printf("  TREE MISMATCH %s\n", argv[i]); bad++; }
    if (ts_node_has_error(ts_tree_root_node(A))) errors++;
    files++; nodes_bytes += sa.n; bytes += ul;
    free(ea); free(eb); free(sa.b); free(sbb.b); free(u); free(src);
    ts_tree_delete(A); ts_tree_delete(B);
  }
  printf("TREES %s: files=%zu utf16_units=%zu walk_bytes=%zu files_with_errors=%zu parse_ms orig=%.1f xform=%.1f %s\n",
         STR(LANG), files, bytes, nodes_bytes, errors, ta, tb, bad ? "FAIL" : "ok");
  ts_parser_delete(pa); ts_parser_delete(pb);
  return bad ? 1 : 0;
}
