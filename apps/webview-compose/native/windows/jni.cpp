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
        // Monitors or adapters changed (D24): every page's capture looks up its window's adapter again.
        wv::UiThread::get().onDisplayChange([] {
            wv::GpuDevices::displaysChanged();
            wv::Pages::forEach([](wv::WvPage& page) { page.followAdapter(true); });
        });
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

// --- The environment (Task 6) ---

/** The installed runtime's version, or null (WebView2's own search: Evergreen, or WEBVIEW2_BROWSER_EXECUTABLE_FOLDER). Any thread. */
WV_JNI(jstring, runtimeVersion)(JNIEnv* e, jclass) {
    wv::CoString version;
    if (FAILED(GetAvailableCoreWebView2BrowserVersionString(nullptr, version.put())) || !version.p) return nullptr;
    return toJava(e, version.str());
}

/** -1, 0 or 1 as [a] is older, the same or newer than [b]; 0 if either can't be read. Any thread. */
WV_JNI(jint, compareVersions)(JNIEnv* e, jclass, jstring a, jstring b) {
    int result = 0;
    if (FAILED(CompareBrowserVersions(fromJava(e, a).c_str(), fromJava(e, b).c_str(), &result))) return 0;
    return result < 0 ? -1 : result > 0 ? 1 : 0;
}

WV_JNI(void, openEnvironment)(JNIEnv* e, jclass, jlong request, jstring folder, jstring arguments) {
    auto f = fromJava(e, folder);
    auto a = fromJava(e, arguments);
    wv::UiThread::get().post([=] { wv::Environment::get().open(request, f, a); });
}

// --- Pages (Task 7): each posts; the page is looked up on the UI thread ---

namespace {
/** Runs [fn] with the page of [token] on the UI thread, if it is still there. */
template <typename Fn>
void onPage(jlong token, Fn fn) {
    wv::UiThread::get().post([token, fn = std::move(fn)]() mutable {
        if (auto page = wv::Pages::find(token)) fn(*page);
    });
}
}  // namespace

WV_JNI(void, createPage)(JNIEnv* e, jclass, jlong token, jstring profile, jboolean inPrivate, jint background) {
    auto name = fromJava(e, profile);
    wv::UiThread::get().post([=] {
        auto page = std::make_shared<wv::WvPage>(token);
        wv::Pages::put(token, page);
        page->create(name, inPrivate == JNI_TRUE, static_cast<uint32_t>(background));
    });
}

WV_JNI(void, release)(JNIEnv*, jclass, jlong token) {
    wv::UiThread::get().post([=] {
        if (auto page = wv::Pages::find(token)) page->release();
        wv::Pages::remove(token);
    });
}

WV_JNI(void, configure)(JNIEnv* e, jclass, jlong token, jint flags, jint background, jstring userAgentSuffix) {
    auto agent = fromJava(e, userAgentSuffix);
    onPage(token, [=](wv::WvPage& page) { page.configure(flags, static_cast<uint32_t>(background), agent); });
}

// --- The document script, script calls, DevTools (Task 8) ---

WV_JNI(void, setScript)(JNIEnv* e, jclass, jlong token, jint epoch, jstring secret, jstring script) {
    auto s = fromJava(e, secret);
    auto text = fromJava(e, script);
    onPage(token, [=](wv::WvPage& page) { page.setScript(epoch, s, text); });
}
WV_JNI(void, navigate)(JNIEnv* e, jclass, jlong token, jint epoch, jstring url, jstring headers) {
    auto u = fromJava(e, url);
    auto h = fromJava(e, headers);
    onPage(token, [=](wv::WvPage& page) { page.navigate(epoch, u, h); });
}
WV_JNI(void, go)(JNIEnv*, jclass, jlong token, jint epoch, jint what) {
    onPage(token, [=](wv::WvPage& page) { page.go(epoch, what); });
}
WV_JNI(void, execute)(JNIEnv* e, jclass, jlong token, jlong request, jstring script) {
    auto s = fromJava(e, script);
    onPage(token, [=](wv::WvPage& page) { page.execute(request, s); });
}
WV_JNI(void, devtools)(JNIEnv* e, jclass, jlong token, jlong request, jstring method, jstring params) {
    auto m = fromJava(e, method);
    auto p = fromJava(e, params);
    onPage(token, [=](wv::WvPage& page) { page.devtools(request, m, p); });
}
WV_JNI(void, subscribe)(JNIEnv* e, jclass, jlong token, jstring event) {
    auto name = fromJava(e, event);
    onPage(token, [=](wv::WvPage& page) { page.subscribe(name); });
}
WV_JNI(void, openDevTools)(JNIEnv*, jclass, jlong token) { onPage(token, [](wv::WvPage& page) { page.openDevTools(); }); }
WV_JNI(void, clearData)(JNIEnv*, jclass, jlong token, jlong request) {
    onPage(token, [=](wv::WvPage& page) { page.clearData(request); });
}

// --- Local content (Task 9) ---

/** [host] is the lease's local origin's host; [html] its page for Html content, null for a bundle; [floor] the floor's page. */
WV_JNI(void, serve)(JNIEnv* e, jclass, jlong token, jstring host, jbyteArray html, jstring floor) {
    auto h = fromJava(e, host);
    std::optional<std::vector<uint8_t>> bytes;
    if (html) bytes = bytesOf(e, html);
    auto f = fromJava(e, floor);
    onPage(token, [=](wv::WvPage& page) { page.serve(h, bytes, f); });
}
WV_JNI(void, respond)(JNIEnv* e, jclass, jlong token, jlong request, jint status, jstring headers, jbyteArray body) {
    auto h = fromJava(e, headers);
    auto b = bytesOf(e, body);
    onPage(token, [=](wv::WvPage& page) { page.respond(request, status, h, b); });
}

// --- Frames and placement (Task 10): pullFrame and frameSeq run on the EDT, under the page's capture lock ---

WV_JNI(void, place)(JNIEnv*, jclass, jlong token, jlong frame, jlong canvas, jint x, jint y, jint width, jint height, jdouble scale) {
    onPage(token, [=](wv::WvPage& page) {
        page.place(reinterpret_cast<HWND>(frame), reinterpret_cast<HWND>(canvas), x, y, width, height, scale);
    });
}
WV_JNI(void, unplace)(JNIEnv*, jclass, jlong token) { onPage(token, [](wv::WvPage& page) { page.unplace(); }); }
WV_JNI(void, parentMoved)(JNIEnv*, jclass, jlong token) { onPage(token, [](wv::WvPage& page) { page.parentMoved(); }); }
WV_JNI(void, setVisible)(JNIEnv*, jclass, jlong token, jboolean visible) {
    onPage(token, [=](wv::WvPage& page) { page.setVisible(visible == JNI_TRUE); });
}
WV_JNI(void, setMemoryTarget)(JNIEnv*, jclass, jlong token, jboolean low) {
    onPage(token, [=](wv::WvPage& page) { page.setMemoryTarget(low == JNI_TRUE); });
}
WV_JNI(jint, pullFrame)(JNIEnv* e, jclass, jlong token, jlong dst, jint rowBytes, jint width, jint height, jboolean full, jlongArray out) {
    int64_t values[10] = {};
    auto page = wv::Pages::find(token);
    const int result = page ? page->pull(reinterpret_cast<uint8_t*>(dst), rowBytes, width, height, full == JNI_TRUE, values) : 0;
    if (out) e->SetLongArrayRegion(out, 0, std::min<jsize>(10, e->GetArrayLength(out)), reinterpret_cast<const jlong*>(values));
    return result;
}
WV_JNI(jlong, frameSeq)(JNIEnv*, jclass, jlong token) {
    auto page = wv::Pages::find(token);
    return page ? page->frameSeq() : -1;
}

// --- Input and the page's questions (Task 11) ---

WV_JNI(void, mouse)(JNIEnv*, jclass, jlong token, jint kind, jint keys, jint data, jint x, jint y) {
    onPage(token, [=](wv::WvPage& page) { page.mouse(kind, keys, data, x, y); });
}
WV_JNI(void, moveFocus)(JNIEnv*, jclass, jlong token, jint reason) {
    onPage(token, [=](wv::WvPage& page) { page.moveFocus(reason); });
}
WV_JNI(void, answer)(JNIEnv* e, jclass, jlong token, jlong question, jint verdict, jstring text) {
    auto t = fromJava(e, text);
    onPage(token, [=](wv::WvPage& page) { page.answer(question, verdict, t); });
}
