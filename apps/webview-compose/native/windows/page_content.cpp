#include "page.h"
#include "environment.h"
using Microsoft::WRL::Callback;

namespace wv {
namespace {
const wchar_t* reasonOf(int status) {
    switch (status) {
        case 200: return L"OK";
        case 404: return L"Not Found";
        case 405: return L"Method Not Allowed";
        default: return L"";
    }
}
std::vector<uint8_t> utf8(std::wstring const& text) {
    const int n = WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    std::vector<uint8_t> out(static_cast<size_t>(std::max(n, 0)));
    if (n > 0) WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), reinterpret_cast<char*>(out.data()), n, nullptr, nullptr);
    return out;
}
constexpr wchar_t kFloorHost[] = L"reset.floor.invalid";   // two labels: no WebOrigin can be it (D7)
}  // namespace

void WvPage::registerContentEvents() {
    // Every https request to a .invalid host, from documents and from workers (D7).
    webview_->AddWebResourceRequestedFilterWithRequestSourceKinds(L"https://*.invalid/*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL,
                                                                  COREWEBVIEW2_WEB_RESOURCE_REQUEST_SOURCE_KINDS_ALL);
    EventRegistrationToken t{};
    webview_->add_WebResourceRequested(WV_HANDLER(ICoreWebView2WebResourceRequestedEventHandler, ICoreWebView2*,
        ICoreWebView2WebResourceRequestedEventArgs*, { self->onResource(args); }), &t);
}

void WvPage::serve(std::wstring host, std::optional<std::vector<uint8_t>> html, std::wstring floorHtml) {
    servedHost_ = std::move(host);
    servedHtml_ = std::move(html);
    if (!floorHtml.empty()) floorHtml_ = std::move(floorHtml);
}

/**
 * The floor and an Html content's root are answered here, on this thread, with no hop to the EDT (the main use case's
 * critical path); a bundle's files wait for the engine's provider (kResource); any other .invalid host is left alone and
 * fails as an unknown host: a missed interception fails closed.
 */
void WvPage::onResource(ICoreWebView2WebResourceRequestedEventArgs* args) {
    winrt::com_ptr<ICoreWebView2WebResourceRequest> request;
    if (FAILED(args->get_Request(request.put()))) return;
    CoString uri, method;
    request->get_Uri(uri.put());
    request->get_Method(method.put());
    const std::wstring host = httpsHost(uri.str());
    const bool read = method.p && (_wcsicmp(method.p, L"GET") == 0 || _wcsicmp(method.p, L"HEAD") == 0);
    if (host == kFloorHost) {
        respondNow(args, read ? 200 : 405, L"Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store", read ? utf8(floorHtml_) : std::vector<uint8_t>{});
        return;
    }
    if (host.empty() || host != servedHost_) return;
    if (servedHtml_) {
        if (!read) respondNow(args, 405, L"Content-Type: text/plain", {});
        else if (pathOf(uri.str()) == L"/") respondNow(args, 200, L"Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store", *servedHtml_);
        else respondNow(args, 404, L"Content-Type: text/plain", {});   // Html inlines its assets
        return;
    }
    winrt::com_ptr<ICoreWebView2Deferral> deferral;
    if (FAILED(args->GetDeferral(deferral.put()))) return;
    winrt::com_ptr<ICoreWebView2WebResourceRequestedEventArgs> held;
    held.copy_from(args);
    const int64_t id = resources_.open(Resource{held, deferral});
    // A document's requests are its epoch's; the main frame's next document's own request, its navigation's.
    COREWEBVIEW2_WEB_RESOURCE_CONTEXT context = COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL;
    args->get_ResourceContext(&context);
    const int epoch = pendingNavigation_.epochOf(uri.str(), context == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT, documentEpoch_);
    emit(token_, kResource, epoch, id, 0, uri.str(), method.str());
}

void WvPage::respondNow(ICoreWebView2WebResourceRequestedEventArgs* args, int status, std::wstring const& headers,
                        std::vector<uint8_t> const& body) {
    auto* env = env_.get();
    if (!env) return;   // released: the request fails with the page
    winrt::com_ptr<IStream> stream;
    stream.attach(SHCreateMemStream(body.empty() ? nullptr : body.data(), static_cast<UINT>(body.size())));
    winrt::com_ptr<ICoreWebView2WebResourceResponse> response;
    if (SUCCEEDED(env->CreateWebResourceResponse(stream.get(), status, reasonOf(status), headers.c_str(), response.put()))) {
        args->put_Response(response.get());
    }
}

/** The provider's answer to a deferred request: status 0 sends no response, so the request fails as an unknown host's. */
void WvPage::respond(int64_t request, int status, std::wstring headers, std::vector<uint8_t> body) {
    resources_.answer(request, [&](Resource& r) {
        if (status > 0) respondNow(r.args.get(), status, headers, body);
        r.deferral->Complete();
    });
}
}  // namespace wv
