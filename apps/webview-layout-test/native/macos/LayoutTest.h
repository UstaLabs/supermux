// The macOS layout test's Objective-C glue (docs/superpowers/plans/2026-10-29-webview-macos-layout-test.md).
// One WKWebView in a container view added to AWT's content view: under skiko's AWTMetalLayer (layout A) or over it
// with a mask (layout B). Throwaway: the macOS engine plan absorbs what wins.
#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <WebKit/WebKit.h>
#include <jni.h>

typedef NS_ENUM(NSInteger, LTLayout) {
    /** A: the page's layer under skiko's AWTMetalLayer; Compose clears a hole over it. */
    LTLayoutUnder = 0,
    /** B: over it; a CAShapeLayer mask cuts the overlays out. */
    LTLayoutOver = 1,
};

/** Runs block on the AppKit thread, in the run loop modes AWT's ThreadUtilities uses, waiting or not. */
void LTOnMain(BOOL wait, dispatch_block_t block);

/** Looks up PageListener.onMessage(String). Call from a JNI entry point, on a Java thread. */
void LTResolveListener(JNIEnv *env);

/** listener.onMessage(json), from any thread. */
void LTSendToJava(jobject listener, NSString *json);

/** Deletes a global reference, from any thread. */
void LTDeleteGlobalRef(jobject ref);

/** The Java string as an NSString (through UTF-16, so any text survives), or nil. */
NSString *LTString(JNIEnv *env, jstring string);

/** The NSWindow a ComposeWindow.windowHandle points to. */
static inline NSWindow *LTWindow(jlong handle) {
    return (__bridge NSWindow *)(void *)(intptr_t)handle;
}

@interface LTPageView : NSView <WKScriptMessageHandler, WKNavigationDelegate>
@property(nonatomic, readonly) WKWebView *webView;
@property(nonatomic) LTLayout layout;
/** The page said it painted its first frame: until then the view is transparent and takes no input. */
@property(nonatomic, readonly) BOOL painted;

- (instancetype)initWithHTML:(NSString *)html listener:(jobject)listener;
/** The page's bounds in window px (Compose's, top-left origin) and Compose's density. */
- (void)placeAtLeft:(CGFloat)left top:(CGFloat)top width:(CGFloat)width height:(CGFloat)height density:(CGFloat)density;
/** Cut-outs in page-local window px, as packCutouts writes them (CutoutPacking.kt). */
- (void)setCutouts:(NSData *)geometry holes:(int)holeCount scrimColors:(NSData *)colors density:(CGFloat)density;
/** Whether a pointer at point (this view's coordinates) is the page's: visible, in no hole, under no scrim. */
- (BOOL)takesInputAt:(NSPoint)point;
- (void)setKeyRouting:(BOOL)enabled;
- (void)send:(NSString *)json;
- (void)detach;
@end

/** The page view among this window's content view's subviews, or nil. */
LTPageView *LTFindPage(NSWindow *window);
