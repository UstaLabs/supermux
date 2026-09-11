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
// SECURITY: both directions are pinned to the frame's own origin. The page is same-origin with the
// host app (served from `/editor/` by the same broker), the eval'd text is always built by
// `EditorBridge.kt` from values the host already holds, and a cross-origin frame could neither
// receive these posts nor send one that passes the `ev.source !== window.parent` check.
(function () {
  // The staged page is same-origin with the app, so this is the app's own origin and both
  // directions are pinned to it. The `*` fallback covers an OPAQUE origin — a sandboxed or
  // `srcdoc` frame, which is how the Karma test mounts this page: `location.origin` is then the
  // string "null", which matches nothing and would silently swallow every message.
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

  // Inbound: run JS the host built. Anything that is not a string with our prefix is ignored, so
  // unrelated `postMessage` traffic (dev servers, extensions) can never reach the eval.
  window.addEventListener("message", function (ev) {
    if (ev.source !== window.parent) return;
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
