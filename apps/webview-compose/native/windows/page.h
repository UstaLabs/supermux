#pragma once
#include "wv2.h"
#include "capture.h"

namespace wv {

/** One engine's WebView2 (D1). Every member runs on the UI thread, except pull() and frameSeq() (the EDT). */
class WvPage : public std::enable_shared_from_this<WvPage> {
public:
    explicit WvPage(int64_t token) : token_(token) {}
    void create(std::wstring profile, bool inPrivate, uint32_t background);   // emits kCreated
    void release();                                                            // answers everything by default first
    void configure(int flags, uint32_t background, std::wstring userAgentSuffix);
    // Task 8: the document script, messages, script calls, DevTools.
    void setScript(int epoch, std::wstring secret, std::wstring script);       // emits kScriptReady
    void navigate(int epoch, std::wstring url, std::wstring headers);          // waits for the script's add
    void go(int epoch, int what);
    void execute(int64_t request, std::wstring script);                       // emits kResult
    void devtools(int64_t request, std::wstring method, std::wstring params);  // emits kResult (request 0: none)
    void subscribe(std::wstring event);                                        // emits kDevToolsEvent
    void openDevTools();
    // Task 9: local content.
    void serve(std::wstring host, std::optional<std::vector<uint8_t>> html, std::wstring floorHtml);
    void respond(int64_t request, int status, std::wstring headers, std::vector<uint8_t> body);
    // Task 10: frames and placement.
    void place(HWND frame, HWND canvas, int x, int y, int width, int height, double scale);
    void unplace();                                                            // the parking window is its parent
    void parentMoved();
    void setVisible(bool visible);
    void setMemoryTarget(bool low);
    int pull(uint8_t* dst, int rowBytes, int width, int height, bool full, int64_t* out) { return capture_.pull(dst, rowBytes, width, height, full, out); }
    int64_t frameSeq() const { return capture_.seq(); }
    /** D24: the capture follows the adapter of the monitor the page's window is on ([force]: the displays changed). */
    void followAdapter(bool force);
    std::shared_ptr<GpuDevice> captureDevice() const;
    void rebindCapture(std::shared_ptr<GpuDevice> device);
    /** Its device was lost and none could be made: the page can't be drawn (kProcessFailed, kFailedNoDevice). */
    void reportNoDevice();
    // Task 11: input and the page's questions.
    void mouse(int kind, int keys, int data, int x, int y);
    void moveFocus(int reason);
    void answer(int64_t question, int verdict, std::wstring text);
    void clearData(int64_t request);

private:
    void onCreated(ICoreWebView2CompositionController* composition);
    bool hasRequiredInterfaces(std::wstring& missing);
    void applySettings();
    void registerNavigationEvents();   // this task
    void registerScriptEvents();       // Task 8
    void registerContentEvents();      // Task 9
    void registerInputEvents();        // Task 11
    void registerQuestionEvents();     // Task 11
    void onNavigationStarting(ICoreWebView2NavigationStartingEventArgs* args, bool mainFrame);
    void answerQuestionsByDefault();   // Task 11: dialogs, permissions, file choosers
    void runOrQueue(std::function<void()> op);            // Task 8: runs now, or after the script's add completes
    void onResource(ICoreWebView2WebResourceRequestedEventArgs* args);   // Task 9
    static void respondNow(ICoreWebView2WebResourceRequestedEventArgs* args, int status, std::wstring const& headers,
                           std::vector<uint8_t> const& body);
    void onAccelerator(ICoreWebView2AcceleratorKeyPressedEventArgs* args);   // Task 11
    void onCursorChanged();
    void filterContextMenu(ICoreWebView2ContextMenuRequestedEventArgs* args);

    const int64_t token_;
    bool released_ = false;
    // WebView2
    winrt::Windows::UI::Composition::Compositor compositor_{nullptr};
    winrt::Windows::UI::Composition::ContainerVisual root_{nullptr};
    winrt::Windows::UI::Composition::ContainerVisual webVisual_{nullptr};
    winrt::com_ptr<ICoreWebView2CompositionController> composition_;
    winrt::com_ptr<ICoreWebView2Controller4> controller_;
    winrt::com_ptr<ICoreWebView2_22> webview_;
    winrt::com_ptr<ICoreWebView2Settings6> settings_;
    winrt::com_ptr<ICoreWebView2Profile6> profile_;
    std::wstring baseUserAgent_;
    int flags_ = 0;
    // Epochs: the page's current one, each main-frame navigation's, and the current document's.
    int epoch_ = 0;
    int documentEpoch_ = 0;
    std::map<UINT64, int> navigationEpochs_;   // bounded: the 64 newest
    // Task 8
    Signature signature_;
    std::wstring scriptId_;
    bool scriptPending_ = false;
    int64_t scriptGeneration_ = 0;         // which add is the current script's (an older one's id is removed)
    std::deque<std::function<void()>> afterScript_;
    std::map<std::wstring, EventRegistrationToken> devtoolsEvents_;
    // Task 9
    std::wstring floorHtml_;
    std::wstring servedHost_;
    std::optional<std::vector<uint8_t>> servedHtml_;
    struct Resource { winrt::com_ptr<ICoreWebView2WebResourceRequestedEventArgs> args; winrt::com_ptr<ICoreWebView2Deferral> deferral; };
    Questions<Resource> resources_;
    // Task 10
    FrameCapture capture_;
    struct Placement { HWND frame = nullptr; HWND canvas = nullptr; int x = 0, y = 0, width = 0, height = 0; double scale = 1.0; };
    std::optional<Placement> requested_;   // kept until the controller exists (W3)
    Placement applied_;
    HWND parent_ = nullptr;
    HMONITOR monitor_ = nullptr;           // the monitor whose adapter the capture is on (D24)
    // Task 11
    bool hasFocus_ = false;
    std::vector<UINT> takenKeys_;          // key-downs the app took: their key-ups are the app's too
    struct Question { int kind = 0; winrt::com_ptr<IUnknown> args; winrt::com_ptr<ICoreWebView2Deferral> deferral; };
    Questions<Question> questions_;
};

/** The pages, by the engine's token. Any thread: the EDT's pull finds its page here. */
class Pages {
public:
    static void put(int64_t token, std::shared_ptr<WvPage> page);
    static std::shared_ptr<WvPage> find(int64_t token);
    static void remove(int64_t token);
    /** Runs [fn] on every live page, outside the registry's lock (UI thread). */
    static void forEach(std::function<void(WvPage&)> const& fn);

private:
    static std::shared_mutex& lock();
    static std::map<int64_t, std::shared_ptr<WvPage>>& map();
};

/**
 * Wraps a handler so it runs only while the page lives and isn't released (L5): the callback holds the page weakly,
 * and nothing it throws reaches WebView2. The body is the macro's variadic tail, so commas inside it are safe. Used in
 * WvPage's members only (it calls shared_from_this() and reads released_).
 */
#define WV_HANDLER(Iface, Sender, Args, ...)                                                         \
    Microsoft::WRL::Callback<Iface>([weak = std::weak_ptr<WvPage>(shared_from_this())](Sender, Args args) -> HRESULT { \
        auto self = weak.lock();                                                                    \
        if (!self || self->released_) return S_OK;                                                  \
        try { __VA_ARGS__ } catch (...) { logLine(L"a WebView2 handler threw"); }                   \
        return S_OK;                                                                                \
    }).Get()

}  // namespace wv
