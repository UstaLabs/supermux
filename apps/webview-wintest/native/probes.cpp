#include "probes.h"

namespace wv2 {
namespace {

std::wstring classOf(HWND window) {
    wchar_t name[256] = L"";
    if (window) GetClassNameW(window, name, 256);
    return name;
}

std::wstring rectOf(HWND window) {
    RECT r{};
    GetWindowRect(window, &r);
    return std::to_wstring(r.left) + L"," + std::to_wstring(r.top) + L"," + std::to_wstring(r.right) + L"," +
           std::to_wstring(r.bottom);
}

struct NamedCursor {
    LPCWSTR id;
    const wchar_t* name;
};

const NamedCursor kCursors[] = {
    {IDC_ARROW, L"IDC_ARROW"},       {IDC_IBEAM, L"IDC_IBEAM"},     {IDC_WAIT, L"IDC_WAIT"},
    {IDC_CROSS, L"IDC_CROSS"},       {IDC_UPARROW, L"IDC_UPARROW"}, {IDC_SIZENWSE, L"IDC_SIZENWSE"},
    {IDC_SIZENESW, L"IDC_SIZENESW"}, {IDC_SIZEWE, L"IDC_SIZEWE"},   {IDC_SIZENS, L"IDC_SIZENS"},
    {IDC_SIZEALL, L"IDC_SIZEALL"},   {IDC_NO, L"IDC_NO"},           {IDC_HAND, L"IDC_HAND"},
    {IDC_APPSTARTING, L"IDC_APPSTARTING"}, {IDC_HELP, L"IDC_HELP"},
};

// "w|h|hotX|hotY" of a cursor, from its icon bitmaps.
std::wstring cursorShape(HCURSOR cursor) {
    ICONINFO info{};
    if (!cursor || !GetIconInfo(cursor, &info)) return L"0|0|0|0";
    BITMAP bitmap{};
    int width = 0;
    int height = 0;
    HBITMAP source = info.hbmColor ? info.hbmColor : info.hbmMask;
    if (source && GetObjectW(source, sizeof(bitmap), &bitmap)) {
        width = bitmap.bmWidth;
        height = info.hbmColor ? bitmap.bmHeight : bitmap.bmHeight / 2;
    }
    if (info.hbmColor) DeleteObject(info.hbmColor);
    if (info.hbmMask) DeleteObject(info.hbmMask);
    return std::to_wstring(width) + L"|" + std::to_wstring(height) + L"|" + std::to_wstring(info.xHotspot) + L"|" +
           std::to_wstring(info.yHotspot);
}

}  // namespace

std::wstring focusInfo() {
    HWND foreground = GetForegroundWindow();
    GUITHREADINFO gui{};
    gui.cbSize = sizeof(gui);
    HWND focus = nullptr;
    if (foreground && GetGUIThreadInfo(GetWindowThreadProcessId(foreground, nullptr), &gui)) focus = gui.hwndFocus;
    DWORD pid = 0;
    if (focus) GetWindowThreadProcessId(focus, &pid);
    return hex(reinterpret_cast<int64_t>(focus)) + L"|" + classOf(focus) + L"|" + std::to_wstring(pid) + L"|" +
           hex(reinterpret_cast<int64_t>(foreground)) + L"|" + classOf(foreground);
}

std::wstring cursorInfo() {
    CURSORINFO info{};
    info.cbSize = sizeof(info);
    if (!GetCursorInfo(&info)) return L"none|0|0|0|0";
    std::wstring name = L"custom";
    for (auto const& cursor : kCursors) {
        if (info.hCursor == LoadCursorW(nullptr, cursor.id)) {
            name = cursor.name;
            break;
        }
    }
    return name + L"|" + cursorShape(info.hCursor);
}

std::wstring standardCursorInfo(int idc) { return cursorShape(LoadCursorW(nullptr, MAKEINTRESOURCEW(idc))); }

std::wstring topLevelWindows(DWORD pid) {
    struct Context {
        DWORD pid;
        std::wstring out;
    } context{pid, L""};
    EnumWindows(
        [](HWND window, LPARAM parameter) -> BOOL {
            auto* c = reinterpret_cast<Context*>(parameter);
            DWORD owner = 0;
            GetWindowThreadProcessId(window, &owner);
            if (!IsWindowVisible(window) || (c->pid != 0 && owner != c->pid)) return TRUE;
            c->out += hex(reinterpret_cast<int64_t>(window)) + L"|" + classOf(window) + L"|" + std::to_wstring(owner) +
                      L"|" + rectOf(window) + L"\n";
            return TRUE;
        },
        reinterpret_cast<LPARAM>(&context));
    return context.out;
}

std::wstring windowTree(HWND root) {
    std::wstring out =
        L"root " + hex(reinterpret_cast<int64_t>(root)) + L"|" + classOf(root) + L"|" + rectOf(root) + L"\n";
    EnumChildWindows(
        root,
        [](HWND window, LPARAM parameter) -> BOOL {
            auto* text = reinterpret_cast<std::wstring*>(parameter);
            DWORD pid = 0;
            GetWindowThreadProcessId(window, &pid);
            *text += L"  child " + hex(reinterpret_cast<int64_t>(window)) + L" parent=" +
                     hex(reinterpret_cast<int64_t>(GetParent(window))) + L"|" + classOf(window) + L"|pid=" +
                     std::to_wstring(pid) + L"|" + rectOf(window) + L"|visible=" +
                     (IsWindowVisible(window) ? L"1" : L"0") + L"|style=" +
                     hex(static_cast<uint32_t>(GetWindowLongW(window, GWL_STYLE))) + L"|exstyle=" +
                     hex(static_cast<uint32_t>(GetWindowLongW(window, GWL_EXSTYLE))) + L"\n";
            return TRUE;
        },
        reinterpret_cast<LPARAM>(&out));
    return out;
}

void sendScan(WORD scan, bool down, bool extended) {
    INPUT input{};
    input.type = INPUT_KEYBOARD;
    input.ki.wScan = scan;
    input.ki.dwFlags = KEYEVENTF_SCANCODE | (down ? 0 : KEYEVENTF_KEYUP) | (extended ? KEYEVENTF_EXTENDEDKEY : 0);
    SendInput(1, &input, sizeof(INPUT));
}

void sendVk(WORD vk, bool down) {
    INPUT input{};
    input.type = INPUT_KEYBOARD;
    input.ki.wVk = vk;
    input.ki.wScan = static_cast<WORD>(MapVirtualKeyW(vk, MAPVK_VK_TO_VSC));
    input.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
    SendInput(1, &input, sizeof(INPUT));
}

std::wstring setInputLanguage(std::wstring const& klid) {
    HKL requested = LoadKeyboardLayoutW(klid.c_str(), KLF_ACTIVATE | KLF_SUBSTITUTE_OK);
    HWND foreground = GetForegroundWindow();
    if (foreground && requested) {
        PostMessageW(foreground, WM_INPUTLANGCHANGEREQUEST, 0, reinterpret_cast<LPARAM>(requested));
    }
    Sleep(300);
    GUITHREADINFO gui{};
    gui.cbSize = sizeof(gui);
    const DWORD thread = foreground ? GetWindowThreadProcessId(foreground, nullptr) : 0;
    HWND focus = (thread && GetGUIThreadInfo(thread, &gui)) ? gui.hwndFocus : nullptr;
    const DWORD focusThread = focus ? GetWindowThreadProcessId(focus, nullptr) : thread;
    return hex(reinterpret_cast<int64_t>(requested)) + L"|" + hex(reinterpret_cast<int64_t>(GetKeyboardLayout(focusThread)));
}

bool forceFocus(HWND target) {
    if (!IsWindow(target)) return false;
    const DWORD self = GetCurrentThreadId();
    const DWORD owner = GetWindowThreadProcessId(target, nullptr);
    const bool attached = owner != self && AttachThreadInput(self, owner, TRUE);
    SetFocus(target);
    if (attached) AttachThreadInput(self, owner, FALSE);
    GUITHREADINFO gui{};
    gui.cbSize = sizeof(gui);
    return GetGUIThreadInfo(owner, &gui) && gui.hwndFocus == target;
}

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

}  // namespace wv2
