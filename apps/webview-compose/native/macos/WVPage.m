#import "WVGlue.h"

/** What a question waits with (D3): each answer checks the kind before it calls the handler. */
typedef NS_ENUM(int, WVQuestionKind) {
    WVQuestionPolicy,
    WVQuestionResponse,
    WVQuestionDialog,
    WVQuestionPermission,
    WVQuestionOpenPanel,
};

/** A WebKit completion handler waiting on the engine: answered once, by the engine, or by default at release. */
@interface WVQuestion : NSObject
@property(nonatomic) WVQuestionKind kind;
/** The handler's call, a block typed by kind: see the answer methods. */
@property(nonatomic, copy) id answer;
@property(nonatomic, copy) dispatch_block_t byDefault;
@end

@implementation WVQuestion
@end

@class WVPage;

/** The relay's handler in the isolated world (iOS D1). WebKit holds handlers strongly, so it holds its page weakly. */
@interface WVRelayHandler : NSObject <WKScriptMessageHandler>
@property(nonatomic, weak) WVPage *page;
@end

/** Serves the lease's bundle through the engine (TASK_START, TASK_STOP), holding its page weakly. */
@interface WVBundleHandler : NSObject <WKURLSchemeHandler>
@property(nonatomic, weak) WVPage *page;
@end

/** Serves the floor (WebKitUrls.FLOOR_HTML) at any URL of its scheme. */
@interface WVFloorHandler : NSObject <WKURLSchemeHandler>
@property(nonatomic, copy) NSString *html;
@end

/** One engine's native side (D3–D6): its WKWebView and view, delegates, questions, scheme tasks and epochs. */
@interface WVPage : NSObject <WKNavigationDelegate, WKUIDelegate, WVPageOwner>
@property(nonatomic, readonly) jlong token;
@property(nonatomic, readonly) WKWebView *webView;
@property(nonatomic, readonly) WVPageView *view;
- (instancetype)initWithToken:(jlong)token profile:(int)profile store:(NSString *)storeUuid mediaNeedsGesture:(BOOL)gesture
                        world:(NSString *)worldName handler:(NSString *)handlerName bundleScheme:(NSString *)bundleScheme
                  floorScheme:(NSString *)floorScheme floorHtml:(NSString *)floorHtml;
- (void)relayMessage:(WKScriptMessage *)message;
- (void)startTask:(id<WKURLSchemeTask>)task;
- (void)stopTask:(id<WKURLSchemeTask>)task;
@end

@implementation WVRelayHandler
- (void)userContentController:(WKUserContentController *)controller didReceiveScriptMessage:(WKScriptMessage *)message {
    [self.page relayMessage:message];
}
@end

static NSError *WVUnknownHost(void) {
    return [NSError errorWithDomain:NSURLErrorDomain code:NSURLErrorCannotFindHost userInfo:nil];
}

@implementation WVBundleHandler
- (void)webView:(WKWebView *)webView startURLSchemeTask:(id<WKURLSchemeTask>)task {
    WVPage *page = self.page;
    if (page) {
        [page startTask:task];
    } else {
        [task didFailWithError:WVUnknownHost()];
    }
}

- (void)webView:(WKWebView *)webView stopURLSchemeTask:(id<WKURLSchemeTask>)task {
    [self.page stopTask:task];
}
@end

@implementation WVFloorHandler
- (void)webView:(WKWebView *)webView startURLSchemeTask:(id<WKURLSchemeTask>)task {
    NSData *body = [self.html dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
    NSDictionary<NSString *, NSString *> *fields = @{
        @"Content-Type" : @"text/html; charset=utf-8",
        @"Content-Length" : [NSString stringWithFormat:@"%lu", (unsigned long)body.length],
    };
    NSHTTPURLResponse *response = [[NSHTTPURLResponse alloc] initWithURL:task.request.URL statusCode:200
                                                             HTTPVersion:@"HTTP/1.1" headerFields:fields];
    [task didReceiveResponse:response];
    [task didReceiveData:body];
    [task didFinish];
}

- (void)webView:(WKWebView *)webView stopURLSchemeTask:(id<WKURLSchemeTask>)task {
    // Answered at once, in startURLSchemeTask: nothing is left to stop.
}
@end

/** WebKit's key-value observable state the engine mirrors (M13). */
static NSArray<NSString *> *WVObservedKeys(void) {
    static NSArray<NSString *> *keys;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        keys = @[ @"URL", @"title", @"loading", @"canGoBack", @"canGoForward" ];
    });
    return keys;
}

static void *WVObserved = &WVObserved;

/** The items' URLs, oldest first, joined by "\n" (a URL never holds a raw newline). */
static NSString *WVJoined(NSArray<WKBackForwardListItem *> *items) {
    NSMutableArray<NSString *> *urls = [NSMutableArray arrayWithCapacity:items.count];
    for (WKBackForwardListItem *item in items) [urls addObject:item.URL.absoluteString ?: @""];
    return [urls componentsJoinedByString:@"\n"];
}

/** Paths or file: URIs, as the engine's client answers an open panel. */
static NSArray<NSURL *> *WVFileURLs(NSArray<NSString *> *paths) {
    if (!paths) return nil;
    NSMutableArray<NSURL *> *urls = [NSMutableArray arrayWithCapacity:paths.count];
    for (NSString *path in paths) {
        NSURL *url = [path hasPrefix:@"file:"] ? [NSURL URLWithString:path] : [NSURL fileURLWithPath:path];
        if (url) [urls addObject:url];
    }
    return urls;
}

/**
 * Whether WebKit has sent this request already: it names the headers only its network layer adds (`Sec-Fetch-*`,
 * `Upgrade-Insecure-Requests`), as a server redirect's does, and never a navigation the page or the app starts, at its
 * policy decision (I6; the engine's `sentByTheNetwork` says why).
 */
static BOOL WVSentByTheNetwork(NSURLRequest *request) {
    for (NSString *name in request.allHTTPHeaderFields) {
        NSString *lower = name.lowercaseString;
        if ([lower hasPrefix:@"sec-fetch-"] || [lower isEqualToString:@"upgrade-insecure-requests"]) return YES;
    }
    return NO;
}

/** The pixels of the largest representation an image has (WebKit's snapshot: the screen's scale). */
static CGImageRef WVLargestImage(NSImage *image) {
    NSImageRep *best = nil;
    for (NSImageRep *rep in image.representations) {
        if (!best || rep.pixelsWide > best.pixelsWide) best = rep;
    }
    if (!best || best.pixelsWide <= 0 || best.pixelsHigh <= 0) return NULL;
    NSRect rect = NSMakeRect(0, 0, (CGFloat)best.pixelsWide, (CGFloat)best.pixelsHigh);
    return [best CGImageForProposedRect:&rect context:nil hints:nil];
}

@implementation WVPage {
    WKContentWorld *_world;
    WVRelayHandler *_relay;
    WVBundleHandler *_bundles;
    NSString *_floorScheme;
    id _floorState;                        // the floor's interactionState, kept the first time it finishes loading
    int _epoch;                            // the last epoch the engine gave (D4)
    int _committedEpoch;                   // the epoch of the last committed navigation
    NSMapTable<WKNavigation *, NSNumber *> *_navigations; // each navigation's epoch; weak keys
    NSMutableDictionary<NSNumber *, WVQuestion *> *_questions;
    jlong _nextQuestion;
    NSMutableDictionary<NSNumber *, id<WKURLSchemeTask>> *_tasks;
    jlong _nextTask;
    NSSize _loadSize;                      // the web view's size when the document being loaded started; zero: none
    BOOL _released;
}

- (instancetype)initWithToken:(jlong)token profile:(int)profile store:(NSString *)storeUuid mediaNeedsGesture:(BOOL)gesture
                        world:(NSString *)worldName handler:(NSString *)handlerName bundleScheme:(NSString *)bundleScheme
                  floorScheme:(NSString *)floorScheme floorHtml:(NSString *)floorHtml {
    self = [super init];
    if (!self) return nil;
    _token = token;
    _floorScheme = [floorScheme copy];
    _navigations = [NSMapTable weakToStrongObjectsMapTable];
    _questions = [NSMutableDictionary dictionary];
    _tasks = [NSMutableDictionary dictionary];
    WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
    switch (profile) {
        case 1:
            configuration.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
            break;
        case 2: {
            NSUUID *uuid = storeUuid ? [[NSUUID alloc] initWithUUIDString:storeUuid] : nil;
            configuration.websiteDataStore = uuid ? [WKWebsiteDataStore dataStoreForIdentifier:uuid] : [WKWebsiteDataStore defaultDataStore];
            break;
        }
        default:
            configuration.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
    }
    configuration.mediaTypesRequiringUserActionForPlayback = gesture ? WKAudiovisualMediaTypeAll : WKAudiovisualMediaTypeNone;
    configuration.preferences.javaScriptCanOpenWindowsAutomatically = NO;
    _bundles = [[WVBundleHandler alloc] init];
    _bundles.page = self;
    [configuration setURLSchemeHandler:_bundles forURLScheme:bundleScheme];
    WVFloorHandler *floor = [[WVFloorHandler alloc] init];
    floor.html = floorHtml;
    [configuration setURLSchemeHandler:floor forURLScheme:floorScheme];
    _world = [WKContentWorld worldWithName:worldName];
    _relay = [[WVRelayHandler alloc] init];
    _relay.page = self;
    [configuration.userContentController addScriptMessageHandler:_relay contentWorld:_world name:handlerName];
    // The main screen's size until a slot gives it its own, never none (D6).
    NSSize screen = NSScreen.mainScreen ? NSScreen.mainScreen.frame.size : NSMakeSize(1280, 800);
    _webView = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, screen.width, screen.height) configuration:configuration];
    _webView.navigationDelegate = self;
    _webView.UIDelegate = self;
    _webView.allowsBackForwardNavigationGestures = NO; // D10
    _webView.allowsMagnification = NO;                // panes turn it on (D7)
    _webView.allowsLinkPreview = NO;
    _view = [[WVPageView alloc] initWithWebView:_webView];
    _view.owner = self;
    for (NSString *key in WVObservedKeys()) [_webView addObserver:self forKeyPath:key options:0 context:WVObserved];
    return self;
}

// --- Upcalls ---

/** WebKit's page now: URL, history, title and loading state, with type, epoch and extra flags. */
- (void)postPage:(WVEvent)type epoch:(int)epoch flags:(int)flags {
    WVPost(_token, type, epoch, 0, flags | (_webView.loading ? 1 : 0), _webView.estimatedProgress, 0,
           _webView.URL.absoluteString, WVJoined(_webView.backForwardList.backList),
           WVJoined(_webView.backForwardList.forwardList), _webView.title, nil);
}

- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if (context != WVObserved) {
        [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
        return;
    }
    if (!_released) [self postPage:WVEventState epoch:_committedEpoch flags:0];
}

/** The epoch navigation started in (D4): registered when the engine started it, else the current one when first seen. */
- (int)epochOf:(WKNavigation *)navigation {
    if (!navigation) return _epoch;
    NSNumber *known = [_navigations objectForKey:navigation];
    if (known) return known.intValue;
    [_navigations setObject:@(_epoch) forKey:navigation];
    return _epoch;
}

- (void)started:(WKNavigation *)navigation epoch:(int)epoch {
    if (navigation) [_navigations setObject:@(epoch) forKey:navigation];
}

- (jlong)ask:(WVQuestionKind)kind answer:(id)answer byDefault:(dispatch_block_t)byDefault {
    WVQuestion *question = [[WVQuestion alloc] init];
    question.kind = kind;
    question.answer = answer;
    question.byDefault = byDefault;
    jlong ident = ++_nextQuestion;
    _questions[@(ident)] = question;
    return ident;
}

- (WVQuestion *)take:(jlong)ident kind:(WVQuestionKind)kind {
    WVQuestion *question = _questions[@(ident)];
    if (!question || question.kind != kind) return nil;
    [_questions removeObjectForKey:@(ident)];
    return question;
}

- (void)pageTookInputAtX:(CGFloat)x y:(CGFloat)y {
    if (!_released) WVPost(_token, WVEventPointer, 0, 0, 0, x, y, nil, nil, nil, nil, nil);
}

// --- WKNavigationDelegate ---

- (void)webView:(WKWebView *)webView decidePolicyForNavigationAction:(WKNavigationAction *)action
            preferences:(WKWebpagePreferences *)preferences
        decisionHandler:(void (^)(WKNavigationActionPolicy, WKWebpagePreferences *))decisionHandler {
    if (_released) {
        decisionHandler(WKNavigationActionPolicyCancel, preferences);
        return;
    }
    jlong question = [self ask:WVQuestionPolicy
        answer:^(BOOL allow, BOOL javaScript) {
            preferences.allowsContentJavaScript = javaScript;
            decisionHandler(allow ? WKNavigationActionPolicyAllow : WKNavigationActionPolicyCancel, preferences);
        }
        byDefault:^{
            decisionHandler(WKNavigationActionPolicyCancel, preferences);
        }];
    WKFrameInfo *target = action.targetFrame;
    int flags = (target == nil || target.isMainFrame ? 1 : 0) | (target == nil ? 2 : 0) | (action.shouldPerformDownload ? 4 : 0) |
                (WVSentByTheNetwork(action.request) ? 8 : 0) | ((int)(action.navigationType + 1) << 8);
    WVPost(_token, WVEventPolicy, _epoch, question, flags, 0, 0, action.request.URL.absoluteString, nil, nil, nil, nil);
}

- (void)webView:(WKWebView *)webView decidePolicyForNavigationResponse:(WKNavigationResponse *)navigationResponse
    decisionHandler:(void (^)(WKNavigationResponsePolicy))decisionHandler {
    if (_released) {
        decisionHandler(WKNavigationResponsePolicyCancel);
        return;
    }
    jlong question = [self ask:WVQuestionResponse
        answer:^(BOOL allow) {
            decisionHandler(allow ? WKNavigationResponsePolicyAllow : WKNavigationResponsePolicyCancel);
        }
        byDefault:^{
            decisionHandler(WKNavigationResponsePolicyCancel);
        }];
    NSURLResponse *response = navigationResponse.response;
    NSHTTPURLResponse *http = [response isKindOfClass:[NSHTTPURLResponse class]] ? (NSHTTPURLResponse *)response : nil;
    NSString *disposition = nil;
    for (id name in http.allHeaderFields) {
        if (![name isKindOfClass:[NSString class]] || [(NSString *)name caseInsensitiveCompare:@"Content-Disposition"] != NSOrderedSame) continue;
        id value = http.allHeaderFields[name];
        if ([value isKindOfClass:[NSString class]]) disposition = value;
    }
    int flags = (navigationResponse.forMainFrame ? 1 : 0) | (navigationResponse.canShowMIMEType ? 2 : 0) |
                (http ? 4 | ((int)http.statusCode << 8) : 0);
    double length = response.expectedContentLength >= 0 ? (double)response.expectedContentLength : -1;
    WVPost(_token, WVEventResponse, _epoch, question, flags, length, 0, response.URL.absoluteString, response.MIMEType,
           response.suggestedFilename, disposition, nil);
}

- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)navigation {
    if (_released) return;
    WVPost(_token, WVEventStarted, [self epochOf:navigation], 0, 0, 0, 0, webView.URL.absoluteString, nil, nil, nil, nil);
}

- (void)webView:(WKWebView *)webView didCommitNavigation:(WKNavigation *)navigation {
    if (_released) return;
    int epoch = [self epochOf:navigation];
    _committedEpoch = epoch;
    [self sizeAgain];
    [self postPage:WVEventCommitted epoch:epoch flags:0];
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    if (_released) return;
    int epoch = [self epochOf:navigation];
    if (!_floorState && [webView.URL.scheme isEqualToString:_floorScheme]) {
        id state = webView.interactionState;
        if (state) _floorState = state;
    }
    [self postPage:WVEventFinished epoch:epoch flags:_floorState ? 2 : 0];
}

- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    [self failed:navigation error:error];
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    [self failed:navigation error:error];
}

/**
 * The URL that failed, as WebKit names it, or none: never the web view's own URL, which is still the document committed
 * before (the floor, or an earlier page). A refused connection names its URL only as an NSURL (macOS 26).
 */
- (void)failed:(WKNavigation *)navigation error:(NSError *)error {
    if (_released) return;
    id failing = error.userInfo[NSURLErrorFailingURLStringErrorKey];
    id failingUrl = error.userInfo[NSURLErrorFailingURLErrorKey];
    NSString *url = [failing isKindOfClass:[NSString class]] ? failing
                  : [failingUrl isKindOfClass:[NSURL class]] ? ((NSURL *)failingUrl).absoluteString : nil;
    WVPost(_token, WVEventFailed, [self epochOf:navigation], 0, 0, (double)error.code, 0, url, error.domain,
           error.localizedDescription, nil, nil);
}

- (void)webViewWebContentProcessDidTerminate:(WKWebView *)webView {
    if (!_released) WVPost(_token, WVEventCrashed, 0, 0, 0, 0, 0, nil, nil, nil, nil, nil);
}

/**
 * A document of another site loads in a WebContent process of its own, which takes the view's size when the load
 * starts: a resize while it loaded reached the old process only (iOS's lesson, D6). WebKit hears a size only when it
 * changes, so it changes twice.
 */
- (void)sizeAgain {
    NSSize was = _loadSize;
    _loadSize = NSZeroSize;
    NSRect frame = _webView.frame;
    if (NSEqualSizes(was, NSZeroSize) || NSEqualSizes(was, frame.size) || frame.size.width <= 0 || frame.size.height <= 0) return;
    _webView.frame = NSMakeRect(frame.origin.x, frame.origin.y, frame.size.width + 1, frame.size.height);
    _webView.frame = frame;
}

// --- WKUIDelegate ---

- (WKWebView *)webView:(WKWebView *)webView createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration
    forNavigationAction:(WKNavigationAction *)action windowFeatures:(WKWindowFeatures *)windowFeatures {
    if (!_released) WVPost(_token, WVEventNewWindow, 0, 0, 0, 0, 0, action.request.URL.absoluteString, nil, nil, nil, nil);
    return nil;
}

- (void)webView:(WKWebView *)webView runJavaScriptAlertPanelWithMessage:(NSString *)message
    initiatedByFrame:(WKFrameInfo *)frame completionHandler:(void (^)(void))completionHandler {
    if (_released) {
        completionHandler();
        return;
    }
    jlong question = [self ask:WVQuestionDialog
        answer:^(BOOL confirmed, NSString *text) {
            completionHandler();
        }
        byDefault:^{
            completionHandler();
        }];
    WVPost(_token, WVEventDialog, 0, question, 0, 0, 0, message, nil, frame.request.URL.absoluteString, nil, nil);
}

- (void)webView:(WKWebView *)webView runJavaScriptConfirmPanelWithMessage:(NSString *)message
    initiatedByFrame:(WKFrameInfo *)frame completionHandler:(void (^)(BOOL))completionHandler {
    if (_released) {
        completionHandler(NO);
        return;
    }
    jlong question = [self ask:WVQuestionDialog
        answer:^(BOOL confirmed, NSString *text) {
            completionHandler(confirmed);
        }
        byDefault:^{
            completionHandler(NO);
        }];
    WVPost(_token, WVEventDialog, 0, question, 1, 0, 0, message, nil, frame.request.URL.absoluteString, nil, nil);
}

- (void)webView:(WKWebView *)webView runJavaScriptTextInputPanelWithPrompt:(NSString *)prompt
    defaultText:(NSString *)defaultText initiatedByFrame:(WKFrameInfo *)frame
    completionHandler:(void (^)(NSString *))completionHandler {
    if (_released) {
        completionHandler(nil);
        return;
    }
    jlong question = [self ask:WVQuestionDialog
        answer:^(BOOL confirmed, NSString *text) {
            completionHandler(confirmed ? (text ?: @"") : nil);
        }
        byDefault:^{
            completionHandler(nil);
        }];
    WVPost(_token, WVEventDialog, 0, question, 2, 0, 0, prompt, defaultText, frame.request.URL.absoluteString, nil, nil);
}

- (void)webView:(WKWebView *)webView requestMediaCapturePermissionForOrigin:(WKSecurityOrigin *)origin
    initiatedByFrame:(WKFrameInfo *)frame type:(WKMediaCaptureType)type
    decisionHandler:(void (^)(WKPermissionDecision))decisionHandler {
    if (_released) {
        decisionHandler(WKPermissionDecisionDeny);
        return;
    }
    jlong question = [self ask:WVQuestionPermission
        answer:^(BOOL grant) {
            decisionHandler(grant ? WKPermissionDecisionGrant : WKPermissionDecisionDeny);
        }
        byDefault:^{
            decisionHandler(WKPermissionDecisionDeny);
        }];
    int kind;
    switch (type) {
        case WKMediaCaptureTypeCamera: kind = 0; break;
        case WKMediaCaptureTypeMicrophone: kind = 1; break;
        case WKMediaCaptureTypeCameraAndMicrophone: kind = 2; break;
        default: kind = 3; break;
    }
    WVPost(_token, WVEventMedia, 0, question, kind, (double)origin.port, 0, origin.protocol, origin.host, nil, nil, nil);
}

- (void)webView:(WKWebView *)webView runOpenPanelWithParameters:(WKOpenPanelParameters *)parameters
    initiatedByFrame:(WKFrameInfo *)frame completionHandler:(void (^)(NSArray<NSURL *> *))completionHandler {
    if (_released) {
        completionHandler(nil);
        return;
    }
    jlong question = [self ask:WVQuestionOpenPanel
        answer:^(NSArray<NSURL *> *urls) {
            completionHandler(urls);
        }
        byDefault:^{
            completionHandler(nil);
        }];
    int flags = (parameters.allowsMultipleSelection ? 1 : 0) | (parameters.allowsDirectories ? 2 : 0);
    WVPost(_token, WVEventOpenPanel, 0, question, flags, 0, 0, nil, nil, nil, nil, nil);
}

// --- The relay and the bundle's scheme tasks ---

- (void)relayMessage:(WKScriptMessage *)message {
    if (_released || ![message.body isKindOfClass:[NSString class]]) return;
    WKSecurityOrigin *origin = message.frameInfo.securityOrigin;
    WVPost(_token, WVEventRelay, _committedEpoch, 0, message.frameInfo.isMainFrame ? 1 : 0, (double)origin.port, 0,
           message.body, origin.protocol, origin.host, nil, nil);
}

- (void)startTask:(id<WKURLSchemeTask>)task {
    if (_released) {
        [task didFailWithError:WVUnknownHost()];
        return;
    }
    jlong ident = ++_nextTask;
    _tasks[@(ident)] = task;
    WVPost(_token, WVEventTaskStart, 0, ident, 0, 0, 0, task.request.URL.absoluteString, nil, nil, nil, nil);
}

- (void)stopTask:(id<WKURLSchemeTask>)task {
    for (NSNumber *key in _tasks.allKeys) {
        if (_tasks[key] != task) continue;
        // WebKit stopped it: never touched again (it would raise).
        [_tasks removeObjectForKey:key];
        if (!_released) WVPost(_token, WVEventTaskStop, 0, key.longLongValue, 0, 0, 0, nil, nil, nil, nil, nil);
        return;
    }
}

// --- Operations (PageGlue) ---

- (void)configureDark:(BOOL)dark transparent:(BOOL)transparent userAgent:(NSString *)userAgent
          inspectable:(BOOL)inspectable relay:(NSString *)relay shim:(NSString *)shim {
    if (_released) return;
    // prefers-color-scheme follows the view's appearance.
    _webView.appearance = [NSAppearance appearanceNamed:dark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua];
    @try {
        [_webView setValue:@(!transparent) forKey:@"drawsBackground"];
    } @catch (NSException *exception) {
        (void)exception; // an older WebKit without the key: the background stays drawn
    }
    _webView.underPageBackgroundColor = transparent ? NSColor.clearColor : (dark ? NSColor.blackColor : NSColor.whiteColor);
    _webView.customUserAgent = userAgent;
    _webView.inspectable = inspectable;
    WKUserContentController *controller = _webView.configuration.userContentController;
    [controller removeAllUserScripts];
    if (relay) {
        [controller addUserScript:[[WKUserScript alloc] initWithSource:relay injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                                      forMainFrameOnly:YES inContentWorld:_world]];
    }
    if (shim) {
        [controller addUserScript:[[WKUserScript alloc] initWithSource:shim injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                                      forMainFrameOnly:YES inContentWorld:WKContentWorld.pageWorld]];
    }
}

- (void)loadUrl:(NSString *)url headers:(NSDictionary<NSString *, NSString *> *)headers epoch:(int)epoch {
    if (_released) return;
    _epoch = epoch;
    _loadSize = _webView.frame.size;
    NSURL *parsed = url ? [NSURL URLWithString:url] : nil;
    if (!parsed) {
        WVPost(_token, WVEventFailed, epoch, 0, 0, (double)NSURLErrorBadURL, 0, url, NSURLErrorDomain,
               @"WebKit can't parse this URL", nil, nil);
        return;
    }
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:parsed];
    [headers enumerateKeysAndObjectsUsingBlock:^(NSString *name, NSString *value, BOOL *stop) {
        [request setValue:value forHTTPHeaderField:name];
    }];
    [self started:[_webView loadRequest:request] epoch:epoch];
}

- (void)loadHtml:(NSString *)html baseUrl:(NSString *)baseUrl epoch:(int)epoch {
    if (_released) return;
    _epoch = epoch;
    _loadSize = _webView.frame.size;
    [self started:[_webView loadHTMLString:html ?: @"" baseURL:baseUrl ? [NSURL URLWithString:baseUrl] : nil] epoch:epoch];
}

- (void)reloadInEpoch:(int)epoch {
    if (_released) return;
    _epoch = epoch;
    _loadSize = _webView.frame.size;
    [self started:[_webView reload] epoch:epoch];
}

- (void)goBack {
    if (!_released) [self started:[_webView goBack] epoch:_epoch];
}

- (void)goForward {
    if (!_released) [self started:[_webView goForward] epoch:_epoch];
}

- (void)stop {
    if (!_released) [_webView stopLoading];
}

- (void)evaluate:(NSString *)script request:(jlong)request pageWorld:(BOOL)pageWorld {
    jlong token = _token;
    if (_released || !script) {
        WVPost(token, WVEventResult, 0, request, 0, 0, 0, nil, nil, nil, nil, nil);
        return;
    }
    WKContentWorld *world = pageWorld ? WKContentWorld.pageWorld : WKContentWorld.defaultClientWorld;
    [_webView evaluateJavaScript:script inFrame:nil inContentWorld:world completionHandler:^(id result, NSError *error) {
        NSString *json = error ? nil : WVJsonOf(result);
        WVPost(token, WVEventResult, 0, request, json ? 1 : 0, 0, 0, json, nil, nil, nil, nil);
    }];
}

- (void)deliver:(NSString *)body json:(NSString *)json target:(NSString *)target name:(NSString *)name {
    if (_released || !body || !json || !target || !name) return;
    [_webView callAsyncJavaScript:body arguments:@{ @"json" : json, @"target" : target, @"name" : name } inFrame:nil
                   inContentWorld:_world completionHandler:nil];
}

- (void)repaint:(NSString *)body request:(jlong)request {
    jlong token = _token;
    if (_released || !body) {
        WVPost(token, WVEventResult, 0, request, 0, 0, 0, nil, nil, nil, nil, nil);
        return;
    }
    [_webView callAsyncJavaScript:body arguments:@{} inFrame:nil inContentWorld:_world
                completionHandler:^(id result, NSError *error) {
                    WVPost(token, WVEventResult, 0, request, error ? 0 : 1, 0, 0, nil, nil, nil, nil, nil);
                }];
}

- (void)snapshot:(jlong)request width:(double)widthPoints {
    jlong token = _token;
    if (_released || widthPoints < 1) {
        WVPost(token, WVEventSnapshot, 0, request, 0, 0, 0, nil, nil, nil, nil, nil);
        return;
    }
    WKSnapshotConfiguration *configuration = [[WKSnapshotConfiguration alloc] init];
    configuration.snapshotWidth = @(widthPoints);
    [_webView takeSnapshotWithConfiguration:configuration completionHandler:^(NSImage *image, NSError *error) {
        if (!image) {
            WVPost(token, WVEventSnapshot, 0, request, 0, 0, 0, nil, nil, nil, nil, nil);
            return;
        }
        // The pixels are copied off AppKit's thread, as iOS copies them off its main thread.
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            size_t width = 0, height = 0;
            NSData *pixels = WVPixelsOf(WVLargestImage(image), &width, &height);
            WVPost(token, WVEventSnapshot, 0, request, 0, (double)width, (double)height, nil, nil, nil, nil, pixels);
        });
    }];
}

- (void)saveState:(jlong)request {
    id state = _released ? nil : _webView.interactionState;
    NSData *data = [state isKindOfClass:[NSData class]] ? state : nil;
    WVPost(_token, WVEventResult, 0, request, data ? 1 : 0, 0, 0, nil, nil, nil, nil, data);
}

/**
 * WebKit ignores state it can't decode: the current item is then still the one it was. A state whose page is the floor
 * (saved after a load that failed, by an engine before iOS's rules) shows nothing of the content: NO, as on iOS, and the
 * engine loads the content instead (the floor's navigation, the engine's no longer, is refused).
 */
- (BOOL)restoreState:(NSData *)state epoch:(int)epoch {
    if (_released || !state) return NO;
    _epoch = epoch;
    _loadSize = _webView.frame.size;
    WKBackForwardListItem *before = _webView.backForwardList.currentItem;
    _webView.interactionState = state;
    WKBackForwardListItem *current = _webView.backForwardList.currentItem;
    return current != before && ![current.URL.scheme isEqualToString:_floorScheme];
}

/** The floor's one-item history back (iOS D4); the engine waits for its FINISHED. */
- (BOOL)showFloorInEpoch:(int)epoch {
    if (_released || !_floorState) return NO;
    _epoch = epoch;
    _webView.interactionState = _floorState;
    return YES;
}

- (void)setMediaSuspended:(BOOL)suspended {
    if (!_released) [_webView setAllMediaPlaybackSuspended:suspended completionHandler:^{}];
}

- (void)stopMedia {
    if (_released) return;
    [_webView pauseAllMediaPlaybackWithCompletionHandler:^{}];
    [_webView closeAllMediaPresentationsWithCompletionHandler:^{}];
}

- (void)removeScripts {
    if (!_released) [_webView.configuration.userContentController removeAllUserScripts];
}

- (void)wipeData:(jlong)request {
    jlong token = _token;
    if (_released) {
        WVPost(token, WVEventResult, 0, request, 0, 0, 0, nil, nil, nil, nil, nil);
        return;
    }
    [_webView.configuration.websiteDataStore removeDataOfTypes:[WKWebsiteDataStore allWebsiteDataTypes]
                                                 modifiedSince:[NSDate distantPast]
                                             completionHandler:^{
                                                 WVPost(token, WVEventResult, 0, request, 1, 0, 0, nil, nil, nil, nil, nil);
                                             }];
}

- (void)answerPolicy:(jlong)ident allow:(BOOL)allow javaScript:(BOOL)javaScript {
    WVQuestion *question = [self take:ident kind:WVQuestionPolicy];
    if (!question) return;
    void (^answer)(BOOL, BOOL) = question.answer;
    answer(allow, javaScript);
}

- (void)answerResponse:(jlong)ident allow:(BOOL)allow {
    WVQuestion *question = [self take:ident kind:WVQuestionResponse];
    if (!question) return;
    void (^answer)(BOOL) = question.answer;
    answer(allow);
}

- (void)answerDialog:(jlong)ident confirmed:(BOOL)confirmed text:(NSString *)text {
    WVQuestion *question = [self take:ident kind:WVQuestionDialog];
    if (!question) return;
    void (^answer)(BOOL, NSString *) = question.answer;
    answer(confirmed, text);
}

- (void)answerPermission:(jlong)ident grant:(BOOL)grant {
    WVQuestion *question = [self take:ident kind:WVQuestionPermission];
    if (!question) return;
    void (^answer)(BOOL) = question.answer;
    answer(grant);
}

- (void)answerOpenPanel:(jlong)ident paths:(NSArray<NSString *> *)paths {
    WVQuestion *question = [self take:ident kind:WVQuestionOpenPanel];
    if (!question) return;
    void (^answer)(NSArray<NSURL *> *) = question.answer;
    answer(WVFileURLs(paths));
}

- (void)respond:(jlong)ident status:(int)status mimeType:(NSString *)mimeType body:(NSData *)body
        headers:(NSDictionary<NSString *, NSString *> *)headers {
    id<WKURLSchemeTask> task = _tasks[@(ident)];
    if (!task) return; // stopped by WebKit meanwhile: never touched again
    [_tasks removeObjectForKey:@(ident)];
    NSMutableDictionary<NSString *, NSString *> *fields = [NSMutableDictionary dictionary];
    [headers enumerateKeysAndObjectsUsingBlock:^(NSString *name, NSString *value, BOOL *stop) {
        // The provider's own headers; it never overrides the type or the length.
        if ([name caseInsensitiveCompare:@"Content-Type"] == NSOrderedSame) return;
        if ([name caseInsensitiveCompare:@"Content-Length"] == NSOrderedSame) return;
        fields[name] = value;
    }];
    fields[@"Content-Type"] = mimeType ?: @"application/octet-stream";
    fields[@"Content-Length"] = [NSString stringWithFormat:@"%lu", (unsigned long)body.length];
    NSHTTPURLResponse *response = [[NSHTTPURLResponse alloc] initWithURL:task.request.URL statusCode:status
                                                             HTTPVersion:@"HTTP/1.1" headerFields:fields];
    [task didReceiveResponse:response];
    if (body.length) [task didReceiveData:body];
    [task didFinish];
}

- (void)failTask:(jlong)ident {
    id<WKURLSchemeTask> task = _tasks[@(ident)];
    if (!task) return;
    [_tasks removeObjectForKey:@(ident)];
    [task didFailWithError:WVUnknownHost()];
}

- (void)setInput:(NSData *)geometry holes:(int)holeCount density:(CGFloat)density flags:(int)flags {
    if (_released) return;
    [_view setRegion:geometry holes:holeCount density:density];
    _view.interactive = (flags & 1) != 0;
    _view.shown = (flags & 2) != 0;
    _view.wheelToPage = (flags & 4) != 0;
    _webView.allowsMagnification = _view.wheelToPage;
}

- (void)focus {
    if (!_released && _view.window) [_view.window makeFirstResponder:_webView];
}

/** argb: the page's background, opaque; 0 for no backdrop. */
- (void)setBackdrop:(int)argb {
    if (_released) return;
    uint32_t c = (uint32_t)argb;
    NSColor *color = c == 0 ? nil : [NSColor colorWithSRGBRed:((c >> 16) & 0xFF) / 255.0 green:((c >> 8) & 0xFF) / 255.0
                                                          blue:(c & 0xFF) / 255.0 alpha:((c >> 24) & 0xFF) / 255.0];
    [_view setBackdropColor:color];
}

/** Answers what WebKit still waits on with its safe default, and lets go of everything (release). */
- (void)shutDown {
    if (_released) return;
    _released = YES;
    NSArray<WVQuestion *> *waiting = _questions.allValues;
    [_questions removeAllObjects];
    for (WVQuestion *question in waiting) question.byDefault();
    for (NSString *key in WVObservedKeys()) [_webView removeObserver:self forKeyPath:key context:WVObserved];
    [_view stopTicks];
    // Stopping stops the scheme tasks: WebKit calls stopURLSchemeTask:, which drops them unanswered.
    [_webView stopLoading];
    [_tasks removeAllObjects];
    WKUserContentController *controller = _webView.configuration.userContentController;
    [controller removeAllUserScripts];
    [controller removeAllScriptMessageHandlers];
    _webView.navigationDelegate = nil;
    _webView.UIDelegate = nil;
    _relay.page = nil;
    _bundles.page = nil;
    [_view removeFromSuperview];
    _view.owner = nil;
}

@end

static inline WVPage *WVPageOf(jlong handle) {
    return (__bridge WVPage *)(void *)(intptr_t)handle;
}

// --- Entry points: every one posts its work to AppKit's thread, in order, and only restoreState and showFloor wait ---

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_createPage(
    JNIEnv *env, jclass type, jlong token, jint profile, jstring storeUuid, jboolean mediaNeedsGesture, jstring worldName,
    jstring handlerName, jstring bundleScheme, jstring floorScheme, jstring floorHtml) {
    NSString *store = WVString(env, storeUuid), *world = WVString(env, worldName), *handler = WVString(env, handlerName);
    NSString *bundle = WVString(env, bundleScheme), *floor = WVString(env, floorScheme), *html = WVString(env, floorHtml);
    BOOL gesture = mediaNeedsGesture == JNI_TRUE;
    WVOnMain(NO, ^{
        WVPage *page = nil;
        @try {
            page = [[WVPage alloc] initWithToken:token profile:profile store:store mediaNeedsGesture:gesture world:world
                                         handler:handler bundleScheme:bundle floorScheme:floor floorHtml:html];
        } @catch (NSException *exception) {
            (void)exception; // WebKit refused the configuration: the engine reports CreationFailed
            page = nil;
        }
        jlong handle = page ? (jlong)(intptr_t)CFBridgingRetain(page) : 0;
        WVPost(token, WVEventCreated, 0, handle, 0, 0, 0, nil, nil, nil, nil, nil);
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_configure(
    JNIEnv *env, jclass type, jlong handle, jboolean dark, jboolean transparent, jstring userAgent, jboolean inspectable,
    jstring relay, jstring shim) {
    WVPage *page = WVPageOf(handle);
    NSString *agent = WVString(env, userAgent), *relayScript = WVString(env, relay), *shimScript = WVString(env, shim);
    BOOL isDark = dark == JNI_TRUE, isTransparent = transparent == JNI_TRUE, isInspectable = inspectable == JNI_TRUE;
    WVOnMain(NO, ^{
        [page configureDark:isDark transparent:isTransparent userAgent:agent inspectable:isInspectable relay:relayScript shim:shimScript];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_loadUrl(
    JNIEnv *env, jclass type, jlong handle, jint epoch, jstring url, jobjectArray headerNames, jobjectArray headerValues) {
    WVPage *page = WVPageOf(handle);
    NSString *target = WVString(env, url);
    NSArray<NSString *> *names = WVStrings(env, headerNames), *values = WVStrings(env, headerValues);
    NSMutableDictionary<NSString *, NSString *> *headers = [NSMutableDictionary dictionary];
    for (NSUInteger i = 0; i < names.count && i < values.count; i++) headers[names[i]] = values[i];
    WVOnMain(NO, ^{
        [page loadUrl:target headers:headers epoch:epoch];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_loadHtml(
    JNIEnv *env, jclass type, jlong handle, jint epoch, jstring html, jstring baseUrl) {
    WVPage *page = WVPageOf(handle);
    NSString *document = WVString(env, html), *base = WVString(env, baseUrl);
    WVOnMain(NO, ^{
        [page loadHtml:document baseUrl:base epoch:epoch];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_reload(JNIEnv *env, jclass type, jlong handle, jint epoch) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page reloadInEpoch:epoch];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_goBack(JNIEnv *env, jclass type, jlong handle) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page goBack];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_goForward(JNIEnv *env, jclass type, jlong handle) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page goForward];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_stop(JNIEnv *env, jclass type, jlong handle) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page stop];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_evaluate(
    JNIEnv *env, jclass type, jlong handle, jlong request, jstring script, jboolean pageWorld) {
    WVPage *page = WVPageOf(handle);
    NSString *source = WVString(env, script);
    BOOL inPage = pageWorld == JNI_TRUE;
    WVOnMain(NO, ^{
        [page evaluate:source request:request pageWorld:inPage];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_deliver(
    JNIEnv *env, jclass type, jlong handle, jstring body, jstring json, jstring target, jstring eventName) {
    WVPage *page = WVPageOf(handle);
    NSString *script = WVString(env, body), *message = WVString(env, json), *origin = WVString(env, target), *name = WVString(env, eventName);
    WVOnMain(NO, ^{
        [page deliver:script json:message target:origin name:name];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_repaint(
    JNIEnv *env, jclass type, jlong handle, jlong request, jstring body) {
    WVPage *page = WVPageOf(handle);
    NSString *script = WVString(env, body);
    WVOnMain(NO, ^{
        [page repaint:script request:request];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_snapshot(
    JNIEnv *env, jclass type, jlong handle, jlong request, jdouble widthPoints) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page snapshot:request width:widthPoints];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_saveState(JNIEnv *env, jclass type, jlong handle, jlong request) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page saveState:request];
    });
}

JNIEXPORT jboolean JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_restoreState(
    JNIEnv *env, jclass type, jlong handle, jint epoch, jbyteArray state) {
    WVPage *page = WVPageOf(handle);
    NSData *data = WVBytes(env, state);
    __block BOOL restored = NO;
    WVOnMain(YES, ^{
        restored = [page restoreState:data epoch:epoch];
    });
    return restored ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_showFloor(JNIEnv *env, jclass type, jlong handle, jint epoch) {
    WVPage *page = WVPageOf(handle);
    __block BOOL shown = NO;
    WVOnMain(YES, ^{
        shown = [page showFloorInEpoch:epoch];
    });
    return shown ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_setMediaSuspended(
    JNIEnv *env, jclass type, jlong handle, jboolean suspended) {
    WVPage *page = WVPageOf(handle);
    BOOL suspend = suspended == JNI_TRUE;
    WVOnMain(NO, ^{
        [page setMediaSuspended:suspend];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_stopMedia(JNIEnv *env, jclass type, jlong handle) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page stopMedia];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_removeScripts(JNIEnv *env, jclass type, jlong handle) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page removeScripts];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_wipeData(JNIEnv *env, jclass type, jlong handle, jlong request) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page wipeData:request];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_answerPolicy(
    JNIEnv *env, jclass type, jlong handle, jlong question, jboolean allow, jboolean javaScript) {
    WVPage *page = WVPageOf(handle);
    BOOL yes = allow == JNI_TRUE, scripts = javaScript == JNI_TRUE;
    WVOnMain(NO, ^{
        [page answerPolicy:question allow:yes javaScript:scripts];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_answerResponse(
    JNIEnv *env, jclass type, jlong handle, jlong question, jboolean allow) {
    WVPage *page = WVPageOf(handle);
    BOOL yes = allow == JNI_TRUE;
    WVOnMain(NO, ^{
        [page answerResponse:question allow:yes];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_answerDialog(
    JNIEnv *env, jclass type, jlong handle, jlong question, jboolean confirmed, jstring text) {
    WVPage *page = WVPageOf(handle);
    BOOL yes = confirmed == JNI_TRUE;
    NSString *value = WVString(env, text);
    WVOnMain(NO, ^{
        [page answerDialog:question confirmed:yes text:value];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_answerPermission(
    JNIEnv *env, jclass type, jlong handle, jlong question, jboolean grant) {
    WVPage *page = WVPageOf(handle);
    BOOL yes = grant == JNI_TRUE;
    WVOnMain(NO, ^{
        [page answerPermission:question grant:yes];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_answerOpenPanel(
    JNIEnv *env, jclass type, jlong handle, jlong question, jobjectArray paths) {
    WVPage *page = WVPageOf(handle);
    NSArray<NSString *> *chosen = WVStrings(env, paths);
    WVOnMain(NO, ^{
        [page answerOpenPanel:question paths:chosen];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_respond(
    JNIEnv *env, jclass type, jlong handle, jlong task, jint status, jstring mimeType, jbyteArray body,
    jobjectArray headerNames, jobjectArray headerValues) {
    WVPage *page = WVPageOf(handle);
    NSString *mime = WVString(env, mimeType);
    NSData *bytes = WVBytes(env, body) ?: [NSData data];
    NSArray<NSString *> *names = WVStrings(env, headerNames), *values = WVStrings(env, headerValues);
    NSMutableDictionary<NSString *, NSString *> *headers = [NSMutableDictionary dictionary];
    for (NSUInteger i = 0; i < names.count && i < values.count; i++) headers[names[i]] = values[i];
    WVOnMain(NO, ^{
        [page respond:task status:status mimeType:mime body:bytes headers:headers];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_failTask(JNIEnv *env, jclass type, jlong handle, jlong task) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page failTask:task];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_attach(JNIEnv *env, jclass type, jlong handle, jlong window) {
    WVPage *page = WVPageOf(handle);
    NSWindow *target = WVWindow(window);
    WVOnMain(NO, ^{
        if (target) [page.view attachTo:target];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_detach(JNIEnv *env, jclass type, jlong handle, jlong window) {
    WVPage *page = WVPageOf(handle);
    NSWindow *from = WVWindow(window);
    WVOnMain(NO, ^{
        if (from) [page.view detachFrom:from];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_place(
    JNIEnv *env, jclass type, jlong handle, jlong window, jfloat x, jfloat y, jfloat width, jfloat height, jfloat shownX,
    jfloat shownY, jfloat shownWidth, jfloat shownHeight, jfloat scale, jboolean hidden) {
    WVPage *page = WVPageOf(handle);
    BOOL hide = hidden == JNI_TRUE;
    NSRect slot = NSMakeRect(x, y, width, height), shown = NSMakeRect(shownX, shownY, shownWidth, shownHeight);
    long long turn = WVNextPlacement();
    [page.view placementSent];
    dispatch_block_t placement = ^{
        [page.view placeSlot:slot shown:shown scale:scale hidden:hide turn:turn];
    };
    // In a window in sync, the view moves in the transaction that presents the frame of this layout (WVFrameSync.m).
    if (!WVFrameSyncEnqueue(window, placement)) WVOnMain(NO, placement);
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_setInput(
    JNIEnv *env, jclass type, jlong handle, jfloatArray geometry, jint holeCount, jfloat density, jint flags) {
    WVPage *page = WVPageOf(handle);
    NSData *region = WVFloats(env, geometry);
    WVOnMain(NO, ^{
        [page setInput:region holes:holeCount density:density flags:flags];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_setBackdrop(JNIEnv *env, jclass type, jlong handle, jint argb) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page setBackdrop:argb];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_tick(JNIEnv *env, jclass type, jlong handle, jlong request) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page.view tickFor:request];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_focus(JNIEnv *env, jclass type, jlong handle) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page focus];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_release(JNIEnv *env, jclass type, jlong handle) {
    WVOnMain(NO, ^{
        WVPage *page = WVPageOf(handle);
        [page shutDown];
        // The creation's retain; the page goes when this block's reference does.
        CFBridgingRelease((CFTypeRef)(void *)(intptr_t)handle);
    });
}
