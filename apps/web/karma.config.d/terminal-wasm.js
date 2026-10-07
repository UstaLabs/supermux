// :web:wasmJsBrowserTest — serve the Ghostty engine as `application/wasm`.
//
// The web bundle now carries the terminal engine, and the loader compiles it with
// `WebAssembly.compileStreaming` ONLY when the response says `application/wasm`; anything else
// falls back to a buffered `WebAssembly.compile`. The fallback is correct, which is exactly the
// problem: without this line the browser tests would pass through a path production never takes,
// and the streaming path — the one the broker serves (`static-serve.ts` maps `.wasm` to
// `application/wasm`) — would be the only one nothing ever exercised.
//
// Karma's default mime table has no `.wasm` entry, so this mirrors
// `:terminal-core/karma.config.d/terminal-wasm.js`. Snippets in karma.config.d are concatenated
// verbatim into the generated karma.conf.js; the leading `;` keeps the IIFE from being parsed as a
// call on whatever the previous snippet ended with.
;(function () {
  config.mime = Object.assign({}, config.mime, { "application/wasm": ["wasm"] })
})();
