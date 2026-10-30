/* The natives of WV_JNI_CLASS, registered by JNI_OnLoad, so the same C serves the test and the engine under their own
 * class names. Most post a WvOp to the WebKit thread; createView, saveState and restoreState wait for it; pullFrame,
 * drainFrames, frameMode, version and uploadBench run on the caller's thread. */
#include "wv.h"
#include <string.h>

static char **strv_of(JNIEnv *env, jobjectArray array)
{
    if (!array)
        return NULL;
    jsize n = (*env)->GetArrayLength(env, array);
    char **out = g_new0(char *, (gsize)n + 1);
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        out[i] = s ? wv_utf8(env, s) : g_strdup("");
        if (s)
            (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static GBytes *bytes_of(JNIEnv *env, jbyteArray array)
{
    if (!array)
        return NULL;
    jsize n = (*env)->GetArrayLength(env, array);
    guint8 *data = g_malloc(n > 0 ? (gsize)n : 1);
    (*env)->GetByteArrayRegion(env, array, 0, n, (jbyte *)data);
    return g_bytes_new_take(data, (gsize)n);
}

static jint n_init(JNIEnv *env, jclass c, jint flags, jstring data, jstring cache)
{
    char *d = wv_utf8(env, data), *k = wv_utf8(env, cache);
    int result = wv_host_init(flags, d, k);
    g_free(d);
    g_free(k);
    return result;
}

static jstring n_version(JNIEnv *env, jclass c)
{
    char *v = g_strdup_printf("%u.%u.%u", webkit_get_major_version(), webkit_get_minor_version(), webkit_get_micro_version());
    jstring s = wv_jstring(env, v);
    g_free(v);
    return s;
}

static jlong n_create_view(JNIEnv *env, jclass c, jint profile, jstring dir, jboolean media, jint width, jint height, jdouble scale)
{
    WvOp *op = wv_op_new(WV_OP_CREATE, 0);
    op->i[0] = profile;
    op->i[1] = media;
    op->i[2] = width;
    op->i[3] = height;
    op->d[0] = scale;
    op->s[0] = wv_utf8(env, dir);
    int64_t id = wv_op_call(op, NULL);
    return id > 0 ? (jlong)id : 0;
}

static void n_destroy_view(JNIEnv *env, jclass c, jlong view) { wv_op_post(wv_op_new(WV_OP_DESTROY, view)); }

static void n_register_scheme(JNIEnv *env, jclass c, jstring scheme, jboolean secure)
{
    WvOp *op = wv_op_new(WV_OP_REGISTER_SCHEME, 0);
    op->s[0] = wv_utf8(env, scheme);
    op->i[0] = secure;
    wv_op_post(op);
}

static void n_configure(JNIEnv *env, jclass c, jlong view, jstring relay, jstring shim, jstring world, jstring handler,
    jint flags, jint background, jstring agent)
{
    WvOp *op = wv_op_new(WV_OP_CONFIGURE, view);
    op->s[0] = wv_utf8(env, relay);
    op->s[1] = wv_utf8(env, shim);
    op->s[2] = wv_utf8(env, world);
    op->s[3] = wv_utf8(env, handler);
    op->s[4] = wv_utf8(env, agent);
    op->i[0] = flags;
    op->i[1] = (uint32_t)background;
    wv_op_post(op);
}

static void n_set_dark(JNIEnv *env, jclass c, jboolean dark)
{
    WvOp *op = wv_op_new(WV_OP_SET_DARK, 0);
    op->i[0] = dark;
    wv_op_post(op);
}

static void n_load(JNIEnv *env, jclass c, jlong view, jint kind, jstring text, jstring base, jobjectArray headers)
{
    WvOp *op = wv_op_new(WV_OP_LOAD, view);
    op->i[0] = kind;
    op->s[0] = wv_utf8(env, text);
    op->s[1] = wv_utf8(env, base);
    op->strv = strv_of(env, headers);
    wv_op_post(op);
}

static void n_navigate(JNIEnv *env, jclass c, jlong view, jint what)
{
    WvOp *op = wv_op_new(WV_OP_NAVIGATE, view);
    op->i[0] = what;
    wv_op_post(op);
}

static void n_evaluate(JNIEnv *env, jclass c, jlong view, jint request, jstring script, jstring world)
{
    WvOp *op = wv_op_new(WV_OP_EVALUATE, view);
    op->i[0] = request;
    op->s[0] = wv_utf8(env, script);
    op->s[1] = wv_utf8(env, world);
    wv_op_post(op);
}

static void n_call_async(JNIEnv *env, jclass c, jlong view, jint request, jstring body, jstring world, jobjectArray args)
{
    WvOp *op = wv_op_new(WV_OP_CALL_ASYNC, view);
    op->i[0] = request;
    op->s[0] = wv_utf8(env, body);
    op->s[1] = wv_utf8(env, world);
    op->strv = strv_of(env, args);
    wv_op_post(op);
}

static void n_decide(JNIEnv *env, jclass c, jlong view, jlong decision, jint verdict)
{
    WvOp *op = wv_op_new(WV_OP_DECIDE, view);
    op->i[0] = decision;
    op->i[1] = verdict;
    wv_op_post(op);
}

static jbyteArray n_save_state(JNIEnv *env, jclass c, jlong view)
{
    GBytes *bytes = NULL;
    if (wv_op_call(wv_op_new(WV_OP_SAVE_STATE, view), &bytes) != 1 || !bytes) {
        if (bytes)
            g_bytes_unref(bytes);
        return NULL;
    }
    gsize n = 0;
    const guint8 *data = g_bytes_get_data(bytes, &n);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)n);
    if (out)
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)data);
    g_bytes_unref(bytes);
    return out;
}

static jboolean n_restore_state(JNIEnv *env, jclass c, jlong view, jbyteArray state)
{
    WvOp *op = wv_op_new(WV_OP_RESTORE_STATE, view);
    op->bytes = bytes_of(env, state);
    return wv_op_call(op, NULL) == 1;
}

static void n_set_size(JNIEnv *env, jclass c, jlong view, jint width, jint height, jdouble scale)
{
    WvOp *op = wv_op_new(WV_OP_SET_SIZE, view);
    op->i[0] = width;
    op->i[1] = height;
    op->d[0] = scale;
    wv_op_post(op);
}

static void n_set_visible(JNIEnv *env, jclass c, jlong view, jboolean visible)
{
    WvOp *op = wv_op_new(WV_OP_SET_VISIBLE, view);
    op->i[0] = visible;
    wv_op_post(op);
}

static void n_set_focus(JNIEnv *env, jclass c, jlong view, jboolean focused)
{
    WvOp *op = wv_op_new(WV_OP_SET_FOCUS, view);
    op->i[0] = focused;
    wv_op_post(op);
}

static void n_pointer(JNIEnv *env, jclass c, jlong view, jint type, jdouble x, jdouble y, jint button, jint modifiers,
    jint count, jlong time)
{
    WvOp *op = wv_op_new(WV_OP_POINTER, view);
    op->i[0] = type;
    op->i[1] = button;
    op->i[2] = modifiers;
    op->i[3] = count;
    op->i[4] = time;
    op->d[0] = x;
    op->d[1] = y;
    wv_op_post(op);
}

static void n_scroll(JNIEnv *env, jclass c, jlong view, jdouble dx, jdouble dy, jdouble x, jdouble y, jboolean precise,
    jint modifiers, jlong time)
{
    WvOp *op = wv_op_new(WV_OP_SCROLL, view);
    op->d[0] = dx;
    op->d[1] = dy;
    op->d[2] = x;
    op->d[3] = y;
    op->i[0] = precise;
    op->i[1] = modifiers;
    op->i[2] = time;
    wv_op_post(op);
}

static void n_key(JNIEnv *env, jclass c, jlong view, jboolean down, jint keyval, jint keycode, jint modifiers, jlong time)
{
    WvOp *op = wv_op_new(WV_OP_KEY, view);
    op->i[0] = down;
    op->i[1] = (uint32_t)keyval;
    op->i[2] = keycode;
    op->i[3] = modifiers;
    op->i[4] = time;
    wv_op_post(op);
}

static void n_ime(JNIEnv *env, jclass c, jlong view, jint kind, jstring text, jint cursor)
{
    WvOp *op = wv_op_new(WV_OP_IME, view);
    op->i[0] = kind;
    op->i[1] = cursor;
    op->s[0] = wv_utf8(env, text);
    wv_op_post(op);
}

static jint n_pull_frame(JNIEnv *env, jclass c, jlong view, jlong dst, jint row_bytes, jint width, jint height,
    jboolean full, jlongArray out)
{
    int64_t values[WV_PULL_OUT] = { 0 };
    int result = -2; /* no such view */
    WvFrames *f = wv_frames_lookup(view);
    if (f) {
        result = wv_frames_pull(f, (uint8_t *)(intptr_t)dst, row_bytes, width, height, full, values);
        wv_frames_unref(f);
    }
    (*env)->SetLongArrayRegion(env, out, 0, WV_PULL_OUT, (const jlong *)values);
    return result;
}

static jint n_drain_frames(JNIEnv *env, jclass c, jlong view, jlongArray out)
{
    WvFrames *f = wv_frames_lookup(view);
    if (!f)
        return 0;
    jsize capacity = (*env)->GetArrayLength(env, out) / WV_RECORD_LONGS;
    int64_t *values = g_new0(int64_t, (gsize)capacity * WV_RECORD_LONGS + 1);
    int n = wv_frames_drain(f, values, capacity);
    wv_frames_unref(f);
    if (n > 0)
        (*env)->SetLongArrayRegion(env, out, 0, n * WV_RECORD_LONGS, (const jlong *)values);
    g_free(values);
    return n;
}

static void n_frame_mode(JNIEnv *env, jclass c, jlong view, jboolean auto_ack, jboolean verify, jdouble damage_scale)
{
    WvFrames *f = wv_frames_lookup(view);
    if (!f)
        return;
    wv_frames_configure(f, auto_ack, verify, damage_scale);
    wv_frames_unref(f);
}

static void n_finish_scheme(JNIEnv *env, jclass c, jlong request, jint status, jstring mime, jbyteArray data)
{
    WvOp *op = wv_op_new(WV_OP_FINISH_SCHEME, 0);
    op->i[0] = request;
    op->i[1] = status;
    op->s[0] = wv_utf8(env, mime);
    op->bytes = bytes_of(env, data);
    wv_op_post(op);
}

static void n_terminate(JNIEnv *env, jclass c, jlong view) { wv_op_post(wv_op_new(WV_OP_TERMINATE, view)); }

#ifdef WV_GLBENCH
static jstring n_upload_bench(JNIEnv *env, jclass c, jint width, jint height, jint dirty_width, jint dirty_height,
    jint runs, jlongArray out)
{
    int64_t values[6] = { 0 };
    char renderer[256] = "";
    int result = wv_upload_bench(width, height, dirty_width, dirty_height, runs, values, renderer, (int)sizeof renderer);
    values[5] = result;
    (*env)->SetLongArrayRegion(env, out, 0, 6, (const jlong *)values);
    return result == 0 ? wv_jstring(env, renderer) : NULL;
}
#endif

static const JNINativeMethod methods[] = {
    { "init", "(ILjava/lang/String;Ljava/lang/String;)I", (void *)n_init },
    { "version", "()Ljava/lang/String;", (void *)n_version },
    { "createView", "(ILjava/lang/String;ZIID)J", (void *)n_create_view },
    { "destroyView", "(J)V", (void *)n_destroy_view },
    { "registerScheme", "(Ljava/lang/String;Z)V", (void *)n_register_scheme },
    { "configure", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;)V", (void *)n_configure },
    { "setDark", "(Z)V", (void *)n_set_dark },
    { "load", "(JILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V", (void *)n_load },
    { "navigate", "(JI)V", (void *)n_navigate },
    { "evaluate", "(JILjava/lang/String;Ljava/lang/String;)V", (void *)n_evaluate },
    { "callAsync", "(JILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V", (void *)n_call_async },
    { "decide", "(JJI)V", (void *)n_decide },
    { "saveState", "(J)[B", (void *)n_save_state },
    { "restoreState", "(J[B)Z", (void *)n_restore_state },
    { "setSize", "(JIID)V", (void *)n_set_size },
    { "setVisible", "(JZ)V", (void *)n_set_visible },
    { "setFocus", "(JZ)V", (void *)n_set_focus },
    { "pointer", "(JIDDIIIJ)V", (void *)n_pointer },
    { "scroll", "(JDDDDZIJ)V", (void *)n_scroll },
    { "key", "(JZIIIJ)V", (void *)n_key },
    { "ime", "(JILjava/lang/String;I)V", (void *)n_ime },
    { "pullFrame", "(JJIIIZ[J)I", (void *)n_pull_frame },
    { "drainFrames", "(J[J)I", (void *)n_drain_frames },
    { "frameMode", "(JZZD)V", (void *)n_frame_mode },
    { "finishScheme", "(JILjava/lang/String;[B)V", (void *)n_finish_scheme },
    { "terminate", "(J)V", (void *)n_terminate },
#ifdef WV_GLBENCH
    { "uploadBench", "(IIIII[J)Ljava/lang/String;", (void *)n_upload_bench },
#endif
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK)
        return JNI_ERR;
    jclass cls = (*env)->FindClass(env, WV_JNI_CLASS);
    if (!cls)
        return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, methods, (jint)(sizeof methods / sizeof methods[0])) != 0)
        return JNI_ERR;
    jmethodID on_event = (*env)->GetStaticMethodID(env, cls, "onEvent", "(JIJJLjava/lang/String;Ljava/lang/String;)V");
    if (!on_event)
        return JNI_ERR;
    wv_host_set_jvm(vm, (jclass)(*env)->NewGlobalRef(env, cls), on_event);
    return JNI_VERSION_1_8;
}
