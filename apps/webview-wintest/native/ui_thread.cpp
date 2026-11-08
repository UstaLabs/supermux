#include "ui_thread.h"

namespace wv2 {

bool UiThread::start() {
    if (queue_) return true;
    DispatcherQueueOptions options{sizeof(DispatcherQueueOptions), DQTYPE_THREAD_DEDICATED, DQTAT_COM_STA};
    const HRESULT hr = CreateDispatcherQueueController(
        options, reinterpret_cast<ABI::Windows::System::IDispatcherQueueController**>(winrt::put_abi(controller_)));
    if (FAILED(hr)) {
        logLine(L"CreateDispatcherQueueController failed " + hex(hr));
        return false;
    }
    queue_ = controller_.DispatcherQueue();
    HANDLE ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    const bool queued = queue_.TryEnqueue([this, ready] {
        threadId_ = GetCurrentThreadId();
        // The display stays on while the test runs: a blanked display stops DWM composing, and capture with it.
        SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED);
        SetEvent(ready);
    });
    if (queued) WaitForSingleObject(ready, 5000);
    CloseHandle(ready);
    return threadId_ != 0;
}

bool UiThread::post(std::function<void()> work) {
    if (!queue_) return false;
    return queue_.TryEnqueue([work = std::move(work)] {
        try {
            work();
        } catch (winrt::hresult_error const& e) {
            logLine(L"ui task failed: " + std::wstring(e.message().c_str()) + L" " + hex(e.code()));
        } catch (std::exception const& e) {
            const char* what = e.what();
            logLine(L"ui task failed: " + std::wstring(what, what + std::strlen(what)));
        } catch (...) {
            logLine(L"ui task failed: unknown exception");
        }
    });
}

void UiThread::stop() {
    if (!controller_) return;
    controller_.ShutdownQueueAsync();  // not waited for: this runs at exit, often on the JVM's UI thread
    queue_ = nullptr;
    controller_ = nullptr;
    threadId_ = 0;
}

}  // namespace wv2
