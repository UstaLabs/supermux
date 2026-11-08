#pragma once
#include "common.h"

namespace wv2 {

// The test's UI thread: a dedicated STA thread that owns a DispatcherQueue and pumps messages, as both
// Windows.UI.Composition (it needs a DispatcherQueue) and WebView2 (it needs an STA with a message loop) require.
// Every WebView2 and Composition call in this DLL runs on it.
class UiThread {
public:
    bool start();
    bool post(std::function<void()> work);
    bool isCurrent() const { return GetCurrentThreadId() == threadId_; }
    DWORD threadId() const { return threadId_; }
    void stop();

private:
    winrt::Windows::System::DispatcherQueueController controller_{nullptr};
    winrt::Windows::System::DispatcherQueue queue_{nullptr};
    DWORD threadId_ = 0;
};

}  // namespace wv2
