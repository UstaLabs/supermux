/*
 * Grammar tables loader: inflates a grammar's .sesz blob on first use, validates it against what
 * the grammar's code was generated with, builds the string-pointer arrays and lets the grammar fill
 * its TSLanguage. Blob formats: tools/sestables.py. Loaded tables are never freed (like the static
 * tables they replace).
 *
 * Integrity: every provided .sesz must hash (SHA-256, whole file) to the value compiled into its
 * grammar's code, or it is refused before it is accepted. A bundled blob sits in the same read-only
 * binary as that hash, so it is not re-hashed by default: fsharp's 835 KB costs 5-10 ms on an M-series
 * Mac and 14-67 ms on the Android emulator, over the 5 ms first-use budget. -DSES_VERIFY_BUNDLED=1 turns
 * it on. A failed load is remembered: the same blob is never inflated twice, and only a newly provided
 * blob is tried again.
 */
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

#include "ses_grammar.h"
#include "ses_registry.h"
#include "supermux_syntax.h"

struct ses_tables {
  const uint8_t *payload;
  uint32_t count;
  const void **at; /* per table: bytes pointer, or the rebuilt const char *[] */
};

typedef struct provided {
  ses_grammar *grammar;
  uint8_t *bytes;
  size_t len;
  struct provided *next;
} provided;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static provided *g_provided; /* guarded by g_lock */

static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint64_t rd64(const uint8_t *p) { return (uint64_t)rd32(p) | (uint64_t)rd32(p + 4) << 32; }

#ifndef SES_VERIFY_BUNDLED
#define SES_VERIFY_BUNDLED 0
#endif

/* ---- SHA-256 (FIPS 180-4). Written for this file and dedicated to the public domain (CC0). ---- */

static const uint32_t SHA_K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha_block(uint32_t h[8], const uint8_t *p) {
  uint32_t w[64];
  for (int i = 0; i < 16; i++)
    w[i] = (uint32_t)p[4 * i] << 24 | (uint32_t)p[4 * i + 1] << 16 | (uint32_t)p[4 * i + 2] << 8 | p[4 * i + 3];
  for (int i = 16; i < 64; i++) {
    uint32_t s0 = ROR(w[i - 15], 7) ^ ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
    uint32_t s1 = ROR(w[i - 2], 17) ^ ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
  }
  uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
  for (int i = 0; i < 64; i++) {
    uint32_t t1 = k + (ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25)) + ((e & f) ^ (~e & g)) + SHA_K[i] + w[i];
    uint32_t t2 = (ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
    k = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
  }
  h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += k;
}

void ses_sha256(const uint8_t *data, size_t len, uint8_t out[32]) {
  uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
  size_t i = 0;
  for (; i + 64 <= len; i += 64) sha_block(h, data + i);
  uint8_t tail[128] = {0};
  size_t r = len - i, tl = r + 1 + 8 <= 64 ? 64 : 128;
  if (r) memcpy(tail, data + i, r);
  tail[r] = 0x80;
  uint64_t bits = (uint64_t)len * 8;
  for (int k = 0; k < 8; k++) tail[tl - 1 - k] = (uint8_t)(bits >> (8 * k));
  sha_block(h, tail);
  if (tl == 128) sha_block(h, tail + 64);
  for (int k = 0; k < 8; k++) {
    out[4 * k] = (uint8_t)(h[k] >> 24); out[4 * k + 1] = (uint8_t)(h[k] >> 16);
    out[4 * k + 2] = (uint8_t)(h[k] >> 8); out[4 * k + 3] = (uint8_t)h[k];
  }
}

static int sha_matches(const ses_grammar *g, const uint8_t *z, size_t len) {
  uint8_t d[32];
  ses_sha256(z, len, d);
  return g->sha256 && memcmp(d, g->sha256, 32) == 0;
}

/* -------------------------------------------------------------- loading --- */

#define SESZ_HEADER 24u
#define SEST_HEADER 32u

const void *ses_tables_at(const ses_tables *t, uint32_t index) {
  return index < t->count ? t->at[index] : NULL;
}

/* Header-only check (no inflate): magic, format, hash. */
static int32_t check_sesz(const ses_grammar *g, const uint8_t *z, size_t len) {
  if (len < SESZ_HEADER || memcmp(z, "SESZ", 4) != 0 || rd32(z + 4) != 1) return SES_ERR_BAD_TABLES;
  if (rd64(z + 16) != g->hash) return SES_ERR_BAD_TABLES;
  if ((size_t)rd32(z + 12) + SESZ_HEADER != len) return SES_ERR_BAD_TABLES;
  return SES_OK;
}

static int32_t load_locked(ses_grammar *g, const uint8_t *z, size_t len) {
  int32_t st = check_sesz(g, z, len);
  if (st) return st;
  uLongf raw_size = rd32(z + 8);
  uint8_t *raw = malloc(raw_size ? raw_size : 1);
  if (!raw) return SES_ERR_OUT_OF_MEMORY;
  uLongf got = raw_size;
  if (uncompress(raw, &got, z + SESZ_HEADER, (uLong)(len - SESZ_HEADER)) != Z_OK || got != raw_size) goto bad;
  if (raw_size < SEST_HEADER || memcmp(raw, "SEST", 4) != 0 || rd16(raw + 4) != 1) goto bad;
  uint32_t n = rd16(raw + 6);
  if (n != g->table_count || rd32(raw + 8) != g->abi || rd64(raw + 16) != g->hash || rd64(raw + 24) != raw_size) goto bad;
  if (SEST_HEADER + 16ull * n > raw_size) goto bad;

  size_t strings = 0;
  for (uint32_t i = 0; i < n; i++) {
    const uint8_t *e = raw + SEST_HEADER + 16 * i;
    uint32_t off = rd32(e), size = rd32(e + 4), count = rd32(e + 8), kind = rd16(e + 12);
    const ses_table_spec *s = &g->specs[i];
    if (kind != s->kind || size != s->size || count != s->count) goto bad;
    if ((off & 15) || (uint64_t)off + size > raw_size) goto bad;
    if (kind == SES_TABLE_STRINGS) strings += count;
  }
  ses_tables *t = malloc(sizeof *t + n * sizeof(void *) + strings * sizeof(char *));
  if (!t) { free(raw); return SES_ERR_OUT_OF_MEMORY; }
  t->payload = raw;
  t->count = n;
  t->at = (const void **)(t + 1);
  const char **pool_ptrs = (const char **)(t->at + n);
  for (uint32_t i = 0; i < n; i++) {
    const uint8_t *e = raw + SEST_HEADER + 16 * i;
    uint32_t off = rd32(e), size = rd32(e + 4), count = rd32(e + 8), kind = rd16(e + 12);
    if (kind == SES_TABLE_BYTES) { t->at[i] = raw + off; continue; }
    /* strings: u32 offsets[count], then the pool; every string must end inside the table */
    if ((uint64_t)count * 4 > size) { free(t); goto bad; }
    const char *pool = (const char *)raw + off + 4ull * count;
    size_t pool_size = size - 4ull * count;
    for (uint32_t k = 0; k < count; k++) {
      uint32_t so = rd32(raw + off + 4 * k);
      if (so == 0xFFFFFFFFu) { pool_ptrs[k] = NULL; continue; }
      if (so >= pool_size || !memchr(pool + so, 0, pool_size - so)) { free(t); goto bad; }
      pool_ptrs[k] = pool + so;
    }
    t->at[i] = pool_ptrs;
    pool_ptrs += count;
  }
  g->fill(t); /* t and raw live forever: the TSLanguage points into them */
  atomic_store_explicit(&g->loaded, g->language, memory_order_release);
  return SES_OK;
bad:
  free(raw);
  return SES_ERR_BAD_TABLES;
}

static provided *find_provided(ses_grammar *g) {
  for (provided *p = g_provided; p; p = p->next)
    if (p->grammar == g) return p;
  return NULL;
}

/* Unlink and free a provided copy: after its load (which copied what it needs), or on a refusal. */
static void drop_provided(provided *p) {
  for (provided **pp = &g_provided; *pp; pp = &(*pp)->next)
    if (*pp == p) { *pp = p->next; break; }
  free(p->bytes);
  free(p);
}

const ses_registry_entry *ses_registry_find(const char *name) {
  if (!name) return NULL;
  for (uint32_t i = 0; i < ses_registry_count; i++)
    if (strcmp(ses_registry[i].name, name) == 0) return &ses_registry[i];
  return NULL;
}

static const ses_registry_entry *entry_for(ses_grammar *g) {
  for (uint32_t i = 0; i < ses_registry_count; i++)
    if (ses_registry[i].grammar() == g) return &ses_registry[i];
  return NULL;
}

const void *ses_grammar_language(ses_grammar *g) {
  void *lang = atomic_load_explicit(&g->loaded, memory_order_acquire);
  if (lang) return lang;
  pthread_mutex_lock(&g_lock);
  lang = atomic_load_explicit(&g->loaded, memory_order_relaxed);
  if (!lang) {
    provided *p = find_provided(g);
    if (p) {
      /* sha256-checked when provided; inflated once, then the copy goes (load_locked copied out) */
      g->status = load_locked(g, p->bytes, p->len);
      drop_provided(p);
    } else if (g->status == 0) {
      /* never tried (a failure stays cached until another blob is provided) */
      const ses_registry_entry *e = entry_for(g);
      if (!e || !e->blob) g->status = SES_ERR_NO_TABLES;
      else if (SES_VERIFY_BUNDLED && !sha_matches(g, e->blob, *e->blob_size)) g->status = SES_ERR_BAD_TABLES;
      else g->status = load_locked(g, e->blob, *e->blob_size);
    }
    lang = atomic_load_explicit(&g->loaded, memory_order_relaxed);
  }
  pthread_mutex_unlock(&g_lock);
  return lang;
}

int32_t ses_tables_provide(ses_grammar *g, const uint8_t *z, size_t len) {
  int32_t st = check_sesz(g, z, len);
  if (st) return st;
  if (!sha_matches(g, z, len)) return SES_ERR_BAD_TABLES; /* before anything is accepted */
  uint8_t *copy = malloc(len);
  if (!copy) return SES_ERR_OUT_OF_MEMORY;
  memcpy(copy, z, len);
  pthread_mutex_lock(&g_lock);
  if (atomic_load_explicit(&g->loaded, memory_order_relaxed)) { /* already loaded: nothing to do */
    pthread_mutex_unlock(&g_lock);
    free(copy);
    return SES_OK;
  }
  provided *p = find_provided(g);
  if (p) { free(p->bytes); }
  else {
    p = calloc(1, sizeof *p);
    if (!p) { pthread_mutex_unlock(&g_lock); free(copy); return SES_ERR_OUT_OF_MEMORY; }
    p->grammar = g; p->next = g_provided; g_provided = p;
  }
  p->bytes = copy; p->len = len;
  g->status = 0; /* a new blob: try loading again */
  pthread_mutex_unlock(&g_lock);
  return SES_OK;
}

int ses_tables_available(ses_grammar *g, const ses_registry_entry *e) {
  if (atomic_load_explicit(&g->loaded, memory_order_acquire)) return 1;
  if (e && e->blob) return 1;
  pthread_mutex_lock(&g_lock);
  int r = find_provided(g) != NULL;
  pthread_mutex_unlock(&g_lock);
  return r;
}

int32_t ses_grammar_status(ses_grammar *g) {
  pthread_mutex_lock(&g_lock);
  int32_t st = g->status;
  pthread_mutex_unlock(&g_lock);
  return st;
}
