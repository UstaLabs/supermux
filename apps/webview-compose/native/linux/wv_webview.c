/* Web views on our display (wv_platform.c): one WebKitWebView each, with its own network session (its profile, F20),
 * settings and user content manager, and the relay's message handler registered in a named script world (F16). Their
 * signals become events for the JVM; the operations the JVM posts (WvOp) run here, on the WebKit thread. */
#include "wv.h"
#include <libsoup/soup.h>
#include <string.h>

typedef struct {
    int64_t id;
    WebKitWebView *web;
    WPEView *wpe;
    WebKitUserContentManager *ucm;
    WvFrames *frames;
    GHashTable *decisions;  /* decision id → WebKitPolicyDecision (a reference), until the JVM answers */
    char *handler;          /* the relay's message handler, registered in `world` */
    char *world;
} WvWeb;

struct _WvSync {
    gint refs;              /* the caller's and the operation's */
    GMutex lock;
    GCond cond;
    gboolean done;
    int64_t result;
    GBytes *bytes;
};

static GHashTable *webs;            /* id → WvWeb*: WebKit thread */
static GMutex frames_lock;
static GHashTable *frames_by_id;    /* id → WvFrames* (a reference): any thread, under frames_lock */
static GHashTable *scheme_requests; /* request id → WebKitURISchemeRequest* (a reference): WebKit thread */
static int64_t next_view = 1, next_decision = 1, next_scheme_request = 1;
static WebKitNetworkSession *default_session;

#define KEY(id) GSIZE_TO_POINTER((gsize)(id))

void wv_webviews_init(void)
{
    webs = g_hash_table_new(g_direct_hash, g_direct_equal);
    g_mutex_lock(&frames_lock);
    frames_by_id = g_hash_table_new(g_direct_hash, g_direct_equal);
    g_mutex_unlock(&frames_lock);
    scheme_requests = g_hash_table_new_full(g_direct_hash, g_direct_equal, NULL, g_object_unref);
}

WvFrames *wv_frames_lookup(int64_t view)
{
    WvFrames *f = NULL;
    g_mutex_lock(&frames_lock);
    if (frames_by_id) {
        f = g_hash_table_lookup(frames_by_id, KEY(view));
        if (f)
            wv_frames_ref(f);
    }
    g_mutex_unlock(&frames_lock);
    return f;
}

static WvWeb *web_of(int64_t id) { return webs ? g_hash_table_lookup(webs, KEY(id)) : NULL; }

/* ---------------------------------------------------------------------------------------------------- signals */

static void on_load_changed(WebKitWebView *web, WebKitLoadEvent event, WvWeb *w)
{
    wv_emit(w->id, WV_EV_LOAD, event, 0, webkit_web_view_get_uri(web), NULL);
}

static gboolean on_load_failed(WebKitWebView *web, WebKitLoadEvent event, const char *uri, GError *error, WvWeb *w)
{
    int64_t flags = 0;
    if (g_error_matches(error, WEBKIT_NETWORK_ERROR, WEBKIT_NETWORK_ERROR_CANCELLED))
        flags |= WV_FAIL_CANCELLED;
    if (g_error_matches(error, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE))
        flags |= WV_FAIL_POLICY;
    if (g_error_matches(error, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_CANNOT_SHOW_URI)
        || g_error_matches(error, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_CANNOT_SHOW_MIME_TYPE))
        flags |= WV_FAIL_CANNOT_SHOW;
    wv_emit(w->id, WV_EV_LOAD_FAILED, error ? error->code : 0, flags, uri, error ? error->message : NULL);
    return TRUE; /* no error page of WebKit's (it would be a document of its own): the runtime shows its own */
}

static gboolean on_tls_failed(WebKitWebView *web, const char *uri, GTlsCertificate *certificate, GTlsCertificateFlags errors, WvWeb *w)
{
    wv_emit(w->id, WV_EV_LOAD_FAILED, (int64_t)errors, WV_FAIL_TLS, uri, "TLS certificate error");
    return TRUE; /* never bypassed */
}

static gboolean on_decide_policy(WebKitWebView *web, WebKitPolicyDecision *decision, WebKitPolicyDecisionType type, WvWeb *w)
{
    int64_t id = next_decision++;
    g_hash_table_insert(w->decisions, KEY(id), g_object_ref(decision));
    if (type == WEBKIT_POLICY_DECISION_TYPE_RESPONSE) {
        WebKitResponsePolicyDecision *r = WEBKIT_RESPONSE_POLICY_DECISION(decision);
        WebKitURIResponse *response = webkit_response_policy_decision_get_response(r);
        int64_t bits = (int64_t)webkit_uri_response_get_status_code(response) << 8;
        if (webkit_response_policy_decision_is_main_frame_main_resource(r))
            bits |= WV_RESPONSE_MAIN;
        if (webkit_response_policy_decision_is_mime_type_supported(r))
            bits |= WV_RESPONSE_CAN_SHOW;
        SoupMessageHeaders *headers = webkit_uri_response_get_http_headers(response);
        const char *disposition = headers ? soup_message_headers_get_one(headers, "Content-Disposition") : NULL;
        while (disposition && *disposition == ' ')
            disposition++;
        if (disposition && g_ascii_strncasecmp(disposition, "attachment", 10) == 0)
            bits |= WV_RESPONSE_ATTACHMENT;
        const char *mime = webkit_uri_response_get_mime_type(response);
        const char *name = webkit_uri_response_get_suggested_filename(response);
        char *info = g_strdup_printf("%s\n%s", mime ? mime : "", name ? name : "");
        wv_emit(w->id, WV_EV_RESPONSE, id, bits, webkit_uri_response_get_uri(response), info);
        g_free(info);
        return TRUE;
    }
    WebKitNavigationAction *action = webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(decision));
    int64_t bits = (int64_t)webkit_navigation_action_get_navigation_type(action) << 4;
    if (type == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION)
        bits |= WV_POLICY_NEW_WINDOW;
    if (webkit_navigation_action_is_user_gesture(action))
        bits |= WV_POLICY_GESTURE;
    if (webkit_navigation_action_is_redirect(action))
        bits |= WV_POLICY_REDIRECT;
    wv_emit(w->id, WV_EV_POLICY, id, bits, webkit_uri_request_get_uri(webkit_navigation_action_get_request(action)),
        webkit_navigation_action_get_frame_name(action));
    return TRUE; /* answered later, by the JVM (WV_OP_DECIDE) */
}

static void on_title(WebKitWebView *web, GParamSpec *pspec, WvWeb *w)
{
    wv_emit(w->id, WV_EV_TITLE, 0, 0, webkit_web_view_get_title(web), NULL);
}

static void on_uri(WebKitWebView *web, GParamSpec *pspec, WvWeb *w)
{
    wv_emit(w->id, WV_EV_URI, 0, 0, webkit_web_view_get_uri(web), NULL);
}

static void on_progress(WebKitWebView *web, GParamSpec *pspec, WvWeb *w)
{
    wv_emit(w->id, WV_EV_PROGRESS, (int64_t)(webkit_web_view_get_estimated_load_progress(web) * 1000), 0, NULL, NULL);
}

static char *join_uris(GList *items)
{
    GString *s = g_string_new(NULL);
    for (GList *l = items; l; l = l->next) {
        if (l != items)
            g_string_append_c(s, '\n');
        const char *uri = webkit_back_forward_list_item_get_uri(WEBKIT_BACK_FORWARD_LIST_ITEM(l->data));
        g_string_append(s, uri ? uri : "");
    }
    return g_string_free(s, FALSE);
}

static void on_history(WebKitBackForwardList *list, WebKitBackForwardListItem *added, gpointer removed, WvWeb *w)
{
    /* WebKit builds each GList by prepending its items in order, which reverses them: reversed again, the back list
     * is oldest first (WebKitUrls.canGoBack's order) and the forward list nearest first. */
    GList *back = g_list_reverse(webkit_back_forward_list_get_back_list(list));
    GList *forward = g_list_reverse(webkit_back_forward_list_get_forward_list(list));
    char *b = join_uris(back), *f = join_uris(forward);
    wv_emit(w->id, WV_EV_HISTORY, webkit_web_view_can_go_back(w->web), webkit_web_view_can_go_forward(w->web), b, f);
    g_free(b);
    g_free(f);
    g_list_free(back);
    g_list_free(forward);
}

static void on_terminated(WebKitWebView *web, WebKitWebProcessTerminationReason reason, WvWeb *w)
{
    wv_emit(w->id, WV_EV_CRASHED, reason, 0, NULL, NULL);
}

static void on_relay(WebKitUserContentManager *ucm, JSCValue *value, WvWeb *w)
{
    if (!jsc_value_is_string(value))
        return;
    char *text = jsc_value_to_string(value);
    wv_emit(w->id, WV_EV_RELAY, 0, 0, text, NULL);
    g_free(text);
}

static void on_scheme_request(WebKitURISchemeRequest *request, gpointer unused)
{
    WebKitWebView *web = webkit_uri_scheme_request_get_web_view(request);
    WvWeb *w = web ? g_object_get_data(G_OBJECT(web), "wv-web") : NULL;
    int64_t id = next_scheme_request++;
    g_hash_table_insert(scheme_requests, KEY(id), g_object_ref(request));
    wv_emit(w ? w->id : 0, WV_EV_SCHEME, id, 0, webkit_uri_scheme_request_get_uri(request), NULL);
}

/* ----------------------------------------------------------------------------------------------------- views */

static WebKitNetworkSession *session_for(int profile, const char *dir)
{
    if (profile == 2)
        return webkit_network_session_new_ephemeral();
    if (profile == 1 && dir && *dir) {
        char *data = g_build_filename(dir, "data", NULL), *cache = g_build_filename(dir, "cache", NULL);
        WebKitNetworkSession *session = webkit_network_session_new(data, cache);
        g_free(data);
        g_free(cache);
        return session;
    }
    if (!default_session) {
        char *data = g_build_filename(wv_host_data_dir(), "default", NULL);
        char *cache = g_build_filename(wv_host_cache_dir(), "default", NULL);
        default_session = webkit_network_session_new(data, cache);
        g_free(data);
        g_free(cache);
    }
    return g_object_ref(default_session);
}

/* i[0] profile (0 default, 1 named, 2 ephemeral), i[1] media needs a gesture, i[2] width, i[3] height, d[0] scale,
 * s[0] the named profile's folder. Returns the view's id, 0 on failure. */
static int64_t web_create(WvOp *op)
{
    WebKitNetworkSession *session = session_for((int)op->i[0], op->s[0]);
    WebKitSettings *settings = webkit_settings_new();
    webkit_settings_set_javascript_can_open_windows_automatically(settings, FALSE);
    webkit_settings_set_allow_file_access_from_file_urls(settings, FALSE);
    webkit_settings_set_allow_universal_access_from_file_urls(settings, FALSE);
    webkit_settings_set_media_playback_requires_user_gesture(settings, op->i[1] != 0);
    WebKitUserContentManager *ucm = webkit_user_content_manager_new();
    WebKitWebView *web = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW,
        "display", wv_host_display(), "web-context", wv_host_context(), "network-session", session,
        "settings", settings, "user-content-manager", ucm, NULL));
    g_object_unref(settings);
    g_object_unref(session);
    WPEView *wpe = webkit_web_view_get_wpe_view(web);
    if (!wv_is_view(wpe)) { /* not on our display: no frame would ever reach us */
        g_object_unref(web);
        g_object_unref(ucm);
        return 0;
    }
    WvWeb *w = g_new0(WvWeb, 1);
    w->id = next_view++;
    w->web = web;
    w->wpe = wpe;
    w->ucm = ucm;
    w->decisions = g_hash_table_new_full(g_direct_hash, g_direct_equal, NULL, g_object_unref);
    w->frames = wv_frames_ref(wv_view_frames(wpe));
    g_object_set_data(G_OBJECT(web), "wv-web", w);
    wv_view_set_owner(wpe, w->id);
    wv_view_set_size(wpe, (int)op->i[2], (int)op->i[3], op->d[0]);
    g_signal_connect(web, "load-changed", G_CALLBACK(on_load_changed), w);
    g_signal_connect(web, "load-failed", G_CALLBACK(on_load_failed), w);
    g_signal_connect(web, "load-failed-with-tls-errors", G_CALLBACK(on_tls_failed), w);
    g_signal_connect(web, "decide-policy", G_CALLBACK(on_decide_policy), w);
    g_signal_connect(web, "notify::title", G_CALLBACK(on_title), w);
    g_signal_connect(web, "notify::uri", G_CALLBACK(on_uri), w);
    g_signal_connect(web, "notify::estimated-load-progress", G_CALLBACK(on_progress), w);
    g_signal_connect(web, "web-process-terminated", G_CALLBACK(on_terminated), w);
    g_signal_connect(webkit_web_view_get_back_forward_list(web), "changed", G_CALLBACK(on_history), w);
    g_hash_table_insert(webs, KEY(w->id), w);
    g_mutex_lock(&frames_lock);
    g_hash_table_insert(frames_by_id, KEY(w->id), wv_frames_ref(w->frames));
    g_mutex_unlock(&frames_lock);
    return w->id;
}

static void web_destroy(WvWeb *w)
{
    g_hash_table_remove(webs, KEY(w->id));
    g_mutex_lock(&frames_lock);
    WvFrames *f = g_hash_table_lookup(frames_by_id, KEY(w->id));
    g_hash_table_remove(frames_by_id, KEY(w->id));
    g_mutex_unlock(&frames_lock);
    if (f)
        wv_frames_unref(f);
    GHashTableIter it;
    gpointer key, value;
    g_hash_table_iter_init(&it, w->decisions);
    while (g_hash_table_iter_next(&it, &key, &value))
        webkit_policy_decision_ignore(WEBKIT_POLICY_DECISION(value));
    g_hash_table_destroy(w->decisions);
    g_signal_handlers_disconnect_by_data(webkit_web_view_get_back_forward_list(w->web), w);
    g_signal_handlers_disconnect_by_data(w->web, w);
    g_signal_handlers_disconnect_by_data(w->ucm, w);
    if (w->handler)
        webkit_user_content_manager_unregister_script_message_handler(w->ucm, w->handler, w->world);
    g_object_set_data(G_OBJECT(w->web), "wv-web", NULL);
    g_object_unref(w->web); /* the page goes with it */
    g_object_unref(w->ucm);
    wv_frames_unref(w->frames);
    g_free(w->handler);
    g_free(w->world);
    g_free(w);
}

/* s[0] relay (in the world), s[1] shim (page world, may be NULL), s[2] world, s[3] handler, s[4] user agent (NULL:
 * WebKit's), i[0] flags (1 page scripts, 2 inspector), i[1] background 0xAARRGGBB. The handler and world are set by
 * the first configure and stay. */
static void web_configure(WvWeb *w, WvOp *op)
{
    const char *relay = op->s[0], *shim = op->s[1], *world = op->s[2], *handler = op->s[3], *agent = op->s[4];
    int flags = (int)op->i[0];
    uint32_t background = (uint32_t)op->i[1];
    if (!w->handler && handler && *handler && world && *world) {
        w->handler = g_strdup(handler);
        w->world = g_strdup(world);
        webkit_user_content_manager_register_script_message_handler(w->ucm, handler, world);
        char *signal = g_strdup_printf("script-message-received::%s", handler);
        g_signal_connect(w->ucm, signal, G_CALLBACK(on_relay), w);
        g_free(signal);
    }
    webkit_user_content_manager_remove_all_scripts(w->ucm);
    if (relay && w->world) {
        WebKitUserScript *script = webkit_user_script_new_for_world(relay, WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
            WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, w->world, NULL, NULL);
        webkit_user_content_manager_add_script(w->ucm, script);
        webkit_user_script_unref(script);
    }
    if (shim) {
        WebKitUserScript *script = webkit_user_script_new(shim, WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
            WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL);
        webkit_user_content_manager_add_script(w->ucm, script);
        webkit_user_script_unref(script);
    }
    WebKitSettings *settings = webkit_web_view_get_settings(w->web);
    /* The page's own scripts follow the lease; the engine's user scripts run either way, as allowsContentJavaScript
     * does on Apple's WebKit. */
    webkit_settings_set_enable_javascript(settings, TRUE);
    webkit_settings_set_enable_javascript_markup(settings, (flags & 1) != 0);
    webkit_settings_set_enable_developer_extras(settings, (flags & 2) != 0);
    webkit_settings_set_user_agent(settings, agent);
    WebKitColor color = { ((background >> 16) & 0xff) / 255.0, ((background >> 8) & 0xff) / 255.0,
        (background & 0xff) / 255.0, ((background >> 24) & 0xff) / 255.0 };
    webkit_web_view_set_background_color(w->web, &color);
}

/* i[0] kind (0 URI, 1 HTML), s[0] the URI or the HTML, s[1] the HTML's base URI, strv header name/value pairs. */
static void web_load(WvWeb *w, WvOp *op)
{
    if (op->i[0] == 1) {
        webkit_web_view_load_html(w->web, op->s[0], op->s[1]);
        return;
    }
    if (!op->strv || !op->strv[0]) {
        webkit_web_view_load_uri(w->web, op->s[0]);
        return;
    }
    WebKitURIRequest *request = webkit_uri_request_new(op->s[0]);
    SoupMessageHeaders *headers = webkit_uri_request_get_http_headers(request);
    for (char **h = op->strv; headers && h[0] && h[1]; h += 2)
        soup_message_headers_append(headers, h[0], h[1]);
    webkit_web_view_load_request(w->web, request);
    g_object_unref(request);
}

static void web_navigate(WvWeb *w, int what)
{
    switch (what) {
    case 0: webkit_web_view_reload(w->web); break;
    case 1: webkit_web_view_stop_loading(w->web); break;
    case 2: webkit_web_view_go_back(w->web); break;
    case 3: webkit_web_view_go_forward(w->web); break;
    default: break;
    }
}

typedef struct { int64_t view, request; } WvEval;

static void report_eval(WvEval *e, JSCValue *value, GError *error)
{
    if (e->request) {
        char *json = value && !jsc_value_is_undefined(value) ? jsc_value_to_json(value, 0) : NULL;
        wv_emit(e->view, WV_EV_EVAL, e->request, error ? 0 : 1, json, error ? error->message : NULL);
        g_free(json);
    }
    g_clear_object(&value);
    g_clear_error(&error);
    g_free(e);
}

static void on_evaluated(GObject *source, GAsyncResult *result, gpointer data)
{
    GError *error = NULL;
    JSCValue *value = webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(source), result, &error);
    report_eval(data, value, error);
}

static void on_called(GObject *source, GAsyncResult *result, gpointer data)
{
    GError *error = NULL;
    JSCValue *value = webkit_web_view_call_async_javascript_function_finish(WEBKIT_WEB_VIEW(source), result, &error);
    report_eval(data, value, error);
}

/* i[0] request (0: no answer wanted), s[0] script, s[1] world (NULL: the page's). */
static void web_evaluate(WvWeb *w, WvOp *op)
{
    WvEval *e = g_new0(WvEval, 1);
    e->view = w->id;
    e->request = op->i[0];
    webkit_web_view_evaluate_javascript(w->web, op->s[0], -1, op->s[1], NULL, NULL, on_evaluated, e);
}

/* i[0] request, s[0] the function body, s[1] world, strv argument name/value pairs (strings), awaited if a promise. */
static void web_call_async(WvWeb *w, WvOp *op)
{
    WvEval *e = g_new0(WvEval, 1);
    e->view = w->id;
    e->request = op->i[0];
    GVariantBuilder builder;
    g_variant_builder_init(&builder, G_VARIANT_TYPE("a{sv}"));
    for (char **a = op->strv; a && a[0] && a[1]; a += 2)
        g_variant_builder_add(&builder, "{sv}", a[0], g_variant_new_string(a[1]));
    webkit_web_view_call_async_javascript_function(w->web, op->s[0], -1, g_variant_builder_end(&builder), op->s[1],
        NULL, NULL, on_called, e);
}

static void web_decide(WvWeb *w, int64_t id, int verdict)
{
    WebKitPolicyDecision *decision = g_hash_table_lookup(w->decisions, KEY(id));
    if (!decision)
        return;
    if (verdict == 1)
        webkit_policy_decision_use(decision);
    else if (verdict == 2)
        webkit_policy_decision_download(decision);
    else
        webkit_policy_decision_ignore(decision);
    g_hash_table_remove(w->decisions, KEY(id));
}

static int64_t web_save_state(WvWeb *w, GBytes **out)
{
    WebKitWebViewSessionState *state = webkit_web_view_get_session_state(w->web);
    *out = webkit_web_view_session_state_serialize(state);
    webkit_web_view_session_state_unref(state);
    return *out ? 1 : 0;
}

static int64_t web_restore_state(WvWeb *w, GBytes *bytes)
{
    WebKitWebViewSessionState *state = bytes ? webkit_web_view_session_state_new(bytes) : NULL;
    if (!state)
        return 0;
    webkit_web_view_restore_session_state(w->web, state);
    webkit_web_view_session_state_unref(state);
    /* The list is replaced, not navigated (F19): load its current item. */
    WebKitBackForwardListItem *item = webkit_back_forward_list_get_current_item(webkit_web_view_get_back_forward_list(w->web));
    if (!item)
        return 0;
    webkit_web_view_go_to_back_forward_list_item(w->web, item);
    return 1;
}

static guint32 event_time(int64_t ms) { return ms > 0 ? (guint32)ms : (guint32)(g_get_monotonic_time() / 1000); }

/* i[0] type (0 move, 1 down, 2 up, 3 enter, 4 leave), i[1] button, i[2] modifiers, i[3] press count (0: WPE's), i[4]
 * time in ms (0: now); d[0], d[1] the position in view coordinates. */
static void web_pointer(WvWeb *w, WvOp *op)
{
    int type = (int)op->i[0];
    double x = op->d[0], y = op->d[1];
    guint button = (guint)op->i[1];
    WPEModifiers modifiers = (WPEModifiers)op->i[2];
    guint32 time = event_time(op->i[4]);
    WPEEvent *event;
    if (type == 1 || type == 2) {
        /* WPE 2.54 takes a press count on a press only: it refuses a release that has one (returns NULL). */
        guint count = 0;
        if (type == 1) {
            count = (guint)op->i[3];
            if (count == 0)
                count = wpe_view_compute_press_count(w->wpe, x, y, button, time);
        }
        event = wpe_event_pointer_button_new(type == 1 ? WPE_EVENT_POINTER_DOWN : WPE_EVENT_POINTER_UP, w->wpe,
            WPE_INPUT_SOURCE_MOUSE, time, modifiers, button, x, y, count);
    } else {
        WPEEventType kind = type == 3 ? WPE_EVENT_POINTER_ENTER : type == 4 ? WPE_EVENT_POINTER_LEAVE : WPE_EVENT_POINTER_MOVE;
        event = wpe_event_pointer_move_new(kind, w->wpe, WPE_INPUT_SOURCE_MOUSE, time, modifiers, x, y, 0, 0);
    }
    if (!event)
        return; /* refused by WPE (a critical warning says why): wpe_event_unref(NULL) would crash the process */
    wpe_view_event(w->wpe, event);
    wpe_event_unref(event);
}

/* d[0], d[1] deltas, d[2], d[3] position; i[0] precise (pixels) or steps, i[1] modifiers, i[2] time. */
static void web_scroll(WvWeb *w, WvOp *op)
{
    WPEEvent *event = wpe_event_scroll_new(w->wpe, op->i[0] ? WPE_INPUT_SOURCE_TOUCHPAD : WPE_INPUT_SOURCE_MOUSE,
        event_time(op->i[2]), (WPEModifiers)op->i[1], op->d[0], op->d[1], op->i[0] != 0, FALSE, op->d[2], op->d[3]);
    if (!event)
        return;
    wpe_view_event(w->wpe, event);
    wpe_event_unref(event);
}

/* i[0] down, i[1] keyval, i[2] keycode, i[3] modifiers, i[4] time. */
static void web_key(WvWeb *w, WvOp *op)
{
    WPEEvent *event = wpe_event_keyboard_new(op->i[0] ? WPE_EVENT_KEYBOARD_KEY_DOWN : WPE_EVENT_KEYBOARD_KEY_UP, w->wpe,
        WPE_INPUT_SOURCE_KEYBOARD, event_time(op->i[4]), (WPEModifiers)op->i[3], (guint)op->i[2], (guint)op->i[1]);
    if (!event)
        return;
    wpe_view_event(w->wpe, event);
    wpe_event_unref(event);
}

/* i[0] kind (1 preedit, 2 commit, 3 finish), i[1] the preedit's cursor (-1: its end), s[0] text. */
static void web_ime(WvWeb *w, WvOp *op)
{
    WPEInputMethodContext *im = wv_view_im(w->wpe);
    if (!im)
        return;
    if (op->i[0] == 1)
        wv_im_preedit(im, op->s[0], (int)op->i[1]);
    else if (op->i[0] == 2)
        wv_im_commit(im, op->s[0]);
    else
        wv_im_finish(im);
}

/* s[0] scheme, i[0] secure context. Before the first view that uses it. */
static void register_scheme(WvOp *op)
{
    webkit_web_context_register_uri_scheme(wv_host_context(), op->s[0], on_scheme_request, NULL, NULL);
    WebKitSecurityManager *security = webkit_web_context_get_security_manager(wv_host_context());
    if (op->i[0])
        webkit_security_manager_register_uri_scheme_as_secure(security, op->s[0]);
    webkit_security_manager_register_uri_scheme_as_cors_enabled(security, op->s[0]);
}

/* i[0] request id, i[1] HTTP status, s[0] MIME type, bytes the body. A status outside 2xx fails the request. */
static void finish_scheme(WvOp *op)
{
    WebKitURISchemeRequest *request = g_hash_table_lookup(scheme_requests, KEY(op->i[0]));
    if (!request)
        return;
    int status = (int)op->i[1];
    if (status >= 200 && status < 300 && op->bytes) {
        GInputStream *stream = g_memory_input_stream_new_from_bytes(op->bytes);
        WebKitURISchemeResponse *response = webkit_uri_scheme_response_new(stream, (gint64)g_bytes_get_size(op->bytes));
        webkit_uri_scheme_response_set_status(response, (guint)status, NULL);
        if (op->s[0])
            webkit_uri_scheme_response_set_content_type(response, op->s[0]);
        webkit_uri_scheme_request_finish_with_response(request, response);
        g_object_unref(response);
        g_object_unref(stream);
    } else {
        GError *error = g_error_new(G_IO_ERROR, G_IO_ERROR_NOT_FOUND, "webview-wpe: status %d", status);
        webkit_uri_scheme_request_finish_error(request, error);
        g_error_free(error);
    }
    g_hash_table_remove(scheme_requests, KEY(op->i[0]));
}

typedef struct { int64_t view, request; } WvClear;

static void on_cleared(GObject *source, GAsyncResult *result, gpointer data)
{
    WvClear *c = data;
    GError *error = NULL;
    gboolean ok = webkit_website_data_manager_clear_finish(WEBKIT_WEBSITE_DATA_MANAGER(source), result, &error);
    wv_emit(c->view, WV_EV_DATA_CLEARED, c->request, ok ? 1 : 0, NULL, error ? error->message : NULL);
    g_clear_error(&error);
    g_free(c);
}

/* i[0] request. Every kind of website data in this view's own network session (an ephemeral profile's, for a reset). */
static void web_clear_data(WvWeb *w, WvOp *op)
{
    WvClear *c = g_new0(WvClear, 1);
    c->view = w->id;
    c->request = op->i[0];
    WebKitNetworkSession *session = webkit_web_view_get_network_session(w->web);
    webkit_website_data_manager_clear(webkit_network_session_get_website_data_manager(session), WEBKIT_WEBSITE_DATA_ALL, 0,
        NULL, on_cleared, c);
}

/* ------------------------------------------------------------------------------------------------ operations */

WvOp *wv_op_new(int op, int64_t view)
{
    WvOp *o = g_new0(WvOp, 1);
    o->op = op;
    o->view = view;
    return o;
}

static void sync_unref(WvSync *s)
{
    if (!g_atomic_int_dec_and_test(&s->refs))
        return;
    if (s->bytes)
        g_bytes_unref(s->bytes);
    g_mutex_clear(&s->lock);
    g_cond_clear(&s->cond);
    g_free(s);
}

static void finish_sync(WvSync *s, int64_t result, GBytes *bytes)
{
    g_mutex_lock(&s->lock);
    if (!s->done) {
        s->result = result;
        s->bytes = bytes;
        s->done = TRUE;
        g_cond_signal(&s->cond);
    } else if (bytes) {
        g_bytes_unref(bytes);
    }
    g_mutex_unlock(&s->lock);
}

static void op_free(gpointer data)
{
    WvOp *op = data;
    for (int k = 0; k < 6; k++)
        g_free(op->s[k]);
    g_strfreev(op->strv);
    if (op->bytes)
        g_bytes_unref(op->bytes);
    if (op->sync) { /* it never ran: wake the caller with a failure */
        finish_sync(op->sync, -1, NULL);
        sync_unref(op->sync);
    }
    g_free(op);
}

static gboolean op_run(gpointer data)
{
    WvOp *op = data;
    int64_t result = 0;
    GBytes *bytes = NULL;
    WvWeb *w = op->view ? web_of(op->view) : NULL;
    switch (op->op) {
    case WV_OP_CREATE:
        result = web_create(op);
        break;
    case WV_OP_REGISTER_SCHEME:
        register_scheme(op);
        break;
    case WV_OP_SET_DARK: /* process-wide on WPE (F18) */
        wpe_settings_set_boolean(wpe_display_get_settings(wv_host_display()), WPE_SETTING_DARK_MODE, op->i[0] != 0,
            WPE_SETTINGS_SOURCE_APPLICATION, NULL);
        break;
    case WV_OP_FINISH_SCHEME:
        finish_scheme(op);
        break;
    default:
        if (!w)
            break; /* the view is gone */
        switch (op->op) {
        case WV_OP_DESTROY: web_destroy(w); break;
        case WV_OP_CONFIGURE: web_configure(w, op); break;
        case WV_OP_LOAD: web_load(w, op); break;
        case WV_OP_NAVIGATE: web_navigate(w, (int)op->i[0]); break;
        case WV_OP_EVALUATE: web_evaluate(w, op); break;
        case WV_OP_CALL_ASYNC: web_call_async(w, op); break;
        case WV_OP_DECIDE: web_decide(w, op->i[0], (int)op->i[1]); break;
        case WV_OP_SAVE_STATE: result = web_save_state(w, &bytes); break;
        case WV_OP_RESTORE_STATE: result = web_restore_state(w, op->bytes); break;
        case WV_OP_SET_SIZE: wv_view_set_size(w->wpe, (int)op->i[0], (int)op->i[1], op->d[0]); break;
        case WV_OP_SET_VISIBLE: wpe_view_set_visible(w->wpe, op->i[0] != 0); break;
        case WV_OP_SET_FOCUS:
            if (op->i[0])
                wpe_view_focus_in(w->wpe);
            else
                wpe_view_focus_out(w->wpe);
            break;
        case WV_OP_POINTER: web_pointer(w, op); break;
        case WV_OP_SCROLL: web_scroll(w, op); break;
        case WV_OP_KEY: web_key(w, op); break;
        case WV_OP_IME: web_ime(w, op); break;
        case WV_OP_TERMINATE: webkit_web_view_terminate_web_process(w->web); break;
        case WV_OP_CLEAR_DATA: web_clear_data(w, op); break;
        default: break;
        }
    }
    if (op->sync) {
        finish_sync(op->sync, result, bytes);
        sync_unref(op->sync);
        op->sync = NULL;
    } else if (bytes) {
        g_bytes_unref(bytes);
    }
    return G_SOURCE_REMOVE;
}

void wv_op_post(WvOp *op) { wv_host_post(op_run, op, op_free); }

int64_t wv_op_call(WvOp *op, GBytes **bytes)
{
    if (bytes)
        *bytes = NULL;
    if (wv_host_is_thread()) { /* it would wait for itself */
        op_free(op);
        return -1;
    }
    WvSync *s = g_new0(WvSync, 1);
    s->refs = 2;
    g_mutex_init(&s->lock);
    g_cond_init(&s->cond);
    op->sync = s;
    wv_op_post(op);
    g_mutex_lock(&s->lock);
    while (!s->done)
        g_cond_wait(&s->cond, &s->lock);
    int64_t result = s->result;
    if (bytes) {
        *bytes = s->bytes;
        s->bytes = NULL;
    }
    g_mutex_unlock(&s->lock);
    sync_unref(s);
    return result;
}
