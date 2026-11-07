// JNI surface of webview_windows_testing.dll for dev.supermux.webview.sample.windows.WindowsInput: the sample's checks
// type, press keys and read the OS back through it. Only the sample loads it; the library's DLL carries no test hooks
// (the macOS review's "test hooks ship in production"). Each body is the wintest jni_bridge.cpp's of the same name.
#include <jni.h>
#include "probes.h"

namespace {

jstring toJava(JNIEnv* env, std::wstring const& text) {
    return env->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
}

std::wstring fromJava(JNIEnv* env, jstring text) {
    if (!text) return L"";
    const jchar* chars = env->GetStringChars(text, nullptr);
    std::wstring out(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(env->GetStringLength(text)));
    env->ReleaseStringChars(text, chars);
    return out;
}

}  // namespace

#define WVT_JNI(type, name) extern "C" JNIEXPORT type JNICALL Java_dev_supermux_webview_sample_windows_WindowsInput_##name

WVT_JNI(void, sendText)(JNIEnv* env, jclass, jstring text) { wv2::sendText(fromJava(env, text)); }

WVT_JNI(void, sendScan)(JNIEnv*, jclass, jint scan, jboolean down, jboolean extended) {
    wv2::sendScan(static_cast<WORD>(scan), down == JNI_TRUE, extended == JNI_TRUE);
}

WVT_JNI(void, sendVk)(JNIEnv*, jclass, jint vk, jboolean down) { wv2::sendVk(static_cast<WORD>(vk), down == JNI_TRUE); }

WVT_JNI(jstring, focusInfo)(JNIEnv* env, jclass) { return toJava(env, wv2::focusInfo()); }

WVT_JNI(jstring, cursorInfo)(JNIEnv* env, jclass) { return toJava(env, wv2::cursorInfo()); }

WVT_JNI(jstring, topLevelWindows)(JNIEnv* env, jclass, jlong pid) {
    return toJava(env, wv2::topLevelWindows(static_cast<DWORD>(pid)));
}

WVT_JNI(jstring, setInputLanguage)(JNIEnv* env, jclass, jstring klid) {
    return toJava(env, wv2::setInputLanguage(fromJava(env, klid)));
}

WVT_JNI(jstring, imeControl)(JNIEnv* env, jclass, jint open, jint conversion) {
    return toJava(env, wv2::imeControl(open, conversion));
}
