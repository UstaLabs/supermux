package dev.supermux.webview.engine.web

import org.w3c.dom.HTMLElement

// The web engine's only contact with the DOM, as js() helpers. Kotlin/Wasm allows js() only as a whole top-level
// function body. None of these throws on a dead element or a closed port.

/** The app's origin, as the browser writes it (`"null"` for an opaque one). */
internal fun pageOrigin(): String = js("String(window.location.origin)")

/**
 * The origin of [url] as the browser's own URL parser reads it (`new URL(url).origin`), or `"null"` when it can't
 * parse it. It normalizes what a hand-written parser may miss: `:0443`, `%61pp`, IPv4 in hex, IDN.
 */
internal fun browserOriginOf(url: String): String =
    js("(function () { try { return String(new URL(url).origin); } catch (e) { return 'null'; } })()")

/**
 * The URL of [iframe]'s document if the app can read it, which it can only on its own origin; else null. A frame of
 * another origin reads as null.
 */
internal fun sameOriginDocumentUrl(iframe: HTMLElement): String? =
    js("(function () { try { var d = iframe.contentDocument; return d ? String(d.URL) : null; } catch (e) { return null; } })()")

/** Milliseconds on the page's clock. */
internal fun nowMillis(): Double = js("performance.now()")

internal fun newElement(tag: String): HTMLElement = js("document.createElement(tag)")

internal fun styleOf(element: HTMLElement, name: String, value: String): Unit = js("{ element.style.setProperty(name, value); }")

internal fun unstyle(element: HTMLElement, name: String): Unit = js("{ element.style.removeProperty(name); }")

internal fun styleValue(element: HTMLElement, name: String): String = js("element.style.getPropertyValue(name)")

internal fun attribute(element: HTMLElement, name: String, value: String): Unit = js("{ element.setAttribute(name, value); }")

internal fun hasAttribute(element: HTMLElement, name: String): Boolean = js("element.hasAttribute(name)")

internal fun setSrcdoc(iframe: HTMLElement, html: String): Unit = js("{ iframe.srcdoc = html; }")

internal fun setSrc(iframe: HTMLElement, url: String): Unit = js("{ iframe.src = url; }")

/** The iframe's WindowProxy, which stays the same across its navigations. */
internal fun windowOf(iframe: HTMLElement): JsAny? = js("iframe.contentWindow")

/** JS identity (`===`), for JS objects whose Kotlin wrappers may differ. */
internal fun same(a: JsAny?, b: JsAny?): Boolean = js("a === b")

internal fun connected(element: HTMLElement): Boolean = js("element.isConnected === true")

internal fun parentOf(element: HTMLElement): HTMLElement? = js("element.parentElement")

internal fun nextSiblingOf(element: HTMLElement): HTMLElement? = js("element.nextElementSibling")

internal fun addChild(parent: HTMLElement, child: HTMLElement): Unit = js("{ parent.appendChild(child); }")

/** Inserts [child] into [parent] before [before], or last when [before] is null. */
internal fun insertChild(parent: HTMLElement, child: HTMLElement, before: HTMLElement?): Unit =
    js("{ parent.insertBefore(child, before); }")

internal fun removeElement(element: HTMLElement): Unit = js("{ if (element.parentNode) element.parentNode.removeChild(element); }")

/** 128 random bits, as 32 hex digits. */
internal fun randomHex(): String = js("""(function () {
  var a = new Uint32Array(4);
  crypto.getRandomValues(a);
  var s = '';
  for (var i = 0; i < 4; i++) s += ('00000000' + a[i].toString(16)).slice(-8);
  return s;
})()""")

internal fun onFrameLoad(iframe: HTMLElement, handler: () -> Unit): Unit =
    js("{ iframe.addEventListener('load', function () { handler(); }); }")

/** Runs [callback] in the next animation frame. */
internal fun afterNextFrame(callback: () -> Unit): Unit = js("{ requestAnimationFrame(function () { callback(); }); }")

/** Runs [callback] after [millis] milliseconds. */
internal fun afterMillis(millis: Int, callback: () -> Unit): Unit = js("{ setTimeout(function () { callback(); }, millis); }")

/**
 * Whether the app's window has transient activation (`navigator.userActivation.isActive`): a click or key press in it,
 * or in any frame inside it, in the last few seconds. Browsers that run Compose for Web all have the API.
 */
internal fun hasTransientActivation(): Boolean =
    js("!!(navigator.userActivation && navigator.userActivation.isActive)")

internal fun focusElement(element: HTMLElement): Unit = js("{ element.focus(); }")

internal fun isActiveElement(element: HTMLElement): Boolean = js("document.activeElement === element")

/**
 * Calls [handler] with each bridge handshake posted to this window, as `{source, origin, secret, text, port}`: an
 * object with `webviewBridge` of [WebShim.PROTOCOL] (1), a string secret and text, and exactly one port.
 */
internal fun listenForHandshakes(handler: (JsAny) -> Unit): Unit = js("""{
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object' || d.webviewBridge !== 1) return;
    if (typeof d.secret !== 'string' || typeof d.text !== 'string' || !e.ports || e.ports.length !== 1) return;
    handler({ source: e.source, origin: String(e.origin), secret: d.secret, text: d.text, port: e.ports[0] });
  });
}""")

internal fun handshakeSource(handshake: JsAny): JsAny? = js("handshake.source")

internal fun handshakeText(handshake: JsAny, key: String): String = js("String(handshake[key])")

internal fun handshakePort(handshake: JsAny): JsAny = js("handshake.port")

/**
 * Listens on a document's port. A string is a bridge message, `{webviewOpen, newWindow}` a link the user clicked, and
 * `{webviewScroll: [dx, dy]}` and `{webviewFling: [vx, vy]}` what the user's wheel or drag left over (CSS px, and CSS px
 * per second; positive down and right). Anything else, or a pair that isn't two finite numbers, is dropped.
 */
internal fun listenOnPort(
    port: JsAny,
    onText: (String) -> Unit,
    onOpen: (String, Boolean) -> Unit,
    onScroll: (Double, Double) -> Unit,
    onFling: (Double, Double) -> Unit,
): Unit = js("""{
  function pair(a) { return Array.isArray(a) && a.length === 2 && typeof a[0] === 'number' && typeof a[1] === 'number' && isFinite(a[0]) && isFinite(a[1]); }
  port.onmessage = function (e) {
    var d = e.data;
    if (typeof d === 'string') onText(d);
    else if (d && typeof d === 'object') {
      if (typeof d.webviewOpen === 'string') onOpen(d.webviewOpen, d.newWindow === true);
      else if (pair(d.webviewScroll)) onScroll(d.webviewScroll[0], d.webviewScroll[1]);
      else if (pair(d.webviewFling)) onFling(d.webviewFling[0], d.webviewFling[1]);
    }
  };
}""")

internal fun postToPort(port: JsAny, text: String): Unit = js("{ try { port.postMessage(text); } catch (e) {} }")

internal fun closePort(port: JsAny): Unit = js("{ try { port.onmessage = null; port.close(); } catch (e) {} }")

/** Calls [onChange] whenever keyboard focus may have moved into, out of, or between iframes. */
internal fun listenForFocusChanges(onChange: () -> Unit): Unit = js("""{
  window.addEventListener('blur', function () { setTimeout(function () { onChange(); }, 0); });
  window.addEventListener('focus', function () { onChange(); });
  document.addEventListener('focusin', function () { onChange(); }, true);
  document.addEventListener('focusout', function () { setTimeout(function () { onChange(); }, 0); }, true);
}""")

/** Whether `clip-path: path(evenodd, …)` is supported (Safari < 13.1 and old Chromium lack it). */
internal fun supportsClipPathPath(): Boolean =
    js("typeof CSS !== 'undefined' && CSS.supports('clip-path', 'path(evenodd, \"M0 0H1V1Z\")')")
