/* The glue's own logic, without WebKit's processes or a JVM: the frame store's buffer handoff and damage, the timed
 * wait of the operations the JVM calls, and the event stamps. It includes the glue's sources to reach their static
 * functions, and stands in for the WebKit thread (wv_host_post keeps what is posted) and the JVM (wv_emit keeps the
 * events). Built and run by native/linux/test.sh, against a WPE install; exit 0 when every test passed. */
#include "../wv_platform.c"
#include "../wv_webview.c"

#include <stdio.h>

/* ---- stand-ins for wv_host.c and wv_client.c ---- */

typedef struct { GSourceFunc fn; gpointer data; GDestroyNotify destroy; } Posted;
static GArray *posted;     /* Posted, in order */
typedef struct { int64_t view; int kind; int64_t a, b; } Emitted;
static GArray *emitted;    /* Emitted, in order */

void wv_host_post(GSourceFunc fn, gpointer data, GDestroyNotify destroy)
{
    Posted p = { fn, data, destroy };
    g_array_append_val(posted, p);
}
gboolean wv_host_is_thread(void) { return FALSE; }
WPEDisplay *wv_host_display(void) { return NULL; }
WebKitWebContext *wv_host_context(void) { return NULL; }
const char *wv_host_data_dir(void) { return "/tmp"; }
const char *wv_host_cache_dir(void) { return "/tmp"; }
void wv_emit(int64_t view, int kind, int64_t a, int64_t b, const char *s1, const char *s2)
{
    Emitted e = { view, kind, a, b };
    g_array_append_val(emitted, e);
}
void wv_client_connect(WebKitWebView *web, int64_t view) { }
void wv_client_disconnect(WebKitWebView *web, int64_t view) { }
void wv_client_answer(int64_t request, gboolean allow, const char *text, char **files) { }

/* ---- the tests ---- */

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("  FAIL %s:%d: ", __func__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static WPEDisplay *display;

/* A w x h buffer, every pixel `fill`, with one pixel at (x, y) `mark`. */
static WPEBuffer *buffer(int w, int h, uint32_t fill, int x, int y, uint32_t mark)
{
    uint32_t *pixels = g_new(uint32_t, (gsize)w * h);
    for (int i = 0; i < w * h; i++)
        pixels[i] = fill;
    if (x >= 0)
        pixels[y * w + x] = mark;
    GBytes *bytes = g_bytes_new_take(pixels, (gsize)w * h * 4);
    WPEBuffer *b = WPE_BUFFER(wpe_buffer_shm_new(display, w, h, WPE_PIXEL_FORMAT_ARGB8888, bytes, (guint)w * 4));
    g_bytes_unref(bytes);
    return b;
}

/* The acknowledgements posted since `from`: the buffers rendered and released, in order. */
static WvAck *ack(guint index) { return g_array_index(posted, Posted, index).data; }

static void reset(void)
{
    for (guint i = 0; i < posted->len; i++) {
        Posted *p = &g_array_index(posted, Posted, i);
        if (p->destroy)
            p->destroy(p->data);
    }
    g_array_set_size(posted, 0);
    g_array_set_size(emitted, 0);
}

static int frame_events(void)
{
    int n = 0;
    for (guint i = 0; i < emitted->len; i++)
        n += g_array_index(emitted, Emitted, i).kind == WV_EV_FRAME;
    return n;
}

static void test_each_buffer_goes_back_one_pull_later(void)
{
    /* The store copies a buffer only while WebKit can't write it: it is released at the next pull, never sooner. */
    GObject *view = g_object_new(G_TYPE_OBJECT, NULL);
    WvFrames *f = frames_new((WPEView *)view);
    WPEBuffer *a = buffer(4, 2, 0xff0000ff, -1, 0, 0), *b = buffer(4, 2, 0xff0000ff, 1, 0, 0xffff0000);
    GError *error = NULL;
    uint32_t dst[8] = { 0 };
    int64_t out[WV_PULL_OUT];
    CHECK(frames_render(f, (WPEView *)view, a, NULL, 0, &error), "render a");
    CHECK(wv_frames_pull(f, (uint8_t *)dst, 16, 4, 2, TRUE, out) == 1, "pull a");
    CHECK(dst[5] == 0xff0000ff, "a's pixels: %08x", dst[5]);
    CHECK(posted->len == 1 && ack(0)->rendered == a && ack(0)->released == NULL, "a rendered, nothing released");
    WPERectangle damage = { 1, 0, 1, 1 };
    CHECK(frames_render(f, (WPEView *)view, b, &damage, 1, &error), "render b");
    dst[5] = 0x12345678; /* outside b's damage: a pull of what changed leaves it */
    CHECK(wv_frames_pull(f, (uint8_t *)dst, 16, 4, 2, FALSE, out) == 1, "pull b");
    CHECK(dst[1] == 0xffff0000 && dst[5] == 0x12345678, "only b's damage copied: %08x %08x", dst[1], dst[5]);
    CHECK(posted->len == 2 && ack(1)->rendered == b && ack(1)->released == a, "b rendered, a released one pull later");
    CHECK(out[0] == 2 && out[7] == 1, "seq %ld, %ld rect", (long)out[0], (long)out[7]);
    reset();
    frames_detach(f);
    wv_frames_unref(f);
    g_object_unref(a);
    g_object_unref(b);
    g_object_unref(view);
}

static void test_a_frame_is_told_once_until_it_is_pulled(void)
{
    GObject *view = g_object_new(G_TYPE_OBJECT, NULL);
    WvFrames *f = frames_new((WPEView *)view);
    WPEBuffer *a = buffer(4, 2, 1, -1, 0, 0), *b = buffer(4, 2, 2, -1, 0, 0), *c = buffer(4, 2, 3, -1, 0, 0);
    GError *error = NULL;
    uint32_t dst[8];
    int64_t out[WV_PULL_OUT];
    frames_render(f, (WPEView *)view, a, NULL, 0, &error);
    frames_render(f, (WPEView *)view, b, NULL, 0, &error);
    CHECK(frame_events() == 1, "%d FRAME events before a pull", frame_events());
    CHECK(wv_frames_pull(f, (uint8_t *)dst, 16, 4, 2, FALSE, out) == 1 && dst[0] == 2, "the newest pulled");
    frames_render(f, (WPEView *)view, c, NULL, 0, &error);
    CHECK(frame_events() == 2, "told again after the pull");
    CHECK(wv_frames_pull(f, (uint8_t *)dst, 16, 4, 2, FALSE, out) == 1, "pulled");
    CHECK(wv_frames_pull(f, (uint8_t *)dst, 16, 4, 2, FALSE, out) == 0, "nothing new");
    reset();
    frames_detach(f);
    wv_frames_unref(f);
    g_object_unref(a);
    g_object_unref(b);
    g_object_unref(c);
    g_object_unref(view);
}

static void test_a_new_size_asks_for_new_memory(void)
{
    GObject *view = g_object_new(G_TYPE_OBJECT, NULL);
    WvFrames *f = frames_new((WPEView *)view);
    WPEBuffer *a = buffer(8, 2, 1, -1, 0, 0);
    GError *error = NULL;
    uint32_t dst[16];
    int64_t out[WV_PULL_OUT];
    frames_render(f, (WPEView *)view, a, NULL, 0, &error);
    CHECK(wv_frames_pull(f, (uint8_t *)dst, 16, 4, 2, FALSE, out) == -1, "memory of another size");
    CHECK(out[1] == 8 && out[2] == 2, "the size it needs: %ld x %ld", (long)out[1], (long)out[2]);
    CHECK(posted->len == 0, "nothing acknowledged: the buffer waits for a pull that copies it");
    CHECK(wv_frames_pull(f, (uint8_t *)dst, 32, 8, 2, TRUE, out) == 1, "pulled at its size");
    reset();
    frames_detach(f);
    wv_frames_unref(f);
    g_object_unref(a);
    g_object_unref(view);
}

static void test_damage_past_sixteen_rects_is_one_box(void)
{
    WvFrames *f = frames_new(NULL);
    f->n_damage = 0;
    WPERectangle rects[17];
    for (int i = 0; i < 17; i++)
        rects[i] = (WPERectangle){ i * 2, i, 1, 1 };
    add_damage(f, rects, 17, 100, 100);
    CHECK(f->n_damage == 1, "%d rects", f->n_damage);
    CHECK(f->damage[0].x == 0 && f->damage[0].y == 0 && f->damage[0].width == 33 && f->damage[0].height == 17,
        "the box %d,%d %dx%d", f->damage[0].x, f->damage[0].y, f->damage[0].width, f->damage[0].height);
    WPERectangle outside = { 90, 90, 20, 20 };
    f->n_damage = 0;
    add_damage(f, &outside, 1, 100, 100);
    CHECK(f->damage[0].width == 10 && f->damage[0].height == 10, "cut to the buffer");
    wv_frames_unref(f);
}

static WvSync *sync_new(void)
{
    WvSync *s = g_new0(WvSync, 1);
    s->refs = 2;
    g_mutex_init(&s->lock);
    g_cond_init(&s->cond);
    return s;
}

static void test_a_call_the_webkit_thread_never_answers_fails_in_time(void)
{
    /* restoreState and the other calls waited forever for a stuck WebKit thread, the app's UI thread with them. */
    WvSync *s = sync_new();
    gint64 start = g_get_monotonic_time();
    int64_t result = sync_wait(s, 50 * G_TIME_SPAN_MILLISECOND, NULL);
    gint64 waited = g_get_monotonic_time() - start;
    CHECK(result == -1, "result %ld", (long)result);
    CHECK(waited >= 50 * G_TIME_SPAN_MILLISECOND && waited < 2 * G_TIME_SPAN_SECOND, "waited %ld us", (long)waited);
    finish_sync(s, 5, g_bytes_new("late", 4)); /* the operation, later: no one waits, its reference frees it */
    sync_unref(s);
}

static void test_a_call_answered_returns_its_result(void)
{
    WvSync *s = sync_new();
    finish_sync(s, 7, g_bytes_new("ok", 2));
    sync_unref(s); /* the operation's */
    GBytes *bytes = NULL;
    CHECK(sync_wait(s, 50 * G_TIME_SPAN_MILLISECOND, &bytes) == 7, "the result");
    CHECK(bytes && g_bytes_get_size(bytes) == 2, "its bytes");
    if (bytes)
        g_bytes_unref(bytes);
}

static void test_event_stamps(void)
{
    gint64 now = g_get_monotonic_time() / 1000;
    guint32 stamped = event_time(0);
    CHECK((gint64)stamped >= now - 5 && (gint64)stamped <= now + 1000, "now: %u, %ld", stamped, (long)now);
    CHECK(event_time(1234) == 1234, "the JVM's own");
    int64_t a = wv_now_ns(), b = wv_now_ns();
    CHECK(b >= a, "monotonic");
}

/* Stands in for a named profile's session: a real one, even an ephemeral one, starts WebKit's network process. The
 * registry only refs, watches and compares what it is given. */
static WebKitNetworkSession *made_for_test(const char *dir)
{
    return (WebKitNetworkSession *)g_object_new(G_TYPE_OBJECT, NULL);
}

/* Named profiles (final review, item 6): every view of one gets its one network session, as Default's views do, so a
 * login in one view is every view's and one storage manager has its folder; another named profile has its own; and
 * once the last view of a profile lets its session go, the next view gets a session that works. */
static void test_a_named_profiles_views_share_one_session(void)
{
    const char *dir = "/profiles/a", *other = "/profiles/b";
    WebKitNetworkSession *a = named_session(dir, made_for_test);
    WebKitNetworkSession *b = named_session(dir, made_for_test);
    WebKitNetworkSession *c = named_session(other, made_for_test);
    CHECK(a == b, "two views of one named profile got two sessions, %p and %p", (void *)a, (void *)b);
    CHECK(a != c, "two named profiles share session %p", (void *)a);
    GWeakRef first;
    g_weak_ref_init(&first, a);
    g_object_unref(a);
    g_object_unref(b);
    g_object_unref(c);
    WebKitNetworkSession *kept = g_weak_ref_get(&first);
    WebKitNetworkSession *again = named_session(dir, made_for_test);
    CHECK(G_IS_OBJECT(again), "no working session once the profile's last view went");
    CHECK(kept == NULL || again == kept, "a second session %p for the profile while its first %p lives", (void *)again, (void *)kept);
    g_clear_object(&kept);
    g_clear_object(&again);
    g_weak_ref_clear(&first);
}

int main(void)
{
    posted = g_array_new(FALSE, FALSE, sizeof(Posted));
    emitted = g_array_new(FALSE, FALSE, sizeof(Emitted));
    GError *error = NULL;
    display = wv_display_new(&error);
    if (!display) {
        printf("no display: %s\n", error ? error->message : "?");
        return 2;
    }
    struct { const char *name; void (*run)(void); } tests[] = {
        { "each-buffer-goes-back-one-pull-later", test_each_buffer_goes_back_one_pull_later },
        { "a-frame-is-told-once-until-it-is-pulled", test_a_frame_is_told_once_until_it_is_pulled },
        { "a-new-size-asks-for-new-memory", test_a_new_size_asks_for_new_memory },
        { "damage-past-sixteen-rects-is-one-box", test_damage_past_sixteen_rects_is_one_box },
        { "a-call-the-webkit-thread-never-answers-fails-in-time", test_a_call_the_webkit_thread_never_answers_fails_in_time },
        { "a-call-answered-returns-its-result", test_a_call_answered_returns_its_result },
        { "event-stamps", test_event_stamps },
        { "a-named-profiles-views-share-one-session", test_a_named_profiles_views_share_one_session },
    };
    int passed = 0;
    for (gsize i = 0; i < G_N_ELEMENTS(tests); i++) {
        int before = failures;
        tests[i].run();
        printf("%s %s\n", failures == before ? "PASS" : "FAIL", tests[i].name);
        passed += failures == before;
    }
    printf("GLUE TESTS passed=%d failed=%d\n", passed, (int)G_N_ELEMENTS(tests) - passed);
    return failures ? 1 : 0;
}
