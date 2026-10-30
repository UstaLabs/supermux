#include "capture.h"
#include "ui_thread.h"

namespace wv {
namespace {

using LuidKey = std::pair<DWORD, LONG>;   // an adapter's LUID (LowPart, HighPart)

std::mutex g_devicesLock;
std::map<LuidKey, std::shared_ptr<GpuDevice>> g_byLuid;   // by the adapter's LUID
std::map<HMONITOR, LuidKey> g_monitorLuid;                 // forgotten at WM_DISPLAYCHANGE
std::shared_ptr<GpuDevice> g_fallback;                     // for a monitor no adapter has an output on

LuidKey keyOf(LUID luid) { return {luid.LowPart, luid.HighPart}; }

std::wstring luidText(LUID luid) {
    wchar_t text[32];
    swprintf_s(text, L"0x%08lx:0x%08lx", static_cast<unsigned long>(luid.HighPart), static_cast<unsigned long>(luid.LowPart));
    return text;
}

/** The adapter with an output on [monitor] (where DWM composes it), or null: no such output (a session with no desktop). */
winrt::com_ptr<IDXGIAdapter1> adapterOf(HMONITOR monitor) {
    winrt::com_ptr<IDXGIFactory1> factory;
    if (!monitor || FAILED(CreateDXGIFactory1(IID_PPV_ARGS(factory.put())))) return nullptr;
    for (UINT a = 0;; ++a) {
        winrt::com_ptr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(a, adapter.put()) == DXGI_ERROR_NOT_FOUND) return nullptr;
        for (UINT o = 0;; ++o) {
            winrt::com_ptr<IDXGIOutput> output;
            if (adapter->EnumOutputs(o, output.put()) == DXGI_ERROR_NOT_FOUND) break;
            DXGI_OUTPUT_DESC desc{};
            if (SUCCEEDED(output->GetDesc(&desc)) && desc.Monitor == monitor) return adapter;
        }
    }
}

/** The adapter whose LUID is [luid], or null: it went away. */
winrt::com_ptr<IDXGIAdapter1> adapterByLuid(LUID luid) {
    winrt::com_ptr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(factory.put())))) return nullptr;
    for (UINT a = 0;; ++a) {
        winrt::com_ptr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(a, adapter.put()) == DXGI_ERROR_NOT_FOUND) return nullptr;
        DXGI_ADAPTER_DESC1 desc{};
        if (SUCCEEDED(adapter->GetDesc1(&desc)) && desc.AdapterLuid.LowPart == luid.LowPart &&
            desc.AdapterLuid.HighPart == luid.HighPart) {
            return adapter;
        }
    }
}

}  // namespace

std::shared_ptr<GpuDevice> GpuDevices::make(IDXGIAdapter1* adapter) {
    auto gpu = std::make_shared<GpuDevice>();
    const UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    HRESULT hr = E_FAIL;
    if (adapter) {
        hr = D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, nullptr, flags, nullptr, 0, D3D11_SDK_VERSION,
                               gpu->device_.put(), nullptr, gpu->context_.put());
        if (FAILED(hr)) logLine(L"no D3D11 device on the monitor's adapter " + hex(hr) + L": the default adapter's");
    }
    if (FAILED(hr)) {
        gpu->device_ = nullptr;
        gpu->context_ = nullptr;
        hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, nullptr, 0, D3D11_SDK_VERSION,
                               gpu->device_.put(), nullptr, gpu->context_.put());
    }
    if (FAILED(hr)) {
        gpu->device_ = nullptr;
        gpu->context_ = nullptr;
        hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, flags, nullptr, 0, D3D11_SDK_VERSION,
                               gpu->device_.put(), nullptr, gpu->context_.put());
    }
    if (FAILED(hr)) {
        logLine(L"D3D11CreateDevice failed " + hex(hr));
        return nullptr;
    }
    // Defence in depth behind contextLock_: capture threads and the EDT both use the immediate context.
    if (auto multithread = gpu->context_.try_as<ID3D11Multithread>()) multithread->SetMultithreadProtected(TRUE);
    auto dxgiDevice = gpu->device_.as<IDXGIDevice>();
    winrt::com_ptr<IDXGIAdapter> used;
    if (SUCCEEDED(dxgiDevice->GetAdapter(used.put()))) {
        DXGI_ADAPTER_DESC desc{};
        if (SUCCEEDED(used->GetDesc(&desc))) {
            gpu->name_ = desc.Description;
            gpu->luid_ = desc.AdapterLuid;
        }
        // The Basic Render Driver is an adapter too: what makes it WARP is the software flag, not the driver type asked.
        DXGI_ADAPTER_DESC1 desc1{};
        if (auto used1 = used.try_as<IDXGIAdapter1>(); used1 && SUCCEEDED(used1->GetDesc1(&desc1))) {
            gpu->hardware_ = (desc1.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) == 0;
        }
    }
    winrt::com_ptr<::IInspectable> inspectable;
    hr = CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice.get(), inspectable.put());
    if (FAILED(hr)) {
        logLine(L"CreateDirect3D11DeviceFromDXGIDevice failed " + hex(hr));
        return nullptr;
    }
    gpu->rtDevice_ = inspectable.as<winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice>();
    gpu->device5_ = gpu->device_.try_as<ID3D11Device5>();
    gpu->context4_ = gpu->context_.try_as<ID3D11DeviceContext4>();
    logLine(L"capture device " + gpu->name() + L" (luid " + luidText(gpu->luid_) + L"), fences " + (gpu->device5_ ? L"yes" : L"no"));
    return gpu;
}

std::shared_ptr<GpuDevice> GpuDevices::forMonitor(HMONITOR monitor) {
    std::lock_guard<std::mutex> lock(g_devicesLock);
    if (auto known = g_monitorLuid.find(monitor); known != g_monitorLuid.end()) {
        if (auto it = g_byLuid.find(known->second); it != g_byLuid.end()) return it->second;
    }
    winrt::com_ptr<IDXGIAdapter1> adapter = adapterOf(monitor);
    DXGI_ADAPTER_DESC1 desc{};
    if (!adapter || FAILED(adapter->GetDesc1(&desc))) {
        // No adapter has an output on it (a session without a desktop): one device for all such, made once.
        if (!g_fallback) g_fallback = make(nullptr);
        return g_fallback;
    }
    const LuidKey key = keyOf(desc.AdapterLuid);
    g_monitorLuid[monitor] = key;
    if (auto it = g_byLuid.find(key); it != g_byLuid.end()) return it->second;
    auto device = make(adapter.get());
    // Kept under the adapter asked for even if it fell back to another: the failing adapter isn't tried at every page.
    if (device) g_byLuid[key] = device;
    return device;
}

std::shared_ptr<GpuDevice> GpuDevices::forWindow(HWND window) {
    return forMonitor(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST));
}

std::shared_ptr<GpuDevice> GpuDevices::primary() {
    return forMonitor(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY));
}

std::shared_ptr<GpuDevice> GpuDevices::replace(GpuDevice const& lost) {
    std::lock_guard<std::mutex> lock(g_devicesLock);
    // The same adapter if it is still there (a driver reset); the default, then WARP, if it went away.
    winrt::com_ptr<IDXGIAdapter1> adapter = adapterByLuid(lost.luid());
    auto device = make(adapter.get());
    for (auto it = g_byLuid.begin(); it != g_byLuid.end();) {
        if (it->second.get() != &lost) {
            ++it;
        } else if (device) {
            it->second = device;
            ++it;
        } else {
            it = g_byLuid.erase(it);
        }
    }
    if (g_fallback.get() == &lost) g_fallback = device;
    return device;
}

void GpuDevices::displaysChanged() {
    std::lock_guard<std::mutex> lock(g_devicesLock);
    // Devices stay: an adapter that went away loses its device, and lost() handles that.
    g_monitorLuid.clear();
}

// Task 10 moves the captures; until then a lost device is only reported.
void GpuDevice::lost() {
    if (lost_.exchange(true)) return;
    logLine(L"the capture device " + name() + L" was lost");
}

}  // namespace wv
