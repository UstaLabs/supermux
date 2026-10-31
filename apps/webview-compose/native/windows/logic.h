#pragma once
// The glue's pure logic: no WebView2, no Direct3D, no JVM, so test/glue_test.cpp tests it alone. Every type here is
// used by one thread at a time unless it says otherwise.
#include <windows.h>
#include <algorithm>
#include <cstdint>
#include <cwchar>
#include <cwctype>
#include <functional>
#include <map>
#include <string>
#include <string_view>

namespace wv {

// --- The document script's messages (D5) ---

/**
 * The current epoch's secret: every message the document script posts starts with it. A message without it (a page's
 * own chrome.webview.postMessage, or a document of an earlier epoch) is dropped before anything else sees it.
 */
class Signature {
public:
    static constexpr size_t kLength = 32;  // 128 bits as hex

    void set(std::wstring secret, int epoch) {
        secret_ = std::move(secret);
        epoch_ = epoch;
    }
    int epoch() const { return epoch_; }
    int64_t dropped() const { return dropped_; }
    bool matches(std::wstring_view body) const {
        return secret_.size() == kLength && body.size() > kLength && body.compare(0, kLength, secret_) == 0;
    }
    /** True, with [rest] what follows the secret, if [body] carries it; counts what it drops. */
    bool accept(std::wstring_view body, std::wstring_view& rest) {
        if (!matches(body)) {
            ++dropped_;
            return false;
        }
        rest = body.substr(kLength);
        return true;
    }

private:
    std::wstring secret_;
    int epoch_ = 0;
    int64_t dropped_ = 0;
};

// --- Frames (D3) ---

/** The part of a page's frame changed since the last pull: one box, clipped to the frame. */
class Damage {
public:
    /** The frame is now [width] × [height], all of it changed. */
    void whole(int width, int height) {
        width_ = width;
        height_ = height;
        box_ = RECT{0, 0, width, height};
        has_ = width > 0 && height > 0;
    }
    void add(RECT r) {
        r.left = std::max<LONG>(r.left, 0);
        r.top = std::max<LONG>(r.top, 0);
        r.right = std::min<LONG>(r.right, width_);
        r.bottom = std::min<LONG>(r.bottom, height_);
        if (r.right <= r.left || r.bottom <= r.top) return;
        if (!has_) {
            box_ = r;
            has_ = true;
            return;
        }
        box_.left = std::min(box_.left, r.left);
        box_.top = std::min(box_.top, r.top);
        box_.right = std::max(box_.right, r.right);
        box_.bottom = std::max(box_.bottom, r.bottom);
    }
    /** Takes the changed box, if anything changed. */
    bool take(RECT& out) {
        if (!has_) return false;
        out = box_;
        has_ = false;
        return true;
    }
    /** A pull that couldn't copy: the box waits for the next. */
    void putBack(RECT r) { add(r); }

private:
    RECT box_{0, 0, 0, 0};
    // No size known yet (no whole()): nothing is clipped.
    int width_ = 0x7fffffff;
    int height_ = 0x7fffffff;
    bool has_ = false;
};

/**
 * Two staging textures written by turns (D3, W23): the capture thread copies a frame into [writing()], waits for that
 * copy, and [published] makes it the [ready()] one, which the EDT maps; the next frame goes into the other. A texture
 * also takes what changed while frames went into the other one, so whichever is ready is whole. Bookkeeping only (the
 * textures and the fence are capture.cpp's), under the capture's locks.
 */
class StagingTurns {
public:
    /** New textures of [width] × [height] (a new size or device): both hold nothing, so each one's first copy is whole. */
    void reset(int width, int height) {
        width_ = width;
        height_ = height;
        writing_ = 0;
        ready_ = -1;
        for (int i = 0; i < 2; ++i) {
            missed_[i] = RECT{0, 0, width, height};
            missedAny_[i] = width > 0 && height > 0;
        }
    }
    int writing() const { return writing_; }
    /** The texture the EDT may map: -1 until a copy is published. */
    int ready() const { return ready_; }
    /** What copying a frame changed in [frame] into the texture being written must cover: [frame] and what it missed. */
    RECT region(RECT frame) const {
        RECT r = clip(frame);
        if (missedAny_[writing_]) r = empty(r) ? missed_[writing_] : unite(r, missed_[writing_]);
        return r;
    }
    /** The copy into [writing()] of a frame changed in [frame] finished: that texture is ready, the other missed [frame]. */
    void published(RECT frame) {
        const int other = 1 - writing_;
        missedAny_[writing_] = false;
        const RECT f = clip(frame);
        if (!empty(f)) {
            missed_[other] = missedAny_[other] ? unite(missed_[other], f) : f;
            missedAny_[other] = true;
        }
        ready_ = writing_;
        writing_ = other;
    }

private:
    RECT clip(RECT r) const {
        return RECT{std::max<LONG>(r.left, 0), std::max<LONG>(r.top, 0), std::min<LONG>(r.right, width_), std::min<LONG>(r.bottom, height_)};
    }
    static bool empty(RECT r) { return r.right <= r.left || r.bottom <= r.top; }
    static RECT unite(RECT a, RECT b) {
        return RECT{std::min(a.left, b.left), std::min(a.top, b.top), std::max(a.right, b.right), std::max(a.bottom, b.bottom)};
    }

    int width_ = 0, height_ = 0, writing_ = 0, ready_ = -1;
    RECT missed_[2]{};
    bool missedAny_[2]{};
};

// --- Questions (D18) ---

/**
 * What WebView2 waits on (a deferral and its arguments), by id: answered exactly once, by the client's answer or by
 * the safe default at a new document, a reset or a release.
 */
template <typename T>
class Questions {
public:
    int64_t open(T value) {
        const int64_t id = next_++;
        open_.emplace(id, std::move(value));
        return id;
    }
    bool answer(int64_t id, std::function<void(T&)> const& fn) {
        auto it = open_.find(id);
        if (it == open_.end()) return false;
        T value = std::move(it->second);
        open_.erase(it);
        fn(value);
        return true;
    }
    void answerAll(std::function<void(T&)> const& byDefault) {
        auto all = std::move(open_);
        open_.clear();
        for (auto& [id, value] : all) byDefault(value);
    }
    size_t size() const { return open_.size(); }

private:
    std::map<int64_t, T> open_;
    int64_t next_ = 1;
};

// --- Script text ---

/** [text] as a JavaScript string literal (JSON's rules, plus U+2028/9 and `</`), for ExecuteScript's arguments. */
inline std::wstring jsString(std::wstring_view text) {
    std::wstring out;
    out.reserve(text.size() + 2);
    out += L'"';
    for (size_t i = 0; i < text.size(); ++i) {
        const wchar_t c = text[i];
        switch (c) {
            case L'"': out += L"\\\""; break;
            case L'\\': out += L"\\\\"; break;
            case L'\n': out += L"\\n"; break;
            case L'\r': out += L"\\r"; break;
            case L'\t': out += L"\\t"; break;
            case L'/': out += (i > 0 && text[i - 1] == L'<') ? L"\\/" : L"/"; break;
            default:
                if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                    wchar_t buffer[8];
                    swprintf_s(buffer, L"\\u%04x", static_cast<unsigned>(c));
                    out += buffer;
                } else {
                    out += c;
                }
        }
    }
    out += L'"';
    return out;
}

// --- Navigations (D9) ---

constexpr int kNavMainFrame = 1;
constexpr int kNavRedirect = 2;
constexpr int kNavUserInitiated = 4;
constexpr int kNavKindShift = 4;  // (COREWEBVIEW2_NAVIGATION_KIND + 1) << 4; 0: not known

inline int navFlags(bool mainFrame, bool redirect, bool userInitiated, int kind) {
    return (mainFrame ? kNavMainFrame : 0) | (redirect ? kNavRedirect : 0) | (userInitiated ? kNavUserInitiated : 0) |
           ((kind + 1) << kNavKindShift);
}
inline int navKind(int flags) { return ((flags >> kNavKindShift) & 0xF) - 1; }

// The decision's verdicts (WinGlue.decideNavigation's answer, Wv2Navigation's).
constexpr int kAllow = 0;      // go on
constexpr int kRefuse = 1;     // cancel
constexpr int kOwn = 2;        // go on: the engine's own navigation
constexpr int kRefuseOwn = 3;  // cancel: the engine's own navigation went somewhere it may not (a redirect)

/**
 * The main frame's navigation on its way (D7, D9): its id, the epoch it started in and its URL without the fragment, from
 * its NavigationStarting (each redirect's too) until its commit or its end. Its document's own request is its epoch's: a
 * load's first request comes before any document of that epoch commits, so the last committed document's epoch is an
 * earlier one, whose requests the engine no longer answers (a bundle's first load failed as an unknown host's).
 */
class PendingNavigation {
public:
    void started(uint64_t id, int epoch, std::wstring_view url) {
        pending_ = true;
        id_ = id;
        epoch_ = epoch;
        url_ = withoutFragment(url);
    }
    /** [id] committed, or ended without a commit: a later navigation's start replaced it already if it isn't the one. */
    void ended(uint64_t id) {
        if (pending_ && id == id_) pending_ = false;
    }
    /** The epoch of a request for [url]: the pending navigation's if it is that navigation's document, else [documentEpoch]. */
    int epochOf(std::wstring_view url, bool isDocument, int documentEpoch) const {
        return pending_ && isDocument && withoutFragment(url) == url_ ? epoch_ : documentEpoch;
    }

private:
    static std::wstring withoutFragment(std::wstring_view url) { return std::wstring(url.substr(0, url.find(L'#'))); }
    bool pending_ = false;
    uint64_t id_ = 0;
    int epoch_ = 0;
    std::wstring url_;
};

// --- URLs, for the local content filter (D7) ---

/** The host of an https URL on the default port with no userinfo, lowercase; empty for anything else. */
inline std::wstring httpsHost(std::wstring_view url) {
    constexpr std::wstring_view scheme = L"https://";
    if (url.size() <= scheme.size() || _wcsnicmp(url.data(), scheme.data(), scheme.size()) != 0) return L"";
    const std::wstring_view rest = url.substr(scheme.size());
    const size_t end = rest.find_first_of(L"/?#");
    const std::wstring_view authority = end == std::wstring_view::npos ? rest : rest.substr(0, end);
    if (authority.find(L'@') != std::wstring_view::npos) return L"";
    std::wstring_view host = authority;
    if (const size_t colon = authority.rfind(L':'); colon != std::wstring_view::npos) {
        if (authority.substr(colon + 1) != L"443") return L"";
        host = authority.substr(0, colon);
    }
    std::wstring out(host);
    for (auto& c : out) c = static_cast<wchar_t>(towlower(c));
    return out;
}

/** The origin of an http(s) URL, "scheme://host[:port]" lowercase (no userinfo), as a profile's site settings key it; empty for any other URL. */
inline std::wstring originOf(std::wstring_view url) {
    const size_t sep = url.find(L"://");
    if (sep == std::wstring_view::npos) return L"";
    std::wstring scheme(url.substr(0, sep));
    for (auto& c : scheme) c = static_cast<wchar_t>(towlower(c));
    if (scheme != L"http" && scheme != L"https") return L"";
    const std::wstring_view rest = url.substr(sep + 3);
    std::wstring_view authority = rest.substr(0, rest.find_first_of(L"/?#"));
    if (const size_t at = authority.rfind(L'@'); at != std::wstring_view::npos) authority = authority.substr(at + 1);
    if (authority.empty()) return L"";
    std::wstring out = scheme + L"://" + std::wstring(authority);
    for (auto& c : out) c = static_cast<wchar_t>(towlower(c));
    return out;
}

/**
 * The pages of this process that let each (profile, origin) autoplay (EngineConfig.mediaPlaybackRequiresGesture false):
 * the profile's AUTOPLAY site setting is ALLOW while one of them shows the origin, and the default again only once the
 * last one is released. Keys are the profile's path and the origin. The glue's UI thread only.
 */
class AutoplayGrants {
public:
    /** A page now lets [key] autoplay: true when it is the first, and the setting is to be ALLOW. */
    bool acquire(std::wstring const& key) { return counts_[key]++ == 0; }

    /** A page that let [key] autoplay is released: true when it was the last, and the setting goes back to the default. */
    bool release(std::wstring const& key) {
        auto it = counts_.find(key);
        if (it == counts_.end()) return false;
        if (--it->second > 0) return false;
        counts_.erase(it);
        return true;
    }

private:
    std::map<std::wstring, int> counts_;
};

/** The path of a hierarchical URL, "/" when it has none. */
inline std::wstring pathOf(std::wstring_view url) {
    const size_t start = url.find(L"://");
    if (start == std::wstring_view::npos) return L"/";
    const std::wstring_view rest = url.substr(start + 3);
    const size_t slash = rest.find_first_of(L"/?#");
    if (slash == std::wstring_view::npos || rest[slash] != L'/') return L"/";
    const std::wstring_view path = rest.substr(slash);
    return std::wstring(path.substr(0, path.find_first_of(L"?#")));
}

/**
 * The context menu's items a page keeps (D17), by WebView2's item name: editing, copying links and images; Inspect only
 * while inspectable. Nothing that saves, prints, navigates or opens a window.
 */
inline bool keepMenuItem(std::wstring_view name, bool inspectable) {
    static constexpr std::wstring_view kKept[] = {L"undo", L"redo", L"cut", L"copy", L"paste", L"pasteAndMatchStyle",
                                                  L"selectAll", L"emoji", L"copyLinkLocation", L"copyImage",
                                                  L"copyImageLocation"};
    if (name == L"inspectElement") return inspectable;
    for (auto kept : kKept) if (name == kept) return true;
    return false;
}

}  // namespace wv
