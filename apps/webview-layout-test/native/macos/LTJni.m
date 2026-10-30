#import "LayoutTest.h"

static inline LTPageView *LTPage(jlong handle) {
    return (__bridge LTPageView *)(void *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL Java_dev_supermux_webview_layouttest_Native_attach(JNIEnv *env, jclass type, jlong window,
                                                                          jstring html, jobject listener) {
    LTResolveListener(env);
    NSWindow *nsWindow = LTWindow(window);
    NSString *page = LTString(env, html);
    jobject ref = (*env)->NewGlobalRef(env, listener);
    __block LTPageView *view = nil;
    LTOnMain(YES, ^{
        view = [[LTPageView alloc] initWithHTML:page listener:ref];
        // A subview of AWT's content view: its layer joins skiko's AWTMetalLayer in one sublayer list.
        [nsWindow.contentView addSubview:view];
    });
    return (jlong)(intptr_t)CFBridgingRetain(view);
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_setLayout(JNIEnv *env, jclass type, jlong page,
                                                                            jint layout) {
    LTPageView *view = LTPage(page);
    LTOnMain(NO, ^{
        view.layout = layout == 1 ? LTLayoutOver : LTLayoutUnder;
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_setFrame(JNIEnv *env, jclass type, jlong page,
                                                                           jfloat density, jfloat left, jfloat top,
                                                                           jfloat width, jfloat height) {
    LTPageView *view = LTPage(page);
    LTOnMain(NO, ^{
        [view placeAtLeft:left top:top width:width height:height density:density];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_setCutouts(JNIEnv *env, jclass type, jlong page,
                                                                             jfloat density, jfloatArray geometry,
                                                                             jint holeCount, jintArray scrimColors) {
    jsize floats = (*env)->GetArrayLength(env, geometry);
    jsize colors = (*env)->GetArrayLength(env, scrimColors);
    NSMutableData *g = [NSMutableData dataWithLength:sizeof(float) * (NSUInteger)floats];
    NSMutableData *c = [NSMutableData dataWithLength:sizeof(int) * (NSUInteger)colors];
    (*env)->GetFloatArrayRegion(env, geometry, 0, floats, g.mutableBytes);
    (*env)->GetIntArrayRegion(env, scrimColors, 0, colors, c.mutableBytes);
    LTPageView *view = LTPage(page);
    LTOnMain(NO, ^{
        [view setCutouts:g holes:holeCount scrimColors:c density:density];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_evaluate(JNIEnv *env, jclass type, jlong page,
                                                                           jstring script) {
    NSString *js = LTString(env, script);
    LTPageView *view = LTPage(page);
    LTOnMain(NO, ^{
        [view.webView evaluateJavaScript:js completionHandler:nil];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_setKeyRouting(JNIEnv *env, jclass type, jlong page,
                                                                                jboolean enabled) {
    LTPageView *view = LTPage(page);
    LTOnMain(NO, ^{
        [view setKeyRouting:enabled == JNI_TRUE];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_detach(JNIEnv *env, jclass type, jlong page) {
    LTPageView *view = LTPage(page);
    LTOnMain(YES, ^{
        [view detach];
    });
    CFBridgingRelease((void *)(intptr_t)page);
}
