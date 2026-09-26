// :editor-syntax:wasmJsBrowserTest — Karma additions (snippets in karma.config.d are concatenated
// verbatim into the generated karma.conf.js; the leading `;` keeps the IIFE from being parsed as a call).
//
// - The wasm module needs no entry: the test bundle imports syntax-loader.mjs (a wasmJs resource),
//   whose default `new URL("./supermux-syntax.wasm", import.meta.url)` makes webpack emit the staged
//   module as an asset that karma-webpack serves, the path a host app's bundler takes too.
// - The test resources (goldens, test tables, the app's tables: build.gradle.kts
//   stageWasmTestResources) are served, not bundled, under /base/kotlin/; syntax-test-setup.mjs
//   fetches every file syntax-test-resources.json lists before the tests run.
// - Mocha's 2 s per-test timeout is far too tight for the 10k-line performance cases.
;(function () {
  var path = require("path")
  config.files = config.files || []
  ;["syntax-test-resources.json", "golden/**/*", "sesz/**/*", "editor-syntax/**/*"].forEach(function (p) {
    config.files.push({ pattern: path.resolve(config.basePath, "kotlin/" + p), included: false, served: true, watched: false, nocache: true })
  })
  config.mime = Object.assign({}, config.mime, { "application/wasm": ["wasm"], "application/octet-stream": ["sesz"] })
  config.client = config.client || {}
  config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 900000 })
  config.browserNoActivityTimeout = 900000
  config.browserDisconnectTimeout = 900000
  config.pingTimeout = 900000
})();
