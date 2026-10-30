#pragma once
// wv2capture: the native half of the WebView2 capture + focus test (apps/webview-wintest).
// One WebView2 in visual-hosting mode; its root visual is captured off screen with Windows.Graphics.Capture, and changed
// rows are copied into a CPU buffer the JVM pulls into a Skia bitmap. Built with MSVC on the Windows 11 x64 VM.
#include <windows.h>
#include <unknwn.h>
#include <d3d11.h>
#include <dxgi.h>
#include <DispatcherQueue.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
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
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

namespace wv2 {

// Event kinds for WinNative.onEvent; keep in sync with WinNative.kt.
enum EventKind : int {
    kReady = 1,              // a: HRESULT; text: "runtime|adapter|parentHwnd|parentClass|rasterizationScale"
    kNavCompleted = 2,       // a: isSuccess; b: COREWEBVIEW2_WEB_ERROR_STATUS
    kFocus = 3,              // a: 1 GotFocus, 0 LostFocus
    kMoveFocusRequested = 4, // a: COREWEBVIEW2_MOVE_FOCUS_REASON (1 next, 2 previous)
    kCursor = 5,             // a: system cursor id (an IDC_* value); custom cursors go through emitCustomCursor
    kWebMessage = 6,         // text: the message as JSON
    kScriptResult = 7,       // a: request id; b: HRESULT; text: the result as JSON
    kAccelerator = 8,        // a: virtual key; b: key event kind | 0x100 ctrl | 0x200 shift | 0x400 alt | 0x800 handled
    kProcessFailed = 9,      // a: COREWEBVIEW2_PROCESS_FAILED_KIND
    kLog = 10,               // text: a diagnostic line
    kDocScriptAdded = 11,    // a: HRESULT
    kCaptureStarted = 12,    // a: width; b: height; text: "dirtyApi=0" or "dirtyApi=1"
};

// Implemented in jni_bridge.cpp. Callable from any thread.
void emit(int kind, int64_t a, int64_t b, std::wstring const& text);
void emitFrameReady();
void emitCustomCursor(int width, int height, int hotX, int hotY, std::vector<int32_t> const& argb);
void logLine(std::wstring const& line);

inline std::wstring hex(int64_t value) {
    wchar_t buffer[32];
    swprintf_s(buffer, L"0x%llx", static_cast<unsigned long long>(value));
    return buffer;
}

// System.nanoTime()'s clock on Windows: HotSpot scales QueryPerformanceCounter to nanoseconds through a double.
inline int64_t qpcFrequency() {
    static const int64_t frequency = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f.QuadPart;
    }();
    return frequency;
}
inline int64_t qpcToNanos(int64_t counter) {
    return static_cast<int64_t>(static_cast<double>(counter) / static_cast<double>(qpcFrequency()) * 1e9);
}
inline int64_t qpcNanos() {
    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);
    return qpcToNanos(counter.QuadPart);
}

// Microseconds since 1970: the clock behind the page's performance.timeOrigin + performance.now().
inline int64_t epochMicros() {
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    ULARGE_INTEGER u;
    u.LowPart = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    return static_cast<int64_t>((u.QuadPart - 116444736000000000ULL) / 10ULL);
}

}  // namespace wv2
