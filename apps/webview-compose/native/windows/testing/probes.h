#pragma once
// The sample's test hooks (webview_windows_testing.dll, plan Task 27): what the checks read back from the OS, and the OS
// input they send as a user's. Never in the library's DLL. The probes are apps/webview-wintest/native/probes.* (the
// feasibility test keeps its own copy), with this header standing alone and sendText added.
#include <windows.h>
#include <algorithm>
#include <cstdint>
#include <cwchar>
#include <string>
#include <vector>

namespace wv2 {

inline std::wstring hex(int64_t value) {
    wchar_t buffer[32];
    swprintf_s(buffer, L"0x%llx", static_cast<unsigned long long>(value));
    return buffer;
}

// What the scenarios read back from the OS, and the OS input they send. Any thread.
std::wstring focusInfo();                     // "focusHwnd|class|pid|foregroundHwnd|foregroundClass"
std::wstring cursorInfo();                    // "name|w|h|hotX|hotY"; name is IDC_* or "custom"
std::wstring standardCursorInfo(int idc);     // "w|h|hotX|hotY" of LoadCursor(IDC_*)
std::wstring topLevelWindows(DWORD pid);      // visible top-level windows (pid 0: all), "hwnd|class|pid|l,t,r,b" per line
std::wstring windowTree(HWND root);           // root and every descendant window, one per line
void sendText(std::wstring const& text);      // text as typed, one UTF-16 unit at a time (VK_PACKET): any layout
void sendScan(WORD scan, bool down, bool extended);  // a key by scan code: the active layout turns it into a character
void sendVk(WORD vk, bool down);
std::wstring setInputLanguage(std::wstring const& klid);  // "requestedHkl|hklOfTheFocusedThread"
bool forceFocus(HWND target);                 // SetFocus with the target's input queue attached
// The focused window's IME through its default IME window (WM_IME_CONTROL): open < 0 or conversion < 0 leaves that
// setting alone. Returns "open|conversionMode|focusClass" as read back afterwards, or "no-ime-window".
std::wstring imeControl(int open, int conversion);
bool cursorToArgb(HCURSOR cursor, int& width, int& height, int& hotX, int& hotY, std::vector<int32_t>& argb);

}  // namespace wv2
