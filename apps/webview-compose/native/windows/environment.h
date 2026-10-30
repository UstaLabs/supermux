#pragma once
#include "wv2.h"

namespace wv {

/** The process's WebView2 environment (D8). UI thread only. */
class Environment {
public:
    static Environment& get();
    /**
     * Opens the environment on [folder] once; kEnvironment(request) says how it went, with the browser's version. Asked
     * again for the same folder, it answers at once; for another, E_INVALIDARG: one environment per process. After its
     * browser process exited (kBrowserExited), the next open makes a new one.
     */
    void open(int64_t request, std::wstring folder, std::wstring arguments);
    ICoreWebView2Environment10* env10() const { return env10_.get(); }
    ICoreWebView2Environment3* env3() const { return env3_.get(); }

private:
    void finish(HRESULT hr, std::wstring const& version);
    winrt::com_ptr<ICoreWebView2Environment10> env10_;
    winrt::com_ptr<ICoreWebView2Environment3> env3_;
    winrt::com_ptr<IUnknown> identity_;   // the environment's IUnknown: whose browser process exited
    std::wstring folder_;
    std::wstring version_;
    bool opening_ = false;
    std::vector<int64_t> waiting_;
};

}  // namespace wv
