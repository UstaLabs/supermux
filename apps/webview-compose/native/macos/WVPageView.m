#import "WVGlue.h"

/** The cubic Bézier control-point factor for a quarter ellipse. */
static const CGFloat WVKappa = 0.5522847498;

/**
 * A rect with elliptical corners, each with its own radii, as one closed subpath (the layout test's). Coordinates are
 * y-down (a page's view is flipped), so "top" is minY.
 */
static void WVAddRoundRect(CGMutablePathRef path, CGRect r, CGSize tl, CGSize tr, CGSize br, CGSize bl) {
    CGFloat minX = CGRectGetMinX(r), maxX = CGRectGetMaxX(r), top = CGRectGetMinY(r), bottom = CGRectGetMaxY(r);
    CGPathMoveToPoint(path, NULL, minX + tl.width, top);
    CGPathAddLineToPoint(path, NULL, maxX - tr.width, top);
    if (tr.width > 0 && tr.height > 0) {
        CGPathAddCurveToPoint(path, NULL, maxX - tr.width + WVKappa * tr.width, top, maxX,
                              top + tr.height - WVKappa * tr.height, maxX, top + tr.height);
    }
    CGPathAddLineToPoint(path, NULL, maxX, bottom - br.height);
    if (br.width > 0 && br.height > 0) {
        CGPathAddCurveToPoint(path, NULL, maxX, bottom - br.height + WVKappa * br.height,
                              maxX - br.width + WVKappa * br.width, bottom, maxX - br.width, bottom);
    }
    CGPathAddLineToPoint(path, NULL, minX + bl.width, bottom);
    if (bl.width > 0 && bl.height > 0) {
        CGPathAddCurveToPoint(path, NULL, minX + bl.width - WVKappa * bl.width, bottom, minX,
                              bottom - bl.height + WVKappa * bl.height, minX, bottom - bl.height);
    }
    CGPathAddLineToPoint(path, NULL, minX, top + tl.height);
    if (tl.width > 0 && tl.height > 0) {
        CGPathAddCurveToPoint(path, NULL, minX, top + tl.height - WVKappa * tl.height,
                              minX + tl.width - WVKappa * tl.width, top, minX + tl.width, top);
    }
    CGPathCloseSubpath(path);
}

CGMutablePathRef WVRegionPath(NSData *geometry, int holeCount, CGFloat density, CGPoint offset, NSArray<NSValue *> **scrims) {
    *scrims = @[];
    if (!geometry || density <= 0 || holeCount < 0) return NULL;
    const float *g = geometry.bytes;
    NSUInteger floats = geometry.length / sizeof(float);
    if (floats < 4 + 12 * (NSUInteger)holeCount) return NULL;
    NSUInteger scrimCount = (floats - 4 - 12 * (NSUInteger)holeCount) / 4;
    CGFloat d = density;
    // Page-local window px, y down → this view's points, y down, from the visible part's top-left.
    CGRect (^toView)(const float *) = ^CGRect(const float *v) {
        return CGRectMake(v[0] / d + offset.x, v[1] / d + offset.y, (v[2] - v[0]) / d, (v[3] - v[1]) / d);
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
    NSRect _slot;                    // the page's slot, window px from the content's top-left
    NSRect _shownPart;                   // the part of it that shows, the same
    CGFloat _scale;                  // window px a point; 0 until placed
    BOOL _hiddenWanted;
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
    // The page never draws outside the part of its slot that shows, over another page or anything else (I2).
    self.clipsToBounds = YES;
    // AWT's content view isn't flipped: kept at its distance from the top while the window's height changes (C1), even
    // before the content view's frame change re-places it exactly.
    self.autoresizingMask = NSViewMinYMargin;
    webView.frame = self.bounds;
    webView.autoresizingMask = NSViewNotSizable; // its frame is the slot's, set at each placement
    [self addSubview:webView];
    return self;
}

- (void)dealloc {
    if (_path) CGPathRelease(_path);
    [NSNotificationCenter.defaultCenter removeObserver:self];
}

/** Top-left based: the slot, the visible part and the input region are all measured from the top-left. */
- (BOOL)isFlipped {
    return YES;
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

- (void)viewDidMoveToSuperview {
    [super viewDidMoveToSuperview];
    [NSNotificationCenter.defaultCenter removeObserver:self name:NSViewFrameDidChangeNotification object:nil];
    NSView *content = self.superview;
    if (!content) return;
    // The window's height changed (a resize, the zoom button, full screen, a smaller screen): AWT's content view is
    // resized, and this view, placed from its bottom, is placed again from the slot it keeps (C1).
    [NSNotificationCenter.defaultCenter addObserver:self selector:@selector(contentFrameChanged:)
                                               name:NSViewFrameDidChangeNotification object:content];
    [self applyPlacement];
}

- (void)contentFrameChanged:(NSNotification *)notification {
    [self applyPlacement];
}

/** Under skiko's AWTMetalLayer (zPosition 0). Re-applied at every placement, in case AppKit resets it. */
- (void)applyOrder {
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    self.layer.zPosition = -1;
    [CATransaction commit];
}

- (void)placeSlot:(NSRect)slot shown:(NSRect)shown scale:(CGFloat)scale hidden:(BOOL)hidden {
    if (scale <= 0) return;
    _slot = slot;
    _shownPart = NSIsEmptyRect(shown) ? NSMakeRect(slot.origin.x, slot.origin.y, 0, 0) : shown;
    _scale = scale;
    _hiddenWanted = hidden;
    [self applyPlacement];
}

/**
 * This view at the part of the slot that shows, the web view at the whole slot inside it (so the page is laid out at
 * its slot's size, and clipped to what shows), from the content view's top-left: AWT's content view isn't flipped, so
 * its bottom-left origin moves whenever its height changes (C1).
 */
- (void)applyPlacement {
    NSView *content = self.superview;
    if (!content || _scale <= 0) return;
    CGFloat s = _scale;
    NSRect shown = NSMakeRect(_shownPart.origin.x / s, _shownPart.origin.y / s, _shownPart.size.width / s, _shownPart.size.height / s);
    NSRect frame = NSMakeRect(shown.origin.x, content.bounds.size.height - NSMaxY(shown), shown.size.width, shown.size.height);
    NSRect inner = NSMakeRect((_slot.origin.x - _shownPart.origin.x) / s, (_slot.origin.y - _shownPart.origin.y) / s,
                              _slot.size.width / s, _slot.size.height / s);
    BOOL moved = !NSEqualPoints(inner.origin, _webView.frame.origin);
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (!NSEqualRects(frame, self.frame)) self.frame = frame;
    if (!NSEqualRects(inner, _webView.frame)) _webView.frame = inner;
    // Hidden only while the slot isn't placed (D1): a stale view never shows through another page's hole.
    if (self.hidden != _hiddenWanted) self.hidden = _hiddenWanted;
    self.layer.zPosition = -1;
    [CATransaction commit];
    // The region is kept from the visible part's top-left: a new clip moves it.
    if (moved) [self applyRegion];
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
    _path = WVRegionPath(_geometry, _holeCount, _density, NSPointToCGPoint(_webView.frame.origin), &scrims);
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

/**
 * WVRegionPath and the hit test's rule, from the JVM (InputRegionMacTest), for a page wholly shown: x, y are points
 * from its view's top-left, which is flipped. viewHeight is the view's height, which a flipped view doesn't need.
 */
JNIEXPORT jboolean JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testRegionTakes(
    JNIEnv *env, jclass type, jfloatArray geometry, jint holeCount, jfloat density, jfloat viewHeight, jfloat x, jfloat y) {
    (void)viewHeight;
    NSArray<NSValue *> *scrims = nil;
    CGMutablePathRef path = WVRegionPath(WVFloats(env, geometry), holeCount, density, CGPointZero, &scrims);
    BOOL takes = WVPathTakes(path, scrims, NSMakePoint(x, y));
    if (path) CGPathRelease(path);
    return takes ? JNI_TRUE : JNI_FALSE;
}
