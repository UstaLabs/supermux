/* The WebKit thread (spec §7 "Linux: frames"): WebKit, WPEPlatform and every GLib source they attach run here, on a
 * main context of their own pushed as this thread's default (GLib's global one with WV_INIT_GLOBAL_CONTEXT). JVM
 * threads post work with wv_host_post; this thread reports to the JVM with wv_emit. */
#include "wv.h"
#include <string.h>

static GMutex start_lock;
static GCond start_cond;
static int start_state; /* 0 starting, 1 running, -1 failed */
static GThread *thread;
static GMainContext *context;
static int init_flags;
static char *data_root, *cache_root;
static WPEDisplay *display;
static WebKitWebContext *web_context;
static JavaVM *jvm;
static jclass event_class;
static jmethodID on_event;
static JNIEnv *thread_env; /* the WebKit thread's own */

void wv_host_set_jvm(JavaVM *vm, jclass cls, jmethodID method)
{
    jvm = vm;
    event_class = cls;
    on_event = method;
}

static gpointer webkit_thread(gpointer unused)
{
    gboolean global = (init_flags & WV_INIT_GLOBAL_CONTEXT) != 0;
    GMainContext *c = global ? g_main_context_ref(g_main_context_default()) : g_main_context_new();
    if (!global)
        g_main_context_push_thread_default(c);
    JavaVMAttachArgs args = { JNI_VERSION_1_8, (char *)"wpe-webkit", NULL };
    JNIEnv *env = NULL;
    gboolean attached = jvm && (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **)&env, &args) == JNI_OK;
    GError *error = NULL;
    WPEDisplay *d = attached ? wv_display_new(&error) : NULL;
    if (error) {
        g_warning("webview-wpe: no display: %s", error->message);
        g_clear_error(&error);
    }
    WebKitWebContext *wc = d ? webkit_web_context_new() : NULL;

    g_mutex_lock(&start_lock);
    context = c;
    thread_env = env;
    display = d;
    web_context = wc;
    if (wc)
        wv_webviews_init();
    start_state = wc ? 1 : -1;
    g_cond_broadcast(&start_cond);
    g_mutex_unlock(&start_lock);
    if (start_state < 0)
        return NULL;

    GMainLoop *loop = g_main_loop_new(c, FALSE);
    g_main_loop_run(loop); /* for the life of the process */
    return NULL;
}

/* Starts WebKit once per process; 0 when it runs, -1 when it can't. Later calls return how the first one went. */
int wv_host_init(int flags, const char *data_dir, const char *cache_dir)
{
    g_mutex_lock(&start_lock);
    if (!thread) {
        init_flags = flags;
        data_root = g_strdup(data_dir && *data_dir ? data_dir : g_get_user_data_dir());
        cache_root = g_strdup(cache_dir && *cache_dir ? cache_dir : g_get_user_cache_dir());
        if (flags & WV_INIT_CPU)
            g_setenv("WEBKIT_SKIA_ENABLE_CPU_RENDERING", "1", TRUE);
        if (flags & WV_INIT_NO_SANDBOX)
            g_setenv("WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS", "1", TRUE);
        g_unsetenv("WPE_DRM_DEVICE"); /* it would bring the GPU transport back (F12) */
        thread = g_thread_new("wpe-webkit", webkit_thread, NULL);
        while (start_state == 0)
            g_cond_wait(&start_cond, &start_lock);
    }
    int state = start_state;
    g_mutex_unlock(&start_lock);
    return state > 0 ? 0 : -1;
}

void wv_host_post(GSourceFunc fn, gpointer data, GDestroyNotify destroy)
{
    if (!context) {
        if (destroy)
            destroy(data);
        return;
    }
    g_main_context_invoke_full(context, G_PRIORITY_DEFAULT, fn, data, destroy);
}

gboolean wv_host_is_thread(void) { return thread && g_thread_self() == thread; }
WPEDisplay *wv_host_display(void) { return display; }
WebKitWebContext *wv_host_context(void) { return web_context; }
const char *wv_host_data_dir(void) { return data_root; }
const char *wv_host_cache_dir(void) { return cache_root; }

void wv_emit(int64_t view, int kind, int64_t a, int64_t b, const char *s1, const char *s2)
{
    JNIEnv *env = thread_env;
    if (!env || !wv_host_is_thread())
        return;
    jstring j1 = s1 ? wv_jstring(env, s1) : NULL;
    jstring j2 = s2 ? wv_jstring(env, s2) : NULL;
    (*env)->CallStaticVoidMethod(env, event_class, on_event, (jlong)view, (jint)kind, (jlong)a, (jlong)b, j1, j2);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    if (j1)
        (*env)->DeleteLocalRef(env, j1);
    if (j2)
        (*env)->DeleteLocalRef(env, j2);
}

/* Standard UTF-8 to a Java string. NewStringUTF wants Java's modified UTF-8 and mangles characters outside the BMP. */
jstring wv_jstring(JNIEnv *env, const char *utf8)
{
    gchar *valid = g_utf8_make_valid(utf8, -1);
    glong n = 0;
    gunichar2 *u16 = g_utf8_to_utf16(valid, -1, NULL, &n, NULL);
    jstring s = u16 ? (*env)->NewString(env, (const jchar *)u16, (jsize)n) : NULL;
    g_free(u16);
    g_free(valid);
    return s;
}

/* A Java string to standard UTF-8; a lone surrogate becomes U+FFFD. NULL for null. */
char *wv_utf8(JNIEnv *env, jstring s)
{
    if (!s)
        return NULL;
    jsize n = (*env)->GetStringLength(env, s);
    const jchar *chars = (*env)->GetStringChars(env, s, NULL);
    GString *out = g_string_sized_new((gsize)n + 16);
    for (jsize i = 0; i < n; i++) {
        gunichar c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < n && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[i + 1] - 0xdc00);
            i++;
        } else if (c >= 0xd800 && c <= 0xdfff) {
            c = 0xfffd;
        }
        g_string_append_unichar(out, c);
    }
    (*env)->ReleaseStringChars(env, s, chars);
    return g_string_free(out, FALSE);
}
