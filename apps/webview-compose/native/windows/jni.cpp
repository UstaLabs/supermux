// The JNI surface of webview_windows.dll for dev.supermux.webview.engine.windows.WinGlue (WinGlue.kt says which thread
// each native runs on). Natives that touch WebView2 post to the UI thread and return at once; pullFrame and frameSeq run
// on the caller's thread (the EDT) under the page's own lock. Java is called only by the glue's own threads, attached
// once each: the UI thread and the memory watcher.
#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#include "wv2.h"
#include "ui_thread.h"
#include "environment.h"
#include "page.h"
#include "capture.h"

namespace {
JavaVM* g_vm = nullptr;
jclass g_glue = nullptr;         // WinGlue, a global reference
jmethodID g_onEvent = nullptr;   // onEvent(JIIJJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V
jmethodID g_decide = nullptr;    // decideNavigation(JIILjava/lang/String;)I
jmethodID g_offerKey = nullptr;  // offerKey(JIII)Z

/** The calling thread's JNIEnv. Only the glue's own threads attach (as daemons, for good): never a pool thread. */
JNIEnv* env() {
    if (!g_vm) return nullptr;
    JNIEnv* e = nullptr;
    const jint status = g_vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_8);
    if (status == JNI_OK) return e;
    if (status != JNI_EDETACHED || !wv::UiThread::isGlueThread()) return nullptr;
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&e), nullptr) != JNI_OK) return nullptr;
    return e;
}

/** Whether this thread may call the JVM: a Java thread, or one of the glue's own (which attaches once). */
bool canCallJava() {
    if (!g_vm) return false;
    JNIEnv* e = nullptr;
    return g_vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_8) == JNI_OK || wv::UiThread::isGlueThread();
}

jstring toJava(JNIEnv* e, std::wstring_view text) {
    if (text.data() == nullptr) return nullptr;
    return e->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
}

std::wstring fromJava(JNIEnv* e, jstring text) {
    if (!text) return {};
    const jchar* chars = e->GetStringChars(text, nullptr);
    if (!chars) return {};
    std::wstring out(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(e->GetStringLength(text)));
    e->ReleaseStringChars(text, chars);
    return out;
}

std::vector<uint8_t> bytesOf(JNIEnv* e, jbyteArray array) {
    if (!array) return {};
    std::vector<uint8_t> out(static_cast<size_t>(e->GetArrayLength(array)));
    if (!out.empty()) e->GetByteArrayRegion(array, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte*>(out.data()));
    return out;
}

/** JAWT from the jawt.dll the loader loaded first (WinNativeLoader): no JDK library is linked, on any architecture. */
using GetAwtFn = jboolean(JNICALL*)(JNIEnv*, JAWT*);
GetAwtFn jawtGetAwt() {
    HMODULE jawt = GetModuleHandleW(L"jawt.dll");
    return jawt ? reinterpret_cast<GetAwtFn>(GetProcAddress(jawt, "JAWT_GetAWT")) : nullptr;
}
}  // namespace

namespace wv {
void emit(int64_t page, int type, int epoch, int64_t id, int64_t flags, std::wstring_view a, std::wstring_view b,
          std::wstring_view c, std::vector<uint8_t> const* data) {
    JNIEnv* e = env();
    if (!e || !g_glue) return;
    // These threads never return to Java, so nothing frees their local references: a frame of our own.
    if (e->PushLocalFrame(8) != JNI_OK) {
        e->ExceptionClear();
        return;
    }
    jbyteArray bytes = nullptr;
    if (data) {
        bytes = e->NewByteArray(static_cast<jsize>(data->size()));
        if (bytes && !data->empty()) e->SetByteArrayRegion(bytes, 0, static_cast<jsize>(data->size()), reinterpret_cast<const jbyte*>(data->data()));
    }
    e->CallStaticVoidMethod(g_glue, g_onEvent, static_cast<jlong>(page), static_cast<jint>(type), static_cast<jint>(epoch),
                            static_cast<jlong>(id), static_cast<jlong>(flags), toJava(e, a), toJava(e, b), toJava(e, c), bytes);
    if (e->ExceptionCheck()) e->ExceptionClear();
    e->PopLocalFrame(nullptr);
}

int decideNavigation(int64_t page, int epoch, int flags, std::wstring_view url) {
    JNIEnv* e = env();
    if (!e || !g_glue || e->PushLocalFrame(4) != JNI_OK) return kRefuse;
    const jint verdict = e->CallStaticIntMethod(g_glue, g_decide, static_cast<jlong>(page), static_cast<jint>(epoch),
                                                static_cast<jint>(flags), toJava(e, url));
    const bool failed = e->ExceptionCheck();
    if (failed) e->ExceptionClear();
    e->PopLocalFrame(nullptr);
    return failed ? kRefuse : verdict;  // a decision that failed is the safe one
}

bool offerKey(int64_t page, int vk, int scan, int flags) {
    JNIEnv* e = env();
    if (!e || !g_glue) return false;
    const jboolean taken = e->CallStaticBooleanMethod(g_glue, g_offerKey, static_cast<jlong>(page), static_cast<jint>(vk),
                                                      static_cast<jint>(scan), static_cast<jint>(flags));
    if (e->ExceptionCheck()) {
        e->ExceptionClear();
        return false;  // the page keeps its key
    }
    return taken == JNI_TRUE;
}

/** A diagnostic line for the engine's log. A pool thread (WGC's) never attaches: its lines go by the UI thread. */
void logLine(std::wstring const& line) {
    if (canCallJava()) {
        emit(0, kLog, 0, 0, 0, line);
    } else {
        UiThread::get().post([line] { emit(0, kLog, 0, 0, 0, line); });
    }
}
}  // namespace wv

#define WV_JNI(type, name) extern "C" JNIEXPORT type JNICALL Java_dev_supermux_webview_engine_windows_WinGlue_##name

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_8) != JNI_OK) return JNI_VERSION_1_8;
    // The class loader of the class that called System.load: WinGlue's own.
    jclass glue = e->FindClass("dev/supermux/webview/engine/windows/WinGlue");
    if (glue) {
        g_onEvent = e->GetStaticMethodID(glue, "onEvent", "(JIIJJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V");
        g_decide = e->GetStaticMethodID(glue, "decideNavigation", "(JIILjava/lang/String;)I");
        g_offerKey = e->GetStaticMethodID(glue, "offerKey", "(JIII)Z");
        if (g_onEvent && g_decide && g_offerKey) g_glue = static_cast<jclass>(e->NewGlobalRef(glue));
        e->DeleteLocalRef(glue);
    }
    if (e->ExceptionCheck()) e->ExceptionClear();
    return JNI_VERSION_1_8;
}

/** WinGlue.ABI, or -1 when an upcall is missing (a shrunk build without the keep rule, L14). */
WV_JNI(jint, abiVersion)(JNIEnv*, jclass) { return g_glue ? WV_ABI_VERSION : -1; }

/** Starts the UI thread, then checks capture and the device there (an STA, where WinRT is ready). Waits up to 15 s. */
WV_JNI(jint, start)(JNIEnv*, jclass) {
    if (!g_glue) return wv::kStartNoUpcall;
    if (!wv::UiThread::get().start()) return wv::kStartNoThread;
    auto result = std::make_shared<std::atomic<int>>(wv::kStartNoThread);
    wv::UiThread::get().runSync([result] {
        namespace meta = winrt::Windows::Foundation::Metadata;
        try {
            if (!meta::ApiInformation::IsMethodPresent(L"Windows.Graphics.Capture.GraphicsCaptureItem", L"CreateFromVisual") ||
                !winrt::Windows::Graphics::Capture::GraphicsCaptureSession::IsSupported()) {
                *result = wv::kStartNoCapture;
                return;
            }
        } catch (...) {
            *result = wv::kStartNoCapture;
            return;
        }
        *result = wv::GpuDevices::primary() ? wv::kStartOk : wv::kStartNoDevice;
    }, 15000);
    return result->load();
}

WV_JNI(jstring, deviceName)(JNIEnv* e, jclass) {
    auto device = wv::GpuDevices::primary();
    return toJava(e, device ? device->name() : std::wstring(L"none"));
}

WV_JNI(jlong, hwndOf)(JNIEnv* e, jclass, jobject component) {
    const GetAwtFn getAwt = jawtGetAwt();
    if (!getAwt || !component) return 0;
    JAWT awt{};
    awt.version = JAWT_VERSION_9;
    if (getAwt(e, &awt) == JNI_FALSE) return 0;
    JAWT_DrawingSurface* surface = awt.GetDrawingSurface(e, component);
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

WV_JNI(jlong, rootOf)(JNIEnv*, jclass, jlong hwnd) {
    return reinterpret_cast<jlong>(GetAncestor(reinterpret_cast<HWND>(hwnd), GA_ROOT));
}

WV_JNI(void, startMemoryWatch)(JNIEnv*, jclass) { wv::startMemoryWatch(); }
