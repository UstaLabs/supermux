#pragma once
#include "wv2.h"

namespace wv {

/**
 * The glue's UI thread (D2): one per process, a dedicated STA with a DispatcherQueue and its message loop, as both
 * Windows.UI.Composition (a DispatcherQueue) and WebView2 (an STA with a message loop) require. The environment, every
 * controller, webview and Composition object live on it, and every WebView2 call and callback happens here. It also owns
 * the parking window: the parent of every page no surface shows.
 */
class UiThread {
public:
    static UiThread& get();
    /** Starts the thread once (any thread; later calls answer at once). False if it couldn't. */
    bool start();
    /** Runs [work] on the UI thread, later, in the order posted. False if the thread isn't running. */
    bool post(std::function<void()> work);
    /** Runs [work] on the UI thread and waits up to [timeoutMs]; false if it didn't finish (it may still run later). */
    bool runSync(std::function<void()> work, DWORD timeoutMs);
    bool isCurrent() const { return GetCurrentThreadId() == threadId_.load(); }
    /** The hidden top-level window that parents a page no surface shows (a closed window's pages must not die with it). */
    HWND parking() const { return parking_; }
    /** Runs [hook] on the UI thread at each WM_DISPLAYCHANGE the parking window gets (monitors or adapters changed, D24).
     *  Set it on the UI thread. */
    void onDisplayChange(std::function<void()> hook) { displayChange_ = std::move(hook); }
    /** Marks the calling thread as one of the glue's own (named [name]): only those may attach to the JVM. */
    static void markGlueThread(wchar_t const* name);
    static bool isGlueThread();

private:
    static LRESULT CALLBACK parkingProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam);

    std::mutex startLock_;
    winrt::Windows::System::DispatcherQueueController controller_{nullptr};
    winrt::Windows::System::DispatcherQueue queue_{nullptr};
    std::atomic<DWORD> threadId_{0};
    HWND parking_ = nullptr;
    std::function<void()> displayChange_;
};

}  // namespace wv
