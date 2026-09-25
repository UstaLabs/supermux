/*
 * The contract between a sestables.py-transformed parser_<lang>.c and the runtime loader
 * (native/src/ses_grammar.c). Included by the generated grammar AFTER its own tree_sitter/parser.h,
 * so the grammar fills its TSLanguage with the struct layout of the ABI it was generated for; the
 * loader never looks inside a TSLanguage (it only passes the pointer on).
 */
#ifndef SES_GRAMMAR_H
#define SES_GRAMMAR_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ses_tables ses_tables;

#define SES_TABLE_BYTES 0u
#define SES_TABLE_STRINGS 1u

/** What the grammar's code expects of table i; the loader refuses a blob that differs. */
typedef struct ses_table_spec {
  uint32_t kind;  /* SES_TABLE_BYTES or SES_TABLE_STRINGS */
  uint32_t size;  /* serialized size in bytes */
  uint32_t count; /* elements (rows for 2-D tables) */
} ses_table_spec;

typedef struct ses_grammar {
  const char *name;
  uint32_t abi;           /* LANGUAGE_VERSION of the generated parser */
  uint64_t hash;          /* identity of the tables this code was generated with */
  uint32_t table_count;
  const ses_table_spec *specs;
  void (*fill)(const ses_tables *tables); /* store every table pointer into *language */
  void *language;         /* the grammar's static, non-const TSLanguage */
  void *_Atomic loaded;   /* NULL until filled; then == language (release/acquire) */
  int32_t status;         /* last load failure (SES_ERR_*), 0 if none; guarded by the loader lock */
} ses_grammar;

/**
 * Table [index]: its bytes, 16-byte aligned, inside the inflated payload; or, for a strings table,
 * a `const char *[count]` array (NULL entries preserved). Valid forever (never unloaded).
 */
const void *ses_tables_at(const ses_tables *tables, uint32_t index);

/** The filled TSLanguage, loading it first if needed (thread-safe, once). NULL if unavailable. */
const void *ses_grammar_language(ses_grammar *grammar);

#ifdef __cplusplus
}
#endif
#endif
