/*
 * supermux terminal-core: JNI glue for Android and the desktop JVM.
 *
 * One thin layer over the st_* ABI (include/supermux_terminal.h), bound to
 *   internal object dev.supermux.terminal.NativeTerminal   (@JvmStatic external fun ...)
 * in src/jvmAndAndroidMain. It holds no state of its own besides test counters:
 * handles are the st_* u32 handles passed as jint, byte ranges are Java byte[]
 * (strings are UTF-8 encoded on the Kotlin side, never modified UTF-8), and
 * every output envelope is copied into a fresh byte[] and freed with
 * st_free_buffer before returning -- on every path, including JNI failures.
 *
 * Conventions:
 * - Every function returns the st_status (jint), except the buffer readers,
 *   which return the byte[] (or NULL) and store the status in status[0].
 * - A pending Java exception (e.g. a too-short out-array, OutOfMemoryError from
 *   NewByteArray) always wins: the native resources acquired by that call are
 *   released first, then the call returns and the exception propagates.
 * - Get<Type>ArrayElements is always paired with Release<Type>ArrayElements
 *   (JNI_ABORT: inputs are never written back).
 * - Thread safety is the Kotlin binding's job (per-engine lock + closed flag):
 *   the st_* table is safe for different handles on different threads only.
 *
 * Test hooks (debugCounters / debugFailNextArray): process-wide atomic counters
 * of buffers taken/freed and array elements acquired/released, and a one-shot
 * injected NewByteArray failure, so JVM tests can prove the failure paths free
 * everything. They cost two atomic increments per call.
 */
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stddef.h>

#include "supermux_terminal.h"

#define ST_JNI_INT(name) JNIEXPORT jint JNICALL Java_dev_supermux_terminal_NativeTerminal_##name
#define ST_JNI_BYTES(name) JNIEXPORT jbyteArray JNICALL Java_dev_supermux_terminal_NativeTerminal_##name

static _Atomic int64_t g_buffers_taken;
static _Atomic int64_t g_buffers_freed;
static _Atomic int64_t g_elements_acquired;
static _Atomic int64_t g_elements_released;
static _Atomic int g_fail_next_array;

/* ------------------------------------------------------------ helpers --- */

static void throw_new(JNIEnv *env, const char *cls, const char *msg) {
  if ((*env)->ExceptionCheck(env)) return;
  jclass c = (*env)->FindClass(env, cls);
  if (c != NULL) {
    (*env)->ThrowNew(env, c, msg);
    (*env)->DeleteLocalRef(env, c);
  }
}

static void free_buffer(uint8_t *buf) {
  if (buf == NULL) return;
  st_free_buffer(buf);
  atomic_fetch_add(&g_buffers_freed, 1);
}

/* Store `status` in out[0]. Returns 0 on success; on failure a Java exception
 * is pending. */
static int put_status(JNIEnv *env, jintArray out, jint status) {
  if (out == NULL) {
    throw_new(env, "java/lang/NullPointerException", "status array is null");
    return -1;
  }
  (*env)->SetIntArrayRegion(env, out, 0, 1, &status);
  return (*env)->ExceptionCheck(env) ? -1 : 0;
}

/* Copy a st_* envelope into a new byte[] and ALWAYS free the native buffer.
 * On any failure returns NULL with a Java exception pending. */
static jbyteArray to_java_and_free(JNIEnv *env, uint8_t *buf, uint32_t len) {
  jbyteArray arr = NULL;
  if (len > (uint32_t)INT32_MAX) {
    throw_new(env, "java/lang/IllegalStateException", "native buffer too large");
  } else if (atomic_exchange(&g_fail_next_array, 0)) {
    throw_new(env, "java/lang/OutOfMemoryError", "injected NewByteArray failure (test hook)");
  } else {
    arr = (*env)->NewByteArray(env, (jsize)len);
    if (arr != NULL) {
      (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)buf);
      if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, arr);
        arr = NULL;
      }
    }
  }
  free_buffer(buf);
  return arr;
}

/* Common shape of the three buffer readers: status goes to status[0], the
 * envelope (only on ST_OK) is returned as byte[]. */
static jbyteArray finish_read(JNIEnv *env, jintArray status_out, st_status st, uint8_t *buf, uint32_t len) {
  if (st == ST_OK) atomic_fetch_add(&g_buffers_taken, 1);
  else buf = NULL; /* outputs are written only on ST_OK */
  if (put_status(env, status_out, st) != 0) {
    free_buffer(buf);
    return NULL;
  }
  if (st != ST_OK) return NULL;
  return to_java_and_free(env, buf, len);
}

/* Byte range input. `*out` is NULL for a zero-length array. Returns 0 on
 * success; the caller MUST call release_bytes afterwards. */
static int acquire_bytes(JNIEnv *env, jbyteArray arr, jbyte **out, jsize *len) {
  *out = NULL;
  *len = 0;
  if (arr == NULL) {
    throw_new(env, "java/lang/NullPointerException", "byte array is null");
    return -1;
  }
  *len = (*env)->GetArrayLength(env, arr);
  if (*len == 0) return 0;
  *out = (*env)->GetByteArrayElements(env, arr, NULL);
  if (*out == NULL) return -1; /* OutOfMemoryError pending */
  atomic_fetch_add(&g_elements_acquired, 1);
  return 0;
}

static void release_bytes(JNIEnv *env, jbyteArray arr, jbyte *elems) {
  if (elems == NULL) return;
  (*env)->ReleaseByteArrayElements(env, arr, elems, JNI_ABORT);
  atomic_fetch_add(&g_elements_released, 1);
}

/* ----------------------------------------------------------- lifecycle --- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)vm;
  (void)reserved;
  return JNI_VERSION_1_6;
}

ST_JNI_INT(abiVersion)(JNIEnv *env, jclass cls) {
  (void)env;
  (void)cls;
  return (jint)st_abi_version();
}

ST_JNI_INT(create)(JNIEnv *env, jclass cls, jint abi, jint columns, jint rows, jint cell_width, jint cell_height,
                  jint history_lines, jlong history_bytes, jintArray out_handle) {
  (void)cls;
  /* Validate the out-array BEFORE creating so a JNI failure never leaves a
   * half-created terminal behind. */
  if (out_handle == NULL || (*env)->GetArrayLength(env, out_handle) < 1) {
    throw_new(env, "java/lang/IllegalArgumentException", "out_handle must have length >= 1");
    return ST_ERR_INVALID_ARGUMENT;
  }
  if (columns < 0 || rows < 0 || cell_width < 0 || cell_height < 0 || history_lines < 0 || history_bytes < 0) {
    return ST_ERR_INVALID_ARGUMENT;
  }
  st_handle handle = 0;
  st_status st = st_create((uint32_t)abi, (uint32_t)columns, (uint32_t)rows, (uint32_t)cell_width,
                           (uint32_t)cell_height, (uint32_t)history_lines, (uint64_t)history_bytes, NULL, &handle);
  if (st != ST_OK) return st;
  jint h = (jint)handle;
  (*env)->SetIntArrayRegion(env, out_handle, 0, 1, &h);
  if ((*env)->ExceptionCheck(env)) {
    st_destroy(handle); /* never leak a terminal the caller cannot see */
    return ST_ERR_INTERNAL;
  }
  return ST_OK;
}

ST_JNI_INT(destroy)(JNIEnv *env, jclass cls, jint handle) {
  (void)env;
  (void)cls;
  return st_destroy((st_handle)handle);
}

/* ------------------------------------------------------------ mutation --- */

ST_JNI_INT(feed)(JNIEnv *env, jclass cls, jint handle, jbyteArray data, jint origin) {
  (void)cls;
  jbyte *bytes;
  jsize len;
  if (acquire_bytes(env, data, &bytes, &len) != 0) return ST_ERR_INTERNAL;
  st_status st = st_feed((st_handle)handle, (const uint8_t *)bytes, (uint32_t)len, (uint32_t)origin);
  release_bytes(env, data, bytes);
  return st;
}

ST_JNI_INT(reset)(JNIEnv *env, jclass cls, jint handle) {
  (void)env;
  (void)cls;
  return st_reset((st_handle)handle);
}

ST_JNI_INT(resize)(JNIEnv *env, jclass cls, jint handle, jint columns, jint rows, jint cell_width, jint cell_height) {
  (void)env;
  (void)cls;
  if (columns < 0 || rows < 0 || cell_width < 0 || cell_height < 0) return ST_ERR_INVALID_ARGUMENT;
  return st_resize((st_handle)handle, (uint32_t)columns, (uint32_t)rows, (uint32_t)cell_width, (uint32_t)cell_height);
}

ST_JNI_INT(colors)(JNIEnv *env, jclass cls, jint handle, jlongArray colors) {
  (void)cls;
  if (colors == NULL) {
    throw_new(env, "java/lang/NullPointerException", "colors is null");
    return ST_ERR_INVALID_ARGUMENT;
  }
  jsize n = (*env)->GetArrayLength(env, colors);
  if (n == 0) return st_colors((st_handle)handle, NULL, 0);
  jlong *elems = (*env)->GetLongArrayElements(env, colors, NULL);
  if (elems == NULL) return ST_ERR_OUT_OF_MEMORY; /* OutOfMemoryError pending */
  atomic_fetch_add(&g_elements_acquired, 1);
  _Static_assert(sizeof(jlong) == sizeof(uint64_t), "jlong must be 64-bit");
  st_status st = st_colors((st_handle)handle, (const uint64_t *)elems, (uint32_t)n);
  (*env)->ReleaseLongArrayElements(env, colors, elems, JNI_ABORT);
  atomic_fetch_add(&g_elements_released, 1);
  return st;
}

ST_JNI_INT(scrollTo)(JNIEnv *env, jclass cls, jint handle, jlong row) {
  (void)env;
  (void)cls;
  return st_scroll_to((st_handle)handle, (int64_t)row);
}

ST_JNI_INT(select)(JNIEnv *env, jclass cls, jint handle, jboolean has_selection, jlong start_row, jint start_column,
                  jlong end_row, jint end_column) {
  (void)env;
  (void)cls;
  if (has_selection && (start_column < 0 || end_column < 0)) return ST_ERR_INVALID_ARGUMENT;
  return st_select((st_handle)handle, has_selection ? 1u : 0u, (int64_t)start_row, (uint32_t)start_column,
                   (int64_t)end_row, (uint32_t)end_column);
}

ST_JNI_INT(acknowledge)(JNIEnv *env, jclass cls, jint handle, jlong generation) {
  (void)env;
  (void)cls;
  return st_acknowledge((st_handle)handle, (int64_t)generation);
}

/* --------------------------------------------------------------- input --- */

ST_JNI_INT(key)(JNIEnv *env, jclass cls, jint handle, jint physical_code, jbyteArray text, jint modifiers, jint action) {
  (void)cls;
  jbyte *bytes;
  jsize len;
  if (acquire_bytes(env, text, &bytes, &len) != 0) return ST_ERR_INTERNAL;
  st_status st = st_key((st_handle)handle, (uint32_t)physical_code, (const uint8_t *)bytes, (uint32_t)len,
                        (uint32_t)modifiers, (uint32_t)action);
  release_bytes(env, text, bytes);
  return st;
}

ST_JNI_INT(mouse)(JNIEnv *env, jclass cls, jint handle, jint column, jint row, jint button, jint modifiers, jint action) {
  (void)env;
  (void)cls;
  return st_mouse((st_handle)handle, (int32_t)column, (int32_t)row, (uint32_t)button, (uint32_t)modifiers,
                  (uint32_t)action);
}

ST_JNI_INT(paste)(JNIEnv *env, jclass cls, jint handle, jbyteArray text, jint flags) {
  (void)cls;
  jbyte *bytes;
  jsize len;
  if (acquire_bytes(env, text, &bytes, &len) != 0) return ST_ERR_INTERNAL;
  st_status st = st_paste((st_handle)handle, (const uint8_t *)bytes, (uint32_t)len, (uint32_t)flags);
  release_bytes(env, text, bytes);
  return st;
}

ST_JNI_INT(focus)(JNIEnv *env, jclass cls, jint handle, jboolean focused) {
  (void)env;
  (void)cls;
  return st_focus((st_handle)handle, focused ? 1u : 0u);
}

/* -------------------------------------------------------------- output --- */

ST_JNI_BYTES(readViewport)(JNIEnv *env, jclass cls, jint handle, jint flags, jintArray status_out) {
  (void)cls;
  uint8_t *buf = NULL;
  uint32_t len = 0;
  st_status st = st_read_viewport((st_handle)handle, (uint32_t)flags, &buf, &len, NULL);
  return finish_read(env, status_out, st, buf, len);
}

ST_JNI_BYTES(selectedText)(JNIEnv *env, jclass cls, jint handle, jintArray status_out) {
  (void)cls;
  uint8_t *buf = NULL;
  uint32_t len = 0;
  st_status st = st_selected_text((st_handle)handle, &buf, &len);
  return finish_read(env, status_out, st, buf, len);
}

ST_JNI_BYTES(drainEffects)(JNIEnv *env, jclass cls, jint handle, jintArray status_out) {
  (void)cls;
  uint8_t *buf = NULL;
  uint32_t len = 0;
  st_status st = st_drain_effects((st_handle)handle, &buf, &len);
  return finish_read(env, status_out, st, buf, len);
}

/* ---------------------------------------------------------- test hooks --- */

/* [buffers taken, buffers freed, array elements acquired, array elements released] */
JNIEXPORT jlongArray JNICALL Java_dev_supermux_terminal_NativeTerminal_debugCounters(JNIEnv *env, jclass cls) {
  (void)cls;
  jlong v[4] = {
      (jlong)atomic_load(&g_buffers_taken),
      (jlong)atomic_load(&g_buffers_freed),
      (jlong)atomic_load(&g_elements_acquired),
      (jlong)atomic_load(&g_elements_released),
  };
  jlongArray arr = (*env)->NewLongArray(env, 4);
  if (arr != NULL) (*env)->SetLongArrayRegion(env, arr, 0, 4, v);
  return arr;
}

/* The next byte[] allocation for an output envelope fails with OutOfMemoryError. */
JNIEXPORT void JNICALL Java_dev_supermux_terminal_NativeTerminal_debugFailNextArray(JNIEnv *env, jclass cls,
                                                                                   jboolean fail) {
  (void)env;
  (void)cls;
  atomic_store(&g_fail_next_array, fail ? 1 : 0);
}
