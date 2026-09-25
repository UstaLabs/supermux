/*
 * supermux editor-syntax: JNI glue for Android and the desktop JVM, bound to
 *   internal object dev.supermux.editor.syntax.Ses   (@JvmStatic external fun ...)
 * One thin layer over the ses_* ABI. Pointers cross as jlong. Text crosses as Java Strings, which
 * are already UTF-16: GetStringChars hands tree-sitter the code units directly (no UTF-8, no
 * modified UTF-8, no offset tables). Pull reads call back into Kotlin (TextSourceJni.chunk(int)),
 * one local ref per chunk, released before the next upcall; #match? regexes likewise
 * (MatcherJni.match(int, String)).
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "supermux_syntax.h"

#define SES_JNI(ret, name) JNIEXPORT ret JNICALL Java_dev_supermux_editor_syntax_Ses_##name
#define P(x) ((void *)(intptr_t)(x))
#define J(x) ((jlong)(intptr_t)(x))

static void throw_new(JNIEnv *env, const char *cls, const char *msg) {
  if ((*env)->ExceptionCheck(env)) return;
  jclass c = (*env)->FindClass(env, cls);
  if (c) { (*env)->ThrowNew(env, c, msg); (*env)->DeleteLocalRef(env, c); }
}

/* Modified-UTF-8 from GetStringUTFChars is fine for language NAMES (ASCII identifiers only). */
static const char *name_chars(JNIEnv *env, jstring s) {
  return s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
}

static jintArray to_int_array(JNIEnv *env, const int32_t *a, uint32_t n) {
  jintArray r = (*env)->NewIntArray(env, (jsize)n);
  if (r && n) (*env)->SetIntArrayRegion(env, r, 0, (jsize)n, (const jint *)a);
  return r;
}

/* ---------------------------------------------------------- pull reader --- */

typedef struct {
  JNIEnv *env;
  jobject source;  /* TextSourceJni */
  jmethodID chunk; /* String chunk(int) */
  jstring cur;
  const jchar *chars;
  int failed;
} jreader;

static void jreader_release(jreader *r) {
  if (r->chars) (*r->env)->ReleaseStringChars(r->env, r->cur, r->chars);
  if (r->cur) (*r->env)->DeleteLocalRef(r->env, r->cur);
  r->chars = NULL;
  r->cur = NULL;
}

static const uint16_t *jreader_read(void *ctx, uint32_t index, uint32_t *out_len) {
  jreader *r = ctx;
  JNIEnv *env = r->env;
  jreader_release(r);
  *out_len = 0;
  if (r->failed) return NULL;
  jstring s = (*env)->CallObjectMethod(env, r->source, r->chunk, (jint)index);
  if ((*env)->ExceptionCheck(env)) { r->failed = 1; return NULL; }
  if (!s) return NULL;
  r->cur = s;
  jsize n = (*env)->GetStringLength(env, s);
  if (n == 0) return NULL;
  r->chars = (*env)->GetStringChars(env, s, NULL);
  if (!r->chars) { r->failed = 1; return NULL; }
  *out_len = (uint32_t)n;
  return (const uint16_t *)r->chars; /* jchar is uint16_t UTF-16 */
}

static int jreader_init(JNIEnv *env, jreader *r, jobject source) {
  memset(r, 0, sizeof *r);
  r->env = env;
  r->source = source;
  if (!source) return 0;
  jclass c = (*env)->GetObjectClass(env, source);
  r->chunk = (*env)->GetMethodID(env, c, "chunk", "(I)Ljava/lang/String;");
  (*env)->DeleteLocalRef(env, c);
  return r->chunk ? 0 : -1;
}

/* ------------------------------------------------------------ regex matcher --- */

typedef struct {
  JNIEnv *env;
  jobject matcher; /* MatcherJni */
  jmethodID match; /* boolean match(int, String) */
} jmatcher;

static int32_t jmatcher_match(void *ctx, uint32_t id, const uint16_t *text, uint32_t len) {
  jmatcher *m = ctx;
  JNIEnv *env = m->env;
  if ((*env)->ExceptionCheck(env)) return -1;
  jstring s = (*env)->NewString(env, (const jchar *)text, (jsize)len);
  if (!s) return -1;
  jboolean r = (*env)->CallBooleanMethod(env, m->matcher, m->match, (jint)id, s);
  (*env)->DeleteLocalRef(env, s);
  if ((*env)->ExceptionCheck(env)) return -1;
  return r ? 1 : 0;
}

static int jmatcher_init(JNIEnv *env, jmatcher *m, jobject matcher) {
  memset(m, 0, sizeof *m);
  m->env = env;
  m->matcher = matcher;
  if (!matcher) return 0;
  jclass c = (*env)->GetObjectClass(env, matcher);
  m->match = (*env)->GetMethodID(env, c, "match", "(ILjava/lang/String;)Z");
  (*env)->DeleteLocalRef(env, c);
  return m->match ? 0 : -1;
}

/* ------------------------------------------------------------- languages --- */

SES_JNI(jint, abiVersion)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return (jint)ses_abi_version(); }
SES_JNI(jlong, debugLiveTrees)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return (jlong)ses_debug_live_trees(); }
SES_JNI(jint, languageCount)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return (jint)ses_language_count(); }

SES_JNI(jstring, languageName)(JNIEnv *env, jclass cls, jint i) {
  (void)cls;
  const char *n = ses_language_name((uint32_t)i);
  return n ? (*env)->NewStringUTF(env, n) : NULL;
}

SES_JNI(jint, languageHasTables)(JNIEnv *env, jclass cls, jstring name) {
  (void)cls;
  const char *n = name_chars(env, name);
  if (!n) return SES_ERR_INVALID_ARGUMENT;
  jint r = ses_language_has_tables(n);
  (*env)->ReleaseStringUTFChars(env, name, n);
  return r;
}

SES_JNI(jint, provideTables)(JNIEnv *env, jclass cls, jstring name, jbyteArray bytes) {
  (void)cls;
  const char *n = name_chars(env, name);
  if (!n) return SES_ERR_INVALID_ARGUMENT;
  if (!bytes) { (*env)->ReleaseStringUTFChars(env, name, n); return SES_ERR_INVALID_ARGUMENT; }
  jsize len = (*env)->GetArrayLength(env, bytes);
  jbyte *b = (*env)->GetByteArrayElements(env, bytes, NULL);
  jint r = b ? ses_language_provide_tables(n, (const uint8_t *)b, (size_t)len) : SES_ERR_OUT_OF_MEMORY;
  if (b) (*env)->ReleaseByteArrayElements(env, bytes, b, JNI_ABORT);
  (*env)->ReleaseStringUTFChars(env, name, n);
  return r;
}

SES_JNI(jint, languageLoad)(JNIEnv *env, jclass cls, jstring name) {
  (void)cls;
  const char *n = name_chars(env, name);
  if (!n) return SES_ERR_INVALID_ARGUMENT;
  jint r = ses_language_load(n);
  (*env)->ReleaseStringUTFChars(env, name, n);
  return r;
}

/* ---------------------------------------------------------------- parser --- */

SES_JNI(jlong, parserNew)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return J(ses_parser_new()); }
SES_JNI(void, parserFree)(JNIEnv *env, jclass cls, jlong p) { (void)env; (void)cls; ses_parser_free(P(p)); }

SES_JNI(jint, parserSetLanguage)(JNIEnv *env, jclass cls, jlong p, jstring name) {
  (void)cls;
  const char *n = name_chars(env, name);
  if (!n) return SES_ERR_INVALID_ARGUMENT;
  jint r = ses_parser_set_language(P(p), n);
  (*env)->ReleaseStringUTFChars(env, name, n);
  return r;
}

SES_JNI(void, parserSetTimeoutMicros)(JNIEnv *env, jclass cls, jlong p, jlong us) {
  (void)env; (void)cls;
  ses_parser_set_timeout_micros(P(p), (uint64_t)us);
}

/* [start, end]* UTF-16 ranges (null or empty: the whole document); source feeds the points. */
SES_JNI(jint, parserSetIncludedRanges)(JNIEnv *env, jclass cls, jlong p, jintArray ranges, jobject source) {
  (void)cls;
  jsize n = ranges ? (*env)->GetArrayLength(env, ranges) : 0;
  if (n == 0) return ses_parser_set_included_ranges(P(p), NULL, 0, NULL, NULL);
  jreader r;
  if (jreader_init(env, &r, source) != 0) return SES_ERR_INVALID_ARGUMENT; /* NoSuchMethodError pending */
  jint *a = (*env)->GetIntArrayElements(env, ranges, NULL);
  if (!a) { jreader_release(&r); return SES_ERR_OUT_OF_MEMORY; }
  jint st = ses_parser_set_included_ranges(P(p), (const int32_t *)a, (uint32_t)n, source ? jreader_read : NULL, &r);
  (*env)->ReleaseIntArrayElements(env, ranges, a, JNI_ABORT);
  jreader_release(&r);
  return st; /* a throwing source: its exception is pending and propagates */
}

/* Returns the tree (0 on failure, status in status[0]). */
SES_JNI(jlong, parse)(JNIEnv *env, jclass cls, jlong p, jlong old, jobject source, jintArray status) {
  (void)cls;
  jreader r;
  if (jreader_init(env, &r, source) != 0) return 0; /* NoSuchMethodError pending */
  ses_status st = SES_OK;
  ses_tree *t = ses_parser_parse(P(p), P(old), jreader_read, &r, &st);
  jreader_release(&r);
  if ((*env)->ExceptionCheck(env)) { ses_tree_free(t); return 0; } /* the source threw: propagate */
  jint s = st;
  (*env)->SetIntArrayRegion(env, status, 0, 1, &s);
  return J(t);
}

SES_JNI(jlong, parseString)(JNIEnv *env, jclass cls, jlong p, jlong old, jstring text, jintArray status) {
  (void)cls;
  jsize n = (*env)->GetStringLength(env, text);
  const jchar *c = (*env)->GetStringChars(env, text, NULL);
  if (!c) return 0;
  ses_status st = SES_OK;
  ses_tree *t = ses_parser_parse_utf16(P(p), P(old), (const uint16_t *)c, (uint32_t)n, &st);
  (*env)->ReleaseStringChars(env, text, c);
  jint s = st;
  (*env)->SetIntArrayRegion(env, status, 0, 1, &s);
  return J(t);
}

/* ------------------------------------------------------------------ tree --- */

SES_JNI(jlong, treeCopy)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; return J(ses_tree_copy(P(t))); }
SES_JNI(void, treeFree)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; ses_tree_free(P(t)); }

SES_JNI(void, treeEdit)(JNIEnv *env, jclass cls, jlong t, jint start, jint oldEnd, jint newEnd, jint sr, jint sc,
                        jint oer, jint oec, jint ner, jint nec) {
  (void)env; (void)cls;
  ses_tree_edit(P(t), (uint32_t)start, (uint32_t)oldEnd, (uint32_t)newEnd, (uint32_t)sr, (uint32_t)sc,
                (uint32_t)oer, (uint32_t)oec, (uint32_t)ner, (uint32_t)nec);
}

SES_JNI(jstring, treeSexp)(JNIEnv *env, jclass cls, jlong t) {
  (void)cls;
  char *s = ses_tree_root_sexp(P(t));
  if (!s) return NULL;
  jstring r = (*env)->NewStringUTF(env, s); /* node type names are ASCII */
  ses_free(s);
  return r;
}

SES_JNI(jboolean, treeHasError)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; return ses_tree_has_error(P(t)) ? JNI_TRUE : JNI_FALSE; }

SES_JNI(jintArray, treeChangedRanges)(JNIEnv *env, jclass cls, jlong old, jlong nw) {
  (void)cls;
  int32_t *a = NULL;
  uint32_t n = 0;
  ses_status st = ses_tree_changed_ranges(P(old), P(nw), &a, &n);
  if (st) { throw_new(env, "java/lang/IllegalStateException", "ses_tree_changed_ranges failed"); return NULL; }
  jintArray r = to_int_array(env, a, n);
  ses_free(a);
  return r;
}

/* ----------------------------------------------------------------- query --- */

/* err[0] = status, err[1] = error byte offset, err[2] = TSQueryError. */
SES_JNI(jlong, queryNew)(JNIEnv *env, jclass cls, jstring language, jbyteArray utf8, jintArray err) {
  (void)cls;
  const char *n = utf8 ? name_chars(env, language) : NULL;
  if (!n) {
    jint e[3] = {SES_ERR_INVALID_ARGUMENT, 0, 0};
    (*env)->SetIntArrayRegion(env, err, 0, 3, e);
    return 0;
  }
  jsize len = (*env)->GetArrayLength(env, utf8);
  jbyte *b = (*env)->GetByteArrayElements(env, utf8, NULL);
  uint32_t off = 0;
  int32_t type = 0;
  ses_status st = SES_ERR_OUT_OF_MEMORY;
  ses_query *q = b ? ses_query_new(n, (const char *)b, (uint32_t)len, &off, &type, &st) : NULL;
  if (b) (*env)->ReleaseByteArrayElements(env, utf8, b, JNI_ABORT);
  (*env)->ReleaseStringUTFChars(env, language, n);
  jint e[3] = {st, (jint)off, type};
  (*env)->SetIntArrayRegion(env, err, 0, 3, e);
  return J(q);
}

SES_JNI(void, queryFree)(JNIEnv *env, jclass cls, jlong q) { (void)env; (void)cls; ses_query_free(P(q)); }
SES_JNI(jint, queryCaptureCount)(JNIEnv *env, jclass cls, jlong q) { (void)env; (void)cls; return (jint)ses_query_capture_count(P(q)); }
SES_JNI(jint, queryFlags)(JNIEnv *env, jclass cls, jlong q) { (void)env; (void)cls; return (jint)ses_query_flags(P(q)); }
SES_JNI(jint, queryPatternCount)(JNIEnv *env, jclass cls, jlong q) { (void)env; (void)cls; return (jint)ses_query_pattern_count(P(q)); }
SES_JNI(jint, queryRegexCount)(JNIEnv *env, jclass cls, jlong q) { (void)env; (void)cls; return (jint)ses_query_regex_count(P(q)); }

static jbyteArray to_byte_array(JNIEnv *env, const void *b, uint32_t len) {
  jbyteArray r = (*env)->NewByteArray(env, (jsize)len);
  if (r && len) (*env)->SetByteArrayRegion(env, r, 0, (jsize)len, (const jbyte *)b);
  return r;
}

/* Regex [id]'s pattern as UTF-8 bytes. */
SES_JNI(jbyteArray, queryRegex)(JNIEnv *env, jclass cls, jlong q, jint id) {
  (void)cls;
  uint32_t len = 0;
  const char *s = ses_query_regex(P(q), (uint32_t)id, &len);
  return to_byte_array(env, s, s ? len : 0);
}

/* The packed directive records of [pattern] (empty when none). */
SES_JNI(jbyteArray, queryPatternSettings)(JNIEnv *env, jclass cls, jlong q, jint pattern) {
  (void)cls;
  uint32_t len = 0;
  const uint8_t *s = ses_query_pattern_settings(P(q), (uint32_t)pattern, &len);
  return to_byte_array(env, s, s ? len : 0);
}

/* Capture names as UTF-8 bytes (a capture name may in theory be non-ASCII; NewStringUTF would mangle it). */
SES_JNI(jbyteArray, queryCaptureName)(JNIEnv *env, jclass cls, jlong q, jint i) {
  (void)cls;
  uint32_t len = 0;
  const char *s = ses_query_capture_name(P(q), (uint32_t)i, &len);
  if (!s) return NULL;
  jbyteArray r = (*env)->NewByteArray(env, (jsize)len);
  if (r) (*env)->SetByteArrayRegion(env, r, 0, (jsize)len, (const jbyte *)s);
  return r;
}

/* [start, end, captureIndex, patternIndex]* in UTF-16 units; source (nullable) feeds the text
   predicates, matcher (nullable) the #match? family; flags[0] = 1 when the match limit was exceeded. */
SES_JNI(jintArray, queryCaptures)(JNIEnv *env, jclass cls, jlong q, jlong t, jint start, jint end, jobject source,
                                  jobject matcher, jintArray flags) {
  (void)cls;
  jreader r;
  jmatcher m;
  if (jreader_init(env, &r, source) != 0 || jmatcher_init(env, &m, matcher) != 0) return NULL;
  int32_t *a = NULL;
  uint32_t n = 0;
  int32_t exceeded = 0;
  ses_status st = ses_query_captures(P(q), P(t), (uint32_t)start, (uint32_t)end, source ? jreader_read : NULL, &r,
                                     matcher ? jmatcher_match : NULL, &m, &a, &n, &exceeded);
  jreader_release(&r);
  if ((*env)->ExceptionCheck(env)) { ses_free(a); return NULL; } /* the source or a regex threw: propagate */
  if (st) {
    char msg[64];
    snprintf(msg, sizeof msg, "ses_query_captures failed (ses status %d)", (int)st);
    throw_new(env, "java/lang/IllegalArgumentException", msg);
    return NULL;
  }
  jintArray res = to_int_array(env, a, n);
  ses_free(a);
  if (flags) { jint f = exceeded; (*env)->SetIntArrayRegion(env, flags, 0, 1, &f); }
  return res;
}
