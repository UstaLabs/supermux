// :webview-compose:wasmJsBrowserTest — pages the web engine's tests load from the Karma server itself, under
// /webview-test/. The server answers on two names, localhost and 127.0.0.1, which are two sites to the browser, so a
// test can load a page of another site, or of its own origin.
// - /webview-test/page?id=<id>         a small HTML page; its requests are counted per id
// - /webview-test/focus                a page with an input it focuses when a message 'focus' comes
// - /webview-test/hits?id=<id>         how many times that page was asked for, as text
// - /webview-test/redirect?to=<url>    a 302 to <url>
// - /webview-test/slow?ms=<ms>         a 1×1 SVG image, sent after <ms> milliseconds
// The leading `;` keeps the IIFE from being parsed as a call on the snippet before it.
;(function () {
  var hits = Object.create(null)
  function webviewTestPages() {
    return function (req, res, next) {
      var url = new URL(req.url, 'http://localhost')
      var query = url.searchParams
      switch (url.pathname) {
        case '/webview-test/page': {
          var id = query.get('id') || ''
          hits[id] = (hits[id] || 0) + 1
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
          res.end('<!doctype html><html><body style="margin:0">a test page</body></html>')
          return
        }
        case '/webview-test/focus':
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
          res.end('<!doctype html><html><body style="margin:0"><input id="i"><script>' +
            'addEventListener("message", function (e) { if (e.data === "focus") document.getElementById("i").focus(); });' +
            '</script></body></html>')
          return
        case '/webview-test/hits':
          res.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' })
          res.end(String(hits[query.get('id') || ''] || 0))
          return
        case '/webview-test/redirect':
          res.writeHead(302, { Location: query.get('to') || '/', 'Cache-Control': 'no-store' })
          res.end()
          return
        case '/webview-test/slow':
          setTimeout(function () {
            res.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'no-store' })
            res.end('<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>')
          }, Number(query.get('ms')) || 3000)
          return
        default:
          next()
      }
    }
  }
  config.plugins = config.plugins || []
  config.plugins.push({ 'middleware:webviewTestPages': ['factory', webviewTestPages] })
  config.middleware = (config.middleware || []).concat(['webviewTestPages'])
})();
