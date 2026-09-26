/*
 * wasm32 only: fixed trampolines for the ses_* entry points that take a callback.
 *
 * A wasm module cannot call a JS function through a C function pointer unless that function is
 * added to the module's function table at run time. Instead the callbacks are C functions compiled
 * in here, and they call two IMPORTED functions (env.ses_host_read, env.ses_host_match) that the
 * JS loader (syntax-loader.mjs) implements by calling Kotlin. The C context pointer is a host
 * context id (a u32 the Kotlin side maps to its TextSource / regex matcher); 0 means "none", which
 * passes a NULL callback, exactly like the native bindings do for a null source / matcher.
 *
 * Text crosses as UTF-16 into one grow-only scratch buffer (ses_wasm_scratch): the host writes a
 * chunk there during ses_host_read and returns its length. The chunk stays valid until the next
 * read, as ses_read_fn requires. Single-threaded: the module is built for wasm32-wasi without
 * threads.
 */
#include <stdint.h>
#include <stdlib.h>

#include "supermux_syntax.h"

#if !defined(__wasm__) || defined(_REENTRANT)
#error "syntax_wasm.c is for single-threaded wasm32-wasi only (one scratch buffer, no locks)"
#endif

/* Largest chunk the scratch buffer holds: its byte size must fit a 32-bit size_t with room to spare. */
#define SCRATCH_MAX_UNITS (UINT32_C(1) << 29)

#define IMPORT(name) __attribute__((import_module("env"), import_name(name)))

/* Write the document's UTF-16 text from [index] into ses_wasm_scratch(n) and return n (0: end). */
IMPORT("ses_host_read") uint32_t ses_host_read(uint32_t ctx, uint32_t index);
/* Does regex [regex_id] of the context's query match somewhere in text[0..len)? 1, 0, or < 0 (failed). */
IMPORT("ses_host_match") int32_t ses_host_match(uint32_t ctx, uint32_t regex_id, const uint16_t *text, uint32_t len);

static uint16_t *g_scratch;
static uint32_t g_scratch_cap;

/** A buffer of at least [units] UTF-16 units for the host to write a chunk into; NULL when out of memory. */
SES_API uint16_t *ses_wasm_scratch(uint32_t units) {
  if (units > SCRATCH_MAX_UNITS) return NULL;
  if (!g_scratch || units > g_scratch_cap) {
    uint32_t cap = g_scratch_cap ? g_scratch_cap : 4096;
    while (cap < units) cap = cap >= SCRATCH_MAX_UNITS / 2 ? SCRATCH_MAX_UNITS : cap * 2;
    uint16_t *b = realloc(g_scratch, (size_t)cap * sizeof(uint16_t));
    if (!b) return NULL;
    g_scratch = b;
    g_scratch_cap = cap;
  }
  return g_scratch;
}

static const uint16_t *wasm_read(void *ctx, uint32_t index, uint32_t *out_len) {
  uint32_t n = ses_host_read((uint32_t)(uintptr_t)ctx, index);
  /* read g_scratch after the call: the host may have grown (moved) it */
  if (n > g_scratch_cap) n = 0; /* the host never wrote more than it asked room for */
  *out_len = n;
  return n ? g_scratch : NULL;
}

static int32_t wasm_match(void *ctx, uint32_t regex_id, const uint16_t *text, uint32_t len) {
  return ses_host_match((uint32_t)(uintptr_t)ctx, regex_id, text, len);
}

#define CTX(id) ((void *)(uintptr_t)(id))

/** ses_parser_parse reading through host context [read_ctx] (non-zero). */
SES_API ses_tree *ses_wasm_parser_parse(ses_parser *parser, const ses_tree *old_tree, uint32_t read_ctx, ses_status *out_status) {
  return ses_parser_parse(parser, old_tree, read_ctx ? wasm_read : NULL, CTX(read_ctx), out_status);
}

/** ses_query_captures; a context id of 0 passes no reader / no matcher. */
SES_API ses_status ses_wasm_query_captures(const ses_query *query, const ses_tree *tree, uint32_t start, uint32_t end,
                                           uint32_t read_ctx, uint32_t match_ctx, int32_t **out, uint32_t *out_count,
                                           int32_t *out_exceeded_match_limit) {
  return ses_query_captures(query, tree, start, end, read_ctx ? wasm_read : NULL, CTX(read_ctx),
                            match_ctx ? wasm_match : NULL, CTX(match_ctx), out, out_count, out_exceeded_match_limit);
}

/** ses_query_matches; a context id of 0 passes no reader / no matcher. */
SES_API ses_status ses_wasm_query_matches(const ses_query *query, const ses_tree *tree, uint32_t start, uint32_t end,
                                          uint32_t read_ctx, uint32_t match_ctx, uint32_t children_of, int32_t **out,
                                          uint32_t *out_count, int32_t *out_exceeded_match_limit) {
  return ses_query_matches(query, tree, start, end, read_ctx ? wasm_read : NULL, CTX(read_ctx),
                           match_ctx ? wasm_match : NULL, CTX(match_ctx), children_of, out, out_count,
                           out_exceeded_match_limit);
}

/** malloc / free for the host (buffers it passes in: names, query source, tables blobs, ranges). */
SES_API void *ses_wasm_malloc(uint32_t size) { return malloc(size ? size : 1); }
SES_API void ses_wasm_free(void *p) { free(p); }

/**
 * Trap, as an out-of-memory abort() inside tree-sitter does (its allocator traps rather than return
 * NULL, which tree-sitter cannot handle). For the tests of the loader's dead-runtime handling only.
 */
SES_API void ses_wasm_debug_trap(void) { __builtin_trap(); }
