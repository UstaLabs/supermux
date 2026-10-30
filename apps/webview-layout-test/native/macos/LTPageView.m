#import "LayoutTest.h"

/** The cubic Bézier control-point factor for a quarter ellipse. */
static const CGFloat kKappa = 0.5522847498;

/**
 * A rect with elliptical corners, each with its own radii, as one closed subpath. Coordinates are y-up (this view, like
 * AWT's content view, isn't flipped), so "top" is maxY.
 */
static void LTAddRoundRect(CGMutablePathRef path, CGRect r, CGSize tl, CGSize tr, CGSize br, CGSize bl) {
    CGFloat minX = CGRectGetMinX(r), maxX = CGRectGetMaxX(r), minY = CGRectGetMinY(r), maxY = CGRectGetMaxY(r);
    CGPathMoveToPoint(path, NULL, minX + tl.width, maxY);
    CGPathAddLineToPoint(path, NULL, maxX - tr.width, maxY);
    if (tr.width > 0 && tr.height > 0) {
        CGPathAddCurveToPoint(path, NULL, maxX - tr.width + kKappa * tr.width, maxY, maxX,
                              maxY - tr.height + kKappa * tr.height, maxX, maxY - tr.height);
    }
    CGPathAddLineToPoint(path, NULL, maxX, minY + br.height);
    if (br.width > 0 && br.height > 0) {
        CGPathAddCurveToPoint(path, NULL, maxX, minY + br.height - kKappa * br.height,
                              maxX - br.width + kKappa * br.width, minY, maxX - br.width, minY);
    }
    CGPathAddLineToPoint(path, NULL, minX + bl.width, minY);
    if (bl.width > 0 && bl.height > 0) {
        CGPathAddCurveToPoint(path, NULL, minX + bl.width - kKappa * bl.width, minY, minX,
                              minY + bl.height - kKappa * bl.height, minX, minY + bl.height);
    }
    CGPathAddLineToPoint(path, NULL, minX, maxY - tl.height);
    if (tl.width > 0 && tl.height > 0) {
        CGPathAddCurveToPoint(path, NULL, minX, maxY - tl.height + kKappa * tl.height,
                              minX + tl.width - kKappa * tl.width, maxY, minX + tl.width, maxY);
    }
    CGPathCloseSubpath(path);
}

static CGColorRef LTCreateColor(int argb) {
    return CGColorCreateSRGB(((argb >> 16) & 0xFF) / 255.0, ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0,
                             ((argb >> 24) & 0xFF) / 255.0);
}

@implementation LTPageView {
    jobject _listener;               // a global reference, deleted in detach
    CGMutablePathRef _inputPath;     // the visible rect plus the holes, even-odd, y-up points; NULL until the first cut-outs
    NSArray<NSValue *> *_scrimRects; // y-up points
    NSArray<CALayer *> *_scrimLayers;
    CAShapeLayer *_mask;
    NSData *_geometry;
    NSData *_colors;
    int _holeCount;
    CGFloat _density;
    id _keyMonitor;
}

- (instancetype)initWithHTML:(NSString *)html listener:(jobject)listener {
    self = [super initWithFrame:NSMakeRect(0, 0, 16, 16)];
    if (!self) return nil;
    _listener = listener;
    _layout = LTLayoutUnder;
    self.wantsLayer = YES;
    // Never a white frame: shown once the page says it painted (see the message handler).
    self.hidden = YES;
    WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
    config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
    [config.userContentController addScriptMessageHandler:self name:@"probe"];
    _webView = [[WKWebView alloc] initWithFrame:self.bounds configuration:config];
    _webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    _webView.navigationDelegate = self;
    _webView.allowsMagnification = YES;
    _webView.allowsBackForwardNavigationGestures = YES;
    if (@available(macOS 13.3, *)) _webView.inspectable = YES;
    [self addSubview:_webView];
    [_webView loadHTMLString:html baseURL:[NSURL URLWithString:@"https://layout-test.invalid/"]];
    return self;
}

- (void)dealloc {
    if (_inputPath) CGPathRelease(_inputPath);
}

/** Under or over skiko's AWTMetalLayer (zPosition 0). Re-applied at every placement, in case AppKit resets it. */
- (void)applyOrder {
    self.layer.zPosition = self.layout == LTLayoutOver ? 1 : -1;
}

- (void)setLayout:(LTLayout)layout {
    _layout = layout;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    [self applyOrder];
    [CATransaction commit];
    [self applyCutouts];
}

- (void)placeAtLeft:(CGFloat)left top:(CGFloat)top width:(CGFloat)width height:(CGFloat)height density:(CGFloat)density {
    NSView *content = self.superview;
    if (!content || density <= 0) return;
    CGFloat h = height / density;
    // AWT's content view isn't flipped: its origin is the bottom-left.
    NSRect frame = NSMakeRect(left / density, content.bounds.size.height - top / density - h, width / density, h);
    BOOL resized = !NSEqualSizes(frame.size, self.frame.size);
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (!NSEqualRects(frame, self.frame)) self.frame = frame;
    [self applyOrder];
    [CATransaction commit];
    // The cut-outs are kept in this view's y-up points: a new height moves them.
    if (resized) [self applyCutouts];
}

- (void)setCutouts:(NSData *)geometry holes:(int)holeCount scrimColors:(NSData *)colors density:(CGFloat)density {
    _geometry = geometry;
    _holeCount = holeCount;
    _colors = colors;
    _density = density;
    [self applyCutouts];
}

- (void)applyCutouts {
    if (!_geometry || _density <= 0) return;
    const float *g = _geometry.bytes;
    NSUInteger floats = _geometry.length / sizeof(float);
    if (floats < 4 + 12 * (NSUInteger)_holeCount) return;
    NSUInteger scrimCount = (floats - 4 - 12 * (NSUInteger)_holeCount) / 4;
    CGFloat d = _density;
    CGFloat height = self.bounds.size.height;
    // Page-local window px, y down → this view's points, y up. Which corner is which doesn't change, only y.
    CGRect (^toView)(const float *) = ^CGRect(const float *v) {
        return CGRectMake(v[0] / d, height - v[3] / d, (v[2] - v[0]) / d, (v[3] - v[1]) / d);
    };
    CGMutablePathRef path = CGPathCreateMutable();
    CGRect visible = toView(g);
    if (!CGRectIsEmpty(visible)) CGPathAddRect(path, NULL, visible);
    for (int i = 0; i < _holeCount; i++) {
        const float *h = g + 4 + 12 * i;
        LTAddRoundRect(path, toView(h), CGSizeMake(h[4] / d, h[5] / d), CGSizeMake(h[6] / d, h[7] / d),
                       CGSizeMake(h[8] / d, h[9] / d), CGSizeMake(h[10] / d, h[11] / d));
    }
    NSMutableArray<NSValue *> *scrims = [NSMutableArray array];
    for (NSUInteger i = 0; i < scrimCount; i++) {
        [scrims addObject:[NSValue valueWithRect:NSRectFromCGRect(toView(g + 4 + 12 * _holeCount + 4 * i))]];
    }
    if (_inputPath) CGPathRelease(_inputPath);
    _inputPath = path;
    _scrimRects = scrims;

    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    for (CALayer *layer in _scrimLayers) [layer removeFromSuperlayer];
    _scrimLayers = @[];
    if (self.layout == LTLayoutOver) {
        if (!_mask) {
            _mask = [CAShapeLayer layer];
            _mask.fillRule = kCAFillRuleEvenOdd;
        }
        _mask.frame = self.layer.bounds;
        _mask.contentsScale = self.window.backingScaleFactor > 0 ? self.window.backingScaleFactor : 2;
        _mask.path = path;
        self.layer.mask = _mask;
        const int *argb = _colors.bytes;
        NSUInteger colorCount = _colors.length / sizeof(int);
        NSMutableArray<CALayer *> *layers = [NSMutableArray array];
        for (NSUInteger i = 0; i < scrims.count && i < colorCount; i++) {
            CALayer *dim = [CALayer layer];
            dim.frame = NSRectToCGRect(scrims[i].rectValue);
            CGColorRef color = LTCreateColor(argb[i]);
            dim.backgroundColor = color;
            CGColorRelease(color);
            dim.zPosition = 10; // over the WKWebView's layer
            [self.layer addSublayer:dim];
            [layers addObject:dim];
        }
        _scrimLayers = layers;
    } else {
        // A: Compose draws over the page; nothing to cut out, nothing to dim.
        self.layer.mask = nil;
    }
    [CATransaction commit];
}

- (BOOL)takesInputAt:(NSPoint)point {
    if (self.hidden || !_inputPath || !NSPointInRect(point, self.bounds)) return NO;
    if (!CGPathContainsPoint(_inputPath, NULL, NSPointToCGPoint(point), true)) return NO;
    for (NSValue *scrim in _scrimRects) {
        if (NSPointInRect(point, scrim.rectValue)) return NO; // a modal is open: the page takes nothing under it
    }
    return YES;
}

- (NSView *)hitTest:(NSPoint)point {
    // point is in the superview's coordinates. nil hands the event to AWT's content view, and so to Compose.
    if (![self takesInputAt:[self convertPoint:point fromView:self.superview]]) return nil;
    return [super hitTest:point];
}

- (void)userContentController:(WKUserContentController *)controller didReceiveScriptMessage:(WKScriptMessage *)message {
    if (![message.body isKindOfClass:[NSString class]]) return;
    NSString *json = message.body;
    if ([json containsString:@"\"t\":\"painted\""]) self.hidden = NO;
    [self send:json];
}

- (void)webViewWebContentProcessDidTerminate:(WKWebView *)webView {
    [self send:@"{\"t\":\"crashed\"}"];
}

- (void)send:(NSString *)json {
    LTSendToJava(_listener, json);
}

- (void)setKeyRouting:(BOOL)enabled {
    if (enabled == (_keyMonitor != nil)) return;
    if (!enabled) {
        [NSEvent removeMonitor:_keyMonitor];
        _keyMonitor = nil;
        return;
    }
    __weak LTPageView *weakSelf = self;
    _keyMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown handler:^NSEvent *(NSEvent *event) {
        LTPageView *page = weakSelf;
        NSEventModifierFlags mods = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
        if (!page || !(mods & NSEventModifierFlagCommand) || event.window != page.window) return event;
        NSResponder *responder = event.window.firstResponder;
        if (![responder isKindOfClass:[NSView class]] || ![(NSView *)responder isDescendantOf:page]) return event;
        NSString *key = event.charactersIgnoringModifiers.lowercaseString;
        BOOL shift = (mods & NSEventModifierFlagShift) != 0;
        SEL action = NULL;
        if ([key isEqualToString:@"c"]) action = @selector(copy:);
        else if ([key isEqualToString:@"x"]) action = @selector(cut:);
        else if ([key isEqualToString:@"v"]) action = @selector(paste:);
        else if ([key isEqualToString:@"a"]) action = @selector(selectAll:);
        else if ([key isEqualToString:@"z"]) action = shift ? @selector(redo:) : @selector(undo:);
        if (!action) return event;
        // AWT's performKeyEquivalent: would hand the key to Java only, never to the page: send the action down the
        // responder chain instead, and swallow the key so Compose doesn't act on it as well.
        [NSApp sendAction:action to:nil from:page];
        return nil;
    }];
}

- (void)detach {
    [self setKeyRouting:NO];
    [_webView.configuration.userContentController removeScriptMessageHandlerForName:@"probe"];
    _webView.navigationDelegate = nil;
    [_webView stopLoading];
    [self removeFromSuperview];
    if (_inputPath) {
        CGPathRelease(_inputPath);
        _inputPath = NULL;
    }
    LTDeleteGlobalRef(_listener);
    _listener = NULL;
}

@end
