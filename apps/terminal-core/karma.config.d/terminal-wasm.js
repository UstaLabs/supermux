// :terminal-core:wasmJsBrowserTest — Karma additions (snippets in karma.config.d are concatenated
// verbatim into the generated karma.conf.js; leading `;` keeps IIFEs from being parsed as calls).
//
// - The engine module itself needs NO entry here: the test bundle imports terminal-loader.mjs (a
//   wasmJs resource), whose default `new URL("./supermux-terminal.wasm", import.meta.url)` makes
//   webpack emit the staged module as an asset that karma-webpack serves — the same path a host
//   app's bundler takes.
// - WasmRuntimeTest.corruptBinaryIsTyped needs a URL that answers 200 with bytes that are not a
//   wasm module: the loader's own source, proxied to /terminal-test/not-a-module.wasm.
// - Mocha's default 2 s per-test timeout is too tight for the multi-MiB fixtures on a loaded host.
;(function () {
  var path = require("path")
  config.files = config.files || []
  config.files.push({
    pattern: path.resolve(config.basePath, "kotlin/terminal-loader.mjs"),
    included: false, served: true, watched: false,
  })
  config.proxies = config.proxies || {}
  config.proxies["/terminal-test/not-a-module.wasm"] = "/base/kotlin/terminal-loader.mjs"
  config.mime = Object.assign({}, config.mime, { "application/wasm": ["wasm"] })
  config.client = config.client || {}
  config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 })
})();
