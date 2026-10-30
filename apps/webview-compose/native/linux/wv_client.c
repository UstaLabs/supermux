/* The page's requests the JVM answers later: script dialogs, permission requests and file choosers, each kept by id
 * until the engine answers it (WV_OP_ANSWER). A view that goes answers its own with the safe defaults: dialogs dismissed
 * (a beforeunload leaves the page), permissions denied, file choosers cancelled. New windows aren't kept: WebKit wants
 * the new web view at once, and gets none; the engine decides what becomes of the URL. WebKit thread only. */
#include "wv.h"
#include <string.h>

typedef enum { WV_REQ_DIALOG, WV_REQ_PERMISSION, WV_REQ_FILES } WvRequestKind;

typedef struct {
    WvRequestKind kind;
    int64_t view;
    gpointer object; /* a WebKitScriptDialog (boxed) or a GObject; a reference either way */
} WvRequest;

static GHashTable *requests; /* request id → WvRequest* */
static int64_t next_request = 1;

#define ID(id) GSIZE_TO_POINTER((gsize)(id))

static void request_free(gpointer data)
{
    WvRequest *r = data;
    if (r->kind == WV_REQ_DIALOG)
        webkit_script_dialog_unref(r->object);
    else
        g_object_unref(r->object);
    g_free(r);
}

static int64_t keep(WvRequestKind kind, int64_t view, gpointer object)
{
    if (!requests)
        requests = g_hash_table_new_full(g_direct_hash, g_direct_equal, NULL, request_free);
    WvRequest *r = g_new0(WvRequest, 1);
    r->kind = kind;
    r->view = view;
    r->object = object;
    int64_t id = next_request++;
    g_hash_table_insert(requests, ID(id), r);
    return id;
}

/* allow: a confirm's or beforeunload's OK (leave), a prompt's text (else null), a permission's grant; files: a
 * chooser's paths, NULL or empty to cancel. */
static void answer(WvRequest *r, gboolean allow, const char *text, char **files)
{
    switch (r->kind) {
    case WV_REQ_DIALOG: {
        WebKitScriptDialog *dialog = r->object;
        switch (webkit_script_dialog_get_dialog_type(dialog)) {
        case WEBKIT_SCRIPT_DIALOG_CONFIRM:
        case WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM:
            webkit_script_dialog_confirm_set_confirmed(dialog, allow);
            break;
        case WEBKIT_SCRIPT_DIALOG_PROMPT:
            if (allow)
                webkit_script_dialog_prompt_set_text(dialog, text ? text : "");
            break;
        default:
            break;
        }
        webkit_script_dialog_close(dialog);
        break;
    }
    case WV_REQ_PERMISSION:
        if (allow)
            webkit_permission_request_allow(r->object);
        else
            webkit_permission_request_deny(r->object);
        break;
    case WV_REQ_FILES:
        if (files && files[0])
            webkit_file_chooser_request_select_files(r->object, (const gchar *const *)files);
        else
            webkit_file_chooser_request_cancel(r->object);
        break;
    }
}

static void answer_by_default(WvRequest *r)
{
    gboolean leave = r->kind == WV_REQ_DIALOG
        && webkit_script_dialog_get_dialog_type(r->object) == WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM;
    answer(r, leave, NULL, NULL);
}

static gboolean on_script_dialog(WebKitWebView *web, WebKitScriptDialog *dialog, gpointer data)
{
    int64_t view = (int64_t)GPOINTER_TO_SIZE(data);
    WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
    int64_t id = keep(WV_REQ_DIALOG, view, webkit_script_dialog_ref(dialog));
    wv_emit(view, WV_EV_DIALOG, id, type, webkit_script_dialog_get_message(dialog),
        type == WEBKIT_SCRIPT_DIALOG_PROMPT ? webkit_script_dialog_prompt_get_default_text(dialog) : NULL);
    return TRUE; /* answered later, by the JVM */
}

static gboolean on_permission(WebKitWebView *web, WebKitPermissionRequest *request, gpointer data)
{
    int64_t view = (int64_t)GPOINTER_TO_SIZE(data);
    int64_t kinds = 0;
    if (WEBKIT_IS_GEOLOCATION_PERMISSION_REQUEST(request)) {
        kinds = WV_PERM_GEOLOCATION;
    } else if (WEBKIT_IS_NOTIFICATION_PERMISSION_REQUEST(request)) {
        kinds = WV_PERM_NOTIFICATIONS;
    } else if (WEBKIT_IS_USER_MEDIA_PERMISSION_REQUEST(request)) {
        WebKitUserMediaPermissionRequest *media = WEBKIT_USER_MEDIA_PERMISSION_REQUEST(request);
        if (webkit_user_media_permission_is_for_video_device(media))
            kinds |= WV_PERM_CAMERA;
        if (webkit_user_media_permission_is_for_audio_device(media))
            kinds |= WV_PERM_MICROPHONE;
        if (webkit_user_media_permission_is_for_display_device(media))
            kinds |= WV_PERM_OTHER;
    }
    if (!kinds)
        kinds = WV_PERM_OTHER;
    int64_t id = keep(WV_REQ_PERMISSION, view, g_object_ref(request));
    wv_emit(view, WV_EV_PERMISSION, id, kinds, webkit_web_view_get_uri(web), NULL);
    return TRUE;
}

static gboolean on_file_chooser(WebKitWebView *web, WebKitFileChooserRequest *request, gpointer data)
{
    int64_t view = (int64_t)GPOINTER_TO_SIZE(data);
    const gchar *const *types = webkit_file_chooser_request_get_mime_types(request);
    char *joined = types ? g_strjoinv("\n", (gchar **)types) : NULL;
    int64_t id = keep(WV_REQ_FILES, view, g_object_ref(request));
    wv_emit(view, WV_EV_FILE_CHOOSER, id, webkit_file_chooser_request_get_select_multiple(request) ? 1 : 0, joined, NULL);
    g_free(joined);
    return TRUE;
}

static WebKitWebView *on_create(WebKitWebView *web, WebKitNavigationAction *action, gpointer data)
{
    int64_t view = (int64_t)GPOINTER_TO_SIZE(data);
    WebKitURIRequest *request = webkit_navigation_action_get_request(action);
    wv_emit(view, WV_EV_NEW_WINDOW, webkit_navigation_action_is_user_gesture(action) ? 1 : 0, 0,
        request ? webkit_uri_request_get_uri(request) : NULL, NULL);
    return NULL; /* no window: the engine decides what becomes of the URL */
}

void wv_client_connect(WebKitWebView *web, int64_t view)
{
    g_signal_connect(web, "script-dialog", G_CALLBACK(on_script_dialog), ID(view));
    g_signal_connect(web, "permission-request", G_CALLBACK(on_permission), ID(view));
    g_signal_connect(web, "run-file-chooser", G_CALLBACK(on_file_chooser), ID(view));
    g_signal_connect(web, "create", G_CALLBACK(on_create), ID(view));
}

void wv_client_disconnect(WebKitWebView *web, int64_t view)
{
    g_signal_handlers_disconnect_by_data(web, ID(view));
    if (!requests)
        return;
    GHashTableIter it;
    gpointer key, value;
    g_hash_table_iter_init(&it, requests);
    while (g_hash_table_iter_next(&it, &key, &value)) {
        WvRequest *r = value;
        if (r->view != view)
            continue;
        answer_by_default(r);
        g_hash_table_iter_remove(&it);
    }
}

void wv_client_answer(int64_t request, gboolean allow, const char *text, char **files)
{
    WvRequest *r = requests ? g_hash_table_lookup(requests, ID(request)) : NULL;
    if (!r)
        return;
    answer(r, allow, text, files);
    g_hash_table_remove(requests, ID(request));
}
