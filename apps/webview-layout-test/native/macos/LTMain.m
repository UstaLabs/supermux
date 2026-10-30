#import "LayoutTest.h"

static JavaVM *gVM;
static jmethodID gOnMessage;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    gVM = vm;
    return JNI_VERSION_1_8;
}

@interface LTBlockRunner : NSObject
@end

@implementation LTBlockRunner
+ (void)run:(dispatch_block_t)block {
    block();
}
@end

void LTOnMain(BOOL wait, dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
        return;
    }
    static NSArray<NSString *> *modes;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        // AWT's ThreadUtilities javaModes: also served during live resize, and while AWT spins its own mode waiting on
        // the EDT, so a waiting call from the EDT can't deadlock against AWT.
        modes = @[ NSDefaultRunLoopMode, NSModalPanelRunLoopMode, NSEventTrackingRunLoopMode, @"AWTRunLoopMode" ];
    });
    [LTBlockRunner performSelectorOnMainThread:@selector(run:) withObject:[block copy] waitUntilDone:wait modes:modes];
}

static JNIEnv *LTEnv(void) {
    JNIEnv *env = NULL;
    if (!gVM) return NULL;
    jint status = (*gVM)->GetEnv(gVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED && (*gVM)->AttachCurrentThreadAsDaemon(gVM, (void **)&env, NULL) != JNI_OK) return NULL;
    return env;
}

void LTResolveListener(JNIEnv *env) {
    if (gOnMessage) return;
    jclass type = (*env)->FindClass(env, "dev/supermux/webview/layouttest/PageListener");
    if (!type) {
        (*env)->ExceptionClear(env);
        return;
    }
    gOnMessage = (*env)->GetMethodID(env, type, "onMessage", "(Ljava/lang/String;)V");
    if (!gOnMessage) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, type);
}

void LTSendToJava(jobject listener, NSString *json) {
    JNIEnv *env = LTEnv();
    if (!env || !listener || !gOnMessage || !json) return;
    // The AppKit thread never returns to Java, so nothing frees local references for it: a frame of our own.
    if ((*env)->PushLocalFrame(env, 4) != JNI_OK) return;
    NSUInteger length = json.length;
    unichar *chars = malloc(sizeof(unichar) * MAX(length, (NSUInteger)1));
    [json getCharacters:chars range:NSMakeRange(0, length)];
    jstring string = (*env)->NewString(env, chars, (jsize)length);
    free(chars);
    if (string) (*env)->CallVoidMethod(env, listener, gOnMessage, string);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL);
}

void LTDeleteGlobalRef(jobject ref) {
    JNIEnv *env = LTEnv();
    if (env && ref) (*env)->DeleteGlobalRef(env, ref);
}

NSString *LTString(JNIEnv *env, jstring string) {
    if (!string) return nil;
    const jchar *chars = (*env)->GetStringChars(env, string, NULL);
    if (!chars) return nil;
    NSString *result = [NSString stringWithCharacters:chars length:(NSUInteger)(*env)->GetStringLength(env, string)];
    (*env)->ReleaseStringChars(env, string, chars);
    return result;
}

LTPageView *LTFindPage(NSWindow *window) {
    for (NSView *view in window.contentView.subviews) {
        if ([view isKindOfClass:[LTPageView class]]) return (LTPageView *)view;
    }
    return nil;
}
