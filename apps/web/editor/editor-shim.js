// Host bridge for the iframe editor, loaded INSIDE `editor/index.html` before `cm6.js` (the staged
// `index.html` gets this `<script>` injected by :web's stageForBroker).
//
// WHY THIS IS ONLY TWENTY LINES: the committed cm6 bundle looks for `window.AndroidEditor` (10
// hooks) and `window.webkit.messageHandlers.lsp.postMessage`, but :ui's `bridgeShimJs(queryFn)`
// ALREADY defines both of those globals in terms of `window[queryFn]`, and the web engine evals
// `bridgeShimJs("smxEditorQuery") + cmInit(...)` into this page exactly as desktop's JCEF engine
// does. So the ONLY things this page has to supply are the two ends of the transport that JCEF's
// message router gives desktop for free:
//
//   1. `window.smxEditorQuery({request})` — outbound. Posts the request string (the same
//      `{fn,arg}` JSON desktop/Android hosts receive, parsed by `parseBridgeEvent`) to the parent.
//   2. a `message` listener — inbound. The parent has no `contentWindow.eval` across the frame in
//      a way Kotlin/Wasm can reach, so every `executeJavaScript` becomes a `postMessage`
//      ("__smxEval:" + code) that lands here and runs through `(0, eval)`.
//
// Defining `AndroidEditor`/`webkit` here too would duplicate — and risk drifting from — the shared
// shim that is unit-tested in `:ui`. This file is deliberately the transport only.
//
// SECURITY: this file hands `eval` to whatever is on the other end of the channel, so both
// directions are pinned to the frame's own origin. Outbound, `postMessage(..., origin)` refuses to
// deliver if the parent document is not that origin. Inbound, `ev.source === window.parent` alone
// is NOT enough — a cross-origin parent is still `window.parent`, and could post us anything — so
// `ev.origin` is checked against the same origin before the payload is even looked at. Together
// with the broker's `frame-ancestors 'self'` (src/channels/web/static-serve.ts), which stops a
// foreign page from framing us in the first place, the only document that can reach the eval is
// the app itself. The eval'd text is then always built by `EditorBridge.kt` from values the host
// already holds.
(function () {
  // The staged page is same-origin with the app, so this is the app's own origin and both
  // directions are pinned to it. The `*` fallback covers an OPAQUE origin — a sandboxed or
  // `srcdoc` frame, which is how the Karma test mounts this page: `location.origin` is then the
  // string "null", which matches nothing and would silently swallow every message. That fallback
  // also drops the inbound origin check, because an opaque-origin frame has no origin to compare
  // against; it exists for the test harness, and the staged page never takes it.
  var origin = window.location.origin && window.location.origin !== "null" ? window.location.origin : "*";

  // Outbound: JCEF's `CefMessageRouter` query function, re-implemented as a post to the parent.
  // The `{request}` argument shape is CEF's, kept verbatim so `bridgeShimJs` needs no web variant.
  window.smxEditorQuery = function (q) {
    try {
      window.parent.postMessage(String(q && q.request != null ? q.request : ""), origin);
    } catch (e) {}
  };
  // CEF's cancel half of the router pair. The shared shim never calls it; present so a future
  // `bridgeShimJs` that does cannot throw in here.
  window.smxEditorQueryCancel = function () {};

  // Inbound: run JS the host built. The sender must be our parent AND our own origin, and the
  // payload must be a string carrying our prefix — so unrelated `postMessage` traffic (dev
  // servers, extensions) and any cross-origin framer can never reach the eval.
  window.addEventListener("message", function (ev) {
    if (ev.source !== window.parent) return;
    if (origin !== "*" && ev.origin !== origin) return;
    if (typeof ev.data !== "string" || ev.data.indexOf("__smxEval:") !== 0) return;
    try {
      (0, eval)(ev.data.slice(10));
    } catch (e) {
      // Report through the same channel rather than dying silently: `parseBridgeEvent` returns null
      // for the unknown `fn` and the engine logs the payload, which is exactly what we want to see.
      try {
        window.smxEditorQuery({ request: JSON.stringify({ fn: "evalError", arg: String(e) }) });
      } catch (e2) {}
    }
  });
})();
