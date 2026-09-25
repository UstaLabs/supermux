/*
 * supermux editor-syntax: the owned C ABI over the tree-sitter C runtime (pinned v0.25.10) and the
 * compiled-in grammars (code) whose parse tables live in compressed blobs (data).
 *
 * The ONLY native surface the Kotlin bindings (JNI on Android/JVM, cinterop on iOS) call. Web uses
 * web-tree-sitter and never sees this.
 *
 * UNITS: every offset, length, column and range in this ABI is in UTF-16 code units. Documents are
 * parsed with TSInputEncodingUTF16LE, so tree-sitter's byte offset is exactly 2 x the UTF-16 index
 * and its point column is 2 x the UTF-16 column; the conversion (x2 / /2) happens here and nowhere
 * else. There are no byte-width tables anywhere.
 *
 * Text input: a pull callback (ses_read_fn) that returns a UTF-16 chunk starting at a UTF-16 index,
 * which maps 1:1 onto Rope.chunkAt(pos); or, for convenience, one contiguous UTF-16 buffer.
 *
 * Ownership: every *_new / parse returns an object the caller frees with the matching *_free.
 * Trees are immutable snapshots except for ses_tree_edit; ses_tree_copy is O(1) (refcounted).
 * Threading: a parser, a tree or a query cursor must not be used from two threads at once. A
 * ses_query is immutable after creation and may be shared. Language loading (first use of a
 * grammar: inflate the tables blob, fix up the TSLanguage) is thread-safe and happens once.
 */
#ifndef SUPERMUX_SYNTAX_H
#define SUPERMUX_SYNTAX_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__GNUC__) || defined(__clang__)
#define SES_API __attribute__((visibility("default")))
#else
#define SES_API
#endif

/** ABI version implemented by this header. Bumped on any incompatible change. */
#define SES_ABI_VERSION 3u

typedef int32_t ses_status;
#define SES_OK 0
#define SES_ERR_INVALID_ARGUMENT (-3)
#define SES_ERR_OUT_OF_MEMORY (-4)
/** No compiled-in grammar has that name. */
#define SES_ERR_UNKNOWN_LANGUAGE (-8)
/** The grammar's code is compiled in but its tables blob is neither bundled nor provided yet. */
#define SES_ERR_NO_TABLES (-9)
/** A tables blob was refused: bad magic/format, wrong grammar hash, corrupt zlib, size mismatch. */
#define SES_ERR_BAD_TABLES (-10)
/**
 * ses_query_new: the query does not compile (see err_offset / err_type), or one of its text
 * predicates is malformed (wrong arity, a capture where a string belongs): then err_type is -1.
 */
#define SES_ERR_QUERY (-11)
/** ts_parser_set_language refused the grammar (ABI outside 13..15). */
#define SES_ERR_INCOMPATIBLE_LANGUAGE (-12)
/** The parse hit the timeout (ses_parser_set_timeout_micros) or was cancelled. */
#define SES_ERR_TIMEOUT (-13)
/** A ses_match_fn failed (returned < 0); the caller's own error is pending on its side. */
#define SES_ERR_CALLBACK (-14)

typedef struct ses_parser ses_parser;
typedef struct ses_tree ses_tree;
typedef struct ses_query ses_query;

/**
 * Pull callback: return a pointer to UTF-16 code units of the document starting at UTF-16 [index]
 * and store how many units it holds in *out_len (0 = end of document). The pointer must stay valid
 * until the next call on the same parse (or the end of it). A chunk may end in the middle of a
 * surrogate pair; tree-sitter asks again from the pair's start.
 */
typedef const uint16_t *(*ses_read_fn)(void *ctx, uint32_t index, uint32_t *out_len);

/**
 * Regex callback for the #match? family: does the query's regex [regex_id] (ses_query_regex) match
 * anywhere in [text] ([len] UTF-16 units; [text] is never NULL)? Return 1 for a match, 0 for none,
 * < 0 to abort the query with SES_ERR_CALLBACK.
 */
typedef int32_t (*ses_match_fn)(void *ctx, uint32_t regex_id, const uint16_t *text, uint32_t len);

/** ses_query_captures keeps at most this many matches in progress (ts_query_cursor_set_match_limit). */
#define SES_QUERY_MATCH_LIMIT 65536u

SES_API uint32_t ses_abi_version(void);

/* ------------------------------------------------------------ languages --- */

/** Number of compiled-in grammars, and the name of the i-th (static storage, never freed). */
SES_API uint32_t ses_language_count(void);
SES_API const char *ses_language_name(uint32_t index);
/** 1 if [name]'s tables are available (bundled or provided), 0 if not, <0 unknown name. */
SES_API ses_status ses_language_has_tables(const char *name);
/**
 * Hand the runtime a .sesz tables blob for [name] (e.g. downloaded on demand). The bytes are copied
 * and checked against the grammar's compiled-in hash on first use. Returns SES_ERR_BAD_TABLES for a
 * header / hash mismatch without inflating.
 */
SES_API ses_status ses_language_provide_tables(const char *name, const uint8_t *sesz, size_t len);
/** Load [name] now (inflate + fix up); a parser's set_language does this lazily. Idempotent. */
SES_API ses_status ses_language_load(const char *name);

/* --------------------------------------------------------------- parser --- */

SES_API ses_parser *ses_parser_new(void);
SES_API void ses_parser_free(ses_parser *parser);
SES_API ses_status ses_parser_set_language(ses_parser *parser, const char *name);
/** 0 = no limit. A parse over the limit returns NULL with SES_ERR_TIMEOUT. */
SES_API void ses_parser_set_timeout_micros(ses_parser *parser, uint64_t micros);
/**
 * Restrict the parser's next parses to these UTF-16 ranges (injections: a <script> element's
 * content, a Markdown fence). [ranges] is a packed [start, end]* array of [count_ints] ints, sorted
 * and non-overlapping (start <= end, each start >= the previous end). tree-sitter also needs each
 * boundary's (row, column), so the document is read through [read] from index 0 up to the last
 * boundary. NULL / 0 resets to the whole document. SES_ERR_INVALID_ARGUMENT for an odd count, a
 * missing reader, or ranges that are unordered or overlap (the previous ranges then stay).
 */
SES_API ses_status ses_parser_set_included_ranges(ses_parser *parser, const int32_t *ranges, uint32_t count_ints,
                                                  ses_read_fn read, void *ctx);

/**
 * Parse the document read through [read]. [old_tree] (may be NULL) must already carry every edit
 * (ses_tree_edit) since it was produced; tree-sitter then reuses its unchanged subtrees. Returns a
 * new tree, or NULL with *out_status set.
 */
SES_API ses_tree *ses_parser_parse(ses_parser *parser, const ses_tree *old_tree, ses_read_fn read,
                                   void *ctx, ses_status *out_status);
/** Same over one contiguous UTF-16 buffer of [len] units. */
SES_API ses_tree *ses_parser_parse_utf16(ses_parser *parser, const ses_tree *old_tree,
                                         const uint16_t *text, uint32_t len, ses_status *out_status);

/* ----------------------------------------------------------------- tree --- */

SES_API ses_tree *ses_tree_copy(const ses_tree *tree);
SES_API void ses_tree_free(ses_tree *tree);
/**
 * Record one edit, all in UTF-16 units: [start, old_end) became [start, new_end). Rows are 0-based
 * lines; columns are UTF-16 units from the start of that line.
 */
SES_API void ses_tree_edit(ses_tree *tree, uint32_t start, uint32_t old_end, uint32_t new_end,
                           uint32_t start_row, uint32_t start_col, uint32_t old_end_row,
                           uint32_t old_end_col, uint32_t new_end_row, uint32_t new_end_col);
/** The root node's S-expression (UTF-8, NUL-terminated). Free with ses_free. For tests. */
SES_API char *ses_tree_root_sexp(const ses_tree *tree);
/** 1 if the tree contains an ERROR or MISSING node. */
SES_API int32_t ses_tree_has_error(const ses_tree *tree);
/**
 * Ranges whose syntax changed between [old_tree] (edited) and [new_tree] (its reparse), as a packed
 * [start, end]* UTF-16 array in *out (free with ses_free) of *out_count ints (2 per range).
 */
SES_API ses_status ses_tree_changed_ranges(const ses_tree *old_tree, const ses_tree *new_tree,
                                           int32_t **out, uint32_t *out_count);

/* ---------------------------------------------------------------- query --- */

/**
 * Compile [source] (UTF-8, [len] bytes) against the grammar [language]. On SES_ERR_QUERY,
 * *err_offset is the byte offset and *err_type tree-sitter's TSQueryError.
 */
SES_API ses_query *ses_query_new(const char *language, const char *source, uint32_t len,
                                 uint32_t *err_offset, int32_t *err_type, ses_status *out_status);
SES_API void ses_query_free(ses_query *query);
SES_API uint32_t ses_query_capture_count(const ses_query *query);
/** The capture's name (UTF-8, NOT NUL-terminated: *out_len bytes); valid while the query lives. */
SES_API const char *ses_query_capture_name(const ses_query *query, uint32_t index, uint32_t *out_len);
/** Bit 0: the query uses #lua-match?, which this ABI does NOT evaluate (those predicates pass). */
SES_API uint32_t ses_query_flags(const ses_query *query);
SES_API uint32_t ses_query_pattern_count(const ses_query *query);
/** Distinct regexes of the query's #match? / #not-match? / #any-match? / #any-not-match? predicates. */
SES_API uint32_t ses_query_regex_count(const ses_query *query);
/** Regex [id]'s pattern (UTF-8, NOT NUL-terminated: *out_len bytes); valid while the query lives. */
SES_API const char *ses_query_regex(const ses_query *query, uint32_t id, uint32_t *out_len);

#define SES_SETTING_SET 1u    /* #set! [@capture] key [value] */
#define SES_SETTING_IS 2u     /* #is? [@capture] property [value] */
#define SES_SETTING_IS_NOT 3u /* #is-not? [@capture] property [value] */
/**
 * Pattern [pattern]'s directives, in source order, packed as *out_len bytes (NULL / 0 when it has
 * none; valid while the query lives). Each record, little-endian:
 *   u8 kind (SES_SETTING_*), i32 capture id (-1: none), u32 key_len, key (UTF-8),
 *   u32 value_len (0xFFFFFFFF: no value), value (UTF-8).
 * Other directives (#offset!, #select-adjacent!, ...) and unknown predicates are ignored. A
 * #set! whose key or value is a capture (`(#set! key @c)`) makes ses_query_new fail with
 * SES_ERR_QUERY.
 */
SES_API const uint8_t *ses_query_pattern_settings(const ses_query *query, uint32_t pattern, uint32_t *out_len);

/**
 * Run [query] over the nodes of [tree] that intersect UTF-16 [start, end), in tree-sitter capture
 * order. Text predicates (#eq? #not-eq? #any-eq? #any-not-eq? #any-of? #not-any-of?) are evaluated
 * against the document read through [read]; the #match? family additionally through [match]. A
 * match failing a predicate is removed from the cursor (ts_query_cursor_remove_match), so later
 * captures of that match are dropped too. With [read] NULL no text predicate is evaluated, with
 * [match] NULL no #match?-family one (they pass). The predicates of one match are evaluated once
 * per call, however many captures it has. Result: a packed
 * [start, end, captureIndex, patternIndex]* int array in *out (free with ses_free), *out_count ints
 * (4 per capture). At most SES_QUERY_MATCH_LIMIT matches are kept in progress; when the cursor
 * had to drop some, *out_exceeded_match_limit (may be NULL) is 1, else 0: the result may then miss
 * captures.
 */
SES_API ses_status ses_query_captures(const ses_query *query, const ses_tree *tree, uint32_t start,
                                      uint32_t end, ses_read_fn read, void *read_ctx, ses_match_fn match,
                                      void *match_ctx, int32_t **out, uint32_t *out_count,
                                      int32_t *out_exceeded_match_limit);
SES_API ses_status ses_query_captures_utf16(const ses_query *query, const ses_tree *tree,
                                            uint32_t start, uint32_t end, const uint16_t *text,
                                            uint32_t len, ses_match_fn match, void *match_ctx,
                                            int32_t **out, uint32_t *out_count,
                                            int32_t *out_exceeded_match_limit);

/**
 * Like ses_query_captures, but grouped per match, in tree-sitter's match order, for injections:
 * a match's @injection.language and @injection.content belong together. Predicates are applied
 * the same way; a match failing one is left out. *out (free with ses_free) is a packed int array
 * of *out_count ints:
 *   per match: patternIndex, n, then n captures;
 *   per capture: start, end, captureIndex, k, then k x (childStart, childEnd, childIsNamed).
 * k is the number of the node's children when captureIndex == [children_of] (an injection's
 * content, whose children an injection may exclude), else 0. UINT32_MAX: no children anywhere.
 */
SES_API ses_status ses_query_matches(const ses_query *query, const ses_tree *tree, uint32_t start,
                                     uint32_t end, ses_read_fn read, void *read_ctx, ses_match_fn match,
                                     void *match_ctx, uint32_t children_of, int32_t **out, uint32_t *out_count,
                                     int32_t *out_exceeded_match_limit);

/** Trees alive right now (made by a parse or ses_tree_copy, not yet freed). For leak tests. */
SES_API int64_t ses_debug_live_trees(void);

/** Frees any buffer this ABI returned (sexp strings, int arrays). NULL is a no-op. */
SES_API void ses_free(void *ptr);

#ifdef __cplusplus
}
#endif
#endif
