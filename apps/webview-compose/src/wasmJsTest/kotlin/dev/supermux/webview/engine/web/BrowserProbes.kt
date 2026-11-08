package dev.supermux.webview.engine.web

/**
 * What hit testing finds at window point ([x], [y]), the way a click would: `"compose"` for the canvas's shadow host,
 * `"iframe:<data-test-name or name>"` for an iframe, `"none"` outside the page, and otherwise the tag name.
 */
internal fun hitAt(x: Double, y: Double): String = js("""(function () {
  var el = document.elementFromPoint(x, y);
  if (!el) return 'none';
  if (el.shadowRoot && el.shadowRoot.querySelector('canvas')) return 'compose';
  if (el.localName === 'iframe') return 'iframe:' + (el.getAttribute('data-test-name') || el.getAttribute('name') || '');
  return el.localName;
})()""")

/**
 * A mouse click at window point ([x], [y]) on the test viewport's canvas: `pointerdown`, then `pointerup`. Compose
 * handles synthetic pointer events (plan fact F5). Use it only where hit testing would reach the canvas: it bypasses
 * hit testing.
 */
internal fun clickCompose(x: Double, y: Double): Unit = js("""{
  var host = document.querySelector('[data-test-viewport]').firstElementChild.firstElementChild;
  var canvas = host.shadowRoot.querySelector('canvas');
  var init = { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, pointerId: 1, pointerType: 'mouse', isPrimary: true, button: 0, buttons: 1 };
  canvas.dispatchEvent(new PointerEvent('pointerdown', init));
  init.buttons = 0;
  canvas.dispatchEvent(new PointerEvent('pointerup', init));
}""")
