#include "capture.h"
#include "page.h"
#include "ui_thread.h"

namespace wv {
namespace {

using LuidKey = std::pair<DWORD, LONG>;   // an adapter's LUID (LowPart, HighPart)

/** The process's devices. Never destroyed: no D3D object is released by a static destructor at exit. */
struct Devices {
    std::mutex lock;
    std::map<LuidKey, std::shared_ptr<GpuDevice>> byLuid;   // by the adapter's LUID
    std::map<HMONITOR, LuidKey> monitorLuid;                 // forgotten at WM_DISPLAYCHANGE
    std::shared_ptr<GpuDevice> fallback;                     // for a monitor no adapter has an output on
};
Devices& devices() {
    static Devices* instance = new Devices();
    return *instance;
}

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
    Devices& d = devices();
    std::lock_guard<std::mutex> lock(d.lock);
    if (auto known = d.monitorLuid.find(monitor); known != d.monitorLuid.end()) {
        if (auto it = d.byLuid.find(known->second); it != d.byLuid.end()) return it->second;
    }
    winrt::com_ptr<IDXGIAdapter1> adapter = adapterOf(monitor);
    DXGI_ADAPTER_DESC1 desc{};
    if (!adapter || FAILED(adapter->GetDesc1(&desc))) {
        // No adapter has an output on it (a session without a desktop): one device for all such, made once.
        if (!d.fallback) d.fallback = make(nullptr);
        return d.fallback;
    }
    const LuidKey key = keyOf(desc.AdapterLuid);
    d.monitorLuid[monitor] = key;
    if (auto it = d.byLuid.find(key); it != d.byLuid.end()) return it->second;
    auto device = make(adapter.get());
    // Kept under the adapter asked for even if it fell back to another: the failing adapter isn't tried at every page.
    if (device) d.byLuid[key] = device;
    return device;
}

std::shared_ptr<GpuDevice> GpuDevices::forWindow(HWND window) {
    return forMonitor(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST));
}

std::shared_ptr<GpuDevice> GpuDevices::primary() {
    return forMonitor(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY));
}

std::shared_ptr<GpuDevice> GpuDevices::replace(GpuDevice const& lost) {
    Devices& d = devices();
    std::lock_guard<std::mutex> lock(d.lock);
    // The same adapter if it is still there (a driver reset); the default, then WARP, if it went away.
    winrt::com_ptr<IDXGIAdapter1> adapter = adapterByLuid(lost.luid());
    auto device = make(adapter.get());
    for (auto it = d.byLuid.begin(); it != d.byLuid.end();) {
        if (it->second.get() != &lost) {
            ++it;
        } else if (device) {
            it->second = device;
            ++it;
        } else {
            it = d.byLuid.erase(it);
        }
    }
    if (d.fallback.get() == &lost) d.fallback = device;
    return device;
}

void GpuDevices::displaysChanged() {
    Devices& d = devices();
    std::lock_guard<std::mutex> lock(d.lock);
    // Devices stay: an adapter that went away loses its device, and lost() handles that.
    d.monitorLuid.clear();
}

// A call failed with DXGI_ERROR_DEVICE_REMOVED or _RESET (any thread): the UI thread makes the adapter's device again
// and moves every capture on it there; pages left without a device are reported failed (kFailedNoDevice).
void GpuDevice::lost() {
    if (lost_.exchange(true)) return;
    auto self = shared_from_this();
    logLine(L"the capture device " + name() + L" was lost: making it again");
    UiThread::get().post([self] {
        auto replacement = GpuDevices::replace(*self);
        logLine(replacement ? L"capture device made again: " + replacement->name() : std::wstring(L"no capture device could be made"));
        Pages::forEach([&](WvPage& page) {
            if (page.captureDevice() != self) return;
            if (replacement) page.rebindCapture(replacement);
            else page.reportNoDevice();
        });
    });
}

// --- FrameCapture (D3, W22, W23): the gate's three-copy chain, with a fence, a device per adapter, the state shared ---

namespace wgc = winrt::Windows::Graphics::Capture;
namespace wgdx = winrt::Windows::Graphics::DirectX;
constexpr auto kFormat = wgdx::DirectXPixelFormat::B8G8R8A8UIntNormalized;
constexpr DWORD kCopyWaitMs = 500;   // a staging copy takes ~1–5 ms (W22): past this, the GPU is hung or gone

namespace {
/**
 * The capture thread's wait for its own copy (W23): an ID3D11Fence signalled right after the copy and waited on without
 * the context lock; on a driver without D3D11.4's fences, an event query polled, as the gate's prototype did.
 */
class CopyFence {
public:
    /** For [gpu]'s device; false if neither a fence nor a query could be made. */
    bool init(GpuDevice& gpu) {
        fence_ = nullptr;
        query_ = nullptr;
        value_ = 0;
        if (gpu.device5() && gpu.context4() &&
            SUCCEEDED(gpu.device5()->CreateFence(0, D3D11_FENCE_FLAG_NONE, IID_PPV_ARGS(fence_.put())))) {
            return true;
        }
        fence_ = nullptr;
        const D3D11_QUERY_DESC desc{D3D11_QUERY_EVENT, 0};
        return SUCCEEDED(gpu.device()->CreateQuery(&desc, query_.put()));
    }
    /** With the context lock held, right after the copy: marks its end, and starts the GPU on it. */
    void mark(GpuDevice& gpu) {
        if (fence_) {
            gpu.context4()->Signal(fence_.get(), ++value_);
            fence_->SetEventOnCompletion(value_, event_.get());
        } else {
            gpu.context()->End(query_.get());
        }
        gpu.context()->Flush();
    }
    /** Without the context lock: true once the copy is done, false after [millis]. A removed device counts as done. */
    bool wait(GpuDevice& gpu, DWORD millis) {
        const ULONGLONG end = GetTickCount64() + millis;
        if (fence_) {
            while (fence_->GetCompletedValue() < value_) {   // UINT64_MAX once the device is removed
                const ULONGLONG now = GetTickCount64();
                if (now >= end) return false;
                WaitForSingleObject(event_.get(), static_cast<DWORD>(end - now));
            }
            return true;
        }
        for (;;) {
            BOOL done = FALSE;
            {
                std::lock_guard<std::mutex> context(gpu.contextLock());
                const HRESULT hr = gpu.context()->GetData(query_.get(), &done, sizeof(done), 0);
                if ((hr == S_OK && done) || FAILED(hr)) return true;   // done, or the device is gone (checked next)
            }
            if (GetTickCount64() >= end) return false;
            SwitchToThread();
        }
    }
    bool usesFence() const { return fence_ != nullptr; }

private:
    winrt::com_ptr<ID3D11Fence> fence_;
    winrt::com_ptr<ID3D11Query> query_;
    winrt::handle event_{CreateEventW(nullptr, FALSE, FALSE, nullptr)};
    uint64_t value_ = 0;
};
}  // namespace

struct FrameCapture::State {
    std::mutex frameMutex;   // the pool, the session, the texture being written: start, resize, rebind, stop, each arrival
    std::mutex mutex;        // what pull reads: the device, which texture is ready, the damage, the size, the sequence
    std::shared_ptr<GpuDevice> device;   // the window's monitor's adapter (D24); replaced by rebind
    wgc::GraphicsCaptureItem item{nullptr};
    wgc::Direct3D11CaptureFramePool pool{nullptr};
    wgc::GraphicsCaptureSession session{nullptr};
    winrt::event_token arrived{};
    winrt::Windows::Graphics::SizeInt32 poolSize{0, 0};
    winrt::com_ptr<ID3D11Texture2D> staging[2];   // written by turns (W23)
    StagingTurns turns;                            // which one is written, which is ready, what each missed (logic.h)
    CopyFence copied;                              // the capture thread's wait for its own copy
    int width = 0, height = 0;
    Damage damage;                                 // changed since the EDT's last pull
    bool dirtyApi = false, needWhole = true, started = false;
    std::atomic<int64_t> seq{0};
    int64_t arrivedNs = 0, stillDrawing = 0;
    std::atomic<bool> signalPending{false};        // one "frame ready" in flight at a time
    std::function<void(int64_t)> onFrame;          // runs on the UI thread (postSignal)
};

namespace {
/** Tells the UI thread a frame is ready, at most one signal in flight; the post holds the state, never `this`. */
void postSignal(std::shared_ptr<FrameCapture::State> const& s) {
    if (s->signalPending.exchange(true)) return;
    if (!UiThread::get().post([s] {
            s->signalPending = false;
            if (s->onFrame) s->onFrame(s->seq.load());
        })) {
        s->signalPending = false;
    }
}

winrt::com_ptr<ID3D11Texture2D> createStaging(GpuDevice& gpu, int width, int height) {
    D3D11_TEXTURE2D_DESC desc{};
    desc.Width = static_cast<UINT>(width);
    desc.Height = static_cast<UINT>(height);
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_STAGING;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    winrt::com_ptr<ID3D11Texture2D> staging;
    winrt::check_hresult(gpu.device()->CreateTexture2D(&desc, nullptr, staging.put()));
    return staging;
}

RECT unite(RECT a, RECT b) {
    return RECT{std::min(a.left, b.left), std::min(a.top, b.top), std::max(a.right, b.right), std::max(a.bottom, b.bottom)};
}

bool removed(GpuDevice& gpu) { return FAILED(gpu.device()->GetDeviceRemovedReason()); }

/** One frame, on WGC's thread pool (W23): copied into the texture being written, waited for, then published. */
void onArrived(std::shared_ptr<FrameCapture::State> const& s, wgc::Direct3D11CaptureFramePool const& sender) {
    std::wstring note;   // logged after the locks
    {
        std::lock_guard<std::mutex> frameLock(s->frameMutex);
        if (!s->started) return;
        auto frame = sender.TryGetNextFrame();
        if (!frame) return;
        const int64_t arrived = qpcNanos();   // W7: arrival, never SystemRelativeTime
        winrt::com_ptr<ID3D11Texture2D> texture;
        auto access = frame.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
        winrt::check_hresult(access->GetInterface(IID_PPV_ARGS(texture.put())));
        D3D11_TEXTURE2D_DESC desc{};
        texture->GetDesc(&desc);
        const auto content = frame.ContentSize();
        // The pool's textures can be larger than the content (after a shrink): only the content counts.
        const int width = std::min<int>(content.Width, static_cast<int>(desc.Width));
        const int height = std::min<int>(content.Height, static_cast<int>(desc.Height));
        if (width <= 0 || height <= 0) return;
        GpuDevice& gpu = *s->device;   // only rebind changes it, under frameMutex
        const bool resized = width != s->width || height != s->height || !s->staging[0];
        // What changed: WGC's regions. WebView2's are the whole frame every time (W24); others are honoured.
        RECT changed{0, 0, width, height};
        if (!resized && !s->needWhole && s->dirtyApi) {
            bool any = false;
            try {
                for (auto const& r : frame.DirtyRegions()) {
                    const RECT c{std::max(0, r.X), std::max(0, r.Y), std::min(width, r.X + r.Width), std::min(height, r.Y + r.Height)};
                    if (c.right <= c.left || c.bottom <= c.top) continue;
                    changed = any ? unite(changed, c) : c;
                    any = true;
                }
            } catch (winrt::hresult_error const&) {
                s->dirtyApi = false;
                any = true;
                changed = RECT{0, 0, width, height};
            }
            if (!any) return;   // nothing changed in it
        }
        if (resized) {
            auto first = createStaging(gpu, width, height);
            auto second = createStaging(gpu, width, height);
            std::lock_guard<std::mutex> context(gpu.contextLock());   // the EDT may be mapping the old ones
            std::lock_guard<std::mutex> lock(s->mutex);
            s->staging[0] = first;
            s->staging[1] = second;
            s->turns.reset(width, height);
            s->width = width;
            s->height = height;
            s->damage.whole(width, height);
            changed = RECT{0, 0, width, height};
        }
        const int writing = s->turns.writing();   // never the texture the EDT may map: that one is ready()
        const RECT region = s->turns.region(changed);
        {
            std::lock_guard<std::mutex> context(gpu.contextLock());
            const D3D11_BOX box{static_cast<UINT>(region.left), static_cast<UINT>(region.top), 0,
                                static_cast<UINT>(region.right), static_cast<UINT>(region.bottom), 1};
            gpu.context()->CopySubresourceRegion(s->staging[writing].get(), 0, region.left, region.top, 0, texture.get(), 0, &box);
            s->copied.mark(gpu);
        }
        // The copy finishes on the GPU while other pages and the EDT use the context; the EDT never maps it unfinished.
        // The frame stays ours until then: WGC can't render into its buffer under the copy.
        const bool done = s->copied.wait(gpu, kCopyWaitMs);
        if (removed(gpu)) {
            gpu.lost();
            return;
        }
        if (!done) {
            std::lock_guard<std::mutex> context(gpu.contextLock());
            std::lock_guard<std::mutex> lock(s->mutex);
            s->turns.reset(width, height);
            s->damage.whole(width, height);
            note = L"a staging copy took over 500 ms: frame dropped, both textures copied whole next";
        } else {
            std::lock_guard<std::mutex> context(gpu.contextLock());   // not while the EDT maps the ready one
            std::lock_guard<std::mutex> lock(s->mutex);
            s->turns.published(changed);
            s->damage.add(changed);
            s->needWhole = false;
            s->arrivedNs = arrived;
            ++s->seq;
        }
    }
    if (!note.empty()) logLine(note);
    else postSignal(s);
}
}  // namespace

FrameCapture::FrameCapture() : state_(std::make_shared<State>()) {}

bool FrameCapture::start(std::shared_ptr<GpuDevice> device, winrt::Windows::UI::Composition::Visual const& visual, int width,
                         int height, std::function<void(int64_t)> onFrame) {
    auto s = state_;
    std::wstring note;
    {
        std::lock_guard<std::mutex> frameLock(s->frameMutex);
        if (s->started) return true;
        if (!device || !device->rtDevice() || width <= 0 || height <= 0) return false;
        if (!s->copied.init(*device)) {
            note = L"capture not started: no fence or query on " + device->name();
        } else {
            try {
                {
                    std::lock_guard<std::mutex> lock(s->mutex);
                    s->device = device;
                }
                s->item = wgc::GraphicsCaptureItem::CreateFromVisual(visual);
                s->poolSize = {width, height};
                s->pool = wgc::Direct3D11CaptureFramePool::CreateFreeThreaded(device->rtDevice(), kFormat, 2, s->poolSize);
                std::weak_ptr<State> weak = s;
                s->arrived = s->pool.FrameArrived([weak](wgc::Direct3D11CaptureFramePool const& sender, winrt::Windows::Foundation::IInspectable const&) {
                    auto state = weak.lock();
                    if (!state) return;
                    try {
                        onArrived(state, sender);
                    } catch (winrt::hresult_error const& e) {
                        // A failed copy or texture: a removed device is made again; anything else costs this frame only.
                        std::shared_ptr<GpuDevice> gpu;
                        {
                            std::lock_guard<std::mutex> lock(state->mutex);   // rebind may be replacing it
                            gpu = state->device;
                        }
                        if (gpu && removed(*gpu)) gpu->lost();
                        else logLine(L"a frame failed: " + std::wstring(e.message().c_str()) + L" " + hex(e.code()));
                    } catch (...) {
                        logLine(L"a frame failed");
                    }
                });
                s->session = s->pool.CreateCaptureSession(s->item);
                try { s->session.IsCursorCaptureEnabled(false); } catch (winrt::hresult_error const&) {}
                try {
                    s->session.DirtyRegionMode(wgc::GraphicsCaptureDirtyRegionMode::ReportAndRender);
                    s->dirtyApi = true;
                } catch (winrt::hresult_error const&) {
                    s->dirtyApi = false;   // older Windows: whole frames (as WebView2's are anyway, W24)
                }
                s->onFrame = std::move(onFrame);
                s->needWhole = true;
                s->session.StartCapture();
                s->started = true;
                note = L"capture on " + device->name() + (s->copied.usesFence() ? L", fence" : L", event query");
            } catch (winrt::hresult_error const& e) {
                if (s->pool) {
                    s->pool.FrameArrived(s->arrived);
                    s->pool.Close();
                }
                if (s->session) s->session.Close();
                s->session = nullptr;
                s->pool = nullptr;
                s->item = nullptr;
                note = L"capture not started: " + std::wstring(e.message().c_str()) + L" " + hex(e.code());
            }
        }
    }
    logLine(note);
    return s->started;
}

void FrameCapture::resize(int width, int height) {
    auto s = state_;
    std::lock_guard<std::mutex> frameLock(s->frameMutex);
    if (!s->started || width <= 0 || height <= 0 || (s->poolSize.Width == width && s->poolSize.Height == height)) return;
    s->poolSize = {width, height};
    try {
        s->pool.Recreate(s->device->rtDevice(), kFormat, 2, s->poolSize);
    } catch (winrt::hresult_error const&) {
        if (removed(*s->device)) s->device->lost();   // the device's replacement recreates the pool at this size
    }
    s->needWhole = true;
}

void FrameCapture::rebind(std::shared_ptr<GpuDevice> device) {
    auto s = state_;
    std::wstring note;
    {
        std::lock_guard<std::mutex> frameLock(s->frameMutex);
        if (!s->started || !device || device == s->device) return;
        try {
            s->pool.Recreate(device->rtDevice(), kFormat, 2, s->poolSize);
        } catch (winrt::hresult_error const& e) {
            note = L"the capture couldn't move to " + device->name() + L": " + std::wstring(e.message().c_str());
        }
        if (note.empty()) {
            s->copied.init(*device);
            std::lock_guard<std::mutex> lock(s->mutex);   // the EDT's pull checks the device under this lock
            s->device = std::move(device);
            s->staging[0] = s->staging[1] = nullptr;      // made again on the new device at the next frame
            s->turns.reset(0, 0);
            s->width = s->height = 0;
            s->needWhole = true;
            note = L"capture moved to " + s->device->name();
        }
    }
    logLine(note);
}

void FrameCapture::stop() {
    auto s = state_;
    std::lock_guard<std::mutex> frameLock(s->frameMutex);
    if (!s->started) return;
    s->started = false;
    try {
        s->pool.FrameArrived(s->arrived);
        s->session.Close();
        s->pool.Close();
    } catch (winrt::hresult_error const&) {
    }
    s->session = nullptr;
    s->pool = nullptr;
    s->item = nullptr;
    s->onFrame = nullptr;
    // The staging textures go with the state (the page's last reference); nothing maps them once the page is released.
}

std::shared_ptr<GpuDevice> FrameCapture::device() const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    return state_->device;
}
bool FrameCapture::started() const { return state_->started; }
int64_t FrameCapture::seq() const { return state_->seq.load(); }

/**
 * The EDT, at the start of a Compose frame: copies the rows changed since the last pull (all of them if [full]) from
 * the newest published staging texture into [dst]. Its copy is finished, so the Map doesn't wait; holding the context
 * lock keeps the capture thread from publishing or writing that texture meanwhile.
 */
int FrameCapture::pull(uint8_t* dst, int rowBytes, int width, int height, bool full, int64_t* out) {
    auto s = state_;
    std::shared_ptr<GpuDevice> gpu;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        gpu = s->device;
    }
    for (int i = 0; i < 9; ++i) out[i] = 0;
    if (!gpu) return 0;
    std::wstring note;
    bool lost = false;
    int result = 0;
    {
        std::lock_guard<std::mutex> context(gpu->contextLock());
        std::lock_guard<std::mutex> lock(s->mutex);
        out[0] = s->seq.load();
        out[1] = s->width;
        out[2] = s->height;
        const int ready = s->turns.ready();
        if (s->device != gpu || ready < 0 || s->width == 0) return 0;   // moved to another adapter meanwhile, or nothing yet
        if (width != s->width || height != s->height || !dst) return -1;
        RECT r{};
        if (full) {
            RECT ignored{};
            s->damage.take(ignored);
            r = RECT{0, 0, s->width, s->height};
        } else if (!s->damage.take(r)) {
            return 0;
        }
        ID3D11Texture2D* source = s->staging[ready].get();
        D3D11_MAPPED_SUBRESOURCE mapped{};
        HRESULT hr = gpu->context()->Map(source, 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &mapped);
        if (hr == DXGI_ERROR_WAS_STILL_DRAWING) {
            // Not expected: the copy finished before the texture was published (W23). Counted and logged, then waited for.
            if (s->stillDrawing++ % 100 == 0) note = L"a published staging texture was still being written (" + std::to_wstring(s->stillDrawing) + L")";
            hr = gpu->context()->Map(source, 0, D3D11_MAP_READ, 0, &mapped);
        }
        if (FAILED(hr)) {
            s->damage.putBack(r);
            lost = hr == DXGI_ERROR_DEVICE_REMOVED || hr == DXGI_ERROR_DEVICE_RESET;
        } else {
            const int64_t start = qpcNanos();
            const size_t bytes = static_cast<size_t>(r.right - r.left) * 4;
            const auto* pixels = static_cast<const uint8_t*>(mapped.pData);
            for (int y = r.top; y < r.bottom; ++y) {
                memcpy(dst + static_cast<size_t>(y) * rowBytes + static_cast<size_t>(r.left) * 4,
                       pixels + static_cast<size_t>(y) * mapped.RowPitch + static_cast<size_t>(r.left) * 4, bytes);
            }
            gpu->context()->Unmap(source, 0);
            out[3] = r.left;
            out[4] = r.top;
            out[5] = r.right;
            out[6] = r.bottom;
            out[7] = s->arrivedNs;
            out[8] = qpcNanos() - start;   // this copy's cost, for the perf lab
            result = 1;
        }
    }
    if (lost) gpu->lost();
    if (!note.empty()) logLine(note);
    return result;
}

}  // namespace wv
