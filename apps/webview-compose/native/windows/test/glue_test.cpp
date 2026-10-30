// apps/webview-compose/native/windows/test/glue_test.cpp
// The glue's pure logic (logic.h), without WebView2, Direct3D or a JVM. Built and run on the VM by build.cmd x64 before
// the DLL is linked: exit 0 when every test passed.
#include "../logic.h"
#include <cstdio>

static int failures = 0;
static int checks = 0;
#define CHECK(cond) do { ++checks; if (!(cond)) { ++failures; std::printf("  FAIL %s:%d: %s\n", __func__, __LINE__, #cond); } } while (0)

static void signatureAcceptsOnlyTheEpochsSecret() {
    wv::Signature s;
    CHECK(!s.matches(L"anything"));                       // no secret yet: nothing passes
    s.set(L"0123456789abcdef0123456789abcdef", 7);
    std::wstring_view rest;
    CHECK(s.accept(L"0123456789abcdef0123456789abcdefaaaabbbbccccdddd start", rest));
    CHECK(rest == L"aaaabbbbccccdddd start");
    CHECK(!s.accept(L"0123456789abcdef0123456789abcdee" L"aaaabbbbccccdddd start", rest));  // one digit off
    CHECK(!s.accept(L"0123456789abcdef", rest));          // the secret alone, cut short
    CHECK(!s.accept(L"", rest));
    CHECK(s.epoch() == 7);
    s.set(L"ffffffffffffffffffffffffffffffff", 8);        // a new epoch: the old secret is refused from now on
    CHECK(!s.accept(L"0123456789abcdef0123456789abcdefaaaabbbbccccdddd start", rest));
    CHECK(s.dropped() == 4);
}

static void damageAccumulatesUntilTaken() {
    wv::Damage d;
    RECT r{};
    CHECK(!d.take(r));
    d.add(RECT{10, 10, 20, 20});
    d.add(RECT{30, 5, 40, 15});
    CHECK(d.take(r) && r.left == 10 && r.top == 5 && r.right == 40 && r.bottom == 20);
    CHECK(!d.take(r));                                   // taken: nothing left
    d.add(RECT{0, 0, 0, 5});                             // empty rectangles count for nothing
    CHECK(!d.take(r));
    d.whole(100, 50);
    CHECK(d.take(r) && r.left == 0 && r.top == 0 && r.right == 100 && r.bottom == 50);
    d.add(RECT{90, 40, 120, 70});                        // clipped to the last whole size
    CHECK(d.take(r) && r.right == 100 && r.bottom == 50);
}

static void damagePutBackStaysForTheNextPull() {
    wv::Damage d;
    RECT r{};
    d.whole(64, 64);
    d.add(RECT{1, 1, 2, 2});
    CHECK(d.take(r));
    d.putBack(r);                                        // the map wasn't ready: the same region next time
    RECT again{};
    CHECK(d.take(again) && again.left == r.left && again.bottom == r.bottom);
}

static void stagingTexturesTakeTurnsAndCatchUp() {
    wv::StagingTurns t;
    t.reset(100, 50);
    CHECK(t.writing() == 0 && t.ready() == -1);                 // nothing to map before the first copy is published
    RECT r = t.region(RECT{10, 10, 20, 20});
    CHECK(r.left == 0 && r.top == 0 && r.right == 100 && r.bottom == 50);   // a new texture's first copy is whole
    t.published(RECT{10, 10, 20, 20});
    CHECK(t.ready() == 0 && t.writing() == 1);
    r = t.region(RECT{30, 30, 40, 40});
    CHECK(r.right == 100 && r.bottom == 50);                    // the other texture's first copy: whole too
    t.published(RECT{30, 30, 40, 40});
    CHECK(t.ready() == 1 && t.writing() == 0);
    r = t.region(RECT{50, 5, 60, 15});                          // texture 0 missed 30..40 while 1 was written
    CHECK(r.left == 30 && r.top == 5 && r.right == 60 && r.bottom == 40);
    t.published(RECT{50, 5, 60, 15});
    CHECK(t.ready() == 0 && t.writing() == 1);
    r = t.region(RECT{0, 0, 1, 1});                             // texture 1 missed only frame 3: its last copy had the rest
    CHECK(r.left == 0 && r.top == 0 && r.right == 60 && r.bottom == 15);
}

static void stagingResetMakesBothWhole() {
    wv::StagingTurns t;
    t.reset(10, 10);
    t.published(RECT{0, 0, 10, 10});
    t.reset(20, 20);                                            // a new size or device: new textures, nothing ready
    CHECK(t.ready() == -1 && t.writing() == 0);
    RECT r = t.region(RECT{1, 1, 2, 2});
    CHECK(r.left == 0 && r.top == 0 && r.right == 20 && r.bottom == 20);
    r = t.region(RECT{-5, -5, 50, 50});                         // clipped to the textures' size
    CHECK(r.right == 20 && r.bottom == 20);
}

static void questionsAreAnsweredExactlyOnce() {
    wv::Questions<int> q;
    const int64_t a = q.open(1), b = q.open(2);
    int answered = 0;
    CHECK(q.answer(a, [&](int& v) { answered += v; }));
    CHECK(!q.answer(a, [&](int& v) { answered += 100 * v; }));   // a second answer reaches nothing
    CHECK(answered == 1);
    q.answerAll([&](int& v) { answered += 10 * v; });            // the safe default for what is still open
    CHECK(answered == 21);
    CHECK(!q.answer(b, [&](int& v) { answered += 1000; }));
    CHECK(q.size() == 0);
}

static void jsStringsSurviveAnyText() {
    CHECK(wv::jsString(L"a\"b\\c") == L"\"a\\\"b\\\\c\"");
    CHECK(wv::jsString(L"line\nnext\r\t") == L"\"line\\nnext\\r\\t\"");
    CHECK(wv::jsString(std::wstring(L"x") + wchar_t(0x2028) + wchar_t(0x2029)) == L"\"x\\u2028\\u2029\"");
    CHECK(wv::jsString(std::wstring(1, wchar_t(1))) == L"\"\\u0001\"");
    CHECK(wv::jsString(L"</script>") == L"\"<\\/script>\"");
}

static void navigationFlagsRoundTrip() {
    const int f = wv::navFlags(true, true, false, 1 /* BACK_OR_FORWARD */);
    CHECK((f & wv::kNavMainFrame) && (f & wv::kNavRedirect) && !(f & wv::kNavUserInitiated));
    CHECK(wv::navKind(f) == 1);
    CHECK(wv::navKind(wv::navFlags(false, false, true, -1)) == -1);   // no kind known (an older runtime)
}

static void hostsAreParsedWithoutGuessing() {
    CHECK(wv::httpsHost(L"https://cabc.invalid/") == L"cabc.invalid");
    CHECK(wv::httpsHost(L"HTTPS://CAbc.Invalid:443/x?y") == L"cabc.invalid");
    CHECK(wv::httpsHost(L"https://user@cabc.invalid/") == L"");        // userinfo: never ours
    CHECK(wv::httpsHost(L"http://cabc.invalid/") == L"");              // http: never ours
    CHECK(wv::httpsHost(L"https://cabc.invalid:8443/") == L"");        // another port: never ours
    CHECK(wv::pathOf(L"https://cabc.invalid/a/b?c#d") == L"/a/b");
    CHECK(wv::pathOf(L"https://cabc.invalid") == L"/");
}

int main() {
    int tests = 0;
    signatureAcceptsOnlyTheEpochsSecret(); ++tests;
    damageAccumulatesUntilTaken(); ++tests;
    damagePutBackStaysForTheNextPull(); ++tests;
    stagingTexturesTakeTurnsAndCatchUp(); ++tests;
    stagingResetMakesBothWhole(); ++tests;
    questionsAreAnsweredExactlyOnce(); ++tests;
    jsStringsSurviveAnyText(); ++tests;
    navigationFlagsRoundTrip(); ++tests;
    hostsAreParsedWithoutGuessing(); ++tests;
    std::printf("glue_test: %s (%d failure(s)), %d tests, %d checks\n", failures == 0 ? "PASS" : "FAIL", failures, tests, checks);
    return failures == 0 ? 0 : 1;
}
