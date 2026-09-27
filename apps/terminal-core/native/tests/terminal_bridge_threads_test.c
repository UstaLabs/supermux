/*
 * Concurrency test for the st_* handle table (POSIX threads; built and run
 * for linux targets on a matching host, and under ThreadSanitizer there).
 *
 * The documented contract: DIFFERENT handles may be used from different
 * threads concurrently (create, every call, destroy); the SAME handle must be
 * serialized by the caller. Each thread here owns its own handles and runs
 * full create -> feed -> read -> acknowledge -> drain -> input -> destroy
 * cycles, so slots are claimed and released concurrently and reused under
 * contention. Every thread checks its own terminal's content, so a handle
 * resolving to another thread's engine would be detected.
 */
#define _POSIX_C_SOURCE 200112L
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "supermux_terminal.h"

#define THREADS 6
#define CYCLES 150

typedef struct {
  int id;
  int cycles_ok;
  int failures;
  st_handle last_handle;
} Worker;

static bool contains(const uint8_t *buf, uint32_t len, const char *needle) {
  size_t n = strlen(needle);
  for (uint32_t i = 0; i + n <= len; i++)
    if (memcmp(buf + i, needle, n) == 0) return true;
  return false;
}

static void *run(void *arg) {
  Worker *w = arg;
  char text[64];
  for (int c = 0; c < CYCLES; c++) {
    st_handle h = 0, h2 = 0;
    if (st_create(ST_ABI_VERSION, 40, 10, 8, 16, 100, 1u << 20, NULL, &h) != ST_OK ||
        st_create(ST_ABI_VERSION, 20, 5, 8, 16, 0, 0, NULL, &h2) != ST_OK) {
      w->failures++;
      st_destroy(h);
      continue;
    }
    bool ok = true;
    for (int i = 0; i < 5; i++) {
      int k = snprintf(text, sizeof(text), "T%dC%dL%d\r\n\x1b[6n", w->id, c, i);
      ok = ok && st_feed(h, (const uint8_t *)text, (uint32_t)k, ST_ORIGIN_LIVE) == ST_OK;
      ok = ok && st_feed(h2, (const uint8_t *)"x", 1, ST_ORIGIN_LIVE) == ST_OK;
    }
    uint8_t *buf = NULL;
    uint32_t len = 0;
    if (st_read_viewport(h, ST_READ_FORCE_FULL, &buf, &len, NULL) == ST_OK) {
      int64_t gen = 0;
      memcpy(&gen, buf + 12, 8); /* little-endian hosts only (this test's targets) */
      ok = ok && st_acknowledge(h, gen) == ST_OK;
      st_free_buffer(buf);
    } else {
      ok = false;
    }
    if (st_drain_effects(h, &buf, &len) == ST_OK) {
      ok = ok && contains(buf, len, "\x1b[");
      st_free_buffer(buf);
    } else {
      ok = false;
    }
    ok = ok && st_key(h, 0x04, (const uint8_t *)"a", 1, 0, 0) == ST_OK;
    /* our own content, not another thread's: row 4 holds this thread's last line */
    ok = ok && st_select(h, 1, 4, 0, 4, 39) == ST_OK;
    if (st_selected_text(h, &buf, &len) == ST_OK) {
      snprintf(text, sizeof(text), "T%dC%dL4", w->id, c);
      ok = ok && len == 12 + 4 + strlen(text) && memcmp(buf + 16, text, strlen(text)) == 0;
      st_free_buffer(buf);
    } else {
      ok = false;
    }
    bool destroyed = st_destroy(h2) == ST_OK;
    destroyed = st_destroy(h) == ST_OK && destroyed;
    ok = ok && destroyed;
    ok = ok && st_feed(h, (const uint8_t *)"x", 1, 0) == ST_ERR_INVALID_HANDLE; /* our closed handle stays dead */
    if (ok) w->cycles_ok++;
    else w->failures++;
    w->last_handle = h;
  }
  return NULL;
}

int main(void) {
  pthread_t t[THREADS];
  Worker w[THREADS];
  memset(w, 0, sizeof(w));
  for (int i = 0; i < THREADS; i++) {
    w[i].id = i;
    if (pthread_create(&t[i], NULL, run, &w[i]) != 0) {
      printf("FAIL - pthread_create\n");
      return 1;
    }
  }
  int ok = 0, failures = 0;
  for (int i = 0; i < THREADS; i++) {
    pthread_join(t[i], NULL);
    ok += w[i].cycles_ok;
    failures += w[i].failures;
  }
  /* The table is empty again: all slots can be claimed. */
  st_handle probe = 0;
  bool reusable = st_create(ST_ABI_VERSION, 2, 1, 8, 16, 0, 0, NULL, &probe) == ST_OK && st_destroy(probe) == ST_OK;
  printf("%s - %d threads x %d cycles (2 handles each): %d ok, %d failed; table reusable: %s\n",
         failures == 0 && ok == THREADS * CYCLES && reusable ? "ok  " : "FAIL", THREADS, CYCLES, ok, failures,
         reusable ? "yes" : "no");
  if (failures || ok != THREADS * CYCLES || !reusable) {
    printf("THREADS TEST FAILED\n");
    return 1;
  }
  printf("THREADS TEST PASSED\n");
  return 0;
}
