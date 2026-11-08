package dev.supermux.web.seams

import dev.supermux.state.BrowserDownload
import dev.supermux.state.browserDownloadTotal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.files.Blob
import kotlin.coroutines.resume

/**
 * The web's downloads (#95), which have no files to land in. The browser's own `fetch` — with the host's auth header,
 * and the page's cookie as every request from this page carries it — streams the answer into a `Blob` the browser
 * holds: the bytes never cross into the Wasm heap, which a whole-body fetch would fill and a big file would run out
 * of. Progress comes from the chunk sizes as they arrive, against the total [browserDownloadTotal] allows.
 *
 * The answer is the Blob, opaque to `:shared` (it becomes the `DownloadedFile.handle` that [WebFiles] hands over), and
 * its size; null for a non-2xx answer or a network error. Cancelling the caller (the bar's Cancel, the fleet closing)
 * aborts the fetch, so the browser stops downloading too.
 */
object WebDownload : BrowserDownload {
    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        progress: (received: Long, total: Long?) -> Unit,
    ): Pair<Any, Long>? = suspendCancellableCoroutine { cont ->
        var total: Long? = null
        val transfer = startDownloadJs(
            url = url,
            headersJson = JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString(),
            onHead = { length, encoding -> total = browserDownloadTotal(length, encoding) },
            onChunk = { received -> if (cont.isActive) progress(received.toLong(), total) },
            onDone = { blob -> if (cont.isActive) cont.resume(blob?.let { it to it.size.toDouble().toLong() }) },
        )
        cont.invokeOnCancellation { abortDownloadJs(transfer) }
    }
}

/**
 * `fetch(url, { headers })` read chunk by chunk into a Blob. [onHead] hears the length and encoding headers once the
 * answer starts, [onChunk] the bytes so far after each chunk, [onDone] the Blob (typed as the answer's content type)
 * or null for a non-2xx answer, a network error or an abort — once. Answers the `AbortController` that stops it, null
 * where the browser has none.
 *
 * Every 8 MiB the chunks so far are folded into a Blob: a big download is then held by the browser's blob store,
 * which can keep it on disk, rather than as thousands of arrays in the page; a Blob of Blobs copies nothing.
 */
@Suppress("UNUSED_PARAMETER")
private fun startDownloadJs(
    url: String,
    headersJson: String,
    onHead: (String?, String?) -> Unit,
    onChunk: (Double) -> Unit,
    onDone: (Blob?) -> Unit,
): JsAny? = js(
    """{
      var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
      try {
        var init = { headers: JSON.parse(headersJson), credentials: 'same-origin' };
        if (ctrl) init.signal = ctrl.signal;
        fetch(url, init).then(function (r) {
          if (!r.ok) { onDone(null); return; }
          var type = r.headers.get('content-type') || '';
          onHead(r.headers.get('content-length'), r.headers.get('content-encoding'));
          if (!r.body || !r.body.getReader) {
            return r.blob().then(function (b) { onChunk(b.size); onDone(b); });
          }
          var reader = r.body.getReader();
          var parts = [], pending = [], pendingBytes = 0, received = 0;
          function step() {
            return reader.read().then(function (chunk) {
              if (chunk.done) {
                if (pending.length) parts.push(new Blob(pending));
                onDone(new Blob(parts, { type: type }));
                return;
              }
              pending.push(chunk.value);
              pendingBytes += chunk.value.byteLength;
              received += chunk.value.byteLength;
              if (pendingBytes >= 8388608) { parts.push(new Blob(pending)); pending = []; pendingBytes = 0; }
              onChunk(received);
              return step();
            });
          }
          return step();
        }).catch(function () { onDone(null); });
      } catch (e) { onDone(null); }
      return ctrl;
    }""",
)

/** Stop a [startDownloadJs] transfer; its `onDone(null)` then comes to a caller that has stopped listening. */
@Suppress("UNUSED_PARAMETER")
private fun abortDownloadJs(ctrl: JsAny?): Unit = js("{ try { if (ctrl) ctrl.abort(); } catch (e) {} }")
