#include "page.h"
using Microsoft::WRL::Callback;

namespace wv {
namespace {
constexpr int kQuestionDialog = 0, kQuestionPermission = 1, kQuestionBeforeUnload = 2;

/** The context menu's items a page keeps (D17): editing, copying links and images; Inspect only while inspectable. */
bool keepMenuItem(std::wstring const& name, bool inspectable) {
    static const wchar_t* const kKept[] = {L"undo", L"redo", L"cut", L"copy", L"paste", L"pasteAndMatchStyle", L"selectAll",
                                           L"emoji", L"copyLinkLocation", L"copyImage", L"copyImageLocation"};
    if (name == L"inspectElement") return inspectable;
    for (auto const* kept : kKept) if (name == kept) return true;
    return false;
}
}  // namespace

void WvPage::registerQuestionEvents() {
    EventRegistrationToken t{};
    webview_->add_ScriptDialogOpening(WV_HANDLER(ICoreWebView2ScriptDialogOpeningEventHandler, ICoreWebView2*,
        ICoreWebView2ScriptDialogOpeningEventArgs*, {
            COREWEBVIEW2_SCRIPT_DIALOG_KIND kind = COREWEBVIEW2_SCRIPT_DIALOG_KIND_ALERT;
            CoString uri, message, text;
            args->get_Kind(&kind);
            args->get_Uri(uri.put());
            args->get_Message(message.put());
            args->get_DefaultText(text.put());
            winrt::com_ptr<ICoreWebView2Deferral> deferral;
            if (FAILED(args->GetDeferral(deferral.put()))) return S_OK;
            winrt::com_ptr<IUnknown> held;
            held.copy_from(args);
            const int question = kind == COREWEBVIEW2_SCRIPT_DIALOG_KIND_BEFOREUNLOAD ? kQuestionBeforeUnload : kQuestionDialog;
            const int64_t id = self->questions_.open(Question{question, held, deferral});
            emit(self->token_, kDialog, self->documentEpoch_, id, static_cast<int64_t>(kind), message.str(), text.str(), uri.str());
        }), &t);
    webview_->add_PermissionRequested(WV_HANDLER(ICoreWebView2PermissionRequestedEventHandler, ICoreWebView2*,
        ICoreWebView2PermissionRequestedEventArgs*, {
            COREWEBVIEW2_PERMISSION_KIND kind = COREWEBVIEW2_PERMISSION_KIND_UNKNOWN_PERMISSION;
            BOOL user = FALSE;
            CoString uri;
            args->get_PermissionKind(&kind);
            args->get_IsUserInitiated(&user);
            args->get_Uri(uri.put());
            winrt::com_ptr<ICoreWebView2PermissionRequestedEventArgs> a;
            a.copy_from(args);
            if (auto a3 = a.try_as<ICoreWebView2PermissionRequestedEventArgs3>()) a3->put_SavesInProfile(FALSE);   // V7
            // Autoplay follows the engine's construction-time policy (EngineConfig.mediaPlaybackRequiresGesture): no question.
            if (kind == COREWEBVIEW2_PERMISSION_KIND_AUTOPLAY) {
                args->put_State((self->flags_ & kConfAutoplay) ? COREWEBVIEW2_PERMISSION_STATE_ALLOW : COREWEBVIEW2_PERMISSION_STATE_DENY);
                return S_OK;
            }
            winrt::com_ptr<ICoreWebView2Deferral> deferral;
            if (FAILED(args->GetDeferral(deferral.put()))) return S_OK;
            winrt::com_ptr<IUnknown> held;
            held.copy_from(args);
            const int64_t id = self->questions_.open(Question{kQuestionPermission, held, deferral});
            emit(self->token_, kPermission, self->documentEpoch_, id, static_cast<int64_t>(kind) | (user ? 0x100 : 0), uri.str());
        }), &t);
    webview_->add_DownloadStarting(WV_HANDLER(ICoreWebView2DownloadStartingEventHandler, ICoreWebView2*,
        ICoreWebView2DownloadStartingEventArgs*, {
            args->put_Cancel(TRUE);    // the engine saves nothing (D18)
            args->put_Handled(TRUE);   // and shows no download UI
            winrt::com_ptr<ICoreWebView2DownloadOperation> operation;
            CoString uri, mime, path;
            if (SUCCEEDED(args->get_DownloadOperation(operation.put())) && operation) {
                operation->get_Uri(uri.put());
                operation->get_MimeType(mime.put());
            }
            args->get_ResultFilePath(path.put());
            const std::wstring file = path.str();
            emit(self->token_, kDownload, self->documentEpoch_, 0, 0, uri.str(), mime.str(), file.substr(file.find_last_of(L"\\/") + 1));
        }), &t);
    webview_->add_NewWindowRequested(WV_HANDLER(ICoreWebView2NewWindowRequestedEventHandler, ICoreWebView2*,
        ICoreWebView2NewWindowRequestedEventArgs*, {
            args->put_Handled(TRUE);   // no window, ever: window.open gets a WindowProxy that loads nothing (L22)
            CoString uri;
            BOOL user = FALSE;
            args->get_Uri(uri.put());
            args->get_IsUserInitiated(&user);
            emit(self->token_, kNewWindow, self->documentEpoch_, 0, user ? 1 : 0, uri.str());
        }), &t);
    webview_->add_ServerCertificateErrorDetected(WV_HANDLER(ICoreWebView2ServerCertificateErrorDetectedEventHandler, ICoreWebView2*,
        ICoreWebView2ServerCertificateErrorDetectedEventArgs*, {
            args->put_Action(COREWEBVIEW2_SERVER_CERTIFICATE_ERROR_ACTION_CANCEL);   // never bypassed
        }), &t);
    webview_->add_ClientCertificateRequested(WV_HANDLER(ICoreWebView2ClientCertificateRequestedEventHandler, ICoreWebView2*,
        ICoreWebView2ClientCertificateRequestedEventArgs*, {
            args->put_Handled(TRUE);   // no certificate is offered: no OS dialog
        }), &t);
    webview_->add_BasicAuthenticationRequested(WV_HANDLER(ICoreWebView2BasicAuthenticationRequestedEventHandler, ICoreWebView2*,
        ICoreWebView2BasicAuthenticationRequestedEventArgs*, { args->put_Cancel(TRUE); }), &t);
    webview_->add_LaunchingExternalUriScheme(WV_HANDLER(ICoreWebView2LaunchingExternalUriSchemeEventHandler, ICoreWebView2*,
        ICoreWebView2LaunchingExternalUriSchemeEventArgs*, { args->put_Cancel(TRUE); }), &t);   // the runtime opens URLs (D9)
    webview_->add_ContextMenuRequested(WV_HANDLER(ICoreWebView2ContextMenuRequestedEventHandler, ICoreWebView2*,
        ICoreWebView2ContextMenuRequestedEventArgs*, { self->filterContextMenu(args); }), &t);
    if (auto w27 = webview_.try_as<ICoreWebView2_27>()) {
        w27->add_ScreenCaptureStarting(WV_HANDLER(ICoreWebView2ScreenCaptureStartingEventHandler, ICoreWebView2*,
            ICoreWebView2ScreenCaptureStartingEventArgs*, { args->put_Cancel(TRUE); }), &t);
    }
    if (auto w25 = webview_.try_as<ICoreWebView2_25>()) {
        w25->add_SaveAsUIShowing(WV_HANDLER(ICoreWebView2SaveAsUIShowingEventHandler, ICoreWebView2*,
            ICoreWebView2SaveAsUIShowingEventArgs*, { args->put_Cancel(TRUE); }), &t);
    }
    if (auto w24 = webview_.try_as<ICoreWebView2_24>()) {
        w24->add_NotificationReceived(WV_HANDLER(ICoreWebView2NotificationReceivedEventHandler, ICoreWebView2*,
            ICoreWebView2NotificationReceivedEventArgs*, { args->put_Handled(TRUE); }), &t);
    }
}

void WvPage::filterContextMenu(ICoreWebView2ContextMenuRequestedEventArgs* args) {
    winrt::com_ptr<ICoreWebView2ContextMenuItemCollection> items;
    if (FAILED(args->get_MenuItems(items.put()))) return;
    UINT32 count = 0;
    items->get_Count(&count);
    for (UINT32 i = count; i-- > 0;) {
        winrt::com_ptr<ICoreWebView2ContextMenuItem> item;
        if (FAILED(items->GetValueAtIndex(i, item.put()))) continue;
        COREWEBVIEW2_CONTEXT_MENU_ITEM_KIND kind = COREWEBVIEW2_CONTEXT_MENU_ITEM_KIND_COMMAND;
        item->get_Kind(&kind);
        if (kind == COREWEBVIEW2_CONTEXT_MENU_ITEM_KIND_SEPARATOR) continue;
        CoString name;
        item->get_Name(name.put());
        if (!keepMenuItem(name.str(), (flags_ & kConfInspectable) != 0)) items->RemoveValueAtIndex(i);
    }
}

void WvPage::answer(int64_t question, int verdict, std::wstring text) {
    questions_.answer(question, [&](Question& q) {
        try {
            if (q.kind == kQuestionPermission) {
                q.args.as<ICoreWebView2PermissionRequestedEventArgs>()->put_State(verdict ? COREWEBVIEW2_PERMISSION_STATE_ALLOW : COREWEBVIEW2_PERMISSION_STATE_DENY);
            } else if (verdict) {
                auto dialog = q.args.as<ICoreWebView2ScriptDialogOpeningEventArgs>();
                dialog->put_ResultText(text.c_str());
                dialog->Accept();
            }
        } catch (winrt::hresult_error const&) {
            logLine(L"a question's answer failed: it gets its default");
        }
        q.deferral->Complete();
    });
}

/** The safe defaults (WebEngine.client's KDoc): dialogs dismissed, a beforeunload leaves the page, permissions denied. */
void WvPage::answerQuestionsByDefault() {
    questions_.answerAll([](Question& q) {
        try {
            if (q.kind == kQuestionPermission) q.args.as<ICoreWebView2PermissionRequestedEventArgs>()->put_State(COREWEBVIEW2_PERMISSION_STATE_DENY);
            else if (q.kind == kQuestionBeforeUnload) q.args.as<ICoreWebView2ScriptDialogOpeningEventArgs>()->Accept();
        } catch (winrt::hresult_error const&) {
        }
        q.deferral->Complete();   // every one, even if setting its answer failed: answerAll has taken them all out
    });
}

}  // namespace wv
