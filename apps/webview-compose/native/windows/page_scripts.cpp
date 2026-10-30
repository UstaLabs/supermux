#include "page.h"
#include "environment.h"
using Microsoft::WRL::Callback;

namespace wv {

void WvPage::registerScriptEvents() {
    EventRegistrationToken t{};
    webview_->add_WebMessageReceived(WV_HANDLER(ICoreWebView2WebMessageReceivedEventHandler, ICoreWebView2*,
        ICoreWebView2WebMessageReceivedEventArgs*, {
            CoString body;
            // The document script posts strings; anything else is a page's own, and never ours.
            if (FAILED(args->TryGetWebMessageAsString(body.put())) || !body.p) return S_OK;
            std::wstring_view rest;
            // No secret, no upcall: a page flooding chrome.webview.postMessage never reaches the JVM (D5, L19).
            if (!self->signature_.accept(body.p, rest)) return S_OK;
            emit(self->token_, kMessage, self->signature_.epoch(), 0, 0, rest);
        }), &t);
}

/**
 * The epoch's document script, in place of the last (D5). A load asked for meanwhile waits for it (runOrQueue). Swapped
 * twice before the first add completed, the older add's script is removed as soon as it is known.
 */
void WvPage::setScript(int epoch, std::wstring secret, std::wstring script) {
    if (released_ || !webview_) return;
    signature_.set(std::move(secret), epoch);   // from now on only this epoch's documents are heard
    if (!scriptId_.empty()) {
        webview_->RemoveScriptToExecuteOnDocumentCreated(scriptId_.c_str());
        scriptId_.clear();
    }
    scriptPending_ = true;
    const int64_t generation = ++scriptGeneration_;
    std::weak_ptr<WvPage> weak = weak_from_this();
    const HRESULT hr = webview_->AddScriptToExecuteOnDocumentCreated(
        script.c_str(),
        Callback<ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler>(
            [weak, generation, epoch](HRESULT result, LPCWSTR id) -> HRESULT {
                auto self = weak.lock();
                if (!self || self->released_) return S_OK;
                if (generation != self->scriptGeneration_) {
                    // Superseded before it was known: the newer script is the one documents run.
                    if (SUCCEEDED(result) && id) self->webview_->RemoveScriptToExecuteOnDocumentCreated(id);
                    return S_OK;
                }
                if (SUCCEEDED(result) && id) self->scriptId_ = id;
                self->scriptPending_ = false;
                auto ops = std::move(self->afterScript_);
                self->afterScript_.clear();
                for (auto& op : ops) op();
                emit(self->token_, kScriptReady, epoch, 0, result);
                return S_OK;
            }).Get());
    if (FAILED(hr)) {
        scriptPending_ = false;
        auto ops = std::move(afterScript_);
        afterScript_.clear();
        for (auto& op : ops) op();
        emit(token_, kScriptReady, epoch, 0, hr);
    }
}

void WvPage::runOrQueue(std::function<void()> op) {
    if (scriptPending_) afterScript_.push_back(std::move(op));
    else op();
}

/** [epoch] becomes the page's when the navigation is sent, so what started before it keeps the earlier epoch. */
void WvPage::navigate(int epoch, std::wstring url, std::wstring headers) {
    if (released_) return;
    runOrQueue([weak = weak_from_this(), epoch, url, headers] {
        auto self = weak.lock();
        if (!self || self->released_ || !self->webview_) return;
        self->epoch_ = epoch;
        if (!headers.empty()) {
            winrt::com_ptr<ICoreWebView2WebResourceRequest> request;
            auto* env = self->env_.get();
            if (env && SUCCEEDED(env->CreateWebResourceRequest(url.c_str(), L"GET", nullptr, headers.c_str(), request.put()))) {
                self->webview_->NavigateWithWebResourceRequest(request.get());
                return;
            }
            logLine(L"CreateWebResourceRequest failed: the load goes without its headers");
        }
        self->webview_->Navigate(url.c_str());
    });
}

void WvPage::go(int epoch, int what) {
    if (released_ || !webview_) return;
    if (what == kGoStop) {   // never waits for a script
        webview_->Stop();
        return;
    }
    runOrQueue([weak = weak_from_this(), epoch, what] {
        auto self = weak.lock();
        if (!self || self->released_ || !self->webview_) return;
        if (epoch > 0) self->epoch_ = epoch;
        if (what == kGoReload) self->webview_->Reload();
        else if (what == kGoBack) self->webview_->GoBack();
        else if (what == kGoForward) self->webview_->GoForward();
    });
}

void WvPage::execute(int64_t request, std::wstring script) {
    if (released_) return;
    if (!webview_) {   // not made yet: the request fails now rather than never
        if (request != 0) emit(token_, kResult, 0, request, 0, L"null");
        return;
    }
    const HRESULT hr = webview_->ExecuteScript(
        script.c_str(), Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
                            [weak = weak_from_this(), request](HRESULT result, LPCWSTR json) -> HRESULT {
                                auto self = weak.lock();
                                if (!self || self->released_ || request == 0) return S_OK;
                                emit(self->token_, kResult, 0, request, SUCCEEDED(result) ? 1 : 0, json ? json : L"null");
                                return S_OK;
                            }).Get());
    if (FAILED(hr) && request != 0) emit(token_, kResult, 0, request, 0, L"null");
}

void WvPage::devtools(int64_t request, std::wstring method, std::wstring params) {
    if (released_) return;
    if (!webview_) {   // not made yet: the request fails now rather than never
        if (request != 0) emit(token_, kResult, 0, request, 0, L"null");
        return;
    }
    const HRESULT hr = webview_->CallDevToolsProtocolMethod(
        method.c_str(), params.c_str(),
        Callback<ICoreWebView2CallDevToolsProtocolMethodCompletedHandler>(
            [weak = weak_from_this(), request](HRESULT result, LPCWSTR json) -> HRESULT {
                auto self = weak.lock();
                if (!self || self->released_ || request == 0) return S_OK;
                emit(self->token_, kResult, 0, request, SUCCEEDED(result) ? 1 : 0, json ? json : L"null");
                return S_OK;
            }).Get());
    if (FAILED(hr) && request != 0) emit(token_, kResult, 0, request, 0, L"null");
}

/** A DevTools event this page reports from now on (Page.fileChooserOpened, Runtime.consoleAPICalled…), once per name. */
void WvPage::subscribe(std::wstring event) {
    if (released_ || !webview_ || devtoolsEvents_.count(event)) return;
    winrt::com_ptr<ICoreWebView2DevToolsProtocolEventReceiver> receiver;
    if (FAILED(webview_->GetDevToolsProtocolEventReceiver(event.c_str(), receiver.put()))) return;
    EventRegistrationToken token{};
    receiver->add_DevToolsProtocolEventReceived(
        Callback<ICoreWebView2DevToolsProtocolEventReceivedEventHandler>(
            [weak = weak_from_this(), event](ICoreWebView2*, ICoreWebView2DevToolsProtocolEventReceivedEventArgs* args) -> HRESULT {
                auto self = weak.lock();
                if (!self || self->released_) return S_OK;
                CoString json;
                args->get_ParameterObjectAsJson(json.put());
                emit(self->token_, kDevToolsEvent, self->documentEpoch_, 0, 0, event, json.str());
                return S_OK;
            }).Get(),
        &token);
    devtoolsEvents_[event] = token;
}

void WvPage::openDevTools() {
    if (!released_ && webview_ && (flags_ & kConfInspectable)) webview_->OpenDevToolsWindow();
}

/** Wipes the page's own profile of every kind of data (D8: an Ephemeral engine's profile is its alone). */
void WvPage::clearData(int64_t request) {
    if (released_) return;
    if (!profile_) {   // not made yet: the wipe fails now rather than never
        emit(token_, kResult, 0, request, 0, L"null");
        return;
    }
    const HRESULT hr = profile_->ClearBrowsingDataAll(
        Callback<ICoreWebView2ClearBrowsingDataCompletedHandler>([weak = weak_from_this(), request](HRESULT result) -> HRESULT {
            auto self = weak.lock();
            if (!self || self->released_) return S_OK;
            emit(self->token_, kResult, 0, request, SUCCEEDED(result) ? 1 : 0, L"null");
            return S_OK;
        }).Get());
    if (FAILED(hr)) emit(token_, kResult, 0, request, 0, L"null");
}

}  // namespace wv
