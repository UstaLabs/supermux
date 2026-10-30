#pragma once
// webview_windows.dll: the Windows engine's glue (docs/superpowers/plans/2026-10-30-webview-windows-engine.md). One
// native UI thread, an STA with a DispatcherQueue, owns the WebView2 environment, every page's
// CoreWebView2CompositionController and its off-screen visual; Windows.Graphics.Capture copies each visual's damage on the
// thread pool. Grown out of apps/webview-wintest/native.
#include <windows.h>
#include <unknwn.h>
#include <d3d11_4.h>
#include <dxgi.h>
#include <shlwapi.h>
#include <DispatcherQueue.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Foundation.Metadata.h>
#include <winrt/Windows.Foundation.Numerics.h>
#include <winrt/Windows.Graphics.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <winrt/Windows.System.h>
#include <winrt/Windows.UI.Composition.h>
#include <wrl.h>
#include <WebView2.h>
#include <WebView2EnvironmentOptions.h>
#include <atomic>
#include <deque>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <optional>
#include <vector>
#include "logic.h"

/** WinGlue.ABI; Gradle's stageWindowsGlue reads it from here. Change both together. */
#define WV_ABI_VERSION 1

namespace wv {

/**
 * The upcall's event types: WinGlue.onEvent's `type`, and what its fields hold (WinEvent.kt decodes them). `page` is the
 * engine's token, 0 for the process. Unnamed fields are 0 or null.
 */
enum Ev : int {
    kCreated = 1,         // id: 1 created, 0 failed; flags: HRESULT; a: what failed
    kEnvironment = 2,     // page 0; id: the request; flags: HRESULT; a: the browser version
    kNavStarting = 3,     // epoch: the navigation's; id: NavigationId; flags: nav flags | verdict << 16; a: URL
    kCommitted = 4,       // epoch: the navigation's; id: NavigationId; flags: 1 an error page | the capture's sequence << 1; a: URL
    kSourceChanged = 5,   // epoch: the document's; flags: 1 a new document; a: URL
    kHistory = 6,         // epoch: the document's; flags: 1 can go back, 2 can go forward
    kTitle = 7,           // epoch: the document's; a: the title
    kDomLoaded = 8,       // epoch: the navigation's; id: NavigationId
    kCompleted = 9,       // epoch: the navigation's; id: NavigationId; flags: 1 success | web error << 8 | HTTP status << 16; a: URL
    kMessage = 10,        // epoch: the secret's; a: the body after the secret ("<D> <kind>[ <payload>]")
    kResult = 11,         // id: the request; flags: 1 ok; a: JSON
    kFrame = 12,          // id: the newest frame's sequence
    kFocus = 13,          // flags: 1 WebView2 got the keyboard focus, 0 lost it
    kMoveFocus = 14,      // flags: COREWEBVIEW2_MOVE_FOCUS_REASON (1 next, 2 previous)
    kCursor = 15,         // flags: the system cursor's id, 0 for a custom one; data: width, height, hotX, hotY, then ARGB (int32 LE)
    kResource = 16,       // id: the request; a: URL; b: method
    kDialog = 17,         // id: the question; flags: COREWEBVIEW2_SCRIPT_DIALOG_KIND; a: message; b: default text; c: URI
    kPermission = 18,     // id: the question; flags: COREWEBVIEW2_PERMISSION_KIND | 0x100 user initiated; a: URI
    kDownload = 19,       // a: URL; b: MIME type; c: the suggested file name
    kNewWindow = 20,      // flags: 1 user initiated; a: URL
    kProcessFailed = 21,  // flags: COREWEBVIEW2_PROCESS_FAILED_KIND
    kDevToolsEvent = 22,  // a: the event's name; b: its JSON
    kMemory = 23,         // page 0: memory is low
    kBrowserExited = 24,  // page 0: the environment's browser process is gone
    kScriptReady = 25,    // epoch: the script's; flags: HRESULT
    kLog = 26,            // a: a diagnostic line
};

/** The page's configure flags (WinGlue.configure). */
constexpr int kConfScripts = 1;      // JavaScript on
constexpr int kConfInspectable = 2;  // DevTools and the context menu's Inspect
constexpr int kConfDark = 4;         // prefers-color-scheme: dark (CDP emulation)
constexpr int kConfAutoplay = 8;     // media may play without a gesture (EngineConfig)

/** kProcessFailed's kind for the glue's own failure to draw (no Direct3D device after a loss): never WebView2's. */
constexpr int kFailedNoDevice = 100;

/** WinGlue.go's what. */
constexpr int kGoReload = 0, kGoStop = 1, kGoBack = 2, kGoForward = 3;

/** WinGlue.start's answers. */
constexpr int kStartOk = 0, kStartNoCapture = 1, kStartNoDevice = 2, kStartNoThread = 3, kStartNoUpcall = 4;

// Implemented in jni.cpp. emit, decide and offerKey run only on the UI thread (and emit on the memory watcher too).
void emit(int64_t page, int type, int epoch, int64_t id, int64_t flags, std::wstring_view a = {}, std::wstring_view b = {},
          std::wstring_view c = {}, std::vector<uint8_t> const* data = nullptr);
int decideNavigation(int64_t page, int epoch, int flags, std::wstring_view url);
bool offerKey(int64_t page, int vk, int scan, int flags);
void logLine(std::wstring const& line);

// Implemented in memory.cpp: the low-memory watcher thread (D21), started once.
void startMemoryWatch();

inline std::wstring hex(int64_t value) {
    wchar_t buffer[32];
    swprintf_s(buffer, L"0x%llx", static_cast<unsigned long long>(value));
    return buffer;
}

/** A CoTaskMem string WebView2 returned, owned and freed. */
struct CoString {
    LPWSTR p = nullptr;
    ~CoString() { if (p) CoTaskMemFree(p); }
    LPWSTR* put() { return &p; }
    std::wstring str() const { return p ? std::wstring(p) : std::wstring(); }
};

// System.nanoTime()'s clock on Windows: HotSpot scales QueryPerformanceCounter to nanoseconds through a double (W7: frame
// arrival is measured with it).
inline int64_t qpcNanos() {
    static const double frequency = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return static_cast<double>(f.QuadPart);
    }();
    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);
    return static_cast<int64_t>(static_cast<double>(counter.QuadPart) / frequency * 1e9);
}

}  // namespace wv
