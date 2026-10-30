#pragma once
#include "wv2.h"
#include <d3d11_4.h>
#include <dxgi1_6.h>

namespace wv {

/** One adapter's D3D11 device: its pages' frame pools, staging textures and fences (D3, D24). */
class GpuDevice : public std::enable_shared_from_this<GpuDevice> {
public:
    ID3D11Device* device() const { return device_.get(); }
    ID3D11DeviceContext* context() const { return context_.get(); }
    /** D3D11.4's fences (W23); null on a driver without them: the copy's wait then polls an event query. */
    ID3D11Device5* device5() const { return device5_.get(); }
    ID3D11DeviceContext4* context4() const { return context4_.get(); }
    winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice rtDevice() const { return rtDevice_; }
    /** Held around every call on the immediate context, from any thread. */
    std::mutex& contextLock() { return contextLock_; }
    LUID luid() const { return luid_; }
    std::wstring name() const { return name_ + (hardware_ ? L"" : L" (WARP)"); }
    /** A call failed with DXGI_ERROR_DEVICE_REMOVED or _RESET: the UI thread makes this adapter's device again and moves
     *  every capture on it there (Task 10). */
    void lost();

private:
    friend class GpuDevices;
    winrt::com_ptr<ID3D11Device> device_;
    winrt::com_ptr<ID3D11DeviceContext> context_;
    winrt::com_ptr<ID3D11Device5> device5_;
    winrt::com_ptr<ID3D11DeviceContext4> context4_;
    winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice rtDevice_{nullptr};
    std::mutex contextLock_;
    std::wstring name_;
    LUID luid_{};
    bool hardware_ = false;
    std::atomic<bool> lost_{false};
};

/** The process's devices, one per adapter, made on first use and kept (a few MB each). Thread-safe. */
class GpuDevices {
public:
    /** The device of the adapter driving [window]'s monitor; the default hardware adapter's, then WARP, when none does. */
    static std::shared_ptr<GpuDevice> forWindow(HWND window);
    /** The primary monitor's: what start() checks (kStartNoDevice) and deviceName() names. */
    static std::shared_ptr<GpuDevice> primary();
    /** [lost]'s adapter made again (UI thread); null if it can't be. */
    static std::shared_ptr<GpuDevice> replace(GpuDevice const& lost);
    /** Monitors or adapters changed (WM_DISPLAYCHANGE): which adapter drives which monitor is looked up again. */
    static void displaysChanged();

private:
    static std::shared_ptr<GpuDevice> forMonitor(HMONITOR monitor);
    /** A device on [adapter] (null: the default hardware adapter, then WARP), as the test's FrameCapture::init made one. */
    static std::shared_ptr<GpuDevice> make(IDXGIAdapter1* adapter);
};

}  // namespace wv
