#pragma once
#include "capture.h"

namespace wv2 {

struct HostOptions {
    HWND canvas = nullptr;          // skiko's canvas, the Compose root: bounds arrive in its client pixels
    int parentMode = 0;             // WebView2's parent: 0 the top-level frame, 1 the canvas, 2 our own 1x1 child
    std::wstring userDataFolder;
    std::wstring browserArguments;
    bool dirtyRegions = true;
    bool compensateOffset = false;  // offset the WebView's visual by -bounds.origin (only if Bounds turns out to move it)
};

// One WebView2 in visual-hosting mode (CoreWebView2CompositionController) whose root visual FrameCapture captures.
// Every member runs on the UI thread, except capture(), browserPid() and parent().
class WebHost {
public:
    void start(HostOptions options);  // asynchronous; emits kReady
    void setBounds(int x, int y, int width, int height);
    void notifyParentMoved();
    void setVisible(bool visible);
    void addDocumentScript(std::wstring const& script);  // emits kDocScriptAdded
    void navigateToString(std::wstring const& html);
    void executeScript(int64_t requestId, std::wstring const& script);  // emits kScriptResult
    void mouse(int kind, int virtualKeys, int mouseData, int x, int y);
    void pointer(int pointerKind, int eventKind, int pointerId, int x, int y, int pressure);
    void moveFocus(int reason);
    void stop();

    FrameCapture& capture() { return capture_; }
    int64_t browserPid() const { return browserPid_.load(); }
    HWND parent() const { return parent_.load(); }

private:
    void onControllerCreated(ICoreWebView2CompositionController* composition);
    void registerEvents();
    void onCursorChanged();

    HostOptions options_;
    winrt::Windows::UI::Composition::Compositor compositor_{nullptr};
    winrt::Windows::UI::Composition::ContainerVisual root_{nullptr};
    winrt::Windows::UI::Composition::ContainerVisual webVisual_{nullptr};
    winrt::com_ptr<ICoreWebView2Environment3> environment_;
    winrt::com_ptr<ICoreWebView2CompositionController> composition_;
    winrt::com_ptr<ICoreWebView2Controller> controller_;
    winrt::com_ptr<ICoreWebView2> webview_;
    FrameCapture capture_;
    HWND hostWindow_ = nullptr;
    RECT bounds_{0, 0, 0, 0};
    UINT32 pointerFrame_ = 0;
    std::wstring runtimeVersion_;
    std::atomic<int64_t> browserPid_{0};
    std::atomic<HWND> parent_{nullptr};
};

}  // namespace wv2
