package dev.supermux.webview.engine.web

import dev.supermux.webview.bridge.BridgeShim
import kotlinx.serialization.json.JsonPrimitive

/**
 * The web engine's page shim: the common [BridgeShim], identical on every engine, over a channel of the web's own
 * (contract **Delivery**: "On the web, delivery uses a per-document `MessagePort` … The port belongs to the web
 * engine's own shim variant, which creates it and transfers it with its `$ready`").
 * - On its first send, the document's `$ready`, it creates a `MessageChannel` and posts
 *   `{webviewBridge: PROTOCOL, secret, text}` with the other end to its parent, aimed at the app's origin. Everything
 *   after that, both ways, goes over the port. Only this document holds it, and it dies with the document.
 * - The secret proves that a document is the one the engine started. A sandboxed srcdoc speaks as the opaque origin
 *   `"null"`, and so does any page it navigates to, so the engine trusts the secret, not the origin. Page scripts can't
 *   read the secret, because the shim's own `<script>` removes itself before they run.
 * - It turns a trusted click on a link that leaves the document into `{webviewOpen, newWindow}` on the port, so a demo's
 *   links go through the app's `WebClient` instead of navigating the demo. Synthetic clicks pass through untouched:
 *   the port is private to the shim, so a request on it always comes from a real click.
 */
internal object WebShim {
    /** The handshake's protocol number. `listenForHandshakes` in Dom.kt accepts this one only. */
    const val PROTOCOL: Int = 1

    /**
     * The shim for one document.
     * @param secret the document's secret: random, used once.
     * @param parentOrigin the app's origin (`location.origin`), the only place the handshake goes. An app with an
     *   opaque origin (served from a file) sends it to `*`: an iframe's parent can't change while the iframe lives.
     */
    fun source(secret: String, parentOrigin: String, callTimeoutMillis: Long = 30_000): String {
        val target = if (parentOrigin == "null" || parentOrigin.isEmpty()) "*" else parentOrigin
        val prelude = PRELUDE
            .replace("__PROTOCOL__", PROTOCOL.toString())
            .replace("__SECRET__", JsonPrimitive(secret).toString())
            .replace("__TARGET__", JsonPrimitive(target).toString())
        val shim = BridgeShim.source("link.send", mainFrameExpression = "window.parent !== window", callTimeoutMillis = callTimeoutMillis)
        return prelude + "\n" + shim + "\n" + LINKS + "\n" + EPILOGUE
    }

    private val PRELUDE = """
(function () {
  var self = document.currentScript;
  var link = (function () {
    var parentWindow = window.parent, Channel = MessageChannel, port = null;
    var SECRET = __SECRET__, TARGET = __TARGET__;
    return {
      send: function (text) {
        if (port !== null) { port.postMessage(text); return; }
        var channel = new Channel();
        parentWindow.postMessage({ webviewBridge: __PROTOCOL__, secret: SECRET, text: text }, TARGET, [channel.port2]);
        port = channel.port1;
        port.onmessage = function (e) {
          var receive = window.__hostBridgeReceive;
          if (typeof e.data === 'string' && typeof receive === 'function') receive(e.data);
        };
      },
      open: function (url, newWindow) {
        if (port !== null) port.postMessage({ webviewOpen: String(url), newWindow: newWindow === true });
      }
    };
  })();
""".trim()

    private val LINKS = """
  document.addEventListener('click', function (e) {
    if (!e.isTrusted || e.defaultPrevented || e.button !== 0) return;
    var node = e.target;
    while (node && node.nodeType === 1 && node.localName !== 'a' && node.localName !== 'area') node = node.parentNode;
    if (!node || node.nodeType !== 1 || !node.hasAttribute('href')) return;
    var href = String(node.href);
    if (href === '' || href.indexOf('javascript:') === 0) return;
    if (href.split('#')[0] === String(location.href).split('#')[0]) return;
    e.preventDefault();
    var target = node.getAttribute('target');
    link.open(href, (target !== null && target !== '' && target.toLowerCase() !== '_self') || e.ctrlKey || e.metaKey || e.shiftKey);
  }, true);
""".trim()

    private val EPILOGUE = """
  if (self && self.parentNode) self.parentNode.removeChild(self);
})();
""".trim()
}
