#import "LayoutTest.h"
#import <Carbon/Carbon.h>
#include <math.h>

/** Compose window px (the content's top-left origin) → the window's base coordinates (the frame's bottom-left origin). */
static NSPoint LTWindowPoint(NSWindow *window, CGFloat density, CGFloat x, CGFloat y) {
    NSView *content = window.contentView;
    return [content convertPoint:NSMakePoint(x / density, content.bounds.size.height - y / density) toView:nil];
}

/**
 * Into this app's own queue, never CGEventPost: no Accessibility permission, the cursor doesn't move, and nothing can
 * land in another app. From nextEventMatchingMask: on, the event takes real input's path: sendEvent:, the local
 * monitors, the window's hit test.
 */
static void LTPostMouse(NSWindow *window, NSEventType type, NSPoint at) {
    NSEvent *event = [NSEvent mouseEventWithType:type
                                        location:at
                                   modifierFlags:0
                                       timestamp:NSProcessInfo.processInfo.systemUptime
                                    windowNumber:window.windowNumber
                                         context:nil
                                     eventNumber:0
                                      clickCount:1
                                        pressure:type == NSEventTypeLeftMouseDown ? 1.0f : 0.0f];
    [NSApp postEvent:event atStart:NO];
}

static void LTPostKey(NSWindow *window, NSString *characters, unsigned short keyCode, NSEventModifierFlags flags) {
    for (int up = 0; up < 2; up++) {
        NSEvent *event = [NSEvent keyEventWithType:up ? NSEventTypeKeyUp : NSEventTypeKeyDown
                                          location:NSZeroPoint
                                     modifierFlags:flags
                                         timestamp:NSProcessInfo.processInfo.systemUptime
                                      windowNumber:window.windowNumber
                                           context:nil
                                        characters:characters
                       charactersIgnoringModifiers:characters
                                         isARepeat:NO
                                           keyCode:keyCode];
        [NSApp postEvent:event atStart:NO];
    }
}

/** The ANSI key codes of a…z. */
static const unsigned short kLetterKeys[26] = {
    kVK_ANSI_A, kVK_ANSI_B, kVK_ANSI_C, kVK_ANSI_D, kVK_ANSI_E, kVK_ANSI_F, kVK_ANSI_G, kVK_ANSI_H, kVK_ANSI_I,
    kVK_ANSI_J, kVK_ANSI_K, kVK_ANSI_L, kVK_ANSI_M, kVK_ANSI_N, kVK_ANSI_O, kVK_ANSI_P, kVK_ANSI_Q, kVK_ANSI_R,
    kVK_ANSI_S, kVK_ANSI_T, kVK_ANSI_U, kVK_ANSI_V, kVK_ANSI_W, kVK_ANSI_X, kVK_ANSI_Y, kVK_ANSI_Z,
};

JNIEXPORT jboolean JNICALL Java_dev_supermux_webview_layouttest_Native_activate(JNIEnv *env, jclass type, jlong window) {
    NSWindow *w = LTWindow(window);
    __block BOOL key = NO;
    LTOnMain(YES, ^{
        [NSApp activateIgnoringOtherApps:YES];
        [w makeKeyAndOrderFront:nil];
        key = NSApp.isActive && w.isKeyWindow;
    });
    return key ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_click(JNIEnv *env, jclass type, jlong window,
                                                                        jfloat density, jfloat x, jfloat y) {
    NSWindow *w = LTWindow(window);
    LTOnMain(NO, ^{
        NSPoint at = LTWindowPoint(w, density, x, y);
        LTPostMouse(w, NSEventTypeLeftMouseDown, at);
        LTPostMouse(w, NSEventTypeLeftMouseUp, at);
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_scroll(JNIEnv *env, jclass type, jlong window,
                                                                         jfloat density, jfloat x, jfloat y,
                                                                         jfloat deltaY) {
    NSWindow *w = LTWindow(window);
    LTOnMain(NO, ^{
        NSPoint screen = [w convertPointToScreen:LTWindowPoint(w, density, x, y)];
        // Quartz's global coordinates start at the primary display's top-left.
        CGFloat primaryTop = NSMaxY(NSScreen.screens.firstObject.frame);
        CGEventRef cg = CGEventCreateScrollWheelEvent2(NULL, kCGScrollEventUnitPixel, 1, (int32_t)lroundf(deltaY), 0, 0);
        if (!cg) return;
        CGEventSetLocation(cg, CGPointMake(screen.x, primaryTop - screen.y));
        CGEventSetFlags(cg, 0);
        CGEventSetIntegerValueField(cg, kCGMouseEventWindowUnderMousePointer, w.windowNumber);
        CGEventSetIntegerValueField(cg, kCGMouseEventWindowUnderMousePointerThatCanHandleThisEvent, w.windowNumber);
        NSEvent *event = [NSEvent eventWithCGEvent:cg];
        CFRelease(cg);
        // Diagnostic only (run 1's wheel reached neither side): the window and point AppKit gives this event.
        NSLog(@"LT scroll: event window %ld (%@ ours, %ld), locationInWindow %@, wanted %@", (long)event.windowNumber,
              event.window == w ? @"is" : @"is not", (long)w.windowNumber, NSStringFromPoint(event.locationInWindow),
              NSStringFromPoint(LTWindowPoint(w, density, x, y)));
        if (event) [NSApp postEvent:event atStart:NO];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_typeText(JNIEnv *env, jclass type, jlong window,
                                                                           jstring text) {
    NSWindow *w = LTWindow(window);
    NSString *s = LTString(env, text);
    LTOnMain(NO, ^{
        for (NSUInteger i = 0; i < s.length; i++) {
            unichar c = [s characterAtIndex:i];
            if (c < 'a' || c > 'z') continue; // lowercase ASCII letters only: their key codes need no layout table
            LTPostKey(w, [NSString stringWithCharacters:&c length:1], kLetterKeys[c - 'a'], 0);
        }
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_layouttest_Native_commandKey(JNIEnv *env, jclass type, jlong window,
                                                                             jstring key) {
    NSWindow *w = LTWindow(window);
    NSString *k = LTString(env, key);
    LTOnMain(NO, ^{
        if (k.length != 1) return;
        unichar c = [k characterAtIndex:0];
        if (c < 'a' || c > 'z') return;
        LTPostKey(w, k, kLetterKeys[c - 'a'], NSEventModifierFlagCommand);
    });
}
