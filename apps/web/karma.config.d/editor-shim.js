// Serve `apps/web/editor/editor-shim.js` to EditorBridgeIframeTest.
//
// Karma only serves what is in `config.files`, and the Kotlin plugin's generated config lists just
// the test bundle. The shim reaches the run as a wasmJsTest RESOURCE (see the `editorShimTestResource`
// Copy task in build.gradle.kts), which KGP places next to the compiled test output under the run's
// base path — so it exists on disk here but is invisible over HTTP until it is registered.
//
// `included: false` — it must not be injected as a <script> into the Karma context; the test fetches
// its TEXT and injects it into an iframe's srcdoc. The proxy gives it the short, stable URL the test
// asks for, independent of where KGP decides to put processed resources.
// Leading `;`: karma.config.d snippets are concatenated verbatim, so an IIFE that follows
// another one without a separator is parsed as a CALL of its result.
;(function () {
  var path = require("path")
  var file = path.resolve(config.basePath, "kotlin/editor-shim.js")
  config.files = config.files || []
  config.files.push({ pattern: file, included: false, served: true, watched: false })
  config.proxies = config.proxies || {}
  config.proxies["/editor-shim.js"] = "/base/kotlin/editor-shim.js"
})();
