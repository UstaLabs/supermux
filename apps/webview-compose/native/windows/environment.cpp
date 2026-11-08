#include "environment.h"
using Microsoft::WRL::Callback;

namespace wv {

// Never destroyed: WebView2's objects must not be released by a static destructor at exit, the UI thread gone.
Environment& Environment::get() {
    static Environment* instance = new Environment();
    return *instance;
}

void Environment::open(int64_t request, std::wstring folder, std::wstring arguments) {
    if (env10_) {
        if (_wcsicmp(folder.c_str(), folder_.c_str()) == 0) emit(0, kEnvironment, 0, request, S_OK, version_);
        else emit(0, kEnvironment, 0, request, E_INVALIDARG, L"one environment per process: " + folder_);
        return;
    }
    if (opening_) {
        if (_wcsicmp(folder.c_str(), folder_.c_str()) == 0) waiting_.push_back(request);
        else emit(0, kEnvironment, 0, request, E_INVALIDARG, L"one environment per process: " + folder_);
        return;
    }
    waiting_.push_back(request);
    opening_ = true;
    folder_ = folder;
    auto options = Microsoft::WRL::Make<CoreWebView2EnvironmentOptions>();
    options->put_AdditionalBrowserArguments(arguments.c_str());
    options->put_AllowSingleSignOnUsingOSPrimaryAccount(FALSE);
    // Shared on purpose (D8): another process of the app joins this browser process rather than being refused.
    if (auto options2 = Microsoft::WRL::ComPtr<ICoreWebView2EnvironmentOptions2>(); SUCCEEDED(options.As(&options2))) {
        options2->put_ExclusiveUserDataFolderAccess(FALSE);
    }
    const HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
        nullptr, folder.c_str(), options.Get(),
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [this](HRESULT result, ICoreWebView2Environment* created) -> HRESULT {
                if (FAILED(result) || !created) {
                    finish(FAILED(result) ? result : E_POINTER, L"");
                    return S_OK;
                }
                winrt::com_ptr<ICoreWebView2Environment> base;
                base.copy_from(created);
                env10_ = base.try_as<ICoreWebView2Environment10>();
                env3_ = base.try_as<ICoreWebView2Environment3>();
                if (!env10_ || !env3_) {
                    env10_ = nullptr;
                    env3_ = nullptr;
                    finish(E_NOINTERFACE, L"");  // a runtime older than the engine needs (D20)
                    return S_OK;
                }
                identity_ = base.as<IUnknown>();
                CoString version;
                base->get_BrowserVersionString(version.put());
                if (auto env5 = base.try_as<ICoreWebView2Environment5>()) {
                    EventRegistrationToken token{};
                    env5->add_BrowserProcessExited(
                        Callback<ICoreWebView2BrowserProcessExitedEventHandler>(
                            [this](ICoreWebView2Environment* sender, ICoreWebView2BrowserProcessExitedEventArgs*) -> HRESULT {
                                // Only the current environment's: an older one's late news is no one's.
                                winrt::com_ptr<IUnknown> who;
                                if (sender) sender->QueryInterface(IID_PPV_ARGS(who.put()));
                                if (!identity_ || who.get() != identity_.get()) return S_OK;
                                // The environment stays: WebView2 binds a new browser process to it for the next page (the
                                // SDK's BrowserProcessExited). Made anew here, it was a second environment on the same
                                // folder, while a page made in the exit window had WebView2 start a browser for the first:
                                // the second's pages waited seconds for that browser to go.
                                emit(0, kBrowserExited, 0, 0, 0);
                                return S_OK;
                            }).Get(),
                        &token);
                }
                finish(S_OK, version.str());
                return S_OK;
            }).Get());
    if (FAILED(hr)) finish(hr, L"");
}

void Environment::forget(ICoreWebView2Environment10* env) {
    if (!env || env != env10_.get()) return;
    env10_ = nullptr;
    env3_ = nullptr;
    identity_ = nullptr;
}

void Environment::finish(HRESULT hr, std::wstring const& version) {
    opening_ = false;
    version_ = version;
    auto waiting = std::move(waiting_);
    waiting_.clear();
    for (const int64_t request : waiting) emit(0, kEnvironment, 0, request, hr, version);
}

}  // namespace wv
