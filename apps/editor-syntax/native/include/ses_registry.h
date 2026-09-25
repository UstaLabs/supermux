/* The compiled-in grammar list, generated as ses_registry.c by tools/gen-registry.py. */
#ifndef SES_REGISTRY_H
#define SES_REGISTRY_H

#include <stddef.h>
#include <stdint.h>
#include "ses_grammar.h"

typedef struct ses_registry_entry {
  const char *name;
  ses_grammar *(*grammar)(void);
  const uint8_t *blob;       /* bundled .sesz, or NULL when the tables must be provided at runtime */
  const uint32_t *blob_size;
} ses_registry_entry;

extern const ses_registry_entry ses_registry[];
extern const uint32_t ses_registry_count;

/** The registry entry for [name], or NULL. */
const ses_registry_entry *ses_registry_find(const char *name);
/** Copy [sesz] as [name]'s tables (checked against [grammar]'s hash). 0 or SES_ERR_*. */
int32_t ses_tables_provide(ses_grammar *grammar, const uint8_t *sesz, size_t len);
/** 1 if [grammar]'s tables are bundled, provided or already loaded. */
int ses_tables_available(ses_grammar *grammar, const ses_registry_entry *entry);
/** Why the last load of [grammar] failed (SES_ERR_*), 0 if it did not. */
int32_t ses_grammar_status(ses_grammar *grammar);
/** SHA-256 of [len] bytes into out[32]. */
void ses_sha256(const uint8_t *data, size_t len, uint8_t out[32]);

#endif
