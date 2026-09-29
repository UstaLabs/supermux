/*
 * bridge_test <build/gen dir>: ses_* ABI tests in C, built with -fsanitize=address,undefined by
 * `native/build.sh ctest` (and at the end of `gen`). Links javascript, json and html (bundled tables).
 * Each check prints "ok <name>" or "FAIL <name>: ..."; the exit status is the failure count.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <zlib.h>

#include "ses_registry.h"
#include "supermux_syntax.h"

static const char *gen_dir;

static int failures;
#define CHECK(name, cond, ...)                                  \
  do {                                                          \
    if (cond) printf("ok %s\n", name);                          \
    else { printf("FAIL %s: ", name); printf(__VA_ARGS__); printf("\n"); failures++; } \
  } while (0)

static uint16_t *u16(const char *ascii, uint32_t *len) {
  size_t n = strlen(ascii);
  uint16_t *o = malloc((n + 1) * sizeof(uint16_t));
  for (size_t i = 0; i < n; i++) o[i] = (unsigned char)ascii[i];
  *len = (uint32_t)n;
  return o;
}


typedef struct { const uint16_t *s; uint32_t len; } buffer_ctx;
static const uint16_t *read_buffer(void *ctx, uint32_t index, uint32_t *out_len) {
  buffer_ctx *b = ctx;
  if (index >= b->len) { *out_len = 0; return NULL; }
  *out_len = b->len - index;
  return b->s + index;
}
typedef buffer_ctx small_ctx;
static const uint16_t *read_small(void *ctx, uint32_t index, uint32_t *out_len) {
  small_ctx *b = ctx;
  if (index >= b->len) { *out_len = 0; return NULL; }
  *out_len = b->len - index < 3 ? b->len - index : 3;
  return b->s + index;
}

static ses_query *query(const char *lang, const char *src, ses_status *st) {
  uint32_t off = 0;
  int32_t type = 0;
  return ses_query_new(lang, src, (uint32_t)strlen(src), &off, &type, st);
}

/* Empty string values cost no UTF-16 units but each still needs a value slot. */
static void empty_any_of_values(void) {
  ses_status st;
  ses_query *q = query("javascript", "((identifier) @x (#any-of? @x \"\" \"\" \"\" \"\" \"\" \"\" \"\" \"\" \"\" \"\"))", &st);
  CHECK("empty any-of values compile", q && st == SES_OK, "status %d", st);
  ses_query_free(q);
}

/* Capture steps where #any-of? expects strings: a malformed predicate, refused. */
static void any_of_with_captures(void) {
  ses_status st;
  ses_query *q = query("javascript",
                       "((identifier) @a (#aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa? @a) (#any-of? @a @a @a @a @a @a @a @a @a @a))", &st);
  CHECK("any-of with capture values is a query error", !q && st == SES_ERR_QUERY, "q %p status %d", (void *)q, st);
  ses_query_free(q);
}

/* Test regex engine: regex 0 is "^a", decided on the first unit; -1 aborts. */
static int32_t starts_with_a(void *ctx, uint32_t id, const uint16_t *text, uint32_t len) {
  int *calls = ctx;
  (*calls)++;
  if (id != 0) return -1;
  return len > 0 && text[0] == 'a';
}

static int32_t fails(void *ctx, uint32_t id, const uint16_t *text, uint32_t len) {
  (void)ctx; (void)id; (void)text; (void)len;
  return -1;
}

static void match_callback(void) {
  uint32_t len;
  uint16_t *src = u16("abc; xyz; ab;", &len);
  ses_status st;
  ses_parser *p = ses_parser_new();
  ses_parser_set_language(p, "javascript");
  ses_tree *t = ses_parser_parse_utf16(p, NULL, src, len, &st);
  ses_query *q = query("javascript", "((identifier) @x (#match? @x \"^a\")) ((identifier) @y (#not-match? @y \"^a\"))", &st);
  uint32_t rl = 0;
  const char *re = q ? ses_query_regex(q, 0, &rl) : NULL;
  CHECK("one deduplicated regex", q && ses_query_regex_count(q) == 1 && rl == 2 && memcmp(re, "^a", 2) == 0,
        "count %u", q ? ses_query_regex_count(q) : 0);
  int32_t *a = NULL;
  uint32_t n = 0;
  int calls = 0;
  st = ses_query_captures_utf16(q, t, 0, len, src, len, starts_with_a, &calls, &a, &n, NULL);
  int32_t want[] = {0, 3, 0, 0, 5, 8, 1, 1, 10, 12, 0, 0};
  CHECK("match captures [start, end, capture, pattern]", st == SES_OK && n == 12 && memcmp(a, want, sizeof want) == 0,
        "status %d n %u", st, n);
  CHECK("the matcher ran", calls >= 3, "calls %d", calls);
  ses_free(a);
  a = NULL;
  st = ses_query_captures_utf16(q, t, 0, len, src, len, fails, NULL, &a, &n, NULL);
  CHECK("a failing matcher aborts the query", st == SES_ERR_CALLBACK && a == NULL, "status %d", st);
  st = ses_query_captures_utf16(q, t, 0, len, src, len, NULL, NULL, &a, &n, NULL);
  CHECK("no matcher: #match? predicates pass", st == SES_OK && n == 24, "status %d n %u", st, n);
  ses_free(a);
  ses_query_free(q);
  CHECK("one live tree", ses_debug_live_trees() == 1, "%lld", (long long)ses_debug_live_trees());
  ses_tree_free(t);
  CHECK("no live trees", ses_debug_live_trees() == 0, "%lld", (long long)ses_debug_live_trees());
  ses_parser_free(p);
  free(src);
}

static void pattern_settings(void) {
  ses_status st;
  ses_query *q = query("javascript", "((identifier) @x (#set! priority \"105\") (#is? local)) (number) @n", &st);
  uint32_t len = 0, l1 = 0;
  const uint8_t *b = q ? ses_query_pattern_settings(q, 0, &len) : NULL;
  static const uint8_t want[] = {1, 0xFF, 0xFF, 0xFF, 0xFF, 8, 0, 0, 0, 'p', 'r', 'i', 'o', 'r', 'i', 't', 'y', 3, 0, 0, 0, '1', '0', '5',
                                 2, 0xFF, 0xFF, 0xFF, 0xFF, 5, 0, 0, 0, 'l', 'o', 'c', 'a', 'l', 0xFF, 0xFF, 0xFF, 0xFF};
  CHECK("pattern settings packed", b && len == sizeof want && memcmp(b, want, len) == 0, "len %u", len);
  CHECK("a pattern without directives", q && ses_query_pattern_settings(q, 1, &l1) == NULL && l1 == 0 &&
        ses_query_pattern_count(q) == 2, "len %u", l1);
  ses_query_free(q);
}


/* Captures as a packed int array; the caller frees. */
static int32_t *caps(ses_query *q, ses_tree *t, const uint16_t *src, uint32_t len, uint32_t *n, int32_t *exceeded) {
  int32_t *a = NULL;
  *n = 0;
  ses_status st = ses_query_captures_utf16(q, t, 0, len, src, len, NULL, NULL, &a, n, exceeded);
  if (st != SES_OK) { printf("captures failed: %d\n", st); return NULL; }
  return a;
}

/* JavaScript parsed only inside the <script> element's content range of an HTML document. */
static void included_ranges(void) {
  const char *doc = "<p>x</p>\n<script>let a = 1;</script>";
  uint32_t len;
  uint16_t *src = u16(doc, &len);
  ses_status st;
  ses_parser *hp = ses_parser_new();
  ses_parser_set_language(hp, "html");
  ses_tree *ht = ses_parser_parse_utf16(hp, NULL, src, len, &st);
  ses_query *raw = query("html", "(script_element (raw_text) @c)", &st);
  uint32_t n = 0;
  int32_t *a = raw && ht ? caps(raw, ht, src, len, &n, NULL) : NULL;
  CHECK("html finds the script content", a && n == 4 && a[0] == 17 && a[1] == 27, "n %u", n);
  /* [start, end, startRow, startCol, endRow, endCol]: the content is on line 1, columns 8..18 */
  uint32_t range[6] = {a ? (uint32_t)a[0] : 0, a ? (uint32_t)a[1] : 0, 1, 8, 1, 18};
  ses_free(a);
  ses_parser *jp = ses_parser_new();
  ses_parser_set_language(jp, "javascript");
  st = ses_parser_set_included_ranges(jp, range, 6);
  CHECK("set included ranges", st == SES_OK, "status %d", st);
  ses_tree *jt = ses_parser_parse_utf16(jp, NULL, src, len, &st);
  ses_query *prog = query("javascript", "(program) @p \"let\" @k", &st);
  a = prog && jt ? caps(prog, jt, src, len, &n, NULL) : NULL;
  CHECK("the js root spans exactly the range, let at 17",
        a && n == 8 && a[0] == 17 && a[1] == 27 && a[4] == 17 && a[5] == 20, "n %u: %d-%d %d-%d", n,
        a ? a[0] : -1, a ? a[1] : -1, a && n >= 8 ? a[4] : -1, a && n >= 8 ? a[5] : -1);
  CHECK("the js tree has no error", jt && !ses_tree_has_error(jt), "sexp %s", "");
  ses_free(a);
  uint32_t bad[12] = {10, 20, 0, 10, 0, 20, 5, 30, 0, 5, 0, 30};
  CHECK("unordered ranges are refused", ses_parser_set_included_ranges(jp, bad, 12) == SES_ERR_INVALID_ARGUMENT, "accepted");
  CHECK("a count not a multiple of 6 is refused", ses_parser_set_included_ranges(jp, bad, 4) == SES_ERR_INVALID_ARGUMENT, "accepted");
  CHECK("reset to the whole document", ses_parser_set_included_ranges(jp, NULL, 0) == SES_OK, "refused");
  ses_tree *whole = ses_parser_parse_utf16(jp, NULL, src, len, &st);
  CHECK("after the reset the whole document is javascript", whole && ses_tree_has_error(whole), "no error");
  ses_tree_free(whole);
  ses_query_free(prog); ses_query_free(raw);
  ses_tree_free(jt); ses_tree_free(ht);
  ses_parser_free(jp); ses_parser_free(hp);
  free(src);
}

/* A timed-out parse resumes on the next call; ses_parser_reset discards it instead. */
static void resumable_parse(void) {
  enum { N = 20000 };
  char *doc = malloc(N * 8 + 3);
  char *w = doc;
  *w++ = '[';
  for (int i = 0; i < N; i++) { if (i) *w++ = ','; w += sprintf(w, "[1,\"a\"]"); }
  *w++ = ']';
  *w = 0;
  uint32_t len;
  uint16_t *src = u16(doc, &len);
  ses_status st;
  ses_parser *ref = ses_parser_new();
  ses_parser_set_language(ref, "json");
  ses_tree *want = ses_parser_parse_utf16(ref, NULL, src, len, &st);
  char *want_s = ses_tree_root_sexp(want);
  ses_parser *p = ses_parser_new();
  ses_parser_set_language(p, "json");
  ses_parser_set_timeout_micros(p, 200);
  int slices = 0;
  ses_tree *t = NULL;
  while (!t && slices < 100000) { t = ses_parser_parse_utf16(p, NULL, src, len, &st); slices++; }
  char *got_s = t ? ses_tree_root_sexp(t) : NULL;
  CHECK("a parse in 200 us slices resumes to the same tree", t && slices > 1 && strcmp(want_s, got_s) == 0, "slices %d", slices);
  ses_free(got_s);
  ses_tree_free(t);
  /* a cancelled parse, reset, then a different document parses from scratch */
  st = SES_OK;
  t = ses_parser_parse_utf16(p, NULL, src, len, &st);
  CHECK("the first slice times out", !t && st == SES_ERR_TIMEOUT, "status %d", st);
  ses_tree_free(t);
  ses_parser_reset(p);
  ses_parser_set_timeout_micros(p, 0);
  uint32_t l2;
  uint16_t *small = u16("{\"k\": 1}", &l2);
  t = ses_parser_parse_utf16(p, NULL, small, l2, &st);
  got_s = t ? ses_tree_root_sexp(t) : NULL;
  CHECK("after a reset another document parses cleanly",
        got_s && strcmp(got_s, "(document (object (pair key: (string (string_content)) value: (number))))") == 0,
        "%s", got_s ? got_s : "(null)");
  ses_free(got_s);
  ses_tree_free(t);
  ses_free(want_s);
  ses_tree_free(want);
  ses_parser_free(p);
  ses_parser_free(ref);
  free(small);
  free(src);
  free(doc);
}

/* #set! with a capture where the value belongs is malformed. */
static void set_with_capture_value(void) {
  ses_status st;
  ses_query *q = query("javascript", "((identifier) @c (#set! k @c))", &st);
  CHECK("#set! with a capture value is a query error", !q && st == SES_ERR_QUERY, "q %p status %d", (void *)q, st);
  ses_query_free(q);
}

/* Two #set! directives on one pattern keep their capture ids. */
static void settings_keep_capture_id(void) {
  ses_status st;
  ses_query *q = query("javascript", "((identifier) @a (number) @b (#set! @a k \"1\") (#set! @b k \"2\"))", &st);
  uint32_t len = 0;
  const uint8_t *s = q ? ses_query_pattern_settings(q, 0, &len) : NULL;
  static const uint8_t want[] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 'k', 1, 0, 0, 0, '1',
                                 1, 1, 0, 0, 0, 1, 0, 0, 0, 'k', 1, 0, 0, 0, '2'};
  CHECK("settings keep the capture id", s && len == sizeof want && memcmp(s, want, len) == 0, "len %u", len);
  ses_query_free(q);
}

static int32_t count_calls(void *ctx, uint32_t id, const uint16_t *text, uint32_t len) {
  int *calls = ctx;
  (*calls)++;
  (void)id;
  return len > 0 && text[0] == 'x';
}

/* A pattern with 3 captures and one #match?: one regex upcall per match, not per capture. */
static void match_callback_once_per_match(void) {
  uint32_t len;
  uint16_t *src = u16("let x1 = 1; let x2 = 2; let y = 3;", &len);
  ses_status st;
  ses_parser *p = ses_parser_new();
  ses_parser_set_language(p, "javascript");
  ses_tree *t = ses_parser_parse_utf16(p, NULL, src, len, &st);
  ses_query *q = query("javascript",
                       "((variable_declarator name: (identifier) @a value: (number) @b) @c (#match? @a \"^x\"))", &st);
  int32_t *a = NULL;
  uint32_t n = 0;
  int calls = 0;
  st = ses_query_captures_utf16(q, t, 0, len, src, len, count_calls, &calls, &a, &n, NULL);
  CHECK("two matching declarators, three captures each", st == SES_OK && n == 24, "status %d n %u", st, n);
  CHECK("one regex upcall per match", calls == 3, "calls %d", calls);
  ses_free(a);
  ses_query_free(q);
  ses_tree_free(t);
  ses_parser_free(p);
  free(src);
}

/* Matches that never finish pile up past SES_QUERY_MATCH_LIMIT: reported, not silent. Each of P
   identical patterns keeps one in-progress state per number seen (none ever finds its string), so
   P * N states hold captures. Many patterns rather than one: tree-sitter compares the states of
   one pattern pairwise at every node, so a single pattern with 65536 states would take hours. */
static void match_limit_exceeded(void) {
  enum { N = 72, P = 1024 };
  char *doc = malloc(N * 2 + 3);
  char *w = doc;
  *w++ = '[';
  for (int i = 0; i < N; i++) { if (i) *w++ = ','; *w++ = '1'; }
  *w++ = ']';
  *w = 0;
  static const char pat[] = "(array (number) @a (string)) ";
  char *qs = malloc(P * (sizeof pat - 1) + 1);
  for (int i = 0; i < P; i++) memcpy(qs + i * (sizeof pat - 1), pat, sizeof pat - 1);
  qs[P * (sizeof pat - 1)] = 0;
  uint32_t len;
  uint16_t *src = u16(doc, &len);
  ses_status st;
  ses_parser *p = ses_parser_new();
  ses_parser_set_language(p, "json");
  ses_tree *t = ses_parser_parse_utf16(p, NULL, src, len, &st);
  ses_query *q = query("json", qs, &st);
  uint32_t n = 0;
  int32_t exceeded = -1;
  int32_t *a = q && t ? caps(q, t, src, len, &n, &exceeded) : NULL;
  CHECK("the match limit was exceeded", exceeded == 1, "exceeded %d n %u", exceeded, n);
  ses_free(a);
  ses_query_free(q);
  q = query("json", "(number) @n", &st);
  exceeded = -1;
  a = q ? caps(q, t, src, len, &n, &exceeded) : NULL;
  CHECK("a plain query does not exceed it", exceeded == 0 && n == N * 4, "exceeded %d n %u", exceeded, n);
  ses_free(a);
  ses_query_free(q);
  ses_tree_free(t);
  ses_parser_free(p);
  free(src);
  free(qs);
  free(doc);
}


/* ses_query_matches groups captures per match: [pattern, n, (start, end, capture)*]*. */
static uint32_t record_len(const int32_t *a) {
  uint32_t l = 2;
  for (int32_t c = 0; c < a[1]; c++) l += 4 + 3 * (uint32_t)a[l + 3];
  return l;
}

static int has_record(const int32_t *a, uint32_t n, const int32_t *rec, uint32_t len) {
  for (uint32_t i = 0; i < n;) {
    uint32_t l = record_len(a + i);
    if (l == len && memcmp(a + i, rec, len * sizeof *rec) == 0) return 1;
    i += l;
  }
  return 0;
}

static void matches_group_captures(void) {
  uint32_t len;
  uint16_t *src = u16("let a = 1; let bb = 22;", &len);
  ses_status st;
  ses_parser *p = ses_parser_new();
  ses_parser_set_language(p, "javascript");
  ses_tree *t = ses_parser_parse_utf16(p, NULL, src, len, &st);
  ses_query *q = query("javascript",
                       "(variable_declarator name: (identifier) @n value: (number) @v) ((identifier) @x (#eq? @x \"bb\"))", &st);
  int32_t *a = NULL;
  uint32_t n = 0;
  int32_t exceeded = -1;
  buffer_ctx b = {src, len};
  st = q ? ses_query_matches(q, t, 0, len, read_buffer, &b, NULL, NULL, UINT32_MAX, &a, &n, &exceeded) : -1;
  static const int32_t d1[] = {0, 2, 4, 5, 0, 0, 8, 9, 1, 0}, d2[] = {0, 2, 15, 17, 0, 0, 20, 22, 1, 0}, bb[] = {1, 1, 15, 17, 2, 0};
  CHECK("matches group captures per match; a failed predicate drops its match",
        st == SES_OK && n == 26 && exceeded == 0 && has_record(a, n, d1, 10) && has_record(a, n, d2, 10) && has_record(a, n, bb, 6),
        "status %d n %u exceeded %d", st, n, exceeded);
  ses_free(a);
  /* the children of one capture: a declarator's children are name, "=", value */
  ses_query *dq = query("javascript", "(variable_declarator) @d", &st);
  st = dq ? ses_query_matches(dq, t, 0, 10, read_buffer, &b, NULL, NULL, 0, &a, &n, &exceeded) : -1;
  static const int32_t kids[] = {0, 1, 4, 9, 0, 3, 4, 5, 1, 6, 7, 0, 8, 9, 1};
  CHECK("the requested capture's children, named or not", st == SES_OK && n == 15 && memcmp(a, kids, sizeof kids) == 0,
        "status %d n %u", st, n);
  ses_free(a);
  ses_query_free(dq);
  ses_query_free(q);
  ses_tree_free(t);
  ses_parser_free(p);
  free(src);
}

static uint8_t *slurp(const char *path, size_t *len) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  uint8_t *b = malloc(n ? (size_t)n : 1);
  *len = fread(b, 1, (size_t)n, f);
  fclose(f);
  return b;
}

static void sha256_vector(void) {
  static const uint8_t abc[32] = {0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
                                  0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad};
  uint8_t d[32];
  ses_sha256((const uint8_t *)"abc", 3, d);
  CHECK("sha256(abc)", memcmp(d, abc, 32) == 0, "wrong digest");
}

/* The cost of the first-load check on the largest blob (the bundled-blob check must stay < 5 ms). */
static void sha256_cost(void) {
  char path[4096];
  snprintf(path, sizeof path, "%s/fsharp/fsharp.sesz", gen_dir);
  size_t n;
  uint8_t *b = slurp(path, &n);
  if (!b) { printf("skip sha256 cost: no %s\n", path); return; }
  uint8_t d[32];
  struct timespec t0, t1;
  double best = 1e9;
  for (int i = 0; i < 5; i++) {
    clock_gettime(CLOCK_MONOTONIC, &t0);
    ses_sha256(b, n, d);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    double ms = (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
    if (ms < best) best = ms;
  }
  printf("SHA256 fsharp.sesz: %zu bytes in %.2f ms (best of 5; this build is sanitized, so slower than a release)\n", n, best);
  free(b);
}

/* A blob with a valid header and valid zlib whose CONTENT changed is refused; the original is not. */
static void tampered_blob(void) {
  char path[4096];
  snprintf(path, sizeof path, "%s/json/json.sesz", gen_dir);
  size_t n;
  uint8_t *z = slurp(path, &n);
  CHECK("json.sesz readable", z != NULL, "%s", path);
  if (!z) return;
  uLongf raw_size = (uLongf)(z[8] | z[9] << 8 | z[10] << 16 | (uint32_t)z[11] << 24), got = raw_size;
  uint8_t *raw = malloc(raw_size);
  int ok = uncompress(raw, &got, z + 24, (uLong)(n - 24)) == Z_OK && got == raw_size;
  raw[raw_size / 2] ^= 0x5A; /* inside the table data, past every header */
  uLongf zl = compressBound(raw_size);
  uint8_t *t = malloc(24 + zl);
  memcpy(t, z, 24);
  ok = ok && compress2(t + 24, &zl, raw, raw_size, 9) == Z_OK;
  t[12] = (uint8_t)zl; t[13] = (uint8_t)(zl >> 8); t[14] = (uint8_t)(zl >> 16); t[15] = (uint8_t)(zl >> 24);
  CHECK("tampered blob built", ok, "zlib");
  CHECK("tampered content is refused", ses_language_provide_tables("json", t, 24 + zl) == SES_ERR_BAD_TABLES, "accepted");
  CHECK("the original is accepted", ses_language_provide_tables("json", z, n) == SES_OK, "refused");
  free(t); free(raw); free(z);
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IOLBF, 0); /* progress is visible in a log */
  gen_dir = argc > 1 ? argv[1] : "build/gen";
  sha256_vector();
  sha256_cost();
  tampered_blob();
  empty_any_of_values();
  any_of_with_captures();
  match_callback();
  pattern_settings();
  included_ranges();
  resumable_parse();
  set_with_capture_value();
  settings_keep_capture_id();
  match_callback_once_per_match();
  match_limit_exceeded();
  matches_group_captures();
  printf("%s: %d failure(s)\n", failures ? "FAIL" : "PASS", failures);
  return failures;
}
