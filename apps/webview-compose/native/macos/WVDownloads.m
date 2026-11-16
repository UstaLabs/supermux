// Downloads (#157): WebKit saves them, with the page's own session (cookies, a form's POST, a blob:), and this file
// reports them. A download outlives its page: it is the process's, by an id of its own, and its upcalls go to page 0.
// Everything here runs on AppKit's main thread, but the progress observer, which hops there.
#import "WVGlue.h"

/** One download: WebKit's (replaced on a resume), where it goes, and what a pause or a failure left to resume from. */
@interface WVDownloadEntry : NSObject
@property(nonatomic) jlong ident;
/** The engine asked where to save it: its page's token. */
@property(nonatomic) jlong page;
@property(nonatomic, strong) WKDownload *download;
@property(nonatomic, copy) NSURL *destination;
@property(nonatomic, copy) NSData *resumeData;
@property(nonatomic, copy) NSURLRequest *request;
/** The data store it was made in: a resume needs a web view of the same session, the page's own may be gone. */
@property(nonatomic, strong) WKWebsiteDataStore *store;
/** A web view of store kept for a resume whose page has gone, until the download ends. */
@property(nonatomic, strong) WKWebView *standIn;
@property(nonatomic) BOOL pausing;
@property(nonatomic) BOOL cancelling;
@property(nonatomic) int64_t reported;
@end

@implementation WVDownloadEntry
@end

@interface WVDownloads : NSObject <WKDownloadDelegate>
+ (instancetype)shared;
@end

@implementation WVDownloads {
    NSMutableDictionary<NSNumber *, WVDownloadEntry *> *_entries;
    /** The destination question of each download being asked about, by id. */
    NSMutableDictionary<NSNumber *, void (^)(NSURL *)> *_asking;
    jlong _next;
}

+ (instancetype)shared {
    static WVDownloads *shared;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        shared = [[WVDownloads alloc] init];
    });
    return shared;
}

- (instancetype)init {
    if ((self = [super init])) {
        _entries = [NSMutableDictionary dictionary];
        _asking = [NSMutableDictionary dictionary];
    }
    return self;
}

- (WVDownloadEntry *)entryOf:(WKDownload *)download {
    for (WVDownloadEntry *entry in _entries.allValues) {
        if (entry.download == download) return entry;
    }
    return nil;
}

- (void)adopt:(WKDownload *)download page:(jlong)page {
    WVDownloadEntry *entry = [[WVDownloadEntry alloc] init];
    entry.ident = ++_next;
    entry.download = download;
    entry.request = download.originalRequest;
    entry.store = download.webView.configuration.websiteDataStore;
    entry.reported = -1;
    entry.page = page;
    // A page that has gone has nobody to ask where it goes: cancelled.
    if (page == 0) {
        [download cancel:nil];
        return;
    }
    _entries[@(entry.ident)] = entry;
    // Asked once WebKit has the response (decideDestination…), with its real name, type and length.
    download.delegate = self;
}

- (void)watch:(WVDownloadEntry *)entry {
    [entry.download.progress addObserver:self forKeyPath:@"completedUnitCount" options:0 context:(void *)(intptr_t)entry.ident];
}

- (void)unwatch:(WVDownloadEntry *)entry {
    @try {
        [entry.download.progress removeObserver:self forKeyPath:@"completedUnitCount" context:(void *)(intptr_t)entry.ident];
    } @catch (NSException *e) {
        // Not watched yet: nothing to remove.
    }
}

- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    jlong ident = (jlong)(intptr_t)context;
    NSProgress *progress = object;
    int64_t received = progress.completedUnitCount, total = progress.totalUnitCount;
    WVOnMain(NO, ^{
        WVDownloadEntry *entry = self->_entries[@(ident)];
        if (!entry || entry.pausing || entry.cancelling) return;
        // Every 64 KiB at most: NSProgress moves per network read.
        if (entry.reported >= 0 && received - entry.reported < 64 * 1024 && (total <= 0 || received < total)) return;
        entry.reported = received;
        WVPost(0, WVEventDownloadProgress, 0, ident, 0, (double)received, total > 0 ? (double)total : -1, nil, nil, nil, nil, nil);
    });
}

// --- WKDownloadDelegate ---

- (void)download:(WKDownload *)download decideDestinationUsingResponse:(NSURLResponse *)response
    suggestedFilename:(NSString *)suggestedFilename completionHandler:(void (^)(NSURL *))completionHandler {
    WVDownloadEntry *entry = [self entryOf:download];
    if (!entry) {
        completionHandler(nil);
        return;
    }
    if (entry.destination) {
        // A resume, or a restart of the same download: the same file.
        [[NSFileManager defaultManager] removeItemAtURL:entry.destination error:nil];
        completionHandler(entry.destination);
        return;
    }
    jlong page = entry.page;
    _asking[@(entry.ident)] = completionHandler;
    double length = response.expectedContentLength >= 0 ? (double)response.expectedContentLength : -1;
    NSString *url = (download.originalRequest.URL ?: response.URL).absoluteString;
    WVPost(page, WVEventDownload, 0, entry.ident, 0, length, 0, url, response.MIMEType, suggestedFilename, nil, nil);
}

- (void)downloadDidFinish:(WKDownload *)download {
    WVDownloadEntry *entry = [self entryOf:download];
    if (!entry) return;
    [self unwatch:entry];
    NSNumber *size = nil;
    [entry.destination getResourceValue:&size forKey:NSURLFileSizeKey error:nil];
    [_entries removeObjectForKey:@(entry.ident)];
    WVPost(0, WVEventDownloadDone, 0, entry.ident, 0, size.doubleValue, 0, nil, nil, nil, nil, nil);
}

- (void)download:(WKDownload *)download didFailWithError:(NSError *)error resumeData:(NSData *)resumeData {
    WVDownloadEntry *entry = [self entryOf:download];
    if (!entry) return;
    [self unwatch:entry];
    double received = (double)download.progress.completedUnitCount;
    if (entry.cancelling) return; // cancelDownload has reported it.
    if (entry.pausing) {
        // cancel:'s own handler reports the pause, with its resume data.
        return;
    }
    entry.resumeData = resumeData;
    WVPost(0, WVEventDownloadStopped, 0, entry.ident, 2, received, 0, error.localizedDescription, nil, nil, nil, nil);
}

// --- The engine's calls ---

- (void)answer:(jlong)ident path:(NSString *)path {
    void (^handler)(NSURL *) = _asking[@(ident)];
    [_asking removeObjectForKey:@(ident)];
    WVDownloadEntry *entry = _entries[@(ident)];
    if (!handler) return;
    if (!entry || path.length == 0) {
        [_entries removeObjectForKey:@(ident)];
        handler(nil);
        return;
    }
    NSURL *destination = [NSURL fileURLWithPath:path];
    [[NSFileManager defaultManager] createDirectoryAtURL:destination.URLByDeletingLastPathComponent
                             withIntermediateDirectories:YES attributes:nil error:nil];
    // WebKit fails a download whose file exists: the app has said to replace it.
    [[NSFileManager defaultManager] removeItemAtURL:destination error:nil];
    entry.destination = destination;
    handler(destination);
    [self watch:entry];
}

- (void)pause:(jlong)ident {
    WVDownloadEntry *entry = _entries[@(ident)];
    if (!entry || !entry.download || entry.pausing) return;
    entry.pausing = YES;
    [self unwatch:entry];
    double received = (double)entry.download.progress.completedUnitCount;
    [entry.download cancel:^(NSData *resumeData) {
        WVOnMain(NO, ^{
            entry.resumeData = resumeData;
            entry.download = nil;
            WVPost(0, WVEventDownloadStopped, 0, ident, 1, received, 0, nil, nil, nil, nil, nil);
        });
    }];
}

/** A web view of the download's session to resume from: any page's will do, so one is made and kept while it runs. */
- (WKWebView *)webViewFor:(WVDownloadEntry *)entry {
    if (!entry.standIn) {
        WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
        if (entry.store) configuration.websiteDataStore = entry.store;
        entry.standIn = [[WKWebView alloc] initWithFrame:NSZeroRect configuration:configuration];
    }
    return entry.standIn;
}

- (void)resume:(jlong)ident {
    WVDownloadEntry *entry = _entries[@(ident)];
    if (!entry || entry.download || !entry.destination) return;
    entry.pausing = NO;
    entry.reported = -1;
    WKWebView *webView = [self webViewFor:entry];
    void (^adopt)(WKDownload *) = ^(WKDownload *download) {
        WVOnMain(NO, ^{
            if (!download) {
                WVPost(0, WVEventDownloadStopped, 0, ident, 2, 0, 0, @"The download could not be resumed", nil, nil, nil, nil);
                return;
            }
            entry.download = download;
            download.delegate = self;
            [self watch:entry];
        });
    };
    NSData *resumeData = entry.resumeData;
    entry.resumeData = nil;
    if (resumeData) {
        [webView resumeDownloadFromResumeData:resumeData completionHandler:adopt];
    } else if (entry.request) {
        // Nothing to resume from (the server sends no ranges): fetched again from the start, to the same file.
        [webView startDownloadUsingRequest:entry.request completionHandler:adopt];
    }
}

- (void)cancel:(jlong)ident {
    WVDownloadEntry *entry = _entries[@(ident)];
    if (!entry) return;
    entry.cancelling = YES;
    [self unwatch:entry];
    double received = (double)entry.download.progress.completedUnitCount;
    void (^handler)(NSURL *) = _asking[@(ident)];
    [_asking removeObjectForKey:@(ident)];
    if (handler) handler(nil);
    [entry.download cancel:nil];
    if (entry.destination) [[NSFileManager defaultManager] removeItemAtURL:entry.destination error:nil];
    [_entries removeObjectForKey:@(ident)];
    WVPost(0, WVEventDownloadStopped, 0, ident, 4, received, 0, nil, nil, nil, nil, nil);
}

@end

void WVAdoptDownload(WKDownload *download, jlong page) {
    [[WVDownloads shared] adopt:download page:page];
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_answerDownload(JNIEnv *env, jclass type, jlong download, jstring path) {
    NSString *where = WVString(env, path);
    WVOnMain(NO, ^{
        [[WVDownloads shared] answer:download path:where];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_pauseDownload(JNIEnv *env, jclass type, jlong download) {
    WVOnMain(NO, ^{
        [[WVDownloads shared] pause:download];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_resumeDownload(JNIEnv *env, jclass type, jlong download) {
    WVOnMain(NO, ^{
        [[WVDownloads shared] resume:download];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_cancelDownload(JNIEnv *env, jclass type, jlong download) {
    WVOnMain(NO, ^{
        [[WVDownloads shared] cancel:download];
    });
}
