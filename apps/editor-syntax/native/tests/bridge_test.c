/*
 * bridge_test <build/gen dir>: ses_* ABI tests in C, built with -fsanitize=address,undefined by
 * `native/build.sh ctest` (and at the end of `gen`). Links javascript and json (bundled tables).
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
  st = ses_query_captures_utf16(q, t, 0, len, src, len, starts_with_a, &calls, &a, &n);
  int32_t want[] = {0, 3, 0, 0, 5, 8, 1, 1, 10, 12, 0, 0};
  CHECK("match captures [start, end, capture, pattern]", st == SES_OK && n == 12 && memcmp(a, want, sizeof want) == 0,
        "status %d n %u", st, n);
  CHECK("the matcher ran", calls >= 3, "calls %d", calls);
  ses_free(a);
  a = NULL;
  st = ses_query_captures_utf16(q, t, 0, len, src, len, fails, NULL, &a, &n);
  CHECK("a failing matcher aborts the query", st == SES_ERR_CALLBACK && a == NULL, "status %d", st);
  st = ses_query_captures_utf16(q, t, 0, len, src, len, NULL, NULL, &a, &n);
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
  gen_dir = argc > 1 ? argv[1] : "build/gen";
  sha256_vector();
  sha256_cost();
  tampered_blob();
  empty_any_of_values();
  any_of_with_captures();
  match_callback();
  pattern_settings();
  printf("%s: %d failure(s)\n", failures ? "FAIL" : "PASS", failures);
  return failures;
}
