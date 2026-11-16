// Downloads (#157): WebView2 saves each with its page's session where the app said (page_questions.cpp), and this file
// reports it, by the app's id, on the process (page 0). Pause, resume and cancel are WebView2's own. UI thread only.
#include "wv2.h"
using Microsoft::WRL::Callback;

namespace wv {
namespace {

constexpr int kStateDone = 0, kStatePaused = 1, kStateFailed = 2, kStateCancelled = 3;

struct Download {
    winrt::com_ptr<ICoreWebView2DownloadOperation> operation;
    EventRegistrationToken bytes{}, state{};
    INT64 reported = -1;
};

std::map<int64_t, Download>& downloads() {
    static auto* map = new std::map<int64_t, Download>();
    return *map;
}

void forget(int64_t id) {
    auto it = downloads().find(id);
    if (it == downloads().end()) return;
    it->second.operation->remove_BytesReceivedChanged(it->second.bytes);
    it->second.operation->remove_StateChanged(it->second.state);
    downloads().erase(it);
}

std::wstring reasonOf(COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON reason) {
    switch (reason) {
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_NO_SPACE: return L"Not enough disk space";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_ACCESS_DENIED: return L"The file couldn't be written";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_FAILED: return L"The file couldn't be saved";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_NAME_TOO_LONG: return L"The file's name is too long";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_TOO_LARGE: return L"The file is too large";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_MALICIOUS: return L"Windows found a threat in it";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_BLOCKED_BY_POLICY: return L"A policy on this PC blocks it";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_SECURITY_CHECK_FAILED: return L"Windows couldn't check it";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_TRANSIENT_ERROR: return L"The file was busy";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_FAILED:
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_TIMEOUT:
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_DISCONNECTED:
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_SERVER_DOWN: return L"The connection was lost";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_UNAUTHORIZED:
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_FORBIDDEN: return L"The server refused it";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_BAD_CONTENT: return L"The server no longer has it";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_CERTIFICATE_PROBLEM: return L"The server's certificate isn't trusted";
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_FAILED:
    case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_NO_RANGE: return L"The server failed";
    // Any other: its number, so a report says which.
    default: return L"Download failed (WebView2 reason " + std::to_wstring(static_cast<int>(reason)) + L")";
    }
}

}  // namespace

void adoptDownload(int64_t id, winrt::com_ptr<ICoreWebView2DownloadOperation> const& operation) {
    Download& d = downloads()[id];
    d.operation = operation;
    operation->add_BytesReceivedChanged(Callback<ICoreWebView2BytesReceivedChangedEventHandler>(
        [id](ICoreWebView2DownloadOperation* sender, IUnknown*) -> HRESULT {
            auto it = downloads().find(id);
            if (it == downloads().end()) return S_OK;
            INT64 received = 0, total = -1;
            sender->get_BytesReceived(&received);
            sender->get_TotalBytesToReceive(&total);
            // Every 64 KiB at most: WebView2 reports per network read.
            if (it->second.reported >= 0 && received - it->second.reported < 64 * 1024 && (total <= 0 || received < total)) return S_OK;
            it->second.reported = received;
            emit(0, kDownloadProgress, 0, id, received, std::to_wstring(total));
            return S_OK;
        }).Get(), &d.bytes);
    operation->add_StateChanged(Callback<ICoreWebView2StateChangedEventHandler>(
        [id](ICoreWebView2DownloadOperation* sender, IUnknown*) -> HRESULT {
            COREWEBVIEW2_DOWNLOAD_STATE state = COREWEBVIEW2_DOWNLOAD_STATE_IN_PROGRESS;
            INT64 received = 0;
            sender->get_State(&state);
            sender->get_BytesReceived(&received);
            if (state == COREWEBVIEW2_DOWNLOAD_STATE_COMPLETED) {
                emit(0, kDownloadState, 0, id, kStateDone, {}, std::to_wstring(received));
                forget(id);
            } else if (state == COREWEBVIEW2_DOWNLOAD_STATE_INTERRUPTED) {
                COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON reason = COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NONE;
                sender->get_InterruptReason(&reason);
                if (reason == COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_PAUSED) {
                    emit(0, kDownloadState, 0, id, kStatePaused, {}, std::to_wstring(received));
                } else if (reason == COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_CANCELED) {
                    emit(0, kDownloadState, 0, id, kStateCancelled, {}, std::to_wstring(received));
                    forget(id);
                } else {
                    emit(0, kDownloadState, 0, id, kStateFailed, reasonOf(reason), std::to_wstring(received));
                }
            } else {
                downloads()[id].reported = -1;   // running again: the next bytes are reported at once
            }
            return S_OK;
        }).Get(), &d.state);
}

void pauseDownload(int64_t id) {
    auto it = downloads().find(id);
    if (it != downloads().end()) it->second.operation->Pause();
}

void resumeDownload(int64_t id) {
    auto it = downloads().find(id);
    if (it == downloads().end()) return;
    BOOL can = FALSE;
    it->second.operation->get_CanResume(&can);
    if (can) {
        it->second.operation->Resume();
    } else {
        emit(0, kDownloadState, 0, id, kStateFailed, L"The download can't be resumed", L"0");
    }
}

void cancelDownload(int64_t id) {
    auto it = downloads().find(id);
    if (it != downloads().end()) it->second.operation->Cancel();
}

}  // namespace wv
