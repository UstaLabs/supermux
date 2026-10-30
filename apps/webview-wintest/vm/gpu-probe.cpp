// gpu-probe: which adapters D3D sees on the GPU passthrough VM, and which one drives the desktop. For each DXGI adapter
// (default order, then high-performance order): name, vendor/device id, LUID, flags, dedicated VRAM, its outputs (the
// displays it scans out or renders for) and whether a D3D11 and a D3D12 device can be created on it. Built and run by
// vm/gpu-probe.ps1 on tester's desktop (outputs only show in a session that has one).
#include <windows.h>
#include <d3d11.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <cstdio>
#include <string>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "d3d12.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "user32.lib")

template <typename T> void release(T*& p) {
    if (p) p->Release();
    p = nullptr;
}

static const char* level(D3D_FEATURE_LEVEL l) {
    switch (l) {
        case D3D_FEATURE_LEVEL_12_2: return "12_2";
        case D3D_FEATURE_LEVEL_12_1: return "12_1";
        case D3D_FEATURE_LEVEL_12_0: return "12_0";
        case D3D_FEATURE_LEVEL_11_1: return "11_1";
        case D3D_FEATURE_LEVEL_11_0: return "11_0";
        case D3D_FEATURE_LEVEL_10_1: return "10_1";
        case D3D_FEATURE_LEVEL_10_0: return "10_0";
        default: return "older";
    }
}

static void describe(IDXGIAdapter1* adapter, UINT index) {
    DXGI_ADAPTER_DESC1 d{};
    adapter->GetDesc1(&d);
    wprintf(L"  [%u] %ls  ven 0x%04x dev 0x%04x  luid 0x%08lx:0x%08lx  vram %llu MB%ls\n", index, d.Description,
            d.VendorId, d.DeviceId, static_cast<unsigned long>(d.AdapterLuid.HighPart),
            static_cast<unsigned long>(d.AdapterLuid.LowPart),
            static_cast<unsigned long long>(d.DedicatedVideoMemory / (1024 * 1024)),
            (d.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) ? L"  SOFTWARE" : L"");
    IDXGIOutput* output = nullptr;
    for (UINT o = 0; adapter->EnumOutputs(o, &output) != DXGI_ERROR_NOT_FOUND; ++o) {
        DXGI_OUTPUT_DESC od{};
        output->GetDesc(&od);
        MONITORINFOEXW mi{};
        mi.cbSize = sizeof(mi);
        GetMonitorInfoW(od.Monitor, &mi);
        wprintf(L"      output %u: %ls  %ldx%ld at %ld,%ld  desktop=%d%ls\n", o, od.DeviceName,
                od.DesktopCoordinates.right - od.DesktopCoordinates.left,
                od.DesktopCoordinates.bottom - od.DesktopCoordinates.top, od.DesktopCoordinates.left,
                od.DesktopCoordinates.top, od.AttachedToDesktop, (mi.dwFlags & MONITORINFOF_PRIMARY) ? L"  PRIMARY" : L"");
        release(output);
    }
    ID3D11Device* device = nullptr;
    D3D_FEATURE_LEVEL got{};
    HRESULT hr = D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0,
                                   D3D11_SDK_VERSION, &device, &got, nullptr);
    wprintf(L"      D3D11: %ls", SUCCEEDED(hr) ? L"ok, feature level " : L"FAILED ");
    if (SUCCEEDED(hr)) printf("%s\n", level(got)); else wprintf(L"0x%08lx\n", static_cast<unsigned long>(hr));
    release(device);
    ID3D12Device* d12 = nullptr;
    hr = D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&d12));
    if (SUCCEEDED(hr)) {
        D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_12_2, D3D_FEATURE_LEVEL_12_1, D3D_FEATURE_LEVEL_12_0,
                                      D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
        D3D12_FEATURE_DATA_FEATURE_LEVELS fl{_countof(levels), levels, D3D_FEATURE_LEVEL_11_0};
        d12->CheckFeatureSupport(D3D12_FEATURE_FEATURE_LEVELS, &fl, sizeof(fl));
        printf("      D3D12: ok, max feature level %s\n", level(fl.MaxSupportedFeatureLevel));
    } else {
        wprintf(L"      D3D12: FAILED 0x%08lx\n", static_cast<unsigned long>(hr));
    }
    release(d12);
}

int main() {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);  // real pixels and the real DPI
    IDXGIFactory6* factory = nullptr;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) {
        printf("gpu-probe: no IDXGIFactory6\n");
        return 1;
    }
    printf("DXGI adapters, default order (adapter 0 is what D3D11CreateDevice(nullptr, HARDWARE) and Chromium use):\n");
    IDXGIAdapter1* adapter = nullptr;
    for (UINT i = 0; factory->EnumAdapters1(i, &adapter) != DXGI_ERROR_NOT_FOUND; ++i) {
        describe(adapter, i);
        release(adapter);
    }
    printf("High-performance order (EnumAdapterByGpuPreference; skiko's 'discrete'):\n");
    for (UINT i = 0; SUCCEEDED(factory->EnumAdapterByGpuPreference(i, DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,
                                                                   IID_PPV_ARGS(&adapter))); ++i) {
        DXGI_ADAPTER_DESC1 d{};
        adapter->GetDesc1(&d);
        wprintf(L"  [%u] %ls\n", i, d.Description);
        release(adapter);
    }
    printf("Displays (EnumDisplayDevices):\n");
    DISPLAY_DEVICEW dd{};
    dd.cb = sizeof(dd);
    for (DWORD i = 0; EnumDisplayDevicesW(nullptr, i, &dd, 0); ++i) {
        DEVMODEW mode{};
        mode.dmSize = sizeof(mode);
        const bool active = (dd.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) != 0;
        if (active) EnumDisplaySettingsW(dd.DeviceName, ENUM_CURRENT_SETTINGS, &mode);
        wprintf(L"  %ls  %ls%ls%ls", dd.DeviceName, dd.DeviceString, active ? L"  ACTIVE" : L"",
                (dd.StateFlags & DISPLAY_DEVICE_PRIMARY_DEVICE) ? L"  PRIMARY" : L"");
        if (active) wprintf(L"  %lux%lu @ %lu Hz", mode.dmPelsWidth, mode.dmPelsHeight, mode.dmDisplayFrequency);
        wprintf(L"\n");
        dd.cb = sizeof(dd);
    }
    printf("System DPI: %u\n", GetDpiForSystem());
    release(factory);
    return 0;
}
