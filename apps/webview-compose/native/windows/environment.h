#pragma once
#include "wv2.h"

namespace wv {

/** The process's WebView2 environment (D8). UI thread only. */
class Environment {
public:
    static Environment& get();
    /**
     * Opens the environment on [folder] once; kEnvironment(request) says how it went, with the browser's version. Asked
     * again for the same folder, it answers at once; for another, E_INVALIDARG: one environment per process. Its browser
     * process exiting (kBrowserExited) leaves it as it is: WebView2 binds a new browser process to it for the next page.
     */
    void open(int64_t request, std::wstring folder, std::wstring arguments);
    /** [env] can't make pages any more (a page's making failed on it): the next open makes a new one. */
    void forget(ICoreWebView2Environment10* env);
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
