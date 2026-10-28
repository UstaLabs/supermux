// :webview-compose:wasmJsBrowserTest — Karma additions (snippets in karma.config.d are concatenated verbatim into
// the generated karma.conf.js; the leading `;` keeps the IIFE from being parsed as a call).
// Mocha's default 2 s per-test timeout is too tight for the bridge tests that wait on real timers (console-flood
// summaries, page-side call timeouts) on a loaded machine.
;(function () {
  config.client = config.client || {}
  config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 20000 })
})();
