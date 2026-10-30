/* webview-compose's Linux glue: WPE WebKit 2.54 through the WPEPlatform API, behind JNI (spec §7 "Linux: frames").
 * Built by apps/webview-wpetest (the feasibility test) and, after a GO, by apps/webview-compose (the engine). Every
 * WebKit and WPEPlatform call happens on one thread, the WebKit thread (wv_host.c); JVM threads post work to it, and
 * only touch the frame store, under its lock. */
#ifndef WV_H
#define WV_H

#include <glib.h>
#include <jni.h>
#include <stdint.h>
#include <time.h>
#include <wpe/webkit.h>
#include <wpe/wpe-platform.h>

#ifndef WV_JNI_CLASS
#error "build with -DWV_JNI_CLASS='\"dev/supermux/.../WpeNative\"', the class whose natives this library registers"
#endif

/* Events to the JVM: WpeNative.onEvent(view, kind, a, b, s1, s2), always on the WebKit thread. WpeNative.kt has the
 * same numbers. */
enum {
    WV_EV_FRAME = 1,        /* a: frame sequence, b: the marker pixel 0xRRGGBB, -1 if none; sent once until a pull */
    WV_EV_LOAD = 2,         /* a: WebKitLoadEvent, s1: the view's URI */
    WV_EV_LOAD_FAILED = 3,  /* a: error code, b: WV_FAIL_* bits, s1: failing URI, s2: message */
    WV_EV_TITLE = 4,        /* s1: title, or null */
    WV_EV_URI = 5,          /* s1: URI, or null */
    WV_EV_PROGRESS = 6,     /* a: estimated load progress, in permille */
    WV_EV_HISTORY = 7,      /* a: can go back, b: can go forward; s1: back list URIs oldest first, s2: forward list
                               nearest first, '\n'-separated */
    WV_EV_POLICY = 8,       /* a: decision id, b: WV_POLICY_* | navigation type << 4, s1: URL, s2: target frame name */
    WV_EV_RESPONSE = 9,     /* a: decision id, b: WV_RESPONSE_* | HTTP status << 8, s1: URL, s2: "MIME\nfilename" */
    WV_EV_RELAY = 10,       /* s1: what the relay posted */
    WV_EV_EVAL = 11,        /* a: request, b: 1 done / 0 failed, s1: the result as JSON (null: undefined), s2: error */
    WV_EV_CRASHED = 12,     /* a: WebKitWebProcessTerminationReason */
    WV_EV_SCHEME = 13,      /* a: request id, s1: URI */
    WV_EV_IME_FOCUS = 14,   /* a: 1 an editable element took focus, 0 it lost it */
    WV_EV_IME_CURSOR = 15,  /* a: x << 32 | y, b: width << 32 | height, in view coordinates */
    WV_EV_CURSOR = 16,      /* s1: the CSS cursor name */
    WV_EV_DATA_CLEARED = 17, /* a: request, b: 1 cleared / 0 failed */
    WV_EV_DIALOG = 18,       /* a: request, b: WebKitScriptDialogType, s1: message, s2: a prompt's default text */
    WV_EV_PERMISSION = 19,   /* a: request, b: WV_PERM_* bits, s1: the page's URI */
    WV_EV_FILE_CHOOSER = 20, /* a: request, b: 1 several files, s1: the MIME types, '\n'-separated */
    WV_EV_NEW_WINDOW = 21,   /* a: 1 a user gesture, s1: the URI */
    WV_EV_RESPONSIVE = 22,   /* a: 1 the web process answers again, 0 WebKit found it unresponsive */
};
enum { WV_PERM_CAMERA = 1, WV_PERM_MICROPHONE = 2, WV_PERM_GEOLOCATION = 4, WV_PERM_NOTIFICATIONS = 8, WV_PERM_OTHER = 16 };

enum { WV_INIT_CPU = 1, WV_INIT_NO_SANDBOX = 2, WV_INIT_GLOBAL_CONTEXT = 4 };
enum { WV_FAIL_CANCELLED = 1, WV_FAIL_TLS = 2, WV_FAIL_POLICY = 4, WV_FAIL_CANNOT_SHOW = 8 };
enum { WV_POLICY_NEW_WINDOW = 1, WV_POLICY_GESTURE = 1 << 8, WV_POLICY_REDIRECT = 1 << 9 };
enum { WV_RESPONSE_MAIN = 1, WV_RESPONSE_CAN_SHOW = 2, WV_RESPONSE_ATTACHMENT = 4 };

static inline int64_t wv_now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts); /* the clock behind System.nanoTime on Linux */
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* ---- wv_platform.c: WvDisplay, WvToplevel, WvView, WvIm (WebKit thread) and the frame store ---- */
typedef struct _WvFrames WvFrames;
WPEDisplay *wv_display_new(GError **error);
gboolean wv_is_view(WPEView *view);
WvFrames *wv_view_frames(WPEView *view);
WPEInputMethodContext *wv_view_im(WPEView *view);
void wv_view_set_owner(WPEView *view, int64_t owner);
void wv_view_set_size(WPEView *view, int width, int height, double scale);
void wv_im_preedit(WPEInputMethodContext *im, const char *text, int cursor);
void wv_im_commit(WPEInputMethodContext *im, const char *text);
void wv_im_finish(WPEInputMethodContext *im);

#define WV_PULL_OUT 8     /* longs wv_frames_pull writes: seq, width, height, copy ns, copied px, full, marker, rects */
#define WV_RECORD_LONGS 5 /* longs per record wv_frames_drain writes: seq, marker, arrival ns, damaged px, outside px */
WvFrames *wv_frames_ref(WvFrames *frames);
int64_t wv_frames_seq(WvFrames *frames);
void wv_frames_unref(WvFrames *frames);
void wv_frames_configure(WvFrames *frames, gboolean auto_ack, gboolean verify, double damage_scale);
/* 1: copied; 0: nothing new; -1: dst isn't the buffer's size (out[1], out[2] say what is), call again with full. */
int wv_frames_pull(WvFrames *frames, uint8_t *dst, int dst_stride, int dst_width, int dst_height, gboolean full, int64_t *out);
int wv_frames_drain(WvFrames *frames, int64_t *out, int max_records);

/* ---- wv_host.c: the WebKit thread ---- */
int wv_host_init(int flags, const char *data_dir, const char *cache_dir, const char *runtime_dir);
void wv_host_set_jvm(JavaVM *vm, jclass cls, jmethodID on_event);
void wv_host_post(GSourceFunc fn, gpointer data, GDestroyNotify destroy);
gboolean wv_host_is_thread(void);
WPEDisplay *wv_host_display(void);
WebKitWebContext *wv_host_context(void);
const char *wv_host_data_dir(void);
const char *wv_host_cache_dir(void);
void wv_emit(int64_t view, int kind, int64_t a, int64_t b, const char *s1, const char *s2);
jstring wv_jstring(JNIEnv *env, const char *utf8);
char *wv_utf8(JNIEnv *env, jstring s);

/* ---- wv_webview.c: web views, and the operations the JVM posts ---- */
enum {
    WV_OP_CREATE = 1, WV_OP_DESTROY, WV_OP_REGISTER_SCHEME, WV_OP_CONFIGURE, WV_OP_SET_DARK, WV_OP_LOAD, WV_OP_NAVIGATE,
    WV_OP_EVALUATE, WV_OP_CALL_ASYNC, WV_OP_DECIDE, WV_OP_SAVE_STATE, WV_OP_RESTORE_STATE, WV_OP_SET_SIZE,
    WV_OP_SET_VISIBLE, WV_OP_SET_FOCUS, WV_OP_POINTER, WV_OP_SCROLL, WV_OP_KEY, WV_OP_IME, WV_OP_FINISH_SCHEME,
    WV_OP_TERMINATE, WV_OP_CLEAR_DATA, WV_OP_ANSWER,
};
typedef struct _WvSync WvSync;
typedef struct {
    int op;
    int64_t view;
    int64_t i[6];
    double d[4];
    char *s[6];
    char **strv;    /* NULL-terminated */
    GBytes *bytes;
    WvSync *sync;   /* set by wv_op_call */
} WvOp;
void wv_webviews_init(void);
WvOp *wv_op_new(int op, int64_t view);
void wv_op_post(WvOp *op);                       /* runs it on the WebKit thread, later */
int64_t wv_op_call(WvOp *op, GBytes **bytes);    /* runs it there and waits; -1 if called on the WebKit thread */
WvFrames *wv_frames_lookup(int64_t view);        /* a reference, or NULL; any thread */

/* ---- wv_client.c: the page's requests the JVM answers (WebKit thread) ---- */
void wv_client_connect(WebKitWebView *web, int64_t view);
void wv_client_disconnect(WebKitWebView *web, int64_t view);
void wv_client_answer(int64_t request, gboolean allow, const char *text, char **files);

#ifdef WV_GLBENCH
int wv_upload_bench(int width, int height, int dirty_width, int dirty_height, int runs, int64_t *out, char *renderer, int renderer_len);
#endif

#endif /* WV_H */
