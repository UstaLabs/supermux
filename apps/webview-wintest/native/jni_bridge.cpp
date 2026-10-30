// JNI surface of wv2capture.dll for dev.supermux.webview.wintest.WinNative (the plan's 30 natives, imeControl, and the GPU
// gate's pullDirect).
// One WebView per process.
#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#include "probes.h"
#include "ui_thread.h"
#include "webview.h"

namespace {
JavaVM* g_vm = nullptr;
jclass g_class = nullptr;
jmethodID g_onFrameReady = nullptr;
jmethodID g_onEvent = nullptr;
jmethodID g_onCustomCursor = nullptr;
wv2::UiThread g_ui;
wv2::WebHost g_host;

JNIEnv* envForThisThread() {
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK) return env;
    // Named, so the scenarios can tell these threads' CPU time (the capture copies) from the JVM's own.
    JavaVMAttachArgs args{JNI_VERSION_1_8, const_cast<char*>("wv2-native"), nullptr};
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK) return nullptr;
    return env;
}

jstring toJava(JNIEnv* env, std::wstring const& text) {
    return env->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
}

std::wstring fromJava(JNIEnv* env, jstring text) {
    if (!text) return L"";
    const jchar* chars = env->GetStringChars(text, nullptr);
    std::wstring out(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(env->GetStringLength(text)));
    env->ReleaseStringChars(text, chars);
    return out;
}

void clearException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}
}  // namespace

namespace wv2 {
void emit(int kind, int64_t a, int64_t b, std::wstring const& text) {
    JNIEnv* env = envForThisThread();
    if (!env || !g_class) return;
    jstring value = toJava(env, text);
    env->CallStaticVoidMethod(g_class, g_onEvent, static_cast<jint>(kind), static_cast<jlong>(a), static_cast<jlong>(b),
                              value);
    clearException(env);
    env->DeleteLocalRef(value);
}

void emitFrameReady() {
    JNIEnv* env = envForThisThread();
    if (!env || !g_class) return;
    env->CallStaticVoidMethod(g_class, g_onFrameReady);
    clearException(env);
}

void emitCustomCursor(int width, int height, int hotX, int hotY, std::vector<int32_t> const& argb) {
    JNIEnv* env = envForThisThread();
    if (!env || !g_class) return;
    jintArray pixels = env->NewIntArray(static_cast<jsize>(argb.size()));
    env->SetIntArrayRegion(pixels, 0, static_cast<jsize>(argb.size()), reinterpret_cast<const jint*>(argb.data()));
    env->CallStaticVoidMethod(g_class, g_onCustomCursor, static_cast<jint>(width), static_cast<jint>(height),
                              static_cast<jint>(hotX), static_cast<jint>(hotY), pixels);
    clearException(env);
    env->DeleteLocalRef(pixels);
}

void logLine(std::wstring const& line) { emit(kLog, 0, 0, line); }
}  // namespace wv2

#define WV2_JNI(type, name) extern "C" JNIEXPORT type JNICALL Java_dev_supermux_webview_wintest_WinNative_##name

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_8;
}

WV2_JNI(jint, nativeInit)(JNIEnv* env, jclass cls) {
    if (g_class) return 0;
    g_onFrameReady = env->GetStaticMethodID(cls, "onFrameReady", "()V");
    g_onEvent = env->GetStaticMethodID(cls, "onEvent", "(IJJLjava/lang/String;)V");
    g_onCustomCursor = env->GetStaticMethodID(cls, "onCustomCursor", "(IIII[I)V");
    if (!g_onFrameReady || !g_onEvent || !g_onCustomCursor) {
        clearException(env);
        return 2;
    }
    g_class = static_cast<jclass>(env->NewGlobalRef(cls));
    return g_ui.start() ? 0 : 3;
}

WV2_JNI(jlong, hwndOf)(JNIEnv* env, jclass, jobject component) {
    JAWT awt{};
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) return 0;
    JAWT_DrawingSurface* surface = awt.GetDrawingSurface(env, component);
    if (!surface) return 0;
    jlong hwnd = 0;
    if ((surface->Lock(surface) & JAWT_LOCK_ERROR) == 0) {
        if (JAWT_DrawingSurfaceInfo* info = surface->GetDrawingSurfaceInfo(surface)) {
            hwnd = reinterpret_cast<jlong>(static_cast<JAWT_Win32DrawingSurfaceInfo*>(info->platformInfo)->hwnd);
            surface->FreeDrawingSurfaceInfo(info);
        }
        surface->Unlock(surface);
    }
    awt.FreeDrawingSurface(surface);
    return hwnd;
}

WV2_JNI(void, start)(JNIEnv* env, jclass, jlong canvas, jint parentMode, jstring userDataFolder, jstring browserArguments,
                     jboolean dirtyRegions, jboolean compensateOffset, jint adapterMode, jboolean directPull) {
    wv2::HostOptions options;
    options.canvas = reinterpret_cast<HWND>(canvas);
    options.parentMode = parentMode;
    options.userDataFolder = fromJava(env, userDataFolder);
    options.browserArguments = fromJava(env, browserArguments);
    options.dirtyRegions = dirtyRegions == JNI_TRUE;
    options.compensateOffset = compensateOffset == JNI_TRUE;
    options.adapterMode = adapterMode;
    options.directPull = directPull == JNI_TRUE;
    g_ui.post([options] { g_host.start(options); });
}

WV2_JNI(void, setBounds)(JNIEnv*, jclass, jint x, jint y, jint width, jint height) {
    g_ui.post([=] { g_host.setBounds(x, y, width, height); });
}

WV2_JNI(void, notifyParentMoved)(JNIEnv*, jclass) {
    g_ui.post([] { g_host.notifyParentMoved(); });
}

WV2_JNI(void, setVisible)(JNIEnv*, jclass, jboolean visible) {
    g_ui.post([=] { g_host.setVisible(visible == JNI_TRUE); });
}

WV2_JNI(void, addDocumentScript)(JNIEnv* env, jclass, jstring script) {
    auto text = fromJava(env, script);
    g_ui.post([text] { g_host.addDocumentScript(text); });
}

WV2_JNI(void, navigateToString)(JNIEnv* env, jclass, jstring html) {
    auto text = fromJava(env, html);
    g_ui.post([text] { g_host.navigateToString(text); });
}

WV2_JNI(void, executeScript)(JNIEnv* env, jclass, jlong requestId, jstring script) {
    auto text = fromJava(env, script);
    g_ui.post([requestId, text] { g_host.executeScript(requestId, text); });
}

WV2_JNI(void, mouse)(JNIEnv*, jclass, jint kind, jint virtualKeys, jint mouseData, jint x, jint y) {
    g_ui.post([=] { g_host.mouse(kind, virtualKeys, mouseData, x, y); });
}

WV2_JNI(void, pointer)(JNIEnv*, jclass, jint pointerKind, jint eventKind, jint pointerId, jint x, jint y, jint pressure) {
    g_ui.post([=] { g_host.pointer(pointerKind, eventKind, pointerId, x, y, pressure); });
}

WV2_JNI(void, moveFocus)(JNIEnv*, jclass, jint reason) {
    g_ui.post([=] { g_host.moveFocus(reason); });
}

WV2_JNI(jint, copyFrame)(JNIEnv* env, jclass, jlong pixels, jint rowBytes, jint width, jint height, jboolean full,
                         jlongArray out) {
    int64_t values[wv2::FrameCapture::kPullFields] = {};
    const int result = g_host.capture().copyTo(reinterpret_cast<uint8_t*>(pixels), rowBytes, width, height,
                                               full == JNI_TRUE, values);
    env->SetLongArrayRegion(out, 0, wv2::FrameCapture::kPullFields, reinterpret_cast<const jlong*>(values));
    return result;
}

// The three-copy chain's pull (-Dwv2.chain=3): from the mapped staging texture straight into the UI's pixels.
WV2_JNI(jint, pullDirect)(JNIEnv* env, jclass, jlong pixels, jint rowBytes, jint width, jint height, jboolean full,
                          jlongArray out) {
    int64_t values[wv2::FrameCapture::kPullFields] = {};
    const int result = g_host.capture().pullDirect(reinterpret_cast<uint8_t*>(pixels), rowBytes, width, height,
                                                   full == JNI_TRUE, values);
    env->SetLongArrayRegion(out, 0, wv2::FrameCapture::kPullFields, reinterpret_cast<const jlong*>(values));
    return result;
}

WV2_JNI(jint, drainArrivals)(JNIEnv* env, jclass, jlongArray out) {
    constexpr int fields = wv2::FrameCapture::kArrivalFields;
    const jsize length = env->GetArrayLength(out);
    std::vector<int64_t> values(static_cast<size_t>(length));
    const int count = g_host.capture().drainArrivals(values.data(), length / fields);
    env->SetLongArrayRegion(out, 0, count * fields, reinterpret_cast<const jlong*>(values.data()));
    return count;
}

WV2_JNI(void, captureStats)(JNIEnv* env, jclass, jlongArray out) {
    const auto s = g_host.capture().stats();
    const int64_t values[9] = {s.arrived, s.copied, s.emptyDirty, s.blank, s.recreates,
                               s.rects,   s.dirtyPixels, s.fullCopies, s.takes};
    env->SetLongArrayRegion(out, 0, 9, reinterpret_cast<const jlong*>(values));
}

WV2_JNI(jlong, nowNanos)(JNIEnv*, jclass) { return wv2::qpcNanos(); }

WV2_JNI(jlong, epochMicros)(JNIEnv*, jclass) { return wv2::epochMicros(); }

WV2_JNI(jlong, browserPid)(JNIEnv*, jclass) { return g_host.browserPid(); }

WV2_JNI(jlong, parentHwnd)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(g_host.parent()); }

WV2_JNI(jstring, focusInfo)(JNIEnv* env, jclass) { return toJava(env, wv2::focusInfo()); }

WV2_JNI(jstring, cursorInfo)(JNIEnv* env, jclass) { return toJava(env, wv2::cursorInfo()); }

WV2_JNI(jstring, standardCursorInfo)(JNIEnv* env, jclass, jint idc) {
    return toJava(env, wv2::standardCursorInfo(idc));
}

WV2_JNI(jintArray, systemCursorImage)(JNIEnv* env, jclass, jint idc) {
    int width = 0;
    int height = 0;
    int hotX = 0;
    int hotY = 0;
    std::vector<int32_t> argb;
    HCURSOR cursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(idc));
    if (!cursor || !wv2::cursorToArgb(cursor, width, height, hotX, hotY, argb)) return nullptr;
    jintArray out = env->NewIntArray(static_cast<jsize>(4 + argb.size()));
    const jint head[4] = {width, height, hotX, hotY};
    env->SetIntArrayRegion(out, 0, 4, head);
    env->SetIntArrayRegion(out, 4, static_cast<jsize>(argb.size()), reinterpret_cast<const jint*>(argb.data()));
    return out;
}

WV2_JNI(jstring, topLevelWindows)(JNIEnv* env, jclass, jlong pid) {
    return toJava(env, wv2::topLevelWindows(static_cast<DWORD>(pid)));
}

WV2_JNI(jstring, windowTree)(JNIEnv* env, jclass, jlong root) {
    return toJava(env, wv2::windowTree(reinterpret_cast<HWND>(root)));
}

WV2_JNI(void, sendScan)(JNIEnv*, jclass, jint scan, jboolean down, jboolean extended) {
    wv2::sendScan(static_cast<WORD>(scan), down == JNI_TRUE, extended == JNI_TRUE);
}

WV2_JNI(void, sendVk)(JNIEnv*, jclass, jint vk, jboolean down) { wv2::sendVk(static_cast<WORD>(vk), down == JNI_TRUE); }

WV2_JNI(jstring, setInputLanguage)(JNIEnv* env, jclass, jstring klid) {
    return toJava(env, wv2::setInputLanguage(fromJava(env, klid)));
}

WV2_JNI(jboolean, forceFocus)(JNIEnv*, jclass, jlong hwnd) {
    return wv2::forceFocus(reinterpret_cast<HWND>(hwnd)) ? JNI_TRUE : JNI_FALSE;
}

WV2_JNI(jstring, imeControl)(JNIEnv* env, jclass, jint open, jint conversion) {
    return toJava(env, wv2::imeControl(open, conversion));
}

WV2_JNI(void, stop)(JNIEnv*, jclass) {
    HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    const bool posted = g_ui.post([done] {
        g_host.stop();
        SetEvent(done);
    });
    if (posted) WaitForSingleObject(done, 3000);
    CloseHandle(done);
    g_ui.stop();
}
