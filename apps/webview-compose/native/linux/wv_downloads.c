/* Downloads (#157): WebKit saves each with its page's network session (cookies, a form's POST, a blob:), where the JVM
 * says, and this file reports it. A download outlives its view: it is the process's, by an id of its own, and its
 * progress and states go to view 0. WebKitDownload can't pause and keep what arrived: a pause cancels it, and a resume
 * fetches the URL again from its session, to the same file. WebKit thread. */
#include "wv.h"
#include <glib/gstdio.h>
#include <string.h>

typedef struct {
    int64_t id;
    WebKitDownload *download;      /* a reference, until it ends; NULL while paused */
    WebKitNetworkSession *session; /* a reference: a resume fetches again from it */
    char *uri;
    char *path;                    /* where it goes, once the JVM said */
    gboolean pausing, cancelling, failed;
    guint64 reported;
} WvDownload;

static GHashTable *downloads;      /* id → WvDownload* */
static int64_t next_download = 1;
static int64_t restarting;         /* the id a resume's new WebKitDownload belongs to, while download_uri runs */

#define KEY(id) GSIZE_TO_POINTER((gsize)(id))

static void forget(WvDownload *d)
{
    if (d->download) {
        g_signal_handlers_disconnect_matched(d->download, G_SIGNAL_MATCH_DATA, 0, 0, NULL, NULL, d);
        g_object_unref(d->download);
    }
    g_hash_table_remove(downloads, KEY(d->id));
    g_object_unref(d->session);
    g_free(d->uri);
    g_free(d->path);
    g_free(d);
}

static void emit_state(WvDownload *d, int state, const char *message)
{
    char received[32];
    g_snprintf(received, sizeof received, "%" G_GUINT64_FORMAT,
        d->download ? webkit_download_get_received_data_length(d->download) : d->reported);
    wv_emit(0, WV_EV_DOWNLOAD_STATE, d->id, state, message, received);
}

static gboolean on_decide_destination(WebKitDownload *download, const char *suggested, WvDownload *d)
{
    if (d->path) { /* a resume, to the same file */
        g_unlink(d->path);
        webkit_download_set_destination(download, d->path);
        return TRUE;
    }
    WebKitWebView *web = webkit_download_get_web_view(download);
    int64_t view = web ? wv_web_id(web) : 0;
    if (!view) { /* its page has gone: nobody can say where it goes */
        d->cancelling = TRUE;
        webkit_download_cancel(download);
        return TRUE;
    }
    WebKitURIResponse *response = webkit_download_get_response(download);
    guint64 length = response ? webkit_uri_response_get_content_length(response) : 0;
    const char *mime = response ? webkit_uri_response_get_mime_type(response) : NULL;
    char *info = g_strdup_printf("%s\n%s", mime ? mime : "", suggested ? suggested : "");
    /* The JVM answers later (WV_OP_DOWNLOAD): WebKit waits for the destination while the handler returns TRUE. */
    wv_emit(view, WV_EV_DOWNLOAD, d->id, length > 0 ? (int64_t)length : -1, d->uri, info);
    g_free(info);
    return TRUE;
}

static void on_received(WebKitDownload *download, guint64 length, WvDownload *d)
{
    guint64 received = webkit_download_get_received_data_length(download);
    WebKitURIResponse *response = webkit_download_get_response(download);
    guint64 total = response ? webkit_uri_response_get_content_length(response) : 0;
    /* Every 64 KiB at most: WebKit reports per network read. */
    if (d->reported && received - d->reported < 64 * 1024 && (total == 0 || received < total))
        return;
    d->reported = received;
    char text[32];
    g_snprintf(text, sizeof text, "%" G_GINT64_FORMAT, total > 0 ? (gint64)total : (gint64)-1);
    wv_emit(0, WV_EV_DOWNLOAD_PROGRESS, d->id, (int64_t)received, text, NULL);
}

static void on_failed(WebKitDownload *download, GError *error, WvDownload *d)
{
    d->failed = TRUE;
    if (g_error_matches(error, WEBKIT_DOWNLOAD_ERROR, WEBKIT_DOWNLOAD_ERROR_CANCELLED_BY_USER)) {
        if (d->pausing) {
            emit_state(d, WV_DOWNLOAD_PAUSED, NULL);
            g_signal_handlers_disconnect_matched(download, G_SIGNAL_MATCH_DATA, 0, 0, NULL, NULL, d);
            g_object_unref(d->download);
            d->download = NULL;
        } else if (d->cancelling) {
            emit_state(d, WV_DOWNLOAD_CANCELLED, NULL);
            if (d->path)
                g_unlink(d->path);
            forget(d);
        }
        return;
    }
    emit_state(d, WV_DOWNLOAD_FAILED, error ? error->message : "Download failed");
    g_signal_handlers_disconnect_matched(download, G_SIGNAL_MATCH_DATA, 0, 0, NULL, NULL, d);
    g_object_unref(d->download);
    d->download = NULL;
}

static void on_finished(WebKitDownload *download, WvDownload *d)
{
    if (d->failed) /* "failed" came first: it has said what happened */
        return;
    emit_state(d, WV_DOWNLOAD_DONE, NULL);
    forget(d);
}

static void watch(WvDownload *d, WebKitDownload *download)
{
    d->download = g_object_ref(download);
    d->failed = FALSE;
    d->reported = 0;
    g_signal_connect(download, "decide-destination", G_CALLBACK(on_decide_destination), d);
    g_signal_connect(download, "received-data", G_CALLBACK(on_received), d);
    g_signal_connect(download, "failed", G_CALLBACK(on_failed), d);
    g_signal_connect(download, "finished", G_CALLBACK(on_finished), d);
}

static void on_started(WebKitNetworkSession *session, WebKitDownload *download, gpointer data)
{
    if (restarting) {
        WvDownload *d = g_hash_table_lookup(downloads, KEY(restarting));
        if (d)
            watch(d, download);
        return;
    }
    WvDownload *d = g_new0(WvDownload, 1);
    d->id = next_download++;
    d->session = g_object_ref(session);
    WebKitURIRequest *request = webkit_download_get_request(download);
    d->uri = g_strdup(request ? webkit_uri_request_get_uri(request) : "");
    g_hash_table_insert(downloads, KEY(d->id), d);
    watch(d, download);
}

void wv_downloads_watch(WebKitNetworkSession *session)
{
    if (!downloads)
        downloads = g_hash_table_new(g_direct_hash, g_direct_equal);
    if (g_object_get_data(G_OBJECT(session), "wv-downloads"))
        return;
    g_object_set_data(G_OBJECT(session), "wv-downloads", GINT_TO_POINTER(1));
    g_signal_connect(session, "download-started", G_CALLBACK(on_started), NULL);
}

void wv_download_op(int64_t id, int action, const char *path)
{
    WvDownload *d = downloads ? g_hash_table_lookup(downloads, KEY(id)) : NULL;
    if (!d)
        return;
    switch (action) {
    case WV_DOWNLOAD_ANSWER:
        if (!path || !*path) {
            d->cancelling = TRUE;
            if (d->download)
                webkit_download_cancel(d->download);
            return;
        }
        d->path = g_strdup(path);
        {
            char *dir = g_path_get_dirname(path);
            g_mkdir_with_parents(dir, 0700);
            g_free(dir);
        }
        g_unlink(path); /* WebKit won't overwrite: the JVM said to replace it */
        if (d->download)
            webkit_download_set_destination(d->download, path);
        break;
    case WV_DOWNLOAD_PAUSE:
        if (!d->download)
            return;
        d->pausing = TRUE;
        webkit_download_cancel(d->download);
        break;
    case WV_DOWNLOAD_RESUME: {
        if (d->download)
            return;
        d->pausing = FALSE;
        restarting = d->id;
        WebKitDownload *again = webkit_network_session_download_uri(d->session, d->uri);
        restarting = 0;
        if (again)
            g_object_unref(again);
        else
            emit_state(d, WV_DOWNLOAD_FAILED, "The download could not be resumed");
        break;
    }
    case WV_DOWNLOAD_CANCEL:
        d->cancelling = TRUE;
        if (d->download) {
            webkit_download_cancel(d->download);
        } else {
            emit_state(d, WV_DOWNLOAD_CANCELLED, NULL);
            if (d->path)
                g_unlink(d->path);
            forget(d);
        }
        break;
    }
}
