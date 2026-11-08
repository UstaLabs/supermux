#pragma once
#include "capture.h"
#include "ui_thread.h"

namespace wv2 {

// Does WGC's visual capture (CreateFromVisual + DirtyRegionMode::ReportAndRender) report fine dirty regions at all?
// It captures a Composition visual this DLL draws itself, a CompositionDrawingSurface on the capture device, while it
// (1) redraws a known 144x8 rectangle of the surface, (2) moves a 64x64 sprite over it, (3) redraws the whole surface,
// and returns, per phase, the frames seen and the dirty pixels and rectangles WGC reported for them. Call it off the UI
// thread: every Composition step is posted there, so the compositor commits between steps.
std::wstring dirtyProbe(UiThread& ui, winrt::Windows::UI::Composition::Compositor const& compositor,
                        FrameCapture& capture, int width, int height, int updates, int intervalMs);

}  // namespace wv2
