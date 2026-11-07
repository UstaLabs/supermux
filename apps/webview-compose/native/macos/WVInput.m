#import "WVGlue.h"
#import <objc/message.h>
#import <objc/runtime.h>

WVPageView *WVFocusedPageView(NSWindow *window) {
    NSResponder *responder = window.firstResponder;
    if (![responder isKindOfClass:[NSView class]]) return nil;
    for (NSView *view = (NSView *)responder; view; view = view.superview) {
        if ([view isKindOfClass:[WVPageView class]]) return (WVPageView *)view;
    }
    return nil;
}

WVPageView *WVPageViewAt(NSWindow *window, NSPoint locationInWindow) {
    NSView *content = window.contentView;
    if (!content) return nil;
    // hitTest: takes its point in the receiver's superview's coordinates.
    NSView *frame = content.superview ?: content;
    NSView *hit = [content hitTest:[frame convertPoint:locationInWindow fromView:nil]];
    for (NSView *view = hit; view; view = view.superview) {
        if ([view isKindOfClass:[WVPageView class]]) return (WVPageView *)view;
    }
    return nil;
}

/** The edit action a ⌘ key stands for, or NULL (M2). */
static SEL WVEditAction(NSEvent *event) {
    NSEventModifierFlags mods = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
    if (!(mods & NSEventModifierFlagCommand) || (mods & (NSEventModifierFlagControl | NSEventModifierFlagOption))) return NULL;
    NSString *key = event.charactersIgnoringModifiers.lowercaseString;
    BOOL shift = (mods & NSEventModifierFlagShift) != 0;
    if ([key isEqualToString:@"z"]) return shift ? @selector(redo:) : @selector(undo:);
    if (shift) return NULL;
    if ([key isEqualToString:@"c"]) return @selector(copy:);
    if ([key isEqualToString:@"x"]) return @selector(cut:);
    if ([key isEqualToString:@"v"]) return @selector(paste:);
    if ([key isEqualToString:@"a"]) return @selector(selectAll:);
    return NULL;
}

/** The page view whose frame holds locationInWindow in window, topmost first, or nil: what is under the pointer natively. */
static WVPageView *WVPageViewUnder(NSWindow *window, NSPoint locationInWindow) {
    NSView *content = window.contentView;
    if (!content) return nil;
    NSPoint inContent = [content convertPoint:locationInWindow fromView:nil];
    for (NSView *view in content.subviews.reverseObjectEnumerator) {
        if (![view isKindOfClass:[WVPageView class]] || view.hidden || !NSPointInRect(inContent, view.frame)) continue;
        return (WVPageView *)view;
    }
    return nil;
}

/** The page that saw the pointer last, while it was over the page's own part. */
static __weak WVPageView *gHovered;

/**
 * The pointer left a page's own part for a part Compose covers (an overlay, a scrim, a cover): the page sees it no
 * more, so the cursor it set (an I-beam, a hand) goes back to the arrow, which AWT believes it shows, until Compose sets
 * its own (I4).
 */
static void WVLeaveHovered(void) {
    if (!gHovered) return;
    gHovered = nil;
    [[NSCursor arrowCursor] set];
}

void WVInstallInputMonitors(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        // I4: WKWebView's own tracking area, and its being first responder, hand it every move over its frame, even
        // where Compose draws over it: its hover states and cursors showed under menus, dialogs and covers. A move
        // over a part the page doesn't take goes to AWT's view alone (so Compose's hover works), and never on to it.
        NSEventMask moves = NSEventMaskMouseMoved | NSEventMaskMouseEntered | NSEventMaskCursorUpdate;
        [NSEvent addLocalMonitorForEventsMatchingMask:moves handler:^NSEvent *(NSEvent *event) {
            NSWindow *window = event.window;
            if (!window) return event;
            WVPageView *page = WVPageViewUnder(window, event.locationInWindow);
            if (!page) {
                if (event.type == NSEventTypeMouseMoved) gHovered = nil; // WebKit's own tracking area saw it leave
                return event;
            }
            if ([page takesEventOfType:NSEventTypeMouseMoved at:[page convertPoint:event.locationInWindow fromView:nil]]) {
                if (event.type == NSEventTypeMouseMoved) gHovered = page;
                return event;
            }
            // Under Compose's overlay: the web view's own tracking areas' entries and cursor updates go nowhere.
            if (event.type != NSEventTypeMouseMoved) {
                NSTrackingArea *area = event.trackingArea;
                return area && [page.webView.trackingAreas containsObject:area] ? nil : event;
            }
            WVLeaveHovered();
            [window.contentView mouseMoved:event];
            return nil;
        }];
        [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown handler:^NSEvent *(NSEvent *event) {
            WVPageView *page = WVFocusedPageView(event.window);
            if (!page) return event;
            [page.owner pageTookInputAtX:-1 y:-1];
            SEL action = WVEditAction(event);
            if (!action) return event;
            // AWT's performKeyEquivalent: hands every key equivalent to Java only, never to the page (M2): send the
            // action down the responder chain from the page's first responder instead, and let the key go no further.
            [NSApp sendAction:action to:nil from:page];
            return nil;
        }];
        NSEventMask presses = NSEventMaskLeftMouseDown | NSEventMaskRightMouseDown | NSEventMaskOtherMouseDown;
        [NSEvent addLocalMonitorForEventsMatchingMask:presses handler:^NSEvent *(NSEvent *event) {
            NSWindow *window = event.window;
            if (!window) return event;
            // The same hit test AppKit is about to make: it ends in a page only where the page takes the press.
            WVPageView *page = WVPageViewAt(window, event.locationInWindow);
            if (!page) return event;
            NSView *content = window.contentView;
            NSPoint inContent = [content convertPoint:event.locationInWindow fromView:nil];
            [page.owner pageTookInputAtX:inContent.x y:content.bounds.size.height - inContent.y];
            return event;
        }];
    });
}

/**
 * A subclass of AWT's content view class that also lists every shown page's WKWebView as an accessibility child, and
 * answers hit tests over a page with the page (M3): AWTView exposes only the Java accessibility tree. One instance at
 * a time is moved to it (object_setClass), as KVO does.
 */
static Class WVAccessibleSubclass(Class base) {
    NSString *name = [@"WVAx_" stringByAppendingString:NSStringFromClass(base)];
    Class existing = NSClassFromString(name);
    if (existing) return existing;
    Class sub = objc_allocateClassPair(base, name.UTF8String, 0);
    if (!sub) return Nil;
    id (^children)(id) = ^id(id me) {
        struct objc_super parent = {me, base};
        NSArray *java = ((id (*)(struct objc_super *, SEL))objc_msgSendSuper)(&parent, @selector(accessibilityChildren));
        NSMutableArray *all = [NSMutableArray arrayWithArray:[java isKindOfClass:[NSArray class]] ? java : @[]];
        for (NSView *view in [(NSView *)me subviews]) {
            if (![view isKindOfClass:[WVPageView class]]) continue;
            WVPageView *page = (WVPageView *)view;
            if (page.shown && !page.hidden) [all addObject:page.webView];
        }
        return all;
    };
    class_addMethod(sub, @selector(accessibilityChildren), imp_implementationWithBlock(children), "@@:");
    id (^hit)(id, NSPoint) = ^id(id me, NSPoint screenPoint) {
        for (NSView *view in [(NSView *)me subviews]) {
            if (![view isKindOfClass:[WVPageView class]]) continue;
            WVPageView *page = (WVPageView *)view;
            if (!page.shown || page.hidden || !page.window) continue;
            NSPoint inWindow = [page.window convertPointFromScreen:screenPoint];
            if ([page takesEventOfType:NSEventTypeLeftMouseDown at:[page convertPoint:inWindow fromView:nil]]) {
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

void WVInstallAccessibilityBridge(NSWindow *window) {
    NSView *content = window.contentView;
    if (!content) return;
    Class current = object_getClass(content);
    // Installed already, perhaps under a key-value observing subclass made since: never twice (each page listed twice).
    for (Class c = current; c; c = class_getSuperclass(c)) {
        if ([NSStringFromClass(c) hasPrefix:@"WVAx_"]) return;
    }
    Class sub = WVAccessibleSubclass(current);
    if (!sub) return;
    object_setClass(content, sub);
    NSAccessibilityPostNotification(content, NSAccessibilityLayoutChangedNotification);
}
