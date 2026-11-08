/* WPEPlatform objects of our own (spec §7 "Linux: frames"; F9 in the plan: the headless ones are final and drop the
 * damage). WvDisplay has no GPU, so WebKit renders into shared memory; WvView keeps each buffer WebKit renders, with
 * its damage, until the JVM pulls it into a Skia bitmap; WvIm is an input-method context the JVM drives. WebKit calls
 * all of it on the WebKit thread; only wv_frames_pull/_drain/_configure run on JVM threads, under the store's lock. */
#include "wv.h"
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------------------------------------ the frame store */

#define WV_MAX_DAMAGE 16
#define WV_RING 8192

typedef struct { int64_t seq, marker, arrival_ns, dirty_px, outside_px; } WvRecord;

struct _WvFrames {
    gint refs;
    GMutex lock;
    WPEView *view;          /* weak: cleared when the view is disposed */
    int64_t owner;          /* the JVM's id of the view */
    WPEBuffer *pending;     /* pull mode: rendered by WebKit, not pulled yet */
    WPEBuffer *shown;       /* the buffer last pulled (pull mode) or last stored (auto mode); released by the next */
    WPERectangle damage[WV_MAX_DAMAGE];
    int n_damage;           /* -1: the whole buffer */
    int width, height;      /* the latest buffer's size, in pixels */
    double marker_scale;    /* the view's scale: the page's marker pixel is at CSS (8, 8) */
    double damage_scale;    /* what damage coordinates are multiplied by: 1 when they are buffer pixels */
    int64_t seq, pulled_seq, last_marker;
    gboolean notified, auto_ack, verify;
    gboolean whole;         /* damage unusable: every frame is copied whole */
    uint8_t *store;         /* auto mode: a copy of the latest frame */
    int store_w, store_h;
    WvRecord ring[WV_RING];
    int ring_start, ring_count;
};

typedef struct {
    WPEView *view;
    WPEBuffer *rendered;    /* buffer_rendered for it, then dropped */
    WPEBuffer *released;    /* buffer_released for it, then dropped */
} WvAck;

static gboolean ack_run(gpointer data)
{
    WvAck *ack = data;
    if (ack->rendered)
        wpe_view_buffer_rendered(ack->view, ack->rendered);
    if (ack->released)
        wpe_view_buffer_released(ack->view, ack->released);
    return G_SOURCE_REMOVE;
}

static void ack_free(gpointer data)
{
    WvAck *ack = data;
    g_clear_object(&ack->rendered);
    g_clear_object(&ack->released);
    g_clear_object(&ack->view);
    g_free(ack);
}

/* Takes over both buffer references; posts the acknowledgement to the WebKit thread. */
static void ack_post(WPEView *view, WPEBuffer *rendered, WPEBuffer *released)
{
    if (!view) {
        g_clear_object(&rendered);
        g_clear_object(&released);
        return;
    }
    WvAck *ack = g_new0(WvAck, 1);
    ack->view = g_object_ref(view);
    ack->rendered = rendered;
    ack->released = released;
    wv_host_post(ack_run, ack, ack_free);
}

static WvFrames *frames_new(WPEView *view)
{
    WvFrames *f = g_new0(WvFrames, 1);
    f->refs = 1;
    g_mutex_init(&f->lock);
    f->view = view;
    f->marker_scale = 1.0;
    f->damage_scale = 1.0;
    f->last_marker = -1;
    return f;
}

WvFrames *wv_frames_ref(WvFrames *f)
{
    g_atomic_int_inc(&f->refs);
    return f;
}

void wv_frames_unref(WvFrames *f)
{
    if (!g_atomic_int_dec_and_test(&f->refs))
        return;
    g_clear_object(&f->pending);
    g_clear_object(&f->shown);
    g_free(f->store);
    g_mutex_clear(&f->lock);
    g_free(f);
}

int64_t wv_frames_seq(WvFrames *f)
{
    g_mutex_lock(&f->lock);
    int64_t seq = f->seq;
    g_mutex_unlock(&f->lock);
    return seq;
}

/* The view is going: nothing is acknowledged any more, and the buffers are let go. */
static void frames_detach(WvFrames *f)
{
    g_mutex_lock(&f->lock);
    f->view = NULL;
    g_clear_object(&f->pending);
    g_clear_object(&f->shown);
    g_mutex_unlock(&f->lock);
}

void wv_frames_configure(WvFrames *f, gboolean auto_ack, gboolean verify, double damage_scale)
{
    WPEView *view = NULL;
    WPEBuffer *rendered = NULL, *released = NULL;
    g_mutex_lock(&f->lock);
    if (auto_ack && !f->auto_ack && f->pending) {
        /* Pull to auto with a frame not pulled yet: acknowledge it now, or WebKit waits for it forever (F10). */
        view = f->view ? g_object_ref(f->view) : NULL;
        rendered = g_object_ref(f->pending);
        released = f->shown; /* its reference goes to the acknowledgement */
        f->shown = f->pending;
        f->pending = NULL;
    }
    f->auto_ack = auto_ack;
    f->verify = verify;
    f->whole = damage_scale <= 0;
    f->damage_scale = damage_scale > 0 ? damage_scale : 1.0;
    f->n_damage = -1; /* the next pull copies everything */
    /* The next frame tells the JVM again: a FRAME the last mode left unpulled would otherwise silence every later one,
     * and in pull mode WebKit then waits for an acknowledgement that never comes. */
    f->notified = FALSE;
    g_mutex_unlock(&f->lock);
    if (rendered || released)
        ack_post(view, rendered, released);
    if (view)
        g_object_unref(view);
}

static void frames_set_scale(WvFrames *f, double scale)
{
    g_mutex_lock(&f->lock);
    f->marker_scale = scale > 0 ? scale : 1.0;
    g_mutex_unlock(&f->lock);
}

static WPERectangle rect_scaled_clamped(WPERectangle r, double s, int w, int h)
{
    int x0 = (int)(r.x * s), y0 = (int)(r.y * s);
    int x1 = (int)((r.x + r.width) * s + 0.999), y1 = (int)((r.y + r.height) * s + 0.999);
    x0 = MAX(x0, 0);
    y0 = MAX(y0, 0);
    x1 = MIN(x1, w);
    y1 = MIN(y1, h);
    WPERectangle out = { x0, y0, MAX(x1 - x0, 0), MAX(y1 - y0, 0) };
    return out;
}

static WPERectangle rect_union(WPERectangle a, WPERectangle b)
{
    int x0 = MIN(a.x, b.x), y0 = MIN(a.y, b.y);
    int x1 = MAX(a.x + a.width, b.x + b.width), y1 = MAX(a.y + a.height, b.y + b.height);
    WPERectangle out = { x0, y0, x1 - x0, y1 - y0 };
    return out;
}

/* Adds a frame's damage to what the next pull copies; returns the frame's damaged area in pixels. */
static int64_t add_damage(WvFrames *f, const WPERectangle *rects, guint n, int w, int h)
{
    if (n == 0 || !rects || f->whole) {
        f->n_damage = -1;
        return (int64_t)w * h;
    }
    int64_t area = 0;
    for (guint i = 0; i < n; i++) {
        WPERectangle r = rect_scaled_clamped(rects[i], f->damage_scale, w, h);
        if (r.width == 0 || r.height == 0)
            continue;
        area += (int64_t)r.width * r.height;
        if (f->n_damage < 0)
            continue;
        if (f->n_damage < WV_MAX_DAMAGE) {
            f->damage[f->n_damage++] = r;
            continue;
        }
        /* Too many rectangles: one box around all of them. */
        WPERectangle box = r;
        for (int k = 0; k < f->n_damage; k++)
            box = rect_union(box, f->damage[k]);
        f->damage[0] = box;
        f->n_damage = 1;
    }
    return area;
}

static int64_t sample_marker(const uint8_t *data, gsize size, int stride, int w, int h, double scale)
{
    int x = (int)(8 * scale), y = (int)(8 * scale);
    if (x >= w || y >= h)
        return -1;
    gsize off = (gsize)y * stride + (gsize)x * 4;
    if (off + 4 > size)
        return -1;
    /* BGRA in memory (F11) → 0xRRGGBB. */
    return ((int64_t)data[off + 2] << 16) | ((int64_t)data[off + 1] << 8) | data[off];
}

static int64_t copy_rect(uint8_t *dst, int dst_stride, const uint8_t *src, int src_stride, WPERectangle r)
{
    for (int row = 0; row < r.height; row++)
        memcpy(dst + (gsize)(r.y + row) * dst_stride + (gsize)r.x * 4,
            src + (gsize)(r.y + row) * src_stride + (gsize)r.x * 4, (gsize)r.width * 4);
    return (int64_t)r.width * r.height;
}

static int64_t copy_damage(WvFrames *f, uint8_t *dst, int dst_stride, const uint8_t *src, int src_stride, gboolean full, int *rects)
{
    if (full || f->n_damage < 0) {
        WPERectangle all = { 0, 0, f->width, f->height };
        *rects = 1;
        if (dst_stride == src_stride) {
            memcpy(dst, src, (gsize)src_stride * f->height);
            return (int64_t)f->width * f->height;
        }
        return copy_rect(dst, dst_stride, src, src_stride, all);
    }
    int64_t px = 0;
    for (int i = 0; i < f->n_damage; i++)
        px += copy_rect(dst, dst_stride, src, src_stride, f->damage[i]);
    *rects = f->n_damage;
    return px;
}

/* Verify mode: pixels that changed since the stored frame outside every damage rectangle of this frame. */
static int64_t count_outside(WvFrames *f, const uint8_t *src, int src_stride, const WPERectangle *rects, guint n)
{
    if (n == 0 || !rects)
        return 0; /* the whole buffer is damaged */
    WPERectangle *rs = g_new(WPERectangle, n);
    for (guint i = 0; i < n; i++)
        rs[i] = rect_scaled_clamped(rects[i], f->damage_scale, f->width, f->height);
    int64_t outside = 0;
    for (int y = 0; y < f->height; y++) {
        const uint32_t *a = (const uint32_t *)(src + (gsize)y * src_stride);
        const uint32_t *b = (const uint32_t *)(f->store + (gsize)y * f->store_w * 4);
        for (int x = 0; x < f->width; x++) {
            if (a[x] == b[x])
                continue;
            gboolean inside = FALSE;
            for (guint i = 0; i < n && !inside; i++)
                inside = x >= rs[i].x && x < rs[i].x + rs[i].width && y >= rs[i].y && y < rs[i].y + rs[i].height;
            if (!inside)
                outside++;
        }
    }
    g_free(rs);
    return outside;
}

static void record(WvFrames *f, int64_t seq, int64_t marker, int64_t arrival, int64_t dirty, int64_t outside)
{
    if (f->ring_count == WV_RING) {
        f->ring_start = (f->ring_start + 1) % WV_RING;
        f->ring_count--;
    }
    WvRecord *r = &f->ring[(f->ring_start + f->ring_count) % WV_RING];
    r->seq = seq;
    r->marker = marker;
    r->arrival_ns = arrival;
    r->dirty_px = dirty;
    r->outside_px = outside;
    f->ring_count++;
}

int wv_frames_drain(WvFrames *f, int64_t *out, int max_records)
{
    g_mutex_lock(&f->lock);
    int n = MIN(max_records, f->ring_count);
    for (int i = 0; i < n; i++) {
        WvRecord *r = &f->ring[(f->ring_start + i) % WV_RING];
        int64_t *o = out + (gsize)i * WV_RECORD_LONGS;
        o[0] = r->seq;
        o[1] = r->marker;
        o[2] = r->arrival_ns;
        o[3] = r->dirty_px;
        o[4] = r->outside_px;
    }
    f->ring_start = (f->ring_start + n) % WV_RING;
    f->ring_count -= n;
    g_mutex_unlock(&f->lock);
    return n;
}

/* render_buffer (WebKit thread): keep the buffer (pull mode) or store and acknowledge it (auto mode). */
static gboolean frames_render(WvFrames *f, WPEView *view, WPEBuffer *buffer, const WPERectangle *rects, guint n_rects, GError **error)
{
    if (!WPE_IS_BUFFER_SHM(buffer)) {
        g_set_error_literal(error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "webview-wpe renders shared-memory buffers only");
        return FALSE;
    }
    WPEBufferSHM *shm = WPE_BUFFER_SHM(buffer);
    gsize size = 0;
    const uint8_t *data = g_bytes_get_data(wpe_buffer_shm_get_data(shm), &size);
    int stride = (int)wpe_buffer_shm_get_stride(shm);
    int w = wpe_buffer_get_width(buffer), h = wpe_buffer_get_height(buffer);
    int64_t arrival = wv_now_ns();
    WPEBuffer *ack_rendered = NULL, *ack_released = NULL;
    gboolean notify = FALSE;

    g_mutex_lock(&f->lock);
    gboolean resized = w != f->width || h != f->height;
    if (resized) {
        f->width = w;
        f->height = h;
        f->n_damage = -1;
    }
    int64_t outside = 0;
    if (f->auto_ack && f->verify && f->store && f->store_w == w && f->store_h == h)
        outside = count_outside(f, data, stride, rects, n_rects);
    int64_t dirty = add_damage(f, rects, n_rects, w, h);
    int64_t marker = sample_marker(data, size, stride, w, h, f->marker_scale);
    int64_t seq = ++f->seq;
    f->last_marker = marker;
    if (f->auto_ack) {
        gboolean fresh_store = !f->store || f->store_w != w || f->store_h != h;
        if (fresh_store) {
            g_free(f->store);
            f->store = g_malloc((gsize)w * h * 4);
            f->store_w = w;
            f->store_h = h;
        }
        if (fresh_store || f->verify || f->whole || n_rects == 0 || !rects) {
            WPERectangle all = { 0, 0, w, h };
            copy_rect(f->store, w * 4, data, stride, all);
        } else {
            for (guint i = 0; i < n_rects; i++)
                copy_rect(f->store, w * 4, data, stride, rect_scaled_clamped(rects[i], f->damage_scale, w, h));
        }
        ack_rendered = g_object_ref(buffer);
        ack_released = f->shown; /* its reference goes to the acknowledgement */
        f->shown = g_object_ref(buffer);
    } else {
        if (f->pending) {
            /* WebKit sends a frame only after the last was rendered, so this is unexpected: let the older one go. */
            ack_rendered = g_object_ref(f->pending);
            ack_released = f->pending;
        }
        f->pending = g_object_ref(buffer);
    }
    record(f, seq, marker, arrival, dirty, outside);
    if (!f->notified) {
        f->notified = TRUE;
        notify = TRUE;
    }
    int64_t owner = f->owner;
    g_mutex_unlock(&f->lock);

    if (ack_rendered || ack_released)
        ack_post(view, ack_rendered, ack_released);
    if (notify)
        wv_emit(owner, WV_EV_FRAME, seq, marker, NULL, NULL);
    return TRUE;
}

int wv_frames_pull(WvFrames *f, uint8_t *dst, int dst_stride, int dst_w, int dst_h, gboolean full, int64_t *out)
{
    int64_t start = wv_now_ns();
    WPEView *view = NULL;
    WPEBuffer *rendered = NULL, *released = NULL;
    int result = 0;

    g_mutex_lock(&f->lock);
    memset(out, 0, sizeof(int64_t) * WV_PULL_OUT);
    out[0] = f->seq;
    out[1] = f->width;
    out[2] = f->height;
    out[6] = f->last_marker;
    const uint8_t *src = NULL;
    int src_stride = 0;
    gboolean fresh;
    if (f->auto_ack) {
        fresh = f->store && f->pulled_seq != f->seq;
        if (f->store) {
            src = f->store;
            src_stride = f->store_w * 4;
        }
    } else {
        WPEBuffer *b = f->pending ? f->pending : f->shown;
        fresh = f->pending != NULL;
        if (b) {
            WPEBufferSHM *shm = WPE_BUFFER_SHM(b);
            src = g_bytes_get_data(wpe_buffer_shm_get_data(shm), NULL);
            src_stride = (int)wpe_buffer_shm_get_stride(shm);
        }
    }
    if (!src || (!fresh && !full))
        goto done;
    if (!dst || dst_w != f->width || dst_h != f->height) {
        result = -1;
        goto done;
    }
    int rects = 0;
    out[5] = (full || f->n_damage < 0) ? 1 : 0;
    out[4] = copy_damage(f, dst, dst_stride, src, src_stride, full, &rects);
    out[7] = rects;
    if (!f->auto_ack && f->pending) {
        view = f->view ? g_object_ref(f->view) : NULL;
        rendered = g_object_ref(f->pending);
        released = f->shown; /* its reference goes to the acknowledgement */
        f->shown = f->pending;
        f->pending = NULL;
    }
    f->n_damage = 0;
    f->notified = FALSE;
    f->pulled_seq = f->seq;
    result = 1;
done:
    g_mutex_unlock(&f->lock);
    if (rendered || released)
        ack_post(view, rendered, released);
    if (view)
        g_object_unref(view);
    out[3] = wv_now_ns() - start;
    return result;
}

/* ----------------------------------------------------------------------------------------------------- the view */

typedef struct {
    WPEView parent_instance;
    WvFrames *frames;
    WPEInputMethodContext *im; /* weak: WebKit owns it */
    int64_t owner;
} WvView;
typedef struct { WPEViewClass parent_class; } WvViewClass;
G_DEFINE_TYPE(WvView, wv_view, WPE_TYPE_VIEW)

gboolean wv_is_view(WPEView *view) { return view && G_TYPE_CHECK_INSTANCE_TYPE(view, wv_view_get_type()); }
static WvView *as_wv(WPEView *view) { return wv_is_view(view) ? (WvView *)view : NULL; }
WvFrames *wv_view_frames(WPEView *view) { WvView *v = as_wv(view); return v ? v->frames : NULL; }
WPEInputMethodContext *wv_view_im(WPEView *view) { WvView *v = as_wv(view); return v ? v->im : NULL; }

void wv_view_set_owner(WPEView *view, int64_t owner)
{
    WvView *v = as_wv(view);
    if (!v)
        return;
    v->owner = owner;
    g_mutex_lock(&v->frames->lock);
    v->frames->owner = owner;
    g_mutex_unlock(&v->frames->lock);
}

void wv_view_set_size(WPEView *view, int width, int height, double scale)
{
    WPEToplevel *toplevel = wpe_view_get_toplevel(view);
    if (!toplevel)
        return;
    if (scale > 0)
        wpe_toplevel_scale_changed(toplevel, scale);
    if (width > 0 && height > 0)
        wpe_toplevel_resize(toplevel, width, height);
    WvView *v = as_wv(view);
    if (v)
        frames_set_scale(v->frames, wpe_view_get_scale(view));
}

static void on_toplevel(WPEView *view, GParamSpec *pspec, gpointer unused)
{
    WPEToplevel *toplevel = wpe_view_get_toplevel(view);
    if (!toplevel) {
        wpe_view_unmap(view);
        return;
    }
    int width = 0, height = 0;
    wpe_toplevel_get_size(toplevel, &width, &height);
    if (width > 0 && height > 0)
        wpe_view_resized(view, width, height);
    wpe_view_map(view); /* WebKit renders a mapped view; WpeNative.setVisible maps and unmaps it from then on */
}

static gboolean wv_view_render_buffer(WPEView *view, WPEBuffer *buffer, const WPERectangle *rects, guint n_rects, GError **error)
{
    WvView *v = as_wv(view);
    if (!v || !v->frames) {
        g_set_error_literal(error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "webview-wpe: no frame store");
        return FALSE;
    }
    return frames_render(v->frames, view, buffer, rects, n_rects, error);
}

static void wv_view_set_cursor_from_name(WPEView *view, const char *name)
{
    WvView *v = as_wv(view);
    if (v)
        wv_emit(v->owner, WV_EV_CURSOR, 0, 0, name, NULL);
}

static void wv_view_constructed(GObject *object)
{
    G_OBJECT_CLASS(wv_view_parent_class)->constructed(object);
    WvView *v = (WvView *)object;
    v->frames = frames_new(WPE_VIEW(object));
    g_signal_connect(object, "notify::toplevel", G_CALLBACK(on_toplevel), NULL);
}

static void wv_view_dispose(GObject *object)
{
    WvView *v = (WvView *)object;
    if (v->frames) {
        frames_detach(v->frames);
        wv_frames_unref(v->frames);
        v->frames = NULL;
    }
    G_OBJECT_CLASS(wv_view_parent_class)->dispose(object);
}

static void wv_view_init(WvView *v) { (void)v; }

static void wv_view_class_init(WvViewClass *klass)
{
    G_OBJECT_CLASS(klass)->constructed = wv_view_constructed;
    G_OBJECT_CLASS(klass)->dispose = wv_view_dispose;
    WPE_VIEW_CLASS(klass)->render_buffer = wv_view_render_buffer;
    WPE_VIEW_CLASS(klass)->set_cursor_from_name = wv_view_set_cursor_from_name;
}

/* ------------------------------------------------------------------------------------------------- the toplevel */

typedef struct { WPEToplevel parent_instance; } WvToplevel;
typedef struct { WPEToplevelClass parent_class; } WvToplevelClass;
G_DEFINE_TYPE(WvToplevel, wv_toplevel, WPE_TYPE_TOPLEVEL)

static gboolean resize_view(WPEToplevel *toplevel, WPEView *view, gpointer unused)
{
    int width = 0, height = 0;
    wpe_toplevel_get_size(toplevel, &width, &height);
    wpe_view_resized(view, width, height);
    return FALSE;
}

static gboolean wv_toplevel_resize(WPEToplevel *toplevel, int width, int height)
{
    wpe_toplevel_resized(toplevel, width, height);
    wpe_toplevel_foreach_view(toplevel, resize_view, NULL);
    return TRUE;
}

static void wv_toplevel_constructed(GObject *object)
{
    G_OBJECT_CLASS(wv_toplevel_parent_class)->constructed(object);
    /* Active, as the headless toplevel is: WebKit treats the page as in a focused window. */
    wpe_toplevel_state_changed(WPE_TOPLEVEL(object), WPE_TOPLEVEL_STATE_ACTIVE);
}

static void wv_toplevel_init(WvToplevel *t) { (void)t; }

static void wv_toplevel_class_init(WvToplevelClass *klass)
{
    G_OBJECT_CLASS(klass)->constructed = wv_toplevel_constructed;
    WPE_TOPLEVEL_CLASS(klass)->resize = wv_toplevel_resize;
}

/* --------------------------------------------------------------------------------------- the input-method context */

typedef struct {
    WPEInputMethodContext parent_instance;
    char *preedit;
    int cursor;
    gboolean composing;
} WvIm;
typedef struct { WPEInputMethodContextClass parent_class; } WvImClass;
G_DEFINE_TYPE(WvIm, wv_im, WPE_TYPE_INPUT_METHOD_CONTEXT)

static int64_t im_owner(WPEInputMethodContext *ctx)
{
    WvView *v = as_wv(wpe_input_method_context_get_view(ctx));
    return v ? v->owner : 0;
}

static void wv_im_get_preedit_string(WPEInputMethodContext *ctx, gchar **text, GList **underlines, guint *cursor)
{
    WvIm *im = (WvIm *)ctx;
    const char *preedit = im->preedit ? im->preedit : "";
    if (text)
        *text = g_strdup(preedit);
    if (underlines) {
        *underlines = NULL;
        if (*preedit)
            *underlines = g_list_append(NULL, wpe_input_method_underline_new(0, (guint)g_utf8_strlen(preedit, -1)));
    }
    if (cursor)
        *cursor = (guint)im->cursor;
}

static gboolean wv_im_filter_key_event(WPEInputMethodContext *ctx, WPEEvent *event) { return FALSE; }
static void wv_im_focus_in(WPEInputMethodContext *ctx) { wv_emit(im_owner(ctx), WV_EV_IME_FOCUS, 1, 0, NULL, NULL); }
static void wv_im_focus_out(WPEInputMethodContext *ctx) { wv_emit(im_owner(ctx), WV_EV_IME_FOCUS, 0, 0, NULL, NULL); }

static void wv_im_set_cursor_area(WPEInputMethodContext *ctx, int x, int y, int width, int height)
{
    wv_emit(im_owner(ctx), WV_EV_IME_CURSOR, ((int64_t)x << 32) | (uint32_t)y, ((int64_t)width << 32) | (uint32_t)height, NULL, NULL);
}

static void wv_im_set_surrounding(WPEInputMethodContext *ctx, const gchar *text, guint length, guint cursor_index, guint selection_index) { }

void wv_im_finish(WPEInputMethodContext *ctx)
{
    WvIm *im = (WvIm *)ctx;
    if (!im->composing)
        return;
    g_clear_pointer(&im->preedit, g_free);
    im->cursor = 0;
    g_signal_emit_by_name(ctx, "preedit-changed");
    im->composing = FALSE;
    g_signal_emit_by_name(ctx, "preedit-finished");
}

static void wv_im_reset(WPEInputMethodContext *ctx) { wv_im_finish(ctx); }

void wv_im_preedit(WPEInputMethodContext *ctx, const char *text, int cursor)
{
    WvIm *im = (WvIm *)ctx;
    if (!im->composing) {
        im->composing = TRUE;
        g_signal_emit_by_name(ctx, "preedit-started");
    }
    g_free(im->preedit);
    im->preedit = g_strdup(text ? text : "");
    im->cursor = cursor < 0 ? (int)g_utf8_strlen(im->preedit, -1) : cursor;
    g_signal_emit_by_name(ctx, "preedit-changed");
}

/* As GTK's input methods do: the commit first, then the composition ends. */
void wv_im_commit(WPEInputMethodContext *ctx, const char *text)
{
    WvIm *im = (WvIm *)ctx;
    gboolean composing = im->composing;
    if (composing) {
        g_clear_pointer(&im->preedit, g_free);
        im->cursor = 0;
    }
    g_signal_emit_by_name(ctx, "committed", text ? text : "");
    if (composing) {
        g_signal_emit_by_name(ctx, "preedit-changed");
        im->composing = FALSE;
        g_signal_emit_by_name(ctx, "preedit-finished");
    }
}

static void wv_im_finalize(GObject *object)
{
    g_free(((WvIm *)object)->preedit);
    G_OBJECT_CLASS(wv_im_parent_class)->finalize(object);
}

static void wv_im_init(WvIm *im) { (void)im; }

static void wv_im_class_init(WvImClass *klass)
{
    G_OBJECT_CLASS(klass)->finalize = wv_im_finalize;
    WPEInputMethodContextClass *im = WPE_INPUT_METHOD_CONTEXT_CLASS(klass);
    im->get_preedit_string = wv_im_get_preedit_string;
    im->filter_key_event = wv_im_filter_key_event;
    im->focus_in = wv_im_focus_in;
    im->focus_out = wv_im_focus_out;
    im->set_cursor_area = wv_im_set_cursor_area;
    im->set_surrounding = wv_im_set_surrounding;
    im->reset = wv_im_reset;
}

/* ------------------------------------------------------------------------------------------------ the clipboard */

/* The system's clipboard for every page: a page's copy goes to the JVM (WV_EV_CLIPBOARD, view 0), which gives it to
 * AWT's clipboard; what AWT's holds comes back before a page could paste it (wv_clipboard_set_system). Without it WPE
 * keeps a clipboard of its own, shared with no other app. Text only. */
typedef struct {
    WPEClipboard parent_instance;
    char *system_text; /* what the system's clipboard holds, as the JVM last said; NULL: no text */
} WvClipboard;
typedef struct { WPEClipboardClass parent_class; } WvClipboardClass;
G_DEFINE_TYPE(WvClipboard, wv_clipboard, WPE_TYPE_CLIPBOARD)

static WPEClipboard *clipboard; /* the display's, made at its first use (WebKit thread) */

static GBytes *wv_clipboard_read(WPEClipboard *c, const char *format)
{
    WvClipboard *wc = (WvClipboard *)c;
    if (!wc->system_text || !g_str_has_prefix(format, "text/plain"))
        return NULL;
    return g_bytes_new(wc->system_text, strlen(wc->system_text));
}

static void wv_clipboard_changed(WPEClipboard *c, GPtrArray *formats, gboolean is_local, WPEClipboardContent *content)
{
    WPE_CLIPBOARD_CLASS(wv_clipboard_parent_class)->changed(c, formats, is_local, content);
    const char *text = is_local && content ? wpe_clipboard_content_get_text(content) : NULL;
    if (text) /* a page copied */
        wv_emit(0, WV_EV_CLIPBOARD, 0, 0, text, NULL);
}

static void wv_clipboard_finalize(GObject *object)
{
    g_free(((WvClipboard *)object)->system_text);
    G_OBJECT_CLASS(wv_clipboard_parent_class)->finalize(object);
}

static void wv_clipboard_init(WvClipboard *c) { (void)c; }

static void wv_clipboard_class_init(WvClipboardClass *klass)
{
    G_OBJECT_CLASS(klass)->finalize = wv_clipboard_finalize;
    WPE_CLIPBOARD_CLASS(klass)->read = wv_clipboard_read;
    WPE_CLIPBOARD_CLASS(klass)->changed = wv_clipboard_changed;
}

void wv_clipboard_set_system(const char *text)
{
    if (!clipboard)
        return;
    WvClipboard *wc = (WvClipboard *)clipboard;
    g_free(wc->system_text);
    wc->system_text = text ? g_strdup(text) : NULL;
    GPtrArray *formats = NULL;
    if (text) {
        /* Interned, as wpe_clipboard_read_bytes finds a format by its pointer; NULL-terminated for get_formats. */
        formats = g_ptr_array_new_null_terminated(2, NULL, TRUE);
        g_ptr_array_add(formats, (gpointer)g_intern_static_string("text/plain"));
        g_ptr_array_add(formats, (gpointer)g_intern_static_string("text/plain;charset=utf-8"));
    }
    WPE_CLIPBOARD_GET_CLASS(clipboard)->changed(clipboard, formats, FALSE, NULL);
    if (formats)
        g_ptr_array_unref(formats);
}

/* -------------------------------------------------------------------------------------------------- the display */

typedef struct { WPEDisplay parent_instance; } WvDisplay;
typedef struct { WPEDisplayClass parent_class; } WvDisplayClass;
G_DEFINE_TYPE(WvDisplay, wv_display, WPE_TYPE_DISPLAY)

static gboolean wv_display_connect(WPEDisplay *display, GError **error) { return TRUE; }

static WPEView *wv_display_create_view(WPEDisplay *display)
{
    return WPE_VIEW(g_object_new(wv_view_get_type(), "display", display, NULL));
}

static WPEToplevel *wv_display_create_toplevel(WPEDisplay *display, guint max_views)
{
    return WPE_TOPLEVEL(g_object_new(wv_toplevel_get_type(), "display", display, NULL));
}

static WPEInputMethodContext *wv_display_create_input_method_context(WPEDisplay *display, WPEView *view)
{
    WPEInputMethodContext *im = WPE_INPUT_METHOD_CONTEXT(g_object_new(wv_im_get_type(), "view", view, NULL));
    WvView *v = as_wv(view);
    if (v)
        v->im = im;
    return im;
}

static WPEClipboard *wv_display_get_clipboard(WPEDisplay *display)
{
    if (!clipboard)
        clipboard = WPE_CLIPBOARD(g_object_new(wv_clipboard_get_type(), "display", display, NULL));
    return clipboard;
}

/* No GPU: WebKit's only transport is then shared memory (F12). */
static WPEDRMDevice *wv_display_get_drm_device(WPEDisplay *display) { return NULL; }

static void wv_display_init(WvDisplay *d) { (void)d; }

static void wv_display_class_init(WvDisplayClass *klass)
{
    WPEDisplayClass *display = WPE_DISPLAY_CLASS(klass);
    display->connect = wv_display_connect;
    display->create_view = wv_display_create_view;
    display->create_toplevel = wv_display_create_toplevel;
    display->create_input_method_context = wv_display_create_input_method_context;
    display->get_drm_device = wv_display_get_drm_device;
    display->get_clipboard = wv_display_get_clipboard;
}

/* The first display created is WebKit's primary (F12), so this runs before any web view. */
WPEDisplay *wv_display_new(GError **error)
{
    WPEDisplay *display = WPE_DISPLAY(g_object_new(wv_display_get_type(), NULL));
    if (!wpe_display_connect(display, error)) {
        g_object_unref(display);
        return NULL;
    }
    return display;
}
