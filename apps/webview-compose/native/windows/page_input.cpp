#include "page.h"
#include <cstring>
using Microsoft::WRL::Callback;

namespace wv {
namespace {

/** The test's cursorToArgb (apps/webview-wintest/native/probes.cpp): draw the cursor on black and on white. */
bool cursorToArgb(HCURSOR cursor, int& width, int& height, int& hotX, int& hotY, std::vector<int32_t>& argb) {
    ICONINFO info{};
    if (!cursor || !GetIconInfo(cursor, &info)) return false;
    BITMAP bitmap{};
    HBITMAP source = info.hbmColor ? info.hbmColor : info.hbmMask;
    const bool measured = source && GetObjectW(source, sizeof(bitmap), &bitmap);
    width = bitmap.bmWidth;
    height = info.hbmColor ? bitmap.bmHeight : bitmap.bmHeight / 2;
    hotX = static_cast<int>(info.xHotspot);
    hotY = static_cast<int>(info.yHotspot);
    if (info.hbmColor) DeleteObject(info.hbmColor);
    if (info.hbmMask) DeleteObject(info.hbmMask);
    if (!measured || width <= 0 || height <= 0) return false;
    // Draw the cursor on black and on white: alpha = 255 - (white - black), colour = black / alpha.
    BITMAPINFO bi{};
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = width;
    bi.bmiHeader.biHeight = -height;
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    HDC screen = GetDC(nullptr);
    HDC dc = CreateCompatibleDC(screen);
    void* blackBits = nullptr;
    void* whiteBits = nullptr;
    HBITMAP black = CreateDIBSection(dc, &bi, DIB_RGB_COLORS, &blackBits, nullptr, 0);
    HBITMAP white = CreateDIBSection(dc, &bi, DIB_RGB_COLORS, &whiteBits, nullptr, 0);
    if (!black || !white || !blackBits || !whiteBits) {
        if (black) DeleteObject(black);
        if (white) DeleteObject(white);
        DeleteDC(dc);
        ReleaseDC(nullptr, screen);
        return false;
    }
    const RECT all{0, 0, width, height};
    HGDIOBJ previous = SelectObject(dc, black);
    FillRect(dc, &all, static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
    DrawIconEx(dc, 0, 0, cursor, width, height, 0, nullptr, DI_NORMAL);
    SelectObject(dc, white);
    FillRect(dc, &all, static_cast<HBRUSH>(GetStockObject(WHITE_BRUSH)));
    DrawIconEx(dc, 0, 0, cursor, width, height, 0, nullptr, DI_NORMAL);
    SelectObject(dc, previous);
    GdiFlush();
    argb.resize(static_cast<size_t>(width) * height);
    const auto* b = static_cast<const uint8_t*>(blackBits);
    const auto* w = static_cast<const uint8_t*>(whiteBits);
    for (size_t i = 0; i < argb.size(); ++i) {
        const int alpha = 255 - std::clamp(w[i * 4 + 1] - b[i * 4 + 1], 0, 255);
        int red = 0;
        int green = 0;
        int blue = 0;
        if (alpha > 0) {
            red = std::min(255, b[i * 4 + 2] * 255 / alpha);
            green = std::min(255, b[i * 4 + 1] * 255 / alpha);
            blue = std::min(255, b[i * 4] * 255 / alpha);
        }
        argb[i] = static_cast<int32_t>((static_cast<uint32_t>(alpha) << 24) | (red << 16) | (green << 8) | blue);
    }
    DeleteObject(black);
    DeleteObject(white);
    DeleteDC(dc);
    ReleaseDC(nullptr, screen);
    return true;
}

/** The test's cursorToArgb, packed for kCursor: width, height, hotX, hotY, then ARGB, int32 each, little-endian. */
bool cursorPixels(HCURSOR cursor, std::vector<uint8_t>& out) {
    int width = 0, height = 0, hotX = 0, hotY = 0;
    std::vector<int32_t> argb;
    if (!cursorToArgb(cursor, width, height, hotX, hotY, argb)) return false;
    const int32_t head[4] = {width, height, hotX, hotY};
    out.resize(sizeof(head) + argb.size() * sizeof(int32_t));
    std::memcpy(out.data(), head, sizeof(head));   // Windows is little-endian on every architecture it runs
    std::memcpy(out.data() + sizeof(head), argb.data(), argb.size() * sizeof(int32_t));
    return true;
}

/** The system cursors AWT has a type of its own for (CursorMapping.kt): only their ids travel; any other cursor its pixels. */
bool hasAwtType(UINT32 id) {
    switch (id) {
        case 32512: case 32513: case 32514: case 32515:            // ARROW, IBEAM, WAIT, CROSS
        case 32642: case 32643: case 32644: case 32645: case 32646: // SIZENWSE, SIZENESW, SIZEWE, SIZENS, SIZEALL
        case 32649:                                                 // HAND
            return true;
        default:
            return false;
    }
}

}  // namespace

void WvPage::registerInputEvents() {
    EventRegistrationToken t{};
    controller_->add_GotFocus(WV_HANDLER(ICoreWebView2FocusChangedEventHandler, ICoreWebView2Controller*, IUnknown*, {
        (void)args;
        self->hasFocus_ = true;
        emit(self->token_, kFocus, 0, 0, 1);
    }), &t);
    controller_->add_LostFocus(WV_HANDLER(ICoreWebView2FocusChangedEventHandler, ICoreWebView2Controller*, IUnknown*, {
        (void)args;
        self->hasFocus_ = false;
        emit(self->token_, kFocus, 0, 0, 0);
    }), &t);
    controller_->add_MoveFocusRequested(WV_HANDLER(ICoreWebView2MoveFocusRequestedEventHandler, ICoreWebView2Controller*,
        ICoreWebView2MoveFocusRequestedEventArgs*, {
            COREWEBVIEW2_MOVE_FOCUS_REASON reason = COREWEBVIEW2_MOVE_FOCUS_REASON_NEXT;
            args->get_Reason(&reason);
            args->put_Handled(TRUE);   // Compose moves the focus on (W4)
            emit(self->token_, kMoveFocus, 0, 0, static_cast<int64_t>(reason));
        }), &t);
    composition_->add_CursorChanged(WV_HANDLER(ICoreWebView2CursorChangedEventHandler, ICoreWebView2CompositionController*, IUnknown*, {
        (void)args;
        self->onCursorChanged();
    }), &t);
    controller_->add_AcceleratorKeyPressed(WV_HANDLER(ICoreWebView2AcceleratorKeyPressedEventHandler, ICoreWebView2Controller*,
        ICoreWebView2AcceleratorKeyPressedEventArgs*, { self->onAccelerator(args); }), &t);
}

void WvPage::mouse(int kind, int keys, int data, int x, int y) {
    if (released_ || !composition_) return;
    composition_->SendMouseInput(static_cast<COREWEBVIEW2_MOUSE_EVENT_KIND>(kind), static_cast<COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS>(keys),
                                 static_cast<UINT32>(data), POINT{x, y});
}

void WvPage::moveFocus(int reason) {
    if (!released_ && controller_) controller_->MoveFocus(static_cast<COREWEBVIEW2_MOVE_FOCUS_REASON>(reason));
}

void WvPage::onCursorChanged() {
    UINT32 id = 0;
    composition_->get_SystemCursorId(&id);
    if (id != 0 && hasAwtType(id)) {
        emit(token_, kCursor, 0, 0, id);
        return;
    }
    // A system cursor AWT can't name (IDC_NO, IDC_APPSTARTING, IDC_HELP…) or a CSS url() cursor: its pixels, taken now, as
    // the handle may be freed after this callback (W8).
    HCURSOR cursor = id != 0 ? LoadCursorW(nullptr, MAKEINTRESOURCEW(id)) : nullptr;
    if (!cursor) composition_->get_Cursor(&cursor);
    std::vector<uint8_t> data;
    if (cursor && cursorPixels(cursor, data)) emit(token_, kCursor, 0, 0, 0, {}, {}, {}, &data);
    else emit(token_, kCursor, 0, 0, 32512 /* IDC_ARROW */);
}

/**
 * Accelerators go to the app first (D15): a key with Ctrl, Alt or Win, or a function key, is offered to Compose, which
 * answers within 50 ms (offerKey). A key Compose took never reaches the page, nor does its key-up. Plain keys, Escape and
 * modifiers alone are the page's. F12 and Ctrl+Shift+I open DevTools while the lease is inspectable.
 */
void WvPage::onAccelerator(ICoreWebView2AcceleratorKeyPressedEventArgs* args) {
    COREWEBVIEW2_KEY_EVENT_KIND kind = COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN;
    UINT key = 0;
    COREWEBVIEW2_PHYSICAL_KEY_STATUS status{};
    args->get_KeyEventKind(&kind);
    args->get_VirtualKey(&key);
    args->get_PhysicalKeyStatus(&status);
    if (key == VK_CONTROL || key == VK_SHIFT || key == VK_MENU || key == VK_LWIN || key == VK_RWIN || key == VK_ESCAPE) return;
    const bool down = kind == COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN || kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN;
    if (!down) {
        auto taken = std::find(takenKeys_.begin(), takenKeys_.end(), key);
        if (taken != takenKeys_.end()) {
            takenKeys_.erase(taken);
            args->put_Handled(TRUE);
        }
        return;
    }
    const bool ctrl = GetKeyState(VK_CONTROL) < 0, shift = GetKeyState(VK_SHIFT) < 0, alt = GetKeyState(VK_MENU) < 0;
    const bool win = GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0;
    if ((flags_ & kConfInspectable) && (key == VK_F12 || (ctrl && shift && key == 'I'))) {
        webview_->OpenDevToolsWindow();
        args->put_Handled(TRUE);
        return;
    }
    const bool function = key >= VK_F1 && key <= VK_F24;
    if (!ctrl && !alt && !win && !function) return;
    const int flags = (ctrl ? 1 : 0) | (shift ? 2 : 0) | (alt ? 4 : 0) | (win ? 8 : 0) | (status.WasKeyDown ? 16 : 0);
    if (offerKey(token_, static_cast<int>(key), static_cast<int>(status.ScanCode), flags)) {
        if (std::find(takenKeys_.begin(), takenKeys_.end(), key) == takenKeys_.end()) takenKeys_.push_back(key);
        args->put_Handled(TRUE);
    }
}

}  // namespace wv
