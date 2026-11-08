// :webview-compose:wasmJsBrowserTest — Karma additions (snippets in karma.config.d are concatenated verbatim into
// the generated karma.conf.js; the leading `;` keeps the IIFE from being parsed as a call).
// - Mocha's 2 s default is too tight for the bridge tests that wait on real timers, and the web engine's tests mount a
//   real Compose viewport and wait for iframes: 2 minutes a test. Karma's own inactivity timeouts follow.
// - The feasibility suites (classes named *SpikeTest) measure for minutes. They run only when the build passes
//   -Pwebview.spike=true, which build.gradle.kts turns into WEBVIEW_SPIKE=1 for this process.
// - A 1280×1024 window: the test viewport is 800×600 at the top-left, and a few probes sit below it.
// - Software WebGL: ChromeHeadless runs with --disable-gpu, and Chrome (156, measured) then gives pages no WebGL
//   unless --enable-unsafe-swiftshader allows its software fallback. Without it skiko gets no GL context and Compose
//   never draws.
;(function () {
  config.client = config.client || {}
  var mocha = Object.assign({}, config.client.mocha, { timeout: 120000 })
  if (process.env.WEBVIEW_SPIKE !== '1') {
    mocha.grep = 'SpikeTest'
    mocha.invert = true
  }
  config.client.mocha = mocha
  config.browserNoActivityTimeout = 600000
  config.browserDisconnectTimeout = 60000
  config.customLaunchers = Object.assign({}, config.customLaunchers, {
    ChromeHeadlessWebview: { base: 'ChromeHeadless', flags: ['--window-size=1280,1024', '--enable-unsafe-swiftshader'] },
  })
  config.browsers = (config.browsers || []).map(function (b) { return b === 'ChromeHeadless' ? 'ChromeHeadlessWebview' : b })
})();
