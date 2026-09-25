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

int main(int argc, char **argv) {
  (void)argc; (void)argv;
  empty_any_of_values();
  any_of_with_captures();
  printf("%s: %d failure(s)\n", failures ? "FAIL" : "PASS", failures);
  return failures;
}
