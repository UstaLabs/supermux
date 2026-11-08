#include "webview.h"
#include "probes.h"

using Microsoft::WRL::Callback;
namespace wuc = winrt::Windows::UI::Composition;

namespace wv2 {
namespace {

std::wstring className(HWND window) {
    wchar_t name[256] = L"";
    if (window) GetClassNameW(window, name, 256);
    return name;
}

HMODULE selfModule() {
    HMODULE self = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       reinterpret_cast<LPCWSTR>(&selfModule), &self);
    return self;
}

using CreateEnvironmentFn = HRESULT(STDAPICALLTYPE*)(PCWSTR, PCWSTR, ICoreWebView2EnvironmentOptions*,
                                                    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler*);

// WebView2Loader.dll sits next to this DLL (build.cmd copies it there).
CreateEnvironmentFn loadCreateEnvironment() {
    wchar_t path[MAX_PATH];
    const DWORD length = GetModuleFileNameW(selfModule(), path, MAX_PATH);
    if (length == 0 || length >= MAX_PATH) return nullptr;
    std::wstring loaderPath(path, length);
    loaderPath = loaderPath.substr(0, loaderPath.find_last_of(L'\\') + 1) + L"WebView2Loader.dll";
    HMODULE loader = LoadLibraryExW(loaderPath.c_str(), nullptr,
                                    LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!loader) return nullptr;
    return reinterpret_cast<CreateEnvironmentFn>(GetProcAddress(loader, "CreateCoreWebView2EnvironmentWithOptions"));
}

LRESULT CALLBACK hostProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    return DefWindowProcW(window, message, wParam, lParam);
}

// parentMode 2: a 1x1 child of the canvas, owned by the UI thread, so WebView2's parent belongs to the calling thread.
HWND createHostWindow(HWND parent) {
    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.lpfnWndProc = hostProc;
    windowClass.hInstance = selfModule();
    windowClass.lpszClassName = L"wv2capture.Host";
    RegisterClassExW(&windowClass);  // fails harmlessly when it is already registered
    return CreateWindowExW(0, windowClass.lpszClassName, L"", WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_CLIPSIBLINGS,
                           0, 0, 1, 1, parent, nullptr, windowClass.hInstance, nullptr);
}

}  // namespace

void WebHost::start(HostOptions options) {
    options_ = std::move(options);
    HWND parent = nullptr;
    if (options_.parentMode == 1) {
        parent = options_.canvas;
    } else if (options_.parentMode == 2) {
        hostWindow_ = createHostWindow(options_.canvas);
        parent = hostWindow_;
    } else {
        parent = GetAncestor(options_.canvas, GA_ROOT);
    }
    if (!parent) {
        emit(kReady, E_HANDLE, 0, L"no parent window for parentMode " + std::to_wstring(options_.parentMode));
        return;
    }
    parent_ = parent;
    capture_.setDirectPull(options_.directPull);
    if (!capture_.init(options_.adapterMode)) {
        emit(kReady, E_FAIL, 0, L"no D3D11 device");
        return;
    }
    const auto createEnvironment = loadCreateEnvironment();
    if (!createEnvironment) {
        emit(kReady, E_FAIL, 0, L"WebView2Loader.dll not found next to wv2capture.dll");
        return;
    }
    compositor_ = wuc::Compositor();
    root_ = compositor_.CreateContainerVisual();
    webVisual_ = compositor_.CreateContainerVisual();
    webVisual_.RelativeSizeAdjustment({1.0f, 1.0f});
    root_.Children().InsertAtTop(webVisual_);

    auto environmentOptions = Microsoft::WRL::Make<CoreWebView2EnvironmentOptions>();
    if (!options_.browserArguments.empty()) {
        environmentOptions->put_AdditionalBrowserArguments(options_.browserArguments.c_str());
    }
    const HRESULT hr = createEnvironment(
        nullptr, options_.userDataFolder.c_str(), environmentOptions.Get(),
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [this](HRESULT result, ICoreWebView2Environment* environment) -> HRESULT {
                if (FAILED(result) || !environment) {
                    emit(kReady, FAILED(result) ? result : E_POINTER, 0, L"CreateCoreWebView2Environment failed");
                    return S_OK;
                }
                winrt::com_ptr<ICoreWebView2Environment> created;
                created.copy_from(environment);
                environment_ = created.try_as<ICoreWebView2Environment3>();
                if (!environment_) {
                    emit(kReady, E_NOINTERFACE, 0, L"no ICoreWebView2Environment3");
                    return S_OK;
                }
                LPWSTR version = nullptr;
                if (SUCCEEDED(created->get_BrowserVersionString(&version)) && version) {
                    runtimeVersion_ = version;
                    CoTaskMemFree(version);
                }
                const HRESULT requested = environment_->CreateCoreWebView2CompositionController(
                    parent_.load(),
                    Callback<ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler>(
                        [this](HRESULT result2, ICoreWebView2CompositionController* composition) -> HRESULT {
                            if (FAILED(result2) || !composition) {
                                emit(kReady, FAILED(result2) ? result2 : E_POINTER, 0,
                                     L"CreateCoreWebView2CompositionController failed (try -Dwv2.parent=host)");
                                return S_OK;
                            }
                            try {
                                onControllerCreated(composition);
                            } catch (winrt::hresult_error const& e) {
                                emit(kReady, e.code(), 0,
                                     L"setting up the controller failed: " + std::wstring(e.message().c_str()));
                            }
                            return S_OK;
                        })
                        .Get());
                if (FAILED(requested)) emit(kReady, requested, 0, L"CreateCoreWebView2CompositionController refused");
                return S_OK;
            })
            .Get());
    if (FAILED(hr)) emit(kReady, hr, 0, L"CreateCoreWebView2EnvironmentWithOptions failed");
}

void WebHost::onControllerCreated(ICoreWebView2CompositionController* composition) {
    composition_.copy_from(composition);
    controller_ = composition_.as<ICoreWebView2Controller>();
    winrt::check_hresult(controller_->get_CoreWebView2(webview_.put()));
    winrt::check_hresult(composition_->put_RootVisualTarget(winrt::get_unknown(webVisual_)));
    const HWND parent = parent_.load();
    const double scale = GetDpiForWindow(parent) / 96.0;
    if (auto controller3 = controller_.try_as<ICoreWebView2Controller3>()) {
        controller3->put_BoundsMode(COREWEBVIEW2_BOUNDS_MODE_USE_RAW_PIXELS);
        controller3->put_ShouldDetectMonitorScaleChanges(FALSE);
        controller3->put_RasterizationScale(scale);
    }
    controller_->put_IsVisible(TRUE);
    winrt::com_ptr<ICoreWebView2Settings> settings;
    if (SUCCEEDED(webview_->get_Settings(settings.put()))) {
        settings->put_IsStatusBarEnabled(FALSE);
        settings->put_AreDevToolsEnabled(TRUE);
        settings->put_AreDefaultContextMenusEnabled(TRUE);
    }
    UINT32 pid = 0;
    if (SUCCEEDED(webview_->get_BrowserProcessId(&pid))) browserPid_ = pid;
    registerEvents();
    if (hasRequested_) {
        setBounds(requested_.left, requested_.top, requested_.right - requested_.left, requested_.bottom - requested_.top);
    }
    emit(kReady, S_OK, 0,
         runtimeVersion_ + L"|" + capture_.adapter() + (capture_.hardware() ? L" (hardware device" : L" (WARP") +
             L", luid " + capture_.adapterLuid() + L")|" + hex(reinterpret_cast<int64_t>(parent)) + L"|" +
             className(parent) + L"|" + std::to_wstring(scale));
}

void WebHost::registerEvents() {
    EventRegistrationToken token{};
    webview_->add_NavigationCompleted(
        Callback<ICoreWebView2NavigationCompletedEventHandler>(
            [](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                BOOL success = FALSE;
                COREWEBVIEW2_WEB_ERROR_STATUS status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                args->get_IsSuccess(&success);
                args->get_WebErrorStatus(&status);
                emit(kNavCompleted, success, status, L"");
                return S_OK;
            })
            .Get(),
        &token);
    controller_->add_GotFocus(Callback<ICoreWebView2FocusChangedEventHandler>(
                                  [](ICoreWebView2Controller*, IUnknown*) -> HRESULT {
                                      emit(kFocus, 1, 0, L"");
                                      return S_OK;
                                  })
                                  .Get(),
                              &token);
    controller_->add_LostFocus(Callback<ICoreWebView2FocusChangedEventHandler>(
                                   [](ICoreWebView2Controller*, IUnknown*) -> HRESULT {
                                       emit(kFocus, 0, 0, L"");
                                       return S_OK;
                                   })
                                   .Get(),
                               &token);
    controller_->add_MoveFocusRequested(
        Callback<ICoreWebView2MoveFocusRequestedEventHandler>(
            [](ICoreWebView2Controller*, ICoreWebView2MoveFocusRequestedEventArgs* args) -> HRESULT {
                COREWEBVIEW2_MOVE_FOCUS_REASON reason = COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC;
                args->get_Reason(&reason);
                args->put_Handled(TRUE);  // the host moves focus itself (Compose focus, then AWT)
                emit(kMoveFocusRequested, reason, 0, L"");
                return S_OK;
            })
            .Get(),
        &token);
    controller_->add_AcceleratorKeyPressed(
        Callback<ICoreWebView2AcceleratorKeyPressedEventHandler>(
            [](ICoreWebView2Controller*, ICoreWebView2AcceleratorKeyPressedEventArgs* args) -> HRESULT {
                COREWEBVIEW2_KEY_EVENT_KIND kind = COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN;
                UINT key = 0;
                args->get_KeyEventKind(&kind);
                args->get_VirtualKey(&key);
                const bool ctrl = GetAsyncKeyState(VK_CONTROL) < 0;
                const bool shift = GetAsyncKeyState(VK_SHIFT) < 0;
                const bool alt = GetAsyncKeyState(VK_MENU) < 0;
                const bool appShortcut = key == 'K' && ctrl && shift;
                if (appShortcut) args->put_Handled(TRUE);
                if (kind == COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN || kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN) {
                    emit(kAccelerator, key,
                         static_cast<int64_t>(kind) | (ctrl ? 0x100 : 0) | (shift ? 0x200 : 0) | (alt ? 0x400 : 0) |
                             (appShortcut ? 0x800 : 0),
                         L"");
                }
                return S_OK;
            })
            .Get(),
        &token);
    composition_->add_CursorChanged(Callback<ICoreWebView2CursorChangedEventHandler>(
                                        [this](ICoreWebView2CompositionController*, IUnknown*) -> HRESULT {
                                            onCursorChanged();
                                            return S_OK;
                                        })
                                        .Get(),
                                    &token);
    webview_->add_WebMessageReceived(
        Callback<ICoreWebView2WebMessageReceivedEventHandler>(
            [](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                LPWSTR json = nullptr;
                if (SUCCEEDED(args->get_WebMessageAsJson(&json)) && json) {
                    emit(kWebMessage, 0, 0, json);
                    CoTaskMemFree(json);
                }
                return S_OK;
            })
            .Get(),
        &token);
    webview_->add_ProcessFailed(Callback<ICoreWebView2ProcessFailedEventHandler>(
                                    [](ICoreWebView2*, ICoreWebView2ProcessFailedEventArgs* args) -> HRESULT {
                                        COREWEBVIEW2_PROCESS_FAILED_KIND kind =
                                            COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED;
                                        args->get_ProcessFailedKind(&kind);
                                        emit(kProcessFailed, kind, 0, L"");
                                        return S_OK;
                                    })
                                    .Get(),
                                &token);
}

void WebHost::onCursorChanged() {
    UINT32 id = 0;
    composition_->get_SystemCursorId(&id);
    if (id != 0) {
        emit(kCursor, id, 0, L"");
        return;
    }
    // A CSS url() cursor: hand its pixels over (the HCURSOR may be freed after this callback).
    HCURSOR cursor = nullptr;
    int width = 0;
    int height = 0;
    int hotX = 0;
    int hotY = 0;
    std::vector<int32_t> argb;
    if (SUCCEEDED(composition_->get_Cursor(&cursor)) && cursor && cursorToArgb(cursor, width, height, hotX, hotY, argb)) {
        emitCustomCursor(width, height, hotX, hotY, argb);
    } else {
        emit(kCursor, 32512, 0, L"");  // IDC_ARROW
    }
}

void WebHost::setBounds(int x, int y, int width, int height) {
    if (width <= 0 || height <= 0) return;
    // The surface lays out (and sends its bounds) before WebView2 has created the controller, and it only sends them
    // again when they change: keep the latest, and onControllerCreated applies them.
    requested_ = RECT{x, y, x + width, y + height};
    hasRequested_ = true;
    if (!controller_) return;
    RECT next{x, y, x + width, y + height};
    if (options_.parentMode == 0) {
        POINT origin{x, y};
        MapWindowPoints(options_.canvas, parent_.load(), &origin, 1);
        next = RECT{origin.x, origin.y, origin.x + width, origin.y + height};
    } else if (options_.parentMode == 2) {
        // Our 1x1 child sits at the surface's origin; the WebView is placed relative to it.
        SetWindowPos(hostWindow_, HWND_TOP, x, y, 1, 1, SWP_NOACTIVATE);
        next = RECT{0, 0, width, height};
    }
    const bool resized = width != bounds_.right - bounds_.left || height != bounds_.bottom - bounds_.top;
    if (resized) {
        capture_.resize(width, height);  // before the visual grows: frames never land in a smaller texture
        root_.Size({static_cast<float>(width), static_cast<float>(height)});
    }
    if (options_.compensateOffset) {
        webVisual_.Offset({-static_cast<float>(next.left), -static_cast<float>(next.top), 0.0f});
    }
    controller_->put_Bounds(next);
    bounds_ = next;
    if (!capture_.started() && capture_.start(root_, width, height, options_.dirtyRegions)) {
        emit(kCaptureStarted, width, height, capture_.dirtyApi() ? L"dirtyApi=1" : L"dirtyApi=0");
    }
}

void WebHost::notifyParentMoved() {
    if (controller_) controller_->NotifyParentWindowPositionChanged();
}

void WebHost::setVisible(bool visible) {
    if (controller_) controller_->put_IsVisible(visible ? TRUE : FALSE);
}

void WebHost::addDocumentScript(std::wstring const& script) {
    if (!webview_) return;
    webview_->AddScriptToExecuteOnDocumentCreated(
        script.c_str(), Callback<ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler>(
                            [](HRESULT result, LPCWSTR) -> HRESULT {
                                emit(kDocScriptAdded, result, 0, L"");
                                return S_OK;
                            })
                            .Get());
}

void WebHost::navigateToString(std::wstring const& html) {
    if (webview_) webview_->NavigateToString(html.c_str());
}

void WebHost::executeScript(int64_t requestId, std::wstring const& script) {
    if (!webview_) {
        emit(kScriptResult, requestId, E_FAIL, L"null");
        return;
    }
    const HRESULT hr = webview_->ExecuteScript(
        script.c_str(), Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
                            [requestId](HRESULT result, LPCWSTR json) -> HRESULT {
                                emit(kScriptResult, requestId, result, json ? json : L"null");
                                return S_OK;
                            })
                            .Get());
    if (FAILED(hr)) emit(kScriptResult, requestId, hr, L"null");
}

void WebHost::mouse(int kind, int virtualKeys, int mouseData, int x, int y) {
    if (!composition_) return;
    const HRESULT hr = composition_->SendMouseInput(static_cast<COREWEBVIEW2_MOUSE_EVENT_KIND>(kind),
                                                    static_cast<COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS>(virtualKeys),
                                                    static_cast<UINT32>(mouseData), POINT{x, y});
    if (FAILED(hr)) logLine(L"SendMouseInput failed " + hex(hr));
}

void WebHost::pointer(int pointerKind, int eventKind, int pointerId, int x, int y, int pressure) {
    if (!composition_ || !environment_) return;
    winrt::com_ptr<ICoreWebView2PointerInfo> info;
    if (FAILED(environment_->CreateCoreWebView2PointerInfo(info.put()))) return;
    const bool down = eventKind == WM_POINTERDOWN;
    const bool update = eventKind == WM_POINTERUPDATE;
    const bool up = eventKind == WM_POINTERUP;
    const bool leave = eventKind == WM_POINTERLEAVE;
    UINT32 flags = POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE;
    if (down) flags |= POINTER_FLAG_NEW | POINTER_FLAG_DOWN | POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON;
    if (update) flags |= POINTER_FLAG_UPDATE | POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON;
    if (up) flags = POINTER_FLAG_PRIMARY | POINTER_FLAG_UP;
    if (leave) flags = POINTER_FLAG_PRIMARY;  // out of range and out of contact: the pointer is gone
    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);
    // Every location is in WebView physical pixels, relative to the WebView (ICoreWebView2PointerInfo docs).
    const POINT location{x, y};
    const RECT view{0, 0, bounds_.right - bounds_.left, bounds_.bottom - bounds_.top};
    info->put_PointerKind(pointerKind == 3 ? PT_PEN : PT_TOUCH);
    info->put_PointerId(static_cast<UINT32>(pointerId));
    info->put_FrameId(++pointerFrame_);
    info->put_PointerFlags(flags);
    info->put_PointerDeviceRect(view);
    info->put_DisplayRect(view);
    info->put_PixelLocation(location);
    info->put_PixelLocationRaw(location);
    info->put_Time(GetTickCount());
    info->put_PerformanceCount(static_cast<UINT64>(counter.QuadPart));
    info->put_ButtonChangeKind(down ? POINTER_CHANGE_FIRSTBUTTON_DOWN : up ? POINTER_CHANGE_FIRSTBUTTON_UP : POINTER_CHANGE_NONE);
    if (pointerKind == 3) {
        info->put_PenFlags(PEN_FLAG_NONE);
        info->put_PenMask(PEN_MASK_PRESSURE);
        info->put_PenPressure(static_cast<UINT32>(pressure));
    } else {
        const RECT contact{x - 2, y - 2, x + 2, y + 2};
        info->put_TouchFlags(TOUCH_FLAG_NONE);
        info->put_TouchMask(TOUCH_MASK_CONTACTAREA | TOUCH_MASK_PRESSURE);
        info->put_TouchPressure(static_cast<UINT32>(pressure));
        info->put_TouchContact(contact);
        info->put_TouchContactRaw(contact);
    }
    const HRESULT hr = composition_->SendPointerInput(static_cast<COREWEBVIEW2_POINTER_EVENT_KIND>(eventKind), info.get());
    if (FAILED(hr)) logLine(L"SendPointerInput failed " + hex(hr));
}

void WebHost::moveFocus(int reason) {
    if (!controller_) return;
    const HRESULT hr = controller_->MoveFocus(static_cast<COREWEBVIEW2_MOVE_FOCUS_REASON>(reason));
    if (FAILED(hr)) logLine(L"MoveFocus failed " + hex(hr));
}

void WebHost::stop() {
    capture_.stop();
    if (controller_) controller_->Close();
    composition_ = nullptr;
    controller_ = nullptr;
    webview_ = nullptr;
    environment_ = nullptr;
    webVisual_ = nullptr;
    root_ = nullptr;
    compositor_ = nullptr;
    if (hostWindow_) {
        DestroyWindow(hostWindow_);
        hostWindow_ = nullptr;
    }
}

}  // namespace wv2
