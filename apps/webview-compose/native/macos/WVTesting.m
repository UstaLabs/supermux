#import "WVGlue.h"
#import <Carbon/Carbon.h>
#include <dlfcn.h>
#include <pthread.h>

// The test driver the sample's engine checks use (MacEngineTesting), from the layout test's LTInput and LTProbe. Input
// goes into this app's own queue, never CGEventPost: no Accessibility permission, the cursor doesn't move, and nothing
// lands in another app. From nextEventMatchingMask: on, it takes real input's path: sendEvent:, the local monitors,
// the window's hit test.

/** Compose window points (the content's top-left origin) → the window's base coordinates (bottom-left origin). */
static NSPoint WVWindowPoint(NSWindow *window, CGFloat x, CGFloat y) {
    NSView *content = window.contentView;
    return [content convertPoint:NSMakePoint(x, content.bounds.size.height - y) toView:nil];
}

static void WVPostMouse(NSWindow *window, NSEventType type, NSPoint at) {
    NSEvent *event = [NSEvent mouseEventWithType:type location:at modifierFlags:0
                                       timestamp:NSProcessInfo.processInfo.systemUptime windowNumber:window.windowNumber
                                         context:nil eventNumber:0 clickCount:1
                                        pressure:type == NSEventTypeLeftMouseDown ? 1.0f : 0.0f];
    [NSApp postEvent:event atStart:NO];
}

static void WVPostKey(NSWindow *window, NSString *characters, unsigned short keyCode, NSEventModifierFlags flags) {
    for (int up = 0; up < 2; up++) {
        NSEvent *event = [NSEvent keyEventWithType:up ? NSEventTypeKeyUp : NSEventTypeKeyDown location:NSZeroPoint
                                     modifierFlags:flags timestamp:NSProcessInfo.processInfo.systemUptime
                                      windowNumber:window.windowNumber context:nil characters:characters
                       charactersIgnoringModifiers:characters isARepeat:NO keyCode:keyCode];
        [NSApp postEvent:event atStart:NO];
    }
}

/** The ANSI key codes of a…z. */
static const unsigned short WVLetterKeys[26] = {
    kVK_ANSI_A, kVK_ANSI_B, kVK_ANSI_C, kVK_ANSI_D, kVK_ANSI_E, kVK_ANSI_F, kVK_ANSI_G, kVK_ANSI_H, kVK_ANSI_I,
    kVK_ANSI_J, kVK_ANSI_K, kVK_ANSI_L, kVK_ANSI_M, kVK_ANSI_N, kVK_ANSI_O, kVK_ANSI_P, kVK_ANSI_Q, kVK_ANSI_R,
    kVK_ANSI_S, kVK_ANSI_T, kVK_ANSI_U, kVK_ANSI_V, kVK_ANSI_W, kVK_ANSI_X, kVK_ANSI_Y, kVK_ANSI_Z,
};

static WVPageView *WVFirstPageView(NSWindow *window) {
    for (NSView *view in window.contentView.subviews) {
        if ([view isKindOfClass:[WVPageView class]]) return (WVPageView *)view;
    }
    return nil;
}

/** Whether target is reachable from root through accessibilityChildren (the layout test's probe). */
static BOOL WVReaches(id root, id target) {
    NSMutableArray *stack = [NSMutableArray arrayWithObject:root];
    NSHashTable *seen = [NSHashTable hashTableWithOptions:NSPointerFunctionsObjectPointerPersonality | NSPointerFunctionsStrongMemory];
    NSUInteger nodes = 0;
    while (stack.count > 0 && nodes < 5000) {
        id node = stack.lastObject;
        [stack removeLastObject];
        if ([seen containsObject:node]) continue;
        [seen addObject:node];
        nodes++;
        if (node == target) return YES;
        if (![node respondsToSelector:@selector(accessibilityChildren)]) continue;
        NSArray *children = [node accessibilityChildren];
        if (![children isKindOfClass:[NSArray class]]) continue;
        for (id child in children) [stack addObject:child];
    }
    return NO;
}

typedef CGImageRef (*WVCreateWindowImage)(CGRect, CGWindowListOption, CGWindowID, CGWindowImageOption);

/**
 * One window as the window server composited it, within area (global display points): CGWindowListCreateImage, found
 * at run time because newer SDKs mark it unavailable (M12). Without Screen Recording permission it still copies the
 * app's own windows; the checks prove that before they trust it.
 */
static CGImageRef WVCopyWindowImage(CGRect area, CGWindowID window) {
    static WVCreateWindowImage create;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        create = (WVCreateWindowImage)dlsym(RTLD_DEFAULT, "CGWindowListCreateImage");
    });
    if (!create) return NULL;
    return create(area, kCGWindowListOptionIncludingWindow, window, kCGWindowImageBoundsIgnoreFraming | kCGWindowImageBestResolution);
}

JNIEXPORT jboolean JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testActivate(JNIEnv *env, jclass type, jlong window) {
    NSWindow *w = WVWindow(window);
    __block BOOL key = NO;
    WVOnMain(YES, ^{
        [NSApp activateIgnoringOtherApps:YES];
        [w makeKeyAndOrderFront:nil];
        key = NSApp.isActive && w.isKeyWindow;
    });
    return key ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testClick(JNIEnv *env, jclass type, jlong window, jfloat x, jfloat y) {
    NSWindow *w = WVWindow(window);
    WVOnMain(NO, ^{
        NSPoint at = WVWindowPoint(w, x, y);
        WVPostMouse(w, NSEventTypeLeftMouseDown, at);
        WVPostMouse(w, NSEventTypeLeftMouseUp, at);
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testKeys(
    JNIEnv *env, jclass type, jlong window, jstring text, jboolean command) {
    NSWindow *w = WVWindow(window);
    NSString *keys = WVString(env, text);
    NSEventModifierFlags flags = command == JNI_TRUE ? NSEventModifierFlagCommand : 0;
    WVOnMain(NO, ^{
        for (NSUInteger i = 0; i < keys.length; i++) {
            unichar c = [keys characterAtIndex:i];
            if (c < 'a' || c > 'z') continue; // lowercase ASCII letters only: their key codes need no layout table
            WVPostKey(w, [NSString stringWithCharacters:&c length:1], WVLetterKeys[c - 'a'], flags);
        }
    });
}

JNIEXPORT jstring JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testFirstResponder(JNIEnv *env, jclass type, jlong window) {
    NSWindow *w = WVWindow(window);
    __block NSString *answer = @"none";
    WVOnMain(YES, ^{
        NSResponder *responder = w.firstResponder;
        NSString *side = @"other";
        if (WVFocusedPageView(w)) {
            side = @"page";
        } else if (responder == w.contentView) {
            side = @"compose";
        }
        answer = [NSString stringWithFormat:@"%@ %@", side, responder ? NSStringFromClass(responder.class) : @"nil"];
    });
    return WVJavaString(env, answer);
}

/** Waits for AppKit's thread, whose accessibility calls ask Java on the EDT: call it off the EDT. */
JNIEXPORT jboolean JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testAccessibilityReaches(JNIEnv *env, jclass type, jlong window) {
    NSWindow *w = WVWindow(window);
    __block BOOL reaches = NO;
    WVOnMain(YES, ^{
        WVPageView *page = WVFirstPageView(w);
        if (page) reaches = WVReaches(w, page.webView);
    });
    return reaches ? JNI_TRUE : JNI_FALSE;
}

/** The window's pixels in (x, y, width, height) (points from the content's top-left): 8 bytes of width and height, big-endian, then RGBA. */
JNIEXPORT jbyteArray JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testCopyWindow(
    JNIEnv *env, jclass type, jlong window, jfloat x, jfloat y, jfloat width, jfloat height) {
    NSWindow *w = WVWindow(window);
    __block CGRect area = CGRectNull;
    __block CGWindowID number = 0;
    WVOnMain(YES, ^{
        NSView *content = w.contentView;
        NSScreen *primary = NSScreen.screens.firstObject;
        if (!content || !primary) return;
        NSRect inContent = NSMakeRect(x, content.bounds.size.height - y - height, width, height);
        NSRect onScreen = [w convertRectToScreen:[content convertRect:inContent toView:nil]];
        // Quartz's global coordinates start at the primary display's top-left.
        area = CGRectMake(onScreen.origin.x, NSMaxY(primary.frame) - NSMaxY(onScreen), onScreen.size.width, onScreen.size.height);
        number = (CGWindowID)w.windowNumber;
    });
    if (number == 0 || CGRectIsNull(area)) return NULL;
    // Copied on the calling thread: CoreGraphics' window list is thread-safe, and AppKit's thread stays free.
    CGImageRef image = WVCopyWindowImage(area, number);
    size_t pixelWidth = 0, pixelHeight = 0;
    NSData *pixels = WVPixelsOf(image, &pixelWidth, &pixelHeight);
    if (image) CGImageRelease(image);
    if (!pixels) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)(8 + pixels.length));
    if (!result) return NULL;
    jbyte header[8];
    uint32_t across = (uint32_t)pixelWidth, down = (uint32_t)pixelHeight;
    for (int i = 0; i < 4; i++) {
        header[i] = (jbyte)(across >> (24 - 8 * i));
        header[4 + i] = (jbyte)(down >> (24 - 8 * i));
    }
    (*env)->SetByteArrayRegion(env, result, 0, 8, header);
    (*env)->SetByteArrayRegion(env, result, 8, (jsize)pixels.length, (const jbyte *)pixels.bytes);
    return result;
}

/** What shows where neither Compose nor a page draws: a colour the checks can't mistake for either. */
JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testPaintBacking(JNIEnv *env, jclass type, jlong window, jint argb) {
    NSWindow *w = WVWindow(window);
    WVOnMain(NO, ^{
        CALayer *layer = w.contentView.layer;
        if (!layer) return;
        CGColorRef color = CGColorCreateSRGB(((argb >> 16) & 0xFF) / 255.0, ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0,
                                             ((argb >> 24) & 0xFF) / 255.0);
        layer.backgroundColor = color;
        CGColorRelease(color);
    });
}

JNIEXPORT jstring JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testDescribePage(JNIEnv *env, jclass type, jlong window) {
    NSWindow *w = WVWindow(window);
    __block NSString *answer = @"no page view in the window";
    WVOnMain(YES, ^{
        WVPageView *page = WVFirstPageView(w);
        if (!page) return;
        answer = [NSString stringWithFormat:@"%@, %@, interactive=%d shown=%d frame=%@ z=%g",
                                            page.window ? @"in its window" : @"in no window", page.hidden ? @"hidden" : @"not hidden",
                                            page.interactive, page.shown, NSStringFromRect(page.frame), page.layer.zPosition];
    });
    return WVJavaString(env, answer);
}

/** Who a press (or with wheel, a scroll) at (x, y), points from the content's top-left, goes to: "page" or "compose". */
JNIEXPORT jstring JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testRoutes(
    JNIEnv *env, jclass type, jlong window, jfloat x, jfloat y, jboolean wheel) {
    NSWindow *w = WVWindow(window);
    NSEventType kind = wheel == JNI_TRUE ? NSEventTypeScrollWheel : NSEventTypeLeftMouseDown;
    __block NSString *answer = @"compose";
    WVOnMain(YES, ^{
        NSView *content = w.contentView;
        NSPoint inContent = NSMakePoint(x, content.bounds.size.height - y);
        for (NSView *view in content.subviews) {
            if (![view isKindOfClass:[WVPageView class]] || !NSPointInRect(inContent, view.frame)) continue;
            WVPageView *page = (WVPageView *)view;
            if ([page takesEventOfType:kind at:[page convertPoint:inContent fromView:content]]) answer = @"page";
        }
    });
    return WVJavaString(env, answer);
}

/**
 * Posts an upcall (of a type the library drops) from count threads of its own, one after another, each of which then
 * ends, as GCD's workers do once retired: MacGlueThreadsMacTest counts the JVM's threads around it (Minor 3).
 */
static void *WVPostAndEnd(void *unused) {
    (void)unused;
    WVPost(0, (WVEvent)0, 0, 0, 0, 0, 0, nil, nil, nil, nil, nil);
    return NULL;
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testPostFromThreads(JNIEnv *env, jclass type, jint count) {
    for (jint i = 0; i < count; i++) {
        pthread_t thread;
        if (pthread_create(&thread, NULL, WVPostAndEnd, NULL) == 0) pthread_join(thread, NULL);
    }
}

/** The window's number, which `screencapture -l` takes (the evidence screenshots, over SSH). */
JNIEXPORT jlong JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testWindowNumber(JNIEnv *env, jclass type, jlong window) {
    NSWindow *w = WVWindow(window);
    __block NSInteger number = 0;
    WVOnMain(YES, ^{
        number = w.windowNumber;
    });
    return (jlong)number;
}
