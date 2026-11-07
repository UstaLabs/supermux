#import "WVGlue.h"

static JavaVM *gVM;
static jclass gGlue;       // MacGlue, a global reference
static jmethodID gOnEvent; // MacGlue.onEvent

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    gVM = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_VERSION_1_8;
    // The class loader of the class that called System.load: MacGlue's own.
    jclass glue = (*env)->FindClass(env, "dev/supermux/webview/engine/mac/MacGlue");
    if (glue) {
        jmethodID onEvent = (*env)->GetStaticMethodID(
            env, glue, "onEvent",
            "(JIIJIDDLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V");
        if (onEvent) {
            gGlue = (*env)->NewGlobalRef(env, glue);
            gOnEvent = onEvent;
        }
        (*env)->DeleteLocalRef(env, glue);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return JNI_VERSION_1_8;
}

@interface WVBlockRunner : NSObject
+ (void)run:(dispatch_block_t)block;
@end

@implementation WVBlockRunner
+ (void)run:(dispatch_block_t)block {
    block();
}
@end

void WVOnMain(BOOL wait, dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
        return;
    }
    static NSArray<NSString *> *modes;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        // AWT's ThreadUtilities javaModes: also served during live resize, and while AWT spins its own mode waiting on
        // the EDT, so a waiting call from the EDT can't deadlock against AWT (M10).
        modes = @[ NSDefaultRunLoopMode, NSModalPanelRunLoopMode, NSEventTrackingRunLoopMode, @"AWTRunLoopMode" ];
    });
    [WVBlockRunner performSelectorOnMainThread:@selector(run:) withObject:[block copy] waitUntilDone:wait modes:modes];
}

/**
 * The calling thread's JNIEnv, attaching it (as a daemon) if it isn't: then *attached is YES, and the caller detaches it
 * when done (Minor 3). A thread GCD lends for a snapshot's copy would otherwise stay a Java thread for good, one more
 * for every worker thread GCD retires.
 */
static JNIEnv *WVEnv(BOOL *attached) {
    *attached = NO;
    if (!gVM) return NULL;
    JNIEnv *env = NULL;
    jint status = (*gVM)->GetEnv(gVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_OK) return env;
    if (status != JNI_EDETACHED || (*gVM)->AttachCurrentThreadAsDaemon(gVM, (void **)&env, NULL) != JNI_OK) return NULL;
    *attached = YES;
    return env;
}

jstring WVJavaString(JNIEnv *env, NSString *string) {
    if (!string) return NULL;
    NSUInteger length = string.length;
    unichar *chars = malloc(sizeof(unichar) * MAX(length, (NSUInteger)1));
    if (!chars) return NULL;
    [string getCharacters:chars range:NSMakeRange(0, length)];
    jstring result = (*env)->NewString(env, chars, (jsize)length);
    free(chars);
    return result;
}

static void WVPostWith(JNIEnv *env, jlong page, WVEvent type, int epoch, jlong ident, int flags, double x, double y,
                       NSString *a, NSString *b, NSString *c, NSString *d, NSData *data);

void WVPost(jlong page, WVEvent type, int epoch, jlong ident, int flags, double x, double y,
            NSString *a, NSString *b, NSString *c, NSString *d, NSData *data) {
    if (!gGlue || !gOnEvent) return;
    BOOL attached = NO;
    JNIEnv *env = WVEnv(&attached);
    if (!env) return;
    WVPostWith(env, page, type, epoch, ident, flags, x, y, a, b, c, d, data);
    if (attached) (*gVM)->DetachCurrentThread(gVM);
}

static void WVPostWith(JNIEnv *env, jlong page, WVEvent type, int epoch, jlong ident, int flags, double x, double y,
                       NSString *a, NSString *b, NSString *c, NSString *d, NSData *data) {
    // AppKit's thread never returns to Java, so nothing frees its local references: a frame of our own.
    if ((*env)->PushLocalFrame(env, 8) != JNI_OK) {
        (*env)->ExceptionClear(env);
        return;
    }
    jbyteArray bytes = NULL;
    if (data) {
        bytes = (*env)->NewByteArray(env, (jsize)data.length);
        if (bytes && data.length) (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)data.length, (const jbyte *)data.bytes);
    }
    (*env)->CallStaticVoidMethod(env, gGlue, gOnEvent, page, (jint)type, (jint)epoch, ident, (jint)flags, x, y,
                                 WVJavaString(env, a), WVJavaString(env, b), WVJavaString(env, c), WVJavaString(env, d),
                                 bytes);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL);
}

NSString *WVString(JNIEnv *env, jstring string) {
    if (!string) return nil;
    const jchar *chars = (*env)->GetStringChars(env, string, NULL);
    if (!chars) return nil;
    NSString *result = [NSString stringWithCharacters:chars length:(NSUInteger)(*env)->GetStringLength(env, string)];
    (*env)->ReleaseStringChars(env, string, chars);
    return result;
}

NSData *WVBytes(JNIEnv *env, jbyteArray array) {
    if (!array) return nil;
    jsize length = (*env)->GetArrayLength(env, array);
    NSMutableData *data = [NSMutableData dataWithLength:(NSUInteger)length];
    if (length > 0) (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *)data.mutableBytes);
    return data;
}

NSData *WVFloats(JNIEnv *env, jfloatArray array) {
    if (!array) return nil;
    jsize length = (*env)->GetArrayLength(env, array);
    NSMutableData *data = [NSMutableData dataWithLength:sizeof(float) * (NSUInteger)length];
    if (length > 0) (*env)->GetFloatArrayRegion(env, array, 0, length, (jfloat *)data.mutableBytes);
    return data;
}

NSArray<NSString *> *WVStrings(JNIEnv *env, jobjectArray array) {
    if (!array) return nil;
    jsize length = (*env)->GetArrayLength(env, array);
    NSMutableArray<NSString *> *strings = [NSMutableArray arrayWithCapacity:(NSUInteger)length];
    for (jsize i = 0; i < length; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        NSString *string = WVString(env, item);
        if (string) [strings addObject:string];
        if (item) (*env)->DeleteLocalRef(env, item);
    }
    return strings;
}

NSData *WVPixelsOf(CGImageRef image, size_t *width, size_t *height) {
    if (!image) return nil;
    size_t w = CGImageGetWidth(image), h = CGImageGetHeight(image);
    if (w == 0 || h == 0) return nil;
    NSMutableData *pixels = [NSMutableData dataWithLength:w * h * 4];
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef context = CGBitmapContextCreate(pixels.mutableBytes, w, h, 8, w * 4, space,
                                                 kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(space);
    if (!context) return nil;
    CGContextSetBlendMode(context, kCGBlendModeCopy);
    CGContextDrawImage(context, CGRectMake(0, 0, (CGFloat)w, (CGFloat)h), image);
    CGContextRelease(context);
    *width = w;
    *height = h;
    return pixels;
}

static dispatch_source_t gMemoryPressure;

void WVStartMemoryPressure(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        gMemoryPressure = dispatch_source_create(DISPATCH_SOURCE_TYPE_MEMORYPRESSURE, 0,
                                                 DISPATCH_MEMORYPRESSURE_WARN | DISPATCH_MEMORYPRESSURE_CRITICAL,
                                                 dispatch_get_main_queue());
        dispatch_source_set_event_handler(gMemoryPressure, ^{
            WVPost(0, WVEventMemory, 0, 0, 0, 0, 0, nil, nil, nil, nil, nil);
        });
        dispatch_resume(gMemoryPressure);
    });
}

JNIEXPORT jint JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_abiVersion(JNIEnv *env, jclass type) {
    return gOnEvent ? WV_ABI_VERSION : -1;
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_startMemoryPressure(JNIEnv *env, jclass type) {
    WVStartMemoryPressure();
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testMemoryPressure(JNIEnv *env, jclass type) {
    WVPost(0, WVEventMemory, 0, 0, 0, 0, 0, nil, nil, nil, nil, nil);
}
