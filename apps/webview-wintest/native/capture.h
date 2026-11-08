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
    // lockNs the wait for the CPU buffer's lock (the UI thread's copyTo holds it while it copies rows out). dirtyPx and
    // rects: the pixels and rectangles this frame copied (the whole frame when WGC reports it all dirty).
    // The three-copy chain (directPull): copyNs is arrival to the GPU copies issued, readNs issuing them, lockNs the
    // wait for the D3D context, and probeNs the test's own marker read (not part of the chain; 0 in the five-copy one).
    struct Arrival {
        int64_t marker = -1, arriveNs = 0, arriveEpochUs = 0, renderNs = 0, copyNs = 0, width = 0, height = 0,
                readNs = 0, lockNs = 0, dirtyPx = 0, rects = 0, probeNs = 0;
    };
    static constexpr int kArrivalFields = 12;  // longs per arrival in drainArrivals' output
    static constexpr int kPullFields = 16;     // longs in copyTo's and pullDirect's out

    // Creates the D3D11 device; false if there is none. adapterMode 0: the default adapter (the primary display's);
    // 1: the first hardware adapter by DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE.
    bool init(int adapterMode);
    bool start(winrt::Windows::UI::Composition::Visual const& visual, int width, int height, bool useDirtyRegions);
    void resize(int width, int height);  // call before the visual changes size: re-sizes the frame pool
    void stop();
    bool started() const { return started_; }
    bool dirtyApi() const { return dirtyApi_; }
    bool hardware() const { return hardware_; }
    std::wstring adapter() const { return adapter_; }
    std::wstring adapterLuid() const { return adapterLuid_; }  // "0xHHHHHHHH:0xLLLLLLLL", as DXGI reports it
    ID3D11Device* device() const { return device_.get(); }
    winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice rtDevice() const { return rtDevice_; }

    // The three-copy chain (-Dwv2.chain=3; set before start): the capture thread only issues GPU -> staging, and
    // pullDirect copies from the mapped staging texture straight into the UI's pixels. Off: the test's five copies.
    void setDirectPull(bool on) { directPull_ = on; }
    bool directPull() const { return directPull_; }

    // Copies the rows changed since the last take (every row if full) into dst: BGRA, rowBytes per row, exactly the
    // frame's size. Returns 0 when nothing changed, 1 when it copied, -1 when the size differs (see out[10], out[11]).
    // out (kPullFields longs): seq, marker, dirty left, top, right, bottom, renderNs, arriveNs, copiedNs, copyNs, width,
    // height, frames merged since the last take, then pullDirect's: context wait ns, Map ns, still-drawing retries.
    int copyTo(uint8_t* dst, int rowBytes, int width, int height, bool full, int64_t* out);
    // The same from the newest finished staging texture (directPull), on the UI thread: Map, rows, Unmap. The capture
    // thread published that texture only once its copy was done, so the Map doesn't wait (out[15] = 1 if it did).
    int pullDirect(uint8_t* dst, int rowBytes, int width, int height, bool full, int64_t* out);
    // Moves up to max arrivals, oldest first, into out (kArrivalFields longs each, the Arrival fields in order);
    // returns how many.
    int drainArrivals(int64_t* out, int max);
    Stats stats();

private:
    void onFrameArrived(winrt::Windows::Graphics::Capture::Direct3D11CaptureFramePool const& sender);
    void issueDirect(ID3D11Texture2D* texture, std::vector<RECT> const& rects, int width, int height, bool sizeChanged,
                     bool full, int64_t arriveNs, int64_t arriveEpochUs, int64_t renderNs);
    void ensureStaging(int width, int height);
    void ensureMarkerStaging();
    bool ensureRing(int width, int height);  // true if it (re)made the textures: both hold nothing yet
    void pushArrivalLocked(Arrival const& arrival);
    int decodeMarkerLocked() const;
    bool looksBlankLocked() const;
    static int decodeMarkerAt(const uint8_t* pixels, size_t pitch, int width, int height);
    static bool looksBlankAt(const uint8_t* pixels, size_t pitch, int width, int height);

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
    std::wstring adapter_, adapterLuid_;
    bool hardware_ = false, started_ = false, dirtyApi_ = false, needFull_ = true, directPull_ = false;
    winrt::com_ptr<ID3D11Texture2D> markerStaging_;  // directPull: the marker's cells, read on the capture thread
    // directPull: two staging textures written by turns. The capture thread waits for its copy (copyDone_, an event
    // query) and only then publishes the texture as readyIndex_, so pullDirect maps a finished copy and never waits;
    // with one texture, the UI thread's Map lands ~1 ms after each copy is issued (both follow DWM's vsync) and finds it
    // still running nearly every time. stale_: what a texture missed while frames went into the other one.
    winrt::com_ptr<ID3D11Texture2D> stagingRing_[2];
    winrt::com_ptr<ID3D11Query> copyDone_;
    int ringWidth_ = 0, ringHeight_ = 0, writeIndex_ = 0, readyIndex_ = -1;
    RECT stale_[2]{};
    bool staleAny_[2]{};

    std::mutex frameMutex_;  // serialises FrameArrived, resize, start and stop: the D3D context, pool and staging
    std::mutex contextMutex_;  // directPull: the D3D context and staging, shared by the capture thread and pullDirect
    std::mutex mutex_;       // guards everything below; shared with copyTo, drainArrivals and stats
    std::vector<uint8_t> cpu_;
    int cpuWidth_ = 0, cpuHeight_ = 0;
    int frameWidth_ = 0, frameHeight_ = 0;  // directPull: the size of the frame in staging
    RECT pending_{0, 0, 0, 0};
    bool hasPending_ = false, seenContent_ = false;
    int64_t seq_ = 0, marker_ = -1, renderNs_ = 0, arriveNs_ = 0, copiedNs_ = 0, copyNs_ = 0, merged_ = 0;
    std::vector<Arrival> ring_;
    int ringHead_ = 0, ringCount_ = 0;
    Stats stats_;
};

}  // namespace wv2
