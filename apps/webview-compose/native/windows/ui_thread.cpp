#include "ui_thread.h"
#include <cstring>
#include <exception>

namespace wv {
namespace {

HMODULE selfModule() {
    HMODULE self = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       reinterpret_cast<LPCWSTR>(&selfModule), &self);
    return self;
}

thread_local bool t_glueThread = false;

}  // namespace

UiThread& UiThread::get() {
    static UiThread instance;
    return instance;
}

bool UiThread::start() {
    std::lock_guard<std::mutex> guard(startLock_);
    if (queue_) return threadId_.load() != 0;
    DispatcherQueueOptions options{sizeof(DispatcherQueueOptions), DQTYPE_THREAD_DEDICATED, DQTAT_COM_STA};
    const HRESULT hr = CreateDispatcherQueueController(
        options, reinterpret_cast<ABI::Windows::System::IDispatcherQueueController**>(winrt::put_abi(controller_)));
    if (FAILED(hr)) {
        logLine(L"CreateDispatcherQueueController failed " + hex(hr));
        return false;
    }
    queue_ = controller_.DispatcherQueue();
    HANDLE ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    // The thread's first task: who it is, and the parking window (it must belong to this thread: WebView2's parent).
    const bool queued = queue_.TryEnqueue([this, ready] {
        threadId_ = GetCurrentThreadId();
        markGlueThread(L"webview2-ui");
        WNDCLASSEXW windowClass{sizeof(windowClass)};
        windowClass.lpfnWndProc = parkingProc;   // DefWindowProcW, plus WM_DISPLAYCHANGE for D24
        windowClass.hInstance = selfModule();
        windowClass.lpszClassName = L"wvc.Parking";
        RegisterClassExW(&windowClass);  // fails harmlessly when already registered
        parking_ = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, windowClass.lpszClassName, L"", WS_POPUP, 0, 0, 1, 1,
                                   nullptr, nullptr, windowClass.hInstance, nullptr);
        SetEvent(ready);
    });
    if (queued) WaitForSingleObject(ready, 5000);
    CloseHandle(ready);
    if (!parking_) logLine(L"the parking window could not be made " + hex(GetLastError()));
    return threadId_.load() != 0;
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

// The state outlives a timed-out wait, so a late run never touches a dead stack.
bool UiThread::runSync(std::function<void()> work, DWORD timeoutMs) {
    if (isCurrent()) {
        work();
        return true;
    }
    struct State {
        HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        ~State() { CloseHandle(done); }
    };
    auto state = std::make_shared<State>();
    if (!post([state, work = std::move(work)] {
            struct Signal {
                HANDLE event;
                ~Signal() { SetEvent(event); }   // also when work throws: the caller isn't left waiting it out
            } signal{state->done};
            work();
        })) {
        return false;
    }
    return WaitForSingleObject(state->done, timeoutMs) == WAIT_OBJECT_0;
}

// A hidden top-level window gets WM_DISPLAYCHANGE (a broadcast): monitors or adapters changed, so captures may move.
LRESULT CALLBACK UiThread::parkingProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_DISPLAYCHANGE && get().displayChange_) get().displayChange_();
    return DefWindowProcW(window, message, wParam, lParam);
}

void UiThread::markGlueThread(wchar_t const* name) {
    t_glueThread = true;
    SetThreadDescription(GetCurrentThread(), name);
}

bool UiThread::isGlueThread() { return t_glueThread; }

}  // namespace wv
