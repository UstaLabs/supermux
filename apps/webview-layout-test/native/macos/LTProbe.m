#import "LayoutTest.h"
#import <Carbon/Carbon.h>
#import <objc/message.h>
#import <objc/runtime.h>

static jstring LTJavaString(JNIEnv *env, NSString *s) {
    NSUInteger length = s.length;
    unichar *chars = malloc(sizeof(unichar) * MAX(length, (NSUInteger)1));
    [s getCharacters:chars range:NSMakeRange(0, length)];
    jstring result = (*env)->NewString(env, chars, (jsize)length);
    free(chars);
    return result;
}

static NSString *LTInputSource(void) {
    TISInputSourceRef source = TISCopyCurrentKeyboardInputSource();
    if (!source) return @"";
    NSString *sourceId = [(__bridge NSString *)TISGetInputSourceProperty(source, kTISPropertyInputSourceID) copy];
    CFRelease(source);
    return sourceId ?: @"";
}

JNIEXPORT jstring JNICALL Java_dev_supermux_webview_layouttest_Native_windowInfo(JNIEnv *env, jclass type, jlong window) {
    NSWindow *w = LTWindow(window);
    __block NSString *json = @"{}";
    LTOnMain(YES, ^{
        NSRect frame = w.frame;
        NSRect content = [w contentRectForFrameRect:frame];
        NSDictionary *info = @{
            @"windowNumber" : @(w.windowNumber),
            @"backingScale" : @(w.backingScaleFactor),
            @"frame" : @[ @(frame.origin.x), @(frame.origin.y), @(frame.size.width), @(frame.size.height) ],
            @"content" : @[ @(content.size.width), @(content.size.height) ],
            @"titlebar" : @(frame.size.height - content.size.height),
            @"maxFps" : @(w.screen.maximumFramesPerSecond),
            @"screen" : w.screen.localizedName ?: @"",
            @"isKey" : @((BOOL)w.isKeyWindow),
            @"isActive" : @((BOOL)NSApp.isActive),
            @"visible" : @((BOOL)((w.occlusionState & NSWindowOcclusionStateVisible) != 0)),
            @"inputSource" : LTInputSource(),
            @"canPostEvents" : @((BOOL)CGPreflightPostEventAccess()),
            @"macos" : NSProcessInfo.processInfo.operatingSystemVersionString,
        };
        NSData *data = [NSJSONSerialization dataWithJSONObject:info options:0 error:NULL];
        if (data) json = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    });
    return LTJavaString(env, json);
}

static void LTDescribe(NSMutableString *out, CALayer *layer, int depth) {
    [out appendFormat:@"%*s%@ frame=%@ z=%g opaque=%d hidden=%d mask=%d\n", depth * 2, "", NSStringFromClass(layer.class),
                      NSStringFromRect(NSRectFromCGRect(layer.frame)), layer.zPosition, layer.opaque, layer.hidden,
                      layer.mask != nil];
    if (depth < 2) {
        for (CALayer *sublayer in layer.sublayers) LTDescribe(out, sublayer, depth + 1);
    }
}

JNIEXPORT jstring JNICALL Java_dev_supermux_webview_layouttest_Native_layerTree(JNIEnv *env, jclass type, jlong window) {
    NSWindow *w = LTWindow(window);
    NSMutableString *out = [NSMutableString string];
    LTOnMain(YES, ^{
        NSView *content = w.contentView;
        [out appendFormat:@"content view %@ flipped=%d subviews=%lu\n", NSStringFromClass(content.class), content.isFlipped,
                          (unsigned long)content.subviews.count];
        LTDescribe(out, content.layer, 0);
    });
    return LTJavaString(env, out);
}

JNIEXPORT jstring JNICALL Java_dev_supermux_webview_layouttest_Native_firstResponder(JNIEnv *env, jclass type,
                                                                                    jlong window) {
    NSWindow *w = LTWindow(window);
    __block NSString *answer = @"none";
    LTOnMain(YES, ^{
        NSResponder *responder = w.firstResponder;
        LTPageView *page = LTFindPage(w);
        NSString *side = @"other";
        if (responder == w.contentView) {
            side = @"compose";
        } else if (page && [responder isKindOfClass:[NSView class]] && [(NSView *)responder isDescendantOf:page]) {
            side = @"page";
        }
        answer = [NSString stringWithFormat:@"%@ %@", side, NSStringFromClass(responder.class)];
    });
    return LTJavaString(env, answer);
}

/** Whether target is reachable from root through accessibilityChildren, counting the nodes visited. */
static BOOL LTReaches(id root, id target, NSUInteger *nodes) {
    NSMutableArray *stack = [NSMutableArray arrayWithObject:root];
    NSHashTable *seen = [NSHashTable hashTableWithOptions:NSPointerFunctionsObjectPointerPersonality | NSPointerFunctionsStrongMemory];
    while (stack.count > 0 && *nodes < 5000) {
        id node = stack.lastObject;
        [stack removeLastObject];
        if ([seen containsObject:node]) continue;
        [seen addObject:node];
        (*nodes)++;
        if (node == target) return YES;
        if (![node respondsToSelector:@selector(accessibilityChildren)]) continue;
        NSArray *children = [node accessibilityChildren];
        if (![children isKindOfClass:[NSArray class]]) continue;
        for (id child in children) [stack addObject:child];
    }
    return NO;
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_probeAccessibility(JNIEnv *env, jclass type,
                                                                                     jlong window) {
    NSWindow *w = LTWindow(window);
    // Not waited for: AWT answers accessibility from Java on the EDT, which is where this was called from.
    LTOnMain(NO, ^{
        LTPageView *page = LTFindPage(w);
        if (!page) return;
        NSUInteger nodes = 0;
        BOOL reaches = LTReaches(w, page.webView, &nodes);
        BOOL bridged = [NSStringFromClass(object_getClass(w.contentView)) hasPrefix:@"LTAx_"];
        [page send:[NSString stringWithFormat:@"{\"t\":\"ax\",\"reaches\":%@,\"nodes\":%lu,\"bridge\":%@}",
                                              reaches ? @"true" : @"false", (unsigned long)nodes, bridged ? @"true" : @"false"]];
    });
}

/**
 * A subclass of AWT's content view class that also lists the page's WKWebView as an accessibility child, and answers
 * hit tests over the page with the page. AWTView returns only the Java accessibility tree, so without it VoiceOver
 * never reaches the page.
 */
static Class LTAccessibleSubclass(Class base) {
    NSString *name = [@"LTAx_" stringByAppendingString:NSStringFromClass(base)];
    Class existing = NSClassFromString(name);
    if (existing) return existing;
    Class sub = objc_allocateClassPair(base, name.UTF8String, 0);
    if (!sub) return Nil;
    id (^children)(id) = ^id(id me) {
        struct objc_super parent = {me, base};
        NSArray *java = ((id (*)(struct objc_super *, SEL))objc_msgSendSuper)(&parent, @selector(accessibilityChildren));
        NSMutableArray *all = [NSMutableArray arrayWithArray:[java isKindOfClass:[NSArray class]] ? java : @[]];
        for (NSView *view in [(NSView *)me subviews]) {
            if ([view isKindOfClass:[LTPageView class]] && !view.hidden) [all addObject:((LTPageView *)view).webView];
        }
        return all;
    };
    class_addMethod(sub, @selector(accessibilityChildren), imp_implementationWithBlock(children), "@@:");
    id (^hit)(id, NSPoint) = ^id(id me, NSPoint screenPoint) {
        for (NSView *view in [(NSView *)me subviews]) {
            if (![view isKindOfClass:[LTPageView class]] || view.hidden) continue;
            LTPageView *page = (LTPageView *)view;
            NSPoint inWindow = [page.window convertPointFromScreen:screenPoint];
            if ([page takesInputAt:[page convertPoint:inWindow fromView:nil]]) {
                return [page.webView accessibilityHitTest:screenPoint];
            }
        }
        struct objc_super parent = {me, base};
        return ((id (*)(struct objc_super *, SEL, NSPoint))objc_msgSendSuper)(&parent, @selector(accessibilityHitTest:), screenPoint);
    };
    class_addMethod(sub, @selector(accessibilityHitTest:), imp_implementationWithBlock(hit), "@@:{CGPoint=dd}");
    objc_registerClassPair(sub);
    return sub;
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_setAccessibilityBridge(JNIEnv *env, jclass type,
                                                                                         jlong window, jboolean enabled) {
    NSWindow *w = LTWindow(window);
    LTOnMain(NO, ^{
        NSView *content = w.contentView;
        Class current = object_getClass(content);
        BOOL bridged = [NSStringFromClass(current) hasPrefix:@"LTAx_"];
        if (enabled == JNI_TRUE && !bridged) {
            Class sub = LTAccessibleSubclass(current);
            if (sub) object_setClass(content, sub); // this one instance, as KVO does
        } else if (enabled != JNI_TRUE && bridged) {
            object_setClass(content, class_getSuperclass(current));
        }
        NSAccessibilityPostNotification(content, NSAccessibilityLayoutChangedNotification);
    });
}
