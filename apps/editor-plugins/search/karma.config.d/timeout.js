// :editor-plugins:search:wasmJsBrowserTest: Mocha's 2 s per-test timeout is too tight for the
// big-document cases in headless Chrome.
;(function () {
  config.client = config.client || {}
  config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 120000 })
  config.browserNoActivityTimeout = 300000
})();
