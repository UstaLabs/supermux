#pragma once
#include "common.h"

namespace wv2 {

// What the scenarios read back from the OS, and the OS input they send. Any thread.
std::wstring focusInfo();                     // "focusHwnd|class|pid|foregroundHwnd|foregroundClass"
std::wstring cursorInfo();                    // "name|w|h|hotX|hotY"; name is IDC_* or "custom"
std::wstring standardCursorInfo(int idc);     // "w|h|hotX|hotY" of LoadCursor(IDC_*)
std::wstring topLevelWindows(DWORD pid);      // visible top-level windows (pid 0: all), "hwnd|class|pid|l,t,r,b" per line
std::wstring windowTree(HWND root);           // root and every descendant window, one per line
void sendScan(WORD scan, bool down, bool extended);  // a key by scan code: the active layout turns it into a character
void sendVk(WORD vk, bool down);
std::wstring setInputLanguage(std::wstring const& klid);  // "requestedHkl|hklOfTheFocusedThread"
bool forceFocus(HWND target);                 // SetFocus with the target's input queue attached
bool cursorToArgb(HCURSOR cursor, int& width, int& height, int& hotX, int& hotY, std::vector<int32_t>& argb);

}  // namespace wv2
