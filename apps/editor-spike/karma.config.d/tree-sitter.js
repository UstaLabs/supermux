// Serve the runtime + grammar wasm next to the test bundle at /ts/<file>.
;(function () {
  var path = require("path")
  config.files = config.files || []
  ;["tree-sitter.wasm", "tree-sitter-json.wasm"].forEach(function (f) {
    config.files.push({ pattern: path.resolve(config.basePath, "kotlin/" + f), included: false, served: true, watched: false })
  })
  config.proxies = Object.assign({}, config.proxies, { "/ts/": "/base/kotlin/" })
  config.mime = Object.assign({}, config.mime, { "application/wasm": ["wasm"] })
  config.client = config.client || {}
  config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 })
})();
