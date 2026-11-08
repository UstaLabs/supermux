// devnode: creates a root-enumerated device node with a hardware id and installs an INF's driver on it, which is what
// `devcon install` does; Windows ships no tool that creates a root device. vm/gpu-display.ps1 uses it for the Virtual
// Display Driver (Root\MttVDD), which has no hardware of its own. Idempotent: a node that already has the id only gets
// the driver (re)installed.
//   devnode.exe C:\full\path\MttVDD.inf Root\MttVDD
#include <windows.h>
#include <cfgmgr32.h>
#include <setupapi.h>
#include <newdev.h>
#include <cstdio>
#include <string>

#pragma comment(lib, "setupapi.lib")
#pragma comment(lib, "newdev.lib")

static int fail(const wchar_t* what) {
    const DWORD error = GetLastError();
    fwprintf(stderr, L"devnode: %ls failed: 0x%08lx\n", what, error);
    return 1;
}

// True if some device (present or not) lists hwid among its hardware ids.
static bool exists(std::wstring const& hwid) {
    HDEVINFO set = SetupDiGetClassDevsW(nullptr, nullptr, nullptr, DIGCF_ALLCLASSES);
    if (set == INVALID_HANDLE_VALUE) return false;
    SP_DEVINFO_DATA data{};
    data.cbSize = sizeof(data);
    bool found = false;
    for (DWORD i = 0; !found && SetupDiEnumDeviceInfo(set, i, &data); ++i) {
        wchar_t ids[4096] = L"";
        if (!SetupDiGetDeviceRegistryPropertyW(set, &data, SPDRP_HARDWAREID, nullptr, reinterpret_cast<BYTE*>(ids),
                                               sizeof(ids) - 2 * sizeof(wchar_t), nullptr)) {
            continue;
        }
        for (const wchar_t* id = ids; *id; id += wcslen(id) + 1) {
            if (_wcsicmp(id, hwid.c_str()) == 0) found = true;
        }
    }
    SetupDiDestroyDeviceInfoList(set);
    return found;
}

int wmain(int argc, wchar_t** argv) {
    if (argc != 3) {
        fwprintf(stderr, L"usage: devnode <full path to .inf> <hardware id>\n");
        return 2;
    }
    const std::wstring inf = argv[1];
    const std::wstring hwid = argv[2];
    GUID classGuid{};
    wchar_t className[MAX_CLASS_NAME_LEN] = L"";
    if (!SetupDiGetINFClassW(inf.c_str(), &classGuid, className, MAX_CLASS_NAME_LEN, nullptr)) {
        return fail(L"SetupDiGetINFClass");
    }
    if (exists(hwid)) {
        wprintf(L"devnode: a device with %ls exists already\n", hwid.c_str());
    } else {
        HDEVINFO set = SetupDiCreateDeviceInfoList(&classGuid, nullptr);
        if (set == INVALID_HANDLE_VALUE) return fail(L"SetupDiCreateDeviceInfoList");
        SP_DEVINFO_DATA data{};
        data.cbSize = sizeof(data);
        if (!SetupDiCreateDeviceInfoW(set, className, &classGuid, nullptr, nullptr, DICD_GENERATE_ID, &data)) {
            return fail(L"SetupDiCreateDeviceInfo");
        }
        std::wstring ids = hwid;
        ids.push_back(L'\0');
        ids.push_back(L'\0');  // a REG_MULTI_SZ
        if (!SetupDiSetDeviceRegistryPropertyW(set, &data, SPDRP_HARDWAREID, reinterpret_cast<const BYTE*>(ids.data()),
                                               static_cast<DWORD>(ids.size() * sizeof(wchar_t)))) {
            return fail(L"SetupDiSetDeviceRegistryProperty");
        }
        if (!SetupDiCallClassInstaller(DIF_REGISTERDEVICE, set, &data)) return fail(L"DIF_REGISTERDEVICE");
        SetupDiDestroyDeviceInfoList(set);
        wprintf(L"devnode: created a root device with %ls (class %ls)\n", hwid.c_str(), className);
    }
    BOOL reboot = FALSE;
    if (!UpdateDriverForPlugAndPlayDevicesW(nullptr, hwid.c_str(), inf.c_str(), INSTALLFLAG_FORCE, &reboot)) {
        return fail(L"UpdateDriverForPlugAndPlayDevices");
    }
    wprintf(L"devnode: driver installed from %ls%ls\n", inf.c_str(), reboot ? L"; Windows wants a reboot" : L"");
    return 0;
}
