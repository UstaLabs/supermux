#import "WVGlue.h"
#import <QuartzCore/CAMetalLayer.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <os/lock.h>
#import <stdatomic.h>

/*
 * Frame sync: a window's page views move in the Core Animation transaction that presents the Compose frame whose layout
 * placed them (MacFrameSync.kt holds the Kotlin side, its capability check and the pins).
 *
 * How skiko 0.150.2 shows a Compose frame on macOS (SkiaLayer.awt.kt, MetalRedrawer.kt, MetalContextHandler.mm,
 * MetalRedrawer.mm): on the EDT, Compose lays out (our placements come from that layout) and records the frame; then a
 * thread of skiko's own takes a drawable, [AWTMetalLayer nextDrawable] (a CAMetalLayer subclass with no nextDrawable of
 * its own), draws the recording into it, and commits a "Present" command buffer whose scheduled handler calls
 * [drawable present]. The layer presents asynchronously, when the GPU is done, apart from any transaction, while AppKit
 * moves our views at its next commit: with WebGL pages keeping the GPU busy the hole showed two to four frames after the
 * view had moved, the page ran ahead of its card and snapped back when the list stopped.
 *
 * So, for a window in sync:
 * - A placement waits (pending) until Compose has recorded the frame of its layout: MacFrameSync wraps the window's
 *   SkiaLayer's render delegate, and once Compose has recorded a frame, the placements so far are that frame's
 *   (recorded). A scroll event moves a list between frames (LazyListState lays itself out as it scrolls), while skiko's
 *   thread may still wait for the GPU to take the drawable of the frame before: those placements wait for the next
 *   recording, the first to draw the list there.
 * - AWTMetalLayer gets a nextDrawable of its own (class_addMethod, calling CAMetalLayer's as super): for a layer in sync
 *   the drawable comes wrapped (WVSyncedDrawable), with the placements recorded since the last drawable took them: those
 *   of the recording skiko draws into it, the newest. Every drawable of such a layer is wrapped from then on.
 * - The wrapper's present, from skiko's scheduled handler (the command buffer is scheduled, as presenting with a
 *   transaction asks), goes to AppKit's thread: there the placements are applied and the drawable presented in one
 *   transaction, the layer presenting with transactions (presentsWithTransaction), so the hole and the views show in the
 *   same frame of the display: recorded at 60 Hz while the chat scrolled, every page in its card to the point
 *   (2026-11-01). (A copy of the window, CGWindowListCreateImage, composites what the window server has been sent:
 *   between such a commit and the next refresh it pairs the views' new places with the drawable before, the frame the
 *   display still shows, so a copy can see a page a presented frame ahead of its card. The display doesn't.)
 * - That transaction is committed as the frame reaches AppKit's thread, at once, as the outermost (what AppKit's thread
 *   has pending is flushed first), but never within SPACING of a refresh of the commit before: a frame presented with a
 *   transaction shows when that commits, and nothing queues it as CAMetalLayer queues what it presents by itself, so two
 *   committed before one refresh show only the second, the refresh before showing nothing new. Nested in AppKit's own
 *   implicit transaction, each frame went at AppKit's next flush (on macOS 26 a step of its update cycle), where frames
 *   met: while the chat was read by trackpad (2026-11-05), 8.8 % of them never showed and 11.9 % of refreshes showed
 *   nothing new (0.6 % and 0.7 % committed at once), and AppKit's thread spent 12 % of its time in those flushes,
 *   waiting on the render server (2.8 %). Where in the refresh a frame commits matters to the copies above: committed
 *   at a display link's refresh, their start, copies caught nearly every moving page ahead of its card; and to the
 *   frame rate: a frame waiting for the display to show the one before (its presented handler) halved it.
 * - presentsWithTransaction is set only once every drawable in flight is wrapped (ARM_FRAMES presented directly first):
 *   a drawable of skiko's own presented on a Metal thread with it would wait on that thread's transaction. A wrapped
 *   drawable presented off AppKit's thread is presented in a transaction of its own, so either value is safe for it.
 * - What can't wait: a frame AppKit's thread hasn't presented after WATCHDOG is presented by itself (skiko waits for a
 *   drawable, and gives up the layer after a second without one), its placements applied when AppKit gets to them; a
 *   drawable skiko drops unpresented gives its placements to the next frame; placements no frame takes within STALE are
 *   applied by themselves (skiko not drawing). A later placement of a page always wins over an earlier one (turns).
 */

/** Wrapped drawables presented directly before the layer presents with transactions: the most skiko has in flight. */
static const int ARM_FRAMES = 3;

/** A frame AppKit's thread hasn't presented in this long is presented by itself. */
static const int64_t WATCHDOG_NANOS = 250 * NSEC_PER_MSEC;

/** Placements no frame took in this long are applied by themselves. */
static const int64_t STALE_NANOS = 250 * NSEC_PER_MSEC;

/** Of a refresh: a frame that reaches AppKit's thread sooner than this after the commit before waits until then. */
static const double SPACING = 0.75;

static atomic_llong gPlacementTurn;

long long WVNextPlacement(void) {
    return atomic_fetch_add(&gPlacementTurn, 1) + 1;
}

/** A frame skiko drew in sync: its drawable, unpresented until nil, and the placements of its layout. */
@interface WVFrame : NSObject
@property(nonatomic, strong) id<CAMetalDrawable> drawable;
@property(nonatomic, copy) NSArray<dispatch_block_t> *placements;
@end

@implementation WVFrame
@end

@interface WVFrameSync : NSObject
@property(nonatomic, readonly, weak) CAMetalLayer *layer;
@property(nonatomic, readonly, weak) NSWindow *window;
- (instancetype)initWithLayer:(CAMetalLayer *)layer window:(NSWindow *)window;
- (BOOL)enqueue:(dispatch_block_t)placement;
- (void)recorded;
- (id)wrap:(id<CAMetalDrawable>)drawable;
- (void)setWanted:(BOOL)wanted;
- (NSString *)describe;
@end

/** Stands in for skiko's drawable: everything goes to it, but present, which goes through the sync. */
@interface WVSyncedDrawable : NSObject
- (instancetype)initWithDrawable:(id<CAMetalDrawable>)drawable sync:(WVFrameSync *)sync
                      placements:(NSArray<dispatch_block_t> *)placements synced:(BOOL)synced;
@end

@interface WVFrameSync ()
- (void)present:(id<CAMetalDrawable>)drawable placements:(NSArray<dispatch_block_t> *)placements synced:(BOOL)synced;
- (void)dropped:(NSArray<dispatch_block_t> *)placements;
- (void)shown:(BOOL)shown;
@end

@implementation WVSyncedDrawable {
    id<CAMetalDrawable> _drawable;
    WVFrameSync *_sync;
    NSArray<dispatch_block_t> *_placements;
    BOOL _synced;
    atomic_bool _presented;
}

- (instancetype)initWithDrawable:(id<CAMetalDrawable>)drawable sync:(WVFrameSync *)sync
                      placements:(NSArray<dispatch_block_t> *)placements synced:(BOOL)synced {
    self = [super init];
    if (!self) return nil;
    _drawable = drawable;
    _sync = sync;
    _placements = placements;
    _synced = synced;
    if (synced) {
        // Once the display has shown the frame, or let it pass (a presentedTime of 0: a later one replaced it first).
        __weak WVFrameSync *weakSync = sync;
        [drawable addPresentedHandler:^(id<MTLDrawable> presented) {
            [weakSync shown:presented.presentedTime > 0];
        }];
    }
    return self;
}

- (id<MTLTexture>)texture {
    return _drawable.texture;
}

- (CAMetalLayer *)layer {
    return _drawable.layer;
}

- (void)present {
    if (atomic_exchange(&_presented, true)) return;
    NSArray<dispatch_block_t> *placements = _placements;
    _placements = nil;
    [_sync present:_drawable placements:placements synced:_synced];
}

- (id)forwardingTargetForSelector:(SEL)selector {
    return _drawable;
}

- (BOOL)respondsToSelector:(SEL)selector {
    return [super respondsToSelector:selector] || [_drawable respondsToSelector:selector];
}

- (BOOL)conformsToProtocol:(Protocol *)protocol {
    return [super conformsToProtocol:protocol] || [_drawable conformsToProtocol:protocol];
}

- (void)dealloc {
    // skiko drops a drawable unpresented when the layer was resized meanwhile: its placements go with the next frame.
    if (!atomic_load(&_presented) && _placements.count > 0) [_sync dropped:_placements];
}

@end

@implementation WVFrameSync {
    os_unfair_lock _lock;
    NSMutableArray<dispatch_block_t> *_pending;  // placements since Compose last recorded a frame
    NSMutableArray<dispatch_block_t> *_recorded; // placements of the frames recorded since a drawable last took them
    uint64_t _generation;                        // how many times placements were taken
    NSMutableArray<WVFrame *> *_unpresented;     // synced frames on their way to AppKit's thread, oldest first
    BOOL _wanted;
    int _armed;
    BOOL _active;
    dispatch_queue_t _watchdog;
    // What it did, for the checks; close: frames presented here within 6 ms of the one before (within a refresh); shown
    // and unshown: synced frames the display showed, and those it never did.
    uint64_t _synced, _close, _direct, _late, _dropped, _stale, _shown, _unshown;
    CFTimeInterval _lastPresent;
    // When AppKit's thread last committed a frame, and whether a frame waits for SPACING after it.
    CFTimeInterval _lastCommit;
    BOOL _spacing;
}

- (instancetype)initWithLayer:(CAMetalLayer *)layer window:(NSWindow *)window {
    self = [super init];
    if (!self) return nil;
    _layer = layer;
    _window = window;
    _lock = OS_UNFAIR_LOCK_INIT;
    _pending = [NSMutableArray array];
    _recorded = [NSMutableArray array];
    _unpresented = [NSMutableArray array];
    _watchdog = dispatch_queue_create("dev.supermux.webview.frame-sync", DISPATCH_QUEUE_SERIAL);
    return self;
}

/** Under the lock: every placement not yet taken by a frame, oldest first, taken. */
- (NSArray<dispatch_block_t> *)takeAllLocked {
    NSMutableArray<dispatch_block_t> *all = _recorded;
    [all addObjectsFromArray:_pending];
    _recorded = [NSMutableArray array];
    _pending = [NSMutableArray array];
    _generation++;
    return all;
}

/** AppKit's thread. */
- (void)setWanted:(BOOL)wanted {
    NSArray<dispatch_block_t> *flush = nil;
    os_unfair_lock_lock(&_lock);
    if (wanted == _wanted) {
        os_unfair_lock_unlock(&_lock);
        return;
    }
    _wanted = wanted;
    if (!wanted) {
        _active = NO;
        _armed = 0;
        flush = [self takeAllLocked];
    }
    os_unfair_lock_unlock(&_lock);
    if (!wanted) {
        // The frames drawn in sync that wait go now, their placements with them.
        while ([self presentWaiting:YES]) {
        }
    }
    for (dispatch_block_t placement in flush) placement();
    if (!wanted) self.layer.presentsWithTransaction = NO;
}

/**
 * Any thread (the EDT, in Compose's layout): YES if placement goes with the frame Compose records next, or is recording;
 * NO if the caller applies it itself.
 */
- (BOOL)enqueue:(dispatch_block_t)placement {
    os_unfair_lock_lock(&_lock);
    BOOL taken = _active;
    if (taken) {
        BOOL first = _pending.count == 0 && _recorded.count == 0;
        [_pending addObject:[placement copy]];
        if (first) [self watchStaleLocked];
    }
    os_unfair_lock_unlock(&_lock);
    return taken;
}

/**
 * The EDT, once Compose has recorded a frame (MacFrameSync's render delegate): the placements so far are that frame's,
 * its drawable takes them. A placement made after it, as a scroll event moves a list between frames, goes with the next
 * frame recorded, the first to draw it.
 */
- (void)recorded {
    os_unfair_lock_lock(&_lock);
    if (_pending.count > 0) {
        [_recorded addObjectsFromArray:_pending];
        [_pending removeAllObjects];
    }
    os_unfair_lock_unlock(&_lock);
}

/** Under the lock, as the placements waiting go from none to some: they are applied by themselves if no frame takes them. */
- (void)watchStaleLocked {
    uint64_t generation = _generation;
    __weak WVFrameSync *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, STALE_NANOS), _watchdog, ^{
        WVOnMain(NO, ^{
            [weakSelf flushStale:generation];
        });
    });
}

/** AppKit's thread. */
- (void)flushStale:(uint64_t)generation {
    NSArray<dispatch_block_t> *flush = nil;
    os_unfair_lock_lock(&_lock);
    if (_generation == generation && (_pending.count > 0 || _recorded.count > 0)) {
        flush = [self takeAllLocked];
        _stale++;
    }
    os_unfair_lock_unlock(&_lock);
    for (dispatch_block_t placement in flush) placement();
}

/**
 * skiko's thread, in nextDrawable: the drawable, wrapped, with the placements of the frames recorded since the last one
 * took them, while in sync. skiko draws the newest recording into it (SkiaLayer.draw).
 */
- (id)wrap:(id<CAMetalDrawable>)drawable {
    NSArray<dispatch_block_t> *placements = nil;
    os_unfair_lock_lock(&_lock);
    BOOL synced = _active;
    if (synced) {
        placements = _recorded;
        _recorded = [NSMutableArray array];
        _generation++;
        // What waits now is newer than this frame: it is watched again.
        if (_pending.count > 0) [self watchStaleLocked];
    }
    os_unfair_lock_unlock(&_lock);
    return [[WVSyncedDrawable alloc] initWithDrawable:drawable sync:self placements:placements synced:synced];
}

/** skiko's scheduled handler (a Metal thread), or wherever skiko presents. */
- (void)present:(id<CAMetalDrawable>)drawable placements:(NSArray<dispatch_block_t> *)placements synced:(BOOL)synced {
    if (!synced) {
        // In a transaction of its own: right whether or not the layer presents with transactions.
        [CATransaction begin];
        [drawable present];
        [CATransaction commit];
        os_unfair_lock_lock(&_lock);
        _direct++;
        if (_wanted && !_active && ++_armed >= ARM_FRAMES) _active = YES;
        os_unfair_lock_unlock(&_lock);
        if (placements.count > 0) {
            WVOnMain(NO, ^{
                for (dispatch_block_t placement in placements) placement();
            });
        }
        return;
    }
    WVFrame *frame = [[WVFrame alloc] init];
    frame.drawable = drawable;
    frame.placements = placements;
    os_unfair_lock_lock(&_lock);
    [_unpresented addObject:frame];
    os_unfair_lock_unlock(&_lock);
    WVOnMain(NO, ^{
        [self presentWaiting:NO];
    });
    __weak WVFrameSync *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, WATCHDOG_NANOS), _watchdog, ^{
        [weakSelf presentLate:frame];
    });
}

/**
 * Takes the frames waiting up to and including frame (none if it was presented already), oldest first: under the lock,
 * so each is presented once, and in order.
 */
- (NSArray<WVFrame *> *)takeThroughLocked:(WVFrame *)frame {
    if (![_unpresented containsObject:frame]) return @[];
    NSMutableArray<WVFrame *> *taken = [NSMutableArray array];
    while (_unpresented.count > 0) {
        WVFrame *first = _unpresented.firstObject;
        [_unpresented removeObjectAtIndex:0];
        [taken addObject:first];
        if (first == frame) break;
    }
    return taken;
}

/**
 * AppKit's thread: the oldest frame waiting, its placements and its drawable in one transaction committed at once, the
 * outermost (what AppKit's thread has pending is flushed first); unless, but with force, the commit before was less than
 * SPACING of a refresh ago: then at that much after it. NO if none was presented.
 */
- (BOOL)presentWaiting:(BOOL)force {
    CFTimeInterval now = CACurrentMediaTime();
    NSScreen *screen = self.window.screen;
    double refresh = 1.0 / (screen.maximumFramesPerSecond > 0 ? (double)screen.maximumFramesPerSecond : 60.0);
    CFTimeInterval at = _lastCommit + SPACING * refresh;
    os_unfair_lock_lock(&_lock);
    WVFrame *frame = force || now >= at ? _unpresented.firstObject : nil;
    BOOL later = !frame && !_spacing && _unpresented.count > 0;
    if (later) _spacing = YES;
    if (frame) {
        [_unpresented removeObjectAtIndex:0];
        _synced++;
        if (now - _lastPresent < 0.006) _close++;
        _lastPresent = now;
    }
    BOOL active = _active;
    os_unfair_lock_unlock(&_lock);
    if (later) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)((at - now) * NSEC_PER_SEC)),
                       dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
            WVOnMain(NO, ^{
                self->_spacing = NO;
                [self presentWaiting:NO];
            });
        });
    }
    if (!frame) return NO;
    _lastCommit = now;
    CAMetalLayer *layer = self.layer;
    if (active && layer && !layer.presentsWithTransaction) layer.presentsWithTransaction = YES;
    [CATransaction flush];
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    for (dispatch_block_t placement in frame.placements) placement();
    [frame.drawable present];
    frame.drawable = nil;
    [CATransaction commit];
    frame.placements = nil;
    // A frame still waiting goes SPACING after this one (it came before: nothing else calls for it).
    if (!force) [self presentWaiting:NO];
    return YES;
}

/** Any thread (once the display showed a synced frame, or a later one replaced it): for the checks. */
- (void)shown:(BOOL)shown {
    os_unfair_lock_lock(&_lock);
    if (shown) {
        _shown++;
    } else {
        _unshown++;
    }
    os_unfair_lock_unlock(&_lock);
}

/**
 * The watchdog's queue: AppKit's thread hasn't presented frame yet; it is presented now, and those before it, and their
 * placements go to AppKit's thread.
 */
- (void)presentLate:(WVFrame *)frame {
    os_unfair_lock_lock(&_lock);
    NSArray<WVFrame *> *taken = [self takeThroughLocked:frame];
    _late += taken.count;
    os_unfair_lock_unlock(&_lock);
    if (taken.count == 0) return;
    NSMutableArray<dispatch_block_t> *placements = [NSMutableArray array];
    [CATransaction begin];
    for (WVFrame *each in taken) {
        [each.drawable present];
        each.drawable = nil;
        [placements addObjectsFromArray:each.placements ?: @[]];
        each.placements = nil;
    }
    [CATransaction commit];
    if (placements.count > 0) {
        WVOnMain(NO, ^{
            [CATransaction begin];
            [CATransaction setDisableActions:YES];
            for (dispatch_block_t placement in placements) placement();
            [CATransaction commit];
        });
    }
}

/** Any thread: a drawable skiko dropped unpresented gives its placements to the next frame. */
- (void)dropped:(NSArray<dispatch_block_t> *)placements {
    os_unfair_lock_lock(&_lock);
    _dropped++;
    BOOL keep = _active;
    if (keep) {
        BOOL first = _pending.count == 0 && _recorded.count == 0;
        [_recorded insertObjects:placements atIndexes:[NSIndexSet indexSetWithIndexesInRange:NSMakeRange(0, placements.count)]];
        if (first) [self watchStaleLocked];
    }
    os_unfair_lock_unlock(&_lock);
    if (!keep) {
        WVOnMain(NO, ^{
            for (dispatch_block_t placement in placements) placement();
        });
    }
}

/** AppKit's thread: what it is, and what it did. */
- (NSString *)describe {
    os_unfair_lock_lock(&_lock);
    NSString *state = [NSString stringWithFormat:@"%@ armed=%d synced=%llu close=%llu direct=%llu late=%llu dropped=%llu stale=%llu pending=%lu shown=%llu unshown=%llu",
                                                 _active ? @"on" : (_wanted ? @"arming" : @"off"), _armed, _synced, _close, _direct,
                                                 _late, _dropped, _stale, (unsigned long)(_pending.count + _recorded.count), _shown, _unshown];
    os_unfair_lock_unlock(&_lock);
    return [state stringByAppendingFormat:@" transaction=%d", self.layer.presentsWithTransaction ? 1 : 0];
}

@end

// --- skiko's layer class, hooked once ---

static const void *WVSyncKey = &WVSyncKey;

/** AWTMetalLayer once its nextDrawable is ours; Nil before, or if it couldn't be. AppKit's thread. */
static Class gHooked;

/** AWTMetalLayer's nextDrawable: CAMetalLayer's (its superclass, WVHookSkiko checked), wrapped for a layer in sync. */
static id WVHookedNextDrawable(id self, SEL _cmd) {
    struct objc_super up = {self, [CAMetalLayer class]};
    id drawable = ((id(*)(struct objc_super *, SEL))objc_msgSendSuper)(&up, _cmd);
    if (!drawable) return nil;
    WVFrameSync *sync = objc_getAssociatedObject(self, WVSyncKey);
    return sync ? [sync wrap:drawable] : drawable;
}

/** Whether cls has a method for selector of its own, not inherited. */
static BOOL WVHasOwnMethod(Class cls, SEL selector) {
    unsigned int count = 0;
    Method *methods = class_copyMethodList(cls, &count);
    BOOL found = NO;
    for (unsigned int i = 0; i < count; i++) {
        if (method_getName(methods[i]) == selector) found = YES;
    }
    free(methods);
    return found;
}

/** What frame sync needs of skiko, checked on AppKit's thread (the pins, MacFrameSync.kt): 1 when it holds. */
typedef NS_ENUM(int, WVSyncStatus) {
    WVSyncOn = 1,
    WVSyncNoClass = -1,
    WVSyncNotMetal = -2,
    WVSyncOverridden = -3,
    WVSyncNoLayer = -4,
    WVSyncLayers = -5,
    WVSyncNotHooked = -6,
};

/** AWTMetalLayer, a CAMetalLayer subclass with no nextDrawable of its own, hooked; once. */
static WVSyncStatus WVHookSkiko(void) {
    if (gHooked) return WVSyncOn;
    Class cls = NSClassFromString(@"AWTMetalLayer");
    if (!cls) return WVSyncNoClass;
    if (class_getSuperclass(cls) != [CAMetalLayer class]) return WVSyncNotMetal;
    if (WVHasOwnMethod(cls, @selector(nextDrawable))) return WVSyncOverridden;
    Method inherited = class_getInstanceMethod([CAMetalLayer class], @selector(nextDrawable));
    if (!inherited) return WVSyncNotMetal;
    if (!class_addMethod(cls, @selector(nextDrawable), (IMP)WVHookedNextDrawable, method_getTypeEncoding(inherited))) {
        return WVSyncNotHooked;
    }
    gHooked = cls;
    return WVSyncOn;
}

/** skiko's layers in window: sublayers of AWT's content view's layer (JAWT's window layer), a level or two down. */
static NSArray<CAMetalLayer *> *WVSkikoLayers(NSWindow *window) {
    NSMutableArray<CAMetalLayer *> *found = [NSMutableArray array];
    CALayer *root = window.contentView.layer;
    if (!root || !gHooked) return found;
    for (CALayer *layer in root.sublayers) {
        if ([layer isKindOfClass:gHooked]) [found addObject:(CAMetalLayer *)layer];
        for (CALayer *inner in layer.sublayers) {
            if ([inner isKindOfClass:gHooked]) [found addObject:(CAMetalLayer *)inner];
        }
    }
    return found;
}

// --- The windows in sync, by their NSWindow ---

static os_unfair_lock gSyncsLock = OS_UNFAIR_LOCK_INIT;
static NSMutableDictionary<NSNumber *, WVFrameSync *> *gSyncs;

/** Any thread: window's sync, if it has one that is still its own. */
static WVFrameSync *WVSyncOf(jlong window) {
    if (window == 0) return nil;
    os_unfair_lock_lock(&gSyncsLock);
    WVFrameSync *sync = gSyncs[@(window)];
    if (sync && (__bridge void *)sync.window != (void *)(intptr_t)window) {
        // That window has gone, and another may have its address now.
        [gSyncs removeObjectForKey:@(window)];
        sync = nil;
    }
    os_unfair_lock_unlock(&gSyncsLock);
    return sync;
}

/** Placements that went with a frame, and those sent to AppKit's thread at once: for the checks. */
static atomic_llong gWithFrames, gAtOnce;

BOOL WVFrameSyncEnqueue(jlong window, dispatch_block_t placement) {
    WVFrameSync *sync = WVSyncOf(window);
    BOOL taken = sync != nil && [sync enqueue:placement];
    atomic_fetch_add(taken ? &gWithFrames : &gAtOnce, 1);
    return taken;
}

/** AppKit's thread: puts window in sync (or out of it); its status. */
static int WVSyncWindow(jlong handle, BOOL on) {
    NSWindow *window = WVWindow(handle);
    WVFrameSync *sync = WVSyncOf(handle);
    if (!on) {
        [sync setWanted:NO];
        return 0;
    }
    WVSyncStatus status = WVHookSkiko();
    if (status != WVSyncOn) return status;
    NSArray<CAMetalLayer *> *layers = WVSkikoLayers(window);
    if (layers.count == 0) return WVSyncNoLayer;
    if (layers.count > 1) return WVSyncLayers;
    CAMetalLayer *layer = layers.firstObject;
    if (sync.layer != layer) {
        // A new window, or skiko made its layer again (another renderer): the old one's frames go on as they were.
        [sync setWanted:NO];
        sync = objc_getAssociatedObject(layer, WVSyncKey) ?: [[WVFrameSync alloc] initWithLayer:layer window:window];
        objc_setAssociatedObject(layer, WVSyncKey, sync, OBJC_ASSOCIATION_RETAIN);
        os_unfair_lock_lock(&gSyncsLock);
        if (!gSyncs) gSyncs = [NSMutableDictionary dictionary];
        gSyncs[@(handle)] = sync;
        os_unfair_lock_unlock(&gSyncsLock);
    }
    [sync setWanted:YES];
    return WVSyncOn;
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_syncFrames(JNIEnv *env, jclass type, jlong window, jboolean on) {
    BOOL wanted = on == JNI_TRUE;
    WVOnMain(NO, ^{
        int status = WVSyncWindow(window, wanted);
        static BOOL told;
        if (status < 0 && !told) {
            told = YES;
            NSLog(@"webview: page views move at their layout, not with skiko's frames (frame sync status %d)", status);
        }
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_frameRecorded(JNIEnv *env, jclass type, jlong window) {
    [WVSyncOf(window) recorded];
}

JNIEXPORT jstring JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_testFrameSync(JNIEnv *env, jclass type, jlong window) {
    __block NSString *state = nil;
    WVOnMain(YES, ^{
        WVFrameSync *sync = WVSyncOf(window);
        NSString *placements = [NSString stringWithFormat:@" placements=%lld+%lld", atomic_load(&gWithFrames), atomic_load(&gAtOnce)];
        if (sync) {
            state = [[sync describe] stringByAppendingString:placements];
        } else {
            NSArray<CAMetalLayer *> *layers = WVSkikoLayers(WVWindow(window));
            state = [[NSString stringWithFormat:@"none hooked=%d layers=%lu", gHooked ? 1 : 0, (unsigned long)layers.count]
                stringByAppendingString:placements];
        }
    });
    return WVJavaString(env, state);
}
