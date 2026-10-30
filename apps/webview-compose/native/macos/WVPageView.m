#import "WVGlue.h"

/** The cubic Bézier control-point factor for a quarter ellipse. */
static const CGFloat WVKappa = 0.5522847498;

/**
 * A rect with elliptical corners, each with its own radii, as one closed subpath (the layout test's). Coordinates are
 * y-up (this view, like AWT's content view, isn't flipped), so "top" is maxY.
 */
static void WVAddRoundRect(CGMutablePathRef path, CGRect r, CGSize tl, CGSize tr, CGSize br, CGSize bl) {
    CGFloat minX = CGRectGetMinX(r), maxX = CGRectGetMaxX(r), minY = CGRectGetMinY(r), maxY = CGRectGetMaxY(r);
    CGPathMoveToPoint(path, NULL, minX + tl.width, maxY);
    CGPathAddLineToPoint(path, NULL, maxX - tr.width, maxY);
    if (tr.width > 0 && tr.height > 0) {
        CGPathAddCurveToPoint(path, NULL, maxX - tr.width + WVKappa * tr.width, maxY, maxX,
                              maxY - tr.height + WVKappa * tr.height, maxX, maxY - tr.height);
    }
    CGPathAddLineToPoint(path, NULL, maxX, minY + br.height);
    if (br.width > 0 && br.height > 0) {
        CGPathAddCurveToPoint(path, NULL, maxX, minY + br.height - WVKappa * br.height,
                              maxX - br.width + WVKappa * br.width, minY, maxX - br.width, minY);
    }
    CGPathAddLineToPoint(path, NULL, minX + bl.width, minY);
    if (bl.width > 0 && bl.height > 0) {
        CGPathAddCurveToPoint(path, NULL, minX + bl.width - WVKappa * bl.width, minY, minX,
                              minY + bl.height - WVKappa * bl.height, minX, minY + bl.height);
    }
    CGPathAddLineToPoint(path, NULL, minX, maxY - tl.height);
    if (tl.width > 0 && tl.height > 0) {
        CGPathAddCurveToPoint(path, NULL, minX, maxY - tl.height + WVKappa * tl.height,
                              minX + tl.width - WVKappa * tl.width, maxY, minX + tl.width, maxY);
    }
    CGPathCloseSubpath(path);
}

CGMutablePathRef WVRegionPath(NSData *geometry, int holeCount, CGFloat density, CGFloat viewHeight,
                              NSArray<NSValue *> **scrims) {
    *scrims = @[];
    if (!geometry || density <= 0 || holeCount < 0) return NULL;
    const float *g = geometry.bytes;
    NSUInteger floats = geometry.length / sizeof(float);
    if (floats < 4 + 12 * (NSUInteger)holeCount) return NULL;
    NSUInteger scrimCount = (floats - 4 - 12 * (NSUInteger)holeCount) / 4;
    CGFloat d = density;
    // Page-local window px, y down → this view's points, y up. Which corner is which doesn't change, only y.
    CGRect (^toView)(const float *) = ^CGRect(const float *v) {
        return CGRectMake(v[0] / d, viewHeight - v[3] / d, (v[2] - v[0]) / d, (v[3] - v[1]) / d);
    };
    CGRect visible = toView(g);
    if (CGRectIsEmpty(visible)) return NULL;
    CGMutablePathRef path = CGPathCreateMutable();
    CGPathAddRect(path, NULL, visible);
    for (int i = 0; i < holeCount; i++) {
        const float *h = g + 4 + 12 * i;
        WVAddRoundRect(path, toView(h), CGSizeMake(h[4] / d, h[5] / d), CGSizeMake(h[6] / d, h[7] / d),
                       CGSizeMake(h[8] / d, h[9] / d), CGSizeMake(h[10] / d, h[11] / d));
    }
    NSMutableArray<NSValue *> *rects = [NSMutableArray arrayWithCapacity:scrimCount];
    for (NSUInteger i = 0; i < scrimCount; i++) {
        [rects addObject:[NSValue valueWithRect:NSRectFromCGRect(toView(g + 4 + 12 * holeCount + 4 * i))]];
    }
    *scrims = rects;
    return path;
}

/** Inside the visible rect, outside every hole (even-odd), under no scrim. */
static BOOL WVPathTakes(CGPathRef path, NSArray<NSValue *> *scrims, NSPoint local) {
    if (!path || !CGPathContainsPoint(path, NULL, NSPointToCGPoint(local), true)) return NO;
    for (NSValue *scrim in scrims) {
        if (NSPointInRect(local, scrim.rectValue)) return NO; // a modal is open: the page takes nothing under it
    }
    return YES;
}

/** Wheel and gesture events, which an embed leaves to Compose (D7). */
static BOOL WVIsGesture(NSEventType type) {
    switch (type) {
        case NSEventTypeScrollWheel:
        case NSEventTypeMagnify:
        case NSEventTypeRotate:
        case NSEventTypeSwipe:
        case NSEventTypeSmartMagnify:
        case NSEventTypeBeginGesture:
        case NSEventTypeEndGesture:
        case NSEventTypeGesture:
            return YES;
        default:
            return NO;
    }
}

@implementation WVPageView {
    CGMutablePathRef _path;          // the input region in this view's points; NULL: the page takes nothing
    NSArray<NSValue *> *_scrims;     // this view's points
    NSData *_geometry;
    int _holeCount;
    CGFloat _density;
    CADisplayLink *_link;            // runs only while ticks wait
    NSMutableArray<NSNumber *> *_waiting;
}

- (instancetype)initWithWebView:(WKWebView *)webView {
    self = [super initWithFrame:webView.frame];
    if (!self) return nil;
    _webView = webView;
    _scrims = @[];
    _waiting = [NSMutableArray array];
    self.wantsLayer = YES;
    webView.frame = self.bounds;
    webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    [self addSubview:webView];
    return self;
}

- (void)dealloc {
    if (_path) CGPathRelease(_path);
}

- (void)attachTo:(NSWindow *)window {
    NSView *content = window.contentView;
    if (!content) return;
    if (self.superview != content) {
        [self removeFromSuperview];
        // A subview of AWT's content view: its layer joins skiko's AWTMetalLayer in one sublayer list (M1).
        [content addSubview:self];
    }
    [self applyOrder];
    WVInstallInputMonitors();
    WVInstallAccessibilityBridge(window);
}

- (void)detachFrom:(NSWindow *)window {
    if (self.window == window) [self removeFromSuperview];
}

/** Under skiko's AWTMetalLayer (zPosition 0). Re-applied at every placement, in case AppKit resets it. */
- (void)applyOrder {
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    self.layer.zPosition = -1;
    [CATransaction commit];
}

- (void)placeAtX:(CGFloat)x y:(CGFloat)y width:(CGFloat)width height:(CGFloat)height density:(CGFloat)density hidden:(BOOL)hidden {
    NSView *content = self.superview;
    if (!content || density <= 0) return;
    // AWT's content view isn't flipped: its origin is the bottom-left.
    NSRect frame = NSMakeRect(x / density, content.bounds.size.height - (y + height) / density, width / density, height / density);
    BOOL resized = !NSEqualSizes(frame.size, self.frame.size);
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (!NSEqualRects(frame, self.frame)) self.frame = frame;
    // Hidden only while the slot isn't placed (D1): a stale view never shows through another page's hole.
    if (self.hidden != hidden) self.hidden = hidden;
    self.layer.zPosition = -1;
    [CATransaction commit];
    // The region is kept in this view's y-up points: a new height moves it.
    if (resized) [self applyRegion];
}

- (void)setRegion:(NSData *)geometry holes:(int)holeCount density:(CGFloat)density {
    _geometry = geometry;
    _holeCount = holeCount;
    _density = density;
    [self applyRegion];
}

- (void)applyRegion {
    if (_path) CGPathRelease(_path);
    NSArray<NSValue *> *scrims = nil;
    _path = WVRegionPath(_geometry, _holeCount, _density, self.bounds.size.height, &scrims);
    _scrims = scrims ?: @[];
}

- (BOOL)takesEventOfType:(NSEventType)type at:(NSPoint)local {
    if (self.hidden || !self.interactive || !self.window || !NSPointInRect(local, self.bounds)) return NO;
    if (!WVPathTakes(_path, _scrims, local)) return NO;
    if (!self.wheelToPage && WVIsGesture(type)) return NO;
    return YES;
}

- (NSView *)hitTest:(NSPoint)point {
    // point is in the superview's coordinates. nil hands the event to AWT's content view, and so to Compose.
    if (![self takesEventOfType:NSApp.currentEvent.type at:[self convertPoint:point fromView:self.superview]]) return nil;
    return [super hitTest:point];
}

- (void)tickFor:(jlong)request {
    jlong token = self.owner.token;
    if (!self.window || self.hidden) {
        WVPost(token, WVEventTick, 0, request, 0, 0, 0, nil, nil, nil, nil, nil);
        return;
    }
    [_waiting addObject:@(request)];
    if (_link) return;
    _link = [self displayLinkWithTarget:self selector:@selector(onTick:)];
    [_link addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
    [_link addToRunLoop:NSRunLoop.mainRunLoop forMode:@"AWTRunLoopMode"];
}

- (void)onTick:(CADisplayLink *)link {
    NSArray<NSNumber *> *due = [_waiting copy];
    [_waiting removeAllObjects];
    int shown = self.window != nil && !self.hidden ? 1 : 0;
    jlong token = self.owner.token;
    for (NSNumber *request in due) WVPost(token, WVEventTick, 0, request.longLongValue, shown, 0, 0, nil, nil, nil, nil, nil);
    if (_waiting.count == 0) {
        [_link invalidate];
        _link = nil;
    }
}

- (void)stopTicks {
    jlong token = self.owner.token;
    NSArray<NSNumber *> *due = [_waiting copy];
    [_waiting removeAllObjects];
    for (NSNumber *request in due) WVPost(token, WVEventTick, 0, request.longLongValue, 0, 0, 0, nil, nil, nil, nil, nil);
    [_link invalidate];
    _link = nil;
}

- (void)viewDidMoveToWindow {
    [super viewDidMoveToWindow];
    // Out of any window the display link stops: what waits hears so now.
    if (!self.window) [self stopTicks];
}

@end

/** WVRegionPath and the hit test's rule, from the JVM (InputRegionMacTest). x, y: points from the view's top-left. */
JNIEXPORT jboolean JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testRegionTakes(
    JNIEnv *env, jclass type, jfloatArray geometry, jint holeCount, jfloat density, jfloat viewHeight, jfloat x, jfloat y) {
    NSArray<NSValue *> *scrims = nil;
    CGMutablePathRef path = WVRegionPath(WVFloats(env, geometry), holeCount, density, viewHeight, &scrims);
    BOOL takes = WVPathTakes(path, scrims, NSMakePoint(x, viewHeight - y));
    if (path) CGPathRelease(path);
    return takes ? JNI_TRUE : JNI_FALSE;
}
