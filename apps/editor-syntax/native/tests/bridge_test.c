/*
 * bridge_test <build/gen dir>: ses_* ABI tests in C, built with -fsanitize=address,undefined by
 * `native/build.sh ctest` (and at the end of `gen`). Links javascript and json (bundled tables).
 * Each check prints "ok <name>" or "FAIL <name>: ..."; the exit status is the failure count.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "supermux_syntax.h"

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
  ses_tree_free(t);
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

int main(int argc, char **argv) {
  (void)argc; (void)argv;
  empty_any_of_values();
  any_of_with_captures();
  match_callback();
  pattern_settings();
  printf("%s: %d failure(s)\n", failures ? "FAIL" : "PASS", failures);
  return failures;
}
