#include "page.h"
#include "environment.h"
#include "ui_thread.h"
using Microsoft::WRL::Callback;
namespace wuc = winrt::Windows::UI::Composition;

namespace wv {
namespace {

/**
 * The UI thread's one Compositor (D2): every page's visuals are a tree of their own in it, so hundreds of pages share one
 * connection to DWM. Made on first use, on the UI thread; never destroyed (no release at exit, the thread gone).
 */
wuc::Compositor sharedCompositor() {
    struct Holder {   // C++/WinRT types can't be new'd themselves; a struct holding one can
        wuc::Compositor compositor{nullptr};
    };
    static auto* holder = new Holder();
    if (!holder->compositor) holder->compositor = wuc::Compositor();
    return holder->compositor;
}

/** Closes a controller nobody will own (its page went, or setting it up failed): its browser processes end with it. */
void closeController(ICoreWebView2CompositionController* composition) {
    if (!composition) return;
    winrt::com_ptr<ICoreWebView2Controller> controller;
    if (SUCCEEDED(composition->QueryInterface(IID_PPV_ARGS(controller.put())))) controller->Close();
}

COREWEBVIEW2_COLOR colorOf(uint32_t argb) {
    return COREWEBVIEW2_COLOR{static_cast<BYTE>(argb >> 24), static_cast<BYTE>(argb >> 16), static_cast<BYTE>(argb >> 8),
                              static_cast<BYTE>(argb)};
}

}  // namespace

void WvPage::create(std::wstring profile, bool inPrivate, uint32_t background) {
    auto* env = Environment::get().env10();
    if (!env) {
        emit(token_, kCreated, 0, 0, E_UNEXPECTED, L"no environment");
        return;
    }
    env_.copy_from(env);
    winrt::com_ptr<ICoreWebView2ControllerOptions> options;
    if (FAILED(env->CreateCoreWebView2ControllerOptions(options.put()))) {
        emit(token_, kCreated, 0, 0, E_FAIL, L"CreateCoreWebView2ControllerOptions");
        return;
    }
    if (!profile.empty()) options->put_ProfileName(profile.c_str());   // empty: WebView2's own default profile
    options->put_IsInPrivateModeEnabled(inPrivate ? TRUE : FALSE);
    // The page's background from its first frame (D13): never a white frame under a dark or transparent page.
    if (auto options3 = options.try_as<ICoreWebView2ControllerOptions3>()) options3->put_DefaultBackgroundColor(colorOf(background));
    compositor_ = sharedCompositor();
    root_ = compositor_.CreateContainerVisual();
    webVisual_ = compositor_.CreateContainerVisual();
    webVisual_.RelativeSizeAdjustment({1.0f, 1.0f});
    root_.Children().InsertAtTop(webVisual_);
    std::weak_ptr<WvPage> weak = weak_from_this();
    // Created under the parking window: a surface's window becomes its parent when it shows the page (place()).
    const HRESULT hr = env->CreateCoreWebView2CompositionControllerWithOptions(
        UiThread::get().parking(), options.get(),
        Callback<ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler>(
            [weak](HRESULT result, ICoreWebView2CompositionController* composition) -> HRESULT {
                auto self = weak.lock();
                if (!self) {
                    closeController(composition);   // the page is gone: nothing may keep its browser processes alive
                    return S_OK;
                }
                if (FAILED(result) || !composition) {
                    emit(self->token_, kCreated, 0, 0, FAILED(result) ? result : E_POINTER, L"CreateCoreWebView2CompositionController");
                    return S_OK;
                }
                std::wstring failure;
                HRESULT code = S_OK;
                try {
                    self->onCreated(composition);
                } catch (winrt::hresult_error const& e) {
                    code = e.code();
                    failure = std::wstring(L"setting up the controller: ") + e.message().c_str();
                } catch (...) {
                    code = E_FAIL;
                    failure = L"setting up the controller threw";
                }
                if (!failure.empty()) {
                    closeController(composition);
                    self->composition_ = nullptr;
                    self->controller_ = nullptr;
                    self->webview_ = nullptr;
                    self->settings_ = nullptr;
                    self->profile_ = nullptr;
                    emit(self->token_, kCreated, 0, 0, code, failure);
                }
                return S_OK;
            }).Get());
    if (FAILED(hr)) emit(token_, kCreated, 0, 0, hr, L"CreateCoreWebView2CompositionControllerWithOptions");
}

/** The interfaces the engine can't work without; each later one includes the earlier ones of its line (W15). */
bool WvPage::hasRequiredInterfaces(std::wstring& missing) {
    if (!webview_) missing = L"ICoreWebView2_22";
    else if (!controller_) missing = L"ICoreWebView2Controller4";
    else if (!settings_) missing = L"ICoreWebView2Settings6";
    else if (!profile_) missing = L"ICoreWebView2Profile6";
    return missing.empty();
}

void WvPage::onCreated(ICoreWebView2CompositionController* composition) {
    if (released_) {
        closeController(composition);   // released while it was being made: nothing is kept
        return;
    }
    composition_.copy_from(composition);
    controller_ = composition_.try_as<ICoreWebView2Controller4>();
    winrt::com_ptr<ICoreWebView2> core;
    if (controller_) winrt::check_hresult(controller_->get_CoreWebView2(core.put()));
    if (core) webview_ = core.try_as<ICoreWebView2_22>();
    if (webview_) {
        winrt::com_ptr<ICoreWebView2Settings> settings;
        webview_->get_Settings(settings.put());
        if (settings) settings_ = settings.try_as<ICoreWebView2Settings6>();
        winrt::com_ptr<ICoreWebView2Profile> profile;
        webview_->get_Profile(profile.put());
        if (profile) profile_ = profile.try_as<ICoreWebView2Profile6>();
    }
    std::wstring missing;
    if (!hasRequiredInterfaces(missing)) {
        closeController(composition);
        composition_ = nullptr;
        controller_ = nullptr;
        webview_ = nullptr;
        settings_ = nullptr;
        profile_ = nullptr;
        emit(token_, kCreated, 0, 0, E_NOINTERFACE, L"the WebView2 runtime lacks " + missing);
        return;
    }
    winrt::check_hresult(composition_->put_RootVisualTarget(winrt::get_unknown(webVisual_)));
    controller_->put_BoundsMode(COREWEBVIEW2_BOUNDS_MODE_USE_RAW_PIXELS);
    controller_->put_ShouldDetectMonitorScaleChanges(FALSE);   // the surface tells the scale (Task 10)
    controller_->put_AllowExternalDrop(FALSE);
    // Visible, as the engine's pages begin (WindowsWebEngine.visible; setVisible hides them). The composition controllers
    // WebView2 156 made here read IsVisible FALSE until set, and a hidden one takes no input and presents no frame after
    // its first: no click reached a page, and a page that painted after its commit was never shown.
    controller_->put_IsVisible(TRUE);
    parent_ = UiThread::get().parking();
    applySettings();
    registerNavigationEvents();
    registerScriptEvents();
    registerContentEvents();
    registerInputEvents();
    registerQuestionEvents();
    if (requested_) place(requested_->frame, requested_->canvas, requested_->x, requested_->y, requested_->width,
                          requested_->height, requested_->scale);   // W3
    emit(token_, kCreated, 0, 1, S_OK);
}

/** What never changes for the engine's life (D15–D18); configure() sets the per-lease rest. */
void WvPage::applySettings() {
    settings_->put_IsStatusBarEnabled(FALSE);
    settings_->put_AreDefaultScriptDialogsEnabled(FALSE);    // ScriptDialogOpening asks the client
    settings_->put_IsBuiltInErrorPageEnabled(FALSE);         // the runtime reports the error; no Edge-branded page
    settings_->put_AreHostObjectsAllowed(FALSE);
    settings_->put_IsWebMessageEnabled(TRUE);                // the document script's channel (D5)
    settings_->put_IsZoomControlEnabled(FALSE);
    settings_->put_AreDefaultContextMenusEnabled(TRUE);      // filtered in ContextMenuRequested (D17)
    settings_->put_AreBrowserAcceleratorKeysEnabled(FALSE);  // no F5, Ctrl+R, Ctrl+P, Alt+Left of WebView2's own (D15)
    settings_->put_IsPinchZoomEnabled(FALSE);
    settings_->put_IsSwipeNavigationEnabled(FALSE);
    settings_->put_AreDevToolsEnabled(FALSE);
    profile_->put_IsPasswordAutosaveEnabled(FALSE);
    profile_->put_IsGeneralAutofillEnabled(FALSE);
    CoString agent;
    settings_->get_UserAgent(agent.put());
    baseUserAgent_ = agent.str();
}

void WvPage::configure(int flags, uint32_t background, std::wstring userAgentSuffix) {
    if (released_ || !webview_) return;
    flags_ = flags;
    settings_->put_IsScriptEnabled((flags & kConfScripts) ? TRUE : FALSE);          // from the next document on
    settings_->put_AreDevToolsEnabled((flags & kConfInspectable) ? TRUE : FALSE);
    controller_->put_DefaultBackgroundColor(colorOf(background));
    // WebView2's own agent, read at creation, plus the lease's suffix (PageSetup.userAgentSuffix).
    if (!baseUserAgent_.empty()) {
        settings_->put_UserAgent((userAgentSuffix.empty() ? baseUserAgent_ : baseUserAgent_ + L" " + userAgentSuffix).c_str());
    }
    // Dark mode is this engine's alone (D13): an emulated media feature, never the profile's colour scheme.
    devtools(0, L"Emulation.setEmulatedMedia",
             (flags & kConfDark) ? L"{\"features\":[{\"name\":\"prefers-color-scheme\",\"value\":\"dark\"}]}"
                                 : L"{\"features\":[{\"name\":\"prefers-color-scheme\",\"value\":\"light\"}]}");
}

void WvPage::release() {
    if (released_) return;
    // A profile's site settings outlive the page: the origins it let autoplay go back to the default.
    if (profile_) {
        for (auto const& origin : autoplayOrigins_) {
            profile_->SetPermissionState(COREWEBVIEW2_PERMISSION_KIND_AUTOPLAY, origin.c_str(), COREWEBVIEW2_PERMISSION_STATE_DEFAULT,
                                         Callback<ICoreWebView2SetPermissionStateCompletedHandler>([](HRESULT) { return S_OK; }).Get());
        }
    }
    autoplayOrigins_.clear();
    answerQuestionsByDefault();
    resources_.answerAll([](Resource& r) { r.deferral->Complete(); });   // unanswered: the request fails
    afterScript_.clear();
    released_ = true;
    capture_.stop();
    if (controller_) controller_->Close();
    env_ = nullptr;
    composition_ = nullptr;
    controller_ = nullptr;
    webview_ = nullptr;
    settings_ = nullptr;
    profile_ = nullptr;
    webVisual_ = nullptr;
    root_ = nullptr;
    compositor_ = nullptr;
}

void WvPage::registerNavigationEvents() {
    EventRegistrationToken t{};
    webview_->add_NavigationStarting(WV_HANDLER(ICoreWebView2NavigationStartingEventHandler, ICoreWebView2*,
        ICoreWebView2NavigationStartingEventArgs*, { self->onNavigationStarting(args, true); }), &t);
    webview_->add_FrameNavigationStarting(WV_HANDLER(ICoreWebView2NavigationStartingEventHandler, ICoreWebView2*,
        ICoreWebView2NavigationStartingEventArgs*, { self->onNavigationStarting(args, false); }), &t);
    webview_->add_ContentLoading(WV_HANDLER(ICoreWebView2ContentLoadingEventHandler, ICoreWebView2*,
        ICoreWebView2ContentLoadingEventArgs*, {
            UINT64 id = 0;
            BOOL errorPage = FALSE;
            args->get_NavigationId(&id);
            args->get_IsErrorPage(&errorPage);
            auto found = self->navigationEpochs_.find(id);
            self->documentEpoch_ = found != self->navigationEpochs_.end() ? found->second : self->epoch_;
            self->pendingNavigation_.ended(id);
            // A new document: what the last one asked and wasn't answered gets its safe default (D18).
            self->answerQuestionsByDefault();
            CoString source;
            self->webview_->get_Source(source.put());
            // The capture's newest frame now: frames after it are the new document's (paint holding showed the last one).
            emit(self->token_, kCommitted, self->documentEpoch_, static_cast<int64_t>(id), (errorPage ? 1 : 0) | (self->capture_.seq() << 1), source.str());
        }), &t);
    webview_->add_SourceChanged(WV_HANDLER(ICoreWebView2SourceChangedEventHandler, ICoreWebView2*,
        ICoreWebView2SourceChangedEventArgs*, {
            BOOL isNew = FALSE;
            args->get_IsNewDocument(&isNew);
            CoString source;
            self->webview_->get_Source(source.put());
            emit(self->token_, kSourceChanged, self->documentEpoch_, 0, isNew ? 1 : 0, source.str());
        }), &t);
    webview_->add_HistoryChanged(WV_HANDLER(ICoreWebView2HistoryChangedEventHandler, ICoreWebView2*, IUnknown*, {
        (void)args;
        BOOL back = FALSE, forward = FALSE;
        self->webview_->get_CanGoBack(&back);
        self->webview_->get_CanGoForward(&forward);
        emit(self->token_, kHistory, self->documentEpoch_, 0, (back ? 1 : 0) | (forward ? 2 : 0));
    }), &t);
    webview_->add_DocumentTitleChanged(WV_HANDLER(ICoreWebView2DocumentTitleChangedEventHandler, ICoreWebView2*, IUnknown*, {
        (void)args;
        CoString title;
        self->webview_->get_DocumentTitle(title.put());
        emit(self->token_, kTitle, self->documentEpoch_, 0, 0, title.str());
    }), &t);
    webview_->add_DOMContentLoaded(WV_HANDLER(ICoreWebView2DOMContentLoadedEventHandler, ICoreWebView2*,
        ICoreWebView2DOMContentLoadedEventArgs*, {
            UINT64 id = 0;
            args->get_NavigationId(&id);
            auto found = self->navigationEpochs_.find(id);
            emit(self->token_, kDomLoaded, found != self->navigationEpochs_.end() ? found->second : self->epoch_, static_cast<int64_t>(id), 0);
        }), &t);
    webview_->add_NavigationCompleted(WV_HANDLER(ICoreWebView2NavigationCompletedEventHandler, ICoreWebView2*,
        ICoreWebView2NavigationCompletedEventArgs*, {
            UINT64 id = 0;
            BOOL success = FALSE;
            COREWEBVIEW2_WEB_ERROR_STATUS status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
            int http = 0;
            args->get_NavigationId(&id);
            args->get_IsSuccess(&success);
            args->get_WebErrorStatus(&status);
            self->pendingNavigation_.ended(id);
            winrt::com_ptr<ICoreWebView2NavigationCompletedEventArgs> a;
            a.copy_from(args);
            if (auto a2 = a.try_as<ICoreWebView2NavigationCompletedEventArgs2>()) a2->get_HttpStatusCode(&http);
            auto found = self->navigationEpochs_.find(id);
            CoString source;
            self->webview_->get_Source(source.put());
            emit(self->token_, kCompleted, found != self->navigationEpochs_.end() ? found->second : self->epoch_,
                 static_cast<int64_t>(id), (success ? 1 : 0) | (static_cast<int64_t>(status) << 8) | (static_cast<int64_t>(http) << 16), source.str());
        }), &t);
    webview_->add_ProcessFailed(WV_HANDLER(ICoreWebView2ProcessFailedEventHandler, ICoreWebView2*,
        ICoreWebView2ProcessFailedEventArgs*, {
            COREWEBVIEW2_PROCESS_FAILED_KIND kind = COREWEBVIEW2_PROCESS_FAILED_KIND_UNKNOWN_PROCESS_EXITED;
            args->get_ProcessFailedKind(&kind);
            emit(self->token_, kProcessFailed, self->documentEpoch_, 0, static_cast<int64_t>(kind));
        }), &t);
}

/**
 * EngineConfig.mediaPlaybackRequiresGesture false: WebView2 raises no PermissionRequested for a play() with no gesture,
 * and its policy refuses it (156). The profile's AUTOPLAY site setting allows it, set for each main-frame navigation's
 * origin before its document commits (it completes at once). Engines of one profile that show one origin share it.
 */
void WvPage::allowAutoplay(std::wstring const& url) {
    const std::wstring origin = originOf(url);
    if (origin.empty() || !profile_) return;
    autoplayOrigins_.insert(origin);
    profile_->SetPermissionState(COREWEBVIEW2_PERMISSION_KIND_AUTOPLAY, origin.c_str(), COREWEBVIEW2_PERMISSION_STATE_ALLOW,
                                 Callback<ICoreWebView2SetPermissionStateCompletedHandler>([](HRESULT) { return S_OK; }).Get());
}

/**
 * Asks the engine synchronously, on this thread, from its published snapshot (D9): NavigationStarting must be answered
 * inside the handler. Subframes (FrameNavigationStarting) are asked as such and never recorded as documents.
 */
void WvPage::onNavigationStarting(ICoreWebView2NavigationStartingEventArgs* args, bool mainFrame) {
    CoString uri;
    args->get_Uri(uri.put());
    BOOL redirected = FALSE, user = FALSE;
    UINT64 id = 0;
    args->get_IsRedirected(&redirected);
    args->get_IsUserInitiated(&user);
    args->get_NavigationId(&id);
    int kind = -1;
    winrt::com_ptr<ICoreWebView2NavigationStartingEventArgs> a;
    a.copy_from(args);
    if (auto a3 = a.try_as<ICoreWebView2NavigationStartingEventArgs3>()) {
        COREWEBVIEW2_NAVIGATION_KIND k = COREWEBVIEW2_NAVIGATION_KIND_NEW_DOCUMENT;
        if (SUCCEEDED(a3->get_NavigationKind(&k))) kind = static_cast<int>(k);
    }
    const int flags = navFlags(mainFrame, redirected != FALSE, user != FALSE, kind);
    const int verdict = decideNavigation(token_, epoch_, flags, uri.str());
    if (verdict == kRefuse || verdict == kRefuseOwn) args->put_Cancel(TRUE);
    if (!mainFrame) return;
    navigationEpochs_[id] = epoch_;
    if (verdict != kRefuse && verdict != kRefuseOwn) {
        pendingNavigation_.started(id, epoch_, uri.str());
        if (flags_ & kConfAutoplay) allowAutoplay(uri.str());
    }
    while (navigationEpochs_.size() > 64) navigationEpochs_.erase(navigationEpochs_.begin());   // ids only grow
    emit(token_, kNavStarting, epoch_, static_cast<int64_t>(id), flags | (verdict << 16), uri.str());
}

// --- Placement (Task 10) ---

/**
 * The surface's place (D1, W3): its window's frame is WebView2's parent, its rectangle in the canvas's client pixels maps
 * to the frame's, and the scale is the window's own (never LocalDensity: L26). Kept until the controller exists. Sizes
 * reach here settled (the Kotlin side debounces them); a size change recreates the capture pool before the visual grows.
 */
void WvPage::place(HWND frame, HWND canvas, int x, int y, int width, int height, double scale) {
    if (released_ || width <= 0 || height <= 0 || !frame || !canvas) return;
    requested_ = Placement{frame, canvas, x, y, width, height, scale};
    if (!controller_) return;
    if (frame != parent_) {
        controller_->put_ParentWindow(frame);
        parent_ = frame;
    }
    POINT origin{x, y};
    MapWindowPoints(canvas, frame, &origin, 1);
    const bool resized = width != applied_.width || height != applied_.height;
    if (scale != applied_.scale || applied_.width == 0) controller_->put_RasterizationScale(scale);
    if (resized) {
        capture_.resize(width, height);
        root_.Size({static_cast<float>(width), static_cast<float>(height)});
    }
    controller_->put_Bounds(RECT{origin.x, origin.y, origin.x + width, origin.y + height});
    applied_ = Placement{frame, canvas, x, y, width, height, scale};
    if (!capture_.started()) {
        std::weak_ptr<WvPage> weak = weak_from_this();
        monitor_ = MonitorFromWindow(frame, MONITOR_DEFAULTTONEAREST);
        capture_.start(GpuDevices::forWindow(frame), root_, width, height, [weak](int64_t seq) {
            if (auto self = weak.lock(); self && !self->released_) emit(self->token_, kFrame, 0, seq, 0);
        });
    } else {
        followAdapter(false);   // a new window (another surface) may be on another adapter's monitor
    }
}

/**
 * D24: the page's window is on a monitor driven by another adapter than its capture's (moved, or the displays changed):
 * the capture moves to that adapter's device. Cheap when the monitor is the same, which a move nearly always is.
 */
void WvPage::followAdapter(bool force) {
    if (released_ || !capture_.started() || !parent_ || parent_ == UiThread::get().parking()) return;
    const HMONITOR monitor = MonitorFromWindow(parent_, MONITOR_DEFAULTTONEAREST);
    if (!force && monitor == monitor_) return;
    monitor_ = monitor;
    auto device = GpuDevices::forWindow(parent_);
    if (device && device != capture_.device()) capture_.rebind(device);
}

std::shared_ptr<GpuDevice> WvPage::captureDevice() const { return capture_.device(); }
void WvPage::rebindCapture(std::shared_ptr<GpuDevice> device) { capture_.rebind(std::move(device)); }
void WvPage::reportNoDevice() {
    if (!released_) emit(token_, kProcessFailed, documentEpoch_, 0, kFailedNoDevice);
}

/** No surface shows the page: the parking window is its parent, so a closed window never takes the page with it. */
void WvPage::unplace() {
    requested_.reset();
    if (released_ || !controller_) return;
    const HWND parking = UiThread::get().parking();
    if (parent_ != parking) {
        controller_->put_ParentWindow(parking);
        parent_ = parking;
    }
}

void WvPage::parentMoved() {
    if (released_ || !controller_) return;
    controller_->NotifyParentWindowPositionChanged();
    followAdapter(false);
}

/** A hidden page (D21): not drawn (Chromium throttles it), muted, and asked to keep little memory. */
void WvPage::setVisible(bool visible) {
    if (released_ || !controller_) return;
    controller_->put_IsVisible(visible ? TRUE : FALSE);
    webview_->put_IsMuted(visible ? FALSE : TRUE);
    webview_->put_MemoryUsageTargetLevel(visible ? COREWEBVIEW2_MEMORY_USAGE_TARGET_LEVEL_NORMAL : COREWEBVIEW2_MEMORY_USAGE_TARGET_LEVEL_LOW);
}

void WvPage::setMemoryTarget(bool low) {
    if (!released_ && webview_) webview_->put_MemoryUsageTargetLevel(low ? COREWEBVIEW2_MEMORY_USAGE_TARGET_LEVEL_LOW : COREWEBVIEW2_MEMORY_USAGE_TARGET_LEVEL_NORMAL);
}

// --- Pages ---

// Never destroyed: a page left at exit is never released by a static destructor, its UI thread gone.
std::shared_mutex& Pages::lock() {
    static auto* instance = new std::shared_mutex();
    return *instance;
}

std::map<int64_t, std::shared_ptr<WvPage>>& Pages::map() {
    static auto* instance = new std::map<int64_t, std::shared_ptr<WvPage>>();
    return *instance;
}

void Pages::put(int64_t token, std::shared_ptr<WvPage> page) {
    std::unique_lock<std::shared_mutex> guard(lock());
    map()[token] = std::move(page);
}

std::shared_ptr<WvPage> Pages::find(int64_t token) {
    std::shared_lock<std::shared_mutex> guard(lock());
    auto it = map().find(token);
    return it == map().end() ? nullptr : it->second;
}

void Pages::remove(int64_t token) {
    std::shared_ptr<WvPage> gone;   // dropped after the lock: a page's last reference never goes under it
    {
        std::unique_lock<std::shared_mutex> guard(lock());
        auto it = map().find(token);
        if (it == map().end()) return;
        gone = std::move(it->second);
        map().erase(it);
    }
}

void Pages::forEach(std::function<void(WvPage&)> const& fn) {
    std::vector<std::shared_ptr<WvPage>> all;
    {
        std::shared_lock<std::shared_mutex> guard(lock());
        all.reserve(map().size());
        for (auto const& entry : map()) all.push_back(entry.second);
    }
    for (auto const& page : all) fn(*page);
}

}  // namespace wv
