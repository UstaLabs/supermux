#include "dirty_probe.h"
#include <d3d11_4.h>
#include <windows.ui.composition.interop.h>
#include <winrt/Windows.UI.h>
#include <chrono>
#include <future>
#include <memory>
#include <thread>

namespace wuc = winrt::Windows::UI::Composition;
namespace wgc = winrt::Windows::Graphics::Capture;
namespace wgdx = winrt::Windows::Graphics::DirectX;

namespace wv2 {
namespace {

struct ProbeState {
    wuc::CompositionGraphicsDevice graphics{nullptr};
    wuc::CompositionDrawingSurface surface{nullptr};
    wuc::SpriteVisual sprite{nullptr};
    wuc::SpriteVisual dot{nullptr};
    wuc::ContainerVisual root{nullptr};
    wgc::Direct3D11CaptureFramePool pool{nullptr};
    wgc::GraphicsCaptureSession session{nullptr};
    std::mutex mutex;  // the frame pool's thread records, the probe's thread reads
    std::vector<int64_t> pixels;  // dirty pixels per captured frame
    std::vector<int64_t> rects;   // dirty rectangles per captured frame
    std::wstring error;
};

// Runs work on the UI thread and waits for it (at most 5 s).
bool onUi(UiThread& ui, std::function<void()> work) {
    auto done = std::make_shared<std::promise<void>>();
    auto future = done->get_future();
    if (!ui.post([done, work] {
            work();
            done->set_value();
        })) {
        return false;
    }
    return future.wait_for(std::chrono::seconds(5)) == std::future_status::ready;
}

// Fills rect (the whole surface when null) with one colour, through the surface's own BeginDraw/EndDraw.
void fill(ProbeState& s, ID3D11Device* device, RECT const* rect, int width, int height, uint32_t color) {
    auto interop = s.surface.as<ABI::Windows::UI::Composition::ICompositionDrawingSurfaceInterop>();
    winrt::com_ptr<ID3D11Texture2D> texture;
    POINT offset{};
    winrt::check_hresult(interop->BeginDraw(rect, __uuidof(ID3D11Texture2D), texture.put_void(), &offset));
    const int w = rect ? rect->right - rect->left : width;
    const int h = rect ? rect->bottom - rect->top : height;
    std::vector<uint32_t> pixels(static_cast<size_t>(w) * h, color);
    winrt::com_ptr<ID3D11DeviceContext> context;
    device->GetImmediateContext(context.put());
    const D3D11_BOX box{static_cast<UINT>(offset.x), static_cast<UINT>(offset.y), 0, static_cast<UINT>(offset.x + w),
                        static_cast<UINT>(offset.y + h), 1};
    context->UpdateSubresource(texture.get(), 0, &box, pixels.data(), static_cast<UINT>(w) * 4, 0);
    winrt::check_hresult(interop->EndDraw());
}

std::wstring summary(std::wstring const& phase, ProbeState& s, int64_t whole) {
    std::lock_guard<std::mutex> lock(s.mutex);
    auto pixels = s.pixels;
    auto rects = s.rects;
    s.pixels.clear();
    s.rects.clear();
    if (pixels.empty()) return phase + L" frames=0";
    std::sort(pixels.begin(), pixels.end());
    std::sort(rects.begin(), rects.end());
    const auto wholeFrames = std::count_if(pixels.begin(), pixels.end(), [whole](int64_t p) { return p >= whole; });
    return phase + L" frames=" + std::to_wstring(pixels.size()) + L" dirty_px_p50=" +
           std::to_wstring(pixels[pixels.size() / 2]) + L" dirty_px_max=" + std::to_wstring(pixels.back()) +
           L" rects_p50=" + std::to_wstring(rects[rects.size() / 2]) + L" whole_frames=" + std::to_wstring(wholeFrames);
}

}  // namespace

std::wstring dirtyProbe(UiThread& ui, wuc::Compositor const& compositor, FrameCapture& capture, int width, int height,
                        int updates, int intervalMs) {
    if (!compositor || !capture.device() || width <= 0 || height <= 0) return L"error=no compositor or capture device yet";
    ID3D11Device* device = capture.device();
    // The capture thread, Composition's EndDraw and this probe share the device's immediate context.
    winrt::com_ptr<ID3D11Multithread> protect;
    if (SUCCEEDED(device->QueryInterface(IID_PPV_ARGS(protect.put())))) protect->SetMultithreadProtected(TRUE);
    auto state = std::make_shared<ProbeState>();
    auto& s = *state;
    const auto sleep = [](int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); };
    const bool setUp = onUi(ui, [&s, &compositor, &capture, device, width, height] {
        try {
            auto interop = compositor.as<ABI::Windows::UI::Composition::ICompositorInterop>();
            winrt::com_ptr<ABI::Windows::UI::Composition::ICompositionGraphicsDevice> graphics;
            winrt::check_hresult(interop->CreateGraphicsDevice(device, graphics.put()));
            winrt::check_hresult(graphics->QueryInterface(winrt::guid_of<wuc::CompositionGraphicsDevice>(),
                                                          winrt::put_abi(s.graphics)));
            const winrt::Windows::Foundation::Size size{static_cast<float>(width), static_cast<float>(height)};
            s.surface = s.graphics.CreateDrawingSurface(size, wgdx::DirectXPixelFormat::B8G8R8A8UIntNormalized,
                                                        wgdx::DirectXAlphaMode::Premultiplied);
            s.sprite = compositor.CreateSpriteVisual();
            s.sprite.Size({static_cast<float>(width), static_cast<float>(height)});
            s.sprite.Brush(compositor.CreateSurfaceBrush(s.surface));
            s.dot = compositor.CreateSpriteVisual();
            s.dot.Size({64.0f, 64.0f});
            s.dot.Offset({400.0f, 300.0f, 0.0f});
            s.dot.Brush(compositor.CreateColorBrush(winrt::Windows::UI::Color{255, 200, 40, 40}));
            s.root = compositor.CreateContainerVisual();
            s.root.Size({static_cast<float>(width), static_cast<float>(height)});
            s.root.Children().InsertAtTop(s.sprite);
            s.root.Children().InsertAtTop(s.dot);
            fill(s, device, nullptr, width, height, 0xFFF0F0F0);
            auto item = wgc::GraphicsCaptureItem::CreateFromVisual(s.root);
            s.pool = wgc::Direct3D11CaptureFramePool::CreateFreeThreaded(
                capture.rtDevice(), wgdx::DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, {width, height});
            s.pool.FrameArrived([&s](wgc::Direct3D11CaptureFramePool const& sender, auto const&) {
                auto frame = sender.TryGetNextFrame();
                if (!frame) return;
                int64_t pixels = 0;
                int64_t rects = 0;
                for (auto const& r : frame.DirtyRegions()) {
                    pixels += static_cast<int64_t>(r.Width) * r.Height;
                    rects++;
                }
                std::lock_guard<std::mutex> lock(s.mutex);
                s.pixels.push_back(pixels);
                s.rects.push_back(rects);
            });
            s.session = s.pool.CreateCaptureSession(item);
            s.session.IsCursorCaptureEnabled(false);
            s.session.DirtyRegionMode(wgc::GraphicsCaptureDirtyRegionMode::ReportAndRender);
            s.session.StartCapture();
        } catch (winrt::hresult_error const& e) {
            s.error = L"setup " + hex(e.code()) + L" " + std::wstring(e.message().c_str());
        }
    });
    if (!setUp || !s.error.empty()) return L"error=" + (s.error.empty() ? std::wstring(L"setup timed out") : s.error);
    sleep(500);
    summary(L"warmup", s, 0);
    const int64_t whole = static_cast<int64_t>(width) * height;
    std::wstring result;
    // 1. A 144x8 rectangle of the surface (the test page's marker), in two colours by turns.
    for (int i = 0; i < updates; ++i) {
        onUi(ui, [&s, device, width, height, i] {
            const RECT rect{0, 0, 144, 8};
            fill(s, device, &rect, width, height, (i % 2) ? 0xFF000000 : 0xFFFFFFFF);
        });
        sleep(intervalMs);
    }
    sleep(300);
    result += summary(L"surface_144x8", s, whole);
    // 2. A 64x64 sprite moved by 200 px: a change to the visual tree, not to any surface.
    for (int i = 0; i < updates; ++i) {
        onUi(ui, [&s, i] { s.dot.Offset({(i % 2) ? 600.0f : 400.0f, 300.0f, 0.0f}); });
        sleep(intervalMs);
    }
    sleep(300);
    result += L"; " + summary(L"sprite_move_64x64", s, whole);
    // 3. The whole surface, as a control.
    for (int i = 0; i < 10; ++i) {
        onUi(ui, [&s, device, width, height, i] { fill(s, device, nullptr, width, height, (i % 2) ? 0xFF3060C0 : 0xFFC06030); });
        sleep(intervalMs);
    }
    sleep(300);
    result += L"; " + summary(L"surface_whole", s, whole);
    onUi(ui, [state] {
        auto& t = *state;
        if (t.session) t.session.Close();
        if (t.pool) t.pool.Close();
        t.session = nullptr;
        t.pool = nullptr;
        t.root = nullptr;
        t.dot = nullptr;
        t.sprite = nullptr;
        t.surface = nullptr;
        t.graphics = nullptr;
    });
    return result + L"; whole_px=" + std::to_wstring(whole);
}

}  // namespace wv2
