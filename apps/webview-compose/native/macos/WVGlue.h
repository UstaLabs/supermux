// The macOS engine's glue (docs/superpowers/plans/2026-10-30-webview-macos-engine.md): one WKWebView per engine, in a
// view under skiko's AWTMetalLayer (layout A), driven from the JVM. Every JNI entry point posts its work to AppKit's
// main thread (WVOnMain); every WebKit callback goes back as the one upcall, MacGlue.onEvent (WVPost).
#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <WebKit/WebKit.h>
#include <jni.h>

/** MacGlue.ABI; Gradle's stageMacGlue reads it from here. Change both together. */
#define WV_ABI_VERSION 2

/**
 * The upcalls: MacGlue.onEvent's `type`, and what its fields hold (the plan's Task 3 table; GlueEvent.kt decodes it).
 * Unnamed fields are 0 or nil.
 */
typedef NS_ENUM(int, WVEvent) {
    WVEventCreated = 1,    // id: the new page's handle
    WVEventStarted = 2,    // epoch: the navigation's; a: URL
    WVEventCommitted = 3,  // epoch: the navigation's; flags: loading 1; x: progress; a URL, b back, c forward, d title
    WVEventFinished = 4,   // epoch: the navigation's; flags: loading 1, floor kept 2; a URL, b back, c forward, d title
    WVEventFailed = 5,     // epoch: the navigation's; x: code; a failing URL, b domain, c description
    WVEventPolicy = 6,     // id: question; flags: main frame 1, opens a window 2, download 4, sent by the network 8,
                           // (type + 1) << 8; a URL
    WVEventResponse = 7,   // id: question; flags: main frame 1, can show 2, HTTP 4, status << 8; x: length or -1;
                           // a URL, b MIME type, c suggested file name, d Content-Disposition
    WVEventState = 8,      // epoch: committed; flags: loading 1; x: progress; a URL, b back, c forward, d title
    WVEventRelay = 9,      // epoch: committed; flags: main frame 1; x: port; a body, b protocol, c host
    WVEventNewWindow = 10, // a: URL
    WVEventDialog = 11,    // id: question; flags: alert 0, confirm 1, prompt 2; a message, b default text, c frame URL
    WVEventMedia = 12,     // id: question; flags: camera 0, microphone 1, both 2, other 3; x: port; a protocol, b host
    WVEventOpenPanel = 13, // id: question; flags: multiple 1, directories 2
    WVEventTaskStart = 14, // id: task; a: URL
    WVEventTaskStop = 15,  // id: task
    WVEventResult = 16,    // id: request; flags: ok 1; a: JSON; data: bytes
    WVEventSnapshot = 17,  // id: request; x: width, y: height (px); data: RGBA, premultiplied, top row first
    WVEventTick = 18,      // id: request; flags: in a window and shown 1
    WVEventCrashed = 19,
    WVEventPointer = 20,   // x, y: points from the window content's top-left; -1, -1 for a key
    WVEventMemory = 21,
};

/** Runs block on AppKit's main thread in AWT's run loop modes (M10), at once if already there; waits for it if wait. */
void WVOnMain(BOOL wait, dispatch_block_t block);

/** One upcall to MacGlue.onEvent, from any thread. page is the engine's token (0 for the process). */
void WVPost(jlong page, WVEvent type, int epoch, jlong ident, int flags, double x, double y,
            NSString *a, NSString *b, NSString *c, NSString *d, NSData *data);

/** The Java string as an NSString (through UTF-16, so any text survives), or nil. */
NSString *WVString(JNIEnv *env, jstring string);

/** The NSString as a Java string, or NULL. */
jstring WVJavaString(JNIEnv *env, NSString *string);

/** The Java byte array's bytes, or nil. */
NSData *WVBytes(JNIEnv *env, jbyteArray array);

/** The Java float array's values (floats), or nil. */
NSData *WVFloats(JNIEnv *env, jfloatArray array);

/** The Java string array's strings, nulls skipped; nil for a null array. */
NSArray<NSString *> *WVStrings(JNIEnv *env, jobjectArray array);

/** A script's result as JSON (WVJson.m, D5): nil for what JSON can't hold. */
NSString *WVJsonOf(id value);

/** The image's pixels as RGBA, premultiplied, sRGB, top row first, or nil. */
NSData *WVPixelsOf(CGImageRef image, size_t *width, size_t *height);

/** The NSWindow a ComposeWindow.windowHandle names. */
static inline NSWindow *WVWindow(jlong handle) {
    return (__bridge NSWindow *)(void *)(intptr_t)handle;
}

/** What a page's view needs from its page (WVPage.m), without naming its class. */
@protocol WVPageOwner <NSObject>
/** The engine's token for the page (D5). */
@property(nonatomic, readonly) jlong token;
/** The page took a press at (x, y), points from its window content's top-left; -1, -1 for a key. */
- (void)pageTookInputAtX:(CGFloat)x y:(CGFloat)y;
@end

/** One page's view under skiko's layer (WVPageView.m): its place, its input region, display ticks. */
@interface WVPageView : NSView
@property(nonatomic, weak) id<WVPageOwner> owner;
@property(nonatomic, readonly) WKWebView *webView;
/** Takes pointers at all: the page shows and is still (D1, D2). */
@property(nonatomic) BOOL interactive;
/** The runtime's cover is lifted: VoiceOver lists the page. */
@property(nonatomic) BOOL shown;
/** Wheel and gesture events are the page's (a pane), or Compose's (an embed) (D7). */
@property(nonatomic) BOOL wheelToPage;
- (instancetype)initWithWebView:(WKWebView *)webView;
/** Into window's content view, under the canvas; out of window, if it is still there. */
- (void)attachTo:(NSWindow *)window;
- (void)detachFrom:(NSWindow *)window;
/**
 * The slot and the part of it that shows (I2), window px from the content's top-left, at the window's backing scale
 * (window px a point, I3); hidden while not placed. The view is the part that shows, clipped; the web view inside it is
 * the slot. Placed again whenever the content view's frame changes (C1).
 */
- (void)placeSlot:(NSRect)slot shown:(NSRect)shown scale:(CGFloat)scale hidden:(BOOL)hidden;
/** InputRegion's geometry: page-local window px. */
- (void)setRegion:(NSData *)geometry holes:(int)holeCount density:(CGFloat)density;
/** Whether an event of type at local (this view's points) is the page's. */
- (BOOL)takesEventOfType:(NSEventType)type at:(NSPoint)local;
/** A TICK for request at the next display tick, or at once with flags 0 if the view is in no window or hidden. */
- (void)tickFor:(jlong)request;
/** Answers every waiting tick with flags 0 and stops the display link. */
- (void)stopTicks;
@end

/**
 * InputRegion's geometry as a path (even-odd: the visible rect and the holes) in a page's view's points (y down), the
 * slot's top-left at offset, and its scrims' rects; NULL when nothing is visible. The caller releases the path.
 */
CGMutablePathRef WVRegionPath(NSData *geometry, int holeCount, CGFloat density, CGPoint offset, NSArray<NSValue *> **scrims);

/** The process-wide key and mouse-down monitors (WVInput.m), installed once. */
void WVInstallInputMonitors(void);

/** The accessibility bridge on window's content view (WVInput.m, M3), installed once per window. */
void WVInstallAccessibilityBridge(NSWindow *window);

/** The page view holding window's first responder, or nil. */
WVPageView *WVFocusedPageView(NSWindow *window);

/** The page view a hit test of window at locationInWindow ends in, or nil. */
WVPageView *WVPageViewAt(NSWindow *window, NSPoint locationInWindow);

/** Starts the memory-pressure source, once (WVMain.m). */
void WVStartMemoryPressure(void);
