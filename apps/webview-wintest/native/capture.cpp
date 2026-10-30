#include "capture.h"

namespace wgc = winrt::Windows::Graphics::Capture;
namespace wgdx = winrt::Windows::Graphics::DirectX;

namespace wv2 {
namespace {
constexpr int kMarkerBits = 16;
constexpr int kMarkerCell = 8;
constexpr auto kFormat = wgdx::DirectXPixelFormat::B8G8R8A8UIntNormalized;
}  // namespace

bool FrameCapture::init() {
    if (device_) return true;
    const UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, nullptr, 0, D3D11_SDK_VERSION,
                                   device_.put(), nullptr, context_.put());
    hardware_ = SUCCEEDED(hr);
    if (FAILED(hr)) {
        hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, flags, nullptr, 0, D3D11_SDK_VERSION,
                               device_.put(), nullptr, context_.put());
    }
    if (FAILED(hr)) {
        logLine(L"D3D11CreateDevice failed " + hex(hr));
        return false;
    }
    auto dxgiDevice = device_.as<IDXGIDevice>();
    winrt::com_ptr<IDXGIAdapter> adapter;
    if (SUCCEEDED(dxgiDevice->GetAdapter(adapter.put()))) {
        DXGI_ADAPTER_DESC desc{};
        if (SUCCEEDED(adapter->GetDesc(&desc))) adapter_ = desc.Description;
    }
    winrt::com_ptr<::IInspectable> inspectable;
    hr = CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice.get(), inspectable.put());
    if (FAILED(hr)) {
        logLine(L"CreateDirect3D11DeviceFromDXGIDevice failed " + hex(hr));
        return false;
    }
    rtDevice_ = inspectable.as<winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice>();
    ring_.assign(kRing, Arrival{});
    return true;
}

bool FrameCapture::start(winrt::Windows::UI::Composition::Visual const& visual, int width, int height,
                         bool useDirtyRegions) {
    std::lock_guard<std::mutex> frameLock(frameMutex_);
    if (started_) return true;
    if (!rtDevice_ || width <= 0 || height <= 0) return false;
    item_ = wgc::GraphicsCaptureItem::CreateFromVisual(visual);
    poolSize_ = {width, height};
    pool_ = wgc::Direct3D11CaptureFramePool::CreateFreeThreaded(rtDevice_, kFormat, 2, poolSize_);
    frameArrivedToken_ = pool_.FrameArrived(
        [this](wgc::Direct3D11CaptureFramePool const& sender, winrt::Windows::Foundation::IInspectable const&) {
            onFrameArrived(sender);
        });
    session_ = pool_.CreateCaptureSession(item_);
    try {
        session_.IsCursorCaptureEnabled(false);
    } catch (winrt::hresult_error const&) {
    }
#ifndef WV2_NO_DIRTY_API
    if (useDirtyRegions) {
        try {
            session_.DirtyRegionMode(wgc::GraphicsCaptureDirtyRegionMode::ReportAndRender);
            dirtyApi_ = true;
        } catch (winrt::hresult_error const& e) {
            logLine(L"DirtyRegionMode unavailable " + hex(e.code()));
        }
    }
#else
    (void)useDirtyRegions;
#endif
    needFull_ = true;
    session_.StartCapture();
    started_ = true;
    return true;
}

void FrameCapture::resize(int width, int height) {
    std::lock_guard<std::mutex> frameLock(frameMutex_);
    if (!started_ || width <= 0 || height <= 0) return;
    if (poolSize_.Width == width && poolSize_.Height == height) return;
    poolSize_ = {width, height};
    pool_.Recreate(rtDevice_, kFormat, 2, poolSize_);
    needFull_ = true;
    std::lock_guard<std::mutex> lock(mutex_);
    stats_.recreates++;
}

void FrameCapture::stop() {
    std::lock_guard<std::mutex> frameLock(frameMutex_);
    if (!started_) return;
    pool_.FrameArrived(frameArrivedToken_);
    session_.Close();
    pool_.Close();
    session_ = nullptr;
    pool_ = nullptr;
    item_ = nullptr;
    started_ = false;
}

void FrameCapture::onFrameArrived(wgc::Direct3D11CaptureFramePool const& sender) {
    std::lock_guard<std::mutex> frameLock(frameMutex_);
    if (!started_) return;
    auto frame = sender.TryGetNextFrame();
    if (!frame) return;
    const int64_t arriveNs = qpcNanos();
    const int64_t arriveEpochUs = epochMicros();
    const int64_t renderNs = frame.SystemRelativeTime().count() * 100;  // 100 ns QPC units, same clock as qpcNanos
    const auto content = frame.ContentSize();

    winrt::com_ptr<ID3D11Texture2D> texture;
    auto access = frame.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
    winrt::check_hresult(access->GetInterface(IID_PPV_ARGS(texture.put())));
    D3D11_TEXTURE2D_DESC desc{};
    texture->GetDesc(&desc);
    // The pool's textures can be larger than the content (after a shrink); only the content is valid.
    const int width = std::min<int>(content.Width, static_cast<int>(desc.Width));
    const int height = std::min<int>(content.Height, static_cast<int>(desc.Height));
    if (width <= 0 || height <= 0) return;

    bool sizeChanged = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        sizeChanged = width != cpuWidth_ || height != cpuHeight_;
    }
    const bool full = needFull_ || sizeChanged || !dirtyApi_;
    std::vector<RECT> rects;
#ifndef WV2_NO_DIRTY_API
    if (!full) {
        try {
            for (auto const& r : frame.DirtyRegions()) {
                const RECT clipped{std::max(0, r.X), std::max(0, r.Y), std::min(width, r.X + r.Width),
                                   std::min(height, r.Y + r.Height)};
                if (clipped.right > clipped.left && clipped.bottom > clipped.top) rects.push_back(clipped);
            }
        } catch (winrt::hresult_error const&) {
            dirtyApi_ = false;
            rects.clear();
        }
        if (rects.empty() && dirtyApi_) {
            std::lock_guard<std::mutex> lock(mutex_);
            stats_.arrived++;
            stats_.emptyDirty++;
            return;
        }
    }
#endif
    if (rects.empty()) rects.push_back(RECT{0, 0, width, height});

    ensureStaging(width, height);
    const int64_t readStart = qpcNanos();
    for (auto const& r : rects) {
        const D3D11_BOX box{static_cast<UINT>(r.left), static_cast<UINT>(r.top), 0,
                            static_cast<UINT>(r.right), static_cast<UINT>(r.bottom), 1};
        context_->CopySubresourceRegion(staging_.get(), 0, r.left, r.top, 0, texture.get(), 0, &box);
    }
    D3D11_MAPPED_SUBRESOURCE mapped{};
    const HRESULT hr = context_->Map(staging_.get(), 0, D3D11_MAP_READ, 0, &mapped);
    if (FAILED(hr)) {
        logLine(L"Map failed " + hex(hr));
        return;
    }
    const int64_t readNs = qpcNanos() - readStart;
    {
        const int64_t lockStart = qpcNanos();
        std::lock_guard<std::mutex> lock(mutex_);
        const int64_t lockNs = qpcNanos() - lockStart;
        if (sizeChanged) {
            cpu_.assign(static_cast<size_t>(width) * height * 4, 0);
            cpuWidth_ = width;
            cpuHeight_ = height;
            hasPending_ = false;
        }
        const auto* source = static_cast<const uint8_t*>(mapped.pData);
        RECT dirty = hasPending_ ? pending_ : rects.front();
        for (auto const& r : rects) {
            const size_t bytes = static_cast<size_t>(r.right - r.left) * 4;
            for (int y = r.top; y < r.bottom; ++y) {
                memcpy(&cpu_[(static_cast<size_t>(y) * cpuWidth_ + r.left) * 4],
                       source + static_cast<size_t>(y) * mapped.RowPitch + static_cast<size_t>(r.left) * 4, bytes);
            }
            UnionRect(&dirty, &dirty, &r);
            stats_.rects++;
            stats_.dirtyPixels += static_cast<int64_t>(r.right - r.left) * (r.bottom - r.top);
        }
        pending_ = dirty;
        hasPending_ = true;
        seq_++;
        merged_++;
        marker_ = decodeMarkerLocked();
        renderNs_ = renderNs;
        arriveNs_ = arriveNs;
        copiedNs_ = qpcNanos();
        copyNs_ = copiedNs_ - arriveNs;
        if (!looksBlankLocked()) {
            seenContent_ = true;
        } else if (seenContent_) {
            stats_.blank++;
        }
        stats_.arrived++;
        stats_.copied++;
        if (full) stats_.fullCopies++;
        pushArrivalLocked(Arrival{marker_, arriveNs, arriveEpochUs, renderNs, copyNs_, width, height, readNs, lockNs});
    }
    context_->Unmap(staging_.get(), 0);
    needFull_ = false;
    emitFrameReady();
}

void FrameCapture::ensureStaging(int width, int height) {
    if (staging_ && stagingWidth_ == width && stagingHeight_ == height) return;
    D3D11_TEXTURE2D_DESC desc{};
    desc.Width = static_cast<UINT>(width);
    desc.Height = static_cast<UINT>(height);
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_STAGING;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    staging_ = nullptr;
    winrt::check_hresult(device_->CreateTexture2D(&desc, nullptr, staging_.put()));
    stagingWidth_ = width;
    stagingHeight_ = height;
}

void FrameCapture::pushArrivalLocked(Arrival const& arrival) {
    if (ring_.empty()) return;
    ring_[ringHead_] = arrival;
    ringHead_ = (ringHead_ + 1) % kRing;
    ringCount_ = std::min(ringCount_ + 1, kRing);
}

int FrameCapture::decodeMarkerLocked() const {
    if (cpuWidth_ < (kMarkerBits + 2) * kMarkerCell || cpuHeight_ < kMarkerCell) return -1;
    const auto luminance = [this](int cell) {
        const size_t index = (static_cast<size_t>(kMarkerCell / 2) * cpuWidth_ +
                              static_cast<size_t>(cell) * kMarkerCell + kMarkerCell / 2) * 4;
        return (cpu_[index] + cpu_[index + 1] + cpu_[index + 2]) / 3;
    };
    if (luminance(kMarkerBits) < 192 || luminance(kMarkerBits + 1) > 63) return -1;  // the guards: white, then black
    int value = 0;
    for (int bit = 0; bit < kMarkerBits; ++bit) {
        if (luminance(bit) > 127) value |= 1 << bit;
    }
    return value;
}

bool FrameCapture::looksBlankLocked() const {
    if (cpuWidth_ < 8 || cpuHeight_ < 8) return true;
    const auto* pixels = reinterpret_cast<const uint32_t*>(cpu_.data());
    const uint32_t first = pixels[static_cast<size_t>(cpuHeight_ / 8) * cpuWidth_ + cpuWidth_ / 8];
    for (int gy = 1; gy < 8; gy += 2) {
        for (int gx = 1; gx < 8; gx += 2) {
            if (pixels[static_cast<size_t>(cpuHeight_ * gy / 8) * cpuWidth_ + cpuWidth_ * gx / 8] != first) return false;
        }
    }
    return true;
}

int FrameCapture::copyTo(uint8_t* dst, int rowBytes, int width, int height, bool full, int64_t* out) {
    std::lock_guard<std::mutex> lock(mutex_);
    out[10] = cpuWidth_;
    out[11] = cpuHeight_;
    if (cpuWidth_ == 0 || cpuHeight_ == 0) return 0;
    if (width != cpuWidth_ || height != cpuHeight_ || dst == nullptr) return -1;
    if (!hasPending_ && !full) return 0;
    const RECT r = full ? RECT{0, 0, cpuWidth_, cpuHeight_} : pending_;
    const size_t bytes = static_cast<size_t>(r.right - r.left) * 4;
    for (int y = r.top; y < r.bottom; ++y) {
        memcpy(dst + static_cast<size_t>(y) * rowBytes + static_cast<size_t>(r.left) * 4,
               &cpu_[(static_cast<size_t>(y) * cpuWidth_ + r.left) * 4], bytes);
    }
    out[0] = seq_;
    out[1] = marker_;
    out[2] = r.left;
    out[3] = r.top;
    out[4] = r.right;
    out[5] = r.bottom;
    out[6] = renderNs_;
    out[7] = arriveNs_;
    out[8] = copiedNs_;
    out[9] = copyNs_;
    out[12] = merged_;
    hasPending_ = false;
    merged_ = 0;
    stats_.takes++;
    return 1;
}

int FrameCapture::drainArrivals(int64_t* out, int max) {
    std::lock_guard<std::mutex> lock(mutex_);
    int n = 0;
    while (ringCount_ > 0 && n < max) {
        Arrival const& a = ring_[(ringHead_ - ringCount_ + kRing) % kRing];
        int64_t* o = out + static_cast<size_t>(n) * 9;
        o[0] = a.marker;
        o[1] = a.arriveNs;
        o[2] = a.arriveEpochUs;
        o[3] = a.renderNs;
        o[4] = a.copyNs;
        o[5] = a.width;
        o[6] = a.height;
        o[7] = a.readNs;
        o[8] = a.lockNs;
        --ringCount_;
        ++n;
    }
    return n;
}

FrameCapture::Stats FrameCapture::stats() {
    std::lock_guard<std::mutex> lock(mutex_);
    return stats_;
}

}  // namespace wv2
