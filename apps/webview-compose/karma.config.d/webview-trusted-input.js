// :webview-compose:wasmJsBrowserTest — real, trusted input for the click-to-send tests. A page's own el.click() or
// dispatchEvent is never trusted, so a test can't make a trusted click from JavaScript; the browser makes one when the
// DevTools protocol dispatches input, as Playwright does in the eval harness (evals/show-html/click.test.ts).
// - Chrome listens for the protocol on a random local port (two runs at once don't collide).
// - The Karma server answers, under /webview-test/:
//     /webview-test/click?x=<x>&y=<y>   a left click at (x, y), CSS px in the top page's viewport
//     /webview-test/key?key=Enter|Space a key press, to whatever has the focus
//   each once Chrome has dispatched it, with "ok"; or 500 and the error.
// The leading `;` keeps the IIFE from being parsed as a call on the snippet before it.
;(function () {
  var port = 20000 + Math.floor(Math.random() * 20000)
  config.customLaunchers = config.customLaunchers || {}
  var launcher = config.customLaunchers.ChromeHeadlessWebview ||
    (config.customLaunchers.ChromeHeadlessWebview = { base: 'ChromeHeadless', flags: [] })
  launcher.flags = (launcher.flags || []).concat(['--remote-debugging-port=' + port, '--remote-allow-origins=*'])
  config.browsers = (config.browsers || []).map(function (b) { return b === 'ChromeHeadless' ? 'ChromeHeadlessWebview' : b })

  // The Karma page: the top-level page Chrome was opened on (the tests run in its context frame).
  function target() {
    return fetch('http://127.0.0.1:' + port + '/json/list').then(function (r) { return r.json() }).then(function (list) {
      var page = list.filter(function (t) { return t.type === 'page' && /^https?:/.test(t.url) && t.url.indexOf('/debug.html') < 0 })[0]
      if (!page) throw new Error('no page target among ' + list.length)
      return page.webSocketDebuggerUrl
    })
  }
  function send(commands) {
    return target().then(function (url) {
      return new Promise(function (resolve, reject) {
        var ws = new WebSocket(url), next = 0
        function step() {
          if (next >= commands.length) { ws.close(); resolve(); return }
          ws.send(JSON.stringify({ id: next + 1, method: commands[next].method, params: commands[next].params }))
        }
        ws.onopen = step
        ws.onerror = function (e) { reject(new Error('DevTools socket: ' + (e && e.message || e))) }
        ws.onmessage = function (m) {
          var reply = JSON.parse(String(m.data))
          if (reply.id !== next + 1) return
          if (reply.error) { ws.close(); reject(new Error(JSON.stringify(reply.error))); return }
          next++
          step()
        }
      })
    })
  }
  var KEYS = {
    Enter: { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13, text: '\r' },
    Space: { key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32, text: ' ' },
  }
  function trustedInput() {
    return function (req, res, next) {
      var url = new URL(req.url, 'http://localhost')
      var commands
      if (url.pathname === '/webview-test/click') {
        var x = Number(url.searchParams.get('x')), y = Number(url.searchParams.get('y'))
        var at = { x: x, y: y, button: 'left', clickCount: 1 }
        commands = [
          { method: 'Input.dispatchMouseEvent', params: Object.assign({ type: 'mouseMoved' }, at, { button: 'none', clickCount: 0 }) },
          { method: 'Input.dispatchMouseEvent', params: Object.assign({ type: 'mousePressed' }, at) },
          { method: 'Input.dispatchMouseEvent', params: Object.assign({ type: 'mouseReleased' }, at) },
        ]
      } else if (url.pathname === '/webview-test/key') {
        var key = KEYS[url.searchParams.get('key')]
        if (!key) { res.writeHead(400); res.end('unknown key'); return }
        commands = [
          { method: 'Input.dispatchKeyEvent', params: Object.assign({ type: 'keyDown' }, key) },
          { method: 'Input.dispatchKeyEvent', params: Object.assign({ type: 'keyUp' }, key, { text: undefined }) },
        ]
      } else {
        next()
        return
      }
      send(commands).then(function () {
        res.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' })
        res.end('ok')
      }, function (e) {
        res.writeHead(500, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' })
        res.end(String(e && e.message || e))
      })
    }
  }
  config.plugins = config.plugins || []
  config.plugins.push({ 'middleware:webviewTrustedInput': ['factory', trustedInput] })
  config.middleware = (config.middleware || []).concat(['webviewTrustedInput'])
})();
