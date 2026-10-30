#pragma once
#include "common.h"

namespace wv2 {

// Frames of one Composition visual, through Windows.Graphics.Capture (GraphicsCaptureItem::CreateFromVisual).
// FrameArrived runs on a capture thread (free-threaded pool): it copies the changed rectangles GPU -> staging texture
// -> a CPU buffer. The JVM's UI thread pulls the changed rows from that buffer into its Skia bitmap with copyTo().
class FrameCapture {
public:
    struct Stats {
        int64_t arrived = 0, copied = 0, emptyDirty = 0, blank = 0, recreates = 0, rects = 0, dirtyPixels = 0,
                fullCopies = 0, takes = 0;
    };
    // copyNs is the whole copy on the capture thread; readNs its GPU -> staging part (CopySubresourceRegion + Map),
    // lockNs the wait for the CPU buffer's lock (the UI thread's copyTo holds it while it copies rows out).
    struct Arrival {
        int64_t marker = -1, arriveNs = 0, arriveEpochUs = 0, renderNs = 0, copyNs = 0, width = 0, height = 0,
                readNs = 0, lockNs = 0;
    };

    bool init();  // creates the D3D11 device; false if there is none
    bool start(winrt::Windows::UI::Composition::Visual const& visual, int width, int height, bool useDirtyRegions);
    void resize(int width, int height);  // call before the visual changes size: re-sizes the frame pool
    void stop();
    bool started() const { return started_; }
    bool dirtyApi() const { return dirtyApi_; }
    bool hardware() const { return hardware_; }
    std::wstring adapter() const { return adapter_; }

    // Copies the rows changed since the last take (every row if full) into dst: BGRA, rowBytes per row, exactly the
    // frame's size. Returns 0 when nothing changed, 1 when it copied, -1 when the size differs (see out[10], out[11]).
    // out (13 longs): seq, marker, dirty left, top, right, bottom, renderNs, arriveNs, copiedNs, copyNs, width, height,
    // frames merged since the last take.
    int copyTo(uint8_t* dst, int rowBytes, int width, int height, bool full, int64_t* out);
    // Moves up to max arrivals, oldest first, into out (9 longs each, the Arrival fields in order); returns how many.
    int drainArrivals(int64_t* out, int max);
    Stats stats();

private:
    void onFrameArrived(winrt::Windows::Graphics::Capture::Direct3D11CaptureFramePool const& sender);
    void ensureStaging(int width, int height);
    void pushArrivalLocked(Arrival const& arrival);
    int decodeMarkerLocked() const;
    bool looksBlankLocked() const;

    static constexpr int kRing = 8192;

    winrt::com_ptr<ID3D11Device> device_;
    winrt::com_ptr<ID3D11DeviceContext> context_;
    winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice rtDevice_{nullptr};
    winrt::Windows::Graphics::Capture::GraphicsCaptureItem item_{nullptr};
    winrt::Windows::Graphics::Capture::Direct3D11CaptureFramePool pool_{nullptr};
    winrt::Windows::Graphics::Capture::GraphicsCaptureSession session_{nullptr};
    winrt::event_token frameArrivedToken_{};
    winrt::Windows::Graphics::SizeInt32 poolSize_{0, 0};
    winrt::com_ptr<ID3D11Texture2D> staging_;
    int stagingWidth_ = 0, stagingHeight_ = 0;
    std::wstring adapter_;
    bool hardware_ = false, started_ = false, dirtyApi_ = false, needFull_ = true;

    std::mutex frameMutex_;  // serialises FrameArrived, resize, start and stop: the D3D context, pool and staging
    std::mutex mutex_;       // guards everything below; shared with copyTo, drainArrivals and stats
    std::vector<uint8_t> cpu_;
    int cpuWidth_ = 0, cpuHeight_ = 0;
    RECT pending_{0, 0, 0, 0};
    bool hasPending_ = false, seenContent_ = false;
    int64_t seq_ = 0, marker_ = -1, renderNs_ = 0, arriveNs_ = 0, copiedNs_ = 0, copyNs_ = 0, merged_ = 0;
    std::vector<Arrival> ring_;
    int ringHead_ = 0, ringCount_ = 0;
    Stats stats_;
};

}  // namespace wv2
